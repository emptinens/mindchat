package com.mindchat.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import com.mindchat.core.FfiConnectionState
import com.mindchat.core.FfiContactPresence
import com.mindchat.core.FfiConversationKind
import com.mindchat.core.FfiCoreSnapshot
import com.mindchat.core.FfiDeliveryState
import com.mindchat.core.FfiMessageDirection
import com.mindchat.core.FfiProtocolCapability
import com.mindchat.core.MindChatBindingException
import com.mindchat.core.MindChatCoreHandle
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Presence { ONLINE, AWAY, DO_NOT_DISTURB, OFFLINE }

enum class MessageDelivery { PENDING, SENT, DELIVERED, READ, FAILED }

enum class AccountConnectionState { OFFLINE, CONNECTING, ONLINE, FAILED }

data class AccountUi(
    val id: Long,
    val jid: String,
    val displayName: String,
    val presence: Presence,
    val connectionState: AccountConnectionState = AccountConnectionState.OFFLINE,
    val supportsGroupChats: Boolean = false,
    val connectionError: String? = null,
)

data class ContactUi(
    val accountId: Long,
    val jid: String,
    val displayName: String,
    val presence: Presence,
    val status: String? = null,
)

data class ConversationUi(
    val id: Long,
    val accountId: Long,
    val title: String,
    val address: String,
    val preview: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val encrypted: Boolean = false,
    val isGroup: Boolean = false,
    val lastActivityEpochMs: Long = 0,
)

data class MessageUi(
    val id: Long,
    val sender: String,
    val body: String,
    val timestamp: String,
    val mine: Boolean,
    val delivery: MessageDelivery? = null,
    val reactions: List<String> = emptyList(),
)

data class MindChatUiState(
    val accounts: List<AccountUi>,
    val contacts: List<ContactUi>,
    val activeAccountId: Long,
    val conversations: List<ConversationUi>,
    val messagesByConversation: Map<Long, List<MessageUi>>,
    val dynamicColor: Boolean = true,
    val comfortableLayout: Boolean = true,
    val appLockEnabled: Boolean = false,
)

private data class TransportPollResult(
    val snapshot: FfiCoreSnapshot,
    val flushedAccounts: Set<Long>,
)

/**
 * Tracks mutations independently from completed persistence snapshots.
 *
 * A save only acknowledges the epoch captured before its native snapshot. A
 * concurrent later mutation therefore remains dirty even if an older writer
 * completes after it.
 */
internal class PersistenceStateTracker {
    private val mutationEpoch = AtomicLong(0)
    private val persistedEpoch = AtomicLong(0)

    fun markMutation() {
        mutationEpoch.incrementAndGet()
    }

    fun requiresSave(): Boolean = mutationEpoch.get() != persistedEpoch.get()

    fun captureSaveEpoch(): Long = mutationEpoch.get()

    fun markPersisted(epoch: Long) {
        while (true) {
            val current = persistedEpoch.get()
            if (current >= epoch || persistedEpoch.compareAndSet(current, epoch)) return
        }
    }
}

interface MindChatGateway {
    val state: MindChatUiState

    fun selectAccount(accountId: Long)
    fun addAccount(jid: String, server: String, displayName: String, password: String): Boolean
    fun reconnectAccount(accountId: Long, password: String): Boolean
    fun addContact(jid: String, displayName: String)
    fun openConversation(address: String, title: String, group: Boolean): Long?
    fun sendText(conversationId: Long, text: String)
    suspend fun pollTransport()
    suspend fun persistNow()
    fun toggleDynamicColor()
    fun toggleComfortableLayout()
    fun toggleAppLock()
}

/**
 * Chooses the generated Rust binding for packaged builds. The preview fallback
 * keeps Compose previews and isolated debug UI tests available when an Android
 * ABI library is intentionally absent.
 */
object MindChatGatewayFactory {
    fun create(context: Context): MindChatGateway {
        val preferences = SharedPreferencesMindChatPreferences(context)
        return try {
            NativeMindChatGateway(
                stateFile = File(context.filesDir, "mindchat_state.json"),
                preferences = preferences,
            )
        } catch (error: LinkageError) {
            if (BuildConfig.DEBUG) PreviewMindChatGateway(preferences) else throw error
        }
    }
}

/** Presentation adapter over the generated UniFFI contract. */
@Stable
class NativeMindChatGateway(
    private val core: MindChatCoreHandle = MindChatCoreHandle(),
    private val stateFile: File,
    private val preferences: MindChatPreferences = InMemoryMindChatPreferences(),
) : MindChatGateway {
    private var activeAccountId = 0L
    private var customization = preferences.readCustomization()
    private val pendingOutboxAccounts = mutableSetOf<Long>()

    /** Serializes snapshots written by polling and lifecycle shutdown. */
    private val persistenceMutex = Mutex()
    private val persistenceTracker = PersistenceStateTracker()

    override var state by mutableStateOf(snapshotToUiState())
        private set

    init {
        // Restore durable state once at startup. The bounded JSON file is
        // parsed by the native core before Compose starts rendering. A failure
        // is quarantined so a future successful save can start a clean state.
        restoreState()
        refresh()
    }

    override fun selectAccount(accountId: Long) {
        if (state.accounts.any { it.id == accountId }) {
            activeAccountId = accountId
            refresh()
        }
    }

    override fun addAccount(
        jid: String,
        server: String,
        displayName: String,
        password: String,
    ): Boolean {
        if (password.isEmpty()) return false
        val normalizedJid = jid.trim()
        val normalizedServer = server.trim()
        return try {
            val accountId = core.snapshot().accounts
                .firstOrNull { account ->
                    account.jid == normalizedJid && account.server == normalizedServer
                }
                ?.id
                ?.toLong()
                ?: core.addAccount(
                    normalizedJid,
                    normalizedServer,
                    displayName.trim().ifBlank { normalizedJid.substringBefore('@') },
                ).toLong()
            activeAccountId = accountId
            val connected = reconnectAccount(accountId, password)
            markDirty()
            connected
        } catch (_: MindChatBindingException) {
            // A failed native setup remains visible through its core connection state.
            refresh()
            false
        }
    }

    override fun reconnectAccount(accountId: Long, password: String): Boolean {
        if (password.isEmpty()) return false
        return try {
            core.connectAccount(accountId.toULong(), password)
            markDirty()
            refresh()
            true
        } catch (_: MindChatBindingException) {
            refresh()
            false
        }
    }

    override fun addContact(jid: String, displayName: String) {
        if (activeAccountId == 0L) return
        try {
            core.upsertContact(
                activeAccountId.toULong(),
                jid.trim(),
                displayName.trim(),
                FfiContactPresence.OFFLINE,
                null,
            )
            markDirty()
            refresh()
        } catch (_: MindChatBindingException) {
            // Domain validation owns contact-address rejection.
        }
    }

    override fun sendText(conversationId: Long, text: String) {
        val account = state.accounts.firstOrNull { it.id == activeAccountId } ?: return
        try {
            core.sendText(
                conversationId.toULong(),
                account.jid,
                text,
                null,
                System.currentTimeMillis().toULong(),
            )
            markDirty()
            pendingOutboxAccounts += account.id
            refresh()
        } catch (_: MindChatBindingException) {
            // Domain validation owns message rejection; the composer remains editable.
        }
    }

    /**
     * Polls Rust-owned transport events without blocking the Compose main dispatcher.
     * Snapshot state is only assigned after the coroutine resumes on that dispatcher.
     */
    override suspend fun pollTransport() {
        val pendingAccounts = pendingOutboxAccounts.toSet()
        val onlineBefore = state.accounts
            .asSequence()
            .filter { it.connectionState == AccountConnectionState.ONLINE }
            .map(AccountUi::id)
            .toSet()
        val result = withContext(Dispatchers.IO) {
            try {
                val processedEvents = core.pollTransportEvents(32U)

                // Flush the outbox before persisting so delivery transitions are
                // captured in the saved snapshot.
                val hasTransportWork = processedEvents > 0U || pendingAccounts.any { it in onlineBefore }
                val flushedAccounts = if (hasTransportWork) {
                    val beforeFlush = core.snapshot()
                    val onlineNow = beforeFlush.accounts
                        .asSequence()
                        .filter { it.connectionState == FfiConnectionState.ONLINE }
                        .map { it.id.toLong() }
                        .toSet()
                    val accountsToFlush = (pendingAccounts + (onlineNow - onlineBefore))
                        .intersect(onlineNow)
                    accountsToFlush.forEach { accountId ->
                        try {
                            core.flushOutbox(accountId.toULong())
                        } catch (_: MindChatBindingException) {
                            // The core has already projected a failed delivery state when applicable.
                        }
                    }
                    accountsToFlush
                } else {
                    emptySet()
                }

                if (processedEvents > 0U || flushedAccounts.isNotEmpty()) {
                    markDirty()
                }
                val stateChanged = persistenceTracker.requiresSave()
                if (stateChanged) {
                    saveSnapshot()
                }

                if (processedEvents == 0U && pendingAccounts.none { it in onlineBefore }) {
                    return@withContext null
                }
                TransportPollResult(core.snapshot(), flushedAccounts)
            } catch (_: MindChatBindingException) {
                null
            }
        }
        result?.let { pollResult ->
            pendingOutboxAccounts.removeAll(pollResult.flushedAccounts)
            refresh(pollResult.snapshot)
        }
    }

    override suspend fun persistNow() {
        withContext(Dispatchers.IO) {
            saveSnapshot()
        }
    }

    override fun openConversation(address: String, title: String, group: Boolean): Long? {
        if (activeAccountId == 0L) return null
        return openLocalConversation(activeAccountId, address, title, group)
    }

    override fun toggleDynamicColor() {
        updateCustomization { it.copy(dynamicColor = !it.dynamicColor) }
    }

    override fun toggleComfortableLayout() {
        updateCustomization { it.copy(comfortableLayout = !it.comfortableLayout) }
    }

    override fun toggleAppLock() {
        updateCustomization { it.copy(appLockEnabled = !it.appLockEnabled) }
    }

    /** Test and development utility until server-backed contact search lands. */
    fun openLocalConversation(
        accountId: Long,
        address: String,
        title: String,
        group: Boolean = false,
    ): Long? {
        try {
            val conversationId = core.openConversation(
                accountId.toULong(),
                if (group) FfiConversationKind.MULTI_USER_CHAT else FfiConversationKind.DIRECT,
                address.trim(),
                title.trim().ifBlank { address.substringBefore('@') },
                System.currentTimeMillis().toULong(),
            ).toLong()
            markDirty()
            refresh()
            return conversationId
        } catch (_: MindChatBindingException) {
            // A MUC action remains disabled by capability discovery in production.
            return null
        }
    }

    private fun refresh(snapshot: FfiCoreSnapshot = core.snapshot()) {
        state = snapshotToUiState(snapshot)
        core.drainEvents()
    }

    private fun markDirty() {
        persistenceTracker.markMutation()
    }

    private fun restoreState() {
        runCatching { core.loadState(stateFile.absolutePath) }
            .onFailure {
                if (stateFile.exists()) {
                    stateFile.renameTo(File(stateFile.path + ".corrupt-" + System.currentTimeMillis()))
                }
            }
    }

    /**
     * Saves one ordered snapshot. A mutation concurrent with native I/O keeps
     * `dirty` set because its epoch differs from the snapshot's captured
     * epoch.
     */
    private suspend fun saveSnapshot(): Boolean = persistenceMutex.withLock {
        val epochAtSave = persistenceTracker.captureSaveEpoch()
        val saved = runCatching { core.saveState(stateFile.absolutePath) }.isSuccess
        if (saved) {
            persistenceTracker.markPersisted(epochAtSave)
        }
        saved
    }

    private fun updateCustomization(update: (MindChatCustomization) -> MindChatCustomization) {
        customization = update(customization)
        preferences.writeCustomization(customization)
        refresh()
    }

    private fun snapshotToUiState(snapshot: FfiCoreSnapshot = core.snapshot()): MindChatUiState {
        val accounts = snapshot.accounts.map { account ->
            AccountUi(
                id = account.id.toLong(),
                jid = account.jid,
                displayName = account.displayName,
                presence = when (account.connectionState) {
                    FfiConnectionState.ONLINE -> Presence.ONLINE
                    FfiConnectionState.OFFLINE,
                    FfiConnectionState.CONNECTING,
                    FfiConnectionState.FAILED,
                    -> Presence.OFFLINE
                },
                connectionState = account.connectionState.toUiModel(),
                supportsGroupChats = account.capabilities.contains(FfiProtocolCapability.MULTI_USER_CHAT),
                connectionError = account.connectionError,
            )
        }
        if (activeAccountId == 0L || accounts.none { it.id == activeAccountId }) {
            activeAccountId = accounts.firstOrNull()?.id ?: 0L
        }
        val messagesByConversation = snapshot.messages
            .groupBy { it.conversationId.toLong() }
            .mapValues { (_, messages) ->
                messages.map { message ->
                    val reactionLabels = snapshot.reactions
                        .filter { it.messageId == message.id }
                        .groupingBy { it.emoji }
                        .eachCount()
                        .map { (emoji, count) -> "$emoji $count" }
                    MessageUi(
                        id = message.id.toLong(),
                        sender = message.sender,
                        body = message.body,
                        timestamp = formatTimestamp(message.sentAtEpochMs.toLong()),
                        mine = message.direction == FfiMessageDirection.OUTGOING,
                        delivery = if (message.direction == FfiMessageDirection.OUTGOING) {
                            message.deliveryState.toUiModel()
                        } else {
                            null
                        },
                        reactions = reactionLabels,
                    )
                }
            }
        val contacts = snapshot.contacts.map { contact ->
            ContactUi(
                accountId = contact.accountId.toLong(),
                jid = contact.jid,
                displayName = contact.displayName,
                presence = when (contact.presence) {
                    FfiContactPresence.ONLINE -> Presence.ONLINE
                    FfiContactPresence.AWAY -> Presence.AWAY
                    FfiContactPresence.DO_NOT_DISTURB -> Presence.DO_NOT_DISTURB
                    FfiContactPresence.OFFLINE,
                    -> Presence.OFFLINE
                },
                status = contact.status,
            )
        }
        val conversations = snapshot.conversations.map { conversation ->
            val messages = messagesByConversation[conversation.id.toLong()].orEmpty()
            ConversationUi(
                id = conversation.id.toLong(),
                accountId = conversation.accountId.toLong(),
                title = conversation.title,
                address = conversation.address,
                preview = messages.lastOrNull()?.body.orEmpty(),
                timestamp = formatTimestamp(conversation.lastActivityEpochMs.toLong()),
                unreadCount = conversation.unreadCount.toInt(),
                isGroup = conversation.kind == FfiConversationKind.MULTI_USER_CHAT,
                lastActivityEpochMs = conversation.lastActivityEpochMs.toLong(),
            )
        }
        return MindChatUiState(
            accounts = accounts,
            contacts = contacts,
            activeAccountId = activeAccountId,
            conversations = conversations,
            messagesByConversation = messagesByConversation,
            dynamicColor = customization.dynamicColor,
            comfortableLayout = customization.comfortableLayout,
            appLockEnabled = customization.appLockEnabled,
        )
    }
}

private fun FfiConnectionState.toUiModel(): AccountConnectionState = when (this) {
    FfiConnectionState.OFFLINE -> AccountConnectionState.OFFLINE
    FfiConnectionState.CONNECTING -> AccountConnectionState.CONNECTING
    FfiConnectionState.ONLINE -> AccountConnectionState.ONLINE
    FfiConnectionState.FAILED -> AccountConnectionState.FAILED
}

private fun FfiDeliveryState.toUiModel(): MessageDelivery = when (this) {
    FfiDeliveryState.PENDING -> MessageDelivery.PENDING
    FfiDeliveryState.SENT -> MessageDelivery.SENT
    FfiDeliveryState.DELIVERED -> MessageDelivery.DELIVERED
    FfiDeliveryState.READ -> MessageDelivery.READ
    FfiDeliveryState.FAILED -> MessageDelivery.FAILED
}

private fun formatTimestamp(epochMs: Long): String = DateFormat
    .getTimeInstance(DateFormat.SHORT, Locale.getDefault())
    .format(Date(epochMs))

/** Compose-previewable fallback for design previews and native-free debug tests. */
@Stable
class PreviewMindChatGateway(
    private val preferences: MindChatPreferences = InMemoryMindChatPreferences(),
) : MindChatGateway {
    private var customization = preferences.readCustomization()

    override var state by mutableStateOf(seedState().withCustomization(customization))
        private set

    override fun selectAccount(accountId: Long) {
        if (state.accounts.any { it.id == accountId }) {
            state = state.copy(activeAccountId = accountId)
        }
    }

    override fun addAccount(
        jid: String,
        server: String,
        displayName: String,
        password: String,
    ): Boolean {
        if ('@' !in jid || server.isBlank() || password.isBlank()) return false
        val nextId = (state.accounts.maxOfOrNull { it.id } ?: 0) + 1
        val account = AccountUi(
            id = nextId,
            jid = jid.trim(),
            displayName = displayName.trim().ifBlank { jid.substringBefore('@') },
            presence = Presence.OFFLINE,
            connectionState = AccountConnectionState.OFFLINE,
            supportsGroupChats = true,
        )
        state = state.copy(
            accounts = state.accounts + account,
            activeAccountId = nextId,
        )
        return true
    }

    override fun reconnectAccount(accountId: Long, password: String): Boolean {
        if (password.isEmpty() || state.accounts.none { it.id == accountId }) return false
        state = state.copy(
            accounts = state.accounts.map {
                if (it.id == accountId) {
                    it.copy(connectionState = AccountConnectionState.CONNECTING, connectionError = null)
                } else {
                    it
                }
            },
        )
        return true
    }

    override fun addContact(jid: String, displayName: String) {
        val address = jid.trim()
        if ('@' !in address || state.activeAccountId == 0L) return
        val contact = ContactUi(
            accountId = state.activeAccountId,
            jid = address,
            displayName = displayName.trim().ifBlank { address.substringBefore('@') },
            presence = Presence.OFFLINE,
        )
        state = state.copy(
            contacts = state.contacts
                .filterNot { it.accountId == contact.accountId && it.jid == contact.jid }
                .plus(contact)
                .sortedWith(compareBy(ContactUi::accountId, ContactUi::jid)),
        )
    }

    override fun sendText(conversationId: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val messages = state.messagesByConversation[conversationId].orEmpty()
        val message = MessageUi(
            id = (messages.maxOfOrNull { it.id } ?: 0) + 1,
            sender = "You",
            body = trimmed,
            timestamp = "now",
            mine = true,
            delivery = MessageDelivery.PENDING,
        )
        state = state.copy(
            messagesByConversation = state.messagesByConversation + (conversationId to (messages + message)),
            conversations = state.conversations.map {
                if (it.id == conversationId) {
                    it.copy(
                        preview = trimmed,
                        timestamp = "now",
                        lastActivityEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            },
        )
    }

    override suspend fun pollTransport() = Unit

    override suspend fun persistNow() = Unit

    override fun openConversation(address: String, title: String, group: Boolean): Long? {
        val addressValue = address.trim()
        if (addressValue.isEmpty() || state.activeAccountId == 0L) return null
        state.conversations
            .firstOrNull {
                it.accountId == state.activeAccountId &&
                    it.address == addressValue &&
                    it.isGroup == group
            }
            ?.let { return it.id }
        val conversationId = (state.conversations.maxOfOrNull { it.id } ?: 0) + 1
        val conversation = ConversationUi(
            id = conversationId,
            accountId = state.activeAccountId,
            title = title.trim().ifBlank { addressValue.substringBefore('@') },
            address = addressValue,
            preview = "",
            timestamp = "now",
            isGroup = group,
            lastActivityEpochMs = System.currentTimeMillis(),
        )
        state = state.copy(
            conversations = state.conversations + conversation,
            messagesByConversation = state.messagesByConversation + (conversationId to emptyList()),
        )
        return conversationId
    }

    override fun toggleDynamicColor() {
        updateCustomization { it.copy(dynamicColor = !it.dynamicColor) }
    }

    override fun toggleComfortableLayout() {
        updateCustomization { it.copy(comfortableLayout = !it.comfortableLayout) }
    }

    override fun toggleAppLock() {
        updateCustomization { it.copy(appLockEnabled = !it.appLockEnabled) }
    }

    private fun updateCustomization(update: (MindChatCustomization) -> MindChatCustomization) {
        customization = update(customization)
        preferences.writeCustomization(customization)
        state = state.withCustomization(customization)
    }
}

private fun MindChatUiState.withCustomization(customization: MindChatCustomization): MindChatUiState = copy(
    dynamicColor = customization.dynamicColor,
    comfortableLayout = customization.comfortableLayout,
    appLockEnabled = customization.appLockEnabled,
)

private fun seedState(): MindChatUiState {
    val account = AccountUi(
        id = 1,
        jid = "alice@mindchat.example",
        displayName = "Alice",
        presence = Presence.ONLINE,
        connectionState = AccountConnectionState.ONLINE,
        supportsGroupChats = true,
    )
    val conversations = listOf(
        ConversationUi(
            id = 1,
            accountId = account.id,
            title = "Bob",
            address = "bob@example.org",
            preview = "See you at 19:00!",
            timestamp = "18:42",
            unreadCount = 2,
            encrypted = true,
            lastActivityEpochMs = 2,
        ),
        ConversationUi(
            id = 2,
            accountId = account.id,
            title = "MindChat community",
            address = "community@conference.example.org",
            preview = "Mila: Material 3 samples are ready",
            timestamp = "16:08",
            isGroup = true,
            lastActivityEpochMs = 1,
        ),
    )
    return MindChatUiState(
        accounts = listOf(account),
        contacts = listOf(
            ContactUi(
                accountId = account.id,
                jid = "bob@example.org",
                displayName = "Bob",
                presence = Presence.ONLINE,
                status = "Available",
            ),
            ContactUi(
                accountId = account.id,
                jid = "mila@example.org",
                displayName = "Mila",
                presence = Presence.OFFLINE,
            ),
            ContactUi(
                accountId = account.id,
                jid = "community@conference.example.org",
                displayName = "MindChat community",
                presence = Presence.ONLINE,
            ),
        ),
        activeAccountId = account.id,
        conversations = conversations,
        messagesByConversation = mapOf(
            1L to listOf(
                MessageUi(1, "Bob", "Hi! Is the prototype ready?", "18:37", mine = false),
                MessageUi(
                    2,
                    "You",
                    "Almost. I am polishing the chat screen.",
                    "18:39",
                    mine = true,
                    delivery = MessageDelivery.DELIVERED,
                ),
                MessageUi(3, "Bob", "See you at 19:00!", "18:42", mine = false, reactions = listOf("👍 1")),
            ),
            2L to listOf(
                MessageUi(1, "Mila", "Material 3 samples are ready", "16:08", mine = false),
            ),
        ),
    )
}

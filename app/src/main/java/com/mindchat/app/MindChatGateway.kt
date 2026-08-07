package com.mindchat.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import com.mindchat.core.FfiConnectionState
import com.mindchat.core.FfiConversationKind
import com.mindchat.core.FfiDeliveryState
import com.mindchat.core.FfiMessageDirection
import com.mindchat.core.FfiProtocolCapability
import com.mindchat.core.MindChatBindingException
import com.mindchat.core.MindChatCoreHandle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class Presence { ONLINE, OFFLINE }

enum class MessageDelivery { PENDING, SENT, DELIVERED, READ, FAILED }

data class AccountUi(
    val id: Long,
    val jid: String,
    val displayName: String,
    val presence: Presence,
    val supportsGroupChats: Boolean = false,
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
    val activeAccountId: Long,
    val conversations: List<ConversationUi>,
    val messagesByConversation: Map<Long, List<MessageUi>>,
    val dynamicColor: Boolean = true,
    val comfortableLayout: Boolean = true,
    val appLockEnabled: Boolean = false,
)

interface MindChatGateway {
    val state: MindChatUiState

    fun selectAccount(accountId: Long)
    fun addLocalAccount(jid: String, server: String, displayName: String)
    fun openConversation(address: String, title: String, group: Boolean)
    fun sendText(conversationId: Long, text: String)
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
            NativeMindChatGateway(preferences = preferences)
        } catch (error: LinkageError) {
            if (BuildConfig.DEBUG) PreviewMindChatGateway(preferences) else throw error
        }
    }
}

/** Presentation adapter over the generated UniFFI contract. */
@Stable
class NativeMindChatGateway(
    private val core: MindChatCoreHandle = MindChatCoreHandle(),
    private val preferences: MindChatPreferences = InMemoryMindChatPreferences(),
) : MindChatGateway {
    private var activeAccountId = 0L
    private var customization = preferences.readCustomization()

    override var state by mutableStateOf(snapshotToUiState())
        private set

    override fun selectAccount(accountId: Long) {
        if (state.accounts.any { it.id == accountId }) {
            activeAccountId = accountId
            refresh()
        }
    }

    override fun addLocalAccount(jid: String, server: String, displayName: String) {
        try {
            val accountId = core.addAccount(
                jid.trim(),
                server.trim(),
                displayName.trim().ifBlank { jid.substringBefore('@').trim() },
            ).toLong()
            activeAccountId = accountId
            refresh()
        } catch (_: MindChatBindingException) {
            // The Compose form keeps invalid entries local until the user changes them.
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
            refresh()
        } catch (_: MindChatBindingException) {
            // Domain validation owns message rejection; the composer remains editable.
        }
    }

    override fun openConversation(address: String, title: String, group: Boolean) {
        if (activeAccountId == 0L) return
        openLocalConversation(activeAccountId, address, title, group)
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

    /** Test and development utility until roster-backed contact search lands. */
    fun openLocalConversation(accountId: Long, address: String, title: String, group: Boolean = false) {
        try {
            core.openConversation(
                accountId.toULong(),
                if (group) FfiConversationKind.MULTI_USER_CHAT else FfiConversationKind.DIRECT,
                address.trim(),
                title.trim().ifBlank { address.substringBefore('@') },
                System.currentTimeMillis().toULong(),
            )
            refresh()
        } catch (_: MindChatBindingException) {
            // A MUC action remains disabled by capability discovery in production.
        }
    }

    private fun refresh() {
        state = snapshotToUiState()
        core.drainEvents()
    }

    private fun updateCustomization(update: (MindChatCustomization) -> MindChatCustomization) {
        customization = update(customization)
        preferences.writeCustomization(customization)
        refresh()
    }

    private fun snapshotToUiState(): MindChatUiState {
        val snapshot = core.snapshot()
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
                supportsGroupChats = account.capabilities.contains(FfiProtocolCapability.MULTI_USER_CHAT),
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
            activeAccountId = activeAccountId,
            conversations = conversations,
            messagesByConversation = messagesByConversation,
            dynamicColor = customization.dynamicColor,
            comfortableLayout = customization.comfortableLayout,
            appLockEnabled = customization.appLockEnabled,
        )
    }
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

    override fun addLocalAccount(jid: String, server: String, displayName: String) {
        if ('@' !in jid || server.isBlank()) return
        val nextId = (state.accounts.maxOfOrNull { it.id } ?: 0) + 1
        val account = AccountUi(
            id = nextId,
            jid = jid.trim(),
            displayName = displayName.trim().ifBlank { jid.substringBefore('@') },
            presence = Presence.OFFLINE,
            supportsGroupChats = true,
        )
        state = state.copy(
            accounts = state.accounts + account,
            activeAccountId = nextId,
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

    override fun openConversation(address: String, title: String, group: Boolean) {
        val addressValue = address.trim()
        if (addressValue.isEmpty() || state.activeAccountId == 0L) return
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

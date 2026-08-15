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
import com.mindchat.core.FfiDiagnosticsReport
import com.mindchat.core.FfiDisconnectKind
import com.mindchat.core.MindChatBindingException
import com.mindchat.core.MindChatCoreHandle
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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
    val connectionStalled: Boolean = false,
    /**
     * Typed disconnect classification (ROADMAP 6.5) from the core snapshot,
     * rendered as the bucket label above [connectionError]. Null while the
     * account is connected or has not disconnected yet. Prose stays
     * display-only; [Diagnostics.disconnectBucket] owns the classification.
     */
    val disconnectKind: FfiDisconnectKind? = null,
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
    val profiles: Map<Long, AccountProfile> = emptyMap(),
    val settings: SettingsSnapshot = SettingsSnapshot(),
    val accountSettings: Map<Long, SettingsSnapshot> = emptyMap(),
    val appearance: AppearanceProfile = AppearanceProfile(),
    val proxyLibrary: List<ProxyLibraryEntry> = emptyList(),
    val proxyAssignments: Map<Long, String> = emptyMap(),
    /**
     * Whether the core quarantined the local state file because it could not
     * be restored (ROADMAP 6.5 internal/persistence bucket). Set from
     * [MindChatGateway.diagnosticsReport] after the startup restore; drives
     * the one-time dismissible quarantine notice.
     */
    val diagnosticsQuarantined: Boolean = false,
    /** Whether the user dismissed the quarantine notice on this device. */
    val diagnosticsNoticeDismissed: Boolean = false,
) {
    // Convenience accessors kept for theme code and 0.1.6 contract tests.
    val dynamicColor: Boolean get() = settings.dynamicColor

    val appLockEnabled: Boolean get() = settings.appLockEnabled

    /**
     * The library entry assigned to [accountId], or null when the account
     * connects directly. Resolves the persisted per-account assignment id
     * against the live library; a deleted entry resolves to null.
     */
    fun assignedProxy(accountId: Long): ProxyLibraryEntry? {
        val id = proxyAssignments[accountId] ?: return null
        return proxyLibrary.firstOrNull { it.id == id }
    }
}

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
    /**
     * XEP-0077 in-band registration (no captcha support). [jid] is the full
     * desired JID; the local part is used as the username. Returns a UI-safe
     * error detail on failure (for example "server requires additional
     * registration fields"), or null on success (account created and Online).
     */
    fun registerAccount(jid: String, server: String, displayName: String, password: String): String?
    fun reconnectAccount(accountId: Long, password: String): Boolean
    fun disconnectAccount(accountId: Long)
    fun addContact(jid: String, displayName: String)
    fun openConversation(address: String, title: String, group: Boolean): Long?
    fun sendText(conversationId: Long, text: String)
    fun markConversationRead(conversationId: Long)
    fun updateProfile(accountId: Long, profile: AccountProfile)
    /** Removes the account and its conversations, contacts, messages, and local profile. */
    fun deleteAccount(accountId: Long)
    fun renameAccount(accountId: Long, displayName: String)
    fun deleteConversation(conversationId: Long)
    suspend fun pollTransport()
    suspend fun persistNow()
    fun toggleDynamicColor()
    /**
     * Replaces the global appearance profile (persists via
     * `preferences.writeCustomization`, then refreshes). Per-account bubble /
     * background overrides live on [AccountProfile] and flow through
     * [updateProfile] instead.
     */
    fun setAppearance(appearance: AppearanceProfile)
    fun toggleAppLock()

    /**
     * Writes one typed global setting through the shared decision path
     * (sanitize in [GatewayInput], persist via [MindChatPreferences], then
     * refresh). Pure and side-effect free: shell-level coordination such as
     * the app lock host state machine stays outside this method.
     */
    fun <T> setSetting(key: SettingKey<T>, value: T)

    /** Writes one account-scoped setting; persisted and reflected in state. */
    fun setAccountSetting(accountId: Long, key: SettingKey<*>, value: Any)

    // --- Proxy library and per-account assignment (ROADMAP 6.3) ---------------

    /**
     * Adds a proxy to the device-local library. [password] is optional and is
     * stored encrypted in the Android Keystore keyed by the new entry id;
     * `null` means the proxy needs no stored password. Returns false when the
     * config fails [GatewayInput.validateProxyConfig]; nothing is persisted.
     */
    fun addProxy(config: ProxyConfig, password: String? = null): Boolean

    /**
     * Replaces a library entry's non-secret config. [password] (when non-null)
     * replaces the stored credential; the previous probe status is cleared
     * because the old latency no longer describes the new config. Returns
     * false for an unknown id or an invalid config.
     */
    fun updateProxy(proxyId: String, config: ProxyConfig, password: String? = null): Boolean

    /**
     * Removes a library entry, its stored credential, and any account
     * assignment pointing at it (assigned accounts fall back to direct
     * connections in the core).
     */
    fun deleteProxy(proxyId: String)

    /**
     * Probes the library entry (real [com.mindchat.core.FfiProxyProbe], no
     * fake latency) and persists the measured status for the latency chip.
     * [password] (when non-null) is stored first, so a first ping can supply
     * a credential that later pings reuse. Must be called off the UI thread.
     */
    fun pingProxy(proxyId: String, password: String? = null): ProxyProbeResult

    /**
     * Assigns a library proxy to an account (config must match a library
     * entry) or clears the assignment with `config = null`. The non-secret
     * config is pushed to the core (`setAccountProxies`) so connections route
     * through it; [password] (when non-null) is stored encrypted and is never
     * exposed through the gateway. Returns false for an invalid or unknown
     * config.
     */
    fun setAccountProxy(accountId: Long, config: ProxyConfig?, password: String? = null): Boolean

    /**
     * The config currently assigned to [accountId], or null for a direct
     * connection. Read-only; the password is never returned.
     */
    fun accountProxy(accountId: Long): ProxyConfig?

    /**
     * Runs a real probe for an arbitrary config (used by the contract tests
     * and the ping path). Validation failures return a failed result with a
     * UI-safe error instead of reaching the core. Must be called off the UI
     * thread; the probe is capped at 15 seconds.
     */
    fun testProxy(config: ProxyConfig, password: String? = null): ProxyProbeResult

    // --- Diagnostics (ROADMAP 6.5) ------------------------------------------

    /**
     * The opt-in diagnostics report: snapshot record counts plus persistence
     * metadata. Structurally excludes passwords, message bodies, avatars and
     * JIDs (redacted on the Rust side; the Kotlin serializer adds nothing).
     * The user triggers export; nothing here ever leaves the device on its
     * own.
     */
    fun diagnosticsReport(): FfiDiagnosticsReport

    /**
     * Dismisses the one-time quarantine notice. The dismissal is device-local
     * and remembered across restarts; the notice reappears only when the core
     * reports a newly quarantined state and the user has not dismissed it.
     */
    fun dismissDiagnosticsNotice()
}

/**
 * Chooses the generated Rust binding for packaged builds. The preview fallback
 * keeps Compose previews and isolated debug UI tests available when an Android
 * ABI library is intentionally absent.
 */
object MindChatGatewayFactory {
    fun create(context: Context): MindChatGateway {
        val preferences = SharedPreferencesMindChatPreferences(context)
        val proxyLibraryStore = SharedPreferencesProxyLibraryStore(context)
        val credentialStore = KeystoreProxyCredentialStore(context)
        return try {
            NativeMindChatGateway(
                stateFile = File(context.filesDir, "mindchat_state.json"),
                preferences = preferences,
                proxyLibraryStore = proxyLibraryStore,
                credentialStore = credentialStore,
            )
        } catch (error: LinkageError) {
            if (BuildConfig.DEBUG) {
                PreviewMindChatGateway(preferences, proxyLibraryStore, credentialStore)
            } else {
                throw error
            }
        }
    }
}

/** Presentation adapter over the generated UniFFI contract. */
@Stable
class NativeMindChatGateway(
    private val core: MindChatCoreHandle = MindChatCoreHandle(),
    private val stateFile: File,
    private val preferences: MindChatPreferences = InMemoryMindChatPreferences(),
    private val proxyLibraryStore: ProxyLibraryStore = InMemoryProxyLibraryStore(),
    private val credentialStore: ProxyCredentialStore = InMemoryProxyCredentialStore(),
) : MindChatGateway {
    private var activeAccountId = 0L

    /** Global settings backing the current UI state; [setSetting] is the only writer. */
    private var settingsCache = SettingsSnapshot(preferences.readAll())

    /** Global appearance backing [MindChatUiState.appearance]; [setAppearance] is the only writer. */
    private var appearanceCache: AppearanceProfile = preferences.readCustomization().appearance

    /**
     * Per-account settings backing [MindChatUiState.accountSettings]. 0.1.7 has
     * no PER_ACCOUNT keys yet, so this starts empty; 0.1.8 populates it when
     * accounts load and [setAccountSetting] updates it.
     */
    private var accountSettingsCache: Map<Long, SettingsSnapshot> = emptyMap()

    /**
     * The non-secret proxy library backing [MindChatUiState.proxyLibrary] and
     * the per-account assignment ids backing [MindChatUiState.proxyAssignments]
     * (ROADMAP 6.3). [setAccountProxy] is the only writer of the assignments;
     * the connect-time configs are mirrored into the core via
     * `setAccountProxies` so restored state stays consistent.
     */
    private var proxyLibraryCache: List<ProxyLibraryEntry> = proxyLibraryStore.readEntries()
    private var proxyAssignmentsCache: Map<Long, String> = proxyLibraryStore.readAssignments()

    /**
     * Diagnostics state (ROADMAP 6.5). [diagnosticsQuarantined] is set once
     * after the startup restore from `core.diagnosticsReport()`;
     * [quarantineNoticeDismissed] is device-local and remembered via
     * [MindChatPreferences], so the one-time notice stays dismissed across
     * restarts while a fresh quarantine re-shows it.
     */
    private var diagnosticsQuarantined = false
    private var quarantineNoticeDismissed = preferences.readQuarantineNoticeDismissed()

    private val pendingOutboxAccounts = mutableSetOf<Long>()

    /** When each account entered CONNECTING (epoch ms), used for stall detection. */
    private val connectingSince = mutableMapOf<Long, Long>()

    /** Profiles backing the current UI state; [updateProfile] is the only writer. */
    private var profilesCache = preferences.readProfiles()

    /**
     * The raw snapshot, profiles, and settings that produced the current
     * [state]. Poll cycles compare against these so an unchanged core skips
     * rebuilding the whole UI state (and the recomposition that would cause).
     */
    private var lastSnapshot: FfiCoreSnapshot? = null
    private var lastProfiles: Map<Long, AccountProfile> = emptyMap()
    private var lastSettings: SettingsSnapshot = settingsCache
    private var lastAccountSettings: Map<Long, SettingsSnapshot> = emptyMap()
    private var lastAppearance: AppearanceProfile = appearanceCache
    private var lastProxyLibrary: List<ProxyLibraryEntry> = proxyLibraryCache
    private var lastProxyAssignments: Map<Long, String> = proxyAssignmentsCache
    private var lastDiagnosticsQuarantined = diagnosticsQuarantined
    private var lastDiagnosticsNoticeDismissed = quarantineNoticeDismissed

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
        // Surface the quarantine outcome through the same report the export
        // path uses; the one-time notice reads this flag from UI state.
        diagnosticsQuarantined = runCatching { core.diagnosticsReport() }
            .getOrNull()
            ?.stateQuarantined == true
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

    override fun registerAccount(
        jid: String,
        server: String,
        displayName: String,
        password: String,
    ): String? {
        val input = when (val validation = validateRegistration(jid, server, displayName, password)) {
            is RegistrationValidation.Refused -> return validation.refusal.toUiDetail()
            is RegistrationValidation.Valid -> validation.input
        }
        return try {
            val accountId = core.registerAccount(
                input.username,
                input.server,
                input.displayName,
                input.password,
            ).toLong()
            activeAccountId = accountId
            markDirty()
            refresh()
            null
        } catch (e: MindChatBindingException) {
            refresh()
            e.message?.takeIf { it.isNotBlank() } ?: "registration failed"
        }
    }

    override fun reconnectAccount(accountId: Long, password: String): Boolean {
        if (password.isEmpty()) return false
        return try {
            val proxy = assignedProxyEntry(accountId)
            if (proxy == null) {
                core.connectAccount(accountId.toULong(), password)
            } else {
                // The proxy password comes from the keystore (per proxy id) and
                // is handed to the worker at the call site; it never crosses
                // the gateway contract or the UI state.
                core.connectAccountWithProxy(
                    accountId.toULong(),
                    password,
                    proxy.toConfig().toFfi(),
                    credentialStore.load(proxy.id),
                )
            }
            markDirty()
            refresh()
            true
        } catch (_: MindChatBindingException) {
            refresh()
            false
        }
    }

    override fun disconnectAccount(accountId: Long) {
        try {
            core.disconnectAccount(accountId.toULong())
            markDirty()
            refresh()
        } catch (_: MindChatBindingException) {
            // The account clears through transport events; keep the UI current.
            refresh()
        }
    }

    override fun markConversationRead(conversationId: Long) {
        try {
            core.markConversationRead(conversationId.toULong())
            markDirty()
            refresh()
        } catch (_: MindChatBindingException) {
            // The unread count is cosmetic; a rejected call just keeps the badge.
            refresh()
        }
    }

    override fun updateProfile(accountId: Long, profile: AccountProfile) {
        preferences.writeProfile(accountId, profile)
        profilesCache = preferences.readProfiles()
        refresh()
    }

    override fun deleteAccount(accountId: Long) {
        try {
            core.deleteAccount(accountId.toULong())
            preferences.removeProfile(accountId)
            preferences.removeAccountSettings(accountId)
            profilesCache = preferences.readProfiles()
            accountSettingsCache = accountSettingsCache - accountId
            // The account's proxy assignment dies with it; the library entry
            // and its stored password stay (they belong to the proxy).
            proxyAssignmentsCache = proxyAssignmentsCache - accountId
            proxyLibraryStore.writeAssignments(proxyAssignmentsCache)
            markDirty()
            refresh()
            activeAccountId = nextActiveAccountId(state.accounts, accountId, activeAccountId)
            // The next poll/persist cycle persists the removal (persistNow is
            // suspend; account deletion stays synchronous for the UI).
        } catch (e: MindChatBindingException) {
            refresh()
            throw e
        }
    }

    override fun renameAccount(accountId: Long, displayName: String) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        try {
            core.updateAccountDisplayName(accountId.toULong(), name)
            markDirty()
            refresh()
        } catch (_: MindChatBindingException) {
            refresh()
        }
    }

    override fun deleteConversation(conversationId: Long) {
        try {
            core.deleteConversation(conversationId.toULong())
            markDirty()
            refresh()
        } catch (_: MindChatBindingException) {
            refresh()
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
     *
     * P0-4: a changed poll captures exactly one core snapshot, after event
     * processing and the outbox flush, and reuses it for both the durable save
     * and the UI refresh. The unchanged poll (no events, no queued outgoing
     * traffic) captures zero snapshots.
     */
    override suspend fun pollTransport() {
        try {
            val pendingAccounts = pendingOutboxAccounts.toSet()
            val onlineBefore = state.accounts
                .asSequence()
                .filter { it.connectionState == AccountConnectionState.ONLINE }
                .map(AccountUi::id)
                .toSet()
            val result = withContext(Dispatchers.IO) {
                try {
                    val processedEvents = drainTransportEvents()
                    val pendingWork = pendingAccounts.any { it in onlineBefore }
                    if (processedEvents == 0U && !pendingWork) {
                        // Unchanged poll: nothing to apply and nothing queued.
                        // No snapshot, no save, no UI rebuild.
                        return@withContext null
                    }

                    // Flush the outbox before persisting so delivery transitions
                    // are captured in the saved snapshot. Candidates are the
                    // Kotlin-tracked pending accounts; flushOutbox is a safe no-op
                    // for an account that dropped offline mid-batch, so no
                    // pre-flush snapshot is needed to decide who to flush.
                    val flushedAccounts = pendingAccounts
                    flushedAccounts.forEach { accountId ->
                        try {
                            core.flushOutbox(accountId.toULong())
                        } catch (_: MindChatBindingException) {
                            // The core has already projected a failed delivery state when applicable.
                        }
                    }

                    if (processedEvents > 0U || flushedAccounts.isNotEmpty()) {
                        markDirty()
                    }
                    // The single snapshot of this poll: post-events, post-flush.
                    val snapshot = core.snapshot()
                    if (persistenceTracker.requiresSave()) {
                        saveSnapshot(snapshot)
                    }
                    TransportPollResult(snapshot, flushedAccounts)
                } catch (_: MindChatBindingException) {
                    // A transport batch can apply Connected/Disconnected before a
                    // later malformed roster or presence stanza makes the native
                    // call return an error. Keep the state projection visible even
                    // when that later event is rejected; otherwise the UI can stay
                    // on a stale Connecting snapshot forever.
                    val fallbackSnapshot = runCatching { core.snapshot() }.getOrNull()
                    if (fallbackSnapshot != null) {
                        // Events applied before the failed batch are visible but
                        // would be lost on process death; make them durable now.
                        markDirty()
                        saveSnapshot(fallbackSnapshot)
                        TransportPollResult(fallbackSnapshot, emptySet())
                    } else {
                        null
                    }
                }
            }
            result?.let { pollResult ->
                pendingOutboxAccounts.removeAll(pollResult.flushedAccounts)
                // Accounts that connected during this batch may carry restored
                // queued messages that are not in pendingOutboxAccounts (they
                // were never sent from this process); schedule them so the next
                // poll flushes their queue.
                val onlineNow = pollResult.snapshot.accounts
                    .asSequence()
                    .filter { it.connectionState == FfiConnectionState.ONLINE }
                    .map { it.id.toLong() }
                    .toSet()
                pendingOutboxAccounts += onlineNow - onlineBefore
                refresh(pollResult.snapshot)
            }
        } catch (throwable: Throwable) {
            // MindChatApp polls inside an infinite LaunchedEffect loop; an
            // exception escaping here kills that loop and freezes the UI on a
            // stale Connecting state forever. Survive by refreshing when a
            // snapshot is still obtainable; the next cycle simply polls again.
            if (throwable is CancellationException) throw throwable
            runCatching { refresh() }
        }
    }

    /**
     * P1-1: adaptively drains the core transport queue in bounded batches.
     *
     * The planner in [GatewayPoll] decides each batch size and when to stop:
     * up to [MAX_DRAIN_BATCHES_PER_CYCLE] polls of [MAX_EVENTS_PER_DRAIN_BATCH]
     * each, capped at [MAX_EVENTS_PER_DRAIN_CYCLE] per cycle, stopping early
     * on a partial batch (the core queue is empty). A busy server therefore
     * yields at most 512 applied events per poll cycle instead of one
     * unbounded call, while a quiet one costs exactly one poll call.
     */
    private fun drainTransportEvents(): UInt {
        var eventsProcessed = 0U
        var batchesUsed = 0
        var previousBatchSize = MAX_EVENTS_PER_DRAIN_BATCH
        while (true) {
            val step = nextDrainStep(previousBatchSize, batchesUsed, eventsProcessed)
            if (!step.continueDraining) break
            val batch = core.pollTransportEvents(step.batchSize)
            eventsProcessed += batch
            batchesUsed += 1
            previousBatchSize = batch
        }
        return eventsProcessed
    }

    override suspend fun persistNow() {
        withContext(Dispatchers.IO) {
            // Skip the write when nothing mutated since the last persisted snapshot.
            if (persistenceTracker.requiresSave()) {
                saveSnapshot()
            }
        }
    }

    override fun openConversation(address: String, title: String, group: Boolean): Long? {
        if (activeAccountId == 0L) return null
        return openLocalConversation(activeAccountId, address, title, group)
    }

    override fun toggleDynamicColor() {
        setSetting(SettingsSchema.dynamicColor, !state.settings.dynamicColor)
    }

    override fun setAppearance(appearance: AppearanceProfile) {
        val current = preferences.readCustomization()
        preferences.writeCustomization(current.copy(appearance = appearance))
        appearanceCache = appearance
        refresh()
    }

    override fun toggleAppLock() {
        setSetting(SettingsSchema.appLockEnabled, !state.settings.appLockEnabled)
    }

    override fun <T> setSetting(key: SettingKey<T>, value: T) {
        val sanitized = sanitizeSetting(key, value)
        preferences.write(key, sanitized)
        settingsCache = SettingsSnapshot(preferences.readAll())
        refresh()
    }

    override fun setAccountSetting(accountId: Long, key: SettingKey<*>, value: Any) {
        @Suppress("UNCHECKED_CAST")
        val sanitized = sanitizeSetting(key as SettingKey<Any>, value)
        preferences.writeAccountSetting(accountId, key, sanitized)
        settingsCache = SettingsSnapshot(preferences.readAll())
        accountSettingsCache = accountSettingsCache + (accountId to SettingsSnapshot(preferences.readAccountSettings(accountId)))
        refresh()
    }

    // --- Proxy library and per-account assignment (ROADMAP 6.3) ---------------

    override fun addProxy(config: ProxyConfig, password: String?): Boolean {
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) return false
        val id = nextProxyId(proxyLibraryCache)
        if (password != null) credentialStore.store(id, password)
        proxyLibraryCache = proxyLibraryCache +
            ProxyLibraryEntry(id = id, host = config.host, port = config.port, kind = config.kind)
        proxyLibraryStore.writeEntries(proxyLibraryCache)
        refresh()
        return true
    }

    override fun updateProxy(proxyId: String, config: ProxyConfig, password: String?): Boolean {
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) return false
        if (proxyLibraryCache.none { it.id == proxyId }) return false
        if (password != null) credentialStore.store(proxyId, password)
        // The config changed, so the previously measured latency is stale.
        proxyLibraryCache = proxyLibraryCache.map {
            if (it.id == proxyId) {
                ProxyLibraryEntry(id = proxyId, host = config.host, port = config.port, kind = config.kind)
            } else {
                it
            }
        }
        proxyLibraryStore.writeEntries(proxyLibraryCache)
        refresh()
        return true
    }

    override fun deleteProxy(proxyId: String) {
        if (proxyLibraryCache.none { it.id == proxyId }) return
        proxyLibraryCache = proxyLibraryCache.filterNot { it.id == proxyId }
        proxyLibraryStore.writeEntries(proxyLibraryCache)
        credentialStore.delete(proxyId)
        val affectedAccounts = proxyAssignmentsCache.filterValues { it == proxyId }.keys
        proxyAssignmentsCache = proxyAssignmentsCache - affectedAccounts
        proxyLibraryStore.writeAssignments(proxyAssignmentsCache)
        affectedAccounts.forEach { accountId ->
            // Assigned accounts fall back to direct connections in the core.
            runCatching { core.setAccountProxies(accountId.toULong(), null) }.onSuccess { markDirty() }
        }
        refresh()
    }

    override fun pingProxy(proxyId: String, password: String?): ProxyProbeResult {
        val entry = proxyLibraryCache.firstOrNull { it.id == proxyId }
            ?: return ProxyProbeResult(ok = false, latencyMs = null, error = "unknown proxy")
        if (password != null) credentialStore.store(proxyId, password)
        val result = testProxy(entry.toConfig(), credentialStore.load(proxyId))
        proxyLibraryCache = proxyLibraryCache.map {
            if (it.id == proxyId) {
                it.copy(
                    status = ProxyStatus(
                        latencyMs = if (result.ok) result.latencyMs else null,
                        error = result.error,
                    ),
                )
            } else {
                it
            }
        }
        proxyLibraryStore.writeEntries(proxyLibraryCache)
        refresh()
        return result
    }

    override fun setAccountProxy(accountId: Long, config: ProxyConfig?, password: String?): Boolean {
        if (config == null) {
            proxyAssignmentsCache = proxyAssignmentsCache - accountId
            proxyLibraryStore.writeAssignments(proxyAssignmentsCache)
            runCatching { core.setAccountProxies(accountId.toULong(), null) }.onSuccess { markDirty() }
            refresh()
            return true
        }
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) return false
        val entry = proxyLibraryCache.findByConfig(config) ?: return false
        if (password != null) credentialStore.store(entry.id, password)
        val previousAssignments = proxyAssignmentsCache
        proxyAssignmentsCache = proxyAssignmentsCache + (accountId to entry.id)
        proxyLibraryStore.writeAssignments(proxyAssignmentsCache)
        try {
            core.setAccountProxies(accountId.toULong(), listOf(config.toFfi()))
            markDirty()
        } catch (_: MindChatBindingException) {
            // The core rejected the assignment (unknown account); keep the
            // local projection honest by rolling the assignment back.
            proxyAssignmentsCache = previousAssignments
            proxyLibraryStore.writeAssignments(previousAssignments)
            refresh()
            return false
        }
        refresh()
        return true
    }

    override fun accountProxy(accountId: Long): ProxyConfig? =
        assignedProxyEntry(accountId)?.toConfig()

    override fun testProxy(config: ProxyConfig, password: String?): ProxyProbeResult {
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) {
            return ProxyProbeResult(ok = false, latencyMs = null, error = "invalid proxy configuration")
        }
        return try {
            val probe = core.testProxy(config.toFfi(), password)
            ProxyProbeResult(
                ok = probe.ok,
                latencyMs = if (probe.ok) probe.latencyMs.toLong() else null,
                error = probe.error,
            )
        } catch (_: MindChatBindingException) {
            ProxyProbeResult(ok = false, latencyMs = null, error = "proxy probe failed")
        }
    }

    // --- Diagnostics (ROADMAP 6.5) ------------------------------------------

    override fun diagnosticsReport(): FfiDiagnosticsReport = core.diagnosticsReport()

    override fun dismissDiagnosticsNotice() {
        if (quarantineNoticeDismissed) return
        quarantineNoticeDismissed = true
        preferences.writeQuarantineNoticeDismissed(true)
        refresh()
    }

    private fun assignedProxyEntry(accountId: Long): ProxyLibraryEntry? {
        val id = proxyAssignmentsCache[accountId] ?: return null
        return proxyLibraryCache.firstOrNull { it.id == id }
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

    /**
     * Rebuilds the UI state only when something actually changed. The raw
     * snapshot is compared structurally against the last mapped one; identical
     * data keeps the previous [state] instance so Compose skips the whole
     * recomposition pass that a fresh instance would trigger.
     */
    private fun refresh(snapshot: FfiCoreSnapshot = core.snapshot()) {
        // The core event queue is drained on every refresh (unchanged state
        // included) so a later mutation's notifications start from an empty
        // queue, exactly as before the fast path.
        core.drainEvents()
        if (shouldSkipUiRebuild(
                snapshot = snapshot,
                lastSnapshot = lastSnapshot,
                publishedActiveAccountId = state.activeAccountId,
                activeAccountId = activeAccountId,
                settings = settingsCache,
                lastSettings = lastSettings,
                profiles = profilesCache,
                lastProfiles = lastProfiles,
                accountSettings = accountSettingsCache,
                lastAccountSettings = lastAccountSettings,
                appearance = appearanceCache,
                lastAppearance = lastAppearance,
                proxyLibrary = proxyLibraryCache,
                lastProxyLibrary = lastProxyLibrary,
                proxyAssignments = proxyAssignmentsCache,
                lastProxyAssignments = lastProxyAssignments,
                diagnosticsQuarantined = diagnosticsQuarantined,
                lastDiagnosticsQuarantined = lastDiagnosticsQuarantined,
                diagnosticsNoticeDismissed = quarantineNoticeDismissed,
                lastDiagnosticsNoticeDismissed = lastDiagnosticsNoticeDismissed,
            )
        ) {
            return
        }
        state = snapshotToUiState(snapshot, profilesCache)
        lastSnapshot = snapshot
        lastProfiles = profilesCache
        lastSettings = settingsCache
        lastAccountSettings = accountSettingsCache
        lastAppearance = appearanceCache
        lastProxyLibrary = proxyLibraryCache
        lastProxyAssignments = proxyAssignmentsCache
        lastDiagnosticsQuarantined = diagnosticsQuarantined
        lastDiagnosticsNoticeDismissed = quarantineNoticeDismissed
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
     * Saves one ordered snapshot. [snapshot] is the core state being persisted:
     * the poll path hands over the single snapshot it already captured so the
     * save never triggers an extra capture, while [persistNow] and lifecycle
     * saves keep their own capture (null). The native save re-serializes the
     * core under the session lock; because no mutation can run between the
     * capture and this call (the persistence mutex serializes writers), the
     * written state equals [snapshot] when one is provided.
     *
     * A mutation concurrent with native I/O keeps `dirty` set because its epoch
     * differs from the snapshot's captured epoch.
     */
    private suspend fun saveSnapshot(snapshot: FfiCoreSnapshot? = null): Boolean = persistenceMutex.withLock {
        val epochAtSave = persistenceTracker.captureSaveEpoch()
        val saved = runCatching { core.saveState(stateFile.absolutePath) }.isSuccess
        if (saved) {
            persistenceTracker.markPersisted(epochAtSave)
        }
        saved
    }

    private fun snapshotToUiState(
        snapshot: FfiCoreSnapshot = core.snapshot(),
        profiles: Map<Long, AccountProfile> = profilesCache,
    ): MindChatUiState {
        val mapping = mapSnapshotToUiState(
            snapshot = snapshot,
            profiles = profiles,
            activeAccountId = activeAccountId,
            connectingSince = connectingSince,
            settings = settingsCache,
            accountSettings = accountSettingsCache,
            appearance = appearanceCache,
            proxyLibrary = proxyLibraryCache,
            proxyAssignments = proxyAssignmentsCache,
            diagnosticsQuarantined = diagnosticsQuarantined,
            diagnosticsNoticeDismissed = quarantineNoticeDismissed,
            now = System.currentTimeMillis(),
            timestampFormatter = ::formatTimestamp,
        )
        activeAccountId = mapping.activeAccountId
        connectingSince.clear()
        connectingSince.putAll(mapping.connectingSince)
        return mapping.state
    }
}

/** Compose-previewable fallback for design previews and native-free debug tests. */
@Stable
class PreviewMindChatGateway(
    private val preferences: MindChatPreferences = InMemoryMindChatPreferences(),
    private val proxyLibraryStore: ProxyLibraryStore = InMemoryProxyLibraryStore(),
    private val credentialStore: ProxyCredentialStore = InMemoryProxyCredentialStore(),
    /**
     * Honest diagnostics seed: the preview has no native core, so its report
     * is an all-default (zero counts, not quarantined) report unless a test or
     * preview seeds one. Never contains secrets; the contract test uses this
     * to drive the quarantine notice state.
     */
    seedDiagnosticsReport: FfiDiagnosticsReport? = null,
) : MindChatGateway {
    override var state by mutableStateOf(
        seedPreviewState(
            preferences,
            proxyLibraryStore.readEntries(),
            proxyLibraryStore.readAssignments(),
            seedDiagnosticsReport,
        ),
    )
        private set

    /** The report [diagnosticsReport] returns; fixed at construction (no native core to re-read). */
    private val previewDiagnosticsReport: FfiDiagnosticsReport =
        seedDiagnosticsReport ?: zeroDiagnosticsReport()

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

    override fun registerAccount(
        jid: String,
        server: String,
        displayName: String,
        password: String,
    ): String? {
        val input = when (val validation = validateRegistration(jid, server, displayName, password)) {
            is RegistrationValidation.Refused -> return validation.refusal.toUiDetail()
            is RegistrationValidation.Valid -> validation.input
        }
        val nextId = (state.accounts.maxOfOrNull { it.id } ?: 0) + 1
        val account = AccountUi(
            id = nextId,
            jid = input.fullJid,
            displayName = input.displayName,
            presence = Presence.OFFLINE,
            connectionState = AccountConnectionState.CONNECTING,
            supportsGroupChats = true,
        )
        state = state.copy(
            accounts = state.accounts + account,
            activeAccountId = nextId,
        )
        return null
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

    override fun disconnectAccount(accountId: Long) {
        state = state.copy(
            accounts = state.accounts.map {
                if (it.id == accountId) {
                    it.copy(
                        connectionState = AccountConnectionState.OFFLINE,
                        connectionError = null,
                    )
                } else {
                    it
                }
            },
        )
    }

    override fun markConversationRead(conversationId: Long) {
        state = state.copy(
            conversations = state.conversations.map {
                if (it.id == conversationId) it.copy(unreadCount = 0) else it
            },
        )
    }

    override fun updateProfile(accountId: Long, profile: AccountProfile) {
        preferences.writeProfile(accountId, profile)
        // Mirror InMemoryMindChatPreferences.writeProfile: an all-default
        // profile removes the stored entry (and the state projection).
        val emptyProfile = profile.avatarUri == null && profile.statusMessage == null &&
            profile.accentKey == null && profile.bubbleStyle == null && profile.chatBackground == null
        state = state.copy(
            profiles = if (emptyProfile) state.profiles - accountId else state.profiles + (accountId to profile),
        )
    }

    /** Preview-only in-memory implementation of the gateway contract. */
    override fun deleteAccount(accountId: Long) {
        val remainingAccounts = state.accounts.filterNot { it.id == accountId }
        val remainingConversationIds = state.conversations
            .filterNot { it.accountId == accountId }
            .map(ConversationUi::id)
            .toSet()
        preferences.removeProfile(accountId)
        preferences.removeAccountSettings(accountId)
        state = state.copy(
            accounts = remainingAccounts,
            activeAccountId = nextActiveAccountId(state.accounts, accountId, state.activeAccountId),
            conversations = state.conversations.filterNot { it.accountId == accountId },
            contacts = state.contacts.filterNot { it.accountId == accountId },
            messagesByConversation = state.messagesByConversation.filterKeys { it in remainingConversationIds },
            profiles = state.profiles - accountId,
            accountSettings = state.accountSettings - accountId,
        )
    }

    /** Preview-only in-memory implementation of the gateway contract. */
    override fun renameAccount(accountId: Long, displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return
        state = state.copy(
            accounts = state.accounts.map {
                if (it.id == accountId) it.copy(displayName = trimmed) else it
            },
        )
    }

    /** Preview-only in-memory implementation of the gateway contract. */
    override fun deleteConversation(conversationId: Long) {
        state = state.copy(
            conversations = state.conversations.filterNot { it.id == conversationId },
            messagesByConversation = state.messagesByConversation - conversationId,
        )
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
        setSetting(SettingsSchema.dynamicColor, !state.settings.dynamicColor)
    }

    override fun setAppearance(appearance: AppearanceProfile) {
        val current = preferences.readCustomization()
        preferences.writeCustomization(current.copy(appearance = appearance))
        state = state.copy(appearance = appearance)
    }

    override fun toggleAppLock() {
        setSetting(SettingsSchema.appLockEnabled, !state.settings.appLockEnabled)
    }

    override fun <T> setSetting(key: SettingKey<T>, value: T) {
        val sanitized = sanitizeSetting(key, value)
        preferences.write(key, sanitized)
        state = state.copy(settings = SettingsSnapshot(preferences.readAll()))
    }

    override fun setAccountSetting(accountId: Long, key: SettingKey<*>, value: Any) {
        @Suppress("UNCHECKED_CAST")
        val sanitized = sanitizeSetting(key as SettingKey<Any>, value)
        preferences.writeAccountSetting(accountId, key, sanitized)
        state = state.copy(
            settings = SettingsSnapshot(preferences.readAll()),
            accountSettings = state.accountSettings +
                (accountId to SettingsSnapshot(preferences.readAccountSettings(accountId))),
        )
    }

    // --- Proxy library and per-account assignment (ROADMAP 6.3) ---------------
    // The preview mirrors the native behavior over the same stores without a
    // core: the library and assignment map are the same device-local state,
    // and probes honestly fail (there is no tunnel to ping in a JVM preview),
    // so the preview never fabricates latency.

    override fun addProxy(config: ProxyConfig, password: String?): Boolean {
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) return false
        val id = nextProxyId(state.proxyLibrary)
        if (password != null) credentialStore.store(id, password)
        proxyLibraryStore.writeEntries(
            state.proxyLibrary + ProxyLibraryEntry(id = id, host = config.host, port = config.port, kind = config.kind),
        )
        state = state.copy(proxyLibrary = proxyLibraryStore.readEntries())
        return true
    }

    override fun updateProxy(proxyId: String, config: ProxyConfig, password: String?): Boolean {
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) return false
        if (state.proxyLibrary.none { it.id == proxyId }) return false
        if (password != null) credentialStore.store(proxyId, password)
        val updated = state.proxyLibrary.map {
            if (it.id == proxyId) {
                ProxyLibraryEntry(id = proxyId, host = config.host, port = config.port, kind = config.kind)
            } else {
                it
            }
        }
        proxyLibraryStore.writeEntries(updated)
        state = state.copy(proxyLibrary = proxyLibraryStore.readEntries())
        return true
    }

    override fun deleteProxy(proxyId: String) {
        if (state.proxyLibrary.none { it.id == proxyId }) return
        val remaining = state.proxyLibrary.filterNot { it.id == proxyId }
        proxyLibraryStore.writeEntries(remaining)
        credentialStore.delete(proxyId)
        val assignments = state.proxyAssignments.filterValues { it != proxyId }
        proxyLibraryStore.writeAssignments(assignments)
        state = state.copy(proxyLibrary = remaining, proxyAssignments = assignments)
    }

    override fun pingProxy(proxyId: String, password: String?): ProxyProbeResult {
        val entry = state.proxyLibrary.firstOrNull { it.id == proxyId }
            ?: return ProxyProbeResult(ok = false, latencyMs = null, error = "unknown proxy")
        if (password != null) credentialStore.store(proxyId, password)
        val result = testProxy(entry.toConfig(), credentialStore.load(proxyId))
        val updated = state.proxyLibrary.map {
            if (it.id == proxyId) {
                it.copy(
                    status = ProxyStatus(
                        latencyMs = if (result.ok) result.latencyMs else null,
                        error = result.error,
                    ),
                )
            } else {
                it
            }
        }
        proxyLibraryStore.writeEntries(updated)
        state = state.copy(proxyLibrary = updated)
        return result
    }

    override fun setAccountProxy(accountId: Long, config: ProxyConfig?, password: String?): Boolean {
        if (config == null) {
            val assignments = state.proxyAssignments - accountId
            proxyLibraryStore.writeAssignments(assignments)
            state = state.copy(proxyAssignments = assignments)
            return true
        }
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) return false
        val entry = state.proxyLibrary.findByConfig(config) ?: return false
        if (password != null) credentialStore.store(entry.id, password)
        val assignments = state.proxyAssignments + (accountId to entry.id)
        proxyLibraryStore.writeAssignments(assignments)
        state = state.copy(proxyAssignments = assignments)
        return true
    }

    override fun accountProxy(accountId: Long): ProxyConfig? =
        state.assignedProxy(accountId)?.toConfig()

    override fun testProxy(config: ProxyConfig, password: String?): ProxyProbeResult {
        if (validateProxyConfig(config.host, config.port, config.kind) is ProxyValidation.Refused) {
            return ProxyProbeResult(ok = false, latencyMs = null, error = "invalid proxy configuration")
        }
        // No native core in the preview: a probe cannot open a tunnel, and a
        // fake latency is forbidden, so the honest result is a failure.
        return ProxyProbeResult(ok = false, latencyMs = null, error = "proxy probes require the native core")
    }

    // --- Diagnostics (ROADMAP 6.5) ------------------------------------------

    override fun diagnosticsReport(): FfiDiagnosticsReport = previewDiagnosticsReport

    override fun dismissDiagnosticsNotice() {
        if (state.diagnosticsNoticeDismissed) return
        preferences.writeQuarantineNoticeDismissed(true)
        state = state.copy(diagnosticsNoticeDismissed = true)
    }
}

/**
 * The honest preview report: all counts zero, no persistence metadata, never
 * quarantined. The preview has no native core and no state file, so any other
 * value would be fabricated; secrets are structurally impossible (the report
 * type carries none).
 */
private fun zeroDiagnosticsReport(): FfiDiagnosticsReport = FfiDiagnosticsReport(
    accountCount = 0uL,
    contactCount = 0uL,
    conversationCount = 0uL,
    messageCount = 0uL,
    reactionCount = 0uL,
    statePath = null,
    stateSizeBytes = null,
    stateSchemaVersion = null,
    stateQuarantined = false,
    stateLastSavedAtEpochMs = null,
    stateLastLoadedAtEpochMs = null,
)

/**
 * Seeds the preview state: the demo accounts plus any persisted settings,
 * profiles, account settings, proxy library and proxy assignments, so a
 * gateway instance created over the same stores restores everything the
 * previous instance wrote.
 */
private fun seedPreviewState(
    preferences: MindChatPreferences,
    proxyLibrary: List<ProxyLibraryEntry> = emptyList(),
    proxyAssignments: Map<Long, String> = emptyMap(),
    seedDiagnosticsReport: FfiDiagnosticsReport? = null,
): MindChatUiState {
    val seed = seedState()
    return seed.copy(
        settings = SettingsSnapshot(preferences.readAll()),
        profiles = preferences.readProfiles(),
        appearance = preferences.readCustomization().appearance,
        accountSettings = seed.accounts.associate { account ->
            account.id to SettingsSnapshot(preferences.readAccountSettings(account.id))
        },
        proxyLibrary = proxyLibrary,
        proxyAssignments = proxyAssignments,
        diagnosticsQuarantined = seedDiagnosticsReport?.stateQuarantined ?: false,
        diagnosticsNoticeDismissed = preferences.readQuarantineNoticeDismissed(),
    )
}

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

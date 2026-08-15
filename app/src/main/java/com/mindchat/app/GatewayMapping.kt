package com.mindchat.app

import com.mindchat.core.FfiConnectionState
import com.mindchat.core.FfiContactPresence
import com.mindchat.core.FfiConversationKind
import com.mindchat.core.FfiCoreSnapshot
import com.mindchat.core.FfiDeliveryState
import com.mindchat.core.FfiMessageDirection
import com.mindchat.core.FfiProtocolCapability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared snapshot-to-UI mapping behind the [MindChatGateway] contract.
 *
 * Both implementations of the public interface (`NativeMindChatGateway` and
 * `PreviewMindChatGateway`) derive UI-visible values from the raw FFI snapshot
 * with exactly the same pure functions here, so the preview cannot drift from
 * the native behavior. [now] and [timestampFormatter] are injected to keep the
 * mapping deterministic; the gateway instance feeds wall-clock time and the
 * locale formatter.
 */

/**
 * Pure mapping from the raw FFI snapshot to the Compose UI model. This is the
 * data contract between the Rust core and the Android UI, extracted so both
 * the semantics (presence translation, stall detection, reaction grouping,
 * unread/preview derivation) and the [connectingSince] bookkeeping are
 * deterministic and unit-testable. [now] and [timestampFormatter] are injected
 * to keep the mapping pure; the instance method feeds wall-clock time and the
 * locale formatter and swaps in the returned [SnapshotMapping.connectingSince].
 */
internal data class SnapshotMapping(
    val state: MindChatUiState,
    val connectingSince: Map<Long, Long>,
    val activeAccountId: Long,
)

internal fun mapSnapshotToUiState(
    snapshot: FfiCoreSnapshot,
    profiles: Map<Long, AccountProfile>,
    activeAccountId: Long,
    connectingSince: Map<Long, Long>,
    settings: SettingsSnapshot,
    accountSettings: Map<Long, SettingsSnapshot>,
    appearance: AppearanceProfile = AppearanceProfile(),
    proxyLibrary: List<ProxyLibraryEntry> = emptyList(),
    proxyAssignments: Map<Long, String> = emptyMap(),
    diagnosticsQuarantined: Boolean = false,
    diagnosticsNoticeDismissed: Boolean = false,
    now: Long,
    timestampFormatter: (Long) -> String,
): SnapshotMapping {
    val tracking = connectingSince.toMutableMap()
    val accounts = snapshot.accounts.map { account ->
        val accountId = account.id.toLong()
        val connectionState = account.connectionState.toUiModel()
        // Record when each account entered CONNECTING so a connection that
        // never reaches a terminal state can be surfaced as stalled.
        val connectingStart = if (connectionState == AccountConnectionState.CONNECTING) {
            tracking.putIfAbsent(accountId, now)
            tracking[accountId] ?: now
        } else {
            tracking.remove(accountId)
            null
        }
        AccountUi(
            id = accountId,
            jid = account.jid,
            displayName = account.displayName,
            presence = when (account.connectionState) {
                FfiConnectionState.ONLINE -> Presence.ONLINE
                FfiConnectionState.OFFLINE,
                FfiConnectionState.CONNECTING,
                FfiConnectionState.FAILED,
                -> Presence.OFFLINE
            },
            connectionState = connectionState,
            supportsGroupChats = account.capabilities.contains(FfiProtocolCapability.MULTI_USER_CHAT),
            connectionError = account.connectionError,
            connectionStalled = connectionState == AccountConnectionState.CONNECTING &&
                connectingStart != null &&
                now - connectingStart > STALL_THRESHOLD_MS,
            disconnectKind = account.disconnectKind,
        )
    }
    val resolvedActiveAccountId =
        if (activeAccountId == 0L || accounts.none { it.id == activeAccountId }) {
            accounts.firstOrNull()?.id ?: 0L
        } else {
            activeAccountId
        }
    // Index reactions by message id once; the previous per-message
    // `filter` made message mapping O(messages x reactions) and allocated
    // a list per message on every rebuild.
    val reactionsByMessage = snapshot.reactions.groupBy { it.messageId }
    val messagesByConversation = snapshot.messages
        .groupBy { it.conversationId.toLong() }
        .mapValues { (_, messages) ->
            messages.map { message ->
                val reactionLabels = reactionsByMessage[message.id]
                    ?.groupingBy { it.emoji }
                    ?.eachCount()
                    ?.map { (emoji, count) -> "$emoji $count" }
                    ?: emptyList()
                MessageUi(
                    id = message.id.toLong(),
                    sender = message.sender,
                    body = message.body,
                    timestamp = timestampFormatter(message.sentAtEpochMs.toLong()),
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
            timestamp = timestampFormatter(conversation.lastActivityEpochMs.toLong()),
            unreadCount = conversation.unreadCount.toInt(),
            isGroup = conversation.kind == FfiConversationKind.MULTI_USER_CHAT,
            lastActivityEpochMs = conversation.lastActivityEpochMs.toLong(),
        )
    }
    return SnapshotMapping(
        state = MindChatUiState(
            accounts = accounts,
            contacts = contacts,
            activeAccountId = resolvedActiveAccountId,
            conversations = conversations,
            messagesByConversation = messagesByConversation,
            profiles = profiles,
            settings = settings,
            accountSettings = accountSettings,
            appearance = appearance,
            proxyLibrary = proxyLibrary,
            proxyAssignments = proxyAssignments,
            diagnosticsQuarantined = diagnosticsQuarantined,
            diagnosticsNoticeDismissed = diagnosticsNoticeDismissed,
        ),
        connectingSince = tracking,
        activeAccountId = resolvedActiveAccountId,
    )
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

/**
 * Default timestamp formatter used by the native gateway. Locale formatting is
 * a JVM concern (no Android APIs), so it lives with the pure mapping and stays
 * injectable for tests.
 *
 * P0-2: one [DateTimeFormatter] is cached per locale instead of rebuilding a
 * `DateFormat` (synchronized factory call) and a `Date` per message on every
 * full remap. `DateTimeFormatter.ofLocalizedTime(SHORT)` renders the same
 * locale-specific short time as `DateFormat.getTimeInstance(SHORT)` on both
 * the JVM (CLDR) and Android (ICU), so output is unchanged; the golden tests
 * in SnapshotMappingTest pin that equality.
 */
private val timestampFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

internal fun formatTimestamp(epochMs: Long): String {
    val locale = Locale.getDefault()
    val formatter = timestampFormatters.getOrPut(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
}

/**
 * Pure decision behind [NativeMindChatGateway.refresh]'s no-op fast path.
 *
 * A poll cycle can skip rebuilding the UI state only when every input that
 * feeds the mapping is unchanged: the raw snapshot, the active account, the
 * settings snapshot, the per-account settings, the stored profiles, the
 * global appearance profile and the proxy library/assignments (ROADMAP 6.3).
 * The appearance keys live outside the keyed
 * [SettingsSnapshot] (they are stored directly by [MindChatPreferences]), so
 * the appearance is compared here explicitly: an appearance-only change must
 * rebuild the UI (R7 in ROADMAP §5.1). Snapshots with an account in CONNECTING
 * always rebuild so the wall-clock stall detection keeps working. The
 * structural snapshot comparison is a serialize-free, allocation-free
 * field-by-field compare (same lengths, then per-record equals).
 */
internal fun shouldSkipUiRebuild(
    snapshot: FfiCoreSnapshot,
    lastSnapshot: FfiCoreSnapshot?,
    publishedActiveAccountId: Long,
    activeAccountId: Long,
    settings: SettingsSnapshot,
    lastSettings: SettingsSnapshot,
    profiles: Map<Long, AccountProfile>,
    lastProfiles: Map<Long, AccountProfile>,
    accountSettings: Map<Long, SettingsSnapshot> = emptyMap(),
    lastAccountSettings: Map<Long, SettingsSnapshot> = emptyMap(),
    appearance: AppearanceProfile = AppearanceProfile(),
    lastAppearance: AppearanceProfile = AppearanceProfile(),
    proxyLibrary: List<ProxyLibraryEntry> = emptyList(),
    lastProxyLibrary: List<ProxyLibraryEntry> = emptyList(),
    proxyAssignments: Map<Long, String> = emptyMap(),
    lastProxyAssignments: Map<Long, String> = emptyMap(),
    diagnosticsQuarantined: Boolean = false,
    lastDiagnosticsQuarantined: Boolean = false,
    diagnosticsNoticeDismissed: Boolean = false,
    lastDiagnosticsNoticeDismissed: Boolean = false,
): Boolean {
    if (snapshot !== lastSnapshot) {
        if (lastSnapshot == null || snapshot != lastSnapshot) return false
    }
    if (activeAccountId != publishedActiveAccountId) return false
    if (settings != lastSettings) return false
    if (profiles != lastProfiles) return false
    if (accountSettings != lastAccountSettings) return false
    if (appearance != lastAppearance) return false
    if (proxyLibrary != lastProxyLibrary) return false
    if (proxyAssignments != lastProxyAssignments) return false
    if (diagnosticsQuarantined != lastDiagnosticsQuarantined) return false
    if (diagnosticsNoticeDismissed != lastDiagnosticsNoticeDismissed) return false
    return snapshot.accounts.none { it.connectionState == FfiConnectionState.CONNECTING }
}

package com.mindchat.app

import com.mindchat.core.FfiAccount
import com.mindchat.core.FfiConnectionState
import com.mindchat.core.FfiContact
import com.mindchat.core.FfiContactPresence
import com.mindchat.core.FfiConversation
import com.mindchat.core.FfiConversationKind
import com.mindchat.core.FfiCoreSnapshot
import com.mindchat.core.FfiDeliveryState
import com.mindchat.core.FfiMessage
import com.mindchat.core.FfiMessageDirection
import com.mindchat.core.FfiMessageKind
import com.mindchat.core.FfiProtocolCapability
import com.mindchat.core.FfiReaction
import com.mindchat.core.FfiRosterSubscription
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the no-op fast path behind [NativeMindChatGateway.refresh]: a poll
 * cycle must skip rebuilding the UI state only when every input that feeds the
 * mapping is unchanged. All fixtures are fresh instances so equality is
 * structural, never reference identity.
 */
class SnapshotDiffingTest {
    @Test
    fun identicalSnapshotsSkipRebuild() {
        assertTrue(skipDecision(snapshot(), snapshot()))
    }

    @Test
    fun sameInstanceSnapshotSkipsRebuild() {
        val same = snapshot()
        assertTrue(skipDecision(snapshot = same, lastSnapshot = same))
    }

    @Test
    fun nullLastSnapshotForcesRebuild() {
        assertFalse(skipDecision(snapshot(), lastSnapshot = null))
    }

    @Test
    fun changedMessageBodyForcesRebuild() {
        val changed = snapshot(messages = listOf(message(1, body = "edited")))
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun addedMessageForcesRebuild() {
        val changed = snapshot(messages = listOf(message(1), message(2)))
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun changedMessageTimestampForcesRebuild() {
        val changed = snapshot(messages = listOf(message(1, sentAtEpochMs = 9_999L)))
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun changedDeliveryStateForcesRebuild() {
        val changed = snapshot(
            messages = listOf(message(1, direction = FfiMessageDirection.OUTGOING, deliveryState = FfiDeliveryState.SENT)),
        )
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun changedReactionForcesRebuild() {
        val changed = snapshot(reactions = listOf(reaction(1, emoji = "❤️")))
        assertFalse(skipDecision(changed, snapshot(reactions = listOf(reaction(1, emoji = "👍")))))
    }

    @Test
    fun changedContactPresenceForcesRebuild() {
        val changed = snapshot(contacts = listOf(contact(1, presence = FfiContactPresence.AWAY)))
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun changedConversationUnreadCountForcesRebuild() {
        val changed = snapshot(conversations = listOf(conversation(1, unreadCount = 3u)))
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun changedAccountConnectionStateForcesRebuild() {
        val changed = snapshot(accounts = listOf(account(1, state = FfiConnectionState.FAILED)))
        assertFalse(skipDecision(changed, snapshot()))
    }

    @Test
    fun changedActiveAccountForcesRebuild() {
        assertFalse(
            skipDecision(
                snapshot(),
                snapshot(),
                publishedActiveAccountId = 1L,
                activeAccountId = 2L,
            ),
        )
    }

    @Test
    fun changedSettingsForcesRebuild() {
        assertFalse(
            skipDecision(
                snapshot(),
                snapshot(),
                settings = SettingsSnapshot(mapOf(SettingsSchema.comfortableLayout to false)),
                lastSettings = SettingsSnapshot(),
            ),
        )
    }

    @Test
    fun changedProfilesForcesRebuild() {
        assertFalse(
            skipDecision(
                snapshot(),
                snapshot(),
                profiles = mapOf(1L to AccountProfile(statusMessage = "busy")),
                lastProfiles = emptyMap(),
            ),
        )
    }

    @Test
    fun changedAppearanceForcesRebuild() {
        assertFalse(
            skipDecision(
                snapshot(),
                snapshot(),
                appearance = AppearanceProfile(shapeScale = ShapeScale.COMPACT),
                lastAppearance = AppearanceProfile(),
            ),
        )
    }

    @Test
    fun identicalAppearanceSkipsRebuild() {
        assertTrue(
            skipDecision(
                snapshot(),
                snapshot(),
                appearance = AppearanceProfile(bubbleStyle = BubbleStyle.ROUNDED),
                lastAppearance = AppearanceProfile(bubbleStyle = BubbleStyle.ROUNDED),
            ),
        )
    }

    @Test
    fun connectingAccountAlwaysRebuildsForStallDetection() {
        // The snapshot is structurally identical to the previous one, but an
        // account stuck in CONNECTING needs periodic rebuilds so the wall-clock
        // stall detection can surface after 35 s.
        val connecting = snapshot(accounts = listOf(account(1, state = FfiConnectionState.CONNECTING)))
        assertFalse(skipDecision(connecting, connecting))
    }

    // --- fixtures ---------------------------------------------------------

    private fun account(id: Long, state: FfiConnectionState = FfiConnectionState.ONLINE) = FfiAccount(
        id = id.toULong(),
        jid = "user$id@example.org",
        server = "example.org",
        displayName = "User $id",
        connectionState = state,
        capabilities = listOf(FfiProtocolCapability.MULTI_USER_CHAT),
        connectionError = null,
        disconnectKind = null,
    )

    private fun contact(id: Long, presence: FfiContactPresence = FfiContactPresence.ONLINE) = FfiContact(
        accountId = 1uL,
        jid = "contact$id@example.org",
        displayName = "Contact $id",
        presence = presence,
        status = null,
        subscription = FfiRosterSubscription.MUTUAL,
    )

    private fun conversation(id: Long, unreadCount: UInt = 0u) = FfiConversation(
        id = id.toULong(),
        accountId = 1uL,
        kind = FfiConversationKind.DIRECT,
        address = "contact$id@example.org",
        title = "Chat $id",
        unreadCount = unreadCount,
        lastActivityEpochMs = 1_000uL + id.toULong(),
    )

    private fun message(
        id: Long,
        body: String = "hello $id",
        sentAtEpochMs: Long = 2_000L + id,
        direction: FfiMessageDirection = FfiMessageDirection.INCOMING,
        deliveryState: FfiDeliveryState = FfiDeliveryState.DELIVERED,
    ) = FfiMessage(
        id = id.toULong(),
        conversationId = 1uL,
        sender = "user@example.org",
        body = body,
        direction = direction,
        kind = FfiMessageKind.TEXT,
        sentAtEpochMs = sentAtEpochMs.toULong(),
        deliveryState = deliveryState,
        inReplyTo = null,
        attachment = null,
    )

    private fun reaction(id: Long, emoji: String = "👍") = FfiReaction(
        id = id.toULong(),
        messageId = 1uL,
        emoji = emoji,
        actor = "contact1@example.org",
    )

    private fun snapshot(
        accounts: List<FfiAccount> = listOf(account(1)),
        contacts: List<FfiContact> = listOf(contact(1)),
        conversations: List<FfiConversation> = listOf(conversation(1)),
        messages: List<FfiMessage> = listOf(message(1)),
        reactions: List<FfiReaction> = emptyList(),
    ) = FfiCoreSnapshot(accounts, contacts, conversations, messages, reactions)

    private fun skipDecision(
        snapshot: FfiCoreSnapshot,
        lastSnapshot: FfiCoreSnapshot? = snapshot,
        publishedActiveAccountId: Long = 1L,
        activeAccountId: Long = 1L,
        settings: SettingsSnapshot = SettingsSnapshot(),
        lastSettings: SettingsSnapshot = SettingsSnapshot(),
        profiles: Map<Long, AccountProfile> = emptyMap(),
        lastProfiles: Map<Long, AccountProfile> = emptyMap(),
        appearance: AppearanceProfile = AppearanceProfile(),
        lastAppearance: AppearanceProfile = AppearanceProfile(),
    ): Boolean = shouldSkipUiRebuild(
        snapshot = snapshot,
        lastSnapshot = lastSnapshot,
        publishedActiveAccountId = publishedActiveAccountId,
        activeAccountId = activeAccountId,
        settings = settings,
        lastSettings = lastSettings,
        profiles = profiles,
        lastProfiles = lastProfiles,
        appearance = appearance,
        lastAppearance = lastAppearance,
    )
}

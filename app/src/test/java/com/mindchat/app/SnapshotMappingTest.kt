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
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Kotlin↔Rust data contract: the pure mapping from an [FfiCoreSnapshot]
 * (the exact generated DTO the Android app receives) to the Compose UI model.
 * Time and timestamp formatting are injected so every rule is deterministic:
 * presence translation, CONNECTING stall detection bookkeeping, active-account
 * resolution, message direction/delivery, reaction grouping, conversation
 * preview/unread derivation, and contact presence.
 */
class SnapshotMappingTest {

    private val formatter: (Long) -> String = { "t$it" }

    private fun map(
        accounts: List<FfiAccount> = listOf(account(1)),
        contacts: List<FfiContact> = listOf(contact(1)),
        conversations: List<FfiConversation> = listOf(conversation(1)),
        messages: List<FfiMessage> = listOf(message(1)),
        reactions: List<FfiReaction> = emptyList(),
        activeAccountId: Long = 1L,
        connectingSince: Map<Long, Long> = emptyMap(),
        settings: SettingsSnapshot = SettingsSnapshot(),
        accountSettings: Map<Long, SettingsSnapshot> = emptyMap(),
        appearance: AppearanceProfile = AppearanceProfile(),
        now: Long = 100_000L,
    ) = mapSnapshotToUiState(
        snapshot = FfiCoreSnapshot(accounts, contacts, conversations, messages, reactions),
        profiles = mapOf(1L to AccountProfile(statusMessage = "busy")),
        activeAccountId = activeAccountId,
        connectingSince = connectingSince,
        settings = settings,
        accountSettings = accountSettings,
        appearance = appearance,
        now = now,
        timestampFormatter = formatter,
    )

    // --- Accounts -------------------------------------------------------------

    @Test
    fun accountPresenceAndConnectionStateAreTranslated() {
        val accounts = listOf(
            account(1, state = FfiConnectionState.ONLINE),
            account(2, state = FfiConnectionState.CONNECTING),
            account(3, state = FfiConnectionState.FAILED, error = "boom"),
            account(4, state = FfiConnectionState.OFFLINE),
        )
        val state = map(accounts = accounts, activeAccountId = 1L).state

        assertEquals(Presence.ONLINE, state.accounts[0].presence)
        assertEquals(AccountConnectionState.ONLINE, state.accounts[0].connectionState)
        assertEquals(Presence.OFFLINE, state.accounts[1].presence)
        assertEquals(AccountConnectionState.CONNECTING, state.accounts[1].connectionState)
        assertEquals(Presence.OFFLINE, state.accounts[2].presence)
        assertEquals(AccountConnectionState.FAILED, state.accounts[2].connectionState)
        assertEquals("boom", state.accounts[2].connectionError)
        assertEquals(AccountConnectionState.OFFLINE, state.accounts[3].connectionState)
        assertFalse(state.accounts[0].connectionStalled)
        assertFalse(state.accounts[1].connectionStalled)
    }

    @Test
    fun groupChatCapabilityDrivesSupportsGroupChats() {
        val withMuc = account(1, capabilities = listOf(FfiProtocolCapability.MULTI_USER_CHAT))
        val withoutMuc = account(2, capabilities = emptyList())

        val state = map(accounts = listOf(withMuc, withoutMuc), activeAccountId = 1L).state

        assertTrue(state.accounts[0].supportsGroupChats)
        assertFalse(state.accounts[1].supportsGroupChats)
    }

    // --- Stall detection ------------------------------------------------------

    @Test
    fun connectingAccountIsTrackedAndNeverStalledBelowThreshold() {
        val connecting = account(1, state = FfiConnectionState.CONNECTING)
        val mapping = map(accounts = listOf(connecting), connectingSince = emptyMap(), now = 100_000L)

        assertFalse(mapping.state.accounts.single().connectionStalled)
        assertEquals(100_000L, mapping.connectingSince[1L])
    }

    @Test
    fun connectingAccountStallsPastThirtyFiveSeconds() {
        val connecting = account(1, state = FfiConnectionState.CONNECTING)
        val first = map(accounts = listOf(connecting), connectingSince = emptyMap(), now = 100_000L)
        val later = map(
            accounts = listOf(connecting),
            connectingSince = first.connectingSince,
            now = 100_000L + STALL_THRESHOLD_MS + 1L,
        )

        assertTrue(later.state.accounts.single().connectionStalled)
        assertEquals(100_000L, later.connectingSince[1L])
    }

    @Test
    fun terminalStateClearsConnectingTracking() {
        val connecting = map(accounts = listOf(account(1, state = FfiConnectionState.CONNECTING)), now = 100_000L)
        val terminal = map(
            accounts = listOf(account(1, state = FfiConnectionState.ONLINE)),
            connectingSince = connecting.connectingSince,
        )

        assertFalse(terminal.connectingSince.containsKey(1L))
    }

    // --- Active account -------------------------------------------------------

    @Test
    fun validActiveAccountIsKept() {
        assertEquals(1L, map(activeAccountId = 1L).activeAccountId)
        assertEquals(1L, map(activeAccountId = 1L).state.activeAccountId)
    }

    @Test
    fun zeroActiveAccountResolvesToFirst() {
        assertEquals(1L, map(activeAccountId = 0L).activeAccountId)
    }

    @Test
    fun staleActiveAccountResolvesToFirst() {
        val mapping = map(accounts = listOf(account(1), account(2)), activeAccountId = 2L)
        assertEquals(2L, mapping.activeAccountId)

        val stale = map(accounts = listOf(account(1)), activeAccountId = 2L)
        assertEquals(1L, stale.activeAccountId)
    }

    @Test
    fun emptyAccountsResolveActiveToZero() {
        val mapping = map(accounts = emptyList(), contacts = emptyList(), conversations = emptyList(), messages = emptyList())
        assertEquals(0L, mapping.activeAccountId)
        assertEquals(0L, mapping.state.activeAccountId)
    }

    // --- Messages and reactions ----------------------------------------------

    @Test
    fun outgoingMessagesCarryDeliveryAndIncomingDoNot() {
        val messages = listOf(
            message(1, direction = FfiMessageDirection.OUTGOING, deliveryState = FfiDeliveryState.READ),
            message(2, direction = FfiMessageDirection.INCOMING, deliveryState = FfiDeliveryState.READ),
        )
        val state = map(messages = messages, conversations = listOf(conversation(1))).state

        val mapped = state.messagesByConversation.getValue(1L)
        assertTrue(mapped[0].mine)
        assertEquals(MessageDelivery.READ, mapped[0].delivery)
        assertEquals("t2001", mapped[0].timestamp)
        assertFalse(mapped[1].mine)
        assertNull(mapped[1].delivery)
    }

    @Test
    fun reactionsAreGroupedByEmojiWithCounts() {
        val reactions = listOf(
            reaction(1, emoji = "👍"),
            reaction(1, emoji = "👍"),
            reaction(1, emoji = "❤️"),
            reaction(2, messageId = 2L, emoji = "👍"),
        )
        val state = map(messages = listOf(message(1), message(2)), reactions = reactions).state

        val first = state.messagesByConversation.getValue(1L)[0]
        assertEquals(listOf("👍 2", "❤️ 1"), first.reactions)
        assertEquals(listOf("👍 1"), state.messagesByConversation.getValue(1L)[1].reactions)
    }

    @Test
    fun messagesAreGroupedByConversation() {
        val messages = listOf(
            message(1, conversationId = 1L),
            message(2, conversationId = 1L),
            message(3, conversationId = 2L),
        )
        val state = map(
            messages = messages,
            conversations = listOf(conversation(1), conversation(2)),
        ).state

        assertEquals(2, state.messagesByConversation.getValue(1L).size)
        assertEquals(1, state.messagesByConversation.getValue(2L).size)
    }

    // --- Conversations --------------------------------------------------------

    @Test
    fun conversationPreviewIsTheLastMessageBody() {
        val state = map(
            messages = listOf(
                message(1, conversationId = 1L, body = "first"),
                message(2, conversationId = 1L, body = "latest"),
            ),
            conversations = listOf(conversation(1)),
        ).state

        assertEquals("latest", state.conversations.single().preview)
    }

    @Test
    fun conversationKindAndUnreadCountAreMapped() {
        val conversations = listOf(
            conversation(1, kind = FfiConversationKind.DIRECT, unreadCount = 4u),
            conversation(2, kind = FfiConversationKind.MULTI_USER_CHAT, unreadCount = 0u),
        )
        val state = map(conversations = conversations).state

        assertFalse(state.conversations[0].isGroup)
        assertEquals(4, state.conversations[0].unreadCount)
        assertTrue(state.conversations[1].isGroup)
        assertEquals(1002L, state.conversations[1].lastActivityEpochMs)
        assertEquals("t1002", state.conversations[1].timestamp)
    }

    // --- Contacts -------------------------------------------------------------

    @Test
    fun contactPresenceAndStatusAreMapped() {
        val contacts = listOf(
            contact(1, presence = FfiContactPresence.ONLINE, status = "here"),
            contact(2, presence = FfiContactPresence.AWAY),
            contact(3, presence = FfiContactPresence.DO_NOT_DISTURB),
            contact(4, presence = FfiContactPresence.OFFLINE),
        )
        val state = map(contacts = contacts).state

        assertEquals(Presence.ONLINE, state.contacts[0].presence)
        assertEquals("here", state.contacts[0].status)
        assertEquals(Presence.AWAY, state.contacts[1].presence)
        assertEquals(Presence.DO_NOT_DISTURB, state.contacts[2].presence)
        assertEquals(Presence.OFFLINE, state.contacts[3].presence)
    }

    // --- Profiles and settings --------------------------------------------------

    @Test
    fun profilesSettingsAndAppearanceFlowIntoTheState() {
        val settings = SettingsSnapshot(
            mapOf(
                SettingsSchema.dynamicColor to false,
                SettingsSchema.appLockEnabled to true,
            ),
        )
        val appearance = AppearanceProfile(
            shapeScale = ShapeScale.COMPACT,
            bubbleStyle = BubbleStyle.ROUNDED,
        )
        val mapping = map(settings = settings, appearance = appearance)

        assertEquals(AccountProfile(statusMessage = "busy"), mapping.state.profiles[1L])
        assertFalse(mapping.state.dynamicColor)
        assertTrue(mapping.state.appLockEnabled)
        assertEquals(appearance, mapping.state.appearance)
    }

    @Test
    fun appearanceDefaultsFlowIntoTheState() {
        val mapping = map()
        assertEquals(AppearanceProfile(), mapping.state.appearance)
    }

    @Test
    fun accountSettingsFlowIntoTheState() {
        val mapping = map(
            accountSettings = mapOf(
                1L to SettingsSnapshot(mapOf(SettingsSchema.appLockEnabled to true)),
            ),
        )

        assertEquals(true, mapping.state.accountSettings[1L]?.get(SettingsSchema.appLockEnabled))
    }

    // --- Timestamp formatting (P0-2) -----------------------------------------

    @Test
    fun cachedTimestampFormatterMatchesLegacyDateFormatOutput() {
        // P0-2: the cached java.time formatter must render exactly what the
        // legacy per-call DateFormat factory produced, across representative
        // locales and times of day (AM/PM vs 24-hour layouts).
        val originalLocale = Locale.getDefault()
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val locales = listOf(Locale.US, Locale.UK, Locale.GERMANY, Locale.FRANCE, Locale.JAPAN, Locale.forLanguageTag("ru-RU"))
            val epochs = longArrayOf(0L, 45_240_000L, 1_700_000_000_000L, 86_400_000L + 45_234_000L)
            for (locale in locales) {
                Locale.setDefault(locale)
                val legacy = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
                for (epoch in epochs) {
                    assertEquals(legacy.format(Date(epoch)), formatTimestamp(epoch))
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun cachedTimestampFormatterRendersGoldenLocaleOutput() {
        // Golden strings for Locale.US in UTC: the exact output the mapping
        // contract must keep producing after the P0-2 formatter cache.
        val originalLocale = Locale.getDefault()
        val originalZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("12:00 AM", formatTimestamp(0L))
            assertEquals("12:34 PM", formatTimestamp(45_240_000L))
            assertEquals("10:13 PM", formatTimestamp(1_700_000_000_000L))
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }

    // --- fixtures -------------------------------------------------------------

    private fun account(
        id: Long,
        state: FfiConnectionState = FfiConnectionState.ONLINE,
        error: String? = null,
        capabilities: List<FfiProtocolCapability> = listOf(FfiProtocolCapability.MULTI_USER_CHAT),
    ) = FfiAccount(
        id = id.toULong(),
        jid = "user$id@example.org",
        server = "example.org",
        displayName = "User $id",
        connectionState = state,
        capabilities = capabilities,
        connectionError = error,
    )

    private fun contact(
        id: Long,
        presence: FfiContactPresence = FfiContactPresence.ONLINE,
        status: String? = null,
    ) = FfiContact(
        accountId = 1uL,
        jid = "contact$id@example.org",
        displayName = "Contact $id",
        presence = presence,
        status = status,
        subscription = FfiRosterSubscription.MUTUAL,
    )

    private fun conversation(
        id: Long,
        kind: FfiConversationKind = FfiConversationKind.DIRECT,
        unreadCount: UInt = 0u,
    ) = FfiConversation(
        id = id.toULong(),
        accountId = 1uL,
        kind = kind,
        address = "contact$id@example.org",
        title = "Chat $id",
        unreadCount = unreadCount,
        lastActivityEpochMs = 1_000uL + id.toULong(),
    )

    private fun message(
        id: Long,
        conversationId: Long = 1L,
        body: String = "hello $id",
        direction: FfiMessageDirection = FfiMessageDirection.INCOMING,
        deliveryState: FfiDeliveryState = FfiDeliveryState.DELIVERED,
    ) = FfiMessage(
        id = id.toULong(),
        conversationId = conversationId.toULong(),
        sender = "user@example.org",
        body = body,
        direction = direction,
        kind = FfiMessageKind.TEXT,
        sentAtEpochMs = (2_000L + id).toULong(),
        deliveryState = deliveryState,
        inReplyTo = null,
        attachment = null,
    )

    private fun reaction(id: Long, messageId: Long = 1L, emoji: String = "👍") = FfiReaction(
        id = id.toULong(),
        messageId = messageId.toULong(),
        emoji = emoji,
        actor = "contact1@example.org",
    )
}

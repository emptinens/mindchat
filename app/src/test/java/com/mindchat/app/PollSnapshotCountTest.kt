package com.mindchat.app

import com.mindchat.core.FfiAccount
import com.mindchat.core.FfiConnectionState
import com.mindchat.core.FfiContact
import com.mindchat.core.FfiContactPresence
import com.mindchat.core.FfiConversation
import com.mindchat.core.FfiConversationKind
import com.mindchat.core.FfiCoreEvent
import com.mindchat.core.FfiCoreSnapshot
import com.mindchat.core.FfiProtocolCapability
import com.mindchat.core.FfiRosterSubscription
import com.mindchat.core.MindChatCoreHandle
import com.mindchat.core.NoHandle
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-4 acceptance: a changed poll runs exactly one `core.snapshot()` (down
 * from two: the flush-decision capture and the post-flush result), and the
 * unchanged poll runs zero. The fake core is a hand-written [MindChatCoreHandle]
 * stub (UniFFI's `NoHandle` constructor) that counts snapshot, flush and save
 * calls; no mocking library is involved.
 */
class PollSnapshotCountTest {

    private val emptySnapshot = FfiCoreSnapshot(
        accounts = emptyList(),
        contacts = emptyList(),
        conversations = emptyList(),
        messages = emptyList(),
        reactions = emptyList(),
    )

    private val onlineSnapshot = FfiCoreSnapshot(
        accounts = listOf(
            FfiAccount(
                id = 1uL,
                jid = "user@example.org",
                server = "example.org",
                displayName = "User 1",
                connectionState = FfiConnectionState.ONLINE,
                capabilities = listOf(FfiProtocolCapability.MULTI_USER_CHAT),
                connectionError = null,
                disconnectKind = null,
            ),
        ),
        contacts = listOf(
            FfiContact(
                accountId = 1uL,
                jid = "contact@example.org",
                displayName = "Contact 1",
                presence = FfiContactPresence.ONLINE,
                status = null,
                subscription = FfiRosterSubscription.MUTUAL,
            ),
        ),
        conversations = listOf(
            FfiConversation(
                id = 1uL,
                accountId = 1uL,
                kind = FfiConversationKind.DIRECT,
                address = "contact@example.org",
                title = "Chat 1",
                unreadCount = 0u,
                lastActivityEpochMs = 1_000uL,
            ),
        ),
        messages = emptyList(),
        reactions = emptyList(),
    )

    private class CountingCore(initial: FfiCoreSnapshot) : MindChatCoreHandle(NoHandle) {
        var current = initial
        var snapshotCalls = 0
            private set
        var saveCalls = 0
            private set
        var flushCalls = 0
            private set
        var eventsPerPoll: UInt = 0u

        fun resetCounters() {
            snapshotCalls = 0
            saveCalls = 0
            flushCalls = 0
        }

        override fun snapshot(): FfiCoreSnapshot {
            snapshotCalls++
            return current
        }

        override fun loadState(path: String): Boolean = false

        override fun drainEvents(): List<FfiCoreEvent> = emptyList()

        override fun pollTransportEvents(maxEvents: UInt): UInt = eventsPerPoll

        override fun flushOutbox(accountId: ULong): UInt {
            flushCalls++
            return 0u
        }

        override fun saveState(path: String) {
            saveCalls++
        }

        override fun sendText(
            conversationId: ULong,
            sender: String,
            body: String,
            inReplyTo: ULong?,
            nowEpochMs: ULong,
        ): ULong = 1uL
    }

    private fun gateway(core: CountingCore): NativeMindChatGateway = NativeMindChatGateway(
        core = core,
        stateFile = File.createTempFile("mindchat_state", ".json"),
        preferences = InMemoryMindChatPreferences(),
    )

    @Test
    fun changedPollCapturesExactlyOneSnapshot() = runBlocking {
        val core = CountingCore(emptySnapshot)
        core.eventsPerPoll = 1u
        val gateway = gateway(core)
        // The constructor restores + refreshes once; count only the poll cycle.
        core.resetCounters()

        gateway.pollTransport()

        assertEquals("changed poll must capture exactly one snapshot", 1, core.snapshotCalls)
        assertEquals("changed poll must persist the snapshot", 1, core.saveCalls)
    }

    @Test
    fun unchangedPollCapturesZeroSnapshots() = runBlocking {
        val core = CountingCore(emptySnapshot)
        core.eventsPerPoll = 0u
        val gateway = gateway(core)
        core.resetCounters()

        gateway.pollTransport()

        assertEquals("unchanged poll must not snapshot", 0, core.snapshotCalls)
        assertEquals("unchanged poll must not save", 0, core.saveCalls)
    }

    @Test
    fun pendingOutboxPollStillFlushesWithOneSnapshot() = runBlocking {
        val core = CountingCore(onlineSnapshot)
        core.eventsPerPoll = 0u
        val gateway = gateway(core)
        // sendText queues the account in the Kotlin pending outbox and refreshes.
        gateway.sendText(1L, "hello")
        core.resetCounters()

        gateway.pollTransport()

        assertEquals("pending-outbox poll must flush", 1, core.flushCalls)
        assertEquals("pending-outbox poll must capture exactly one snapshot", 1, core.snapshotCalls)
        assertEquals("pending-outbox poll must persist", 1, core.saveCalls)
    }
}

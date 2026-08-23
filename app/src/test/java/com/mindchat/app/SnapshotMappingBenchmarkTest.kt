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
import com.mindchat.core.FfiRosterSubscription
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3 micro-benchmark harness for the two poll hot paths, in the style of
 * [SnapshotDiffingTest] and [SnapshotMappingTest] (same fixture builders,
 * pure JVM functions, no instrumentation).
 *
 * - unchanged poll: [shouldSkipUiRebuild] over two structurally equal 10k
 *   snapshots (fresh instances, so the comparison is serialization-free but
 *   field-by-field);
 * - changed poll: [mapSnapshotToUiState] over a 10k-message fixture with the
 *   real locale [formatTimestamp].
 *
 * Budgets are tripwires with ~5-10x headroom over the measured p50, never
 * tight perf gates. Measured p50s are recorded in the comments below and
 * printed by [reportP50ByFixtureSize] through the test runner only (zero
 * logs from app code).
 */
class SnapshotMappingBenchmarkTest {

    /** Consumes benchmark results so the JIT cannot dead-code the measured call. */
    @Volatile
    private var sink = 0L

    private fun consume(value: Long) {
        sink += value
    }

    private fun measureMicros(iterations: Int, warmup: Int, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val samples = LongArray(iterations)
        repeat(iterations) { i ->
            val start = System.nanoTime()
            block()
            samples[i] = System.nanoTime() - start
        }
        samples.sort()
        return samples[iterations / 2] / 1_000.0
    }

    @Test
    fun unchangedPollDiffStaysUnderTripwireAtTenThousandMessages() {
        val current = snapshot(10_000)
        val previous = snapshot(10_000)
        val p50Micros = measureMicros(iterations = 300, warmup = 60) {
            consume(if (skip(current, previous)) 1L else 0L)
        }
        // p50 on the reference JVM: ~0.3 ms at 10k (see reportP50ByFixtureSize;
        // the plan's 200 µs target was extrapolated from the ~53 µs single-
        // message baseline, but the 10k structural compare is heavier). The 2 ms
        // tripwire leaves ~4-7x headroom for shared CI.
        assertTrue(
            "unchanged poll diff p50 was ${format(p50Micros)} µs; tripwire is 2000 µs",
            p50Micros < 2_000.0,
        )
    }

    @Test
    fun changedPollMappingStaysUnderTripwireAtTenThousandMessages() {
        val current = snapshot(10_000)
        val p50Millis = measureMicros(iterations = 25, warmup = 6) {
            consume(map(current).state.messagesByConversation.size.toLong())
        } / 1_000.0
        // p50 on the reference JVM: ~9-14 ms (see reportP50ByFixtureSize); the
        // 50 ms tripwire leaves ~3.5-5x headroom for shared CI.
        assertTrue(
            "changed poll mapping p50 was ${format(p50Millis)} ms; tripwire is 50 ms",
            p50Millis < 50.0,
        )
    }

    @Test
    fun reportP50ByFixtureSize() {
        for (size in intArrayOf(1_000, 10_000, 50_000)) {
            val current = snapshot(size)
            val previous = snapshot(size)
            val diffMicros = measureMicros(iterations = 100, warmup = 20) {
                consume(if (skip(current, previous)) 1L else 0L)
            }
            val mapMicros = measureMicros(iterations = 8, warmup = 3) {
                consume(map(current).state.messagesByConversation.size.toLong())
            }
            println(
                "p50 $size messages: unchanged-poll diff=${format(diffMicros)} µs, " +
                    "changed-poll mapping=${format(mapMicros / 1_000.0)} ms",
            )
        }
    }

    // --- fixtures (mirrors SnapshotDiffingTest/SnapshotMappingTest) ----------

    private fun account() = FfiAccount(
        id = 1uL,
        jid = "user@example.org",
        server = "example.org",
        displayName = "User 1",
        connectionState = FfiConnectionState.ONLINE,
        capabilities = listOf(FfiProtocolCapability.MULTI_USER_CHAT),
        connectionError = null,
        disconnectKind = null,
    )

    private fun contact() = FfiContact(
        accountId = 1uL,
        jid = "contact@example.org",
        displayName = "Contact 1",
        presence = FfiContactPresence.ONLINE,
        status = null,
        subscription = FfiRosterSubscription.MUTUAL,
    )

    private fun conversation() = FfiConversation(
        id = 1uL,
        accountId = 1uL,
        kind = FfiConversationKind.DIRECT,
        address = "contact@example.org",
        title = "Chat 1",
        unreadCount = 0u,
        lastActivityEpochMs = 1_000uL,
    )

    private fun message(id: Long) = FfiMessage(
        id = id.toULong(),
        conversationId = 1uL,
        sender = "user@example.org",
        body = "hello $id",
        direction = FfiMessageDirection.INCOMING,
        kind = FfiMessageKind.TEXT,
        sentAtEpochMs = (2_000L + id).toULong(),
        deliveryState = FfiDeliveryState.DELIVERED,
        inReplyTo = null,
        attachment = null,
    )

    private fun snapshot(messageCount: Int) = FfiCoreSnapshot(
        accounts = listOf(account()),
        contacts = listOf(contact()),
        conversations = listOf(conversation()),
        messages = List(messageCount) { message(it.toLong() + 1L) },
        reactions = emptyList(),
    )

    private fun map(snapshot: FfiCoreSnapshot): SnapshotMapping = mapSnapshotToUiState(
        snapshot = snapshot,
        profiles = emptyMap(),
        activeAccountId = 1L,
        connectingSince = emptyMap(),
        settings = SettingsSnapshot(),
        appearance = AppearanceProfile(),
        now = 1_000_000L,
        timestampFormatter = ::formatTimestamp,
    )

    private fun skip(current: FfiCoreSnapshot, previous: FfiCoreSnapshot): Boolean = shouldSkipUiRebuild(
        snapshot = current,
        lastSnapshot = previous,
        publishedActiveAccountId = 1L,
        activeAccountId = 1L,
        settings = SettingsSnapshot(),
        lastSettings = SettingsSnapshot(),
        profiles = emptyMap(),
        lastProfiles = emptyMap(),
        appearance = AppearanceProfile(),
        lastAppearance = AppearanceProfile(),
    )

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}

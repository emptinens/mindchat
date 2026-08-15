package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0.1.8 P1-1 event-drain policy: up to 4 batches of 128 events,
 * capped at 512 events per poll cycle, stopping early when a partial batch
 * proves the core queue is empty.
 */
class GatewayPollTest {

    // --- step-level planner ---------------------------------------------------

    @Test
    fun cycleConstantsAreConsistent() {
        assertEquals(
            MAX_EVENTS_PER_DRAIN_CYCLE,
            MAX_EVENTS_PER_DRAIN_BATCH * MAX_DRAIN_BATCHES_PER_CYCLE.toUInt(),
        )
        // The FFI clamp for one poll call is 128; the batch size must match it.
        assertEquals(128U, MAX_EVENTS_PER_DRAIN_BATCH)
        assertEquals(4, MAX_DRAIN_BATCHES_PER_CYCLE)
        assertEquals(512U, MAX_EVENTS_PER_DRAIN_CYCLE)
    }

    @Test
    fun firstStepStartsAtMaximumBatch() {
        val step = nextDrainStep(
            previousBatchSize = MAX_EVENTS_PER_DRAIN_BATCH,
            batchesUsed = 0,
            eventsProcessed = 0U,
        )
        assertEquals(MAX_EVENTS_PER_DRAIN_BATCH, step.batchSize)
        assertTrue(step.continueDraining)
    }

    @Test
    fun emptyPreviousBatchStopsTheCycle() {
        val step = nextDrainStep(
            previousBatchSize = 0U,
            batchesUsed = 0,
            eventsProcessed = 0U,
        )
        assertEquals(0U, step.batchSize)
        assertFalse(step.continueDraining)
    }

    @Test
    fun partialPreviousBatchStopsTheCycle() {
        val step = nextDrainStep(
            previousBatchSize = 40U,
            batchesUsed = 1,
            eventsProcessed = 128U,
        )
        assertEquals(0U, step.batchSize)
        assertFalse(step.continueDraining)
    }

    @Test
    fun batchCountCapStopsTheCycle() {
        val step = nextDrainStep(
            previousBatchSize = MAX_EVENTS_PER_DRAIN_BATCH,
            batchesUsed = MAX_DRAIN_BATCHES_PER_CYCLE,
            eventsProcessed = MAX_EVENTS_PER_DRAIN_CYCLE,
        )
        assertEquals(0U, step.batchSize)
        assertFalse(step.continueDraining)
    }

    @Test
    fun eventCapStopsTheCycleEvenWithinBatchCount() {
        val step = nextDrainStep(
            previousBatchSize = MAX_EVENTS_PER_DRAIN_BATCH,
            batchesUsed = MAX_DRAIN_BATCHES_PER_CYCLE - 1,
            eventsProcessed = MAX_EVENTS_PER_DRAIN_CYCLE,
        )
        assertEquals(0U, step.batchSize)
        assertFalse(step.continueDraining)
    }

    @Test
    fun remainingBudgetClampsTheNextBatchSize() {
        val step = nextDrainStep(
            previousBatchSize = MAX_EVENTS_PER_DRAIN_BATCH,
            batchesUsed = 3,
            eventsProcessed = 500U,
        )
        assertEquals(12U, step.batchSize)
        assertTrue(step.continueDraining)
    }

    // --- whole-cycle simulation ----------------------------------------------

    /** Mirrors the gateway drain loop with an injectable core poll function. */
    private fun drainWith(poll: (UInt) -> UInt): Pair<UInt, Int> {
        var eventsProcessed = 0U
        var batchesUsed = 0
        var previousBatchSize = MAX_EVENTS_PER_DRAIN_BATCH
        while (true) {
            val step = nextDrainStep(previousBatchSize, batchesUsed, eventsProcessed)
            if (!step.continueDraining) break
            val batch = poll(step.batchSize)
            eventsProcessed += batch
            batchesUsed += 1
            previousBatchSize = batch
        }
        return eventsProcessed to batchesUsed
    }

    @Test
    fun quietQueueCostsExactlyOnePoll() {
        val (processed, polls) = drainWith { 0U }
        assertEquals(0U, processed)
        assertEquals(1, polls)
    }

    @Test
    fun busyQueueDrainsFourBatchesAndStopsAtTheCap() {
        val (processed, polls) = drainWith { MAX_EVENTS_PER_DRAIN_BATCH }
        assertEquals(MAX_EVENTS_PER_DRAIN_CYCLE, processed)
        assertEquals(MAX_DRAIN_BATCHES_PER_CYCLE, polls)
    }

    @Test
    fun partialBatchStopsTheCycleEarly() {
        var pollIndex = 0
        val (processed, polls) = drainWith { _ ->
            pollIndex += 1
            when (pollIndex) {
                1 -> 128U
                2 -> 128U
                else -> 40U
            }
        }
        assertEquals(296U, processed)
        assertEquals(3, polls)
    }

    @Test
    fun queuedEventsBeyondTheCapStayForTheNextCycle() {
        val (processed, polls) = drainWith { MAX_EVENTS_PER_DRAIN_BATCH }
        // 512 applied this cycle; a fifth batch would be needed for more, but
        // the cycle cap forbids it. The next cycle's first poll picks them up.
        assertEquals(512U, processed)
        assertEquals(4, polls)
        val next = nextDrainStep(MAX_EVENTS_PER_DRAIN_BATCH, 0, 0U)
        assertEquals(128U, next.batchSize)
        assertTrue(next.continueDraining)
    }
}

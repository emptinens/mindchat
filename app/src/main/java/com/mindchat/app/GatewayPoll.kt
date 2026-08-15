package com.mindchat.app

/**
 * Shared event-drain policy behind [MindChatGateway.pollTransport]
 * (ROADMAP 6.2 P1-1).
 *
 * The native poll loop reads transport events from the core in bounded
 * batches. All sizing decisions live here as pure functions so the drain is
 * deterministic and JVM-testable without a core handle:
 *
 * - at most [MAX_DRAIN_BATCHES_PER_CYCLE] poll calls per cycle,
 * - each call requests at most [MAX_EVENTS_PER_DRAIN_BATCH] events,
 * - the whole cycle applies at most [MAX_EVENTS_PER_DRAIN_CYCLE] events
 *   (4 batches of 128, the FFI clamp already being 128),
 * - a partial batch (fewer events than requested) means the core queue is
 *   empty, so the cycle stops early instead of polling again.
 *
 * The one adaptive signal is the previous batch's fill: a full batch keeps
 * the drain at the maximum size, a partial batch ends it. A zero-sized
 * requested batch is the "stop" sentinel.
 */

/** Maximum events requested from the core in a single poll call. */
internal const val MAX_EVENTS_PER_DRAIN_BATCH: UInt = 128U

/** Maximum poll calls per drain cycle. */
internal const val MAX_DRAIN_BATCHES_PER_CYCLE: Int = 4

/** Hard cap on events applied per drain cycle (`4 x 128`). */
internal const val MAX_EVENTS_PER_DRAIN_CYCLE: UInt = 512U

/** One decision of the drain planner, applied between poll calls. */
internal data class DrainStep(
    /** The next batch size to request; zero means "issue no more polls". */
    val batchSize: UInt,
    /** False when the cycle must stop before issuing another poll. */
    val continueDraining: Boolean,
)

/**
 * Computes the next drain step from the previous poll's outcome.
 *
 * [previousBatchSize] is the event count the previous poll returned (start
 * the cycle at [MAX_EVENTS_PER_DRAIN_BATCH]). [batchesUsed] and
 * [eventsProcessed] count what the cycle has already consumed. The cycle
 * stops when any cap is reached or when a partial batch proves the core
 * queue is empty; otherwise the next batch stays at the maximum, clamped to
 * the remaining per-cycle budget.
 */
internal fun nextDrainStep(
    previousBatchSize: UInt,
    batchesUsed: Int,
    eventsProcessed: UInt,
): DrainStep {
    if (batchesUsed >= MAX_DRAIN_BATCHES_PER_CYCLE) return DrainStep(0U, continueDraining = false)
    if (eventsProcessed >= MAX_EVENTS_PER_DRAIN_CYCLE) return DrainStep(0U, continueDraining = false)
    if (previousBatchSize < MAX_EVENTS_PER_DRAIN_BATCH) return DrainStep(0U, continueDraining = false)
    val remainingBudget = MAX_EVENTS_PER_DRAIN_CYCLE - eventsProcessed
    return DrainStep(
        batchSize = minOf(MAX_EVENTS_PER_DRAIN_BATCH, remainingBudget),
        continueDraining = true,
    )
}

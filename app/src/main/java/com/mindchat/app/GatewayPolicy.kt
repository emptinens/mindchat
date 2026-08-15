package com.mindchat.app

/**
 * Shared state-transition and fallback rules behind the [MindChatGateway]
 * contract.
 *
 * Both implementations of the public interface (`NativeMindChatGateway` and
 * `PreviewMindChatGateway`) run exactly the same policy decisions here, so the
 * preview cannot drift from the native behavior: account fallback after
 * deletion and the thresholds that surface stalled connections are decided once
 * and shared.
 */

/**
 * How long an account may stay in CONNECTING before the UI surfaces it as
 * stalled. Pure mapping and policy functions reference this named constant so
 * tuning never silently diverges from the tests.
 */
internal const val STALL_THRESHOLD_MS = 35_000L

/**
 * The account id that stays active after [deletedId] is removed: the previous
 * active id unless it was the deleted one, in which case the first remaining
 * account (or 0 when none is left).
 */
internal fun nextActiveAccountId(
    accounts: List<AccountUi>,
    deletedId: Long,
    activeAccountId: Long,
): Long =
    if (activeAccountId == deletedId) {
        accounts.firstOrNull { it.id != deletedId }?.id ?: 0L
    } else {
        activeAccountId
    }

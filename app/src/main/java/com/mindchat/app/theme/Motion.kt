package com.mindchat.app.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import com.mindchat.app.AnimationSpeed

/**
 * MindChat motion tokens (0.1.7, M3E P0 B1).
 *
 * One motion source of truth. material3 1.4.0 keeps its expressive motion API
 * (`MotionScheme`, `MaterialTheme.motionScheme`, `MotionSchemeKeyTokens` and
 * even the `MotionTokens` constants) `internal` in the Kotlin metadata
 * (verified at implementation time against the 1.4.0 AAR), so the app defines
 * its own [MindChatMotionScheme] carrying the same Material 3 motion-token
 * values. The [AnimationSpeed] dimension selects which effects token family an
 * animated affordance uses (FASTER -> fast effects, DEFAULT -> default
 * effects, SLOWER -> slow effects). Screens reference these tokens; no bare
 * `tween(...)` literals live outside this file.
 */

/**
 * App-level motion scheme: Material 3 effects-token durations (fast 200 ms,
 * default 300 ms, slow 500 ms) with the M3 emphasized cubic bezier
 * (approximated by [FastOutSlowInEasing], the closest public easing). Revisit
 * when material3 promotes `MotionScheme` to public Kotlin API (expected 1.5):
 * replace this object with `MotionScheme.expressive()` + `fromToken`.
 */
object MindChatMotionScheme {
    private const val FAST_EFFECTS_DURATION_MS = 200
    private const val DEFAULT_EFFECTS_DURATION_MS = 300
    private const val SLOW_EFFECTS_DURATION_MS = 500

    private fun <T> effects(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)

    /** The effects spec for [speed], resolved through the motion scheme. */
    fun <T> effectsSpecFor(speed: AnimationSpeed): FiniteAnimationSpec<T> = when (speed) {
        AnimationSpeed.FASTER -> effects(FAST_EFFECTS_DURATION_MS)
        AnimationSpeed.DEFAULT -> effects(DEFAULT_EFFECTS_DURATION_MS)
        AnimationSpeed.SLOWER -> effects(SLOW_EFFECTS_DURATION_MS)
    }
}

/**
 * App-level motion speed provided by [MindChatTheme] from the appearance
 * profile. Animations read it to pick the effects token family, so
 * `setAppearance(animationSpeed = ...)` changes motion immediately.
 */
val LocalMindChatMotionSpeed = staticCompositionLocalOf { AnimationSpeed.DEFAULT }

package com.mindchat.app.theme

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import com.mindchat.app.AnimationSpeed
import kotlin.math.roundToInt

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

    /** The effects-token duration (ms) for [speed], before system scaling (T1). */
    fun effectsDurationMs(speed: AnimationSpeed): Int = when (speed) {
        AnimationSpeed.FASTER -> FAST_EFFECTS_DURATION_MS
        AnimationSpeed.DEFAULT -> DEFAULT_EFFECTS_DURATION_MS
        AnimationSpeed.SLOWER -> SLOW_EFFECTS_DURATION_MS
    }

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

/* ===================================================================== *
 * T1 (0.1.7): system reduce-motion plumbing + shared motion tokens.     *
 * ===================================================================== */

/**
 * Shared micro-animation durations (T1). Task-specific tweens (for example
 * the 220 ms message entrance) are passed as literals to [Motion.tween]; the
 * token family below covers the reusable tiers.
 */
object MotionDurations {
    const val FastMs = 150
    const val StandardMs = 250
    const val EmphasizedMs = 350
    const val LargeMs = 500
}

/** M3 Expressive emphasized curve; [StandardEasing] is an alias for it. */
val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** M3 Expressive emphasized-decelerate curve (entrances). */
val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** M3 Expressive emphasized-accelerate curve (exits). */
val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Standard easing used by MindChat micro-animations. */
val StandardEasing: Easing = EmphasizedEasing

/**
 * System reduce-motion contract (T1): one source of truth for how the system
 * animator/transition scales shape every micro-animation.
 *
 * [durationScale] comes from `Settings.Global.ANIMATOR_DURATION_SCALE`; 0
 * snaps every [durationMs] to 0 (instant). [excessiveScale] comes from
 * `Settings.Global.TRANSITION_ANIMATION_SCALE`; 0 disables translation,
 * scale and spring motion ([excessiveMotionAllowed] == false), leaving fades
 * (still duration-scaled) or instant transitions. The appearance engine's
 * animation speed is applied on top by call sites that need it (for example
 * [rememberEffectsSpec]); this type itself only carries the system scale.
 */
@Immutable
data class Motion(
    val durationScale: Float = 1f,
    val excessiveScale: Float = 1f,
) {
    /** True when translation/scale/spring motion is permitted by the system. */
    val excessiveMotionAllowed: Boolean get() = excessiveScale > 0f

    private val instant: Boolean get() = durationScale <= 0f

    /** The [normalMs] duration scaled by the system animator scale (0 => snap). */
    fun durationMs(normalMs: Int): Int = (normalMs * durationScale).roundToInt()

    /** A [normalMs] tween with the system scale baked in. */
    fun <T> tween(normalMs: Int, easing: Easing = StandardEasing): TweenSpec<T> =
        androidx.compose.animation.core.tween(durationMillis = durationMs(normalMs), easing = easing)

    /** Standard entrance tween: [MotionDurations.StandardMs] emphasized-decelerate. */
    fun <T> entranceTween(): TweenSpec<T> = tween(MotionDurations.StandardMs, EmphasizedDecelerate)

    /** Standard exit tween: [MotionDurations.FastMs] standard easing. */
    fun <T> exitTween(): TweenSpec<T> = tween(MotionDurations.FastMs, StandardEasing)

    /**
     * Snappy one-overshoot spring for pops (reaction chip, badge, dock icon).
     * Degrades to an instant snap when the system disables excessive motion or
     * scales durations to zero.
     */
    fun <T> microSpring(
        dampingRatio: Float = 0.6f,
        stiffness: Float = 700f,
    ): FiniteAnimationSpec<T> =
        if (excessiveMotionAllowed && !instant) spring(dampingRatio, stiffness) else tween(0)

    /**
     * Low-stiffness spring for list placement shifts. Degrades to an instant
     * snap under reduced motion.
     */
    fun <T> placementSpring(
        dampingRatio: Float = 0.85f,
        stiffness: Float = 400f,
    ): FiniteAnimationSpec<T> =
        if (excessiveMotionAllowed && !instant) spring(dampingRatio, stiffness) else tween(0)
}

/**
 * Composition-local system reduce-motion value. Provided at the app root by
 * [rememberMotionScale] and overridable in tests via
 * `CompositionLocalProvider(LocalMotion provides Motion(...))`. Defaults to
 * full motion so isolated previews and tests behave like a normal device.
 */
val LocalMotion = staticCompositionLocalOf { Motion() }

/** Reads the current system reduce-motion contract (T1). */
@Composable
fun rememberMotion(): Motion = LocalMotion.current

/**
 * The effects-token spec for the current appearance speed, additionally
 * scaled by the system reduce-motion settings (T1 + B1 integration). Used by
 * affordances that were already wired to [MindChatMotionScheme] (the dock).
 */
@Composable
fun <T> rememberEffectsSpec(): FiniteAnimationSpec<T> {
    val motion = rememberMotion()
    return motion.tween(
        MindChatMotionScheme.effectsDurationMs(LocalMindChatMotionSpeed.current),
        FastOutSlowInEasing,
    )
}

/**
 * Reads `ANIMATOR_DURATION_SCALE` and `TRANSITION_ANIMATION_SCALE` and keeps
 * them live through a [ContentObserver] so toggling "Remove animations" or
 * the developer options scales updates every animation on the next frame.
 * This is the documented fallback for `LocalReduceMotion`, which the pinned
 * Compose line does not ship; the contract is identical and call sites only
 * ever see [LocalMotion] / [rememberMotion].
 */
@Composable
internal fun rememberMotionScale(context: Context): Motion {
    val resolver = context.contentResolver
    var motion by remember { mutableStateOf(readMotionScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                motion = readMotionScale(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return motion
}

internal fun readMotionScale(contentResolver: ContentResolver): Motion = Motion(
    durationScale = readScale(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE),
    excessiveScale = readScale(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE),
)

private fun readScale(contentResolver: ContentResolver, name: String): Float =
    Settings.Global.getFloat(contentResolver, name, 1f).coerceIn(0f, 10f)

/**
 * Two-way converter so the dock label can animate between font weights (T5).
 * JVM-safe; covered by MotionTokenTest.
 */
internal val FontWeightConverter: TwoWayConverter<FontWeight, AnimationVector1D> = TwoWayConverter(
    convertToVector = { weight -> AnimationVector1D(weight.weight.toFloat()) },
    convertFromVector = { vector -> FontWeight(vector.value.toInt()) },
)

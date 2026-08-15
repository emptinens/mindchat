package com.mindchat.app

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.text.font.FontWeight
import com.mindchat.app.theme.EmphasizedAccelerate
import com.mindchat.app.theme.EmphasizedDecelerate
import com.mindchat.app.theme.EmphasizedEasing
import com.mindchat.app.theme.FontWeightConverter
import com.mindchat.app.theme.MindChatMotionScheme
import com.mindchat.app.theme.Motion
import com.mindchat.app.theme.MotionDurations
import com.mindchat.app.theme.StandardEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 acceptance (ROADMAP §5.4): the motion token contract is pure and
 * JVM-testable. `Motion.durationMs` scales by the system animator scale (0 =>
 * snap), excessive-motion gating disables translation/scale/spring factories,
 * the tween factories bake the scale in, the M3 expressive easing curves are
 * monotonic on [0,1], and the dock label FontWeight converter round-trips.
 */
class MotionTokenTest {

    @Test
    fun durationScaleZeroSnapsEveryDuration() {
        val motion = Motion(durationScale = 0f)
        assertEquals(0, motion.durationMs(300))
        assertEquals(0, motion.durationMs(150))
        assertEquals(0, motion.durationMs(500))
    }

    @Test
    fun durationScaleHalfHalvesDurations() {
        val motion = Motion(durationScale = 0.5f)
        assertEquals(150, motion.durationMs(300))
        assertEquals(75, motion.durationMs(150))
    }

    @Test
    fun fullScaleKeepsDurations() {
        assertEquals(300, Motion().durationMs(300))
    }

    @Test
    fun excessiveMotionRequiresAPositiveTransitionScale() {
        assertFalse(Motion(excessiveScale = 0f).excessiveMotionAllowed)
        assertTrue(Motion(excessiveScale = 1f).excessiveMotionAllowed)
        assertTrue(Motion().excessiveMotionAllowed)
    }

    @Test
    fun tweenFactoryBakesInTheDurationScale() {
        val half = Motion(durationScale = 0.5f)
        val spec = half.tween<Float>(200, StandardEasing)
        assertEquals(100, (spec as TweenSpec).durationMillis)
        val none = Motion(durationScale = 0f)
        assertEquals(0, (none.tween<Float>(200) as TweenSpec).durationMillis)
    }

    @Test
    fun entranceAndExitTweensUseTheSharedTokens() {
        val motion = Motion()
        assertEquals(
            MotionDurations.StandardMs,
            (motion.entranceTween<Float>() as TweenSpec).durationMillis,
        )
        assertEquals(MotionDurations.FastMs, (motion.exitTween<Float>() as TweenSpec).durationMillis)
    }

    @Test
    fun microSpringDegradesToASnapUnderReducedMotion() {
        assertTrue(Motion(excessiveScale = 0f).microSpring<Float>() is TweenSpec<*>)
        assertTrue(Motion(durationScale = 0f).microSpring<Float>() is TweenSpec<*>)
        assertTrue(Motion().microSpring<Float>() is SpringSpec<*>)
    }

    @Test
    fun placementSpringDegradesToASnapUnderReducedMotion() {
        assertTrue(Motion(excessiveScale = 0f).placementSpring<Float>() is TweenSpec<*>)
        assertTrue(Motion(durationScale = 0f).placementSpring<Float>() is TweenSpec<*>)
        assertTrue(Motion().placementSpring<Float>() is SpringSpec<*>)
    }

    @Test
    fun easingCurvesAreMonotonicIncreasingOnTheUnitInterval() {
        val curves = listOf(
            EmphasizedEasing,
            EmphasizedDecelerate,
            EmphasizedAccelerate,
            StandardEasing,
        )
        curves.forEach { easing ->
            var previous = easing.transform(0f)
            var step = 0f
            while (step <= 1f) {
                val value = easing.transform(step)
                assertTrue("$easing must be non-decreasing at $step", value + 1e-6f >= previous)
                previous = value
                step += 0.05f
            }
            assertEquals(0f, easing.transform(0f), 1e-4f)
            assertEquals(1f, easing.transform(1f), 1e-4f)
        }
    }

    @Test
    fun standardEasingIsTheEmphasizedCurve() {
        assertEquals(EmphasizedEasing, StandardEasing)
    }

    @Test
    fun fontWeightConverterRoundTripsDockLabelWeights() {
        val normalVector = FontWeightConverter.convertToVector(FontWeight.Normal)
        assertEquals(FontWeight.Normal.weight.toFloat(), normalVector.value, 1e-4f)
        val semibold = FontWeight.SemiBold
        val roundTripped = FontWeightConverter.convertFromVector(
            FontWeightConverter.convertToVector(semibold),
        )
        assertEquals(semibold, roundTripped)
    }

    @Test
    fun effectsDurationMsFollowsTheSpeedTiers() {
        assertEquals(200, MindChatMotionScheme.effectsDurationMs(AnimationSpeed.FASTER))
        assertEquals(300, MindChatMotionScheme.effectsDurationMs(AnimationSpeed.DEFAULT))
        assertEquals(500, MindChatMotionScheme.effectsDurationMs(AnimationSpeed.SLOWER))
    }
}

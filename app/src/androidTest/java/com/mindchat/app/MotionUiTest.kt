package com.mindchat.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.mindchat.app.theme.EmphasizedDecelerate
import com.mindchat.app.theme.LocalMotion
import com.mindchat.app.theme.Motion
import com.mindchat.app.theme.rememberMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T1/T2 Compose acceptance (ROADMAP §5.4): the reduce-motion contract is
 * read through [LocalMotion] and [rememberMotion], overrides flow into every
 * animation, and entrances driven by the motion tokens complete on the
 * main clock (never wall-clock sleeps). The zero-scale override renders
 * immediately and stays geometrically stable; the full-scale override plays
 * its entrance over `mainClock.advanceTimeBy`.
 */
class MotionUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localMotionDefaultsToFullMotion() {
        lateinit var motion: Motion
        composeRule.setContent {
            motion = rememberMotion()
        }
        composeRule.waitForIdle()
        assertEquals(1f, motion.durationScale)
        assertEquals(1f, motion.excessiveScale)
        assertTrue(motion.excessiveMotionAllowed)
        assertEquals(300, motion.durationMs(300))
    }

    @Test
    fun localMotionOverrideIsReadByRememberMotion() {
        lateinit var motion: Motion
        composeRule.setContent {
            CompositionLocalProvider(
                LocalMotion provides Motion(durationScale = 0f, excessiveScale = 0f),
            ) {
                motion = rememberMotion()
            }
        }
        composeRule.waitForIdle()
        assertEquals(0f, motion.durationScale)
        assertEquals(0f, motion.excessiveScale)
        assertFalse(motion.excessiveMotionAllowed)
        assertEquals(0, motion.durationMs(300))
    }

    @Test
    fun zeroScaleEntranceIsVisibleImmediatelyAndStable() {
        lateinit var state: MutableTransitionState<Boolean>
        composeRule.setContent {
            CompositionLocalProvider(
                LocalMotion provides Motion(durationScale = 0f, excessiveScale = 0f),
            ) {
                state = remember { MutableTransitionState(false).apply { targetState = true } }
                MotionEntranceHarness(state)
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()
        // Reduced motion: the entrance is instant, so the transition is already
        // finished at clock time 0 and no translation is ever applied.
        composeRule.onNodeWithTag("bubble").assertIsDisplayed()
        val before = composeRule.onNodeWithTag("bubble").getBoundsInRoot()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.waitForIdle()
        val after = composeRule.onNodeWithTag("bubble").getBoundsInRoot()
        assertTrue(state.currentState)
        assertEquals(before, after)
    }

    @Test
    fun fullScaleEntranceCompletesOnTheMainClock() {
        lateinit var state: MutableTransitionState<Boolean>
        composeRule.setContent {
            state = remember { MutableTransitionState(false).apply { targetState = true } }
            MotionEntranceHarness(state)
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()
        // Full motion: the 220 ms entrance is still running at clock time 0.
        assertFalse(state.currentState)
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        assertTrue(state.currentState)
        composeRule.onNodeWithTag("bubble").assertIsDisplayed()
    }

    @Composable
    private fun MotionEntranceHarness(state: MutableTransitionState<Boolean>) {
        val motion = rememberMotion()
        val density = LocalDensity.current
        val entrance = if (motion.excessiveMotionAllowed) {
            fadeIn(motion.tween(220, EmphasizedDecelerate)) +
                slideInVertically(motion.tween(220, EmphasizedDecelerate)) { with(density) { 6.dp.roundToPx() } }
        } else {
            fadeIn(motion.tween(220, EmphasizedDecelerate))
        }
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = state,
                enter = entrance,
                label = "testEntrance",
            ) {
                Text(
                    text = "arrived",
                    modifier = Modifier.testTag("bubble"),
                )
            }
        }
    }
}

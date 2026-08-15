package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the 0.1.6/0.1.7 customization controls driven through
 * the public [MindChatGateway] contract: each mutation flips its own
 * customization field in both the UI state and the preferences, leaves the
 * other fields untouched, and survives a gateway restart (a new instance
 * reading the same preferences). `PreviewMindChatGateway` is the JVM-runnable
 * contract implementation; the native gateway runs the same lambdas against
 * the same preferences storage.
 */
class GatewayCustomizationContractTest {

    private fun preferences(initial: MindChatCustomization = MindChatCustomization()) =
        InMemoryMindChatPreferences(initial = initial)

    // --- Flipping -------------------------------------------------------------

    @Test
    fun toggleDynamicColorFlipsStateAndPersists() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        assertTrue("dynamic color defaults on", g.state.dynamicColor)

        g.toggleDynamicColor()

        assertFalse(g.state.dynamicColor)
        assertFalse(prefs.readCustomization().dynamicColor)

        g.toggleDynamicColor()
        assertTrue(g.state.dynamicColor)
        assertTrue(prefs.readCustomization().dynamicColor)
    }

    @Test
    fun setAppearanceReplacesStateAndPersists() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        assertEquals(AppearanceProfile(), g.state.appearance)

        val dense = AppearanceProfile(shapeScale = ShapeScale.COMPACT, density = Density.COMPACT)
        g.setAppearance(dense)

        assertEquals(dense, g.state.appearance)
        assertEquals(dense, prefs.readCustomization().appearance)
    }

    @Test
    fun toggleAppLockFlipsStateAndPersists() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        assertFalse("app lock defaults off", g.state.appLockEnabled)

        g.toggleAppLock()

        assertTrue(g.state.appLockEnabled)
        assertTrue(prefs.readCustomization().appLockEnabled)
    }

    @Test
    fun customizationsAreIndependent() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)

        g.toggleDynamicColor()
        g.setAppearance(AppearanceProfile(density = Density.COMPACT))
        g.toggleAppLock()

        val stored = prefs.readCustomization()
        assertFalse(stored.dynamicColor)
        assertEquals(Density.COMPACT, stored.appearance.density)
        assertTrue("app lock must be flipped", stored.appLockEnabled)
    }

    @Test
    fun setAppearanceLeavesOtherCustomizationFieldsUntouched() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        g.toggleDynamicColor()
        g.toggleAppLock()

        g.setAppearance(AppearanceProfile(bubbleStyle = BubbleStyle.ROUNDED))

        val stored = prefs.readCustomization()
        assertFalse(stored.dynamicColor)
        assertTrue(stored.appLockEnabled)
        assertEquals(BubbleStyle.ROUNDED, stored.appearance.bubbleStyle)
        assertEquals("shape scale untouched by a bubble change", ShapeScale.EXPRESSIVE, stored.appearance.shapeScale)
    }

    // --- Persistence across instances -----------------------------------------

    @Test
    fun newGatewayInstanceReadsPersistedCustomization() {
        val prefs = preferences()
        val first = PreviewMindChatGateway(prefs)
        first.toggleDynamicColor()
        first.toggleAppLock()
        first.setAppearance(AppearanceProfile(textScale = TextScale.LARGE))

        val second = PreviewMindChatGateway(prefs)

        assertFalse(second.state.dynamicColor)
        assertTrue(second.state.appLockEnabled)
        assertEquals("appearance survives restart", TextScale.LARGE, second.state.appearance.textScale)
        assertTrue("untouched fields keep their default", second.state.appearance.shapeScale == ShapeScale.EXPRESSIVE)
    }

    @Test
    fun gatewayInitializesFromNonDefaultPreferences() {
        val prefs = preferences(
            MindChatCustomization(
                dynamicColor = false,
                appearance = AppearanceProfile(density = Density.STANDARD, bubbleStyle = BubbleStyle.OUTLINED),
                appLockEnabled = true,
            ),
        )

        val g = PreviewMindChatGateway(prefs)

        assertFalse(g.state.dynamicColor)
        assertEquals(Density.STANDARD, g.state.appearance.density)
        assertEquals(BubbleStyle.OUTLINED, g.state.appearance.bubbleStyle)
        assertTrue(g.state.appLockEnabled)
    }
}

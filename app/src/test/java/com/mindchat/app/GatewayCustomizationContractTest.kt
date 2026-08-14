package com.mindchat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the 0.1.6 settings toggles driven through the public
 * [MindChatGateway] contract: each toggle flips its own customization flag in
 * both the UI state and the preferences, leaves the other flags untouched, and
 * survives a gateway restart (a new instance reading the same preferences).
 * `PreviewMindChatGateway` is the JVM-runnable contract implementation; the
 * native gateway runs the same toggle lambdas against the same preferences
 * storage.
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
    fun toggleComfortableLayoutFlipsStateAndPersists() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        assertTrue("comfortable layout defaults on", g.state.comfortableLayout)

        g.toggleComfortableLayout()

        assertFalse(g.state.comfortableLayout)
        assertFalse(prefs.readCustomization().comfortableLayout)
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
    fun togglesAreIndependent() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)

        g.toggleDynamicColor()
        g.toggleComfortableLayout()

        val stored = prefs.readCustomization()
        assertFalse(stored.dynamicColor)
        assertFalse(stored.comfortableLayout)
        assertFalse("app lock must not be touched", stored.appLockEnabled)
    }

    // --- Persistence across instances -----------------------------------------

    @Test
    fun newGatewayInstanceReadsPersistedCustomization() {
        val prefs = preferences()
        val first = PreviewMindChatGateway(prefs)
        first.toggleDynamicColor()
        first.toggleAppLock()

        val second = PreviewMindChatGateway(prefs)

        assertFalse(second.state.dynamicColor)
        assertTrue(second.state.appLockEnabled)
        assertTrue("untouched flags keep their default", second.state.comfortableLayout)
    }

    @Test
    fun gatewayInitializesFromNonDefaultPreferences() {
        val prefs = preferences(
            MindChatCustomization(dynamicColor = false, comfortableLayout = false, appLockEnabled = true),
        )

        val g = PreviewMindChatGateway(prefs)

        assertFalse(g.state.dynamicColor)
        assertFalse(g.state.comfortableLayout)
        assertTrue(g.state.appLockEnabled)
    }
}

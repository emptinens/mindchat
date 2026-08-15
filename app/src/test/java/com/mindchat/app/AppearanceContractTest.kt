package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public appearance contract driven through [PreviewMindChatGateway] (ROADMAP
 * §5.1 acceptance 1/2): setAppearance flips [MindChatUiState.appearance] and
 * persists, a second gateway over the same preferences reads it back, the
 * dynamic-color/app-lock controls stay independent, defaults equal the
 * [AppearanceProfile] defaults, and per-account bubble/background overrides
 * flow into [MindChatUiState.profiles] and resolve through
 * [resolveAppearance].
 */
class AppearanceContractTest {

    @Test
    fun setAppearanceFlipsStateAndPersists() {
        val prefs = InMemoryMindChatPreferences()
        val g = PreviewMindChatGateway(prefs)
        assertEquals(AppearanceProfile(), g.state.appearance)

        g.setAppearance(AppearanceProfile(density = Density.COMPACT))

        assertEquals(Density.COMPACT, g.state.appearance.density)
        assertEquals(Density.COMPACT, prefs.readCustomization().appearance.density)
    }

    @Test
    fun appearanceSurvivesGatewayRecreation() {
        val prefs = InMemoryMindChatPreferences()
        val first = PreviewMindChatGateway(prefs)
        first.setAppearance(
            AppearanceProfile(
                shapeScale = ShapeScale.STANDARD,
                textScale = TextScale.LARGE,
                animationSpeed = AnimationSpeed.SLOWER,
            ),
        )

        val second = PreviewMindChatGateway(prefs)

        assertEquals(ShapeScale.STANDARD, second.state.appearance.shapeScale)
        assertEquals(TextScale.LARGE, second.state.appearance.textScale)
        assertEquals(AnimationSpeed.SLOWER, second.state.appearance.animationSpeed)
    }

    @Test
    fun appearanceIsIndependentOfOtherCustomizationControls() {
        val prefs = InMemoryMindChatPreferences()
        val g = PreviewMindChatGateway(prefs)

        g.toggleDynamicColor()
        g.toggleAppLock()
        g.setAppearance(AppearanceProfile(bubbleStyle = BubbleStyle.ROUNDED))

        val stored = prefs.readCustomization()
        assertFalse(stored.dynamicColor)
        assertTrue(stored.appLockEnabled)
        assertEquals(BubbleStyle.ROUNDED, stored.appearance.bubbleStyle)
        assertEquals("defaults for untouched dimensions", ShapeScale.EXPRESSIVE, stored.appearance.shapeScale)
    }

    @Test
    fun defaultsEqualProfileDefaults() {
        assertEquals(AppearanceProfile(), PreviewMindChatGateway().state.appearance)
    }

    @Test
    fun perAccountOverridesFlowIntoProfilesAndResolve() {
        val g = PreviewMindChatGateway()
        val accountId = g.state.activeAccountId

        g.updateProfile(
            accountId,
            AccountProfile(bubbleStyle = BubbleStyle.OUTLINED, chatBackground = ChatBackground.TINTED),
        )

        val profile = g.state.profiles[accountId]
        assertEquals(BubbleStyle.OUTLINED, profile?.bubbleStyle)
        assertEquals(ChatBackground.TINTED, profile?.chatBackground)
        val resolved = resolveAppearance(g.state.appearance, profile)
        assertEquals(BubbleStyle.OUTLINED, resolved.bubbleStyle)
        assertEquals(ChatBackground.TINTED, resolved.chatBackground)
        assertEquals("ergonomics stay global", g.state.appearance.shapeScale, resolved.shapeScale)
    }

    @Test
    fun clearedOverrideReturnsToGlobal() {
        val g = PreviewMindChatGateway()
        val accountId = g.state.activeAccountId
        g.updateProfile(accountId, AccountProfile(bubbleStyle = BubbleStyle.ROUNDED))
        assertEquals(BubbleStyle.ROUNDED, g.state.profiles[accountId]?.bubbleStyle)

        g.updateProfile(accountId, AccountProfile(bubbleStyle = null))

        // An all-default profile removes the stored entry; the merge falls
        // back to the global value.
        assertEquals(null, g.state.profiles[accountId])
        assertEquals(
            g.state.appearance.bubbleStyle,
            resolveAppearance(g.state.appearance, g.state.profiles[accountId]).bubbleStyle,
        )
    }

    @Test
    fun profileOverridesDoNotLeakAcrossAccounts() {
        val g = PreviewMindChatGateway()
        val firstId = g.state.activeAccountId
        g.registerAccount("second@example.org", "example.org", "Second", "pw")
        val secondId = g.state.activeAccountId
        g.updateProfile(firstId, AccountProfile(bubbleStyle = BubbleStyle.OUTLINED))

        assertEquals(BubbleStyle.OUTLINED, g.state.profiles[firstId]?.bubbleStyle)
        assertEquals(null, g.state.profiles[secondId]?.bubbleStyle)
    }

    @Test
    fun resolveAppearanceMergeIsTheSingleRule() {
        val global = AppearanceProfile(
            bubbleStyle = BubbleStyle.DEFAULT,
            chatBackground = ChatBackground.DEFAULT,
        )
        assertEquals(
            AppearanceProfile(bubbleStyle = BubbleStyle.ROUNDED),
            resolveAppearance(global, AccountProfile(bubbleStyle = BubbleStyle.ROUNDED)),
        )
        assertEquals(
            AppearanceProfile(chatBackground = ChatBackground.TINTED),
            resolveAppearance(global, AccountProfile(chatBackground = ChatBackground.TINTED)),
        )
    }
}

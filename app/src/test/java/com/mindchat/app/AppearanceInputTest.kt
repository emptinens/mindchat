package com.mindchat.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure appearance-domain rules (ROADMAP §5.1, acceptance 3/4): stable ASCII
 * enum keys with default fallback, the legacy comfortable-layout migration,
 * the global↔profile merge, and the exact factor/spacing constants that the
 * theme pipeline depends on. Everything here is JVM-runnable, no Android
 * mocks.
 */
class AppearanceInputTest {

    // --- fromKey --------------------------------------------------------------

    @Test
    fun fromKeyRoundTripsEnumKeys() {
        assertEquals(ShapeScale.COMPACT, fromKey(ShapeScale.entries.toTypedArray(), "compact", ShapeScale.EXPRESSIVE))
        assertEquals(Density.STANDARD, fromKey(Density.entries.toTypedArray(), "standard", Density.COMFORTABLE))
        assertEquals(TextScale.LARGE, fromKey(TextScale.entries.toTypedArray(), "large", TextScale.DEFAULT))
        assertEquals(AnimationSpeed.FASTER, fromKey(AnimationSpeed.entries.toTypedArray(), "faster", AnimationSpeed.DEFAULT))
        assertEquals(BubbleStyle.ROUNDED, fromKey(BubbleStyle.entries.toTypedArray(), "rounded", BubbleStyle.DEFAULT))
        assertEquals(ChatBackground.TINTED, fromKey(ChatBackground.entries.toTypedArray(), "tinted", ChatBackground.DEFAULT))
    }

    @Test
    fun fromKeyFallsBackToDefaultOnUnknownOrNull() {
        assertEquals(ShapeScale.EXPRESSIVE, fromKey(ShapeScale.entries.toTypedArray(), "huge", ShapeScale.EXPRESSIVE))
        assertEquals(Density.COMFORTABLE, fromKey(Density.entries.toTypedArray(), null, Density.COMFORTABLE))
    }

    // --- densityFromLegacy ----------------------------------------------------

    @Test
    fun densityFromLegacyMapsTheOldBoolean() {
        assertEquals(Density.COMFORTABLE, densityFromLegacy(true))
        assertEquals(Density.STANDARD, densityFromLegacy(false))
        assertEquals(Density.COMFORTABLE, densityFromLegacy(null))
    }

    // --- resolveAppearance ----------------------------------------------------

    @Test
    fun resolveAppearanceWithoutProfileKeepsGlobal() {
        val global = AppearanceProfile(bubbleStyle = BubbleStyle.ROUNDED, chatBackground = ChatBackground.TINTED)
        assertEquals(global, resolveAppearance(global, null))
    }

    @Test
    fun resolveAppearanceAppliesOnlySetOverrides() {
        val global = AppearanceProfile(
            shapeScale = ShapeScale.COMPACT,
            bubbleStyle = BubbleStyle.ROUNDED,
            chatBackground = ChatBackground.TINTED,
        )
        val merged = resolveAppearance(global, AccountProfile(bubbleStyle = BubbleStyle.OUTLINED))

        assertEquals(BubbleStyle.OUTLINED, merged.bubbleStyle)
        assertEquals("unset chat background stays global", ChatBackground.TINTED, merged.chatBackground)
        assertEquals("ergonomics never come from the profile", ShapeScale.COMPACT, merged.shapeScale)
    }

    @Test
    fun resolveAppearanceNeverTouchesAccent() {
        val global = AppearanceProfile()
        val merged = resolveAppearance(global, AccountProfile(accentKey = "ocean", bubbleStyle = BubbleStyle.ROUNDED))
        assertEquals(BubbleStyle.ROUNDED, merged.bubbleStyle)
        // accentKey is applied at the theme call site, never in the merge.
        assertEquals(global.copy(bubbleStyle = BubbleStyle.ROUNDED), merged)
    }

    // --- factors and constants ------------------------------------------------

    @Test
    fun textFactorsAreExact() {
        assertEquals(0.9f, TextScale.COMPACT.factor)
        assertEquals(1.0f, TextScale.DEFAULT.factor)
        assertEquals(1.15f, TextScale.LARGE.factor)
    }

    @Test
    fun motionFactorsAreExact() {
        assertEquals(0.6f, AnimationSpeed.FASTER.factor)
        assertEquals(1.0f, AnimationSpeed.DEFAULT.factor)
        assertEquals(1.8f, AnimationSpeed.SLOWER.factor)
    }

    @Test
    fun densitySpacingMapsAreExact() {
        assertEquals(2.dp, Density.COMPACT.listSpacing)
        assertEquals(4.dp, Density.STANDARD.listSpacing)
        assertEquals(8.dp, Density.COMFORTABLE.listSpacing)
    }

    @Test
    fun densityRowPaddingMapsAreExact() {
        assertEquals(12.dp, Density.COMPACT.rowPadding)
        assertEquals(14.dp, Density.STANDARD.rowPadding)
        assertEquals(16.dp, Density.COMFORTABLE.rowPadding)
    }

    // --- defaults preserve 0.1.6 visuals --------------------------------------

    @Test
    fun defaultsReproduceTheZeroPointOneSixLook() {
        val defaults = AppearanceProfile()
        assertEquals(ShapeScale.EXPRESSIVE, defaults.shapeScale)
        assertEquals(Density.COMFORTABLE, defaults.density)
        assertEquals(TextScale.DEFAULT, defaults.textScale)
        assertEquals(AnimationSpeed.DEFAULT, defaults.animationSpeed)
        assertEquals(BubbleStyle.DEFAULT, defaults.bubbleStyle)
        assertEquals(ChatBackground.DEFAULT, defaults.chatBackground)
    }

    @Test
    fun appearanceProfilesAreValueTypes() {
        assertEquals(AppearanceProfile(), AppearanceProfile())
        assertNotEquals(AppearanceProfile(), AppearanceProfile(density = Density.COMPACT))
    }

    // --- migration through the preferences store -------------------------------

    @Test
    fun legacyComfortableLayoutOnlyMigratesToDensity() {
        val prefs = InMemoryMindChatPreferences(
            rawGlobal = mapOf(SettingsSchema.comfortableLayout to false),
        )
        assertEquals(Density.STANDARD, prefs.readCustomization().appearance.density)
    }

    @Test
    fun presentDensityKeyWinsOverLegacy() {
        val prefs = InMemoryMindChatPreferences(
            rawAppearanceKeys = mapOf(KEY_DENSITY to "compact"),
            rawGlobal = mapOf(SettingsSchema.comfortableLayout to false),
        )
        assertEquals(Density.COMPACT, prefs.readCustomization().appearance.density)
    }

    @Test
    fun garbageAppearanceKeysFallBackToDefaults() {
        val prefs = InMemoryMindChatPreferences(
            rawAppearanceKeys = mapOf(
                KEY_SHAPE_SCALE to "bogus",
                KEY_DENSITY to "bogus",
            ),
        )
        val appearance = prefs.readCustomization().appearance
        assertEquals(ShapeScale.EXPRESSIVE, appearance.shapeScale)
        assertEquals(Density.COMFORTABLE, appearance.density)
    }
}

package com.mindchat.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.mindchat.app.theme.LightColorScheme
import com.mindchat.app.theme.MindChatShapes
import com.mindchat.app.theme.MindChatTypography
import com.mindchat.app.theme.bubbleContainerColor
import com.mindchat.app.theme.bubbleOutlineColor
import com.mindchat.app.theme.bubbleShape
import com.mindchat.app.theme.chatListBackground
import com.mindchat.app.theme.scaleTypography
import com.mindchat.app.theme.shapesFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure theme mappings (ROADMAP §5.1 acceptance 5): the shape-scale
 * resolution (EXPRESSIVE == the 0.1.6 canonical set, COMPACT/STANDARD corner
 * specs), the typography scale (factor 1.0 identity, only fontSize/lineHeight
 * scaled), the bubble silhouette matrix, and the bubble/chat container colors.
 * [Shapes]/[Typography]/[RoundedCornerShape]/[ColorScheme] are JVM-safe data.
 */
class ThemeMappingTest {

    // --- shapesFor ------------------------------------------------------------

    @Test
    fun expressiveShapesAreTheCanonicalSet() {
        val resolved = shapesFor(ShapeScale.EXPRESSIVE)
        assertEquals(MindChatShapes.extraSmall, resolved.extraSmall)
        assertEquals(MindChatShapes.small, resolved.small)
        assertEquals(MindChatShapes.medium, resolved.medium)
        assertEquals(MindChatShapes.large, resolved.large)
        assertEquals(MindChatShapes.extraLarge, resolved.extraLarge)
    }

    @Test
    fun compactShapesMatchTheSpec() {
        val compact = shapesFor(ShapeScale.COMPACT)
        assertEquals(RoundedCornerShape(2.dp), compact.extraSmall)
        assertEquals(RoundedCornerShape(6.dp), compact.small)
        assertEquals(RoundedCornerShape(10.dp), compact.medium)
        assertEquals(RoundedCornerShape(16.dp), compact.large)
        assertEquals(RoundedCornerShape(20.dp), compact.extraLarge)
    }

    @Test
    fun standardShapesMatchTheM3Baseline() {
        val standard = shapesFor(ShapeScale.STANDARD)
        assertEquals(RoundedCornerShape(4.dp), standard.extraSmall)
        assertEquals(RoundedCornerShape(8.dp), standard.small)
        assertEquals(RoundedCornerShape(12.dp), standard.medium)
        assertEquals(RoundedCornerShape(16.dp), standard.large)
        assertEquals(RoundedCornerShape(28.dp), standard.extraLarge)
    }

    // --- scaleTypography ------------------------------------------------------

    @Test
    fun scaleTypographyIdentityIsExact() {
        val identity = scaleTypography(MindChatTypography, 1.0f)
        assertEquals(MindChatTypography.displayLarge, identity.displayLarge)
        assertEquals(MindChatTypography.displayMedium, identity.displayMedium)
        assertEquals(MindChatTypography.displaySmall, identity.displaySmall)
        assertEquals(MindChatTypography.headlineLarge, identity.headlineLarge)
        assertEquals(MindChatTypography.headlineMedium, identity.headlineMedium)
        assertEquals(MindChatTypography.headlineSmall, identity.headlineSmall)
        assertEquals(MindChatTypography.titleLarge, identity.titleLarge)
        assertEquals(MindChatTypography.titleMedium, identity.titleMedium)
        assertEquals(MindChatTypography.titleSmall, identity.titleSmall)
        assertEquals(MindChatTypography.bodyLarge, identity.bodyLarge)
        assertEquals(MindChatTypography.bodyMedium, identity.bodyMedium)
        assertEquals(MindChatTypography.bodySmall, identity.bodySmall)
        assertEquals(MindChatTypography.labelLarge, identity.labelLarge)
        assertEquals(MindChatTypography.labelMedium, identity.labelMedium)
        assertEquals(MindChatTypography.labelSmall, identity.labelSmall)
    }

    @Test
    fun scaleTypographyScalesOnlySizeAndLineHeight() {
        val scaled = scaleTypography(MindChatTypography, 1.15f)
        val base = MindChatTypography

        assertEquals(base.bodyLarge.fontSize * 1.15f, scaled.bodyLarge.fontSize)
        assertEquals(base.bodyLarge.lineHeight * 1.15f, scaled.bodyLarge.lineHeight)
        assertEquals("letterSpacing must be untouched", base.bodyLarge.letterSpacing, scaled.bodyLarge.letterSpacing)
        assertEquals(base.displayLarge.fontSize * 1.15f, scaled.displayLarge.fontSize)
        assertEquals(base.labelSmall.fontSize * 1.15f, scaled.labelSmall.fontSize)

        val compact = scaleTypography(MindChatTypography, 0.9f)
        assertEquals(base.titleMedium.fontSize * 0.9f, compact.titleMedium.fontSize)
        assertEquals(base.titleMedium.lineHeight * 0.9f, compact.titleMedium.lineHeight)
        assertEquals(base.titleMedium.letterSpacing, compact.titleMedium.letterSpacing)
    }

    // --- bubbleShape ----------------------------------------------------------

    @Test
    fun defaultBubbleKeepsTheSpeechTail() {
        assertEquals(
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp),
            bubbleShape(BubbleStyle.DEFAULT, mine = true),
        )
        assertEquals(
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp),
            bubbleShape(BubbleStyle.DEFAULT, mine = false),
        )
    }

    @Test
    fun roundedBubbleIsUniformSixteen() {
        assertEquals(RoundedCornerShape(16.dp), bubbleShape(BubbleStyle.ROUNDED, mine = true))
        assertEquals(RoundedCornerShape(16.dp), bubbleShape(BubbleStyle.ROUNDED, mine = false))
    }

    @Test
    fun outlinedBubbleKeepsDefaultCorners() {
        assertEquals(bubbleShape(BubbleStyle.DEFAULT, mine = true), bubbleShape(BubbleStyle.OUTLINED, mine = true))
        assertEquals(bubbleShape(BubbleStyle.DEFAULT, mine = false), bubbleShape(BubbleStyle.OUTLINED, mine = false))
    }

    // --- bubble + chat colors -------------------------------------------------

    @Test
    fun bubbleColorsFollowTheStyleMatrix() {
        assertEquals(LightColorScheme.primaryContainer, bubbleContainerColor(BubbleStyle.DEFAULT, mine = true, LightColorScheme))
        assertEquals(LightColorScheme.surfaceContainerHigh, bubbleContainerColor(BubbleStyle.DEFAULT, mine = false, LightColorScheme))
        assertEquals(LightColorScheme.surfaceContainerLowest, bubbleContainerColor(BubbleStyle.OUTLINED, mine = true, LightColorScheme))
        assertEquals(LightColorScheme.surfaceContainerLowest, bubbleContainerColor(BubbleStyle.OUTLINED, mine = false, LightColorScheme))
        assertEquals(LightColorScheme.primaryContainer, bubbleContainerColor(BubbleStyle.ROUNDED, mine = true, LightColorScheme))
    }

    @Test
    fun outlinedBubblesGetABorderOthersDoNot() {
        assertEquals(LightColorScheme.outlineVariant, bubbleOutlineColor(BubbleStyle.OUTLINED, LightColorScheme))
        assertEquals(Color.Transparent, bubbleOutlineColor(BubbleStyle.DEFAULT, LightColorScheme))
        assertEquals(Color.Transparent, bubbleOutlineColor(BubbleStyle.ROUNDED, LightColorScheme))
    }

    @Test
    fun tintedChatBackgroundIsAFaintPrimaryTint() {
        assertEquals(LightColorScheme.background, chatListBackground(ChatBackground.DEFAULT, LightColorScheme))
        assertEquals(
            lerp(LightColorScheme.surface, LightColorScheme.primaryContainer, 0.15f),
            chatListBackground(ChatBackground.TINTED, LightColorScheme),
        )
    }
}

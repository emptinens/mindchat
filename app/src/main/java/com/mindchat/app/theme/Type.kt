package com.mindchat.app.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive type scale on the default system font.
 *
 * Display and headline roles are the expressive part of the scale: larger
 * than the baseline M3 sizes (64/52/44/40/36/32 vs 57/45/36/32/28/24), set in
 * FontWeight.Medium with tight-but-not-cramped line heights (the same ~1.1x
 * to ~1.25x ratio the BOM uses for its emphasized variants). This follows the
 * M3 Expressive guidance of bolder, more editorial display text.
 *
 * Title/body/label roles keep the baseline sizes and tracking so long-form
 * content and controls stay readable and scannable; only the emphasized
 * letter-spacing for displayLarge/displayMedium carries the editorial feel.
 */
val MindChatTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 64.sp,
        lineHeight = 72.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 52.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 52.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 48.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Scales [base]'s `fontSize` and `lineHeight` by [factor] and nothing else.
 * `letterSpacing` is intentionally untouched (tracking is an expressive
 * flourish, not a size); factor 1.0 is the identity, so the DEFAULT text size
 * is exactly [MindChatTypography].
 */
fun scaleTypography(base: Typography, factor: Float): Typography = Typography(
    displayLarge = base.displayLarge.copy(
        fontSize = base.displayLarge.fontSize * factor,
        lineHeight = base.displayLarge.lineHeight * factor,
    ),
    displayMedium = base.displayMedium.copy(
        fontSize = base.displayMedium.fontSize * factor,
        lineHeight = base.displayMedium.lineHeight * factor,
    ),
    displaySmall = base.displaySmall.copy(
        fontSize = base.displaySmall.fontSize * factor,
        lineHeight = base.displaySmall.lineHeight * factor,
    ),
    headlineLarge = base.headlineLarge.copy(
        fontSize = base.headlineLarge.fontSize * factor,
        lineHeight = base.headlineLarge.lineHeight * factor,
    ),
    headlineMedium = base.headlineMedium.copy(
        fontSize = base.headlineMedium.fontSize * factor,
        lineHeight = base.headlineMedium.lineHeight * factor,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontSize = base.headlineSmall.fontSize * factor,
        lineHeight = base.headlineSmall.lineHeight * factor,
    ),
    titleLarge = base.titleLarge.copy(
        fontSize = base.titleLarge.fontSize * factor,
        lineHeight = base.titleLarge.lineHeight * factor,
    ),
    titleMedium = base.titleMedium.copy(
        fontSize = base.titleMedium.fontSize * factor,
        lineHeight = base.titleMedium.lineHeight * factor,
    ),
    titleSmall = base.titleSmall.copy(
        fontSize = base.titleSmall.fontSize * factor,
        lineHeight = base.titleSmall.lineHeight * factor,
    ),
    bodyLarge = base.bodyLarge.copy(
        fontSize = base.bodyLarge.fontSize * factor,
        lineHeight = base.bodyLarge.lineHeight * factor,
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = base.bodyMedium.fontSize * factor,
        lineHeight = base.bodyMedium.lineHeight * factor,
    ),
    bodySmall = base.bodySmall.copy(
        fontSize = base.bodySmall.fontSize * factor,
        lineHeight = base.bodySmall.lineHeight * factor,
    ),
    labelLarge = base.labelLarge.copy(
        fontSize = base.labelLarge.fontSize * factor,
        lineHeight = base.labelLarge.lineHeight * factor,
    ),
    labelMedium = base.labelMedium.copy(
        fontSize = base.labelMedium.fontSize * factor,
        lineHeight = base.labelMedium.lineHeight * factor,
    ),
    labelSmall = base.labelSmall.copy(
        fontSize = base.labelSmall.fontSize * factor,
        lineHeight = base.labelSmall.lineHeight * factor,
    ),
)

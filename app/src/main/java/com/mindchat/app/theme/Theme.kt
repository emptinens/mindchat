package com.mindchat.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/**
 * MindChat's Material 3 Expressive theme.
 *
 * Uses dynamic color (Material You) on Android 12+ when [dynamicColor] is
 * enabled and no per-account [accentSeed] is set. A non-null [accentSeed]
 * overrides the system seed with a fixed Material 3 Expressive accent color
 * for the active account. Otherwise falls back to the brand-indigo static
 * [LightColorScheme] / [DarkColorScheme] on Android 8-11 and when dynamic
 * color is disabled. Both static schemes define the full tonal surface role
 * set (surfaceContainerLowest..Highest, surfaceVariant, outline, ...) with
 * WCAG AA-verified on* pairs, so cards, sheets and dialogs keep their layered
 * surface look on every API level. Always applies the expressive
 * [MindChatShapes] and [MindChatTypography] so every MaterialTheme-backed
 * component renders with the expressive silhouette and type scale.
 */
@Composable
fun MindChatTheme(
    dynamicColor: Boolean,
    accentSeed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        accentSeed != null ->
            if (darkTheme) darkAccentColorScheme(accentSeed) else lightAccentColorScheme(accentSeed)

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MindChatShapes,
        typography = MindChatTypography,
        content = content,
    )
}

/** Light scheme seeded from a fixed accent; the remaining roles stay at the M3 baseline. */
private fun lightAccentColorScheme(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = lerp(seed, Color.White, 0.86f),
    onPrimaryContainer = lerp(seed, Color.Black, 0.55f),
)

/** Dark scheme seeded from a fixed accent; the remaining roles stay at the M3 baseline. */
private fun darkAccentColorScheme(seed: Color) = darkColorScheme(
    primary = lerp(seed, Color.White, 0.40f),
    onPrimary = lerp(seed, Color.Black, 0.55f),
    primaryContainer = lerp(seed, Color.Black, 0.42f),
    onPrimaryContainer = lerp(seed, Color.White, 0.78f),
)

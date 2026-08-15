package com.mindchat.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.mindchat.app.AppearanceProfile
import com.mindchat.app.factor

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
 * surface look on every API level.
 *
 * [appearance] (0.1.7) drives the shape scale ([shapesFor]), the type scale
 * ([scaleTypography]) and the motion speed ([LocalMindChatMotionSpeed]);
 * [LocalMindChatStatusColors] follows the dark/light pair. `MaterialExpressiveTheme`
 * is still `internal` in material3 1.4.0 Kotlin metadata, so the motion
 * tokens are the app-local [MindChatMotionScheme] (M3E P0 B1/B5 fallback).
 */
@Composable
fun MindChatTheme(
    appearance: AppearanceProfile = AppearanceProfile(),
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

    CompositionLocalProvider(
        LocalMindChatStatusColors provides if (darkTheme) DarkMindChatStatusColors else LightMindChatStatusColors,
        LocalMindChatMotionSpeed provides appearance.animationSpeed,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapesFor(appearance.shapeScale),
            typography = scaleTypography(MindChatTypography, appearance.textScale.factor),
            content = content,
        )
    }
}

/**
 * Light scheme seeded from a fixed accent; the remaining roles stay at the M3
 * baseline.
 *
 * B13 deferral (documented, not accidental): `ColorScheme.fromSeed` does NOT
 * exist in material3 1.4.0 (verified against the 1.4.0 AAR), so the accent
 * schemes here still override only the primary family. Broadening
 * secondary/tertiary and the neutral surface family from the seed is deferred
 * to the material3 1.5 bump, where `fromSeed` becomes available; until then a
 * hand-derived tonal family would be unverifiable without a device and is
 * consciously not shipped.
 */
private fun lightAccentColorScheme(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = lerp(seed, Color.White, 0.86f),
    onPrimaryContainer = lerp(seed, Color.Black, 0.55f),
)

/** Dark scheme seeded from a fixed accent; the remaining roles stay at the M3 baseline (see B13 note). */
private fun darkAccentColorScheme(seed: Color) = darkColorScheme(
    primary = lerp(seed, Color.White, 0.40f),
    onPrimary = lerp(seed, Color.Black, 0.55f),
    primaryContainer = lerp(seed, Color.Black, 0.42f),
    onPrimaryContainer = lerp(seed, Color.White, 0.78f),
)

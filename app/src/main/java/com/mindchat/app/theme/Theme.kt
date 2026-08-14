package com.mindchat.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * MindChat's Material 3 Expressive theme.
 *
 * Uses dynamic color (Material You) on Android 12+ when [dynamicColor] is
 * enabled, falling back to the brand-indigo static [LightColorScheme] /
 * [DarkColorScheme] on Android 8-11 and when the user disables dynamic color.
 * Both static schemes define the full tonal surface role set
 * (surfaceContainerLowest..Highest, surfaceVariant, outline, ...) with WCAG
 * AA-verified on* pairs, so cards, sheets and dialogs keep their layered
 * surface look on every API level.
 *
 * Always applies the expressive [MindChatShapes] and [MindChatTypography] so
 * every MaterialTheme-backed component renders with the expressive silhouette
 * and type scale.
 */
@Composable
fun MindChatTheme(
    dynamicColor: Boolean,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
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

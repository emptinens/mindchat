package com.mindchat.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.mindchat.app.BubbleStyle
import com.mindchat.app.ChatBackground

/**
 * MindChat static color roles.
 *
 * The static palettes are generated from the MindChat brand seed (the indigo
 * #3F51B5 of `res/drawable/ic_mindchat.xml`) using the Material 3 tonal
 * "tonal spot" algorithm: primary keeps the full seed chroma, secondary and
 * tertiary are derived companions, and the neutral palettes keep a faint
 * indigo tint so every `surfaceContainer*` role reads as a tonal step of the
 * same family. All `on*` pairs are audited against WCAG AA (>= 4.5:1); the
 * values below were verified with that check.
 *
 * Dynamic color (Material You) takes over on Android 12+ when enabled; these
 * schemes are the contrast-safe fallback on Android 8-11 and when the user
 * turns dynamic color off.
 */

// Brand seed: MindChat indigo, matching the app icon (#3F51B5).
val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    inversePrimary = Color(0xFFBAC3FF),
    primaryFixed = Color(0xFFDEE0FF),
    primaryFixedDim = Color(0xFFBAC3FF),
    onPrimaryFixed = Color(0xFF00105C),
    onPrimaryFixedVariant = Color(0xFF293CA0),
    secondary = Color(0xFF565C84),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE0FF),
    onSecondaryContainer = Color(0xFF12183D),
    secondaryFixed = Color(0xFFDEE0FF),
    secondaryFixedDim = Color(0xFFBEC4F2),
    onSecondaryFixed = Color(0xFF12183D),
    onSecondaryFixedVariant = Color(0xFF3E446B),
    tertiary = Color(0xFF7A5170),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F1),
    onTertiaryContainer = Color(0xFF2F0F2A),
    tertiaryFixed = Color(0xFFFFD7F1),
    tertiaryFixedDim = Color(0xFFEAB7DB),
    onTertiaryFixed = Color(0xFF2F0F2A),
    onTertiaryFixedVariant = Color(0xFF603A57),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBF8FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceDim = Color(0xFFDCD9DE),
    surfaceBright = Color(0xFFFBF8FD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2F7),
    surfaceContainer = Color(0xFFF0EDF1),
    surfaceContainerHigh = Color(0xFFEAE7EC),
    surfaceContainerHighest = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFFDEE0FF),
    onSurfaceVariant = Color(0xFF3E446B),
    outline = Color(0xFF6E759E),
    outlineVariant = Color(0xFFBEC4F2),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF3F0F4),
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0),
    onPrimaryContainer = Color(0xFFDEE0FF),
    inversePrimary = Color(0xFF4355B9),
    primaryFixed = Color(0xFFDEE0FF),
    primaryFixedDim = Color(0xFFBAC3FF),
    onPrimaryFixed = Color(0xFF00105C),
    onPrimaryFixedVariant = Color(0xFF293CA0),
    secondary = Color(0xFFBEC4F2),
    onSecondary = Color(0xFF272E53),
    secondaryContainer = Color(0xFF3E446B),
    onSecondaryContainer = Color(0xFFDEE0FF),
    secondaryFixed = Color(0xFFDEE0FF),
    secondaryFixedDim = Color(0xFFBEC4F2),
    onSecondaryFixed = Color(0xFF12183D),
    onSecondaryFixedVariant = Color(0xFF3E446B),
    tertiary = Color(0xFFEAB7DB),
    onTertiary = Color(0xFF472440),
    tertiaryContainer = Color(0xFF603A57),
    onTertiaryContainer = Color(0xFFFFD7F1),
    tertiaryFixed = Color(0xFFFFD7F1),
    tertiaryFixedDim = Color(0xFFEAB7DB),
    onTertiaryFixed = Color(0xFF2F0F2A),
    onTertiaryFixedVariant = Color(0xFF603A57),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE4E1E6),
    surfaceDim = Color(0xFF131316),
    surfaceBright = Color(0xFF39393C),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF353438),
    surfaceVariant = Color(0xFF3E446B),
    onSurfaceVariant = Color(0xFFBEC4F2),
    outline = Color(0xFF888EB9),
    outlineVariant = Color(0xFF3E446B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E1E6),
    inverseOnSurface = Color(0xFF303034),
)

// --- Semantic status colors (0.1.7, M3E P0 B2) ------------------------------

/**
 * Semantic presence/connection dot colors, replacing the duplicated hard-coded
 * ARGB literals that used to live in MindChatApp.kt. Both schemes are audited
 * against WCAG AA on their host surface families:
 *
 * Light (host `surfaceContainer` 0xFFF0EDF1):
 *  - online 0xFF27732F  5.05:1
 *  - away   0xFF7A5900  5.55:1
 *  - failed 0xFFBA1A1A  (= light `colorScheme.error`) 5.56:1
 *
 * Dark (host `surface` 0xFF131316):
 *  - online 0xFF81C784  9.22:1
 *  - away   0xFFFFD54F  13.14:1
 *  - failed 0xFFFFB4AB  (= dark `colorScheme.error`) 10.92:1
 *
 * The `on*` pairs are the content colors for anything rendered on top of a dot
 * (>= 4.5:1 on their dot colors; White on the light dots, Black on the dark
 * dots).
 */
data class MindChatStatusColors(
    val online: Color,
    val away: Color,
    val failed: Color,
    val onOnline: Color,
    val onAway: Color,
    val onFailed: Color,
)

val LightMindChatStatusColors = MindChatStatusColors(
    online = Color(0xFF27732F),
    away = Color(0xFF7A5900),
    failed = Color(0xFFBA1A1A),
    onOnline = Color.White,
    onAway = Color.White,
    onFailed = Color.White,
)

val DarkMindChatStatusColors = MindChatStatusColors(
    online = Color(0xFF81C784),
    away = Color(0xFFFFD54F),
    failed = Color(0xFFFFB4AB),
    onOnline = Color.Black,
    onAway = Color.Black,
    onFailed = Color.Black,
)

/** Theme-provided status colors; [MindChatTheme] installs the light/dark pair. */
val LocalMindChatStatusColors = staticCompositionLocalOf { LightMindChatStatusColors }

/**
 * Message-bubble container color: OUTLINED bubbles use the lowest container
 * with an `outlineVariant` border (see [bubbleOutlineColor]); filled styles
 * keep today's `primaryContainer` (mine) / `surfaceContainerHigh` (theirs).
 */
fun bubbleContainerColor(style: BubbleStyle, mine: Boolean, scheme: ColorScheme): Color = when {
    style == BubbleStyle.OUTLINED -> scheme.surfaceContainerLowest
    mine -> scheme.primaryContainer
    else -> scheme.surfaceContainerHigh
}

/** The 1 dp border color for OUTLINED bubbles; transparent otherwise. */
fun bubbleOutlineColor(style: BubbleStyle, scheme: ColorScheme): Color =
    if (style == BubbleStyle.OUTLINED) scheme.outlineVariant else Color.Transparent

/**
 * Message-list background: TINTED blends a faint `primaryContainer` tint into
 * the surface (bubbles stay opaque so their on* contrast is preserved);
 * DEFAULT keeps the plain surface/background.
 */
fun chatListBackground(background: ChatBackground, scheme: ColorScheme): Color =
    if (background == ChatBackground.TINTED) {
        lerp(scheme.surface, scheme.primaryContainer, 0.15f)
    } else {
        scheme.background
    }

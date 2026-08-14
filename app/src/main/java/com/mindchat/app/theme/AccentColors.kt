package com.mindchat.app.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.mindchat.app.R

/**
 * Fixed Material 3 Expressive accent choices for per-account theming (0.1.5).
 *
 * A `null`/default accent keeps the dynamic (Material You) or baseline scheme;
 * selecting an accent seeds the color scheme from its [color] instead.
 */
data class AccentOption(
    val key: String,
    @StringRes val labelRes: Int,
    val color: Color,
)

/** Key stored in the per-account profile when the user wants the system default. */
const val ACCENT_DEFAULT_KEY = "default"

val AccentOptions: List<AccentOption> = listOf(
    AccentOption("ocean", R.string.accent_ocean, Color(0xFF00639B)),
    AccentOption("forest", R.string.accent_forest, Color(0xFF2E7D32)),
    AccentOption("sunset", R.string.accent_sunset, Color(0xFFE65100)),
    AccentOption("berry", R.string.accent_berry, Color(0xFF7B1FA2)),
    AccentOption("crimson", R.string.accent_crimson, Color(0xFFC62828)),
)

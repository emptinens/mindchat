package com.mindchat.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shape scheme.
 *
 * The scale (extraSmall 8 / small 12 / medium 16 / large 24 / extraLarge 32)
 * is one notch rounder than the baseline M3 scale (4 / 8 / 12 / 16 / 28) and
 * matches the M3 Expressive guidance:
 *  - extraSmall (8dp): text fields and other fine controls, just enough of a
 *    rounded corner to stay "pill-ish" without losing input affordance;
 *  - small (12dp) / medium (16dp): buttons, chips, cards and sheet content;
 *  - large (24dp): large cards and the FAB;
 *  - extraLarge (32dp): top-level surfaces, dialogs and modal sheets.
 *
 * Every M3 component resolves its corner from these slots via
 * `MaterialTheme.shapes`, so the whole app inherits the expressive silhouette
 * without ad-hoc radii (chat bubbles intentionally keep their own speech-tail
 * shape in MindChatApp.kt).
 */
val MindChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

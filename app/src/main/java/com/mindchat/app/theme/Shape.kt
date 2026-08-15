package com.mindchat.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.mindchat.app.BubbleStyle
import com.mindchat.app.ShapeScale

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
 * without ad-hoc radii. Chat bubbles intentionally keep their own speech-tail
 * shape ([bubbleShape]) beside the scale.
 */

/** Expressive scale (the 0.1.6 canonical set, unchanged). */
val MindChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Compact scale: tightly clipped controls for dense screens. */
val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Standard M3 baseline scale. */
val StandardShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Resolves the app shape scale from the appearance engine. */
fun shapesFor(scale: ShapeScale): Shapes = when (scale) {
    ShapeScale.COMPACT -> CompactShapes
    ShapeScale.STANDARD -> StandardShapes
    ShapeScale.EXPRESSIVE -> MindChatShapes
}

/**
 * Chat bubble silhouette. DEFAULT keeps today's asymmetric speech tail (20 dp
 * main corners, 4 dp tail corner on the sender's bottom corner); ROUNDED is a
 * uniform 16 dp; OUTLINED keeps DEFAULT's corners because the outline style is
 * a fill/border treatment, orthogonal to the silhouette.
 */
fun bubbleShape(style: BubbleStyle, mine: Boolean): RoundedCornerShape = when (style) {
    BubbleStyle.ROUNDED -> RoundedCornerShape(16.dp)
    else -> RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 4.dp,
        bottomEnd = if (mine) 4.dp else 20.dp,
    )
}

/**
 * Floating dock container silhouette (extraLarge family: 28 dp pill) and the
 * per-item selection pill (20 dp). Both derive from the expressive scale and
 * live here so `theme/` stays the single source of truth for ad-hoc radii.
 */
val MindChatDockShape = RoundedCornerShape(28.dp)
val MindChatDockItemShape = RoundedCornerShape(20.dp)

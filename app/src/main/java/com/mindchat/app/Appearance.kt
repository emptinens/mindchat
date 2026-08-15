package com.mindchat.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Client-side appearance engine (0.1.7).
 *
 * Single home for the appearance domain: the ergonomic dimensions a user can
 * configure globally ([AppearanceProfile]), the per-account chat-personality
 * overrides ([AccountProfile.bubbleStyle] / [AccountProfile.chatBackground]),
 * and every shared decision rule that turns those into theme inputs.
 * Everything here is pure and JVM-testable (mirroring the [GatewayInput]
 * pattern); the gateway implementations call the same functions so the
 * preview cannot drift from the native behavior.
 *
 * Design (see ROADMAP §5.1):
 *  - Six global dimensions, stored as stable ASCII enum keys (never localized
 *    labels, never `Enum.name`, so renames stay cheap).
 *  - Defaults reproduce 0.1.6 visuals exactly: EXPRESSIVE shapes, COMFORTABLE
 *    density, DEFAULT text/animation/bubble/background.
 *  - The global↔override merge is exactly one pure function:
 *    [resolveAppearance].
 */

/** Enums that carry a stable ASCII storage key (never a localized label). */
internal interface KeyedEnum {
    val key: String
}

/** Corner scale of the whole app shell. */
enum class ShapeScale(override val key: String) : KeyedEnum {
    COMPACT("compact"),
    STANDARD("standard"),
    EXPRESSIVE("expressive"),
}

/** List density: vertical spacing between rows and row padding. */
enum class Density(override val key: String) : KeyedEnum {
    COMPACT("compact"),
    STANDARD("standard"),
    COMFORTABLE("comfortable"),
}

/** Typography scale applied on top of the expressive [MindChatTypography]. */
enum class TextScale(override val key: String) : KeyedEnum {
    COMPACT("compact"),
    DEFAULT("default"),
    LARGE("large"),
}

/** Global motion speed; maps to the motion-scheme effects token family. */
enum class AnimationSpeed(override val key: String) : KeyedEnum {
    FASTER("faster"),
    DEFAULT("default"),
    SLOWER("slower"),
}

/** Chat bubble silhouette. */
enum class BubbleStyle(override val key: String) : KeyedEnum {
    DEFAULT("default"),
    ROUNDED("rounded"),
    OUTLINED("outlined"),
}

/** Chat message-list background treatment. */
enum class ChatBackground(override val key: String) : KeyedEnum {
    DEFAULT("default"),
    TINTED("tinted"),
}

/**
 * Global appearance profile. Defaults are chosen so a fresh install (or an
 * upgrade that never touches appearance) renders exactly like 0.1.6.
 */
data class AppearanceProfile(
    val shapeScale: ShapeScale = ShapeScale.EXPRESSIVE,
    val density: Density = Density.COMFORTABLE,
    val textScale: TextScale = TextScale.DEFAULT,
    val animationSpeed: AnimationSpeed = AnimationSpeed.DEFAULT,
    val bubbleStyle: BubbleStyle = BubbleStyle.DEFAULT,
    val chatBackground: ChatBackground = ChatBackground.DEFAULT,
)

/**
 * The one merge rule: a per-account profile overrides only the chat
 * personality dimensions (bubble style, chat background); ergonomics stay
 * global. [AccountProfile.accentKey] is deliberately untouched here - the
 * accent is applied independently at the [MindChatTheme] call site.
 */
internal fun resolveAppearance(global: AppearanceProfile, profile: AccountProfile?): AppearanceProfile =
    global.copy(
        bubbleStyle = profile?.bubbleStyle ?: global.bubbleStyle,
        chatBackground = profile?.chatBackground ?: global.chatBackground,
    )

/**
 * Stable ASCII enum lookup with an unknown-key fallback to [default] (forward
 * compatibility: a future release that drops an option never crashes a store
 * that still holds it).
 */
internal fun <T> fromKey(values: Array<T>, key: String?, default: T): T where T : Enum<T>, T : KeyedEnum =
    values.firstOrNull { it.key == key } ?: default

/**
 * Maps the 0.1.6 boolean layout preference onto the 0.1.7 three-state density
 * scale: `true` (comfortable) keeps the COMFORTABLE default, `false` maps to
 * the new STANDARD middle value, and an absent legacy key keeps COMFORTABLE.
 */
internal fun densityFromLegacy(comfortableLayout: Boolean?): Density = when (comfortableLayout) {
    true -> Density.COMFORTABLE
    false -> Density.STANDARD
    null -> Density.COMFORTABLE
}

// --- Typography factors ------------------------------------------------------

internal const val TEXT_SCALE_FACTOR_COMPACT = 0.9f
internal const val TEXT_SCALE_FACTOR_DEFAULT = 1.0f
internal const val TEXT_SCALE_FACTOR_LARGE = 1.15f

internal val TextScale.factor: Float
    get() = when (this) {
        TextScale.COMPACT -> TEXT_SCALE_FACTOR_COMPACT
        TextScale.DEFAULT -> TEXT_SCALE_FACTOR_DEFAULT
        TextScale.LARGE -> TEXT_SCALE_FACTOR_LARGE
    }

// --- Motion factors ----------------------------------------------------------

internal const val MOTION_FACTOR_FASTER = 0.6f
internal const val MOTION_FACTOR_DEFAULT = 1.0f
internal const val MOTION_FACTOR_SLOWER = 1.8f

internal val AnimationSpeed.factor: Float
    get() = when (this) {
        AnimationSpeed.FASTER -> MOTION_FACTOR_FASTER
        AnimationSpeed.DEFAULT -> MOTION_FACTOR_DEFAULT
        AnimationSpeed.SLOWER -> MOTION_FACTOR_SLOWER
    }

// --- Density metrics ---------------------------------------------------------

/** Vertical gap between conversation rows. */
internal val Density.listSpacing: Dp
    get() = when (this) {
        Density.COMPACT -> 2.dp
        Density.STANDARD -> 4.dp
        Density.COMFORTABLE -> 8.dp
    }

/** Vertical padding inside a conversation row. */
internal val Density.rowPadding: Dp
    get() = when (this) {
        Density.COMPACT -> 12.dp
        Density.STANDARD -> 14.dp
        Density.COMFORTABLE -> 16.dp
    }

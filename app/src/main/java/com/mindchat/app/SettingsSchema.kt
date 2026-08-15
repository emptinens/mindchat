package com.mindchat.app

/**
 * Typed settings schema (0.1.7 settings platform).
 *
 * Every user-facing setting is a first-class [SettingKey] object carrying its
 * type, default, category, scope, storage key, label resources and
 * availability. The UI derives its rows from [SettingsSchema.all] via
 * `GatewayInput.catalogRows`, the store persists by key, and the gateway
 * mutates by key, so adding a setting in 0.1.8 is one new key object plus one
 * EN/RU string pair, never a rework of storage, navigation or screens.
 *
 * Two hard rules:
 *
 * 1. **Storage keys are immutable.** `storageKey` of every existing setting
 *    equals today's `SharedPreferences` key byte-for-byte, so 0.1.7 ships with
 *    zero migration. New keys get new names; deleted keys are never reused.
 * 2. **Secrets never get a `SettingKey`.** This schema is device-local
 *    non-secret configuration only (the same contract `MindChatPreferences`
 *    documents). Passwords, tokens and future encryption material go through
 *    the core/Keystore surface, never through this schema, `SettingsSnapshot`
 *    or `MindChatUiState`.
 */

/** Top-level settings sections shown on the settings root screen. */
enum class SettingCategory {
    APPEARANCE,
    ACCOUNTS,
    PRIVACY_SECURITY,
    NOTIFICATIONS,
    STORAGE,
    ABOUT,
}

/** Where a setting applies: device-wide or per account. */
enum class SettingScope { GLOBAL, PER_ACCOUNT }

/**
 * Whether the feature behind a key is actually backed by the domain core.
 * [SettingAvailability.PENDING_CORE] keys render as honest disabled rows with
 * the "not implemented yet" supporting text; they are never persisted and
 * never fake behavior.
 */
enum class SettingAvailability { IMPLEMENTED, PENDING_CORE }

/**
 * A typed, immutable settings key. The generic [T] is the value type; the key
 * knows how to [encode]/[decode] its value to a stable string form and falls
 * back to [default] when decoding garbage.
 */
sealed interface SettingKey<out T> {
    /** Stable on-disk key. Never renamed, never reused after deletion. */
    val storageKey: String

    /** The value used when nothing was stored. */
    val default: T

    val category: SettingCategory

    val scope: SettingScope

    val availability: SettingAvailability

    /** Label resource; the EN and RU pair must exist in strings.xml. */
    val labelRes: Int

    /** Extra search terms as string resources (optional). */
    val keywords: List<Int>

    /** Encodes a value to its stable string form ([UnsafeVariance] keeps the
     *  covariant [T] usable as an encoder argument). */
    fun encode(value: @UnsafeVariance T): String

    /** Returns [default] on garbage instead of throwing. */
    fun decode(raw: String): T
}

/** Boolean setting stored as "true"/"false". */
data class BooleanKey(
    override val storageKey: String,
    override val default: Boolean,
    override val category: SettingCategory,
    override val scope: SettingScope,
    override val availability: SettingAvailability,
    override val labelRes: Int,
    override val keywords: List<Int> = emptyList(),
) : SettingKey<Boolean> {
    override fun encode(value: Boolean): String = value.toString()

    override fun decode(raw: String): Boolean = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }
}

/** Free-form string setting stored verbatim. */
data class StringKey(
    override val storageKey: String,
    override val default: String,
    override val category: SettingCategory,
    override val scope: SettingScope,
    override val availability: SettingAvailability,
    override val labelRes: Int,
    override val keywords: List<Int> = emptyList(),
) : SettingKey<String> {
    override fun encode(value: String): String = value

    override fun decode(raw: String): String = raw
}

/** Enum setting stored by [Enum.name]. */
data class EnumKey<E : Enum<E>>(
    override val storageKey: String,
    override val default: E,
    override val category: SettingCategory,
    override val scope: SettingScope,
    override val availability: SettingAvailability,
    override val labelRes: Int,
    val enumClass: Class<E>,
    override val keywords: List<Int> = emptyList(),
) : SettingKey<E> {
    override fun encode(value: E): String = value.name

    override fun decode(raw: String): E {
        val match = enumClass.enumConstants?.firstOrNull { it.name == raw }
        return match ?: default
    }
}

/**
 * The complete settings catalog. `all` is the single list the store, the
 * gateway, the catalog renderer and the search function all iterate, so every
 * new setting is registered in exactly one place.
 */
object SettingsSchema {
    val dynamicColor = BooleanKey(
        storageKey = "dynamic_color",
        default = true,
        category = SettingCategory.APPEARANCE,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.IMPLEMENTED,
        labelRes = R.string.use_dynamic_colors,
    )

    val comfortableLayout = BooleanKey(
        storageKey = "comfortable_layout",
        default = true,
        category = SettingCategory.APPEARANCE,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.IMPLEMENTED,
        labelRes = R.string.comfortable_layout,
    )

    val appLockEnabled = BooleanKey(
        storageKey = "app_lock_enabled",
        default = false,
        category = SettingCategory.PRIVACY_SECURITY,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.IMPLEMENTED,
        labelRes = R.string.app_lock,
    )

    // PENDING_CORE: the FFI does not model message search yet; the row renders
    // honestly disabled and is never persisted.
    val messageSearch = BooleanKey(
        storageKey = "message_search",
        default = false,
        category = SettingCategory.PRIVACY_SECURITY,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.PENDING_CORE,
        labelRes = R.string.message_search,
    )

    // PENDING_CORE: end-to-end encryption policy lands with the 0.1.9 core work.
    val encryption = BooleanKey(
        storageKey = "encryption",
        default = false,
        category = SettingCategory.PRIVACY_SECURITY,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.PENDING_CORE,
        labelRes = R.string.encryption,
    )

    // PENDING_CORE: per-app notification channels and wiring ship in 0.1.8.
    val messageNotifications = BooleanKey(
        storageKey = "message_notifications",
        default = false,
        category = SettingCategory.NOTIFICATIONS,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.PENDING_CORE,
        labelRes = R.string.message_notifications,
    )

    val groupNotifications = BooleanKey(
        storageKey = "group_notifications",
        default = false,
        category = SettingCategory.NOTIFICATIONS,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.PENDING_CORE,
        labelRes = R.string.group_notifications,
    )

    /** Every key in the catalog, in display order. */
    val all: List<SettingKey<*>> = listOf(
        dynamicColor,
        comfortableLayout,
        appLockEnabled,
        messageSearch,
        encryption,
        messageNotifications,
        groupNotifications,
    )
}

/**
 * Immutable, map-backed projection of the stored settings. The map holds only
 * non-default overrides (mirroring how profiles store only set fields), so map
 * equality gives the fast-path diff a cheap O(keys) comparison.
 */
data class SettingsSnapshot(
    private val values: Map<SettingKey<*>, Any> = emptyMap(),
) {
    /** The typed value for [key], falling back to the key's default. */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: SettingKey<T>): T = (values[key] ?: key.default) as T

    /** The stored overrides; convenience for gateway persistence round trips. */
    val overrides: Map<SettingKey<*>, Any> get() = values

    // Convenience getters kept for theme code this release.
    val dynamicColor: Boolean get() = get(SettingsSchema.dynamicColor)

    val comfortableLayout: Boolean get() = get(SettingsSchema.comfortableLayout)

    val appLockEnabled: Boolean get() = get(SettingsSchema.appLockEnabled)
}

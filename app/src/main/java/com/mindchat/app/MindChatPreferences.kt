package com.mindchat.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Non-sensitive appearance and local-behaviour choices selected by the user. */
data class MindChatCustomization(
    val dynamicColor: Boolean = true,
    val appearance: AppearanceProfile = AppearanceProfile(),
    val appLockEnabled: Boolean = false,
)

/**
 * Per-account, device-local profile customization (0.1.5, extended 0.1.7).
 *
 * Nothing here is a secret or server state: [avatarUri] is a local content URI
 * or copied file path, [statusMessage] is local-only, [accentKey] selects one
 * of the fixed Material 3 Expressive accent options (null = system), and
 * [bubbleStyle] / [chatBackground] override the global appearance defaults for
 * this account's chat screen only (null = follow global).
 *
 * Deliberately **not** folded into [SettingsSchema] (see the settings plan,
 * decision D7): it is a 0.1.5 surface already used by the drawer, theme
 * seeding and snapshot mapping, and keeping it stable avoids a risky merge.
 */
data class AccountProfile(
    val avatarUri: String? = null,
    val statusMessage: String? = null,
    val accentKey: String? = null,
    val bubbleStyle: BubbleStyle? = null,
    val chatBackground: ChatBackground? = null,
)

/** Small persistence boundary that keeps UI customization independent of the core binding. */
interface MindChatPreferences {
    // --- 0.1.5/0.1.6 aggregate surface (kept for compatibility; implemented
    // over the key-based store below so there is a single source of truth) ---

    fun readCustomization(): MindChatCustomization

    fun writeCustomization(customization: MindChatCustomization)

    /** Reads all stored per-account profiles, keyed by account id. */
    fun readProfiles(): Map<Long, AccountProfile>

    /** Stores one per-account profile. */
    fun writeProfile(accountId: Long, profile: AccountProfile)

    /** Removes one per-account profile; a no-op when the account had none. */
    fun removeProfile(accountId: Long)

    // --- 0.1.7 key-based settings store -----------------------------------

    /** Reads a typed setting, falling back to its default when absent. */
    fun <T> read(key: SettingKey<T>): T

    /** Writes a typed setting under the key's immutable storage key. */
    fun <T> write(key: SettingKey<T>, value: T)

    /** All stored non-default global settings, keyed by their keys. */
    fun readAll(): Map<SettingKey<*>, Any>

    /**
     * Whether the user dismissed the one-time diagnostics quarantine notice
     * (ROADMAP 6.5). Device-local, non-secret; remembered across restarts.
     */
    fun readQuarantineNoticeDismissed(): Boolean

    /** Remembers that the user dismissed the quarantine notice. */
    fun writeQuarantineNoticeDismissed(dismissed: Boolean)

}
    }
}

// --- 0.1.7 appearance storage keys (stable ASCII, never localized) -----------

internal const val KEY_SHAPE_SCALE = "shape_scale"
internal const val KEY_DENSITY = "density"
internal const val KEY_TEXT_SCALE = "text_scale"
internal const val KEY_ANIMATION_SPEED = "animation_speed"
internal const val KEY_BUBBLE_STYLE = "bubble_style"
internal const val KEY_CHAT_BACKGROUND = "chat_background"

private val SHAPE_SCALE_VALUES = ShapeScale.entries.toTypedArray()
private val DENSITY_VALUES = Density.entries.toTypedArray()
private val TEXT_SCALE_VALUES = TextScale.entries.toTypedArray()
private val ANIMATION_SPEED_VALUES = AnimationSpeed.entries.toTypedArray()
private val BUBBLE_STYLE_VALUES = BubbleStyle.entries.toTypedArray()
private val CHAT_BACKGROUND_VALUES = ChatBackground.entries.toTypedArray()

/** Android-backed storage for non-secret UI preferences only. */
class SharedPreferencesMindChatPreferences(context: Context) : MindChatPreferences {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    // --- 0.1.5/0.1.6 aggregate surface ------------------------------------

    override fun readCustomization(): MindChatCustomization = MindChatCustomization(
        dynamicColor = read(SettingsSchema.dynamicColor),
        appearance = readAppearance(),
        appLockEnabled = read(SettingsSchema.appLockEnabled),
    )

    override fun writeCustomization(customization: MindChatCustomization) {
        write(SettingsSchema.dynamicColor, customization.dynamicColor)
        writeAppearance(customization.appearance)
        write(SettingsSchema.appLockEnabled, customization.appLockEnabled)
    }

    override fun readProfiles(): Map<Long, AccountProfile> {
        val accountIds = preferences.getStringSet(KEY_PROFILE_ACCOUNT_IDS, emptySet()).orEmpty()
        return accountIds
            .mapNotNull { id -> id.toLongOrNull() }
            .associateWith { accountId ->
                AccountProfile(
                    avatarUri = preferences.getString(profileKey(accountId, KEY_AVATAR), null),
                    statusMessage = preferences.getString(profileKey(accountId, KEY_STATUS), null),
                    accentKey = preferences.getString(profileKey(accountId, KEY_ACCENT), null),
                    bubbleStyle = preferences.getString(profileKey(accountId, KEY_PROFILE_BUBBLE_STYLE), null)
                        ?.let { fromKey(BUBBLE_STYLE_VALUES, it, BubbleStyle.DEFAULT) },
                    chatBackground = preferences.getString(profileKey(accountId, KEY_PROFILE_CHAT_BACKGROUND), null)
                        ?.let { fromKey(CHAT_BACKGROUND_VALUES, it, ChatBackground.DEFAULT) },
                )
            }
            .filterValues {
                it.avatarUri != null || it.statusMessage != null || it.accentKey != null ||
                    it.bubbleStyle != null || it.chatBackground != null
            }
    }

    override fun writeProfile(accountId: Long, profile: AccountProfile) {
        preferences.edit {
            val ids = (preferences.getStringSet(KEY_PROFILE_ACCOUNT_IDS, emptySet()).orEmpty()).toMutableSet()
            ids.add(accountId.toString())
            putStringSet(KEY_PROFILE_ACCOUNT_IDS, ids)
            writeOrRemove(profileKey(accountId, KEY_AVATAR), profile.avatarUri)
            writeOrRemove(profileKey(accountId, KEY_STATUS), profile.statusMessage)
            writeOrRemove(profileKey(accountId, KEY_ACCENT), profile.accentKey)
            writeOrRemove(profileKey(accountId, KEY_PROFILE_BUBBLE_STYLE), profile.bubbleStyle?.key)
            writeOrRemove(profileKey(accountId, KEY_PROFILE_CHAT_BACKGROUND), profile.chatBackground?.key)
        }
    }

    override fun removeProfile(accountId: Long) {
        preferences.edit {
            val ids = (preferences.getStringSet(KEY_PROFILE_ACCOUNT_IDS, emptySet()).orEmpty()).toMutableSet()
            ids.remove(accountId.toString())
            putStringSet(KEY_PROFILE_ACCOUNT_IDS, ids)
            remove(profileKey(accountId, KEY_AVATAR))
            remove(profileKey(accountId, KEY_STATUS))
            remove(profileKey(accountId, KEY_ACCENT))
            remove(profileKey(accountId, KEY_PROFILE_BUBBLE_STYLE))
            remove(profileKey(accountId, KEY_PROFILE_CHAT_BACKGROUND))
        }
    }

    // --- 0.1.7 key-based settings store -----------------------------------

    override fun <T> read(key: SettingKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        return (readRaw(key) ?: key.default) as T
    }

    private fun readRaw(key: SettingKey<*>): Any? = when (key) {
        // Typed getters keep the on-disk value types identical to 0.1.6
        // (booleans stay booleans), so the store a 0.1.6 build wrote reads
        // back unchanged with zero migration code.
        is BooleanKey -> preferences.getBoolean(key.storageKey, key.default)
    }

    override fun <T> write(key: SettingKey<T>, value: T) {
        preferences.edit {
            when (key) {
                is BooleanKey -> putBoolean(key.storageKey, value as Boolean)
            }
        }
    }

    override fun readAll(): Map<SettingKey<*>, Any> = SettingsSchema.all
        .filter { preferences.contains(it.storageKey) }
        .mapNotNull { key ->
            val value = readRaw(key) ?: return@mapNotNull null
            if (value == key.default) null else key to value
        }
        .toMap()

    override fun readQuarantineNoticeDismissed(): Boolean =
        preferences.getBoolean(KEY_QUARANTINE_NOTICE_DISMISSED, false)

    override fun writeQuarantineNoticeDismissed(dismissed: Boolean) {
        preferences.edit { putBoolean(KEY_QUARANTINE_NOTICE_DISMISSED, dismissed) }
    }

    /**
     * Reads the six appearance keys. The `density` key wins when present; a
     * store that only has the legacy `comfortable_layout` boolean is migrated
     * through [densityFromLegacy] so 0.1.6 users keep their choice.
     */
    private fun readAppearance(): AppearanceProfile = AppearanceProfile(
        shapeScale = fromKey(
            SHAPE_SCALE_VALUES,
            preferences.getString(KEY_SHAPE_SCALE, null),
            ShapeScale.EXPRESSIVE,
        ),
        density = if (preferences.contains(KEY_DENSITY)) {
            fromKey(
                DENSITY_VALUES,
                preferences.getString(KEY_DENSITY, null),
                Density.COMFORTABLE,
            )
        } else {
            densityFromLegacy(
                preferences.takeIf { it.contains(SettingsSchema.comfortableLayout.storageKey) }
                    ?.getBoolean(SettingsSchema.comfortableLayout.storageKey, true),
            )
        },
        textScale = fromKey(
            TEXT_SCALE_VALUES,
            preferences.getString(KEY_TEXT_SCALE, null),
            TextScale.DEFAULT,
        ),
        animationSpeed = fromKey(
            ANIMATION_SPEED_VALUES,
            preferences.getString(KEY_ANIMATION_SPEED, null),
            AnimationSpeed.DEFAULT,
        ),
        bubbleStyle = fromKey(
            BUBBLE_STYLE_VALUES,
            preferences.getString(KEY_BUBBLE_STYLE, null),
            BubbleStyle.DEFAULT,
        ),
        chatBackground = fromKey(
            CHAT_BACKGROUND_VALUES,
            preferences.getString(KEY_CHAT_BACKGROUND, null),
            ChatBackground.DEFAULT,
        ),
    )

    /** Writes the six appearance keys plus the derived legacy boolean. */
    private fun writeAppearance(appearance: AppearanceProfile) {
        preferences.edit {
            putString(KEY_SHAPE_SCALE, appearance.shapeScale.key)
            putString(KEY_DENSITY, appearance.density.key)
            putString(KEY_TEXT_SCALE, appearance.textScale.key)
            putString(KEY_ANIMATION_SPEED, appearance.animationSpeed.key)
            putString(KEY_BUBBLE_STYLE, appearance.bubbleStyle.key)
            putString(KEY_CHAT_BACKGROUND, appearance.chatBackground.key)
            // Keep the 0.1.6 key derived so the legacy settings toggle (still
            // rendered by the catalog) never disagrees with the density engine.
            putBoolean(SettingsSchema.comfortableLayout.storageKey, appearance.density == Density.COMFORTABLE)
        }
    }

    private fun android.content.SharedPreferences.Editor.writeOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private companion object {
        const val PREFERENCES_FILE = "mindchat_customization"
        const val KEY_PROFILE_ACCOUNT_IDS = "profile_account_ids"
        const val KEY_QUARANTINE_NOTICE_DISMISSED = "quarantine_notice_dismissed"
        const val KEY_AVATAR = "avatar"
        const val KEY_STATUS = "status"
        const val KEY_ACCENT = "accent"
        const val KEY_PROFILE_BUBBLE_STYLE = "bubble_style"
        const val KEY_PROFILE_CHAT_BACKGROUND = "chat_background"

        fun profileKey(accountId: Long, field: String) = "profile_${accountId}_$field"
    }
}

/**
 * Preview and JVM-test implementation with the same contract as Android
 * storage. [rawAppearanceKeys] and [rawGlobal] seed the underlying store
 * before [initial] is applied (used by migration tests to replay a 0.1.6
 * store that only has the legacy `comfortable_layout` boolean).
 */
class InMemoryMindChatPreferences(
    initial: MindChatCustomization = MindChatCustomization(),
    private val profiles: MutableMap<Long, AccountProfile> = mutableMapOf(),
    rawAppearanceKeys: Map<String, String> = emptyMap(),
    rawGlobal: Map<SettingKey<*>, Any> = emptyMap(),
) : MindChatPreferences {
    private val global: MutableMap<SettingKey<*>, Any> = mutableMapOf()
    private val appearanceStore: MutableMap<String, String> = mutableMapOf()
    private var quarantineNoticeDismissed = false

    init {
        appearanceStore.putAll(rawAppearanceKeys)
        global.putAll(rawGlobal)
        // A default customization writes nothing so a raw-seeded store (e.g. a
        // legacy `comfortable_layout` only) is preserved for the density
        // migration path; a non-default initial still writes through the
        // normal writeCustomization contract.
        if (initial != MindChatCustomization()) {
            writeCustomization(initial)
        }
    }

    override fun readCustomization(): MindChatCustomization = MindChatCustomization(
        dynamicColor = read(SettingsSchema.dynamicColor),
        appearance = readAppearance(),
        appLockEnabled = read(SettingsSchema.appLockEnabled),
    )

    override fun writeCustomization(customization: MindChatCustomization) {
        write(SettingsSchema.dynamicColor, customization.dynamicColor)
        writeAppearance(customization.appearance)
        write(SettingsSchema.appLockEnabled, customization.appLockEnabled)
    }

    override fun readProfiles(): Map<Long, AccountProfile> = profiles.toMap()

    override fun writeProfile(accountId: Long, profile: AccountProfile) {
        if (profile.avatarUri == null && profile.statusMessage == null && profile.accentKey == null &&
            profile.bubbleStyle == null && profile.chatBackground == null
        ) {
            profiles.remove(accountId)
        } else {
            profiles[accountId] = profile
        }
    }

    override fun removeProfile(accountId: Long) {
        profiles.remove(accountId)
    }

    override fun <T> read(key: SettingKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        return (global[key] as? T) ?: key.default
    }

    override fun <T> write(key: SettingKey<T>, value: T) {
        global[key] = value as Any
    }

    override fun readAll(): Map<SettingKey<*>, Any> =
        global.filter { (key, value) -> value != key.default }

    override fun readQuarantineNoticeDismissed(): Boolean = quarantineNoticeDismissed

    override fun writeQuarantineNoticeDismissed(dismissed: Boolean) {
        quarantineNoticeDismissed = dismissed
    }

    // --- 0.1.7 appearance store (mirrors the Android key layout) --------------

    private fun readAppearance(): AppearanceProfile = AppearanceProfile(
        shapeScale = fromKey(SHAPE_SCALE_VALUES, appearanceStore[KEY_SHAPE_SCALE], ShapeScale.EXPRESSIVE),
        density = if (appearanceStore.containsKey(KEY_DENSITY)) {
            fromKey(DENSITY_VALUES, appearanceStore[KEY_DENSITY], Density.COMFORTABLE)
        } else {
            densityFromLegacy((global[SettingsSchema.comfortableLayout] as? Boolean))
        },
        textScale = fromKey(TEXT_SCALE_VALUES, appearanceStore[KEY_TEXT_SCALE], TextScale.DEFAULT),
        animationSpeed = fromKey(ANIMATION_SPEED_VALUES, appearanceStore[KEY_ANIMATION_SPEED], AnimationSpeed.DEFAULT),
        bubbleStyle = fromKey(BUBBLE_STYLE_VALUES, appearanceStore[KEY_BUBBLE_STYLE], BubbleStyle.DEFAULT),
        chatBackground = fromKey(CHAT_BACKGROUND_VALUES, appearanceStore[KEY_CHAT_BACKGROUND], ChatBackground.DEFAULT),
    )

    private fun writeAppearance(appearance: AppearanceProfile) {
        appearanceStore[KEY_SHAPE_SCALE] = appearance.shapeScale.key
        appearanceStore[KEY_DENSITY] = appearance.density.key
        appearanceStore[KEY_TEXT_SCALE] = appearance.textScale.key
        appearanceStore[KEY_ANIMATION_SPEED] = appearance.animationSpeed.key
        appearanceStore[KEY_BUBBLE_STYLE] = appearance.bubbleStyle.key
        appearanceStore[KEY_CHAT_BACKGROUND] = appearance.chatBackground.key
        // Mirror the Android store: the legacy boolean stays derived.
        global[SettingsSchema.comfortableLayout] = appearance.density == Density.COMFORTABLE
    }
}

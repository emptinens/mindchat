package com.mindchat.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Non-sensitive appearance and local-behaviour choices selected by the user. */
data class MindChatCustomization(
    val dynamicColor: Boolean = true,
    val comfortableLayout: Boolean = true,
    val appLockEnabled: Boolean = false,
)

/**
 * Per-account, device-local profile customization (0.1.5).
 *
 * Nothing here is a secret or server state: [avatarUri] is a local content URI
 * or copied file path, [statusMessage] is local-only, and [accentKey] selects
 * one of the fixed Material 3 Expressive accent options (null = system).
 *
 * Deliberately **not** folded into [SettingsSchema] in 0.1.7 (see the settings
 * plan, decision D7): it is a 0.1.5 surface already used by the drawer, theme
 * seeding and snapshot mapping, and keeping it stable avoids a risky merge.
 */
data class AccountProfile(
    val avatarUri: String? = null,
    val statusMessage: String? = null,
    val accentKey: String? = null,
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

    /** All stored non-default per-account settings for [accountId]. */
    fun readAccountSettings(accountId: Long): Map<SettingKey<*>, Any>

    /** Writes one per-account setting under the `account_<id>_<key>` namespace. */
    fun writeAccountSetting(accountId: Long, key: SettingKey<*>, value: Any)

    /** Removes every per-account setting for [accountId]; a no-op when none. */
    fun removeAccountSettings(accountId: Long)

    /** Schema version written to disk for future migrations (starts at 1). */
    fun settingsSchemaVersion(): Int

    companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA_VERSION = "settings_schema_version"

        /** Namespace prefix for per-account settings, mirroring `profile_`. */
        fun accountKey(accountId: Long, key: SettingKey<*>): String =
            if (key.scope == SettingScope.PER_ACCOUNT) {
                "account_${accountId}_${key.storageKey}"
            } else {
                key.storageKey
            }
    }
}

/** Android-backed storage for non-secret UI preferences only. */
class SharedPreferencesMindChatPreferences(context: Context) : MindChatPreferences {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    // --- 0.1.5/0.1.6 aggregate surface ------------------------------------

    override fun readCustomization(): MindChatCustomization = MindChatCustomization(
        dynamicColor = read(SettingsSchema.dynamicColor),
        comfortableLayout = read(SettingsSchema.comfortableLayout),
        appLockEnabled = read(SettingsSchema.appLockEnabled),
    )

    override fun writeCustomization(customization: MindChatCustomization) {
        write(SettingsSchema.dynamicColor, customization.dynamicColor)
        write(SettingsSchema.comfortableLayout, customization.comfortableLayout)
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
                )
            }
            .filterValues { it.avatarUri != null || it.statusMessage != null || it.accentKey != null }
    }

    override fun writeProfile(accountId: Long, profile: AccountProfile) {
        preferences.edit {
            val ids = (preferences.getStringSet(KEY_PROFILE_ACCOUNT_IDS, emptySet()).orEmpty()).toMutableSet()
            ids.add(accountId.toString())
            putStringSet(KEY_PROFILE_ACCOUNT_IDS, ids)
            writeOrRemove(profileKey(accountId, KEY_AVATAR), profile.avatarUri)
            writeOrRemove(profileKey(accountId, KEY_STATUS), profile.statusMessage)
            writeOrRemove(profileKey(accountId, KEY_ACCENT), profile.accentKey)
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
        is StringKey -> preferences.getString(key.storageKey, key.default)
        is EnumKey<*> -> preferences.getString(key.storageKey, null)?.let { key.decode(it) }
    }

    override fun <T> write(key: SettingKey<T>, value: T) {
        preferences.edit {
            when (key) {
                is BooleanKey -> putBoolean(key.storageKey, value as Boolean)
                is StringKey -> putString(key.storageKey, value as String)
                is EnumKey<*> -> putString(key.storageKey, key.encode(value))
            }
            putInt(MindChatPreferences.KEY_SCHEMA_VERSION, MindChatPreferences.SCHEMA_VERSION)
        }
    }

    override fun readAll(): Map<SettingKey<*>, Any> = SettingsSchema.all
        .filter { it.scope == SettingScope.GLOBAL && preferences.contains(it.storageKey) }
        .mapNotNull { key ->
            val value = readRaw(key) ?: return@mapNotNull null
            if (value == key.default) null else key to value
        }
        .toMap()

    override fun readAccountSettings(accountId: Long): Map<SettingKey<*>, Any> {
        val ids = preferences.getStringSet(KEY_ACCOUNT_SETTINGS_ACCOUNT_IDS, emptySet()).orEmpty()
        if (accountId.toString() !in ids) return emptyMap()
        return SettingsSchema.all
            .filter {
                it.scope == SettingScope.PER_ACCOUNT &&
                    preferences.contains(MindChatPreferences.accountKey(accountId, it))
            }
            .mapNotNull { key ->
                val value = readAccountValue(accountId, key) ?: return@mapNotNull null
                if (value == key.default) null else key to value
            }
            .toMap()
    }

    override fun writeAccountSetting(accountId: Long, key: SettingKey<*>, value: Any) {
        preferences.edit {
            val ids = (preferences.getStringSet(KEY_ACCOUNT_SETTINGS_ACCOUNT_IDS, emptySet()).orEmpty()).toMutableSet()
            ids.add(accountId.toString())
            putStringSet(KEY_ACCOUNT_SETTINGS_ACCOUNT_IDS, ids)
            putAccountValue(accountId, key, value)
            putInt(MindChatPreferences.KEY_SCHEMA_VERSION, MindChatPreferences.SCHEMA_VERSION)
        }
    }

    override fun removeAccountSettings(accountId: Long) {
        preferences.edit {
            val ids = (preferences.getStringSet(KEY_ACCOUNT_SETTINGS_ACCOUNT_IDS, emptySet()).orEmpty()).toMutableSet()
            ids.remove(accountId.toString())
            putStringSet(KEY_ACCOUNT_SETTINGS_ACCOUNT_IDS, ids)
            SettingsSchema.all
                .filter { it.scope == SettingScope.PER_ACCOUNT }
                .forEach { remove(MindChatPreferences.accountKey(accountId, it)) }
        }
    }

    override fun settingsSchemaVersion(): Int =
        preferences.getInt(MindChatPreferences.KEY_SCHEMA_VERSION, 1)

    private fun readAccountValue(accountId: Long, key: SettingKey<*>): Any? = when (key) {
        is BooleanKey -> preferences.getBoolean(MindChatPreferences.accountKey(accountId, key), key.default)
        is StringKey -> preferences.getString(MindChatPreferences.accountKey(accountId, key), key.default)
        is EnumKey<*> -> preferences.getString(MindChatPreferences.accountKey(accountId, key), null)?.let { key.decode(it) }
    }

    private fun android.content.SharedPreferences.Editor.putAccountValue(accountId: Long, key: SettingKey<*>, value: Any) {
        val storageKey = MindChatPreferences.accountKey(accountId, key)
        when (key) {
            is BooleanKey -> putBoolean(storageKey, value as Boolean)
            is StringKey -> putString(storageKey, value as String)
            is EnumKey<*> -> putString(storageKey, key.encode(value))
        }
    }

    private fun android.content.SharedPreferences.Editor.writeOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private companion object {
        const val PREFERENCES_FILE = "mindchat_customization"
        const val KEY_PROFILE_ACCOUNT_IDS = "profile_account_ids"
        const val KEY_ACCOUNT_SETTINGS_ACCOUNT_IDS = "account_settings_account_ids"
        const val KEY_AVATAR = "avatar"
        const val KEY_STATUS = "status"
        const val KEY_ACCENT = "accent"

        fun profileKey(accountId: Long, field: String) = "profile_${accountId}_$field"
    }
}

/** Preview and JVM-test implementation with the same contract as Android storage. */
class InMemoryMindChatPreferences(
    initial: MindChatCustomization = MindChatCustomization(),
    private val profiles: MutableMap<Long, AccountProfile> = mutableMapOf(),
) : MindChatPreferences {
    private val global: MutableMap<SettingKey<*>, Any> = mutableMapOf()
    private val perAccount: MutableMap<Long, MutableMap<SettingKey<*>, Any>> = mutableMapOf()

    init {
        writeCustomization(initial)
    }

    override fun readCustomization(): MindChatCustomization = MindChatCustomization(
        dynamicColor = read(SettingsSchema.dynamicColor),
        comfortableLayout = read(SettingsSchema.comfortableLayout),
        appLockEnabled = read(SettingsSchema.appLockEnabled),
    )

    override fun writeCustomization(customization: MindChatCustomization) {
        write(SettingsSchema.dynamicColor, customization.dynamicColor)
        write(SettingsSchema.comfortableLayout, customization.comfortableLayout)
        write(SettingsSchema.appLockEnabled, customization.appLockEnabled)
    }

    override fun readProfiles(): Map<Long, AccountProfile> = profiles.toMap()

    override fun writeProfile(accountId: Long, profile: AccountProfile) {
        if (profile.avatarUri == null && profile.statusMessage == null && profile.accentKey == null) {
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

    override fun readAccountSettings(accountId: Long): Map<SettingKey<*>, Any> =
        perAccount[accountId]
            ?.filterKeys { it.scope == SettingScope.PER_ACCOUNT }
            .orEmpty()

    override fun writeAccountSetting(accountId: Long, key: SettingKey<*>, value: Any) {
        if (key.scope == SettingScope.PER_ACCOUNT) {
            perAccount.getOrPut(accountId) { mutableMapOf() }[key] = value
        } else {
            // Mirrors the Android store: a GLOBAL key written through the
            // account-scoped path lands on the flat global key.
            global[key] = value
        }
    }

    override fun removeAccountSettings(accountId: Long) {
        perAccount.remove(accountId)
    }

    override fun settingsSchemaVersion(): Int = MindChatPreferences.SCHEMA_VERSION
}

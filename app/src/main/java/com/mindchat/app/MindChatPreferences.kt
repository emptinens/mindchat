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
 */
data class AccountProfile(
    val avatarUri: String? = null,
    val statusMessage: String? = null,
    val accentKey: String? = null,
)

/** Small persistence boundary that keeps UI customization independent of the core binding. */
interface MindChatPreferences {
    fun readCustomization(): MindChatCustomization

    fun writeCustomization(customization: MindChatCustomization)

    /** Reads all stored per-account profiles, keyed by account id. */
    fun readProfiles(): Map<Long, AccountProfile>

    /** Stores one per-account profile. */
    fun writeProfile(accountId: Long, profile: AccountProfile)
}

/** Android-backed storage for non-secret UI preferences only. */
class SharedPreferencesMindChatPreferences(context: Context) : MindChatPreferences {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    override fun readCustomization(): MindChatCustomization = MindChatCustomization(
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, true),
        comfortableLayout = preferences.getBoolean(KEY_COMFORTABLE_LAYOUT, true),
        appLockEnabled = preferences.getBoolean(KEY_APP_LOCK_ENABLED, false),
    )

    override fun writeCustomization(customization: MindChatCustomization) {
        preferences.edit {
            putBoolean(KEY_DYNAMIC_COLOR, customization.dynamicColor)
            putBoolean(KEY_COMFORTABLE_LAYOUT, customization.comfortableLayout)
            putBoolean(KEY_APP_LOCK_ENABLED, customization.appLockEnabled)
        }
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

    private fun android.content.SharedPreferences.Editor.writeOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private companion object {
        const val PREFERENCES_FILE = "mindchat_customization"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_COMFORTABLE_LAYOUT = "comfortable_layout"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        const val KEY_PROFILE_ACCOUNT_IDS = "profile_account_ids"
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
    private var customization = initial

    override fun readCustomization(): MindChatCustomization = customization

    override fun writeCustomization(customization: MindChatCustomization) {
        this.customization = customization
    }

    override fun readProfiles(): Map<Long, AccountProfile> = profiles.toMap()

    override fun writeProfile(accountId: Long, profile: AccountProfile) {
        if (profile.avatarUri == null && profile.statusMessage == null && profile.accentKey == null) {
            profiles.remove(accountId)
        } else {
            profiles[accountId] = profile
        }
    }
}

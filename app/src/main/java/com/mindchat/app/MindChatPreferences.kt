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

/** Small persistence boundary that keeps UI customization independent of the core binding. */
interface MindChatPreferences {
    fun readCustomization(): MindChatCustomization

    fun writeCustomization(customization: MindChatCustomization)
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

    private companion object {
        const val PREFERENCES_FILE = "mindchat_customization"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_COMFORTABLE_LAYOUT = "comfortable_layout"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    }
}

/** Preview and JVM-test implementation with the same contract as Android storage. */
class InMemoryMindChatPreferences(
    initial: MindChatCustomization = MindChatCustomization(),
) : MindChatPreferences {
    private var customization = initial

    override fun readCustomization(): MindChatCustomization = customization

    override fun writeCustomization(customization: MindChatCustomization) {
        this.customization = customization
    }
}

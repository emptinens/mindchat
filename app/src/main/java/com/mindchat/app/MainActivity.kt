package com.mindchat.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity(), AppLockHost {
    private val appLockViewModel: AppLockViewModel by viewModels()
    private lateinit var appLockAuthenticator: AndroidAppLockAuthenticator

    override val appLockState: AppLockUiState
        get() = appLockViewModel.state

    override val isAuthenticationAvailable: Boolean
        get() = appLockAuthenticator.isAuthenticationAvailable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLockAuthenticator = AndroidAppLockAuthenticator(this)

        val preferences = SharedPreferencesMindChatPreferences(applicationContext)
        val customization = preferences.readCustomization()
        val appLockEnabled = customization.appLockEnabled && isAuthenticationAvailable
        if (customization.appLockEnabled && !appLockEnabled) {
            preferences.writeCustomization(customization.copy(appLockEnabled = false))
        }
        setAppLockEnabled(appLockEnabled)

        enableEdgeToEdge()
        setContent {
            MindChatApp(appLockHost = this)
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            appLockViewModel.onAppBackgrounded()
        }
        super.onStop()
    }

    override fun setAppLockEnabled(enabled: Boolean) {
        if (enabled && !isAuthenticationAvailable) return
        appLockViewModel.setEnabled(enabled)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun requestAppUnlock() {
        if (!appLockViewModel.beginAuthentication()) return
        appLockAuthenticator.authenticate(
            onSucceeded = appLockViewModel::onAuthenticationSucceeded,
            onCancelled = appLockViewModel::onAuthenticationCancelled,
        )
    }
}

package com.mindchat.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class AppLockStatus {
    UNLOCKED,
    LOCKED,
    AUTHENTICATING,
}

data class AppLockUiState(
    val isEnabled: Boolean = false,
    val status: AppLockStatus = AppLockStatus.UNLOCKED,
    val automaticPromptNonce: Long = 0,
) {
    val blocksContent: Boolean
        get() = isEnabled && status != AppLockStatus.UNLOCKED

    val canRequestAuthentication: Boolean
        get() = isEnabled && status == AppLockStatus.LOCKED
}

/**
 * Pure state machine for an optional local screen lock. It deliberately stores
 * no credential material: Android's system biometric/device-credential prompt
 * owns authentication and only reports a success or cancellation here.
 */
class AppLockStateMachine {
    var state by mutableStateOf(AppLockUiState())
        private set

    fun setEnabled(enabled: Boolean) {
        when {
            !enabled -> {
                state = AppLockUiState(
                    isEnabled = false,
                    status = AppLockStatus.UNLOCKED,
                    automaticPromptNonce = state.automaticPromptNonce,
                )
            }

            !state.isEnabled -> lockWithAutomaticPrompt()
        }
    }

    fun onAppBackgrounded() {
        if (state.isEnabled && state.status != AppLockStatus.AUTHENTICATING) {
            lockWithAutomaticPrompt()
        }
    }

    fun beginAuthentication(): Boolean {
        if (!state.canRequestAuthentication) return false
        state = state.copy(status = AppLockStatus.AUTHENTICATING)
        return true
    }

    fun onAuthenticationSucceeded() {
        if (state.isEnabled && state.status == AppLockStatus.AUTHENTICATING) {
            state = state.copy(status = AppLockStatus.UNLOCKED)
        }
    }

    fun onAuthenticationCancelled() {
        if (state.isEnabled && state.status == AppLockStatus.AUTHENTICATING) {
            state = state.copy(status = AppLockStatus.LOCKED)
        }
    }

    private fun lockWithAutomaticPrompt() {
        state = AppLockUiState(
            isEnabled = true,
            status = AppLockStatus.LOCKED,
            automaticPromptNonce = state.automaticPromptNonce + 1,
        )
    }
}

/** Activity-scoped owner retained over configuration changes. */
class AppLockViewModel : ViewModel() {
    private val stateMachine = AppLockStateMachine()

    val state: AppLockUiState
        get() = stateMachine.state

    fun setEnabled(enabled: Boolean) = stateMachine.setEnabled(enabled)

    fun onAppBackgrounded() = stateMachine.onAppBackgrounded()

    fun beginAuthentication(): Boolean = stateMachine.beginAuthentication()

    fun onAuthenticationSucceeded() = stateMachine.onAuthenticationSucceeded()

    fun onAuthenticationCancelled() = stateMachine.onAuthenticationCancelled()
}

interface AppLockHost {
    val appLockState: AppLockUiState

    val isAuthenticationAvailable: Boolean

    fun setAppLockEnabled(enabled: Boolean)

    fun requestAppUnlock()
}

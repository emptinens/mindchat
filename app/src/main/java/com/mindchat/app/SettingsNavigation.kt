package com.mindchat.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * Dependency-free sub-navigation for the Settings destination (0.1.7).
 *
 * The stack is held in a snapshot-backed [mutableStateOf] list so the shell can
 * keep it in `rememberSaveable` (via [SettingsNavSaver]) and so pure JVM tests
 * can drive the state machine without a device. Future routes (0.1.9:
 * NotificationDetail, Encryption, StorageDetail) are additive `data object`s.
 */
sealed interface SettingsRoute {
    data object Root : SettingsRoute

    data class Category(val category: SettingCategory) : SettingsRoute

    data object Accounts : SettingsRoute

    data class AccountSettings(val accountId: Long) : SettingsRoute
}

/**
 * Pure back-stack state machine for settings. [navigate] pushes a route and
 * collapses Root duplicates; [back] pops one level and reports false when
 * already at Root (the shell then exits the settings destination).
 */
class SettingsNavState(initial: SettingsRoute = SettingsRoute.Root) {
    var backStack: List<SettingsRoute> by mutableStateOf(listOf(initial))
        private set

    /** Pushes [route]; navigating to Root collapses the whole stack to Root. */
    fun navigate(route: SettingsRoute) {
        if (route == SettingsRoute.Root) {
            if (backStack.size == 1 && backStack.first() == SettingsRoute.Root) return
            backStack = listOf(SettingsRoute.Root)
        } else {
            if (backStack.lastOrNull() == route) return
            backStack = backStack + route
        }
    }

    /** Pops one level; returns false when already at Root. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack = backStack.dropLast(1)
        return true
    }

    /** Returns to the root screen. */
    fun reset() {
        backStack = listOf(SettingsRoute.Root)
    }

    companion object {
        /** Restores a saved stack (see [SettingsNavSaver]). */
        fun fromStack(stack: List<SettingsRoute>): SettingsNavState =
            SettingsNavState().apply {
                backStack = stack.ifEmpty { listOf(SettingsRoute.Root) }
            }
    }
}

/**
 * Bundle-safe string form of a route for `rememberSaveable`; the shell's saver
 * stores the whole stack as a list of these tokens.
 */
internal fun SettingsRoute.toToken(): String = when (this) {
    SettingsRoute.Root -> "root"
    is SettingsRoute.Category -> "category:${category.name}"
    SettingsRoute.Accounts -> "accounts"
    is SettingsRoute.AccountSettings -> "account:$accountId"
}

/** Inverse of [toToken]; returns null for unknown/garbage tokens. */
internal fun settingsRouteFromToken(token: String): SettingsRoute? {
    val kind = token.substringBefore(':')
    val value = token.substringAfter(':', missingDelimiterValue = "")
    return when (kind) {
        "root" -> SettingsRoute.Root
        "accounts" -> SettingsRoute.Accounts
        "category" -> value
            .takeIf { it.isNotEmpty() }
            ?.let { name -> SettingCategory.entries.firstOrNull { it.name == name } }
            ?.let { SettingsRoute.Category(it) }
        "account" -> value.toLongOrNull()?.let(SettingsRoute::AccountSettings)
        else -> null
    }
}

/** `rememberSaveable` saver: the settings back stack survives rotation. */
internal val SettingsNavSaver: Saver<SettingsNavState, ArrayList<String>> = Saver(
    save = { state -> ArrayList(state.backStack.map { it.toToken() }) },
    restore = { tokens ->
        SettingsNavState.fromStack(tokens.mapNotNull { settingsRouteFromToken(it) })
    },
)

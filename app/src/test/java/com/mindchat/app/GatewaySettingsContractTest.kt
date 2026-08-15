package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the 0.1.7 keyed settings flow driven through the public
 * [MindChatGateway] contract (T4/T5/T12), mirroring
 * [GatewayCustomizationContractTest] 1:1: each mutation updates
 * [MindChatUiState.settings] / [MindChatUiState.accountSettings], persists to
 * [MindChatPreferences], survives a gateway restart over the same preferences,
 * and leaves unrelated keys untouched. Account deletion cleans up its
 * account-scoped settings.
 */
class GatewaySettingsContractTest {

    /** Synthetic PER_ACCOUNT key: no such key exists in 0.1.7, so the
     *  account-scoped contract is exercised with a test-only key, exactly as
     *  0.1.8 will add real ones. */
    private val testPerAccountKey = BooleanKey(
        storageKey = "presence_visibility_test",
        default = false,
        category = SettingCategory.PRIVACY_SECURITY,
        scope = SettingScope.PER_ACCOUNT,
        availability = SettingAvailability.IMPLEMENTED,
        labelRes = com.mindchat.app.R.string.encryption,
    )

    private fun preferences() = InMemoryMindChatPreferences()

    // --- setSetting ----------------------------------------------------------

    @Test
    fun setSettingUpdatesStateAndPersists() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        assertTrue(g.state.settings.dynamicColor)

        g.setSetting(SettingsSchema.dynamicColor, false)

        assertFalse(g.state.settings.dynamicColor)
        assertFalse(prefs.read(SettingsSchema.dynamicColor))
        assertEquals(false, prefs.readAll()[SettingsSchema.dynamicColor])
    }

    @Test
    fun setSettingLeavesUnrelatedKeysUntouched() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)

        g.setSetting(SettingsSchema.dynamicColor, false)
        g.setSetting(SettingsSchema.appLockEnabled, true)

        val stored = prefs.readAll()
        assertFalse(stored[SettingsSchema.dynamicColor] as Boolean)
        assertTrue(stored[SettingsSchema.appLockEnabled] as Boolean)
        assertFalse("comfortable layout must be untouched", stored.containsKey(SettingsSchema.comfortableLayout))
        assertEquals(2, stored.size)
    }

    @Test
    fun setSettingSurvivesGatewayRestart() {
        val prefs = preferences()
        val first = PreviewMindChatGateway(prefs)
        first.setSetting(SettingsSchema.dynamicColor, false)
        first.setSetting(SettingsSchema.appLockEnabled, true)

        val second = PreviewMindChatGateway(prefs)

        assertFalse(second.state.settings.dynamicColor)
        assertTrue(second.state.settings.appLockEnabled)
        assertTrue("untouched keys keep their default", second.state.settings.comfortableLayout)
    }

    @Test
    fun gatewayInitializesFromNonDefaultPreferences() {
        val prefs = preferences()
        prefs.write(SettingsSchema.dynamicColor, false)
        prefs.write(SettingsSchema.comfortableLayout, false)
        prefs.write(SettingsSchema.appLockEnabled, true)

        val g = PreviewMindChatGateway(prefs)

        assertFalse(g.state.settings.dynamicColor)
        assertFalse(g.state.settings.comfortableLayout)
        assertTrue(g.state.settings.appLockEnabled)
        assertFalse("convenience accessor agrees", g.state.dynamicColor)
    }

    // --- legacy toggles stay thin wrappers ------------------------------------

    @Test
    fun legacyTogglesRouteThroughTheSameStore() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)

        g.toggleDynamicColor()
        g.toggleComfortableLayout()
        g.toggleAppLock()

        val stored = prefs.readAll()
        assertFalse(stored[SettingsSchema.dynamicColor] as Boolean)
        assertFalse(stored[SettingsSchema.comfortableLayout] as Boolean)
        assertTrue(stored[SettingsSchema.appLockEnabled] as Boolean)
        assertFalse(g.state.dynamicColor)
        assertFalse(g.state.comfortableLayout)
        assertTrue(g.state.appLockEnabled)
    }

    // --- setAccountSetting ----------------------------------------------------

    @Test
    fun setAccountSettingUpdatesStateAndPersists() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        val accountId = g.state.activeAccountId

        g.setAccountSetting(accountId, testPerAccountKey, true)

        assertEquals(true, g.state.accountSettings[accountId]?.get(testPerAccountKey))
        assertEquals(true, prefs.readAccountSettings(accountId)[testPerAccountKey])
    }

    @Test
    fun accountSettingsDoNotLeakAcrossAccounts() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        val firstId = g.state.activeAccountId
        g.registerAccount("second@example.org", "example.org", "Second", "pw")
        val secondId = g.state.activeAccountId

        g.setAccountSetting(firstId, testPerAccountKey, true)

        assertTrue(prefs.readAccountSettings(firstId).containsKey(testPerAccountKey))
        assertTrue(prefs.readAccountSettings(secondId).isEmpty())
        assertEquals(null, g.state.accountSettings[secondId]?.get(testPerAccountKey))
    }

    @Test
    fun setAccountSettingSurvivesGatewayRestart() {
        val prefs = preferences()
        val first = PreviewMindChatGateway(prefs)
        val accountId = first.state.activeAccountId
        first.setAccountSetting(accountId, testPerAccountKey, true)

        val second = PreviewMindChatGateway(prefs)

        assertEquals(true, second.state.accountSettings[accountId]?.get(testPerAccountKey))
    }

    @Test
    fun deleteAccountClearsItsAccountSettings() {
        val prefs = preferences()
        val g = PreviewMindChatGateway(prefs)
        val accountId = g.state.activeAccountId
        g.setAccountSetting(accountId, testPerAccountKey, true)
        assertTrue(prefs.readAccountSettings(accountId).isNotEmpty())

        g.deleteAccount(accountId)

        assertTrue(prefs.readAccountSettings(accountId).isEmpty())
        assertTrue(g.state.accountSettings.isEmpty())
    }
}

package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure settings decision logic in [GatewayInput] (T5/T8): catalog row
 * derivation (categories, PENDING_CORE honesty, app lock capability), search
 * matching, scope resolution, sanitizing, and snapshot diffing. Everything
 * here is JVM-runnable and shared by both gateway implementations.
 */
class SettingsCatalogTest {

    private fun toggles(rows: List<SettingRowSpec>) = rows.filterIsInstance<SettingToggleRowSpec>()

    private fun actions(rows: List<SettingRowSpec>) = rows.filterIsInstance<SettingActionRowSpec>()

    // --- catalogRows ---------------------------------------------------------

    @Test
    fun appearanceRowsAreCatalogDriven() {
        val rows = catalogRows(SettingCategory.APPEARANCE, SettingsSnapshot(), activeAccountId = 1L)
        assertEquals(listOf(SettingsSchema.dynamicColor, SettingsSchema.comfortableLayout), toggles(rows).map { it.key })
        val accent = actions(rows).single()
        assertEquals(SettingRowAction.OPEN_ACCENT_PROFILE, accent.action)
        assertEquals(R.string.accent_color, accent.labelRes)
        assertEquals(R.string.accent_per_account, accent.supportingRes)
    }

    @Test
    fun privacyRowsMarkPendingCoreAsDisabled() {
        val rows = catalogRows(SettingCategory.PRIVACY_SECURITY, SettingsSnapshot(), activeAccountId = 1L)
        val toggles = toggles(rows)
        assertEquals(3, toggles.size)

        val appLock = toggles.first { it.key == SettingsSchema.appLockEnabled }
        assertTrue("app lock is implemented", appLock.enabled)
        assertFalse(appLock.notImplemented)
        assertFalse(appLock.checked)

        val search = toggles.first { it.key == SettingsSchema.messageSearch }
        assertFalse(search.enabled)
        assertTrue(search.notImplemented)
        assertEquals(R.string.message_search_summary, search.supportingRes)

        val encryption = toggles.first { it.key == SettingsSchema.encryption }
        assertFalse(encryption.enabled)
        assertTrue(encryption.notImplemented)
    }

    @Test
    fun notificationsRowsArePendingCore() {
        val rows = catalogRows(SettingCategory.NOTIFICATIONS, SettingsSnapshot(), activeAccountId = 1L)
        assertEquals(
            listOf(SettingsSchema.messageNotifications, SettingsSchema.groupNotifications),
            toggles(rows).map { it.key },
        )
        assertTrue(toggles(rows).all { it.notImplemented && !it.enabled })
    }

    @Test
    fun nonCatalogCategoriesHaveNoRows() {
        assertEquals(0, catalogRows(SettingCategory.ACCOUNTS, SettingsSnapshot(), activeAccountId = 1L).size)
        assertEquals(0, catalogRows(SettingCategory.STORAGE, SettingsSnapshot(), activeAccountId = 1L).size)
        assertEquals(0, catalogRows(SettingCategory.ABOUT, SettingsSnapshot(), activeAccountId = 1L).size)
    }

    @Test
    fun accentRowIsDisabledWithoutAnActiveAccount() {
        val rows = catalogRows(SettingCategory.APPEARANCE, SettingsSnapshot(), activeAccountId = 0L)
        assertFalse(actions(rows).single().enabled)
    }

    @Test
    fun snapshotDrivesCheckedState() {
        val rows = catalogRows(
            SettingCategory.APPEARANCE,
            SettingsSnapshot(mapOf(SettingsSchema.dynamicColor to false)),
            activeAccountId = 1L,
        )
        assertFalse(toggles(rows).first { it.key == SettingsSchema.dynamicColor }.checked)
        assertTrue(toggles(rows).first { it.key == SettingsSchema.comfortableLayout }.checked)
    }

    @Test
    fun appLockStaysEnabledWhileOnEvenWithoutDeviceSupport() {
        val rows = catalogRows(
            SettingCategory.PRIVACY_SECURITY,
            SettingsSnapshot(mapOf(SettingsSchema.appLockEnabled to true)),
            activeAccountId = 1L,
            appLockAvailable = false,
        )
        val appLock = toggles(rows).first { it.key == SettingsSchema.appLockEnabled }
        assertTrue("user must be able to turn app lock off again", appLock.enabled)
        assertTrue(appLock.checked)
    }

    @Test
    fun appLockDisabledWithoutDeviceSupportAndOff() {
        val rows = catalogRows(
            SettingCategory.PRIVACY_SECURITY,
            SettingsSnapshot(),
            activeAccountId = 1L,
            appLockAvailable = false,
        )
        assertFalse(toggles(rows).first { it.key == SettingsSchema.appLockEnabled }.enabled)
    }

    @Test
    fun appLockRowShowsCapabilityAwareSupportingText() {
        val available = catalogRows(
            SettingCategory.PRIVACY_SECURITY,
            SettingsSnapshot(),
            activeAccountId = 1L,
            appLockAvailable = true,
        )
        assertEquals(
            R.string.app_lock_summary,
            toggles(available).first { it.key == SettingsSchema.appLockEnabled }.supportingRes,
        )

        val unavailable = catalogRows(
            SettingCategory.PRIVACY_SECURITY,
            SettingsSnapshot(),
            activeAccountId = 1L,
            appLockAvailable = false,
        )
        assertEquals(
            R.string.app_lock_unavailable,
            toggles(unavailable).first { it.key == SettingsSchema.appLockEnabled }.supportingRes,
        )
    }

    @Test
    fun syntheticPendingCoreKeyRendersWithoutStoreOrGatewayChanges() {
        // Acceptance criterion 10: the 0.1.8 recipe (one new key, zero rework).
        val synthetic = BooleanKey(
            storageKey = "test_presence_visibility",
            default = false,
            category = SettingCategory.PRIVACY_SECURITY,
            scope = SettingScope.GLOBAL,
            availability = SettingAvailability.PENDING_CORE,
            labelRes = R.string.encryption,
        )
        val snapshot = SettingsSnapshot(mapOf(synthetic to true))
        val rows = catalogRows(
            SettingCategory.PRIVACY_SECURITY,
            snapshot,
            activeAccountId = 1L,
            keys = SettingsSchema.all + synthetic,
        )
        val row = toggles(rows).first { it.key == synthetic }
        assertTrue(row.notImplemented)
        assertFalse(row.enabled)
    }

    // --- searchSettings ------------------------------------------------------

    private val accentSearchKey = EnumKey(
        storageKey = "test_accent_search",
        default = TestVisibility.DEFAULT,
        category = SettingCategory.APPEARANCE,
        scope = SettingScope.GLOBAL,
        availability = SettingAvailability.IMPLEMENTED,
        labelRes = R.string.accent_color,
        enumClass = TestVisibility::class.java,
        keywords = listOf(R.string.accent_default),
    )

    @Test
    fun searchMatchesLabelSubstring() {
        val hits = searchSettings("acc", listOf(accentSearchKey), resolveText = ::resolveTestText)
        assertEquals(listOf(accentSearchKey), hits)
    }

    @Test
    fun searchIsCaseInsensitive() {
        val hits = searchSettings("ACC", listOf(accentSearchKey), resolveText = ::resolveTestText)
        assertEquals(listOf(accentSearchKey), hits)
    }

    @Test
    fun searchMatchesKeywords() {
        val hits = searchSettings("default", listOf(accentSearchKey), resolveText = ::resolveTestText)
        assertEquals(listOf(accentSearchKey), hits)
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(searchSettings("   ", SettingsSchema.all, resolveText = ::resolveTestText).isEmpty())
    }

    @Test
    fun noMatchReturnsNothing() {
        assertTrue(searchSettings("zzz", SettingsSchema.all, resolveText = ::resolveTestText).isEmpty())
    }

    @Test
    fun searchIsPureOverTheResolvedText() {
        // Same key, different resolver text: the function only sees the
        // resolved strings, so it must match what the resolver says.
        val alwaysEmpty = searchSettings("acc", listOf(accentSearchKey), resolveText = { "" })
        assertTrue(alwaysEmpty.isEmpty())
    }

    private fun resolveTestText(res: Int): String = when (res) {
        R.string.accent_color -> "Accent color"
        R.string.accent_default -> "System default"
        R.string.use_dynamic_colors -> "Use system colors"
        R.string.comfortable_layout -> "Comfortable layout"
        R.string.app_lock -> "App lock"
        R.string.message_search -> "Message search"
        R.string.encryption -> "Encryption"
        R.string.message_notifications -> "Message notifications"
        R.string.group_notifications -> "Group chat notifications"
        else -> ""
    }

    private enum class TestVisibility { DEFAULT }

    // --- settingKeyFor / sanitizeSetting / settingsChanged -------------------

    private val testPerAccountKey = BooleanKey(
        storageKey = "presence_visibility_test",
        default = false,
        category = SettingCategory.PRIVACY_SECURITY,
        scope = SettingScope.PER_ACCOUNT,
        availability = SettingAvailability.IMPLEMENTED,
        labelRes = R.string.encryption,
    )

    @Test
    fun settingKeyForNamespacesPerAccountKeys() {
        assertEquals("account_42_${testPerAccountKey.storageKey}", settingKeyFor(42L, testPerAccountKey))
        assertEquals("dynamic_color", settingKeyFor(42L, SettingsSchema.dynamicColor))
    }

    @Test
    fun sanitizeSettingPassesValidValuesThrough() {
        assertEquals(false, sanitizeSetting(SettingsSchema.dynamicColor, false))
        assertEquals(true, sanitizeSetting(SettingsSchema.dynamicColor, true))
    }

    @Test
    fun sanitizeSettingCoercesGarbageToDefault() {
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, sanitizeSetting(SettingsSchema.dynamicColor as SettingKey<Any>, "garbage"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(false, sanitizeSetting(SettingsSchema.appLockEnabled as SettingKey<Any>, 42))
    }

    @Test
    fun sanitizeSettingCoercesEnumGarbage() {
        val key = EnumKey<TestVisibility>(
            storageKey = "test_enum_sanitize",
            default = TestVisibility.DEFAULT,
            category = SettingCategory.APPEARANCE,
            scope = SettingScope.GLOBAL,
            availability = SettingAvailability.IMPLEMENTED,
            labelRes = R.string.appearance,
            enumClass = TestVisibility::class.java,
        )
        assertEquals(TestVisibility.DEFAULT, sanitizeSetting(key, TestVisibility.DEFAULT))
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            TestVisibility.DEFAULT,
            sanitizeSetting(key as SettingKey<Any>, "not-an-enum"),
        )
    }

    @Test
    fun settingsChangedComparesSnapshots() {
        assertFalse(settingsChanged(SettingsSnapshot(), SettingsSnapshot()))
        assertTrue(
            settingsChanged(
                SettingsSnapshot(mapOf(SettingsSchema.dynamicColor to false)),
                SettingsSnapshot(),
            ),
        )
    }
}

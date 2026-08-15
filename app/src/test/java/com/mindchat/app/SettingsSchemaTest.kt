package com.mindchat.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the typed settings schema contract (T1/T2/T12): type-safe encode/decode
 * round trips with default fallback on garbage, unique immutable storage keys
 * (byte-identical with the 0.1.6 keys), complete key metadata, snapshot
 * defaults/overrides, and EN/RU label parity checked against the real
 * strings.xml files.
 */
class SettingsSchemaTest {

    // --- encode/decode round trips -----------------------------------------

    @Test
    fun booleanKeysRoundTrip() {
        val key = SettingsSchema.dynamicColor
        assertEquals("true", key.encode(true))
        assertEquals("false", key.encode(false))
        assertTrue(key.decode("true"))
        assertFalse(key.decode("false"))
    }

    @Test
    fun booleanDecodeIsCaseInsensitive() {
        assertEquals(true, SettingsSchema.dynamicColor.decode("TRUE"))
        assertEquals(false, SettingsSchema.dynamicColor.decode("False"))
    }

    @Test
    fun booleanDecodeFallsBackToDefaultOnGarbage() {
        // dynamicColor defaults to true; garbage must not silently become false.
        assertTrue(SettingsSchema.dynamicColor.decode("not-a-boolean"))
        // app lock defaults to false.
        assertFalse(SettingsSchema.appLockEnabled.decode("garbage"))
    }

    @Test
    fun enumKeysRoundTripAndFallBackToDefault() {
        val key = EnumKey(
            storageKey = "test_enum",
            default = TestEnum.ONE,
            category = SettingCategory.APPEARANCE,
            scope = SettingScope.GLOBAL,
            availability = SettingAvailability.IMPLEMENTED,
            labelRes = R.string.appearance,
            enumClass = TestEnum::class.java,
        )
        assertEquals("TWO", key.encode(TestEnum.TWO))
        assertEquals(TestEnum.TWO, key.decode("TWO"))
        assertEquals(TestEnum.ONE, key.decode("NOT_A_VALUE"))
    }

    @Test
    fun stringKeysRoundTrip() {
        val key = StringKey(
            storageKey = "test_string",
            default = "default",
            category = SettingCategory.ABOUT,
            scope = SettingScope.GLOBAL,
            availability = SettingAvailability.IMPLEMENTED,
            labelRes = R.string.version,
        )
        assertEquals("raw", key.encode("raw"))
        assertEquals("raw", key.decode("raw"))
    }

    // --- defaults and snapshot ----------------------------------------------

    @Test
    fun schemaDefaultsMatchConvenienceGetters() {
        val snapshot = SettingsSnapshot()
        assertTrue(snapshot.dynamicColor)
        assertTrue(snapshot.comfortableLayout)
        assertFalse(snapshot.appLockEnabled)
        assertFalse(snapshot.get(SettingsSchema.messageSearch))
        assertFalse(snapshot.get(SettingsSchema.encryption))
        assertFalse(snapshot.get(SettingsSchema.messageNotifications))
        assertFalse(snapshot.get(SettingsSchema.groupNotifications))
    }

    @Test
    fun snapshotReturnsOverridesAndDefaultsForOthers() {
        val snapshot = SettingsSnapshot(mapOf(SettingsSchema.dynamicColor to false))
        assertFalse(snapshot.get(SettingsSchema.dynamicColor))
        assertTrue(snapshot.get(SettingsSchema.comfortableLayout))
        assertEquals(mapOf(SettingsSchema.dynamicColor to false), snapshot.overrides)
    }

    @Test
    fun snapshotEqualityIsMapBacked() {
        assertEquals(SettingsSnapshot(), SettingsSnapshot())
        assertNotEquals(SettingsSnapshot(), SettingsSnapshot(mapOf(SettingsSchema.dynamicColor to false)))
        assertEquals(
            SettingsSnapshot(mapOf(SettingsSchema.dynamicColor to false)),
            SettingsSnapshot(mapOf(SettingsSchema.dynamicColor to false)),
        )
    }

    // --- key uniqueness and stability ---------------------------------------

    @Test
    fun storageKeysAreUnique() {
        val keys = SettingsSchema.all.map { it.storageKey }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun legacyStorageKeysAreStable() {
        // Byte-identical with the 0.1.6 SharedPreferences keys: zero migration.
        assertEquals("dynamic_color", SettingsSchema.dynamicColor.storageKey)
        assertEquals("comfortable_layout", SettingsSchema.comfortableLayout.storageKey)
        assertEquals("app_lock_enabled", SettingsSchema.appLockEnabled.storageKey)
    }

    @Test
    fun everyKeyHasCompleteMetadata() {
        SettingsSchema.all.forEach { key ->
            assertTrue("labelRes for ${key.storageKey} must be set", key.labelRes != 0)
            assertTrue("category for ${key.storageKey}", SettingCategory.entries.contains(key.category))
            assertTrue("scope for ${key.storageKey}", SettingScope.entries.contains(key.scope))
            assertTrue("availability for ${key.storageKey}", SettingAvailability.entries.contains(key.availability))
        }
    }

    @Test
    fun allKeysCoveredByCategories() {
        val covered = SettingsSchema.all.map { it.category }.toSet()
        assertTrue(covered.contains(SettingCategory.APPEARANCE))
        assertTrue(covered.contains(SettingCategory.PRIVACY_SECURITY))
        assertTrue(covered.contains(SettingCategory.NOTIFICATIONS))
    }

    // --- EN/RU parity (real resource files) ---------------------------------

    @Test
    fun labelsAndKeywordsExistInBothLocaleFiles() {
        val en = stringsXml("values")
        val ru = stringsXml("values-ru")
        SettingsSchema.all.forEach { key ->
            val name = resourceName(key.labelRes)
            assertTrue("EN strings.xml must define $name", en.contains("name=\"$name\""))
            assertTrue("RU strings.xml must define $name", ru.contains("name=\"$name\""))
            key.keywords.forEach { keywordRes ->
                val keywordName = resourceName(keywordRes)
                assertTrue("EN strings.xml must define $keywordName", en.contains("name=\"$keywordName\""))
                assertTrue("RU strings.xml must define $keywordName", ru.contains("name=\"$keywordName\""))
            }
        }
    }

    @Test
    fun localeFilesHaveEqualStringCounts() {
        val enCount = Regex("<string").findAll(stringsXml("values")).count()
        val ruCount = Regex("<string").findAll(stringsXml("values-ru")).count()
        assertEquals("EN and RU string counts must stay equal", enCount, ruCount)
    }

    // --- helpers -------------------------------------------------------------

    private enum class TestEnum { ONE, TWO }

    private fun stringsXml(folder: String): String {
        val candidates = listOf(
            File("src/main/res/$folder/strings.xml"),
            File("../app/src/main/res/$folder/strings.xml"),
            File("app/src/main/res/$folder/strings.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("strings.xml not found under src/main/res/$folder (cwd=${System.getProperty("user.dir")})")
        return file.readText()
    }

    /** Reverse-maps an R.string id to its resource name via the R class. */
    private fun resourceName(resId: Int): String {
        val field = R.string::class.java.fields.firstOrNull { it.getInt(null) == resId }
            ?: error("No R.string field for id $resId")
        return field.name
    }
}

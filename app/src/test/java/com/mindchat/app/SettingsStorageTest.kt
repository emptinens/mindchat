package com.mindchat.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun estimateCountsAvatarsAndStateFile() {
        val filesDir = temporaryFolder.newFolder("files")
        File(filesDir, "avatars").apply { mkdirs() }
        File(File(filesDir, "avatars"), "account_1.img").writeBytes(ByteArray(1024))
        File(filesDir, "mindchat_state.json").writeText("""{"accounts":[]}""")

        val bytes = estimateLocalDataBytes(filesDir)

        assertEquals(1024L + """{"accounts":[]}""".length.toLong(), bytes)
    }

    @Test
    fun estimateIsZeroWhenNothingIsStored() {
        val filesDir = temporaryFolder.newFolder("files")
        assertEquals(0L, estimateLocalDataBytes(filesDir))
    }

    @Test
    fun clearProfileImagesRemovesOnlyAvatarFiles() {
        val filesDir = temporaryFolder.newFolder("files")
        val avatarDir = File(filesDir, "avatars").apply { mkdirs() }
        val avatarFile = File(avatarDir, "account_1.img").apply { writeText("x") }
        val stateFile = File(filesDir, "mindchat_state.json").apply { writeText("{}") }

        val removed = clearProfileImages(filesDir)

        assertEquals(1, removed)
        assertFalse(avatarFile.exists())
        assertTrue(stateFile.exists())
    }

    @Test
    fun clearProfileImagesWithNoAvatarsReturnsZero() {
        val filesDir = temporaryFolder.newFolder("files")
        assertEquals(0, clearProfileImages(filesDir))
    }
}

package com.mindchat.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Proxy credential storage (ROADMAP 6.3).
 *
 * Proxy passwords are secrets: they never enter [MindChatUiState], the
 * [MindChatGateway] contract or [ProxyLibraryStore]. [ProxyCredentialStore]
 * keeps each password in the Android Keystore as its own AES-256-GCM key
 * (per proxy id), with only the encrypted blob (IV || ciphertext, Base64) on
 * disk in `SharedPreferences`. There is no plaintext on disk and no
 * deprecated security-crypto dependency; the platform keystore owns the key
 * material.
 *
 * [KeystoreProxyCredentialStore] cannot run in JVM unit tests (no Android
 * Keystore), so the contract is pinned against the in-memory fake
 * [InMemoryProxyCredentialStore], the same interface + fake pattern the rest
 * of the project uses.
 */
interface ProxyCredentialStore {
    /** Encrypts and persists [password] for [proxyId], replacing any previous value. */
    fun store(proxyId: String, password: String)

    /** Decrypts the stored password, or null when none (or undecryptable) is stored. */
    fun load(proxyId: String): String?

    /** Removes the stored password and the keystore key for [proxyId]. */
    fun delete(proxyId: String)
}

/**
 * Android Keystore implementation: one AES-256-GCM key per proxy id, key
 * material held by the platform keystore, ciphertext stored in
 * `SharedPreferences`. A corrupted/undecryptable blob reads back as null
 * (zero-log by design) so a lost key never crashes the settings UI.
 */
class KeystoreProxyCredentialStore(context: Context) : ProxyCredentialStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    override fun store(proxyId: String, password: String) {
        val key = getOrCreateKey(proxyId)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ciphertext
        preferences.edit { putString(prefKey(proxyId), Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    override fun load(proxyId: String): String? {
        return try {
            val blob = preferences.getString(prefKey(proxyId), null) ?: return null
            val decoded = Base64.decode(blob, Base64.NO_WRAP)
            if (decoded.size < IV_LENGTH + TAG_LENGTH_BYTES) return null
            val key = keystore().getKey(alias(proxyId), null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, decoded, 0, IV_LENGTH))
            String(cipher.doFinal(decoded, IV_LENGTH, decoded.size - IV_LENGTH), Charsets.UTF_8)
        } catch (_: Exception) {
            // Key missing, tag mismatch, or malformed blob: treat as not stored.
            null
        }
    }

    override fun delete(proxyId: String) {
        preferences.edit { remove(prefKey(proxyId)) }
        runCatching { keystore().deleteEntry(alias(proxyId)) }
    }

    private fun getOrCreateKey(proxyId: String): SecretKey {
        val store = keystore()
        store.getKey(alias(proxyId), null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias(proxyId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keystore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun alias(proxyId: String): String = "$ALIAS_PREFIX$proxyId"

    private fun prefKey(proxyId: String): String = "$PREF_PREFIX$proxyId"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
        const val PREFERENCES_FILE = "mindchat_proxy_credentials"
        const val ALIAS_PREFIX = "mindchat_proxy_"
        const val PREF_PREFIX = "proxy_cred_"
    }
}

/**
 * Preview and JVM-test fake with the same contract: plain in-memory map,
 * deliberately never persisted to disk. Used by [PreviewMindChatGateway] and
 * the credential-store contract tests.
 */
class InMemoryProxyCredentialStore : ProxyCredentialStore {
    private val values = mutableMapOf<String, String>()

    override fun store(proxyId: String, password: String) {
        values[proxyId] = password
    }

    override fun load(proxyId: String): String? = values[proxyId]

    override fun delete(proxyId: String) {
        values.remove(proxyId)
    }
}

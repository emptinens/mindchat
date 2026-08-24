package com.mindchat.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * State-file encryption key plumbing (0.1.9 storage encryption at rest).
 *
 * The Rust core encrypts the local state file with a caller-supplied 32-byte
 * AES-256-GCM key (`saveStateSecured` / `loadStateSecured`). This file owns
 * the Android side of that key: [AndroidStateKeyProvider] generates the data
 * key once and stores only an AndroidKeyStore-wrapped copy (envelope scheme,
 * no new dependencies); the platform keystore never exports the wrapping
 * key.
 *
 * Honest secrecy stance: while a save/load call runs, the unwrapped data key
 * lives in the Java heap and crosses the FFI boundary as plain bytes — the
 * same best-effort posture the core documents for account passwords. What
 * this buys is that nothing readable lands on disk: the state file is
 * ciphertext and the persisted key material is Keystore-wrapped.
 */

/** Pure JVM codec for the wrapped-data-key blob stored in SharedPreferences. */
internal object StateKeyCodec {
    /**
     * Encodes wrapped key bytes as unpadded Base64. Uses `java.util.Base64`
     * (API 26+, equals minSdk) instead of `android.util.Base64` so the codec
     * stays testable in JVM unit tests.
     */
    fun encode(wrapped: ByteArray): String = java.util.Base64.getEncoder().encodeToString(wrapped)

    /** Decodes a stored blob, or null when absent/garbage (zero-log stance).
     * An empty string decodes to an empty array so encode/decode stays a
     * total round trip; real blobs always carry IV + ciphertext. */
    fun decodeOrNull(raw: String?): ByteArray? {
        if (raw == null) return null
        return try {
            java.util.Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Supplies the 32-byte state-encryption key handed to the native core. */
interface StateEncryptionKeyProvider {
    /** Returns the data key; implementations may generate it on first use. */
    fun stateKey(): ByteArray
}

/**
 * Envelope implementation: a non-exportable AES-256-GCM wrap key lives in
 * the Android Keystore; the random 32-byte data key is stored only as a
 * wrapped blob under [PREF_WRAPPED_KEY]. If the blob is lost or undecryptable
 * (keystore reset, cleared prefs), a fresh key is generated: the old state
 * file then fails authenticated decryption at load and flows into the
 * existing quarantine path, which starts clean — deliberate, documented data
 * loss over a crash loop.
 */
class AndroidStateKeyProvider(context: Context) :
    StateEncryptionKeyProvider {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    override fun stateKey(): ByteArray {
        val stored = StateKeyCodec.decodeOrNull(preferences.getString(PREF_WRAPPED_KEY, null))
        if (stored != null) {
            unwrapOrNull(stored)?.let { return it }
        }
        return generateAndWrap()
    }

    private fun generateAndWrap(): ByteArray {
        val dataKey = ByteArray(DATA_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        preferences.edit { putString(PREF_WRAPPED_KEY, StateKeyCodec.encode(wrap(dataKey))) }
        return dataKey
    }

    private fun wrap(dataKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val ciphertext = cipher.doFinal(dataKey)
        return cipher.iv + ciphertext
    }

    private fun unwrapOrNull(wrapped: ByteArray): ByteArray? = try {
        if (wrapped.size < IV_LENGTH + TAG_LENGTH_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateWrapKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, wrapped, 0, IV_LENGTH),
            )
            cipher.doFinal(wrapped, IV_LENGTH, wrapped.size - IV_LENGTH)
        }
    } catch (_: Exception) {
        // Wrap key missing (keystore reset) or tag mismatch: treated as lost.
        null
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val store = keystore()
        store.getKey(WRAP_KEY_ALIAS, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val DATA_KEY_BYTES = 32
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
        const val PREFERENCES_FILE = "mindchat_state_crypto"
        const val WRAP_KEY_ALIAS = "mindchat_state_wrap_v1"
        const val PREF_WRAPPED_KEY = "state_wrapped_data_key"
    }
}

/** Deterministic JVM-test provider; never used by production construction sites. */
internal class InMemoryStateKeyProvider(seed: Byte = 7) : StateEncryptionKeyProvider {
    private val key = ByteArray(32) { seed }

    override fun stateKey(): ByteArray = key.copyOf()
}

package app.opah.tv.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class PersistedCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

interface SavedCredentialStore {
    fun readPassword(): String?
    fun writePassword(password: String)
    fun clearPassword()
    fun clearAll()
}

/** Stores only encrypted secrets; the Android Keystore key is non-exportable. */
class SecureSessionStore(context: Context) : SavedCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): PersistedCookie? = readEncrypted(COOKIE_SLOT) { cleartext ->
        json.decodeFromString<PersistedCookie>(cleartext.decodeToString())
    }

    @Synchronized
    fun write(cookie: PersistedCookie) {
        writeEncrypted(
            slot = COOKIE_SLOT,
            cleartext = json.encodeToString(PersistedCookie.serializer(), cookie).encodeToByteArray(),
        )
        preferences.edit(commit = true) {
            remove(LEGACY_KEY_IV)
            remove(LEGACY_KEY_PAYLOAD)
        }
    }

    @Synchronized
    override fun readPassword(): String? = readEncrypted(PASSWORD_SLOT) { cleartext ->
        cleartext.decodeToString().takeIf(String::isNotBlank)
    }

    @Synchronized
    override fun writePassword(password: String) {
        require(password.isNotBlank()) { "Password is required." }
        writeEncrypted(PASSWORD_SLOT, password.encodeToByteArray())
    }

    /** Clears the revocable session while retaining the encrypted auto-sign-in credential. */
    @Synchronized
    fun clear() {
        clearSlot(COOKIE_SLOT)
        preferences.edit(commit = true) {
            remove(LEGACY_KEY_IV)
            remove(LEGACY_KEY_PAYLOAD)
        }
    }

    @Synchronized
    override fun clearPassword() = clearSlot(PASSWORD_SLOT)

    @Synchronized
    override fun clearAll() {
        preferences.edit(commit = true) { clear() }
    }

    private fun <T> readEncrypted(slot: String, decode: (ByteArray) -> T): T? {
        val encodedIv = preferences.getString(ivKey(slot), null) ?: return null
        val encodedPayload = preferences.getString(payloadKey(slot), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.updateAAD(slot.encodeToByteArray())
            val cleartext = cipher.doFinal(Base64.decode(encodedPayload, Base64.NO_WRAP))
            try {
                decode(cleartext)
            } finally {
                cleartext.fill(0)
            }
        }.getOrElse {
            clearSlot(slot)
            null
        }
    }

    private fun writeEncrypted(slot: String, cleartext: ByteArray) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(slot.encodeToByteArray())
            val payload = cipher.doFinal(cleartext)
            preferences.edit(commit = true) {
                putString(ivKey(slot), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                putString(payloadKey(slot), Base64.encodeToString(payload, Base64.NO_WRAP))
            }
            payload.fill(0)
        } finally {
            cleartext.fill(0)
        }
    }

    private fun clearSlot(slot: String) {
        preferences.edit(commit = true) {
            remove(ivKey(slot))
            remove(payloadKey(slot))
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ivKey(slot: String) = "${slot}_iv_v2"
    private fun payloadKey(slot: String) = "${slot}_payload_v2"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opah_secure_secrets_aes_v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val PREFERENCES_NAME = "opah_secure_session"
        const val COOKIE_SLOT = "session_cookie"
        const val PASSWORD_SLOT = "auto_sign_in_password"
        const val LEGACY_KEY_IV = "session_iv"
        const val LEGACY_KEY_PAYLOAD = "session_payload"
    }
}

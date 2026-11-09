package app.opah.tv.homeassistant.android

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.opah.tv.homeassistant.HomeAssistantSession
import app.opah.tv.homeassistant.HomeAssistantSessionStore
import app.opah.tv.homeassistant.haJson
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Home Assistant has its own encrypted store and key; Frigate credentials are never accessed. */
// The KTX edit helper discards commit's result; durable secret writes must check it.
@SuppressLint("UseKtx")
internal class HomeAssistantSecretStore(context: Context) : HomeAssistantSessionStore {
    private val preferences = context.getSharedPreferences("opah_home_assistant", Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): HomeAssistantSession? {
        val payload = preferences.getString("session", null) ?: return null
        val iv = preferences.getString("iv", null) ?: error("Home Assistant storage unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(AAD)
        val bytes = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP))
        return try { haJson.decodeFromString<HomeAssistantSession>(bytes.toString(Charsets.UTF_8)) }
        finally { bytes.fill(0) }
    }

    @Synchronized
    override fun write(session: HomeAssistantSession) {
        val bytes = haJson.encodeToString(HomeAssistantSession.serializer(), session).toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(bytes)
            if (!preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                error("Home Assistant storage unavailable")
            }
            encrypted.fill(0)
        } finally { bytes.fill(0) }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove("iv").remove("session").commit()) { "Home Assistant storage unavailable" }
    }

    @Synchronized
    override fun deviceId(): String = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("device_id", it).commit()) { "Home Assistant storage unavailable" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "opah_home_assistant_aes_v1"
        private val AAD = "opah-home-assistant-session-v1".toByteArray()
    }
}

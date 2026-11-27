package app.opah.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.opah.tv.data.model.ConnectionProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.connectionDataStore by preferencesDataStore(name = "connection_profile")

interface ConnectionProfileStore {
    suspend fun load(): ConnectionProfile?
    suspend fun save(profile: ConnectionProfile)
    suspend fun clear()
}

class ProfileRepository internal constructor(private val store: DataStore<Preferences>) : ConnectionProfileStore {
    constructor(context: Context) : this(context.connectionDataStore)

    val automaticLiveRecovery = store.data.map { preferences ->
        val profile = preferences.connectionProfile()
        AutomaticLiveRecoveryPreference(preferences[AUTOMATIC_RECOVERY_BINDING]
            ?.takeIf { it == profile?.let(::automaticLiveRecoveryBinding) })
    }

    suspend fun setAutomaticLiveRecovery(profile: ConnectionProfile, enabled: Boolean) {
        val binding = automaticLiveRecoveryBinding(profile) ?: return
        store.edit { preferences ->
            val saved = preferences.connectionProfile()
            if (saved?.let(::automaticLiveRecoveryBinding) != binding) return@edit
            if (enabled) preferences[AUTOMATIC_RECOVERY_BINDING] = binding
            else preferences.remove(AUTOMATIC_RECOVERY_BINDING)
        }
    }
    internal val hasSavedProfile = store.data.map { it[API_URL] != null && it[USERNAME] != null }
    override suspend fun load(): ConnectionProfile? = store.data.first().connectionProfile()

    override suspend fun save(profile: ConnectionProfile) {
        store.edit { preferences ->
            val old = preferences.connectionProfile()
            if (old?.let(::automaticLiveRecoveryBinding) != automaticLiveRecoveryBinding(profile)) {
                preferences.remove(AUTOMATIC_RECOVERY_BINDING)
            }
            preferences[API_URL] = profile.apiBaseUrl
            preferences[USERNAME] = profile.username
            preferences[WITHOUT_SIGN_IN] = profile.withoutSignIn
            profile.rtspHostOverride?.let { preferences[RTSP_HOST] = it }
                ?: preferences.remove(RTSP_HOST)
            preferences[RTSP_PORT] = profile.rtspPort
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun Preferences.connectionProfile(): ConnectionProfile? {
        val url = this[API_URL] ?: return null
        val username = this[USERNAME] ?: return null
        val profile = ConnectionProfile(url, username, this[RTSP_HOST], this[RTSP_PORT] ?: 8554)
        // Older installs inferred the mode from the configured port.
        return this[WITHOUT_SIGN_IN]?.let { profile.copy(withoutSignIn = it) } ?: profile
    }

    private companion object {
        val API_URL = stringPreferencesKey("api_base_url")
        val USERNAME = stringPreferencesKey("username")
        val WITHOUT_SIGN_IN = booleanPreferencesKey("without_sign_in")
        val RTSP_HOST = stringPreferencesKey("rtsp_host_override")
        val RTSP_PORT = intPreferencesKey("rtsp_port")
        val AUTOMATIC_RECOVERY_BINDING = stringPreferencesKey("automatic_live_recovery_binding")
    }
}

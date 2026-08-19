package app.opah.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.opah.tv.data.model.AppSettings
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.ThemeColorPolicy
import app.opah.tv.data.model.StreamPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "opah_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::decode)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            val updated = transform(decode(preferences))
            preferences[STREAM_PREFERENCE] = updated.streamPreference.name
            preferences[PREFER_RTP_TCP] = updated.preferRtpTcp
            preferences[START_LIVE_MUTED] = updated.startLiveMuted
            preferences[DIAGNOSTICS_ENABLED] = updated.diagnosticsEnabled
            preferences[APPEARANCE_MODE] = updated.appearanceMode.name
            preferences[CUSTOM_ACCENT] = updated.customThemeColors.accentArgb
            preferences[CUSTOM_BACKGROUND] = updated.customThemeColors.backgroundArgb
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }

    private fun decode(preferences: androidx.datastore.preferences.core.Preferences): AppSettings =
        AppSettings(
            streamPreference = preferences[STREAM_PREFERENCE]
                ?.let { runCatching { StreamPreference.valueOf(it) }.getOrNull() }
                ?: StreamPreference.AUTOMATIC,
            preferRtpTcp = preferences[PREFER_RTP_TCP] ?: true,
            startLiveMuted = preferences[START_LIVE_MUTED] ?: false,
            diagnosticsEnabled = preferences[DIAGNOSTICS_ENABLED] ?: true,
            appearanceMode = preferences[APPEARANCE_MODE]
                ?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() }
                ?: AppearanceMode.SYSTEM,
            customThemeColors = ThemeColorPolicy.sanitize(
                CustomThemeColors(
                    accentArgb = preferences[CUSTOM_ACCENT] ?: CustomThemeColors().accentArgb,
                    backgroundArgb = preferences[CUSTOM_BACKGROUND] ?: CustomThemeColors().backgroundArgb,
                ),
            ),
        )

    private companion object {
        val STREAM_PREFERENCE = stringPreferencesKey("stream_preference")
        val PREFER_RTP_TCP = booleanPreferencesKey("prefer_rtp_tcp")
        val START_LIVE_MUTED = booleanPreferencesKey("start_live_muted")
        val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val CUSTOM_ACCENT = intPreferencesKey("custom_accent")
        val CUSTOM_BACKGROUND = intPreferencesKey("custom_background")
    }
}

package com.lxpure.music.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxpure.music.domain.model.AppSettings
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "lx_pure_settings")

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val AUTO_FALLBACK = booleanPreferencesKey("auto_fallback")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FLUID_AMBIENT = booleanPreferencesKey("fluid_ambient")
        val SHOW_TRANSLATION = booleanPreferencesKey("show_translation")
        val WIFI_ONLY_HQ = booleanPreferencesKey("wifi_only_hq")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            preferredQuality = AudioQuality.fromLxType(prefs[Keys.PREFERRED_QUALITY] ?: AudioQuality.FLAC.lxType),
            autoFallbackSource = prefs[Keys.AUTO_FALLBACK] ?: true,
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name) }
                .getOrDefault(ThemeMode.DARK),
            fluidAmbientBackground = prefs[Keys.FLUID_AMBIENT] ?: true,
            showTranslationLyrics = prefs[Keys.SHOW_TRANSLATION] ?: true,
            wifiOnlyHighQuality = prefs[Keys.WIFI_ONLY_HQ] ?: true
        )
    }

    suspend fun setPreferredQuality(quality: AudioQuality) {
        context.settingsDataStore.edit { it[Keys.PREFERRED_QUALITY] = quality.lxType }
    }

    suspend fun setAutoFallback(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_FALLBACK] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setFluidAmbient(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FLUID_AMBIENT] = enabled }
    }

    suspend fun setShowTranslation(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_TRANSLATION] = enabled }
    }

    suspend fun setWifiOnlyHighQuality(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WIFI_ONLY_HQ] = enabled }
    }
}

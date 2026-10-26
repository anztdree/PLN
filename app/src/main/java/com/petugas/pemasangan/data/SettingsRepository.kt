package com.petugas.pemasangan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "petugas_settings")

/**
 * Menyimpan preferensi sederhana:
 * - nama petugas (menjadi default pada form input berikutnya)
 * - mode tema (terang / gelap) — baru di v2.0
 */
class SettingsRepository(private val context: Context) {

    val officerNameFlow: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[KEY_OFFICER_NAME] ?: "" }

    suspend fun setOfficerName(name: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OFFICER_NAME] = name }
    }

    /** true berarti tema gelap aktif. */
    val themeFlow: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[KEY_THEME] == VALUE_DARK }

    suspend fun setTheme(dark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = if (dark) VALUE_DARK else VALUE_LIGHT
        }
    }

    companion object {
        private val KEY_OFFICER_NAME = stringPreferencesKey("officer_name")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private const val VALUE_DARK = "dark"
        private const val VALUE_LIGHT = "light"
    }
}

package com.petugas.pemasangan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "petugas_settings")

/**
 * Menyimpan preferensi sederhana, saat ini hanya nama petugas.
 * Nama petugas terakhir yang disimpan akan menjadi nilai default
 * pada form input berikutnya.
 */
class SettingsRepository(private val context: Context) {

    val officerNameFlow: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[KEY_OFFICER_NAME] ?: "" }

    suspend fun setOfficerName(name: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OFFICER_NAME] = name }
    }

    companion object {
        private val KEY_OFFICER_NAME = stringPreferencesKey("officer_name")
    }
}

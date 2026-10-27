package com.petugas.pemasangan.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "petugas_settings")

/**
 * Menyimpan preferensi sederhana:
 * - nama petugas (diisi otomatis saat registrasi, jadi default form berikutnya)
 * - identitas pusat: id petugas + kredensial perangkat (device_id & android_id)
 * - mode tema (terang / gelap)
 *
 * Aturan nama: sekali terdaftar di pusat, nama permanen untuk perangkat ini.
 * Jika data aplikasi direset, [petugasId] ikut hilang -> layar registrasi
 * tampil lagi; server mengenali [androidId] dan memulihkan nama lama.
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

    /**
     * Tema warna gelembung obrolan (id 0..3, lihat ui.chat.BubbleThemes).
     * Dipilih pengguna lewat tombol palet di header layar obrolan.
     */
    val bubbleThemeFlow: Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[KEY_BUBBLE]?.toIntOrNull() ?: 0 }

    suspend fun setBubbleTheme(id: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_BUBBLE] = id.toString() }
    }

    /* ================= IDENTITAS PUSAT (M4) ================= */

    /** Flow id petugas terdaftar ("" = belum terdaftar). */
    val petugasIdFlow: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[KEY_PETUGAS_ID] ?: "" }

    /** Kredensial perangkat yang stabil (dibuat sekali, dipakai selamanya). */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[KEY_DEVICE_ID]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            if (prefs[KEY_DEVICE_ID].isNullOrEmpty()) prefs[KEY_DEVICE_ID] = fresh
        }
        return context.dataStore.data.first()[KEY_DEVICE_ID] ?: fresh
    }

    /**
     * android_id perangkat — bertahan walau data aplikasi direset,
     * dipakai server untuk memulihkan nama setelah install ulang.
     */
    fun androidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "pln-unknown"

    /** Simpan hasil registrasi (dipanggil setelah server mengonfirmasi). */
    suspend fun setRegistered(petugasId: Long, nama: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PETUGAS_ID] = petugasId.toString()
            prefs[KEY_OFFICER_NAME] = nama
        }
    }

    /** Baca sekali: sudah terdaftar atau belum (untuk menentukan layar awal). */
    suspend fun isRegistered(): Boolean =
        context.dataStore.data.first()[KEY_PETUGAS_ID]?.isNotEmpty() == true

    companion object {
        private val KEY_OFFICER_NAME = stringPreferencesKey("officer_name")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_BUBBLE = stringPreferencesKey("bubble_theme")
        private val KEY_PETUGAS_ID = stringPreferencesKey("petugas_id")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private const val VALUE_DARK = "dark"
        private const val VALUE_LIGHT = "light"
    }
}

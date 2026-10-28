package com.pln.admin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pemeriksa berkala: pesan belum dibaca (obrolan) & laporan terbaru.
 * Sumber = /api/chat/previews (unread_total) + /api/dashboard (statistik).
 * Di foreground (aplikasi terbuka) nilai tersimpan diperbarui TANPA
 * notifikasi — admin sedang melihat dasbor sendiri. Di latar belakang
 * (WorkManager 15 menit) kenaikan memunculkan notifikasi status bar.
 */
object AdminPoller {

    private const val PREFS = "admin-poll"
    private const val KEY_UNREAD = "last_unread"
    private const val KEY_REPORT = "last_report_id"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(url: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("X-API-Key", Server.API_KEY)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.let(::JSONObject)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** @return benar bila ada perubahan yang berhasil dibaca. */
    fun poll(context: Context, notify: Boolean): Boolean {
        var changed = false

        // ---- pesan belum dibaca ----
        val pv = get("${Server.BASE_URL}/api/chat/previews")
        if (pv != null && pv.optBoolean("ok")) {
            val total = pv.optInt("unread_total", -1)
            if (total >= 0) {
                val p = prefs(context)
                val last = p.getInt(KEY_UNREAD, 0)
                if (total > last && notify && last >= 0) {
                    Notify.chat(context, total)
                }
                if (total != last) {
                    p.edit().putInt(KEY_UNREAD, total).apply()
                    changed = true
                }
            }
        }

        // ---- laporan terbaru ----
        val db = get("${Server.BASE_URL}/api/dashboard")
        if (db != null && db.optBoolean("ok")) {
            val stats = db.optJSONObject("statistik")
            val totalReports = stats?.optInt("reports", -1) ?: -1
            val first = db.optJSONArray("reports")?.optJSONObject(0)
            val newestId = first?.optString("id")?.toLongOrNull() ?: -1L
            val p = prefs(context)
            val lastId = p.getLong(KEY_REPORT, 0L)
            if (newestId > 0) {
                if (newestId > lastId && lastId > 0 && notify) {
                    val nama = first?.optString("nama_pelanggan").orEmpty().ifEmpty { "Pelanggan" }
                    val ptg = first?.optString("petugas").orEmpty().ifEmpty { "petugas" }
                    Notify.report(context, nama, ptg)
                }
                if (newestId != lastId) {
                    p.edit().putLong(KEY_REPORT, newestId).apply()
                    changed = true
                }
            }
            if (totalReports >= 0 && totalReports == 0 && p.getLong(KEY_REPORT, 0L) != 0L) {
                // data kosong (retensi) — jangan notif, cukup simpan
                p.edit().putLong(KEY_REPORT, 0L).apply()
            }
        }

        return changed
    }
}

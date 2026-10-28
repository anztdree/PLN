package com.pln.admin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pemeriksa berkala: pesan belum dibaca (obrolan) & laporan terbaru.
 * Sumber = /api/chat/previews (unread_total) + /api/dashboard (statistik).
 * Aplikasi SELALU siap memberi notifikasi saat ada pesan masuk:
 *  - foreground (aplikasi terbuka) : loop 20 detik dengan notifikasi aktif,
 *    hasilnya juga mengisi lencana FAB obrolan native;
 *  - latar belakang                : WorkManager tiap 15 menit + notifikasi.
 *
 * v1.1.0: notifikasi pesan kini SPESIFIK — memuat nama pengirim + isi
 * pesan terbaru (dari preview percakapan), dan mengetuknya membuka
 * obrolan native. Id pesan terakhir yang sudah dinotifikasi disimpan
 * agar pesan yang sama tidak muncul berulang.
 */
object AdminPoller {

    private const val PREFS = "admin-poll"
    private const val KEY_UNREAD = "last_unread"
    private const val KEY_REPORT = "last_report_id"
    private const val KEY_NOTIF_MSG = "last_notif_msg_id"

    /** Hasil satu kali pemeriksaan: ada perubahan & jumlah pesan belum dibaca terkini (-1 = belum tahu). */
    data class HasilPoll(val changed: Boolean, val unread: Int)

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

    fun poll(context: Context, notify: Boolean): HasilPoll {
        var changed = false
        var unreadTerkini = -1

        // ---- pesan belum dibaca + pesan terbaru pengirim lain ----
        val pv = get("${Server.BASE_URL}/api/chat/previews")
        if (pv != null && pv.optBoolean("ok")) {
            val total = pv.optInt("unread_total", -1)
            if (total >= 0) {
                unreadTerkini = total
                val p = prefs(context)
                val last = p.getInt(KEY_UNREAD, 0)
                if (total > last && notify && last >= 0) {
                    // Cari pesan TERBARU dari pengirim lain (bukan admin sendiri)
                    // di seluruh percakapan — itulah isi notifikasinya.
                    var newestId = 0L
                    var newestNama = ""
                    var newestBody = ""
                    pv.optJSONObject("global")?.let { g ->
                        if (g.optString("sender_type") != "admin") {
                            newestId = g.optLong("id")
                            newestNama = g.optString("sender_nama", "petugas")
                            newestBody = g.optString("body")
                        }
                    }
                    val dm = pv.optJSONObject("dm")
                    dm?.let {
                        for (key in it.keys()) {
                            val m = it.optJSONObject(key) ?: continue
                            if (m.optString("sender_type") == "admin") continue
                            val id = m.optLong("id")
                            if (id > newestId) {
                                newestId = id
                                newestNama = m.optString("sender_nama", "petugas")
                                newestBody = m.optString("body")
                            }
                        }
                    }

                    val lastNotifId = p.getLong(KEY_NOTIF_MSG, 0L)
                    if (newestId > 0 && newestId != lastNotifId) {
                        val isi = newestBody.ifEmpty { "Ada $total pesan menunggu dibaca." }
                        Notify.chat(context, newestNama, isi, total)
                        p.edit().putLong(KEY_NOTIF_MSG, newestId).apply()
                    }
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

        return HasilPoll(changed, unreadTerkini)
    }
}

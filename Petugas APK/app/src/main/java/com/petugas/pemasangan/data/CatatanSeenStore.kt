package com.petugas.pemasangan.data

import android.content.Context
import android.content.SharedPreferences

/**
 * v1.16-m17 — penyimpan "sudah dilihat/diberi tahu" CATATAN PUSAT.
 *
 * Dua penanda terpisah per laporan (kunci = client_ref "L<id>-<createdAt>"):
 *  - seen    : catatan terakhir yang benar-benar dilihat petugas di layar
 *              Detail — penentu lencana angka (belum dilihat = merah).
 *  - notified: catatan terakhir yang sudah diberi notifikasi — mencegah
 *              notifikasi berulang pada setiap putaran pemantau.
 *
 * Nilainya string ISO-8601 UTC (`catatan_pusat_at`) — mengurut leksikal,
 * jadi perbandingan cukup perbandingan string.
 */
object CatatanSeenStore {

    private const val FILE = "catatan_seen"
    private const val P_SEEN = "seen_"
    private const val P_NOTIFIED = "notified_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun has(context: Context, clientRef: String): Boolean =
        prefs(context).contains(P_SEEN + clientRef)

    fun get(context: Context, clientRef: String): String =
        prefs(context).getString(P_SEEN + clientRef, "") ?: ""

    /** Catat catatan terakhir yang sudah DILIHAT petugas. */
    fun set(context: Context, clientRef: String, catatanAt: String) {
        prefs(context).edit().putString(P_SEEN + clientRef, catatanAt).apply()
    }

    fun notified(context: Context, clientRef: String): String =
        prefs(context).getString(P_NOTIFIED + clientRef, "") ?: ""

    /** Catat catatan terakhir yang sudah DIBERI NOTIFIKASI. */
    fun setNotified(context: Context, clientRef: String, catatanAt: String) {
        prefs(context).edit().putString(P_NOTIFIED + clientRef, catatanAt).apply()
    }

    /**
     * v1.18-m20 — MIGRASI SEKALI: kode 28/29 ternyata MENELAN catatan
     * PERTAMA untuk tiap laporan secara diam (tanpa notifikasi, tanpa
     * tanda merah) — itulah keluhan pemilik. Penanda lama direset sekali
     * supaya catatan yang sudah ada dievaluasi ulang oleh aturan baru:
     * catatan segar (≤48 jam) -> notifikasi + tanda merah; catatan
     * sejarah (>48 jam) -> ditandai diam tanpa banjir notifikasi.
     */
    fun migrasiResetSekali(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("migrasi_m20", false)) return
        p.edit().clear().putBoolean("migrasi_m20", true).apply()
    }
}

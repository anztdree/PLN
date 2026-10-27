package com.petugas.pemasangan.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Catatan "pesan terakhir yang sudah dilihat pengguna" per percakapan.
 *
 * Dipakai dua arah:
 *  - ChatScreen menyimpan id pesan terbaru setiap layar obrolan dibuka /
 *    pesan diterima -> percakapan itu dianggap terbaca.
 *  - ChatPoller (pemantau pesan) membandingkan id terakhir dari server
 *    dengan catatan ini; bila lebih besar (dan pengirimnya bukan saya) =
 *    ada pesan baru -> notifikasi status bar.
 *
 * Penyimpanan hanya maju (id baru selalu menggantikan id lama) agar
 * hemat dan tidak pernah "membolos" ke notifikasi palsu.
 */
object ConvSeenStore {

    private const val PREFS = "chat_seen"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, convKey: String): Long =
        prefs(context).getLong(key(convKey), 0L)

    /** Simpan hanya jika lebih besar (monoton maju). */
    fun set(context: Context, convKey: String, id: Long) {
        if (id <= 0) return
        prefs(context).edit().putLong(key(convKey), id).apply()
    }

    /** Sudahkah catatan awal dibuat untuk percakapan ini? */
    fun has(context: Context, convKey: String): Boolean =
        prefs(context).contains(key(convKey))

    private fun key(convKey: String): String = "seen_$convKey"
}

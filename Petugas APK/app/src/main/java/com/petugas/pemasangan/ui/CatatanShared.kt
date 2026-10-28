package com.petugas.pemasangan.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.petugas.pemasangan.data.remote.PlnApi

/**
 * v1.16-m17 — state Compose bersama CATATAN PUSAT (pola ChatShared).
 *
 * [notes] = daftar catatan pusat terakhir dari server (maks 50 terbaru).
 * [unread] = jumlah catatan yang BELUM dilihat petugas — sumber angka
 * merah di banner menu & titik merah di daftar data.
 * [activeClientRef] = data yang sedang dibuka di layar Detail — catatan
 * untuk data ini tidak diberi notifikasi (petugas sedang melihatnya).
 */
object CatatanShared {

    val notes = mutableStateListOf<PlnApi.CatatanPusatItem>()

    var unread: Int by mutableIntStateOf(0)

    @Volatile
    var activeClientRef: String = ""
}

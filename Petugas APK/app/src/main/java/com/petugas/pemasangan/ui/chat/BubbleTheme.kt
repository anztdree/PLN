package com.petugas.pemasangan.ui.chat

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Satu gaya tampilan gelembung obrolan: warna latar & teks untuk
 * pesan milik sendiri (own) dan pesan orang lain (other).
 *
 * Warna dibuat STATIS (tidak mengikuti mode terang/gelap aplikasi)
 * supaya hasil chatting selalu terbaca jelas, konsisten, dan tidak
 * "tidak sinkron" antar perangkat/tema.
 */
data class BubbleStyle(
    val id: Int,
    val label: String,
    val deskripsi: String,
    val ownBg: Brush,
    val ownText: Color,
    val ownClock: Color,
    val otherBg: Color,
    val otherText: Color,
    val otherClock: Color,
)

/**
 * Koleksi tema gelembung obrolan. Dipilih lewat tombol palet di
 * header layar obrolan; pilihan tersimpan di pengaturan perangkat.
 */
object BubbleThemes {

    /** 0 — Bawaan: pesanku emas PLN, pesan lawan kaca navy pekat. */
    val Emas = BubbleStyle(
        id = 0,
        label = "Emas PLN",
        deskripsi = "Bawaan — pesanku emas, lawan navy",
        ownBg = Brush.linearGradient(
            listOf(Color(0xFFFFDE21), Color(0xFFFFB300), Color(0xFFFF8F1F))
        ),
        ownText = Color(0xFF241A00),
        ownClock = Color(0xB3241A00),
        otherBg = Color(0xF2101830),
        otherText = Color(0xFFF4F6FF),
        otherClock = Color(0xFF9AA7C7),
    )

    /** 1 — Hijau segar gaya aplikasi perpesanan klasik. */
    val Hijau = BubbleStyle(
        id = 1,
        label = "Hijau Segar",
        deskripsi = "Pesanku hijau, lawan biru tua",
        ownBg = Brush.linearGradient(
            listOf(Color(0xFF2BD26A), Color(0xFF128C5E))
        ),
        ownText = Color.White,
        ownClock = Color(0xE6FFFFFF),
        otherBg = Color(0xFF1B2647),
        otherText = Color(0xFFF4F6FF),
        otherClock = Color(0xFF9AA7C7),
    )

    /** 2 — Kontras maksimal: kertas terang vs abu gelap. Paling jelas. */
    val Kontras = BubbleStyle(
        id = 2,
        label = "Kontras Terang",
        deskripsi = "Pesanku putih, lawan abu gelap — paling jelas",
        ownBg = Brush.linearGradient(
            listOf(Color(0xFFFEFEF7), Color(0xFFF0F1E8))
        ),
        ownText = Color(0xFF101828),
        ownClock = Color(0xFF667085),
        otherBg = Color(0xFF39435E),
        otherText = Color.White,
        otherClock = Color(0xFFB4C0DC),
    )

    /** 3 — Cyan listrik: biru gelombang khas logo PLN. */
    val Cyan = BubbleStyle(
        id = 3,
        label = "Cyan Listrik",
        deskripsi = "Pesanku biru cyan, lawan navy",
        ownBg = Brush.linearGradient(
            listOf(Color(0xFF52E5FF), Color(0xFF0E9BC8))
        ),
        ownText = Color(0xFF002430),
        ownClock = Color(0xB3002430),
        otherBg = Color(0xF2101830),
        otherText = Color(0xFFF4F6FF),
        otherClock = Color(0xFF9AA7C7),
    )

    val all = listOf(Emas, Hijau, Kontras, Cyan)

    fun byId(id: Int): BubbleStyle = all.firstOrNull { it.id == id } ?: Emas
}

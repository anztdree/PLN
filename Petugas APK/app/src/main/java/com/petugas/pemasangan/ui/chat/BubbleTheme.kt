package com.petugas.pemasangan.ui.chat

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Satu gaya tampilan gelembung obrolan: warna latar, teks, garis tepi,
 * dan warna nama untuk pesan milik sendiri (own) dan pesan orang lain
 * (other).
 *
 * Warna dibuat STATIS (tidak mengikuti mode terang/gelap aplikasi)
 * supaya hasil chatting selalu terbaca jelas, konsisten, dan "sinkron".
 * M7: gelembung kini KOTAK penuh (dari ujung kiri ke kanan, di samping
 * avatar) — latar pesan lawan dibuat lebih TERANG dari latar aplikasi
 * plus garis tepi supaya tidak menyatu dengan latar navy.
 */
data class BubbleStyle(
    val id: Int,
    val label: String,
    val deskripsi: String,
    val ownBg: Brush,
    val ownText: Color,
    val ownClock: Color,
    val ownBorder: Color,
    val otherBg: Color,
    val otherText: Color,
    val otherClock: Color,
    val otherBorder: Color,
    val otherName: Color,
)

/**
 * Koleksi tema gelembung obrolan. Dipilih lewat tombol palet di
 * header layar obrolan; pilihan tersimpan di pengaturan perangkat.
 */
object BubbleThemes {

    /** 0 — Bawaan: pesanku emas PLN, pesan lawan panel biru terang. */
    val Emas = BubbleStyle(
        id = 0,
        label = "Emas PLN",
        deskripsi = "Bawaan — pesanku emas, lawan biru terang",
        ownBg = Brush.linearGradient(
            listOf(Color(0xFFFFDE21), Color(0xFFFFB300), Color(0xFFFF8F1F))
        ),
        ownText = Color(0xFF241A00),
        ownClock = Color(0xB3241A00),
        ownBorder = Color(0x33FFFFFF),
        otherBg = Color(0xFF2A3352),
        otherText = Color(0xFFF4F6FF),
        otherClock = Color(0xFF9AA7C7),
        otherBorder = Color(0x4D52E5FF),
        otherName = Color(0xFFFFD95A),
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
        ownBorder = Color(0x33FFFFFF),
        otherBg = Color(0xFF22304F),
        otherText = Color(0xFFF4F6FF),
        otherClock = Color(0xFF9AA7C7),
        otherBorder = Color(0x4D2BD26A),
        otherName = Color(0xFF7BE8A2),
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
        ownBorder = Color(0x1A101828),
        otherBg = Color(0xFF39435E),
        otherText = Color.White,
        otherClock = Color(0xFFB4C0DC),
        otherBorder = Color(0x40FFFFFF),
        otherName = Color(0xFF9CC8FF),
    )

    /** 3 — Cyan listrik: biru gelombang khas logo PLN. */
    val Cyan = BubbleStyle(
        id = 3,
        label = "Cyan Listrik",
        deskripsi = "Pesanku biru cyan, lawan biru terang",
        ownBg = Brush.linearGradient(
            listOf(Color(0xFF52E5FF), Color(0xFF0E9BC8))
        ),
        ownText = Color(0xFF002430),
        ownClock = Color(0xB3002430),
        ownBorder = Color(0x33FFFFFF),
        otherBg = Color(0xFF2A3352),
        otherText = Color(0xFFF4F6FF),
        otherClock = Color(0xFF9AA7C7),
        otherBorder = Color(0x4D52E5FF),
        otherName = Color(0xFF7FE3FF),
    )

    val all = listOf(Emas, Hijau, Kontras, Cyan)

    fun byId(id: Int): BubbleStyle = all.firstOrNull { it.id == id } ?: Emas
}

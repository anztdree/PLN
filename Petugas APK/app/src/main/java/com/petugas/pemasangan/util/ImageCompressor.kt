package com.petugas.pemasangan.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Kompresi foto sebelum dikirim ke server pusat.
 *
 * Target: sisi terpanjang 1080 px + JPEG kualitas 72 -> umumnya 150-300 KB
 * per foto (dari 2-6 MB). Manfaat:
 *  - menghemat kuota data petugas saat mengirim laporan,
 *  - menjaga storage gratis Cloudflare (R2 10 GB) tetap awet,
 *  - kualitas bukti pemasangan tetap jelas terbaca.
 */
object ImageCompressor {

    /** Sisi terpanjang maksimal hasil kompresi. */
    const val MAX_DIM_PX = 1080

    /** Kualitas JPEG hasil kompresi (0-100). */
    const val JPEG_QUALITY = 72

    /**
     * Kompres file foto di [path] menjadi byte JPEG siap unggah.
     * Mengembalikan null bila file tidak bisa dibaca sebagai gambar.
     */
    fun compress(path: String): ByteArray? = runCatching {
        // 1. Baca ukuran asli tanpa memuat piksel ke memori.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // 2. Ambil sampel kasar dulu (hemat memori di HP low-end).
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM_PX) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@runCatching null

        // 3. Haluskan sisa skala agar sisi terpanjang pas 1080 px.
        val longest = maxOf(decoded.width, decoded.height)
        val bmp = if (longest > MAX_DIM_PX) {
            val scale = MAX_DIM_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }

        // 4. Enkode JPEG.
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        val bytes = bos.toByteArray()

        if (bmp !== decoded) bmp.recycle()
        decoded.recycle()
        bytes
    }.getOrNull()
}

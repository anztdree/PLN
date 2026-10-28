package com.petugas.pemasangan.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entitas utama penyimpanan data pemasangan.
 * [photos] disimpan sebagai JSON array berisi path absolut file foto.
 * [status] "SELESAI" / "PENDING" (baru di v3.0).
 * [catatanPusat] & [catatanPusatAt] catatan admin pusat (v1.16-m17).
 * [kirimPusatAt] waktu data benar-benar sampai ke server (v1.16-m17).
 */
@Entity(tableName = "installations")
data class InstallationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val customerId: String,
    val customerName: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val installDate: Long,
    val officerName: String,
    val photos: String,
    val notes: String = "",
    val status: String = "SELESAI",
    // v1.16-m17 — CATATAN PUSAT: instruksi/keterangan dari admin pusat
    // atas data ini (tersalin dari server oleh CatatanPoller).
    val catatanPusat: String = "",
    val catatanPusatAt: Long = 0L,
    // v1.16-m17 — status pengiriman ke pusat: 0 = BELUM sampai ke server
    // (jaringan gagal) -> detail menampilkan tombol "Kirim Ulang".
    val kirimPusatAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object InstallStatus {
    const val SELESAI = "SELESAI"
    const val PENDING = "PENDING"
}

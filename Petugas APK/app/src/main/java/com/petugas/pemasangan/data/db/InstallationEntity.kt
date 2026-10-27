package com.petugas.pemasangan.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entitas utama penyimpanan data pemasangan.
 * [photos] disimpan sebagai JSON array berisi path absolut file foto.
 * [status] "SELESAI" / "PENDING" (baru di v3.0).
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object InstallStatus {
    const val SELESAI = "SELESAI"
    const val PENDING = "PENDING"
}

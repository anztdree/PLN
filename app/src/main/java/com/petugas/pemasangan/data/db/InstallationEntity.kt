package com.petugas.pemasangan.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entitas utama penyimpanan data pemasangan.
 * [photos] disimpan sebagai JSON array berisi path absolut file foto.
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

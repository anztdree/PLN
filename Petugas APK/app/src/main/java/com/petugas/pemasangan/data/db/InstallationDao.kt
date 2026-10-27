package com.petugas.pemasangan.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallationDao {

    @Query("SELECT * FROM installations ORDER BY installDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<InstallationEntity>>

    @Query("SELECT * FROM installations WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<InstallationEntity?>

    @Insert
    suspend fun insert(entity: InstallationEntity): Long

    @Update
    suspend fun update(entity: InstallationEntity)

    @Delete
    suspend fun delete(entity: InstallationEntity)
}

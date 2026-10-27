package com.petugas.pemasangan.data

import com.petugas.pemasangan.data.db.InstallationDao
import com.petugas.pemasangan.data.db.InstallationEntity
import kotlinx.coroutines.flow.Flow

class InstallationRepository(private val dao: InstallationDao) {

    fun observeAll(): Flow<List<InstallationEntity>> = dao.observeAll()

    fun observeById(id: Long): Flow<InstallationEntity?> = dao.observeById(id)

    suspend fun upsert(entity: InstallationEntity): Long =
        if (entity.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            entity.id
        }

    suspend fun delete(entity: InstallationEntity) = dao.delete(entity)
}

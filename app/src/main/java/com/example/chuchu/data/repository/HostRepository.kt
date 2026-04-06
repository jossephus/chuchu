package com.example.chuchu.data.repository

import com.example.chuchu.data.db.HostProfileDao
import com.example.chuchu.model.HostProfile
import kotlinx.coroutines.flow.Flow

class HostRepository(
    private val dao: HostProfileDao,
) {
    fun observeAll(): Flow<List<HostProfile>> = dao.observeAll()

    suspend fun getById(id: Long): HostProfile? = dao.getById(id)

    suspend fun upsert(profile: HostProfile): Long = dao.insert(profile)

    suspend fun delete(profile: HostProfile) = dao.delete(profile)
}

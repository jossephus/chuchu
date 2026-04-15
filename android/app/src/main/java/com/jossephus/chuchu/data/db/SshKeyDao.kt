package com.jossephus.chuchu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jossephus.chuchu.model.SshKey
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name")
    fun observeAll(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SshKey): Long

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: Long)
}

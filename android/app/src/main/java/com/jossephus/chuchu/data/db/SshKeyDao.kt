package com.jossephus.chuchu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jossephus.chuchu.model.SshKey
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name") fun observeAll(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id") suspend fun getById(id: Long): SshKey?

    @Query("SELECT * FROM ssh_keys ORDER BY name") suspend fun getAll(): List<SshKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(key: SshKey): Long

    @Insert suspend fun insertForImport(key: SshKey): Long

    @Query("UPDATE ssh_keys SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM ssh_keys WHERE name = :name AND id != :excludeId)")
    suspend fun nameExists(name: String, excludeId: Long): Boolean

    @Query("DELETE FROM ssh_keys WHERE id = :id") suspend fun deleteById(id: Long)
}

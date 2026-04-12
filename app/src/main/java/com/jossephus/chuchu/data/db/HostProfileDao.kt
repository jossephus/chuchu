package com.jossephus.chuchu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jossephus.chuchu.model.HostProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface HostProfileDao {
    @Query("SELECT * FROM host_profiles ORDER BY name")
    fun observeAll(): Flow<List<HostProfile>>

    @Query("SELECT * FROM host_profiles WHERE id = :id")
    suspend fun getById(id: Long): HostProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: HostProfile): Long

    @Update
    suspend fun update(profile: HostProfile)

    @Delete
    suspend fun delete(profile: HostProfile)
}

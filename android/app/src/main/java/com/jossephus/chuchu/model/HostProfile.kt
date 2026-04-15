package com.jossephus.chuchu.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "host_profiles",
    indices = [Index(value = ["name"])],
)
data class HostProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val keyId: Long? = null,
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
)

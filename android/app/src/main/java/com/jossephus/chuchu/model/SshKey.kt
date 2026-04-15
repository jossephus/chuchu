package com.jossephus.chuchu.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ssh_keys",
    indices = [Index(value = ["name"], unique = true)],
)
data class SshKey(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val algorithm: String = "RSA",
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
    val createdAtEpochMs: Long,
)

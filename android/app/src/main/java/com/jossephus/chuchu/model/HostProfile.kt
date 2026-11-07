package com.jossephus.chuchu.model

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "0")
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val multiplexer: MultiplexerType? = null,
    // Extra PATH entries (colon separated, absolute) prepended to the multiplexer's exec-channel
    // commands: exec runs without the account's rc files, so mise/nix/asdf shims are not on PATH
    // there, and tmux/zmx would report "command not found".
    @ColumnInfo(defaultValue = "NULL")
    val remotePath: String? = null,
)

package com.jossephus.chuchu.data.backup

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.model.Transport

const val CHUCHU_BACKUP_EXTENSION = "chuchu-backup"
const val CHUCHU_BACKUP_MIME_TYPE = "application/octet-stream"

class BackupFormatException(message: String) : Exception(message)

class InvalidBackupPassphraseException : Exception("Invalid backup passphrase")

data class BackupPayload(
    val keys: List<BackupSshKey>,
    val hosts: List<BackupHostProfile>,
) {
    fun toEntities(): Pair<List<SshKey>, List<HostProfile>> =
        keys.map { it.toEntity() } to hosts.map { it.toEntity() }
}

data class BackupSshKey(
    val id: Long,
    val name: String,
    val algorithm: String,
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
    val createdAtEpochMs: Long,
) {
    fun toEntity(idOverride: Long = id): SshKey = SshKey(
        id = idOverride,
        name = name,
        algorithm = algorithm,
        privateKeyPem = privateKeyPem,
        publicKeyOpenSsh = publicKeyOpenSsh,
        createdAtEpochMs = createdAtEpochMs,
    )

    companion object {
        fun fromEntity(key: SshKey): BackupSshKey = BackupSshKey(
            id = key.id,
            name = key.name,
            algorithm = key.algorithm,
            privateKeyPem = key.privateKeyPem,
            publicKeyOpenSsh = key.publicKeyOpenSsh,
            createdAtEpochMs = key.createdAtEpochMs,
        )
    }
}

data class BackupHostProfile(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val keyId: Long?,
    val keyPassphrase: String,
    val transport: Transport,
    val authMethod: AuthMethod,
    val requireAuthOnConnect: Boolean,
    val postConnectCommand: String?,
) {
    fun toEntity(
        idOverride: Long = id,
        keyIdOverride: Long? = keyId,
    ): HostProfile = HostProfile(
        id = idOverride,
        name = name,
        host = host,
        port = port,
        username = username,
        password = password,
        keyId = keyIdOverride,
        keyPassphrase = keyPassphrase,
        transport = transport,
        authMethod = authMethod,
        requireAuthOnConnect = requireAuthOnConnect,
        postConnectCommand = postConnectCommand,
    )

    companion object {
        fun fromEntity(host: HostProfile): BackupHostProfile = BackupHostProfile(
            id = host.id,
            name = host.name,
            host = host.host,
            port = host.port,
            username = host.username,
            password = host.password,
            keyId = host.keyId,
            keyPassphrase = host.keyPassphrase,
            transport = host.transport,
            authMethod = host.authMethod,
            requireAuthOnConnect = host.requireAuthOnConnect,
            postConnectCommand = host.postConnectCommand,
        )
    }
}

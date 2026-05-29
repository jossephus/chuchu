package com.jossephus.chuchu.data.backup

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey

object ChuchuBackupImportPlanner {
    @Throws(BackupFormatException::class)
    fun planImport(
        payload: BackupPayload,
        existingKeys: List<SshKey>,
        existingHosts: List<HostProfile>,
    ): BackupImportPlan {
        validatePayload(payload)

        val usedKeyNames = existingKeys.map { it.name }.toMutableSet()
        val keyActions = mutableListOf<KeyImportAction>()
        val keyRefs = mutableMapOf<Long, KeyReference>()

        payload.keys.forEach { backupKey ->
            val exactExisting = existingKeys.firstOrNull { it.sameContentAs(backupKey) }
            if (exactExisting != null) {
                keyActions += KeyImportAction.Reuse(
                    exportedId = backupKey.id,
                    localId = exactExisting.id,
                    name = exactExisting.name,
                )
                keyRefs[backupKey.id] = KeyReference.Existing(exactExisting.id)
            } else {
                val importName = uniqueName(backupKey.name, usedKeyNames)
                val importKey = backupKey.toEntity(idOverride = 0L).copy(name = importName)
                keyActions += KeyImportAction.Insert(
                    exportedId = backupKey.id,
                    key = importKey,
                    renamed = importName != backupKey.name,
                )
                keyRefs[backupKey.id] = KeyReference.Insert(keyActions.lastIndex)
            }
        }

        val usedHostNames = existingHosts.map { it.name }.toMutableSet()
        val hostActions = payload.hosts.map { backupHost ->
            val localKeyId = backupHost.keyId?.let { exportedKeyId ->
                when (val ref = keyRefs[exportedKeyId]) {
                    is KeyReference.Existing -> ref.localId
                    is KeyReference.Insert -> null
                    null -> throw BackupFormatException("Host references a missing SSH key")
                }
            }
            val importName = uniqueName(backupHost.name, usedHostNames)
            val importHost = backupHost.toEntity(
                idOverride = 0L,
                keyIdOverride = localKeyId,
            ).copy(name = importName)
            HostImportAction(
                exportedId = backupHost.id,
                host = importHost,
                keyActionIndex = backupHost.keyId?.let { (keyRefs[it] as? KeyReference.Insert)?.actionIndex },
                renamed = importName != backupHost.name,
            )
        }

        return BackupImportPlan(
            keyActions = keyActions,
            hostActions = hostActions,
        )
    }

    @Throws(BackupFormatException::class)
    private fun validatePayload(payload: BackupPayload) {
        val keyIds = mutableSetOf<Long>()
        payload.keys.forEach { key ->
            if (key.id <= 0L) throw BackupFormatException("Invalid SSH key id")
            if (!keyIds.add(key.id)) throw BackupFormatException("Duplicate SSH key id")
            if (key.name.isBlank()) throw BackupFormatException("SSH key name is required")
            if (key.privateKeyPem.isBlank()) throw BackupFormatException("SSH private key is required")
            if (key.publicKeyOpenSsh.isBlank()) throw BackupFormatException("SSH public key is required")
        }

        val hostIds = mutableSetOf<Long>()
        payload.hosts.forEach { host ->
            if (host.id <= 0L) throw BackupFormatException("Invalid host id")
            if (!hostIds.add(host.id)) throw BackupFormatException("Duplicate host id")
            if (host.name.isBlank()) throw BackupFormatException("Host name is required")
            if (host.host.isBlank()) throw BackupFormatException("Host address is required")
            if (host.username.isBlank()) throw BackupFormatException("Host username is required")
            if (host.port !in 1..65535) throw BackupFormatException("Invalid host port")
            if (host.keyId != null && host.keyId !in keyIds) {
                throw BackupFormatException("Host references a missing SSH key")
            }
            if (host.authMethod.requiresKey() && host.keyId == null) {
                throw BackupFormatException("Key authentication host is missing an SSH key")
            }
        }
    }

    private fun uniqueName(baseName: String, usedNames: MutableSet<String>): String {
        val trimmed = baseName.trim().ifBlank { "imported" }
        if (usedNames.add(trimmed)) return trimmed
        var index = 2
        while (true) {
            val candidate = "$trimmed-$index"
            if (usedNames.add(candidate)) return candidate
            index += 1
        }
    }

    private fun SshKey.sameContentAs(backupKey: BackupSshKey): Boolean =
        name == backupKey.name &&
            algorithm == backupKey.algorithm &&
            privateKeyPem == backupKey.privateKeyPem &&
            publicKeyOpenSsh == backupKey.publicKeyOpenSsh &&
            createdAtEpochMs == backupKey.createdAtEpochMs

    private fun AuthMethod.requiresKey(): Boolean =
        this == AuthMethod.Key || this == AuthMethod.KeyWithPassphrase
}

data class BackupImportPlan(
    val keyActions: List<KeyImportAction>,
    val hostActions: List<HostImportAction>,
) {
    val keyInsertCount: Int get() = keyActions.count { it is KeyImportAction.Insert }
    val keyReuseCount: Int get() = keyActions.count { it is KeyImportAction.Reuse }
    val renamedKeyCount: Int get() = keyActions.count { it is KeyImportAction.Insert && it.renamed }
    val renamedHostCount: Int get() = hostActions.count { it.renamed }
}

sealed interface KeyImportAction {
    val exportedId: Long

    data class Reuse(
        override val exportedId: Long,
        val localId: Long,
        val name: String,
    ) : KeyImportAction

    data class Insert(
        override val exportedId: Long,
        val key: SshKey,
        val renamed: Boolean,
    ) : KeyImportAction
}

data class HostImportAction(
    val exportedId: Long,
    val host: HostProfile,
    val keyActionIndex: Int?,
    val renamed: Boolean,
)

private sealed interface KeyReference {
    data class Existing(val localId: Long) : KeyReference
    data class Insert(val actionIndex: Int) : KeyReference
}

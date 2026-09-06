package com.jossephus.chuchu.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.jossephus.chuchu.data.db.SshKeyDao
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.service.ssh.Ed25519KeyGenerator
import com.jossephus.chuchu.service.ssh.SshKeyImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SshKeyRepository(
    private val dao: SshKeyDao,
    private val keyGenerator: Ed25519KeyGenerator = Ed25519KeyGenerator(),
    private val keyImporter: SshKeyImporter = SshKeyImporter(),
) {
    fun observeAll(): Flow<List<SshKey>> = dao.observeAll()

    suspend fun getById(id: Long): SshKey? = dao.getById(id)

    suspend fun getAll(): List<SshKey> = dao.getAll()

    suspend fun insert(key: SshKey): Long = dao.insert(key)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun generate(nameHint: String, passphrase: String = ""): SshKey {
        val base = nameHint.trim().ifBlank { "android-ed25519" }
        var name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
        while (true) {
            val key = withContext(Dispatchers.Default) { keyGenerator.generate(name, passphrase) }
            try {
                return key.copy(id = dao.insert(key))
            } catch (_: SQLiteConstraintException) {
                name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
            }
        }
    }

    suspend fun import(nameHint: String, privateKeyPem: String): ImportResult {
        val pem = privateKeyPem.trim()
        if (pem.isBlank()) return ImportResult.Blank
        if (!pem.contains("PRIVATE KEY")) return ImportResult.NotAPrivateKey

        val derived = withContext(Dispatchers.Default) { keyImporter.derivePublicKey(pem) }
        val base = nameHint.trim().ifBlank { "imported-key" }
        var name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
        while (true) {
            val key =
                SshKey(
                    name = name,
                    algorithm = derived?.algorithm?.let(::normalizeAlgorithm) ?: "imported",
                    privateKeyPem = pem,
                    publicKeyOpenSsh = derived?.publicKeyOpenSsh ?: "",
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            try {
                return ImportResult.Success(
                    key.copy(id = dao.insert(key)),
                    publicKeyDerived = derived != null,
                )
            } catch (_: SQLiteConstraintException) {
                name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
            }
        }
    }

    suspend fun rename(id: Long, newName: String): RenameResult {
        val name = newName.trim()
        if (name.isBlank()) return RenameResult.Blank
        if (dao.nameExists(name, excludeId = id)) return RenameResult.NameTaken
        return try {
            dao.rename(id, name)
            RenameResult.Success
        } catch (_: SQLiteConstraintException) {
            RenameResult.NameTaken
        }
    }

    private fun normalizeAlgorithm(keytype: String): String =
        when {
            keytype == "ssh-ed25519" -> "Ed25519"
            keytype == "ssh-rsa" -> "RSA"
            keytype == "ssh-dss" -> "DSA"
            keytype.startsWith("ecdsa-") -> "ECDSA"
            else -> keytype
        }

    private fun uniquify(base: String, existingNames: Set<String>): String {
        if (base !in existingNames) return base
        var index = 2
        var candidate = "$base-$index"
        while (candidate in existingNames) {
            index += 1
            candidate = "$base-$index"
        }
        return candidate
    }
}

enum class RenameResult {
    Success,
    Blank,
    NameTaken,
}

sealed interface ImportResult {
    data class Success(val key: SshKey, val publicKeyDerived: Boolean) : ImportResult

    data object Blank : ImportResult

    data object NotAPrivateKey : ImportResult
}

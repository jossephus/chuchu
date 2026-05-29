package com.jossephus.chuchu.data.backup

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object ChuchuBackupCodec {
    const val FORMAT_VERSION: Int = 1
    const val PAYLOAD_VERSION: Int = 1
    const val KDF_ID_PBKDF2_HMAC_SHA1: Int = 1
    const val CIPHER_ID_AES_256_GCM: Int = 1
    const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA1"
    const val CIPHER_ALGORITHM: String = "AES/GCM/NoPadding"
    const val KDF_ITERATIONS: Int = 210_000
    const val KEY_SIZE_BITS: Int = 256
    const val SALT_SIZE_BYTES: Int = 16
    const val IV_SIZE_BYTES: Int = 12
    const val GCM_TAG_SIZE_BITS: Int = 128
    const val MAX_BACKUP_SIZE_BYTES: Int = 32 * 1024 * 1024

    private const val CONTAINER_MAGIC: Int = 0x4348424b // CHBK
    private const val PAYLOAD_MAGIC: Int = 0x4348504c // CHPL
    private const val MAX_ITEMS: Int = 100_000
    private const val MAX_STRING_BYTES: Int = 4 * 1024 * 1024

    fun encrypt(
        payload: BackupPayload,
        passphrase: CharArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase must not be empty" }
        val plaintext = encodePayload(payload)
        return try {
            val salt = ByteArray(SALT_SIZE_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_SIZE_BYTES).also(random::nextBytes)
            val metadata = encodeMetadata(salt = salt, iv = iv)
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
            cipher.updateAAD(metadata)
            val ciphertext = cipher.doFinal(plaintext)
            val encrypted = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.write(metadata)
                    data.writeInt(ciphertext.size)
                    data.write(ciphertext)
                }
                output.toByteArray()
            }
            if (encrypted.size > MAX_BACKUP_SIZE_BYTES) throw BackupFormatException("Backup file is too large")
            encrypted
        } finally {
            plaintext.fill(0)
        }
    }

    @Throws(BackupFormatException::class, InvalidBackupPassphraseException::class)
    fun decrypt(bytes: ByteArray, passphrase: CharArray): BackupPayload {
        require(passphrase.isNotEmpty()) { "Backup passphrase must not be empty" }
        return try {
            if (bytes.size > MAX_BACKUP_SIZE_BYTES) throw BackupFormatException("Backup file is too large")
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val metadata = readMetadata(input)
                val ciphertextSize = input.readPositiveInt("ciphertext size")
                if (ciphertextSize > MAX_BACKUP_SIZE_BYTES) {
                    throw BackupFormatException("Backup file is too large")
                }
                val ciphertext = input.readExactBytes(ciphertextSize, "ciphertext")
                if (input.read() != -1) throw BackupFormatException("Unexpected trailing backup data")
                val key = deriveKey(passphrase, metadata.salt)
                val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, metadata.iv))
                cipher.updateAAD(metadata.encoded)
                val plaintext = try {
                    cipher.doFinal(ciphertext)
                } catch (_: AEADBadTagException) {
                    throw InvalidBackupPassphraseException()
                }
                try {
                    decodePayload(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }
        } catch (e: InvalidBackupPassphraseException) {
            throw e
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: EOFException) {
            throw BackupFormatException("Truncated backup file")
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException(e.message ?: "Invalid backup file")
        }
    }

    @Throws(BackupFormatException::class)
    fun encodePayload(payload: BackupPayload): ByteArray {
        validateItemCount(payload.keys.size, "key count")
        validateItemCount(payload.hosts.size, "host count")
        val encoded = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(PAYLOAD_MAGIC)
                data.writeInt(PAYLOAD_VERSION)
                data.writeInt(payload.keys.size)
                payload.keys.forEach { key ->
                    data.writeLong(key.id)
                    data.writeString(key.name, "key name")
                    data.writeString(key.algorithm, "key algorithm")
                    data.writeString(key.privateKeyPem, "private key")
                    data.writeString(key.publicKeyOpenSsh, "public key")
                    data.writeLong(key.createdAtEpochMs)
                }
                data.writeInt(payload.hosts.size)
                payload.hosts.forEach { host ->
                    data.writeLong(host.id)
                    data.writeString(host.name, "host name")
                    data.writeString(host.host, "host")
                    data.writeInt(host.port)
                    data.writeString(host.username, "username")
                    data.writeString(host.password, "password")
                    data.writeNullableLong(host.keyId)
                    data.writeString(host.keyPassphrase, "key passphrase")
                    data.writeString(host.transport.name, "transport")
                    data.writeString(host.authMethod.name, "auth method")
                    data.writeBoolean(host.requireAuthOnConnect)
                    data.writeNullableString(host.postConnectCommand, "post-connect command")
                }
            }
            output.toByteArray()
        }
        if (encoded.size > MAX_BACKUP_SIZE_BYTES) throw BackupFormatException("Backup payload is too large")
        return encoded
    }

    @Throws(BackupFormatException::class)
    fun decodePayload(bytes: ByteArray): BackupPayload = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != PAYLOAD_MAGIC) throw BackupFormatException("Invalid backup payload")
            val version = input.readInt()
            if (version != PAYLOAD_VERSION) throw BackupFormatException("Unsupported backup payload version")
            val keyCount = input.readItemCount("key count")
            val keys = buildList(keyCount) {
                repeat(keyCount) {
                    add(
                        BackupSshKey(
                            id = input.readLong(),
                            name = input.readString("key name"),
                            algorithm = input.readString("key algorithm"),
                            privateKeyPem = input.readString("private key"),
                            publicKeyOpenSsh = input.readString("public key"),
                            createdAtEpochMs = input.readLong(),
                        ),
                    )
                }
            }
            val hostCount = input.readItemCount("host count")
            val hosts = buildList(hostCount) {
                repeat(hostCount) {
                    add(
                        BackupHostProfile(
                            id = input.readLong(),
                            name = input.readString("host name"),
                            host = input.readString("host"),
                            port = input.readInt(),
                            username = input.readString("username"),
                            password = input.readString("password"),
                            keyId = input.readNullableLong(),
                            keyPassphrase = input.readString("key passphrase"),
                            transport = input.readEnum<Transport>("transport"),
                            authMethod = input.readEnum<AuthMethod>("auth method"),
                            requireAuthOnConnect = input.readBoolean(),
                            postConnectCommand = input.readNullableString("post-connect command"),
                        ),
                    )
                }
            }
            if (input.read() != -1) throw BackupFormatException("Unexpected trailing payload data")
            BackupPayload(keys = keys, hosts = hosts)
        }
    } catch (e: BackupFormatException) {
        throw e
    } catch (e: EOFException) {
        throw BackupFormatException("Truncated backup payload")
    }

    private fun encodeMetadata(salt: ByteArray, iv: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(CONTAINER_MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(KDF_ID_PBKDF2_HMAC_SHA1)
            data.writeInt(KDF_ITERATIONS)
            data.writeInt(CIPHER_ID_AES_256_GCM)
            data.writeBytesWithLength(salt)
            data.writeBytesWithLength(iv)
        }
        output.toByteArray()
    }

    private fun readMetadata(input: DataInputStream): BackupMetadata {
        val bytes = ByteArrayOutputStream()
        fun writeInt(value: Int) {
            DataOutputStream(bytes).use { it.writeInt(value) }
        }
        val magic = input.readInt().also(::writeInt)
        if (magic != CONTAINER_MAGIC) throw BackupFormatException("Invalid backup file")
        val version = input.readInt().also(::writeInt)
        if (version != FORMAT_VERSION) throw BackupFormatException("Unsupported backup version")
        val kdfId = input.readInt().also(::writeInt)
        if (kdfId != KDF_ID_PBKDF2_HMAC_SHA1) throw BackupFormatException("Unsupported backup KDF")
        val iterations = input.readInt().also(::writeInt)
        if (iterations != KDF_ITERATIONS) throw BackupFormatException("Unsupported backup KDF parameters")
        val cipherId = input.readInt().also(::writeInt)
        if (cipherId != CIPHER_ID_AES_256_GCM) throw BackupFormatException("Unsupported backup cipher")
        val salt = input.readBytesWithLength("salt", SALT_SIZE_BYTES).also { bytes.writeIntAndBytes(it) }
        val iv = input.readBytesWithLength("iv", IV_SIZE_BYTES).also { bytes.writeIntAndBytes(it) }
        return BackupMetadata(salt = salt, iv = iv, encoded = bytes.toByteArray())
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_SIZE_BITS)
        return try {
            val keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private data class BackupMetadata(
        val salt: ByteArray,
        val iv: ByteArray,
        val encoded: ByteArray,
    )

    private fun DataOutputStream.writeString(value: String, label: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw BackupFormatException("$label is too large")
        writeBytesWithLength(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?, label: String) {
        writeBoolean(value != null)
        if (value != null) writeString(value, label)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeBytesWithLength(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeIntAndBytes(bytes: ByteArray) {
        DataOutputStream(this).use { data ->
            data.writeInt(bytes.size)
            data.write(bytes)
        }
    }

    private fun DataInputStream.readString(label: String): String {
        val size = readPositiveInt("$label length")
        if (size > MAX_STRING_BYTES) throw BackupFormatException("$label is too large")
        return String(readExactBytes(size, label), Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(label: String): String? =
        if (readBoolean()) readString(label) else null

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(label: String): T {
        val value = readString(label)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw BackupFormatException("Unknown $label")
    }

    private fun DataInputStream.readItemCount(label: String): Int {
        val count = readPositiveInt(label)
        validateItemCount(count, label)
        return count
    }

    private fun validateItemCount(count: Int, label: String) {
        if (count > MAX_ITEMS) throw BackupFormatException("$label is too large")
    }

    private fun DataInputStream.readPositiveInt(label: String): Int {
        val value = readInt()
        if (value < 0) throw BackupFormatException("Invalid $label")
        return value
    }

    private fun DataInputStream.readBytesWithLength(label: String, expectedSize: Int): ByteArray {
        val size = readPositiveInt("$label length")
        if (size != expectedSize) throw BackupFormatException("Invalid $label length")
        return readExactBytes(size, label)
    }

    private fun DataInputStream.readExactBytes(size: Int, label: String): ByteArray {
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes
    }
}

package com.jossephus.chuchu.service.backup

import com.jossephus.chuchu.data.backup.BackupFormatException
import com.jossephus.chuchu.data.backup.BackupHostProfile
import com.jossephus.chuchu.data.backup.BackupPayload
import com.jossephus.chuchu.data.backup.BackupSshKey
import com.jossephus.chuchu.data.backup.InvalidBackupPassphraseException
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport

object ChuchuBackupCodec {
    const val FORMAT_VERSION: Int = 1
    const val PAYLOAD_VERSION: Int = 1
    const val KDF_ID_PBKDF2_HMAC_SHA1: Int = 1
    const val CIPHER_ID_AES_256_GCM: Int = 1
    const val KDF_ITERATIONS: Int = 210_000
    const val SALT_SIZE_BYTES: Int = 16
    const val IV_SIZE_BYTES: Int = 12
    const val MAX_BACKUP_SIZE_BYTES: Int = 32 * 1024 * 1024

    private const val CONTAINER_MAGIC: Int = 0x4348424b // CHBK
    private const val PAYLOAD_MAGIC: Int = 0x4348504c // CHPL
    private const val MAX_ITEMS: Int = 100_000
    private const val MAX_STRING_BYTES: Int = 4 * 1024 * 1024

    private val nativeBridge = NativeBackupBridge()

    fun encrypt(
        payload: BackupPayload,
        passphrase: CharArray,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase must not be empty" }
        val plaintext = encodePayload(payload)
        return try {
            if (!nativeBridge.isLoaded()) throw BackupFormatException("Native backup encryption is unavailable")
            val result = nativeBridge.nativeEncrypt(plaintext, passphrase)
                ?: throw BackupFormatException("Native backup encryption failed")
            decodeNativeResult(result, allowInvalidPassphrase = false)
        } finally {
            plaintext.fill(0)
        }
    }

    @Throws(BackupFormatException::class, InvalidBackupPassphraseException::class)
    fun decrypt(bytes: ByteArray, passphrase: CharArray): BackupPayload {
        require(passphrase.isNotEmpty()) { "Backup passphrase must not be empty" }
        if (!nativeBridge.isLoaded()) throw BackupFormatException("Native backup decryption is unavailable")
        val result = nativeBridge.nativeDecrypt(bytes, passphrase)
            ?: throw BackupFormatException("Native backup decryption failed")
        val plaintext = decodeNativeResult(result, allowInvalidPassphrase = true)
        return try {
            decodePayload(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    @Throws(BackupFormatException::class)
    fun encodePayload(payload: BackupPayload): ByteArray {
        validateItemCount(payload.keys.size, "key count")
        validateItemCount(payload.hosts.size, "host count")

        val writer = ByteWriter()
        fun writeStringField(value: String, label: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_STRING_BYTES) throw BackupFormatException("$label is too large")
            writer.writeInt(bytes.size)
            writer.writeBytes(bytes)
        }

        fun writeNullableStringField(value: String?, label: String) {
            writer.writeBoolean(value != null)
            if (value != null) writeStringField(value, label)
        }

        writer.writeInt(PAYLOAD_MAGIC)
        writer.writeInt(PAYLOAD_VERSION)
        writer.writeInt(payload.keys.size)
        payload.keys.forEach { key ->
            writer.writeLong(key.id)
            writeStringField(key.name, "key name")
            writeStringField(key.algorithm, "key algorithm")
            writeStringField(key.privateKeyPem, "private key")
            writeStringField(key.publicKeyOpenSsh, "public key")
            writer.writeLong(key.createdAtEpochMs)
        }

        writer.writeInt(payload.hosts.size)
        payload.hosts.forEach { host ->
            writer.writeLong(host.id)
            writeStringField(host.name, "host name")
            writeStringField(host.host, "host")
            writer.writeInt(host.port)
            writeStringField(host.username, "username")
            writeStringField(host.password, "password")
            writer.writeNullableLong(host.keyId)
            writeStringField(host.keyPassphrase, "key passphrase")
            writeStringField(host.transport.name, "transport")
            writeStringField(host.authMethod.name, "auth method")
            writer.writeBoolean(host.requireAuthOnConnect)
            writeNullableStringField(host.postConnectCommand, "post-connect command")
        }

        val encoded = writer.toByteArray()
        if (encoded.size > MAX_BACKUP_SIZE_BYTES) throw BackupFormatException("Backup payload is too large")
        return encoded
    }

    @Throws(BackupFormatException::class)
    fun decodePayload(bytes: ByteArray): BackupPayload {
        val reader = ByteReader(bytes)
        fun readStringField(label: String): String {
            val size = reader.readPositiveInt("$label length")
            if (size > MAX_STRING_BYTES) throw BackupFormatException("$label is too large")
            return String(reader.readExactBytes(size, label), Charsets.UTF_8)
        }

        fun readNullableStringField(label: String): String? =
            if (reader.readBoolean()) readStringField(label) else null

        fun <T : Enum<T>> readEnumField(label: String, values: Array<T>): T {
            val value = readStringField(label)
            return values.firstOrNull { it.name == value } ?: throw BackupFormatException("Unknown $label")
        }

        if (reader.readInt() != PAYLOAD_MAGIC) throw BackupFormatException("Invalid backup payload")
        val version = reader.readInt()
        if (version != PAYLOAD_VERSION) throw BackupFormatException("Unsupported backup payload version")

        val keyCount = reader.readPositiveInt("key count")
        validateItemCount(keyCount, "key count")
        val keys = buildList(keyCount) {
            repeat(keyCount) {
                add(
                    BackupSshKey(
                        id = reader.readLong(),
                        name = readStringField("key name"),
                        algorithm = readStringField("key algorithm"),
                        privateKeyPem = readStringField("private key"),
                        publicKeyOpenSsh = readStringField("public key"),
                        createdAtEpochMs = reader.readLong(),
                    ),
                )
            }
        }

        val hostCount = reader.readPositiveInt("host count")
        validateItemCount(hostCount, "host count")
        val hosts = buildList(hostCount) {
            repeat(hostCount) {
                add(
                    BackupHostProfile(
                        id = reader.readLong(),
                        name = readStringField("host name"),
                        host = readStringField("host"),
                        port = reader.readInt(),
                        username = readStringField("username"),
                        password = readStringField("password"),
                        keyId = reader.readNullableLong(),
                        keyPassphrase = readStringField("key passphrase"),
                        transport = readEnumField("transport", enumValues<Transport>()),
                        authMethod = readEnumField("auth method", enumValues<AuthMethod>()),
                        requireAuthOnConnect = reader.readBoolean(),
                        postConnectCommand = readNullableStringField("post-connect command"),
                    ),
                )
            }
        }

        if (!reader.isEof()) throw BackupFormatException("Unexpected trailing payload data")
        return BackupPayload(keys = keys, hosts = hosts)
    }


    private fun decodeNativeResult(result: ByteArray, allowInvalidPassphrase: Boolean): ByteArray {
        if (result.isEmpty()) throw BackupFormatException("Invalid native backup response")
        val status = result[0].toInt() and 0xff
        val payload = result.copyOfRange(1, result.size)
        return when (status) {
            NativeBackupBridge.STATUS_OK -> payload
            NativeBackupBridge.STATUS_INVALID_PASSPHRASE -> {
                if (allowInvalidPassphrase) throw InvalidBackupPassphraseException()
                throw BackupFormatException("Native backup encryption rejected passphrase")
            }
            NativeBackupBridge.STATUS_FORMAT_ERROR -> {
                val message = String(payload, Charsets.UTF_8).ifBlank { "Invalid backup file" }
                throw BackupFormatException(message)
            }
            else -> {
                val message = String(payload, Charsets.UTF_8).ifBlank { "Native backup failure" }
                throw BackupFormatException(message)
            }
        }
    }

    private class ByteWriter(initialCapacity: Int = 256) {
        private var bytes = ByteArray(initialCapacity)
        private var size = 0

        fun writeInt(value: Int) {
            ensureCapacity(size + Int.SIZE_BYTES)
            bytes[size] = (value ushr 24).toByte()
            bytes[size + 1] = (value ushr 16).toByte()
            bytes[size + 2] = (value ushr 8).toByte()
            bytes[size + 3] = value.toByte()
            size += Int.SIZE_BYTES
        }

        fun writeLong(value: Long) {
            ensureCapacity(size + Long.SIZE_BYTES)
            bytes[size] = (value ushr 56).toByte()
            bytes[size + 1] = (value ushr 48).toByte()
            bytes[size + 2] = (value ushr 40).toByte()
            bytes[size + 3] = (value ushr 32).toByte()
            bytes[size + 4] = (value ushr 24).toByte()
            bytes[size + 5] = (value ushr 16).toByte()
            bytes[size + 6] = (value ushr 8).toByte()
            bytes[size + 7] = value.toByte()
            size += Long.SIZE_BYTES
        }

        fun writeBoolean(value: Boolean) {
            ensureCapacity(size + 1)
            bytes[size] = if (value) 1.toByte() else 0.toByte()
            size += 1
        }

        fun writeBytes(value: ByteArray) {
            ensureCapacity(size + value.size)
            value.copyInto(bytes, destinationOffset = size)
            size += value.size
        }

        fun writeBytesWithLength(value: ByteArray) {
            writeInt(value.size)
            writeBytes(value)
        }

        fun writeNullableLong(value: Long?) {
            writeBoolean(value != null)
            if (value != null) writeLong(value)
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var next = bytes.size
            while (next < required) {
                next = (next * 2).coerceAtMost(MAX_BACKUP_SIZE_BYTES)
                if (next < required && next == MAX_BACKUP_SIZE_BYTES) {
                    throw BackupFormatException("Backup payload is too large")
                }
            }
            bytes = bytes.copyOf(next)
        }
    }

    private class ByteReader(private val bytes: ByteArray) {
        private var offset: Int = 0

        fun readInt(): Int {
            ensureRemaining(Int.SIZE_BYTES, "int")
            val value = ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
            offset += Int.SIZE_BYTES
            return value
        }

        fun readLong(): Long {
            ensureRemaining(Long.SIZE_BYTES, "long")
            val value =
                ((bytes[offset].toLong() and 0xff) shl 56) or
                    ((bytes[offset + 1].toLong() and 0xff) shl 48) or
                    ((bytes[offset + 2].toLong() and 0xff) shl 40) or
                    ((bytes[offset + 3].toLong() and 0xff) shl 32) or
                    ((bytes[offset + 4].toLong() and 0xff) shl 24) or
                    ((bytes[offset + 5].toLong() and 0xff) shl 16) or
                    ((bytes[offset + 6].toLong() and 0xff) shl 8) or
                    (bytes[offset + 7].toLong() and 0xff)
            offset += Long.SIZE_BYTES
            return value
        }

        fun readBoolean(): Boolean {
            ensureRemaining(1, "boolean")
            return bytes[offset++].toInt() != 0
        }

        fun readNullableLong(): Long? =
            if (readBoolean()) readLong() else null

        fun readPositiveInt(label: String): Int {
            val value = readInt()
            if (value < 0) throw BackupFormatException("Invalid $label")
            return value
        }

        fun readExactBytes(size: Int, label: String): ByteArray {
            ensureRemaining(size, label)
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun isEof(): Boolean = offset == bytes.size

        private fun ensureRemaining(required: Int, label: String) {
            if (required < 0 || bytes.size - offset < required) {
                throw BackupFormatException("Truncated backup $label")
            }
        }
    }

    private fun validateItemCount(count: Int, label: String) {
        if (count > MAX_ITEMS) throw BackupFormatException("$label is too large")
    }
}

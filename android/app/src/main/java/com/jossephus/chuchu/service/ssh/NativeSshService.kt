package com.jossephus.chuchu.service.ssh

import com.jossephus.chuchu.model.AuthMethod
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeSshService(
    private val bridge: NativeSshBridge = NativeSshBridge(),
    private val hostKeyPolicy: HostKeyPolicy,
) : Closeable {
    private fun passwordAuthErrorMessage(nativeError: String?): String {
        if (nativeError.isNullOrBlank()) return "Native SSH password auth failed"
        if (nativeError.contains("Keyboard-interactive auth failed", ignoreCase = true)) {
            return "$nativeError. Server-side password authentication may be disabled for this endpoint (for example Tailscale SSH or sshd Match rules on tailnet addresses)."
        }
        return nativeError
    }

    private object Ipc {
        private const val VERSION: Byte = 1
        private const val TAG_WRITE: Byte = 1
        private const val TAG_READ: Byte = 2
        private const val TAG_ACK: Byte = 100.toByte()
        private const val TAG_DATA: Byte = 101.toByte()
        private const val TAG_ERROR: Byte = 255.toByte()
        private const val HEADER_SIZE = 6

        fun encodeWrite(data: ByteArray): ByteArray {
            val frame = ByteArray(HEADER_SIZE + data.size)
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(VERSION)
            buffer.put(TAG_WRITE)
            buffer.putInt(data.size)
            if (data.isNotEmpty()) {
                buffer.put(data)
            }
            return frame
        }

        fun encodeRead(maxBytes: Int): ByteArray {
            val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(maxBytes.coerceAtLeast(1)).array()
            val frame = ByteArray(HEADER_SIZE + payload.size)
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(VERSION)
            buffer.put(TAG_READ)
            buffer.putInt(payload.size)
            buffer.put(payload)
            return frame
        }

        data class DecodedFrame(val tag: Byte, val payload: ByteArray)

        fun decode(frame: ByteArray): DecodedFrame {
            require(frame.size >= HEADER_SIZE) { "Invalid IPC response" }
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.get()
            require(version == VERSION) { "Unsupported IPC version: $version" }
            val tag = buffer.get()
            val len = buffer.int
            require(len >= 0) { "Invalid IPC payload length" }
            require(frame.size == HEADER_SIZE + len) { "Truncated IPC response" }
            val payload = ByteArray(len)
            if (len > 0) {
                buffer.get(payload)
            }
            return DecodedFrame(tag, payload)
        }

        fun parseAckWritten(payload: ByteArray): Int {
            require(payload.size == 4) { "Invalid ACK payload" }
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        }

        fun parseError(payload: ByteArray): String {
            if (payload.isEmpty()) return "Native IPC error"
            return payload.toString(Charsets.UTF_8)
        }

        val tagAck: Byte get() = TAG_ACK
        val tagData: Byte get() = TAG_DATA
        val tagError: Byte get() = TAG_ERROR
    }

    private var handle: Long = 0L

    fun isAvailable(): Boolean = bridge.isLoaded()

    fun connect(
        host: String,
        port: Int,
        username: String,
        authMethod: AuthMethod = AuthMethod.Password,
        password: String,
        publicKeyOpenSsh: String = "",
        privateKeyPem: String = "",
        keyPassphrase: String = "",
    ) {
        require(bridge.isLoaded()) { "Native SSH unavailable: ${bridge.nativeStatus()}" }
        close()
        handle = bridge.nativeCreateSession()
        check(handle != 0L) { "Failed to create native SSH session" }

        if (!bridge.nativeConnect(handle, host, port, username)) {
            throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH connect failed")
        }

        val algorithm = bridge.nativeGetHostKeyAlgorithm(handle)
            ?: throw IllegalStateException("Missing server host key algorithm")
        val keyBytes = bridge.nativeGetHostKey(handle)
            ?: throw IllegalStateException("Missing server host key")
        if (!hostKeyPolicy.verify(host, port, algorithm, keyBytes)) {
            throw IllegalStateException("Host key rejected")
        }

        when (authMethod) {
            AuthMethod.None -> {
                if (!bridge.nativeAuthenticateNone(handle)) {
                    throw IllegalStateException(
                        bridge.nativeGetLastError(handle) ?: "Server did not accept none authentication",
                    )
                }
            }
            AuthMethod.Password -> {
                if (!bridge.nativeAuthenticatePassword(handle, password)) {
                    throw IllegalStateException(passwordAuthErrorMessage(bridge.nativeGetLastError(handle)))
                }
            }
            AuthMethod.Key -> {
                check(privateKeyPem.isNotBlank()) { "Missing in-app private key for key auth" }
                val ok = bridge.nativeAuthenticatePublicKeyMemory(handle, publicKeyOpenSsh.ifBlank { null }, privateKeyPem, null)
                if (!ok) {
                    throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH public key auth failed")
                }
            }
            AuthMethod.KeyWithPassphrase -> {
                check(privateKeyPem.isNotBlank()) { "Missing in-app private key for key auth" }
                check(keyPassphrase.isNotBlank()) { "Missing key passphrase for encrypted private key" }
                val ok = bridge.nativeAuthenticatePublicKeyMemory(handle, publicKeyOpenSsh.ifBlank { null }, privateKeyPem, keyPassphrase)
                if (!ok) {
                    val nativeError = bridge.nativeGetLastError(handle)
                    val hint = if (
                        nativeError?.contains("Callback returned error", ignoreCase = true) == true ||
                        nativeError?.contains("Public key memory auth failed", ignoreCase = true) == true
                    ) {
                        "Encrypted key authentication failed. Verify you entered the key passphrase (not the server account password)."
                    } else {
                        null
                    }
                    throw IllegalStateException(hint ?: nativeError ?: "Native SSH public key auth failed")
                }
            }
        }
    }

    fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeOpenShell(handle, cols, rows, widthPx, heightPx, "xterm-kitty")) {
            throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH shell open failed")
        }
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (handle == 0L) return
        if (!bridge.nativeResize(handle, cols, rows, widthPx, heightPx)) {
            throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH resize failed")
        }
    }

    fun write(data: ByteArray) {
        if (handle == 0L || data.isEmpty()) return
        var offset = 0
        var stalledWrites = 0
        while (offset < data.size) {
            val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
            val response = bridge.nativeIpcExchange(handle, Ipc.encodeWrite(chunk))
                ?: throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH write failed")
            val decoded = Ipc.decode(response)
            val written = when (decoded.tag) {
                Ipc.tagAck -> Ipc.parseAckWritten(decoded.payload)
                Ipc.tagError -> throw IllegalStateException(Ipc.parseError(decoded.payload))
                else -> throw IllegalStateException("Unexpected IPC write response tag: ${decoded.tag}")
            }
            if (written == 0) {
                stalledWrites += 1
                if (stalledWrites > 64) {
                    throw IllegalStateException("Native SSH write stalled")
                }
                Thread.sleep(4)
                continue
            }
            stalledWrites = 0
            offset += written
        }
    }

    fun read(maxBytes: Int = 8192): ByteArray? {
        if (handle == 0L) return null
        val response = bridge.nativeIpcExchange(handle, Ipc.encodeRead(maxBytes)) ?: return null
        val decoded = Ipc.decode(response)
        return when (decoded.tag) {
            Ipc.tagData -> decoded.payload
            Ipc.tagError -> throw IllegalStateException(Ipc.parseError(decoded.payload))
            else -> throw IllegalStateException("Unexpected IPC read response tag: ${decoded.tag}")
        }
    }

    override fun close() {
        if (handle == 0L) return
        bridge.nativeClose(handle)
        bridge.nativeDestroySession(handle)
        handle = 0L
    }
}

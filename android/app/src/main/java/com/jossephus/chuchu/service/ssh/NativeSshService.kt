package com.jossephus.chuchu.service.ssh

import com.jossephus.chuchu.model.AuthMethod
import java.io.Closeable

class NativeSshService(
    private val bridge: NativeSshBridge = NativeSshBridge(),
    private val hostKeyPolicy: HostKeyPolicy,
) : Closeable {
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
                        "Tailscale SSH authentication was not accepted by the server. This app currently supports direct tailnet connections only (no userspace proxy fallback).",
                    )
                }
            }
            AuthMethod.Password -> {
                if (!bridge.nativeAuthenticatePassword(handle, password)) {
                    throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH password auth failed")
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
            val written = bridge.nativeWrite(handle, chunk)
            if (written < 0) {
                throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Native SSH write failed")
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
        return bridge.nativeRead(handle, maxBytes)
    }

    override fun close() {
        if (handle == 0L) return
        bridge.nativeClose(handle)
        bridge.nativeDestroySession(handle)
        handle = 0L
    }
}

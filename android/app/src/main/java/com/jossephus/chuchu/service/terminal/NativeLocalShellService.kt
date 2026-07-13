package com.jossephus.chuchu.service.terminal

import java.io.Closeable
import java.io.File

class NativeLocalShellService(
    private val homeDir: File,
    private val tempDir: File,
    private val bridge: NativeLocalShellBridge = NativeLocalShellBridge(),
    private val command: String = "/system/bin/sh",
) : Closeable {
    private var handle: Long = 0L

    fun start(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        require(bridge.isLoaded()) { "Native local shell unavailable: ${bridge.nativeStatus()}" }
        homeDir.mkdirs()
        tempDir.mkdirs()
        close()
        handle = bridge.nativeCreateSession()
        check(handle != 0L) { "Failed to create native local shell session" }
        val ok =
            bridge.nativeStart(
                handle = handle,
                command = command,
                homeDir = homeDir.absolutePath,
                tempDir = tempDir.absolutePath,
                cols = cols,
                rows = rows,
                widthPx = widthPx,
                heightPx = heightPx,
                term = "xterm-ghostty",
            )
        if (!ok) {
            val message = bridge.nativeGetLastError(handle) ?: "Native local shell start failed"
            close()
            throw IllegalStateException(message)
        }
    }

    fun read(maxBytes: Int): ByteArray? {
        if (handle == 0L) return null
        return bridge.nativeRead(handle, maxBytes.coerceAtLeast(1))
    }

    fun write(data: ByteArray) {
        check(handle != 0L) { "Local shell not open" }
        if (data.isEmpty()) return
        val written = bridge.nativeWrite(handle, data)
        if (written < 0) {
            throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Local shell write failed")
        }
        check(written == data.size) { "Local shell write incomplete" }
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (handle == 0L) return
        if (!bridge.nativeResize(handle, cols, rows, widthPx, heightPx)) {
            throw IllegalStateException(bridge.nativeGetLastError(handle) ?: "Local shell resize failed")
        }
    }

    override fun close() {
        val localHandle = handle
        handle = 0L
        if (localHandle != 0L) {
            bridge.nativeDestroySession(localHandle)
        }
    }
}

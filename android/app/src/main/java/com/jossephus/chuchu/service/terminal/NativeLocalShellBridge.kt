package com.jossephus.chuchu.service.terminal

class NativeLocalShellBridge {
    companion object {
        private val loadError: Throwable? =
            runCatching { System.loadLibrary("chuchu_jni") }.exceptionOrNull()
    }

    fun isLoaded(): Boolean = loadError == null

    fun isAvailable(): Boolean {
        if (loadError != null) return false
        return runCatching { nativeIsSupported() }.getOrDefault(false)
    }

    fun nativeStatus(): String {
        return if (loadError == null) {
            if (isAvailable()) "loaded" else "loaded without local shell support"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }
    }

    external fun nativeIsSupported(): Boolean

    external fun nativeCreateSession(): Long

    external fun nativeDestroySession(handle: Long)

    external fun nativeStart(
        handle: Long,
        command: String,
        homeDir: String,
        tempDir: String,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String,
    ): Boolean

    external fun nativeRead(handle: Long, maxBytes: Int): ByteArray?

    external fun nativeWrite(handle: Long, data: ByteArray): Int

    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ): Boolean

    external fun nativeGetLastError(handle: Long): String?
}

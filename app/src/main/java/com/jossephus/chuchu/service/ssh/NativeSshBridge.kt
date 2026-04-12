package com.jossephus.chuchu.service.ssh

class NativeSshBridge {
    companion object {
        private val loadError: Throwable? = runCatching {
            System.loadLibrary("chuchu_jni")
        }.exceptionOrNull()
    }

    fun isLoaded(): Boolean = loadError == null

    fun nativeStatus(): String {
        return if (loadError == null) {
            "loaded"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }
    }

    external fun nativeCreateSession(): Long
    external fun nativeDestroySession(handle: Long)
    external fun nativeConnect(handle: Long, host: String, port: Int, username: String): Boolean
    external fun nativeGetLastError(handle: Long): String?
    external fun nativeGetHostKey(handle: Long): ByteArray?
    external fun nativeGetHostKeyAlgorithm(handle: Long): String?
    external fun nativeAuthenticateNone(handle: Long): Boolean
    external fun nativeAuthenticatePassword(handle: Long, password: String): Boolean
    external fun nativeAuthenticatePublicKey(handle: Long, keyPath: String, passphrase: String?): Boolean
    external fun nativeOpenShell(handle: Long, cols: Int, rows: Int, widthPx: Int, heightPx: Int, term: String): Boolean
    external fun nativeResize(handle: Long, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean
    external fun nativeWrite(handle: Long, data: ByteArray): Int
    external fun nativeRead(handle: Long, maxBytes: Int): ByteArray?
    external fun nativeClose(handle: Long)
}

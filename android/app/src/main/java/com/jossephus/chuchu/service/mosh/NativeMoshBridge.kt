package com.jossephus.chuchu.service.mosh

class NativeMoshBridge {
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

    external fun nativeCreate(configJson: String): Long
    external fun nativeStart(handle: Long): Int
    external fun nativeSendInput(handle: Long, data: ByteArray): Int
    external fun nativeResize(handle: Long, cols: Int, rows: Int): Int
    external fun nativeMaintenanceTick(handle: Long): Int
    external fun nativePumpNetwork(handle: Long): Int

    /// outBuf: byte array to receive payload (host bytes or diagnostic text).
    /// outMeta: long[5] array receiving [eventType, written, cols, rows, echoAck].
    /// Returns 0 on success, non-zero on error.
    external fun nativePollOutput(handle: Long, outBuf: ByteArray, outMeta: LongArray): Int

    /// outState: long[7] array receiving [state, lastFailure, lastSent, lastReceived, pendingOut, pendingHostOps, rtoMs].
    /// Returns 0 on success.
    external fun nativePollState(handle: Long, outState: LongArray): Int

    external fun nativeStop(handle: Long): Int
    external fun nativeDestroy(handle: Long): Int
}

package com.jossephus.chuchu.service.backup

class NativeBackupBridge {
    fun isLoaded(): Boolean = false

    fun nativeEncrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray? = null

    fun nativeDecrypt(ciphertext: ByteArray, passphrase: CharArray): ByteArray? = null

    companion object {
        const val STATUS_OK: Int = 0
        const val STATUS_INVALID_PASSPHRASE: Int = 1
        const val STATUS_FORMAT_ERROR: Int = 2
    }
}

package com.jossephus.chuchu.service.ssh

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest

class HostKeyStore(
    private val prefs: SharedPreferences,
) {
    fun loadKey(host: String, port: Int, algorithm: String): ByteArray? {
        val encoded = prefs.getString(key(host, port, algorithm), null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    fun saveKey(host: String, port: Int, algorithm: String, keyBytes: ByteArray) {
        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        prefs.edit().putString(key(host, port, algorithm), encoded).apply()
    }

    fun fingerprintSha256(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val encoded = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "SHA256:$encoded"
    }

    private fun key(host: String, port: Int, algorithm: String): String =
        "$host:$port:$algorithm"
}

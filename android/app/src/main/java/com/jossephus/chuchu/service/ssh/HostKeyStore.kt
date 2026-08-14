package com.jossephus.chuchu.service.ssh

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest

class HostKeyStore(
    private val prefs: SharedPreferences,
) {
    companion object {
        const val PREFS_NAME = "host_keys"
    }

    fun loadKey(host: String, port: Int, algorithm: String): ByteArray? {
        val encoded = prefs.getString(key(host, port, algorithm), null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    fun saveKey(host: String, port: Int, algorithm: String, keyBytes: ByteArray) {
        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        prefs.edit().putString(key(host, port, algorithm), encoded).apply()
    }

    fun check(
        host: String,
        port: Int,
        algorithm: String,
        keyBytes: ByteArray,
    ): HostKeyCheck {
        val existing = loadKey(host, port, algorithm)
        return when {
            existing == null -> HostKeyCheck.Unknown(fingerprint = fingerprintSha256(keyBytes))
            existing.contentEquals(keyBytes) -> HostKeyCheck.Match
            else ->
                HostKeyCheck.Changed(
                    previousFingerprint = fingerprintSha256(existing),
                    fingerprint = fingerprintSha256(keyBytes),
                )
        }
    }

    private fun key(host: String, port: Int, algorithm: String): String =
        "$host:$port:$algorithm"

    private fun fingerprintSha256(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val encoded = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$encoded"
    }
}

sealed interface HostKeyCheck {
    data object Match : HostKeyCheck
    data class Unknown(val fingerprint: String) : HostKeyCheck
    data class Changed(
        val previousFingerprint: String,
        val fingerprint: String,
    ) : HostKeyCheck
}

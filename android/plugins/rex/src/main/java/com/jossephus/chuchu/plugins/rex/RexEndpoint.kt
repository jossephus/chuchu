package com.jossephus.chuchu.plugins.rex

import java.net.URI
import org.json.JSONException
import org.json.JSONObject

data class RexEndpoint(
    val host: String,
    val port: Int,
    val secure: Boolean,
) {
    val url: String
        get() = "${if (secure) "https" else "http"}://$authority"

    fun webSocketUrl(path: String): String = "${if (secure) "wss" else "ws"}://$authority$path"

    private val authority: String
        get() {
            val hostPart = if (':' in host) "[$host]" else host
            return if (secure && port == HTTPS_PORT) hostPart else "$hostPart:$port"
        }

    companion object {
        const val HTTPS_PORT = 443
        const val PLAIN_PORT = 7297

        fun parse(raw: String): RexEndpoint? {
            val text = raw.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val hasScheme = text.contains("://")
            val uri =
                try {
                    URI(if (hasScheme) text else "https://$text")
                } catch (_: Exception) {
                    return null
                }
            if (uri.rawUserInfo != null) return null
            val path = uri.rawPath.orEmpty()
            if (path.isNotEmpty() && path != "/") return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null
            val explicitPort = uri.port.takeIf { it != -1 }
            if (explicitPort != null && explicitPort !in 1..65535) return null
            val secure =
                when (uri.scheme?.lowercase()) {
                    "https", "wss" -> true
                    "http", "ws" -> false
                    else -> return null
                }.let { schemeSecure ->
                    if (hasScheme) schemeSecure else explicitPort == null || explicitPort == HTTPS_PORT
                }
            val port = explicitPort ?: if (secure) HTTPS_PORT else PLAIN_PORT
            return RexEndpoint(host = host, port = port, secure = secure)
        }

        fun fromPairingCode(raw: String): RexEndpoint? {
            val text = raw.trim()
            if (text.startsWith("{")) {
                val url =
                    try {
                        JSONObject(text).optString("url")
                    } catch (_: JSONException) {
                        return null
                    }
                return url.takeIf { it.isNotBlank() }?.let(::parse)
            }
            if (!text.contains("://")) return null
            return parse(text)
        }
    }
}

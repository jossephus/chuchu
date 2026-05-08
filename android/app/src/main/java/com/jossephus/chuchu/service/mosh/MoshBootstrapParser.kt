package com.jossephus.chuchu.service.mosh

/**
 * Parses the output of `mosh-server new` to extract the connection endpoint.
 *
 * Expected line format: `MOSH CONNECT <port> <key>`
 * - `port`: UInt16, must be > 0
 * - `key`: exactly 22 chars, charset [A-Za-z0-9+/-_]
 *
 * Handles noisy output before/after the connect line. If multiple connect lines
 * exist, the first valid one is accepted.
 */
object MoshBootstrapParser {

    data class MoshEndpoint(
        val host: String,
        val port: Int,
        val key: String,
    )

    sealed class ParseResult {
        data class Success(val endpoint: MoshEndpoint) : ParseResult()
        data class Error(val reason: String) : ParseResult()
    }

    private val CONNECT_LINE_REGEX =
        Regex("""MOSH CONNECT\s+(\d+)\s+([A-Za-z0-9+/_-]{22})""")
    private const val KEY_LENGTH = 22

    fun parse(host: String, output: String): ParseResult {
        // Normalize CRLF to LF before scanning
        val normalized = output.replace("\r\n", "\n")
        val lines = normalized.split('\n')

        for (line in lines) {
            val trimmed = line.trim()
            val match = CONNECT_LINE_REGEX.find(trimmed)
            if (match != null) {
                val portStr = match.groupValues[1]
                val key = match.groupValues[2]

                val port = portStr.toIntOrNull()
                    ?: return ParseResult.Error("Invalid port: '$portStr'")
                if (port <= 0 || port > 65535) {
                    return ParseResult.Error("Port out of range: $port")
                }
                if (key.length != KEY_LENGTH) {
                    return ParseResult.Error("Key length invalid: ${key.length}")
                }
                return ParseResult.Success(
                    MoshEndpoint(host = host, port = port, key = key)
                )
            }
        }

        return ParseResult.Error(
            if (normalized.contains("mosh-server", ignoreCase = true)) {
                "Server output missing valid MOSH CONNECT line"
            } else {
                "mosh-server not found on remote host"
            }
        )
    }
}

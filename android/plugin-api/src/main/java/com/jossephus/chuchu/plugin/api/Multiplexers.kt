package com.jossephus.chuchu.plugin.api

data class RemoteMultiplexerSession(
    val name: String,
    val attached: Boolean,
)

/**
 * Every method returns a shell command or parses its output; chuchu runs the commands.
 * Quote interpolated values with [Shell.quote].
 */
interface MultiplexerProvider {
    /** Persisted in host profiles and backups; never change it. */
    val id: String

    /** Exits 0 when the multiplexer is installed. */
    fun availabilityCommand(): String

    /** Must exit 0 even when there are no sessions. */
    fun listSessionsCommand(): String

    fun parseSessions(output: String): List<RemoteMultiplexerSession>

    fun launchCommand(sessionName: String, createIfMissing: Boolean): String
}

object Shell {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

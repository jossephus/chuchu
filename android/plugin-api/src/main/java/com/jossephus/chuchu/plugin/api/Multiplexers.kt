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

    val displayName: String

    /** Exits 0 when the multiplexer is installed. */
    fun availabilityCommand(): String

    /** Must exit 0 even when there are no sessions. */
    fun listSessionsCommand(): String

    fun parseSessions(output: String): List<RemoteMultiplexerSession>

    /**
     * @param trustedRemoteName the name came from [parseSessions], so it may contain any
     *   characters.
     */
    fun launchCommand(sessionName: String, createIfMissing: Boolean, trustedRemoteName: Boolean): String

    /** What chuchu calls; override to read per-host settings. */
    fun launchCommand(launch: MultiplexerLaunch): String =
        launchCommand(launch.sessionName, launch.createIfMissing, launch.trustedRemoteName)

    fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String
}

class MultiplexerLaunch(
    val sessionName: String,
    val createIfMissing: Boolean,
    val trustedRemoteName: Boolean,
    val hostId: Long?,
)

object MultiplexerSessionNames {
    /** Lowest `chuchu-N` not used remotely or by another open tab. */
    fun next(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val used = remoteSessions.mapTo(HashSet()) { it.name } + localSessionNames
        var index = 1
        while ("chuchu-$index" in used) index += 1
        return "chuchu-$index"
    }
}

object Shell {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

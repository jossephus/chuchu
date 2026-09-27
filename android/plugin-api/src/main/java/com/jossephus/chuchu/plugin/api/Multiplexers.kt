package com.jossephus.chuchu.plugin.api

/** A session a multiplexer reports on the remote host. */
data class RemoteMultiplexerSession(
    val name: String,
    /** Whether some client (chuchu or otherwise) is currently attached. */
    val attached: Boolean,
)

/**
 * A terminal multiplexer chuchu can keep sessions alive with (tmux, zmx, ...). Once
 * registered, it appears under "session persistence" when adding a host, and chuchu uses
 * it to list, create and attach sessions over SSH.
 *
 * Every method returns a shell command or parses its output. Chuchu runs the commands
 * itself (exec channel for queries, the tab's PTY for [launchCommand]), so providers never
 * touch the connection directly. Commands run under the remote user's login shell; quote
 * every interpolated value with [Shell.quote].
 */
interface MultiplexerProvider {
    /**
     * Stable id persisted in host profiles and backups, e.g. "tmux". Never change it: hosts
     * saved with the old id would silently lose their multiplexer.
     */
    val id: String

    /** Label shown in the host editor, e.g. "tmux". */
    val displayName: String

    /** Exits 0 when the multiplexer is installed on the remote. */
    fun availabilityCommand(): String

    /** Prints sessions for [parseSessions]; exits 0 even when there are none. */
    fun listSessionsCommand(): String

    fun parseSessions(output: String): List<RemoteMultiplexerSession>

    /**
     * Command sent to the tab's PTY to enter [sessionName].
     *
     * @param createIfMissing create the session if it doesn't exist; otherwise fall back to
     *   a login shell with a message when it's gone.
     * @param trustedRemoteName the name came from [parseSessions] (a real remote session)
     *   rather than from [defaultSessionName], so it may contain any characters.
     */
    fun launchCommand(sessionName: String, createIfMissing: Boolean, trustedRemoteName: Boolean): String

    /** Name for a new session. [MultiplexerSessionNames.next] gives chuchu's convention. */
    fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String
}

/** Chuchu's session naming convention (`chuchu-1`, `chuchu-2`, ...), shared by providers. */
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
    /** Single-quotes [value] for POSIX shells, so it's passed as one literal argument. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

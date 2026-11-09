package com.jossephus.chuchu.plugin.api

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class SessionStatus {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Error,
}

enum class SessionTransport {
    Ssh,
    TailscaleSsh,
    Mosh,
    LocalShell,
    Plugin,
}

/** What a plugin may know about a session's host. Deliberately carries no credentials. */
data class HostInfo(
    /** Saved host profile id, or null for ad-hoc and local-shell sessions. */
    val hostId: Long?,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val transport: SessionTransport,
    /** Multiplexer id (e.g. "tmux", "zmx") the session runs under, or null. */
    val multiplexer: String?,
    /** Name of the multiplexer session this tab is attached to, when known. */
    val multiplexerSession: String? = null,
    val transportId: String? = null,
)

/** A terminal tab's session, as seen by plugins. */
interface PluginSession {
    /** Stable for the lifetime of the tab. */
    val id: String

    /** Current host details; may change when the tab switches multiplexer session. */
    val host: HostInfo

    val status: StateFlow<SessionStatus>

    /**
     * Runs [command] on the session's existing SSH connection, in its own channel next to
     * the shell (no second login).
     *
     * Returns null when the session isn't SSH (Mosh, local shell) or isn't connected.
     * Throws if the server refuses the channel. Every open channel is closed when the
     * session disconnects or reconnects; reopen after [SessionEvent.StatusChanged] to
     * [SessionStatus.Connected].
     */
    suspend fun exec(command: String): ExecChannel?
}

/** Terminal grid size. Pixel sizes are optional (0 = unknown). */
data class PtySize(val cols: Int, val rows: Int, val widthPx: Int = 0, val heightPx: Int = 0)

/**
 * A command running in its own SSH channel. Close it when done; channels hold server
 * resources until closed.
 *
 * [stdout] and [stderr] are single-collector flows that complete at end of output. Output
 * is only read from the network while there's buffer room, so a stream nobody collects
 * eventually pauses the command through SSH flow control, like a full pipe would.
 */
interface ExecChannel : AutoCloseable {
    val stdout: Flow<ByteArray>
    val stderr: Flow<ByteArray>

    /**
     * Completes when the channel finishes: the exit status the server reported (0 if it
     * reported none), or null if the channel was closed or the connection dropped first.
     */
    val exitCode: Deferred<Int?>

    /** Writes all of [bytes] to the command's stdin. Throws if the channel is closed. */
    suspend fun write(bytes: ByteArray)

    /** Closes the channel; safe to call more than once. */
    override fun close()
}

sealed interface SessionEvent {
    val session: PluginSession

    /**
     * The tab was closed. It is not necessarily preceded by [StatusChanged] to
     * [SessionStatus.Disconnected]: closing removes the tab before its connection winds
     * down, so treat this as the end of the session.
     */
    data class Closed(override val session: PluginSession) : SessionEvent

    data class StatusChanged(
        override val session: PluginSession,
        val status: SessionStatus,
        /** Set when [status] is [SessionStatus.Error] or a disconnect carried a reason. */
        val error: String?,
        /** Current attempt when [status] is [SessionStatus.Reconnecting], otherwise 0. */
        val reconnectAttempt: Int,
    ) : SessionEvent
}

interface SessionRegistry {
    /** Open terminal tabs, in tab order. */
    val sessions: StateFlow<List<PluginSession>>

    /**
     * Lifecycle and state events across all sessions. Hot: events from before collection
     * are not replayed. A collector that falls far behind loses the oldest events, so keep
     * handlers quick and move slow work into another coroutine.
     */
    val events: SharedFlow<SessionEvent>
}

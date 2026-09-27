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
)

/**
 * One chunk of a byte stream from [PluginSession.output] or [PluginSession.input].
 *
 * Streams never block the terminal: each subscriber has a bounded buffer, and when it falls
 * behind the oldest chunks are dropped. The subscriber then receives a [Gap] before the next
 * [Data], so parsers can resynchronize instead of silently misreading the stream.
 */
sealed interface ByteStreamEvent {
    /** Bytes owned by this subscriber; safe to keep or mutate. */
    class Data(val bytes: ByteArray) : ByteStreamEvent

    /** [droppedBytes] bytes were lost between the previous [Data] and the next one. */
    data class Gap(val droppedBytes: Long) : ByteStreamEvent
}

/** A terminal tab's session, as seen by plugins. */
interface PluginSession {
    /** Stable for the lifetime of the tab. */
    val id: String

    /** Current host details; may change when the tab switches multiplexer session. */
    val host: HostInfo

    val status: StateFlow<SessionStatus>
    val title: StateFlow<String?>
    val pwd: StateFlow<String?>

    /**
     * Raw bytes exactly as fed into the terminal emulator (after transport decoding, so the
     * same for SSH, Mosh and local shells). Starts at subscription time; there is no replay.
     * Completes when the tab closes.
     *
     * [capacity] is the number of chunks buffered for this subscriber before the oldest are
     * dropped (see [ByteStreamEvent.Gap]). Chunks are typically up to 64 KiB.
     */
    fun output(capacity: Int = DEFAULT_OUTPUT_CAPACITY): Flow<ByteStreamEvent>

    /**
     * Bytes produced by the user (keys, text, paste) after terminal encoding, as sent to the
     * remote. Excludes terminal query replies and writes made by plugins. Observe-only.
     * Completes when the tab closes.
     */
    fun input(capacity: Int = DEFAULT_INPUT_CAPACITY): Flow<ByteStreamEvent>

    /** Sends raw bytes to the remote as if typed. Not echoed to [input]. */
    fun write(bytes: ByteArray)

    /** Sends [text] as UTF-8. Not paste-encoded; use it for commands, not user pastes. */
    fun writeText(text: String)

    /**
     * Runs [command] on the session's existing SSH connection, in its own channel next to
     * the shell (no second login). Pass [pty] for interactive programs that need a
     * terminal, e.g. streaming a multiplexer pane into a terminal from `TerminalFactory`.
     *
     * Returns null when the session isn't SSH (Mosh, local shell) or isn't connected.
     * Throws if the server refuses the channel. Every open channel is closed when the
     * session disconnects or reconnects; reopen after [SessionEvent.StatusChanged] to
     * [SessionStatus.Connected].
     */
    suspend fun exec(command: String, pty: PtySize? = null): ExecChannel?

    companion object {
        const val DEFAULT_OUTPUT_CAPACITY: Int = 256
        const val DEFAULT_INPUT_CAPACITY: Int = 64
    }
}

/** Terminal size for a PTY exec channel. Pixel sizes are optional (0 = unknown). */
data class PtySize(val cols: Int, val rows: Int, val widthPx: Int = 0, val heightPx: Int = 0)

/**
 * A command running in its own SSH channel. Close it when done; channels hold server
 * resources until closed.
 *
 * [stdout] and [stderr] are single-collector flows that complete at end of output. Output
 * is only read from the network while there's buffer room, so a stream nobody collects
 * eventually pauses the command through SSH flow control, like a full pipe would. With a
 * PTY the remote merges stderr into stdout.
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

    /** Closes the command's stdin, for programs that read until EOF. */
    suspend fun sendEof()

    /** Resizes the PTY; no effect for channels opened without one. */
    suspend fun resize(size: PtySize)

    /** Closes the channel; safe to call more than once. */
    override fun close()
}

sealed interface SessionEvent {
    val session: PluginSession

    data class Opened(override val session: PluginSession) : SessionEvent

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

    data class TitleChanged(override val session: PluginSession, val title: String?) : SessionEvent

    data class PwdChanged(override val session: PluginSession, val pwd: String?) : SessionEvent

    /** The terminal rang the bell (BEL) [count] times since the previous [Bell]. */
    data class Bell(override val session: PluginSession, val count: Int) : SessionEvent
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

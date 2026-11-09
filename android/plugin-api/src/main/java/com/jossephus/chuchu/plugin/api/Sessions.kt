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

data class HostInfo(
    /** Null for ad-hoc and local-shell sessions. */
    val hostId: Long?,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val transport: SessionTransport,
    val multiplexer: String?,
    val multiplexerSession: String? = null,
    val transportId: String? = null,
)

interface PluginSession {
    val id: String

    /** May change when the tab switches multiplexer session. */
    val host: HostInfo

    val status: StateFlow<SessionStatus>

    /**
     * Runs [command] in a new channel on the session's SSH connection. Returns null when the
     * session isn't a connected SSH session. Channels close when the session disconnects or
     * reconnects.
     */
    suspend fun exec(command: String): ExecChannel?
}

/** Pixel sizes are optional (0 = unknown). */
data class PtySize(val cols: Int, val rows: Int, val widthPx: Int = 0, val heightPx: Int = 0)

/**
 * [stdout] and [stderr] are single-collector flows. Output is only read while there's buffer
 * room, so an uncollected stream eventually pauses the command.
 */
interface ExecChannel : AutoCloseable {
    val stdout: Flow<ByteArray>
    val stderr: Flow<ByteArray>

    /** Null when the channel was closed or the connection dropped before the command exited. */
    val exitCode: Deferred<Int?>

    suspend fun write(bytes: ByteArray)

    override fun close()
}

sealed interface SessionEvent {
    val session: PluginSession

    /** Not necessarily preceded by a [StatusChanged] to [SessionStatus.Disconnected]. */
    data class Closed(override val session: PluginSession) : SessionEvent

    data class StatusChanged(
        override val session: PluginSession,
        val status: SessionStatus,
        val error: String?,
        /** Non-zero only while [status] is [SessionStatus.Reconnecting]. */
        val reconnectAttempt: Int,
    ) : SessionEvent
}

interface SessionRegistry {
    /** In tab order. */
    val sessions: StateFlow<List<PluginSession>>

    /** Hot, no replay; a slow collector loses the oldest events. */
    val events: SharedFlow<SessionEvent>
}

package com.jossephus.chuchu.plugins.herdr

import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.PluginTerminal
import com.jossephus.chuchu.plugin.api.TerminalFactory
import com.jossephus.chuchu.plugin.api.TerminalOptions
import com.jossephus.chuchu.plugins.herdr.protocol.FrameDisposition
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrMultiplexer
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrStreamMessage
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrStreamMode
import com.jossephus.chuchu.plugins.herdr.protocol.appendHerdrNdjsonChunk
import com.jossephus.chuchu.plugins.herdr.protocol.frameDisposition
import com.jossephus.chuchu.plugins.herdr.protocol.herdrInputBytesJson
import com.jossephus.chuchu.plugins.herdr.protocol.herdrResizeJson
import com.jossephus.chuchu.plugins.herdr.protocol.herdrScrollCommand
import com.jossephus.chuchu.plugins.herdr.protocol.herdrScrollJson
import com.jossephus.chuchu.plugins.herdr.protocol.parseHerdrStreamMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HerdrPaneStatus {
    Connecting,
    Streaming,
    Error,
}

data class HerdrPaneState(
    val status: HerdrPaneStatus = HerdrPaneStatus.Connecting,
    /** Another client controls the pane; input is ignored until the user takes over. */
    val readOnly: Boolean = false,
    val error: String? = null,
)

/**
 * One herdr pane rendered by chuchu: `herdr terminal session control|observe` on an exec
 * channel of the tab's own SSH connection, fed into a chuchu [PluginTerminal].
 *
 * herdr re-renders the pane server-side at the size we ask for and sends ndjson frames;
 * keys, resizes and scrolls go back as ndjson commands on stdin. Adapted from
 * `HerdrPaneHost` in chuchu PR #69, which ran this over a second SSH connection with its own
 * Ghostty handle.
 */
class HerdrPane(
    val paneId: String,
    private val session: PluginSession,
    private val herdrSession: String?,
    terminals: TerminalFactory,
    private val scope: CoroutineScope,
) {
    // herdr keeps scrollback on the server; gestures scroll there instead of locally.
    val terminal: PluginTerminal =
        terminals.create(TerminalOptions(scrollbackLines = 0, onScroll = ::scroll))

    private val _state = MutableStateFlow(HerdrPaneState())
    val state: StateFlow<HerdrPaneState> = _state.asStateFlow()

    // Commands for the current stream only; a restart drops what the old stream didn't send.
    private val outgoing = Channel<String>(Channel.UNLIMITED)
    @Volatile private var mode = HerdrStreamMode.Control
    private var streamJob: Job? = null
    private val job: Job =
        scope.launch {
            launch { forwardInput() }
            // Re-request the pane at the view's size; the first size is sent when the
            // stream opens.
            launch { terminal.size.drop(1).collect { send(herdrResizeJson(it.cols, it.rows), allowReadOnly = true) } }
            restart()
        }

    /** Asks herdr for control even though another client (e.g. the desktop TUI) is attached. */
    fun takeover() {
        mode = HerdrStreamMode.ControlTakeover
        scope.launch { restart() }
    }

    fun close() {
        job.cancel()
        streamJob?.cancel()
        outgoing.close()
        terminal.close()
    }

    private suspend fun restart() {
        streamJob?.cancel()
        while (outgoing.tryReceive().isSuccess) Unit
        streamJob = scope.launch(Dispatchers.Default) { streamWithRetry() }
    }

    private suspend fun streamWithRetry() {
        var retryDelayMs = INITIAL_RETRY_MS
        while (currentCoroutineIsActive()) {
            _state.value = _state.value.copy(status = HerdrPaneStatus.Connecting, error = null)
            val failure =
                try {
                    streamOnce()
                    retryDelayMs = INITIAL_RETRY_MS
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RestartStream) {
                    null
                } catch (e: Exception) {
                    e.message ?: "herdr pane stream failed"
                }
            if (failure != null) {
                _state.value = _state.value.copy(status = HerdrPaneStatus.Error, error = failure)
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    /** Returns to restart (sequence gap, control refused); throws on failure. */
    private suspend fun streamOnce() = coroutineScope {
        val size = terminal.size.value
        val channel =
            session.exec(HerdrMultiplexer.terminalSessionCommand(paneId, size.cols, size.rows, mode, herdrSession))
                ?: throw IllegalStateException("session is not connected")
        try {
            val writer = launch { for (line in outgoing) channel.write((line + "\n").toByteArray()) }
            readFrames(channel)
            writer.cancel()
        } finally {
            channel.close()
        }
    }

    private suspend fun readFrames(channel: ExecChannel) {
        val buffer = StringBuilder()
        var lastSeq: Long? = null
        var gridCols = 0
        var gridRows = 0
        channel.stdout.collect { chunk ->
            for (line in appendHerdrNdjsonChunk(buffer, String(chunk, Charsets.UTF_8))) {
                when (val message = parseHerdrStreamMessage(line)) {
                    is HerdrStreamMessage.Frame -> {
                        val frame = message.value
                        if (frameDisposition(lastSeq, frame) == FrameDisposition.Restart) {
                            throw RestartStream()
                        }
                        if (frame.width != gridCols || frame.height != gridRows) {
                            gridCols = frame.width
                            gridRows = frame.height
                            terminal.setGridSize(gridCols, gridRows)
                        }
                        terminal.feed(frame.decodedBytes())
                        lastSeq = frame.seq
                        if (_state.value.status != HerdrPaneStatus.Streaming) {
                            _state.value =
                                HerdrPaneState(HerdrPaneStatus.Streaming, readOnly = mode == HerdrStreamMode.Observe)
                        }
                    }
                    is HerdrStreamMessage.Closed -> {
                        val reason = message.value.reason
                        // Another client holds control: watch read-only until the user takes over.
                        if (mode == HerdrStreamMode.Control && reason.contains("attached client", ignoreCase = true)) {
                            mode = HerdrStreamMode.Observe
                            throw RestartStream()
                        }
                        throw IllegalStateException(reason.ifBlank { "herdr closed the pane stream" })
                    }
                    null -> Unit
                }
            }
        }
        throw IllegalStateException("herdr pane stream ended")
    }

    private suspend fun forwardInput() {
        terminal.input.collect { bytes -> send(herdrInputBytesJson(bytes)) }
    }

    private fun scroll(lines: Int) {
        if (lines == 0) return
        val (direction, count) = herdrScrollCommand(lines)
        send(herdrScrollJson(direction, count), allowReadOnly = true)
    }

    private fun send(json: String, allowReadOnly: Boolean = false) {
        if (!allowReadOnly && mode == HerdrStreamMode.Observe) return
        outgoing.trySend(json)
    }

    private suspend fun currentCoroutineIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    /** Not an error: the stream needs reopening (sequence gap or a mode change). */
    private class RestartStream : Exception()

    private companion object {
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
    }
}

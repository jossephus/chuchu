package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.service.multiplexer.FrameDisposition
import com.jossephus.chuchu.service.multiplexer.HerdrScrollDirection
import com.jossephus.chuchu.service.multiplexer.HerdrStreamMessage
import com.jossephus.chuchu.service.multiplexer.HerdrStreamMode
import com.jossephus.chuchu.service.multiplexer.appendHerdrNdjsonChunk
import com.jossephus.chuchu.service.multiplexer.frameDisposition
import com.jossephus.chuchu.service.multiplexer.herdrInputBytesJson
import com.jossephus.chuchu.service.multiplexer.herdrInputTextJson
import com.jossephus.chuchu.service.multiplexer.herdrResizeJson
import com.jossephus.chuchu.service.multiplexer.herdrScrollCommand
import com.jossephus.chuchu.service.multiplexer.herdrScrollJson
import com.jossephus.chuchu.service.multiplexer.parseHerdrStreamMessage
import com.jossephus.chuchu.service.ssh.SharedSshConnection
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class HerdrPaneStreamStatus {
    Connecting,
    Streaming,
    Error,
    Disposed,
}

data class HerdrPaneState(
    val snapshot: TerminalSnapshot? = null,
    val handle: Long = 0,
    val status: HerdrPaneStreamStatus = HerdrPaneStreamStatus.Connecting,
    val readOnly: Boolean = false,
    val error: String? = null,
)

class HerdrPaneHost(
    val paneId: String,
    private val connection: SharedSshConnection,
    private val runCommand: (HerdrStreamMode, Int, Int) -> String,
    private val scope: CoroutineScope,
) {
    private enum class StreamEnd {
        Restart,
        ControlRefused,
    }

    private data class PendingCommand(
        val channelId: Int,
        val json: String,
    )

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
                Thread(r, "herdr-pane").apply { isDaemon = true }
            }
            .asCoroutineDispatcher()
    private val bridge = GhosttyBridge()
    private val pendingCommands = Channel<PendingCommand>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(HerdrPaneState())
    val state: StateFlow<HerdrPaneState> = _state.asStateFlow()

    @Volatile private var disposed = false
    @Volatile private var streamChannelId: Int? = null
    private var streamJob: Job? = null
    private var handle = 0L
    private var cols = 80
    private var rows = 24
    private var cellWidth = 1
    private var cellHeight = 1
    private var vtCols = 80
    private var vtRows = 24
    private var streamMode = HerdrStreamMode.Observe
    private var lastSnapshotAtMs = 0L
    private var snapshotScheduled = false

    fun start(cols: Int, rows: Int, mode: HerdrStreamMode) {
        scope.launch(dispatcher) {
            if (disposed || cols <= 0 || rows <= 0) return@launch
            this@HerdrPaneHost.cols = cols
            this@HerdrPaneHost.rows = rows
            streamMode = mode
            if (handle == 0L) {
                handle = bridge.nativeCreate(cols, rows, 0)
                vtCols = cols
                vtRows = rows
                _state.value = _state.value.copy(handle = handle)
            }
            restartStream()
        }
    }

    fun setMode(mode: HerdrStreamMode) {
        scope.launch(dispatcher) {
            if (disposed || streamMode == mode) return@launch
            streamMode = mode
            _state.value = _state.value.copy(readOnly = mode == HerdrStreamMode.Observe, error = null)
            restartStream()
        }
    }

    fun setViewport(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        scope.launch(dispatcher) {
            if (disposed || cols <= 0 || rows <= 0 || cellWidthPx <= 0 || cellHeightPx <= 0) {
                return@launch
            }
            this@HerdrPaneHost.cols = cols
            this@HerdrPaneHost.rows = rows
            cellWidth = cellWidthPx
            cellHeight = cellHeightPx
            sendCommand(herdrResizeJson(cols, rows), allowReadOnly = true)
        }
    }

    fun writeText(text: String) {
        if (text.isEmpty()) return
        scope.launch(dispatcher) { sendCommand(herdrInputTextJson(text)) }
    }

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded = bridge.nativeEncodeKey(handle, key, codepoint, mods, action, utf8) ?: return@launch
            if (encoded.isNotEmpty()) sendCommand(herdrInputBytesJson(encoded))
        }
    }

    fun writePaste(text: String) {
        if (text.isEmpty()) return
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded = bridge.nativeEncodePaste(handle, text) ?: return@launch
            if (encoded.isNotEmpty()) sendCommand(herdrInputBytesJson(encoded))
        }
    }

    fun sendFocus(focused: Boolean) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded = bridge.nativeEncodeFocus(handle, focused) ?: return@launch
            if (encoded.isNotEmpty()) sendCommand(herdrInputBytesJson(encoded))
        }
    }

    fun scrollLines(lines: Int) {
        if (lines == 0) return
        scope.launch(dispatcher) {
            val (direction, count) = herdrScrollCommand(lines)
            sendCommand(herdrScrollJson(direction, count), allowReadOnly = true)
        }
    }

    fun setColorScheme(isDark: Boolean) {
        scope.launch(dispatcher) {
            if (handle != 0L) bridge.nativeSetColorScheme(handle, if (isDark) 1 else 0)
        }
    }

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            bridge.nativeSetDefaultColors(handle, fg, bg, cursor, palette)
            requestSnapshot(force = true)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        streamJob?.cancel()
        streamJob = null
        runBlocking {
            withContext(dispatcher) {
                closeStreamChannel()
                snapshotScheduled = false
                if (handle != 0L) {
                    bridge.nativeDestroy(handle)
                    handle = 0L
                }
                _state.value = _state.value.copy(handle = 0L, status = HerdrPaneStreamStatus.Disposed)
            }
        }
        dispatcher.close()
    }

    private suspend fun restartStream() {
        streamJob?.cancel()
        streamJob = null
        closeStreamChannel()
        _state.value =
            _state.value.copy(
                status = HerdrPaneStreamStatus.Connecting,
                readOnly = streamMode == HerdrStreamMode.Observe,
                error = null,
            )
        val mode = streamMode
        streamJob = scope.launch(Dispatchers.IO) { stream(mode) }
    }

    private suspend fun stream(initialMode: HerdrStreamMode) {
        var mode = initialMode
        var retryDelayMs = 2_000L
        while (currentCoroutineContext().isActive && !disposed) {
            var channelId: Int? = null
            var failed = false
            try {
                setConnecting(mode)
                val (commandCols, commandRows) = withContext(dispatcher) { cols to rows }
                channelId = connection.openExecChannel(runCommand(mode, commandCols, commandRows))
                streamChannelId = channelId
                retryDelayMs = 2_000L
                when (readFrames(channelId, mode)) {
                    StreamEnd.Restart -> Unit
                    StreamEnd.ControlRefused -> {
                        mode = HerdrStreamMode.Observe
                        setReadOnly()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                setError(error.message ?: "Herdr terminal stream failed")
            } finally {
                if (streamChannelId == channelId) streamChannelId = null
                channelId?.let { id ->
                    withContext(NonCancellable) { runCatching { connection.closeChannel(id) } }
                }
            }
            if (failed && !disposed) {
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun readFrames(channelId: Int, mode: HerdrStreamMode): StreamEnd {
        val buffer = StringBuilder()
        var lastSeq: Long? = null
        while (currentCoroutineContext().isActive && !disposed) {
            writePendingCommands(channelId)
            val chunk = connection.readChannel(channelId, 8192)
            if (chunk != null && chunk.isNotEmpty()) {
                for (line in appendHerdrNdjsonChunk(buffer, chunk.toString(Charsets.UTF_8))) {
                    when (val message = parseHerdrStreamMessage(line)) {
                        is HerdrStreamMessage.Frame -> {
                            val frame = message.value
                            if (frameDisposition(lastSeq, frame) == FrameDisposition.Restart) {
                                return StreamEnd.Restart
                            }
                            applyFrame(frame.width, frame.height, frame.decodedBytes())
                            lastSeq = frame.seq
                        }
                        is HerdrStreamMessage.Closed -> {
                            if (
                                mode == HerdrStreamMode.Control &&
                                    message.value.reason.contains("attached client", ignoreCase = true)
                            ) {
                                return StreamEnd.ControlRefused
                            }
                            throw IllegalStateException(message.value.reason.ifBlank { "Herdr terminal stream closed" })
                        }
                        null -> Unit
                    }
                }
            } else if (connection.channelEof(channelId)) {
                throw IllegalStateException("Herdr terminal stream ended")
            } else {
                delay(25)
            }
        }
        throw CancellationException()
    }

    private suspend fun writePendingCommands(channelId: Int) {
        while (true) {
            val pending = pendingCommands.tryReceive().getOrNull() ?: return
            if (pending.channelId == channelId) {
                connection.writeChannel(channelId, (pending.json + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }

    private suspend fun applyFrame(width: Int, height: Int, bytes: ByteArray) {
        withContext(dispatcher) {
            if (handle == 0L) return@withContext
            if (width != vtCols || height != vtRows) {
                bridge.nativeResize(handle, width, height, cellWidth, cellHeight)
                vtCols = width
                vtRows = height
            }
            bridge.nativeWriteRemote(handle, bytes)
            requestSnapshot()
            _state.value =
                _state.value.copy(
                    handle = handle,
                    status = HerdrPaneStreamStatus.Streaming,
                    readOnly = streamMode == HerdrStreamMode.Observe,
                    error = null,
                )
        }
    }

    private suspend fun setConnecting(mode: HerdrStreamMode) {
        withContext(dispatcher) {
            if (!disposed) {
                _state.value =
                    _state.value.copy(
                        status = HerdrPaneStreamStatus.Connecting,
                        readOnly = mode == HerdrStreamMode.Observe,
                        error = null,
                    )
            }
        }
    }

    private suspend fun setReadOnly() {
        withContext(dispatcher) {
            if (!disposed) {
                streamMode = HerdrStreamMode.Observe
                _state.value = _state.value.copy(readOnly = true, error = null)
            }
        }
    }

    private suspend fun setError(message: String) {
        withContext(dispatcher) {
            if (!disposed) {
                _state.value = _state.value.copy(status = HerdrPaneStreamStatus.Error, error = message)
            }
        }
    }

    private fun sendCommand(json: String, allowReadOnly: Boolean = false) {
        if (disposed || (!allowReadOnly && _state.value.readOnly)) return
        val channelId = streamChannelId ?: return
        pendingCommands.trySend(PendingCommand(channelId, json))
    }

    private suspend fun closeStreamChannel() {
        val channelId = streamChannelId ?: return
        streamChannelId = null
        runCatching { connection.closeChannel(channelId) }
    }

    private fun requestSnapshot(force: Boolean = false) {
        if (handle == 0L) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastSnapshotAtMs
        if (force || elapsed >= SNAPSHOT_INTERVAL_MS) {
            snapshotScheduled = false
            emitSnapshot()
            lastSnapshotAtMs = now
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        val waitMs = (SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(1L)
        scope.launch(dispatcher) {
            delay(waitMs)
            snapshotScheduled = false
            if (handle == 0L) return@launch
            emitSnapshot()
            lastSnapshotAtMs = System.currentTimeMillis()
        }
    }

    private fun emitSnapshot() {
        if (handle == 0L) return
        val raw = bridge.nativeSnapshot(handle)
        val images = TerminalSnapshot.parseImages(bridge.nativeSnapshotImages(handle))
        val snapshot = TerminalSnapshot.fromByteBuffer(raw, images)
        _state.value = _state.value.copy(snapshot = snapshot, handle = handle)
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 16L
    }
}

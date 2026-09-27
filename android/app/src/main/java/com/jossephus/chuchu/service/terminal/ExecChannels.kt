package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.service.ssh.NativeSshService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Native operations on one connection's exec channels; a seam so the pump is testable. */
interface ExecTransport {
    /** Returns the new channel's id; throws on failure. */
    fun open(command: String, pty: PtySize?): Int

    /** Buffered bytes of stdout (0) or stderr (1), empty when none; throws on failure. */
    fun read(id: Int, stream: Int, maxBytes: Int): ByteArray

    /** Bytes accepted (may be 0 while the window is full); throws on failure. */
    fun write(id: Int, data: ByteArray): Int

    fun isEof(id: Int): Boolean

    fun exitStatus(id: Int): Int

    fun sendEof(id: Int): Boolean

    fun resize(id: Int, size: PtySize): Boolean

    fun close(id: Int)
}

class NativeExecTransport(private val ssh: NativeSshService) : ExecTransport {
    override fun open(command: String, pty: PtySize?): Int =
        ssh.execOpen(command, pty != null, pty?.cols ?: 0, pty?.rows ?: 0, pty?.widthPx ?: 0, pty?.heightPx ?: 0)

    override fun read(id: Int, stream: Int, maxBytes: Int): ByteArray = ssh.execRead(id, stream, maxBytes)

    override fun write(id: Int, data: ByteArray): Int = ssh.execWrite(id, data)

    override fun isEof(id: Int): Boolean = ssh.execEof(id)

    override fun exitStatus(id: Int): Int = ssh.execExitStatus(id)

    override fun sendEof(id: Int): Boolean = ssh.execSendEof(id)

    override fun resize(id: Int, size: PtySize): Boolean =
        ssh.execResize(id, size.cols, size.rows, size.widthPx, size.heightPx)

    override fun close(id: Int) = ssh.execClose(id)
}

/**
 * Plugin exec channels on one engine's SSH connection.
 *
 * Every native call runs on [dispatcher], the engine's single session thread, because
 * libssh2 sessions are not thread-safe; that also makes [open] and the pumps race-free
 * without locks. Channel ids restart at 1 on every new native session, so each channel
 * remembers the connection [generation] it was opened on and stops touching the transport
 * once [invalidateAll] moves past it; otherwise a stale handle could reach a new,
 * unrelated channel that reused its id.
 */
class ExecChannels(
    private val transport: ExecTransport,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val open = ArrayList<Exec>()
    private var generation = 0

    suspend fun open(command: String, pty: PtySize?): ExecChannel =
        withContext(dispatcher) {
            val exec = Exec(transport.open(command, pty), generation)
            open += exec
            exec.pump = scope.launch(dispatcher) { exec.runPump() }
            exec
        }

    /**
     * The connection is being closed or replaced, so its native channels are gone. Call
     * on [dispatcher] before the native session is closed or reconnected.
     */
    fun invalidateAll() {
        generation++
        open.toList().forEach { it.finish(exitCode = null, closeNative = false) }
        open.clear()
    }

    private inner class Exec(val id: Int, private val openedIn: Int) : ExecChannel {
        private val stdoutChannel = Channel<ByteArray>(STREAM_BUFFER_CHUNKS)
        private val stderrChannel = Channel<ByteArray>(STREAM_BUFFER_CHUNKS)
        override val stdout: Flow<ByteArray> = stdoutChannel.receiveAsFlow()
        override val stderr: Flow<ByteArray> = stderrChannel.receiveAsFlow()
        override val exitCode = CompletableDeferred<Int?>()
        var pump: Job? = null
        private var finished = false

        private val live: Boolean
            get() = !finished && openedIn == generation

        suspend fun runPump() {
            // A chunk read but not yet accepted by a full stream buffer; while one is
            // pending that stream isn't read, which is what applies SSH flow control.
            val pending = arrayOfNulls<ByteArray>(2)
            var lastProgressAt = clock()
            var eofAt: Long? = null
            try {
                while (live) {
                    if (pumpStreams(pending)) {
                        lastProgressAt = clock()
                        eofAt = null
                        yield()
                        continue
                    }
                    if (pending.all { it == null } && transport.isEof(id)) {
                        val since = eofAt ?: clock().also { eofAt = it }
                        // The exit-status message can trail EOF by a packet; keep reading
                        // briefly so libssh2 processes it before we ask.
                        if (clock() - since >= EXIT_STATUS_GRACE_MS) {
                            finish(transport.exitStatus(id), closeNative = true)
                            return
                        }
                    }
                    delay(idleDelayMs(clock() - lastProgressAt))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(exitCode = null, closeNative = true)
            }
        }

        /** Moves data from the transport into the stream buffers; true if anything moved. */
        private fun pumpStreams(pending: Array<ByteArray?>): Boolean {
            var progressed = false
            for (stream in 0..1) {
                val target = if (stream == 0) stdoutChannel else stderrChannel
                pending[stream]?.let { chunk ->
                    if (target.trySend(chunk).isSuccess) {
                        pending[stream] = null
                        progressed = true
                    }
                }
                if (pending[stream] != null) continue
                val chunk = transport.read(id, stream, READ_CHUNK_BYTES)
                if (chunk.isEmpty()) continue
                progressed = true
                if (!target.trySend(chunk).isSuccess) pending[stream] = chunk
            }
            return progressed
        }

        fun finish(exitCode: Int?, closeNative: Boolean) {
            if (finished) return
            val wasLive = openedIn == generation
            finished = true
            stdoutChannel.close()
            stderrChannel.close()
            this.exitCode.complete(exitCode)
            if (closeNative && wasLive) transport.close(id)
            open.remove(this)
        }

        override suspend fun write(bytes: ByteArray) =
            withContext(dispatcher) {
                var offset = 0
                var stalled = 0
                while (offset < bytes.size) {
                    check(live) { "exec channel is closed" }
                    val written = transport.write(id, bytes.copyOfRange(offset, bytes.size))
                    if (written > 0) {
                        offset += written
                        stalled = 0
                        continue
                    }
                    // Window full: suspend so the pump can read and the remote can drain.
                    check(++stalled <= MAX_STALLED_WRITES) { "exec channel write stalled" }
                    delay(WRITE_RETRY_DELAY_MS)
                }
            }

        override suspend fun sendEof() {
            withContext(dispatcher) { if (live) transport.sendEof(id) }
        }

        override suspend fun resize(size: PtySize) {
            withContext(dispatcher) { if (live) transport.resize(id, size) }
        }

        override fun close() {
            scope.launch(dispatcher) {
                finish(exitCode = null, closeNative = true)
                pump?.cancel()
            }
        }
    }

    private companion object {
        // Per stream, in chunks of up to READ_CHUNK_BYTES: bounds memory per channel.
        const val STREAM_BUFFER_CHUNKS = 16
        const val READ_CHUNK_BYTES = 32 * 1024
        const val EXIT_STATUS_GRACE_MS = 100L
        const val WRITE_RETRY_DELAY_MS = 4L
        // ~2 s of a full window before giving up, matching the shell writer's patience.
        const val MAX_STALLED_WRITES = 500

        // Same shape as the shell read loop: snappy while data flows, backing off when idle.
        fun idleDelayMs(idleForMs: Long): Long =
            when {
                idleForMs < 50 -> 2L
                idleForMs < 500 -> 8L
                idleForMs < 3_000 -> 24L
                else -> 64L
            }
    }
}

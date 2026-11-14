package com.jossephus.chuchu.plugins.rex

import android.util.Log
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.plugin.api.TransportConnection
import com.jossephus.chuchu.plugin.api.TransportEndedException
import com.jossephus.chuchu.plugin.api.TransportFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class RexTerminalSize(val columns: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int)

/**
 * One Rex pane: its data stream plus the input, resizes and ownership it sends over
 * the server's shared [RexServer] connection.
 */
internal class RexPane private constructor(
    private val server: RexServer,
    private val pane: RexAttachment,
) : TransportConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val data = RexDataConnection(RexServer.http)
    private val writes = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizes = Channel<RexTerminalSize>(Channel.CONFLATED)
    private val resizeEchoes = RexResizeEchoFilter()
    private val ownership = RexBlockOwnership(pane.blockId)
    // Only the pane on screen owns its block; preloaded neighbours just stream.
    @Volatile private var active = false

    override val target: String = RexTargets.pane(pane)

    override val frames: Flow<TransportFrame> =
        flow {
            try {
                for (item in data.stream) {
                    when (item) {
                        is RexStreamItem.Snapshot -> emit(TransportFrame.Snapshot(item.bytes))
                        is RexStreamItem.Output -> emit(TransportFrame.Output(item.bytes))
                        is RexStreamItem.Resize ->
                            if (resizeEchoes.shouldApply(item.columns, item.rows)) {
                                emit(TransportFrame.Resize(item.columns, item.rows))
                            }
                    }
                }
            } catch (e: RexPaneClosedException) {
                throw TransportEndedException("The Rex pane was closed. Reconnect to open the pane that has focus now.")
            }
        }

    private suspend fun start(size: RexTerminalSize) {
        // Subscribe before claiming so a take by another client right after ours is not missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { server.events.collect(ownership::onEvent) }
        // The resize and the ticket are independent round trips; overlap them. The resize
        // goes before any claim: Rex stores a non-owner's size and applies it on the take.
        val ticket = coroutineScope {
            val ticket = async { server.ticket(pane) }
            sendResize(size)
            ticket.await()
        }
        data.open(server.endpoint, ticket)
        scope.launch { data.fail(server.awaitEnd()) }
        scope.launch { pumpWrites() }
        scope.launch { pumpResizes() }
    }

    /** Claims the pane while it is on screen and gives it back when it is not. */
    override fun setActive(active: Boolean) {
        this.active = active
        if (active) {
            onUserInteraction()
        } else {
            release()
        }
    }

    /** The user touched this pane; take it back if another client has it, as iOS does on a tap. */
    override fun onUserInteraction() {
        if (active && ownership.needsClaim) scope.launch { claimIfLost() }
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) writes.trySend(bytes)
    }

    override fun resize(size: PtySize) {
        if (size.cols > 0 && size.rows > 0) resizes.trySend(size.toRexSize())
    }

    override fun close() {
        writes.close()
        resizes.close()
        data.close()
        scope.cancel()
        // The shared connection outlives this pane, so ownership has to be given up
        // explicitly; closing our own connection used to do it.
        release()
        RexServer.release(server)
    }

    private fun release() {
        if (ownership.needsClaim) return
        ownership.onReleased()
        server.disown(pane)
    }

    private suspend fun sendResize(size: RexTerminalSize) {
        // Recorded before the call so an echo that beats the reply is still recognised.
        resizeEchoes.onSent(size.columns, size.rows)
        server.resize(pane, size)
    }

    /** Re-takes the block if another client took it, like the official clients do on interaction. */
    private suspend fun claimIfLost() {
        if (!active || !ownership.needsClaim) return
        try {
            ownership.onClaimed(server.own(pane))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Rex could not take the block back", e)
        }
    }

    private suspend fun pumpWrites() {
        for (first in writes) {
            var batch = first
            while (batch.size < MAX_WRITE_BYTES) {
                batch += writes.tryReceive().getOrNull() ?: break
            }
            // Before the input, so the program already sees our size when it reacts to it.
            claimIfLost()
            for (chunk in splitUtf8(batch, MAX_WRITE_BYTES)) {
                try {
                    server.write(pane, String(chunk, Charsets.UTF_8))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Rex write failed", e)
                }
            }
        }
    }

    private suspend fun pumpResizes() {
        // The host already waits for the size to settle before resizing a transport.
        for (size in resizes) {
            try {
                // Resize first: a non-owner's size is stored, and taking the block then
                // applies it once instead of applying our previous size and then this one.
                sendResize(size)
                claimIfLost()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Rex resize failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "RexPane"
        private const val MAX_WRITE_BYTES = 32 * 1024

        suspend fun open(endpoint: RexEndpoint, request: RexOpenRequest, size: PtySize): RexPane {
            val server = RexServer.acquire(endpoint)
            val pane =
                try {
                    server.awaitReady()
                    RexPane(server, server.open(request))
                } catch (e: Exception) {
                    RexServer.release(server)
                    throw e
                }
            try {
                pane.start(size.toRexSize())
            } catch (e: Exception) {
                pane.close()
                throw e
            }
            return pane
        }

        internal fun splitUtf8(bytes: ByteArray, maxBytes: Int): List<ByteArray> {
            if (bytes.size <= maxBytes) return listOf(bytes)
            val chunks = mutableListOf<ByteArray>()
            var start = 0
            while (start < bytes.size) {
                var end = minOf(start + maxBytes, bytes.size)
                if (end < bytes.size) {
                    while (end > start + 1 && (bytes[end].toInt() and 0xC0) == 0x80) end--
                }
                chunks += bytes.copyOfRange(start, end)
                start = end
            }
            return chunks
        }

        private fun PtySize.toRexSize(): RexTerminalSize =
            RexTerminalSize(
                columns = cols,
                rows = rows,
                cellWidthPx = if (widthPx > 0 && cols > 0) widthPx / cols else 0,
                cellHeightPx = if (heightPx > 0 && rows > 0) heightPx / rows else 0,
            )
    }
}

/**
 * Tracks whether this client still owns its terminal block.
 *
 * Rex only applies a resize from the block's owner, and every official client
 * takes ownership when the user interacts with it (the desktop on focus, iOS on
 * a tap). Without re-taking it, a pane the desktop grabbed keeps the desktop's
 * size while the phone draws its own grid, so output wraps at the wrong width.
 * Knowing when ownership moved lets us re-take it on the next interaction
 * instead of calling the server on every keystroke.
 */
internal class RexBlockOwnership(private val blockId: String) {
    @Volatile private var clientId: String? = null
    @Volatile private var owned = false

    val needsClaim: Boolean
        get() = !owned

    /** Records the reply to `session.own_block`, which names us as the owner. */
    fun onClaimed(result: JSONObject) {
        val blocks = result.optJSONArray("blocks") ?: return
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.stringOrNull("block_id") != blockId) continue
            clientId = block.stringOrNull("owner")
            owned = clientId != null
        }
    }

    /** We gave the block up ourselves; the next interaction must claim it again. */
    fun onReleased() {
        owned = false
    }

    /** Reads a control-connection event; only `block_owner_changed` for our block matters. */
    fun onEvent(event: JSONObject) {
        if (event.optString("type") != "session_event") return
        val inner = event.optJSONObject("payload")?.optJSONObject("event") ?: return
        if (inner.optString("type") != "block_owner_changed") return
        val payload = inner.optJSONObject("payload") ?: return
        if (payload.stringOrNull("block_id") != blockId) return
        val ours = clientId ?: return
        owned = payload.stringOrNull("owner") == ours
    }
}

/**
 * Drops the server's late echoes of sizes this client already moved past.
 *
 * Rex reports every pane resize back on the data stream, including the ones we
 * asked for. The keyboard animates the canvas through many sizes, so the echoes
 * arrive after the grid has already reached its final size; applying them would
 * replay the animation a second late and draw the old grid under the toolbar.
 *
 * A resize we never requested (another client, or the server clamping our
 * size) is still applied, and so is the echo of the latest request, which puts
 * the grid back after a snapshot restored it at the server's old size.
 */
internal class RexResizeEchoFilter {
    private val sent = ArrayDeque<Pair<Int, Int>>()

    @Synchronized
    fun onSent(columns: Int, rows: Int) {
        sent.addLast(columns to rows)
        while (sent.size > MAX_TRACKED) sent.removeFirst()
    }

    /** Returns true when a `resize` event from the server should reach the terminal. */
    @Synchronized
    fun shouldApply(columns: Int, rows: Int): Boolean {
        val size = columns to rows
        val at = sent.indexOf(size)
        if (at < 0) return true
        val isLatest = at == sent.lastIndex
        // Echoes arrive in request order, so everything up to this one is answered.
        repeat(at) { sent.removeFirst() }
        if (!isLatest) sent.removeFirst()
        return isLatest
    }

    private companion object {
        // Comfortably more than one keyboard animation's worth of row changes.
        const val MAX_TRACKED = 64
    }
}

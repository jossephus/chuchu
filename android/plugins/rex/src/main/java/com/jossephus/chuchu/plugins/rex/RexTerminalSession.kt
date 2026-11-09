package com.jossephus.chuchu.plugins.rex

import android.util.Log
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

data class RexTerminalSize(val columns: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int)

data class RexAttachment(val sessionId: String, val blockId: String)

sealed interface RexOpenRequest {
    val sessionId: String?

    data object Focused : RexOpenRequest {
        override val sessionId: String? = null
    }

    data object NewSession : RexOpenRequest {
        override val sessionId: String? = null
    }

    data class NewWindow(override val sessionId: String) : RexOpenRequest

    data class Block(val attachment: RexAttachment) : RexOpenRequest {
        override val sessionId: String = attachment.sessionId
    }
}

/**
 * One Rex pane: its data stream plus the input, resizes and ownership it sends over
 * the server's shared [RexHub] connection.
 */
class RexTerminalSession(
    private val endpoint: RexEndpoint,
    private val request: RexOpenRequest = RexOpenRequest.Focused,
    http: OkHttpClient = sharedHttp,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hub = RexHubs.acquire(endpoint, http)
    private val control: RexControlConnection
        get() = hub.control
    private val data = RexDataConnection(http)
    private val writes = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizes = Channel<RexTerminalSize>(Channel.CONFLATED)
    private val resizeEchoes = RexResizeEchoFilter()
    @Volatile var attachment: RexAttachment? = null
        private set
    @Volatile private var claimTarget: Pair<Target, RexBlockOwnership>? = null
    // Only the pane on screen owns its block; preloaded neighbours just stream.
    @Volatile private var active = false

    val stream: ReceiveChannel<RexStreamItem>
        get() = data.stream

    suspend fun connect(size: RexTerminalSize?) {
        hub.awaitReady()
        val attached = attach()
        val ownership = RexBlockOwnership(attached.blockId)
        // Subscribe before claiming so a take by another client right after ours is not missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { control.events.collect(ownership::onEvent) }
        // The resize and the ticket are independent round trips; overlap them. The resize
        // goes before any claim: Rex stores a non-owner's size and applies it on the take.
        val ticket = coroutineScope {
            val ticket = async { requestTicket(attached) }
            if (size != null) sendResize(attached, size)
            ticket.await()
        }
        data.open(endpoint, ticket)
        scope.launch { data.fail(control.awaitEnd()) }
        scope.launch { pumpWrites(attached, ownership) }
        scope.launch { pumpResizes(attached, ownership) }
        attachment = RexAttachment(attached.sessionId, attached.blockId)
        claimTarget = attached to ownership
        // setActive may have arrived while connecting.
        if (active) takeBackIfLost()
    }

    /** Claims the pane while it is on screen and gives it back when it is not. */
    fun setActive(isActive: Boolean) {
        active = isActive
        val (target, ownership) = claimTarget ?: return
        if (isActive) {
            takeBackIfLost()
        } else if (!ownership.needsClaim) {
            ownership.onReleased()
            hub.disown(target.sessionId, target.blockId)
        }
    }

    /** The user touched this pane; take it back if another client has it, as iOS does on a tap. */
    fun takeBackIfLost() {
        val (target, ownership) = claimTarget ?: return
        if (active && ownership.needsClaim) scope.launch { claimIfLost(target, ownership) }
    }

    fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) writes.trySend(bytes)
    }

    fun resize(size: RexTerminalSize) {
        if (size.columns > 0 && size.rows > 0) resizes.trySend(size)
    }

    /** False for the server's late echo of a size this session has already resized past. */
    fun shouldApplyRemoteResize(columns: Int, rows: Int): Boolean = resizeEchoes.shouldApply(columns, rows)

    override fun close() {
        writes.close()
        resizes.close()
        data.close()
        scope.cancel()
        // The shared connection outlives this pane, so ownership has to be given up
        // explicitly; closing our own connection used to do it.
        claimTarget?.let { (target, ownership) -> if (!ownership.needsClaim) hub.disown(target.sessionId, target.blockId) }
        RexHubs.release(hub)
    }

    private suspend fun attach(): Target {
        if (request is RexOpenRequest.Block && hub.browser.knowsBlock(request.sessionId, request.attachment.blockId)) {
            return Target(request.sessionId, request.attachment.blockId)
        }
        val sessionId =
            when (request) {
                RexOpenRequest.NewSession -> return createSession()
                is RexOpenRequest.NewWindow -> return attachSession(request.sessionId)?.let { openWindow(it) } ?: createSession()
                is RexOpenRequest.Block -> attachSession(request.sessionId)
                RexOpenRequest.Focused -> attachSession(firstSessionId())
            } ?: return createSession()
        val view = control.call("session.view", JSONObject().put("session_id", sessionId)).let { it.optJSONObject("view") ?: it }
        if (request is RexOpenRequest.Block && RexPanePicker.contains(view, request.attachment.blockId)) {
            return Target(sessionId, request.attachment.blockId)
        }
        val blockId =
            RexPanePicker.pick(view)
                ?: throw RexConnectionException(
                    "The Rex session \"${view.stringOrNull("label") ?: sessionId}\" has no terminal pane"
                )
        return Target(sessionId, blockId)
    }

    /** Attaches the shared connection to [sessionId]; null when that session is gone. */
    private suspend fun attachSession(sessionId: String?): String? {
        if (sessionId == null) return null
        return try {
            control.call("session.attach", JSONObject().put("session_id", sessionId))
            sessionId
        } catch (e: RexCallException) {
            Log.w(TAG, "Rex session $sessionId is gone", e)
            null
        }
    }

    private suspend fun firstSessionId(): String? =
        control.call("session.list").optJSONArray("sessions")?.optJSONObject(0)?.stringOrNull("session_id")

    private suspend fun openWindow(sessionId: String): Target {
        val created =
            control.call(
                "session.new_window",
                JSONObject().put("session_id", sessionId).put("focus", false).put("layout", shellLayout()),
            )
        val blockId =
            created.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotBlank() }
                ?: throw RexConnectionException("Rex created a window without a terminal pane")
        return Target(sessionId, blockId)
    }

    private suspend fun createSession(): Target {
        val created =
            control.call(
                "session.create",
                JSONObject()
                    .put(
                        "initial_windows",
                        JSONArray()
                            .put(JSONObject().put("layout", shellLayout())),
                    ),
            )
        val sessionId = created.getString("session_id")
        val blockId =
            created.optJSONArray("initial_windows")?.optJSONObject(0)?.optJSONArray("block_ids")?.optString(0)
                ?.takeIf { it.isNotBlank() }
                ?: throw RexConnectionException("Rex created a session without a terminal pane")
        control.call("session.attach", JSONObject().put("session_id", sessionId))
        return Target(sessionId, blockId)
    }

    private suspend fun requestTicket(target: Target): String =
        control
            .call(
                "data.connect",
                target.payload().put("data_ref_id", DATA_REF_TERMINAL).put("subprotocol", DATA_PROTOCOL),
            )
            .getString("ticket")

    private suspend fun sendResize(target: Target, size: RexTerminalSize) {
        val args = JSONObject().put("columns", size.columns).put("rows", size.rows)
        if (size.cellWidthPx > 0 && size.cellHeightPx > 0) {
            args.put("cell_width_px", size.cellWidthPx).put("cell_height_px", size.cellHeightPx)
        }
        // Recorded before the call so an echo that beats the reply is still recognised.
        resizeEchoes.onSent(size.columns, size.rows)
        control.call(TERMINAL_RESIZE, target.payload().put("args", args))
    }

    private suspend fun takeBlock(target: Target, ownership: RexBlockOwnership) {
        val result =
            control.call(
                "session.own_block",
                JSONObject()
                    .put("session_id", target.sessionId)
                    .put("block_ids", JSONArray().put(target.blockId))
                    .put("mode", "take"),
            )
        ownership.onClaimed(result)
    }

    /** Re-takes the block if another client took it, like the official clients do on interaction. */
    private suspend fun claimIfLost(target: Target, ownership: RexBlockOwnership) {
        if (!active || !ownership.needsClaim) return
        try {
            takeBlock(target, ownership)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Rex could not take the block back", e)
        }
    }

    private suspend fun pumpWrites(target: Target, ownership: RexBlockOwnership) {
        for (first in writes) {
            var batch = first
            while (batch.size < MAX_WRITE_BYTES) {
                batch += writes.tryReceive().getOrNull() ?: break
            }
            // Before the input, so the program already sees our size when it reacts to it.
            claimIfLost(target, ownership)
            for (chunk in splitUtf8(batch, MAX_WRITE_BYTES)) {
                try {
                    control.call(
                        TERMINAL_WRITE,
                        target.payload().put("args", JSONObject().put("data", String(chunk, Charsets.UTF_8))),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Rex write failed", e)
                }
            }
        }
    }

    private suspend fun pumpResizes(target: Target, ownership: RexBlockOwnership) {
        // The host already waits for the size to settle before resizing a transport.
        for (size in resizes) {
            try {
                // Resize first: a non-owner's size is stored, and taking the block then
                // applies it once instead of applying our previous size and then this one.
                sendResize(target, size)
                claimIfLost(target, ownership)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Rex resize failed", e)
            }
        }
    }

    private data class Target(val sessionId: String, val blockId: String) {
        fun payload(): JSONObject = JSONObject().put("session_id", sessionId).put("block_id", blockId)
    }

    companion object {
        private const val TAG = "RexTerminalSession"
        private const val DATA_REF_TERMINAL = "terminal"
        private const val DATA_PROTOCOL = "rex.data.terminal.v1"
        private const val SHELL_FLAVOR = "com.superlogical.terminal.shell"
        private const val TERMINAL_WRITE = "com.superlogical.terminal.write"
        private const val TERMINAL_RESIZE = "com.superlogical.terminal.resize"
        private const val MAX_WRITE_BYTES = 32 * 1024

        private fun shellLayout(): JSONObject =
            JSONObject().put("block", JSONObject().put("flavor", SHELL_FLAVOR))

        internal val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

        suspend fun probeSessionCount(endpoint: RexEndpoint): Int {
            val control = RexControlConnection(sharedHttp)
            try {
                control.open(endpoint)
                return control.call("server.status").optInt("session_count")
            } finally {
                control.close()
            }
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
    }
}

internal object RexPanePicker {
    const val TERMINAL_CREATOR = "com.superlogical.terminal"
    private const val WINDOW_ID_SUFFIX = 8

    fun pick(view: JSONObject): String? {
        val windows = view.optJSONArray("windows") ?: return null
        val activeId = view.stringOrNull("active_window_id")
        val ordered =
            (0 until windows.length())
                .mapNotNull { windows.optJSONObject(it) }
                .sortedByDescending { it.stringOrNull("window_id") == activeId }
        for (window in ordered) {
            val terminals = windowTerminals(window).map { it.first }
            if (terminals.isEmpty()) continue
            listOfNotNull(window.stringOrNull("zoomed_block_id"), window.stringOrNull("focused_block_id"))
                .firstOrNull { it in terminals }
                ?.let { return it }
            return terminals.first()
        }
        return null
    }

    fun contains(view: JSONObject, blockId: String): Boolean = blockId in terminalBlockIds(view)

    fun terminalBlockIds(view: JSONObject): List<String> = terminalBlocks(view).map { it.first }

    /** Terminal blocks whose window has a name the user set, not Rex's default "Window <id>". */
    fun customNamedBlockIds(view: JSONObject): List<String> {
        val windows = view.optJSONArray("windows") ?: return emptyList()
        return (0 until windows.length()).flatMap { i ->
            val window = windows.optJSONObject(i) ?: return@flatMap emptyList()
            val label = window.stringOrNull("label") ?: return@flatMap emptyList()
            val defaultName = window.stringOrNull("window_id")?.let { "Window " + it.takeLast(WINDOW_ID_SUFFIX) }
            if (label == defaultName) emptyList() else windowTerminals(window).map { it.first }
        }
    }

    /** Terminal blocks whose shown name another block also has, so the list needs more to tell them apart. */
    fun sharedNameBlockIds(view: JSONObject): List<String> {
        val blocks = terminalBlocks(view)
        val counts = blocks.groupingBy { it.second }.eachCount()
        return blocks.filter { (counts[it.second] ?: 0) > 1 }.map { it.first }
    }

    fun terminalBlocks(view: JSONObject): List<Pair<String, String?>> {
        val windows = view.optJSONArray("windows") ?: return emptyList()
        return (0 until windows.length()).flatMap { i ->
            windows.optJSONObject(i)?.let(::windowTerminals).orEmpty()
        }
    }

    /**
     * Terminal blocks in [window], each paired with the name to show for it. That is the
     * window's label, as the official clients list windows by name, and Rex's own
     * "Window <id>" form when a window has none.
     */
    private fun windowTerminals(window: JSONObject): List<Pair<String, String?>> {
        val layers = window.optJSONArray("layers") ?: return emptyList()
        val windowName =
            window.stringOrNull("label")
                ?: window.stringOrNull("window_id")?.let { "Window " + it.takeLast(WINDOW_ID_SUFFIX) }
        val found = mutableListOf<Pair<String, String?>>()
        for (i in 0 until layers.length()) {
            val blocks = layers.optJSONObject(i)?.optJSONArray("blocks") ?: continue
            for (j in 0 until blocks.length()) {
                val block = blocks.optJSONObject(j) ?: continue
                val id = block.stringOrNull("block_id") ?: continue
                if (block.stringOrNull("creator_name") == TERMINAL_CREATOR) {
                    found += id to (windowName ?: block.stringOrNull("label"))
                }
            }
        }
        return found
    }
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

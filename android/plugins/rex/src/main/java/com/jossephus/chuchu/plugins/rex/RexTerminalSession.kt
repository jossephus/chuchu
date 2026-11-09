package com.jossephus.chuchu.plugins.rex

import android.util.Log
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
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

class RexTerminalSession(
    private val endpoint: RexEndpoint,
    private val request: RexOpenRequest = RexOpenRequest.Focused,
    http: OkHttpClient = sharedHttp,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = RexControlConnection(http)
    private val data = RexDataConnection(http)
    private val writes = Channel<ByteArray>(Channel.UNLIMITED)
    private val resizes = Channel<RexTerminalSize>(Channel.CONFLATED)
    @Volatile var attachment: RexAttachment? = null
        private set

    val stream: ReceiveChannel<RexStreamItem>
        get() = data.stream

    suspend fun connect(size: RexTerminalSize?) {
        control.open(endpoint)
        val attached = attach()
        control.call(
            "session.own_block",
            JSONObject()
                .put("session_id", attached.sessionId)
                .put("block_ids", JSONArray().put(attached.blockId))
                .put("mode", "take"),
        )
        if (size != null) sendResize(attached, size)
        data.open(endpoint, attached.ticket ?: requestTicket(attached))
        scope.launch { data.fail(control.awaitEnd()) }
        scope.launch { pumpWrites(attached) }
        scope.launch { pumpResizes(attached) }
        attachment = RexAttachment(attached.sessionId, attached.blockId)
    }

    fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) writes.trySend(bytes)
    }

    fun resize(size: RexTerminalSize) {
        if (size.columns > 0 && size.rows > 0) resizes.trySend(size)
    }

    override fun close() {
        writes.close()
        resizes.close()
        data.close()
        control.close()
        scope.cancel()
    }

    private suspend fun attach(): Target {
        if (request == RexOpenRequest.NewSession) return createSession()
        val bootstrap =
            JSONObject()
                .put("event_scope", "session")
                .put(
                    "data_tickets",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("creator_name", RexPanePicker.TERMINAL_CREATOR)
                                .put("data_ref_id", DATA_REF_TERMINAL)
                                .put("subprotocol", DATA_PROTOCOL)
                        ),
                )
        request.sessionId?.let { bootstrap.put("preferred_session_id", it) }
        val result = control.call("session.bootstrap", bootstrap)
        val selected = result.optJSONObject("selected_session") ?: return createSession()
        val view = selected.getJSONObject("view")
        val sessionId =
            view.stringOrNull("session_id")
                ?: selected.getJSONObject("session").getString("session_id")
        val tickets = result.optJSONArray("data_tickets")
        when (request) {
            is RexOpenRequest.NewWindow ->
                return if (request.sessionId == sessionId) openWindow(sessionId) else createSession()
            is RexOpenRequest.Block -> {
                val pinned = request.attachment.blockId
                if (request.sessionId == sessionId && RexPanePicker.contains(view, pinned)) {
                    return Target(sessionId, pinned, ticketFor(tickets, pinned))
                }
            }
            RexOpenRequest.Focused,
            RexOpenRequest.NewSession -> Unit
        }
        val blockId =
            RexPanePicker.pick(view)
                ?: throw RexConnectionException(
                    "The Rex session \"${view.stringOrNull("label") ?: sessionId}\" has no terminal pane"
                )
        return Target(sessionId, blockId, ticketFor(tickets, blockId))
    }


    private suspend fun openWindow(sessionId: String): Target {
        val created =
            control.call(
                "session.new_window",
                JSONObject().put("session_id", sessionId).put("focus", false).put("layout", shellLayout()),
            )
        val blockId =
            created.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotBlank() }
                ?: throw RexConnectionException("Rex created a window without a terminal pane")
        return Target(sessionId, blockId, ticket = null)
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
        return Target(sessionId, blockId, ticket = null)
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
        control.call(TERMINAL_RESIZE, target.payload().put("args", args))
    }

    private suspend fun pumpWrites(target: Target) {
        for (first in writes) {
            var batch = first
            while (batch.size < MAX_WRITE_BYTES) {
                batch += writes.tryReceive().getOrNull() ?: break
            }
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

    private suspend fun pumpResizes(target: Target) {
        for (size in resizes) {
            try {
                sendResize(target, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Rex resize failed", e)
            }
        }
    }

    private data class Target(val sessionId: String, val blockId: String, val ticket: String?) {
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

        private fun ticketFor(tickets: JSONArray?, blockId: String): String? {
            if (tickets == null) return null
            for (i in 0 until tickets.length()) {
                val entry = tickets.optJSONObject(i) ?: continue
                if (entry.stringOrNull("block_id") == blockId && entry.stringOrNull("data_ref_id") == DATA_REF_TERMINAL) {
                    return entry.stringOrNull("ticket")
                }
            }
            return null
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

    fun terminalBlocks(view: JSONObject): List<Pair<String, String?>> {
        val windows = view.optJSONArray("windows") ?: return emptyList()
        return (0 until windows.length()).flatMap { i ->
            windows.optJSONObject(i)?.let(::windowTerminals).orEmpty()
        }
    }

    private fun windowTerminals(window: JSONObject): List<Pair<String, String?>> {
        val layers = window.optJSONArray("layers") ?: return emptyList()
        val found = mutableListOf<Pair<String, String?>>()
        for (i in 0 until layers.length()) {
            val blocks = layers.optJSONObject(i)?.optJSONArray("blocks") ?: continue
            for (j in 0 until blocks.length()) {
                val block = blocks.optJSONObject(j) ?: continue
                val id = block.stringOrNull("block_id") ?: continue
                if (block.stringOrNull("creator_name") == TERMINAL_CREATOR) found += id to block.stringOrNull("label")
            }
        }
        return found
    }
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

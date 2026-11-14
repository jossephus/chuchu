package com.jossephus.chuchu.plugins.rex

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

data class RexAttachment(val sessionId: String, val blockId: String) {
    fun payload(): JSONObject = JSONObject().put("session_id", sessionId).put("block_id", blockId)
}

/**
 * One control connection to a Rex server, shared by its session list and every pane
 * open on it.
 *
 * The official clients keep a single connection for everything. A connection per
 * screen meant leaving a pane tore down two connections and rebuilt the list from
 * scratch, two list screens loaded everything twice, and each pane was a separate
 * client to Rex.
 */
internal class RexServer private constructor(val endpoint: RexEndpoint) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = RexControlConnection(http)
    val browser = RexSessionBrowser(this)
    private val ready: Deferred<Unit> =
        scope.async {
            try {
                control.open(endpoint)
                browser.start()
            } catch (e: Exception) {
                control.close()
                throw e
            }
        }

    val isEnded: Boolean
        get() = control.isEnded

    val events: SharedFlow<JSONObject>
        get() = control.events

    /** Suspends until the connection is open and the session list has loaded once. */
    suspend fun awaitReady() = ready.await()

    suspend fun awaitEnd(): Throwable = control.awaitEnd()

    suspend fun listSessions(): JSONArray? = control.call("session.list").optJSONArray("sessions")

    suspend fun bootstrap(sessionIds: List<String>): JSONArray =
        control
            .call(
                "session.bootstrap",
                JSONObject().put("event_scope", "all").put("attach_session_ids", JSONArray(sessionIds)),
            )
            .optJSONArray("attached_sessions") ?: JSONArray()

    suspend fun attach(sessionId: String): JSONObject =
        control.call("session.attach", JSONObject().put("session_id", sessionId))

    suspend fun open(request: RexOpenRequest): RexAttachment {
        if (request is RexOpenRequest.Block && browser.knowsBlock(request.pane)) return request.pane
        val sessionId =
            when (request) {
                RexOpenRequest.NewSession -> return createSession()
                is RexOpenRequest.NewWindow -> return attachOrNull(request.sessionId)?.let { openWindow(it) } ?: createSession()
                is RexOpenRequest.Block -> attachOrNull(request.pane.sessionId)
                RexOpenRequest.Focused -> attachOrNull(listSessions()?.optJSONObject(0)?.stringOrNull("session_id"))
            } ?: return createSession()
        val view = control.call("session.view", JSONObject().put("session_id", sessionId)).let { it.optJSONObject("view") ?: it }
        if (request is RexOpenRequest.Block && RexLayout.contains(view, request.pane.blockId)) return request.pane
        val blockId =
            RexLayout.pick(view)
                ?: throw RexConnectionException("The Rex session \"${view.stringOrNull("label") ?: sessionId}\" has no terminal pane")
        return RexAttachment(sessionId, blockId)
    }

    suspend fun ticket(pane: RexAttachment): String =
        control
            .call("data.connect", pane.payload().put("data_ref_id", "terminal").put("subprotocol", RexDataConnection.PROTOCOL))
            .getString("ticket")

    suspend fun write(pane: RexAttachment, text: String) {
        control.call(TERMINAL_WRITE, pane.payload().put("args", JSONObject().put("data", text)))
    }

    suspend fun resize(pane: RexAttachment, size: RexTerminalSize) {
        val args = JSONObject().put("columns", size.columns).put("rows", size.rows)
        if (size.cellWidthPx > 0 && size.cellHeightPx > 0) {
            args.put("cell_width_px", size.cellWidthPx).put("cell_height_px", size.cellHeightPx)
        }
        control.call(TERMINAL_RESIZE, pane.payload().put("args", args))
    }

    suspend fun own(pane: RexAttachment): JSONObject =
        control.call("session.own_block", blocksPayload(pane).put("mode", "take"))

    /** Gives up a pane this client claimed; the server keeps ownership otherwise. */
    fun disown(pane: RexAttachment) {
        if (control.isEnded) return
        scope.launch {
            runCatching { control.call("session.disown_block", blocksPayload(pane)) }
                .onFailure { Log.w(TAG, "Rex could not release ${pane.blockId}", it) }
        }
    }

    suspend fun title(pane: RexAttachment): JSONObject = control.call(TITLE_METHOD, pane.payload())

    suspend fun process(pane: RexAttachment): JSONObject = control.call(PROCESS_METHOD, pane.payload())

    private fun close() {
        browser.close()
        control.close()
        scope.cancel()
    }

    private suspend fun attachOrNull(sessionId: String?): String? {
        if (sessionId == null) return null
        return try {
            attach(sessionId)
            sessionId
        } catch (e: RexCallException) {
            Log.w(TAG, "Rex session $sessionId is gone", e)
            null
        }
    }

    private suspend fun openWindow(sessionId: String): RexAttachment {
        val created =
            control.call(
                "session.new_window",
                JSONObject().put("session_id", sessionId).put("focus", false).put("layout", shellLayout()),
            )
        val blockId =
            created.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotBlank() }
                ?: throw RexConnectionException("Rex created a window without a terminal pane")
        return RexAttachment(sessionId, blockId)
    }

    private suspend fun createSession(): RexAttachment {
        val created =
            control.call(
                "session.create",
                JSONObject().put("initial_windows", JSONArray().put(JSONObject().put("layout", shellLayout()))),
            )
        val sessionId = created.getString("session_id")
        val blockId =
            created.optJSONArray("initial_windows")?.optJSONObject(0)?.optJSONArray("block_ids")?.optString(0)
                ?.takeIf { it.isNotBlank() }
                ?: throw RexConnectionException("Rex created a session without a terminal pane")
        attach(sessionId)
        return RexAttachment(sessionId, blockId)
    }

    private fun blocksPayload(pane: RexAttachment): JSONObject =
        JSONObject().put("session_id", pane.sessionId).put("block_ids", JSONArray().put(pane.blockId))

    private fun shellLayout(): JSONObject = JSONObject().put("block", JSONObject().put("flavor", SHELL_FLAVOR))

    private class Entry(val server: RexServer) {
        var users = 0
        var closing: Job? = null
    }

    /**
     * Servers are shared per address and reference-counted. One stays open briefly after
     * its last user leaves, so going from a pane back to the list (or the reverse) reuses
     * the connection instead of reconnecting.
     */
    companion object {
        private const val TAG = "RexServer"
        private const val LINGER_MS = 30_000L
        private const val SHELL_FLAVOR = "com.superlogical.terminal.shell"
        private const val TERMINAL_WRITE = "com.superlogical.terminal.write"
        private const val TERMINAL_RESIZE = "com.superlogical.terminal.resize"
        private const val TITLE_METHOD = "com.superlogical.terminal.title"
        private const val PROCESS_METHOD = "com.superlogical.terminal.process"

        private val entries = mutableMapOf<String, Entry>()
        private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

        @Synchronized
        fun acquire(endpoint: RexEndpoint): RexServer {
            val entry =
                entries[endpoint.url]?.takeUnless { it.server.isEnded }
                    ?: Entry(RexServer(endpoint)).also { fresh ->
                        entries.remove(endpoint.url)?.server?.close()
                        entries[endpoint.url] = fresh
                    }
            entry.closing?.cancel()
            entry.closing = null
            entry.users++
            return entry.server
        }

        @Synchronized
        fun release(server: RexServer) {
            val entry = entries[server.endpoint.url]?.takeIf { it.server === server } ?: return server.close()
            entry.users--
            if (entry.users > 0) return
            if (server.isEnded) {
                entries.remove(server.endpoint.url)
                server.close()
                return
            }
            entry.closing =
                poolScope.launch {
                    delay(LINGER_MS)
                    closeIfStillUnused(entry)
                }
        }

        suspend fun probeSessionCount(endpoint: RexEndpoint): Int {
            val control = RexControlConnection(http)
            try {
                control.open(endpoint)
                return control.call("server.status").optInt("session_count")
            } finally {
                control.close()
            }
        }

        @Synchronized
        private fun closeIfStillUnused(entry: Entry) {
            if (entry.users > 0 || entries[entry.server.endpoint.url] !== entry) return
            entries.remove(entry.server.endpoint.url)
            entry.server.close()
        }
    }
}

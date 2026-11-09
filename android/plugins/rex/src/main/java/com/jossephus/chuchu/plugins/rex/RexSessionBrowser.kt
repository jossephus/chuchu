package com.jossephus.chuchu.plugins.rex

import android.util.Log
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RexPane(
    val sessionId: String,
    val blockId: String,
    /** The window's name: one the user set, or Rex's "Window <id>". */
    val label: String?,
    /** True when [label] was set by the user rather than defaulted by Rex. */
    val labelIsCustom: Boolean,
    /** The terminal title, only while the program that set it is still in front. */
    val title: String?,
    val program: String?,
    val cwd: String?,
)

data class RexSessionSummary(val sessionId: String, val label: String, val panes: List<RexPane>)

sealed interface RexBrowserState {
    data object Connecting : RexBrowserState

    data class Ready(val sessions: List<RexSessionSummary>) : RexBrowserState

    data class Failed(val message: String) : RexBrowserState
}

/**
 * Keeps the session list for one Rex server current. It runs on a [RexHub]'s shared
 * control connection, which the hub opens before [start] and closes after [close].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RexSessionBrowser(private val control: RexControlConnection) : Closeable {
    private val confined = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val dirty = Channel<String>(Channel.UNLIMITED)
    private val order = mutableListOf<String>()
    private val labels = mutableMapOf<String, String>()
    private val views = mutableMapOf<String, JSONObject>()
    private val processes = mutableMapOf<String, RexProcess>()
    private val titles = mutableMapOf<String, RexTitle>()
    private var home: String? = null
    private val _state = MutableStateFlow<RexBrowserState>(RexBrowserState.Connecting)

    val state: StateFlow<RexBrowserState>
        get() = _state

    // Read from pane connects on other threads; replaced whole on every publish.
    @Volatile private var knownBlocks: Map<String, Set<String>> = emptyMap()

    /**
     * True when [blockId] is a terminal of [sessionId] in the list as last loaded. The
     * shared connection is then already attached to that session, so a pane opened from
     * the list can skip session.attach and session.view.
     */
    fun knowsBlock(sessionId: String, blockId: String): Boolean = knownBlocks[sessionId]?.contains(blockId) == true

    suspend fun start() =
        withContext(confined) {
            try {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { control.events.collect { handleEvent(it) } }
                replaceOrder(control.call("session.list").optJSONArray("sessions"))
                val ids = JSONArray().apply { order.forEach { put(it) } }
                val boot =
                    control.call(
                        "session.bootstrap",
                        JSONObject().put("event_scope", "all").put("attach_session_ids", ids),
                    )
                val attached = boot.optJSONArray("attached_sessions") ?: JSONArray()
                for (i in 0 until attached.length()) {
                    attached.optJSONObject(i)?.optJSONObject("view")?.let(::storeView)
                }
                publish()
                scope.launch { refreshLoop() }
                scope.launch { fail(control.awaitEnd()) }
                order.toList().forEach { loadProcesses(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }

    /** Stops refreshing. The hub closes the connection afterwards, so no refresh is left waiting on it. */
    override fun close() {
        scope.cancel()
    }

    private fun handleEvent(event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            "session_created" -> payload.optJSONObject("session")?.let { session ->
                val id = session.stringOrNull("session_id") ?: return
                if (id !in order) order += id
                session.stringOrNull("label")?.let { labels[id] = it }
                dirty.trySend(id)
            }
            "session_destroyed" -> {
                val id = payload.stringOrNull("session_id") ?: payload.optJSONObject("session")?.stringOrNull("session_id") ?: return
                order -= id
                views.remove(id)
                publish()
            }
            "session_moved" -> dirty.trySend(RELIST)
            "session_event" -> handleSessionEvent(payload)
        }
    }

    private fun handleSessionEvent(payload: JSONObject) {
        val sessionId = payload.stringOrNull("session_id") ?: return
        val inner = payload.optJSONObject("event") ?: return
        when (inner.optString("type")) {
            "block_event" -> {
                val block = inner.optJSONObject("payload") ?: return
                val blockId = block.stringOrNull("block_id") ?: return
                val data = block.optJSONObject("payload") ?: return
                when (block.optString("name")) {
                    "process_changed" -> processes[blockId] = RexProcess.from(data.optJSONObject("foreground") ?: return)
                    "title_changed" -> titles[blockId] = RexTitle.from(data)
                    else -> return
                }
                publish()
            }
            "client_connected", "client_disconnected", "block_owner_changed" -> Unit
            else -> dirty.trySend(sessionId)
        }
    }

    private suspend fun refreshLoop() {
        for (first in dirty) {
            delay(REFRESH_DEBOUNCE_MS)
            val pending = linkedSetOf(first)
            while (true) pending += dirty.tryReceive().getOrNull() ?: break
            for (id in pending) {
                try {
                    if (id == RELIST) {
                        replaceOrder(control.call("session.list").optJSONArray("sessions"))
                    } else if (id in order) {
                        val attached = control.call("session.attach", JSONObject().put("session_id", id))
                        attached.optJSONObject("view")?.let(::storeView)
                        loadProcesses(id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // Covers timeouts and a dropped connection too; a lost connection is
                    // reported through awaitEnd(), so a refresh only needs to give up.
                    Log.w(TAG, "Rex refresh of $id failed", e)
                }
            }
            publish()
        }
    }

    /**
     * Looks up each pane's terminal title and foreground process, which name the rows
     * the way the official clients do (a program's title, like Claude's, over Rex's
     * default "Window <id>"). Each pane is asked once per shared connection, a few at a
     * time; title_changed and process_changed events keep the answers current, so
     * reopening the list costs nothing.
     */
    private suspend fun loadProcesses(sessionId: String) {
        val view = views[sessionId] ?: return
        val missing = RexPanePicker.terminalBlockIds(view).filter { it !in processes }
        if (missing.isEmpty()) return
        val limit = Semaphore(PANE_LOOKUP_CONCURRENCY)
        coroutineScope {
            for (blockId in missing) {
                launch { limit.withPermit { loadPane(sessionId, blockId) } }
            }
        }
    }

    private suspend fun loadPane(sessionId: String, blockId: String) {
        val target = JSONObject().put("session_id", sessionId).put("block_id", blockId)
        try {
            coroutineScope {
                val title = async { control.call(TITLE_METHOD, JSONObject(target.toString())) }
                val result = control.call(PROCESS_METHOD, target)
                result.stringOrNull("home")?.let { home = it }
                processes[blockId] = result.optJSONObject("foreground")?.let(RexProcess::from) ?: RexProcess(null, null, null)
                titles[blockId] = RexTitle.from(title.await())
            }
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Rex pane lookup for $blockId failed", e)
        }
    }

    private fun replaceOrder(sessions: JSONArray?) {
        if (sessions == null) return
        val ids = mutableListOf<String>()
        for (i in 0 until sessions.length()) {
            val session = sessions.optJSONObject(i) ?: continue
            val id = session.stringOrNull("session_id") ?: continue
            ids += id
            session.stringOrNull("label")?.let { labels[id] = it }
        }
        order.clear()
        order += ids
        views.keys.retainAll(ids.toSet())
    }

    private fun storeView(view: JSONObject) {
        val id = view.stringOrNull("session_id") ?: return
        views[id] = view
        view.stringOrNull("label")?.let { labels[id] = it }
    }

    private fun publish() {
        knownBlocks = views.mapValues { (_, view) -> RexPanePicker.terminalBlockIds(view).toSet() }
        if (_state.value is RexBrowserState.Failed) return
        _state.value = RexBrowserState.Ready(order.mapNotNull(::summary))
    }

    private fun summary(sessionId: String): RexSessionSummary? {
        val view = views[sessionId] ?: return null
        val shared = RexPanePicker.sharedNameBlockIds(view).toSet()
        val customNamed = RexPanePicker.customNamedBlockIds(view).toSet()
        val panes =
            RexPanePicker.terminalBlocks(view).map { (blockId, label) ->
                val process = processes[blockId]
                val detail = process?.takeIf { blockId in shared }
                RexPane(
                    sessionId = sessionId,
                    blockId = blockId,
                    label = label,
                    labelIsCustom = blockId in customNamed,
                    title = titles[blockId]?.takeIf { it.isCurrent(process) }?.title,
                    program = detail?.name,
                    cwd = detail?.cwd?.let(::abbreviateHome),
                )
            }
        return RexSessionSummary(sessionId, labels[sessionId] ?: sessionId, panes)
    }

    private fun abbreviateHome(path: String): String {
        val homeDir = home?.trimEnd('/') ?: return path
        return when {
            path == homeDir -> "~"
            path.startsWith("$homeDir/") -> "~" + path.substring(homeDir.length)
            else -> path
        }
    }

    private fun fail(cause: Throwable) {
        if (_state.value is RexBrowserState.Failed) return
        _state.value = RexBrowserState.Failed(cause.message ?: "Lost the connection to Rex")
        control.close()
    }

    private data class RexProcess(val pid: Int?, val name: String?, val cwd: String?) {
        companion object {
            fun from(foreground: JSONObject) =
                RexProcess(
                    foreground.optInt("pid").takeIf { it > 0 },
                    foreground.stringOrNull("name"),
                    foreground.stringOrNull("cwd"),
                )
        }
    }

    /**
     * A terminal title and the process that set it. A title outlives its program (a
     * shell rarely resets it), so it only counts while that program is still in front.
     */
    private data class RexTitle(val title: String?, val ownerPid: Int?, val ownerName: String?) {
        fun isCurrent(process: RexProcess?): Boolean {
            if (title.isNullOrBlank()) return false
            if (ownerPid == null || process?.pid == null) return true
            return ownerPid == process.pid && (ownerName == null || ownerName == process.name)
        }

        companion object {
            fun from(payload: JSONObject): RexTitle {
                val owner = payload.optJSONObject("owner")
                return RexTitle(
                    payload.stringOrNull("title"),
                    owner?.optInt("pid")?.takeIf { it > 0 },
                    owner?.stringOrNull("name"),
                )
            }
        }
    }

    private companion object {
        const val TAG = "RexSessionBrowser"
        const val PROCESS_METHOD = "com.superlogical.terminal.process"
        const val TITLE_METHOD = "com.superlogical.terminal.title"
        // Enough to fill a list quickly without flooding the server with one request per pane.
        const val PANE_LOOKUP_CONCURRENCY = 6
        const val RELIST = "\u0000relist"
        const val REFRESH_DEBOUNCE_MS = 150L
    }
}

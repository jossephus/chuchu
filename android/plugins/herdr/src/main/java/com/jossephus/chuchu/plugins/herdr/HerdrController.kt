package com.jossephus.chuchu.plugins.herdr

import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrMultiplexer
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrSnapshot
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrSplitDirection
import com.jossephus.chuchu.plugins.herdr.protocol.MAX_RECENT_TABS
import com.jossephus.chuchu.plugins.herdr.protocol.appendHerdrStreamChunk
import com.jossephus.chuchu.plugins.herdr.protocol.desiredHerdrPaneStreams
import com.jossephus.chuchu.plugins.herdr.protocol.parseHerdrSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the herdr view renders for one chuchu tab. */
data class HerdrUiState(
    val snapshot: HerdrSnapshot? = null,
    /** Snapshot stream problem (connecting, herdr missing, ...), shown instead of panes. */
    val error: String? = null,
    /** Tab the user just switched to, shown before herdr's next snapshot confirms it. */
    val optimisticTabId: String? = null,
    /** Pane chuchu treats as focused locally (keyboard target), ahead of herdr. */
    val focusedPaneId: String? = null,
    /** Last failed command, e.g. a split herdr refused. */
    val actionError: String? = null,
) {
    val focusedTabId: String?
        get() = optimisticTabId ?: snapshot?.focusedTabId
}

/**
 * Applies a fresh snapshot. The local pane focus only bridges the gap until herdr agrees:
 * once herdr reports that pane focused, or the pane is gone (closed, or another client moved
 * things), herdr's own focus is authoritative again.
 */
internal fun HerdrUiState.withSnapshot(snapshot: HerdrSnapshot): HerdrUiState {
    val local = focusedPaneId
    val stillExists = local != null && snapshot.panes.any { it.paneId == local }
    val confirmed = local != null && snapshot.layouts.any { it.focusedPaneId == local }
    return copy(
        snapshot = snapshot,
        error = null,
        focusedPaneId = local.takeIf { stillExists && !confirmed },
    )
}

/**
 * herdr native mode for one chuchu tab: polls `herdr api snapshot`, keeps a [HerdrPane]
 * stream open for each visible (and recently visible) pane, and runs herdr commands. Every
 * channel runs on the tab's own SSH connection.
 *
 * The orchestration follows chuchu PR #69 (engine + view model), minus the extra SSH
 * connections it had to open before plugins could share the session's connection.
 */
class HerdrController(
    private val host: PluginHost,
    private val session: PluginSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val herdrSession = session.host.multiplexerSession

    /** Chuchu's name for the host, shown in the switcher home header. */
    val hostName: String
        get() = session.host.name
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private val recentTabIds = ArrayDeque<String>()

    private val _state = MutableStateFlow(HerdrUiState())
    val state: StateFlow<HerdrUiState> = _state.asStateFlow()

    private val _panes = MutableStateFlow<Map<String, HerdrPane>>(emptyMap())
    val panes: StateFlow<Map<String, HerdrPane>> = _panes.asStateFlow()

    init {
        scope.launch { pollSnapshots() }
        scope.launch {
            combine(_state, host.appVisible) { state, visible -> state to visible }.collect { (state, visible) ->
                reconcilePanes(state, visible)
            }
        }
    }

    fun close() {
        scope.cancel()
        _panes.value.values.forEach(HerdrPane::close)
        _panes.value = emptyMap()
    }

    /** Switch herdr tab, showing it immediately and confirming once herdr agrees. */
    fun focusTab(tabId: String) {
        val layout = _state.value.snapshot?.layouts?.firstOrNull { it.tabId == tabId }
        _state.update { it.copy(optimisticTabId = tabId, focusedPaneId = layout?.focusedPaneId) }
        rememberRecent(tabId)
        scope.launch {
            val ok = run(HerdrMultiplexer.focusTabCommand(tabId, herdrSession))
            if (ok) {
                withTimeoutOrNull(OPTIMISTIC_CONFIRM_MS) {
                    _state.first { it.snapshot?.focusedTabId == tabId }
                }
            }
            // Only clear our own guess; a newer focusTab() may have replaced it meanwhile.
            _state.update { if (it.optimisticTabId == tabId) it.copy(optimisticTabId = null) else it }
        }
    }

    fun focusPane(paneId: String) {
        _state.update { it.copy(focusedPaneId = paneId) }
        scope.launch { run(HerdrMultiplexer.focusPaneCommand(paneId, herdrSession)) }
    }

    fun focusWorkspace(workspaceId: String) {
        scope.launch { run(HerdrMultiplexer.focusWorkspaceCommand(workspaceId, herdrSession)) }
    }

    /** Jump straight to an agent's pane: its tab first, then the pane within it. */
    fun focusAgent(paneId: String, tabId: String) {
        _state.update { it.copy(optimisticTabId = tabId, focusedPaneId = paneId) }
        rememberRecent(tabId)
        scope.launch {
            if (run(HerdrMultiplexer.focusTabCommand(tabId, herdrSession))) {
                run(HerdrMultiplexer.focusPaneCommand(paneId, herdrSession))
            }
            _state.update { if (it.optimisticTabId == tabId) it.copy(optimisticTabId = null) else it }
        }
    }

    fun createWorkspace() {
        scope.launch { run(HerdrMultiplexer.createWorkspaceCommand(label = null, session = herdrSession)) }
    }

    /** Closes a workspace and every agent running in it; callers confirm first. */
    fun closeWorkspace(workspaceId: String) {
        scope.launch { run(HerdrMultiplexer.closeWorkspaceCommand(workspaceId, herdrSession)) }
    }

    fun splitFocused(direction: HerdrSplitDirection) {
        val paneId = focusedPaneId() ?: return
        scope.launch { run(HerdrMultiplexer.splitPaneCommand(paneId, direction, herdrSession)) }
    }

    fun closeFocusedPane() {
        val paneId = focusedPaneId() ?: return
        scope.launch { run(HerdrMultiplexer.closePaneCommand(paneId, herdrSession)) }
    }

    fun newTab() {
        val workspaceId = _state.value.snapshot?.focusedWorkspaceId ?: return
        scope.launch { run(HerdrMultiplexer.createTabCommand(workspaceId, herdrSession)) }
    }

    fun closeTab(tabId: String) {
        scope.launch { run(HerdrMultiplexer.closeTabCommand(tabId, herdrSession)) }
    }

    fun takeoverFocusedPane() {
        focusedPaneId()?.let { _panes.value[it]?.takeover() }
    }

    fun dismissActionError() {
        _state.update { it.copy(actionError = null) }
    }

    private fun focusedPaneId(): String? {
        val state = _state.value
        return state.focusedPaneId
            ?: state.snapshot?.layouts?.firstOrNull { it.tabId == state.focusedTabId }?.focusedPaneId
            ?: state.snapshot?.focusedPaneId
    }

    /** Runs a one-shot herdr command; records a failure for the view. Returns success. */
    private suspend fun run(command: String): Boolean {
        val result =
            try {
                val channel = session.exec(command) ?: return false.also { setActionError("session is not connected") }
                val out = scope.async { channel.stdout.toList() }
                val err = scope.async { channel.stderr.toList() }
                val exit = channel.exitCode.await()
                out.await()
                val stderr = err.await().joinToString("") { String(it) }.trim()
                if (exit == 0) null else stderr.ifBlank { "herdr exited with ${exit ?: "no status"}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "herdr command failed"
            }
        setActionError(result)
        poke.trySend(Unit)
        return result == null
    }

    private fun setActionError(message: String?) {
        _state.update { it.copy(actionError = message) }
    }

    private fun rememberRecent(tabId: String) {
        synchronized(recentTabIds) {
            recentTabIds.remove(tabId)
            recentTabIds.addFirst(tabId)
            while (recentTabIds.size > MAX_RECENT_TABS) recentTabIds.removeLast()
        }
    }

    // Streams cost a channel each: open the focused tab's panes plus a few recently visited
    // ones (so switching back is instant), and none while chuchu is in the background.
    private fun reconcilePanes(state: HerdrUiState, visible: Boolean) {
        val recent = synchronized(recentTabIds) { recentTabIds.toList() }
        val desired =
            desiredHerdrPaneStreams(
                snapshot = state.snapshot,
                nativeModeActive = true,
                foreground = visible,
                recentTabIds = recent,
                focusedTabIdOverride = state.optimisticTabId,
            )
        val current = _panes.value
        val closing = current.filterKeys { it !in desired }
        closing.values.forEach(HerdrPane::close)
        val opened =
            (desired - current.keys).associateWith { paneId ->
                HerdrPane(paneId, session, herdrSession, host.terminals, scope)
            }
        if (closing.isNotEmpty() || opened.isNotEmpty()) {
            _panes.value = current - closing.keys + opened
        }
    }

    // One long-lived `herdr api snapshot` loop on the remote, triggered by a newline on
    // stdin, so each poll costs a write instead of a new channel and process.
    private var retryDelayMs = INITIAL_RETRY_MS

    private suspend fun pollSnapshots() {
        while (true) {
            host.appVisible.first { it }
            val failure =
                try {
                    streamSnapshots()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "herdr snapshot stream failed"
                }
            _state.update { it.copy(error = failure ?: "herdr snapshot stream ended") }
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private suspend fun streamSnapshots() {
        val channel =
            session.exec(HerdrMultiplexer.snapshotStreamCommand(herdrSession))
                ?: throw IllegalStateException("session is not connected")
        try {
            val snapshots = Channel<HerdrSnapshot>(Channel.CONFLATED)
            val reader =
                scope.launch {
                    val buffer = StringBuilder()
                    channel.stdout.collect { chunk ->
                        appendHerdrStreamChunk(buffer, String(chunk, Charsets.UTF_8)).forEach { frame ->
                            parseHerdrSnapshot(frame)?.let { snapshots.trySend(it) }
                        }
                    }
                    snapshots.close()
                }
            var idleStretch = 0
            var previous: HerdrSnapshot? = null
            while (true) {
                host.appVisible.first { it }
                channel.write("\n".toByteArray())
                val snapshot =
                    withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { snapshots.receive() }
                        ?: throw IllegalStateException("herdr didn't answer; is it running on this host?")
                idleStretch = if (snapshot == previous) idleStretch + 1 else 0
                previous = snapshot
                _state.update { it.withSnapshot(snapshot) }
                retryDelayMs = INITIAL_RETRY_MS
                // Back off while nothing changes; any command or user action pokes a refresh.
                val wait = if (idleStretch >= IDLE_POLLS_BEFORE_BACKOFF) IDLE_CADENCE_MS else CADENCE_MS
                withTimeoutOrNull(wait) { poke.receive() }
            }
        } finally {
            channel.close()
        }
    }

    private companion object {
        const val CADENCE_MS = 3_000L
        const val IDLE_CADENCE_MS = 15_000L
        const val IDLE_POLLS_BEFORE_BACKOFF = 5
        const val SNAPSHOT_TIMEOUT_MS = 10_000L
        const val OPTIMISTIC_CONFIRM_MS = 4_000L
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
    }
}

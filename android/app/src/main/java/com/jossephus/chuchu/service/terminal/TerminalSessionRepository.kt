package com.jossephus.chuchu.service.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.PluginManager
import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.plugin.api.RemoteMultiplexerSession
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionRepository private constructor(application: Application) {
    private sealed interface PreflightAttempt<out T> {
        data class Completed<T>(val value: T) : PreflightAttempt<T>

        data class HostKeyVerificationRequired(
            val verification: PreflightHostKeyVerification,
        ) : PreflightAttempt<Nothing>
    }

    private data class PreflightHostKeyVerification(
        val prompt: HostKeyPrompt,
        val keyBytes: ByteArray,
    )

    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hostKeyStore =
        HostKeyStore(appContext.getSharedPreferences(HostKeyStore.PREFS_NAME, Application.MODE_PRIVATE))
    private val tailscaleStatusChecker = TailscaleStatusChecker(appContext)
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)
    private val settingsRepository = SettingsRepository.getInstance(appContext)
    private val plugins = PluginManager.instance
    private val hostRepository = HostRepository(AppDatabase.getInstance(appContext).hostProfileDao())
    private val portForwards =
        PortForwardManager(
            scope = scope,
            hostKeyStore = hostKeyStore,
            loadForwards = { hostId -> hostRepository.getById(hostId)?.portForwards.orEmpty() },
        )
    val portForwardStatus: StateFlow<Map<Long, PortForwardStatus>> = portForwards.status

    private fun publishTerminalClipboard(tabId: String, text: String) {
        if (!canPublishTerminalClipboard(tabId)) return
        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal clipboard", text))
    }

    private fun canPublishTerminalClipboard(tabId: String): Boolean {
        if (!settingsRepository.remoteClipboardWriteEnabled.value) return false
        return attachedClients > 0 && _activeTabId.value == tabId
    }

    private val _tabs = MutableStateFlow<List<TabSession>>(emptyList())
    val tabs: StateFlow<List<TabSession>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<TabSession?> =
        combine(_tabs, _activeTabId) { tabs, id -> tabs.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val sessionState: StateFlow<SessionState> =
        activeTab
            .flatMapLatest { tab -> tab?.sessionState ?: flowOf(SessionState()) }
            .stateIn(scope, SharingStarted.Eagerly, SessionState())

    private val _preflightHostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    private val preflightMutex = Mutex()
    private var preflightEngine: TerminalSessionEngine? = null
    @Volatile private var preflightHostKeyDecision: CompletableDeferred<Boolean>? = null
    @Volatile private var preflightGeneration = 0L

    private val activeHostKeyPrompt: StateFlow<HostKeyPrompt?> =
        activeTab
            .flatMapLatest { tab -> tab?.hostKeyPrompt ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> =
        combine(_preflightHostKeyPrompt, activeHostKeyPrompt) { preflight, active -> preflight ?: active }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val connectedHostIds: StateFlow<Set<Long>> =
        _tabs
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tabs.map { tab -> tab.statusUpdates().map { status -> tab to status } }) {
                        it.toList()
                    }
                }
            }
            .map { pairs ->
                pairs
                    .asSequence()
                    .filter { (_, status) -> status.isAlive() }
                    .mapNotNull { (tab, _) -> tab.spec.hostId }
                    .toSet()
            }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var attachedClients = 0
    private var foregroundServiceRunning = false
    private var foregroundNotificationLabel: String? = null
    private var browserWatch: Pair<Long, Job>? = null

    private val forwardingSpecs: Flow<Map<Long, TabSpec>> =
        _tabs
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tabs.map { tab -> tab.statusUpdates().map { status -> tab to status } }) {
                        it.toList()
                    }
                }
            }
            .map(::forwardingSpecsFor)

    init {
        scope.launch { activeTab.collect(::followBrowserGroup) }
        scope.launch {
            forwardingSpecs
                .distinctUntilChanged { old, new -> old.keys == new.keys }
                .collect { portForwards.sync(it) }
        }
        scope.launch {
        combine(_tabs, _activeTabId) { tabs, _ -> tabs }
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tabs.map { tab -> tab.statusUpdates() }) { it.toList() }
                }
            }
            .collect { statuses ->
                val anyAlive = statuses.any { it.isAlive() }
                val label = if (anyAlive) currentNotificationLabel() else null
                if (anyAlive && (!foregroundServiceRunning || foregroundNotificationLabel != label)) {
                    SessionForegroundService.start(appContext, label ?: "Active session")
                    foregroundServiceRunning = true
                    foregroundNotificationLabel = label
                } else if (!anyAlive && foregroundServiceRunning) {
                    SessionForegroundService.stop(appContext)
                    foregroundServiceRunning = false
                    foregroundNotificationLabel = null
                }
            }
    }
    }

    fun attachClient() {
        attachedClients += 1
    }

    fun detachClient() {
        attachedClients = (attachedClients - 1).coerceAtLeast(0)
    }

    // Session state changes on every frame of terminal output; these watchers only
    // care whether a tab is alive, so follow its status alone.
    private fun TabSession.statusUpdates(): Flow<SessionStatus> = sessionState.map { it.status }.distinctUntilChanged()

    private fun SessionStatus.isAlive(): Boolean =
        this == SessionStatus.Connecting || this == SessionStatus.Connected || this == SessionStatus.Reconnecting

    private fun currentNotificationLabel(): String {
        val tabs = _tabs.value
        if (tabs.isEmpty()) return "Active session"
        val active = tabs.firstOrNull { it.id == _activeTabId.value } ?: tabs.first()
        if (tabs.size == 1) return active.spec.notificationLabel
        return "${tabs.size} sessions  ·  ${active.spec.notificationLabel}"
    }

    fun tabsForHost(hostId: Long?): List<TabSession> =
        _tabs.value.filter { it.spec.hostId == hostId }

    fun openMultiplexerSessionNamesForHost(
        hostId: Long?,
        multiplexer: MultiplexerType,
    ): List<String> = tabsForHost(hostId)
        .filter { it.spec.multiplexer == multiplexer }
        .mapNotNull { it.spec.multiplexerSessionName }

    suspend fun resolveMultiplexerSessionName(
        spec: TabSpec,
        reuseDetachedChuchuSession: Boolean = false,
    ): String = withPreflightEngine { engine ->
        engine.resolveMultiplexerSessionName(
            spec = spec,
            localSessionNames = openMultiplexerSessionNamesForHost(
                hostId = spec.hostId,
                multiplexer = spec.multiplexer ?: MultiplexerType.Default,
            ),
            reuseDetachedChuchuSession = reuseDetachedChuchuSession,
        )
    }

    suspend fun listMultiplexerSessions(spec: TabSpec): List<RemoteMultiplexerSession> =
        withPreflightEngine { engine -> engine.listMultiplexerSessions(spec) }

    private suspend fun <T> withPreflightEngine(block: suspend (TerminalSessionEngine) -> T): T {
        val generation = preflightGeneration
        return preflightMutex.withLock { runPreflightWithHostKeyRetries(generation, block) }
    }

    private suspend fun <T> runPreflightWithHostKeyRetries(
        generation: Long,
        block: suspend (TerminalSessionEngine) -> T,
    ): T {
        while (true) {
            when (val attempt = runPreflightAttempt(block)) {
                is PreflightAttempt.Completed -> return attempt.value
                is PreflightAttempt.HostKeyVerificationRequired -> {
                    if (!awaitPreflightHostKeyDecision(generation, attempt.verification)) {
                        throw IllegalStateException("Host key rejected")
                    }
                    ensurePreflightCurrent(generation)
                    val prompt = attempt.verification.prompt
                    hostKeyStore.saveKey(
                        prompt.host,
                        prompt.port,
                        prompt.algorithm,
                        attempt.verification.keyBytes,
                    )
                }
            }
        }
    }

    private fun ensurePreflightCurrent(generation: Long) {
        check(preflightGeneration == generation) { "Disconnected while preparing the connection" }
    }

    private suspend fun <T> runPreflightAttempt(
        block: suspend (TerminalSessionEngine) -> T,
    ): PreflightAttempt<T> {
        var verification: PreflightHostKeyVerification? = null
        val engine =
            TerminalSessionEngine(
                {},
                scope,
                newLocalShellService(),
                hostKeyStore,
                tailscaleStatusChecker,
                plugins.multiplexers,
                plugins.transports,
                onHostKeyVerificationRequired = { prompt, keyBytes ->
                    verification = PreflightHostKeyVerification(prompt, keyBytes)
                },
            )
        preflightEngine = engine
        return try {
            PreflightAttempt.Completed(block(engine))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            verification?.let { PreflightAttempt.HostKeyVerificationRequired(it) } ?: throw error
        } finally {
            // A preflight key check must not retain a live libssh2 session while the
            // user is deciding whether to trust the key.
            if (preflightEngine === engine) {
                preflightEngine = null
            }
            engine.dispose()
        }
    }

    private suspend fun awaitPreflightHostKeyDecision(
        generation: Long,
        verification: PreflightHostKeyVerification,
    ): Boolean {
        val decision = CompletableDeferred<Boolean>()
        preflightHostKeyDecision = decision
        return try {
            ensurePreflightCurrent(generation)
            _preflightHostKeyPrompt.value = verification.prompt
            decision.await()
        } finally {
            if (preflightHostKeyDecision === decision) {
                preflightHostKeyDecision = null
            }
            _preflightHostKeyPrompt.value = null
        }
    }

    fun openTab(spec: TabSpec, select: Boolean = true, connect: Boolean = true): TabSession {
        val id = UUID.randomUUID().toString()
        val engine =
            TerminalSessionEngine(
                { text -> publishTerminalClipboard(id, text) },
                scope,
                newLocalShellService(),
                hostKeyStore,
                tailscaleStatusChecker,
                plugins.multiplexers,
                plugins.transports,
            )
        val tab = TabSession(id, spec, engine)
        _tabs.value = _tabs.value + tab
        if (select) {
            activate(_activeTabId.value, next = tab)
            _activeTabId.value = id
        }
        if (connect) reconnectTab(tab)
        return tab
    }

    fun selectTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (_activeTabId.value == id) return
        activate(_activeTabId.value, next = tab)
        _activeTabId.value = id
        reconnectIfReleased(tab)
    }

    private fun reconnectIfReleased(tab: TabSession) {
        if (tab.connectsOnlyWhileActive() && tab.sessionState.value.status == SessionStatus.Disconnected) {
            reconnectTab(tab)
        }
    }

    fun leaveHost(hostId: Long) {
        val tabs = tabsForHost(hostId)
        if (tabs.none { it.followsBrowserGroup() }) return
        closeTabs(tabs.mapTo(HashSet()) { it.id })
    }

    fun duplicateSpec(tab: TabSession): TabSpec {
        val provider = plugins.transports[tab.spec.transportId] ?: return tab.spec
        val target = provider.duplicateTarget(tab.engine.attachedTarget.value)
        return tab.spec.copy(transportTarget = target?.let { TransportTarget(it, creates = true) })
    }

    private fun arrangeHostTabs(hostId: Long, orderedIds: List<String>) {
        val current = _tabs.value
        val rank = orderedIds.withIndex().associate { it.value to it.index }
        val hostTabs = current.filter { it.spec.hostId == hostId }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        if (hostTabs.map { it.id } == current.filter { it.spec.hostId == hostId }.map { it.id }) return
        val queue = ArrayDeque(hostTabs)
        _tabs.value = current.map { if (it.spec.hostId == hostId) queue.removeFirst() else it }
    }

    /**
     * Makes [next] the tab on screen. For transports that connect only while active,
     * the tabs on either side of it stay connected too, like the official Rex client,
     * so swiping to a neighbour shows it at once; tabs further away are released.
     * Only the active tab claims its terminal (see TransportConnection.setActive).
     */
    private fun activate(previousId: String?, next: TabSession) {
        _tabs.value.firstOrNull { it.id == previousId && it !== next }?.engine?.setActive(false)
        next.fitCanvas()
        next.engine.setActive(true)
        warmNeighbours(next)
    }

    private fun warmNeighbours(next: TabSession) {
        if (!next.connectsOnlyWhileActive()) return
        val hostTabs = tabsForHost(next.spec.hostId).filter { it.connectsOnlyWhileActive() }
        val index = hostTabs.indexOf(next)
        val warm = hostTabs.subList(maxOf(0, index - WARM_NEIGHBOURS), minOf(hostTabs.size, index + WARM_NEIGHBOURS + 1)).toSet()
        for (tab in hostTabs) {
            when {
                tab === next -> Unit
                tab in warm ->
                    if (tab.sessionState.value.status == SessionStatus.Disconnected) {
                        tab.fitCanvas()
                        reconnectIfReleased(tab)
                    }
                // A tab without a target would reopen a different pane, so it is never released.
                tab.target != null && tab.sessionState.value.status != SessionStatus.Disconnected -> tab.engine.disconnect()
            }
        }
    }

    private fun TabSession.connectsOnlyWhileActive(): Boolean =
        plugins.transports[spec.transportId]?.behavior?.activeTabOnly == true

    private fun TabSession.followsBrowserGroup(): Boolean =
        plugins.transports[spec.transportId]?.behavior?.tabsFollowBrowserGroup == true

    private fun followBrowserGroup(tab: TabSession?) {
        val hostId = tab?.spec?.hostId ?: return
        if (!tab.followsBrowserGroup()) return
        val factory = plugins.transports[tab.spec.transportId]?.hostBrowser ?: return
        if (browserWatch?.first == hostId && browserWatch?.second?.isActive == true) return
        browserWatch?.second?.cancel()
        val job =
            scope.launch {
                val browser = runCatching { factory.open(tab.spec.host) }.getOrNull() ?: return@launch
                try {
                    launch { runCatching { browser.start() } }
                    val watcher = coroutineContext[Job]
                    launch {
                        _tabs.first { tabs -> tabs.none { it.spec.hostId == hostId } }
                        watcher?.cancel()
                    }
                    val activeTarget =
                        activeTab.flatMapLatest { active ->
                            if (active?.spec?.hostId == hostId) active.engine.attachedTarget else flowOf(null)
                        }
                    combine(browser.state, activeTarget) { state, target -> state to target }
                        .collect { (state, target) ->
                            if (state !is HostBrowserState.Ready || target == null) return@collect
                            state.groups.firstOrNull { group -> group.items.any { it.target == target } }
                                ?.let { mirrorBrowserGroup(hostId, it) }
                        }
                } finally {
                    browser.close()
                }
            }
        browserWatch = hostId to job
    }

    private fun mirrorBrowserGroup(hostId: Long, group: BrowserGroup) {
        val hostTabs = tabsForHost(hostId)
        val template = hostTabs.firstOrNull()?.spec ?: return
        val activeId = _activeTabId.value
        val itemTargets = group.items.mapTo(HashSet()) { it.target }
        closeTabs(
            hostTabs
                .filter { tab -> tab.id != activeId && tab.target.let { it != null && it !in itemTargets } }
                .mapTo(HashSet()) { it.id },
        )
        val opening =
            hostTabs.any { tab ->
                tab.target == null &&
                    tab.sessionState.value.status.let { it == SessionStatus.Connecting || it == SessionStatus.Reconnecting }
            }
        val byTarget = hostTabs.filter { it.target != null }.associateBy { it.target }
        val ordered =
            group.items.mapNotNull { item ->
                val tab =
                    byTarget[item.target]
                        ?: if (opening) {
                            null
                        } else {
                            openTab(template.copy(transportTarget = TransportTarget(item.target)), select = false, connect = false)
                        }
                tab?.titleOverride?.value = item.title
                tab
            }
        arrangeHostTabs(hostId, ordered.map { it.id })
        // The mirrored tabs appear after the opened one, so its neighbours exist only now.
        activeTab.value?.takeIf { it.spec.hostId == hostId }?.let(::warmNeighbours)
    }

    fun closeTab(id: String) = closeTabs(setOf(id))

    private fun closeTabs(ids: Set<String>) {
        val closing = _tabs.value.filter { it.id in ids }
        if (closing.isEmpty()) return
        val remaining = _tabs.value.filterNot { it.id in ids }
        _tabs.value = remaining
        val active = closing.firstOrNull { it.id == _activeTabId.value }
        if (active != null) {
            val next = remaining.firstOrNull { it.spec.hostId == active.spec.hostId } ?: remaining.firstOrNull()
            next?.let { activate(previousId = null, next = it) }
            _activeTabId.value = next?.id
            next?.let(::reconnectIfReleased)
        }
        closing.forEach { it.engine.dispose() }
    }

    fun reconnectActive() {
        val tab = activeTab.value ?: return
        reconnectTab(tab)
    }

    fun switchActiveMultiplexerSession(sessionName: String): Boolean {
        val tab = activeTab.value ?: return false
        val type = tab.spec.multiplexer ?: MultiplexerType.Default
        plugins.multiplexers[type.id] ?: return false
        val updatedSpec = tab.spec.copy(
            multiplexer = type,
            multiplexerSessionName = sessionName,
            multiplexerCreateIfMissing = false,
        )
        tab.spec = updatedSpec
        reconnectTab(tab)
        return true
    }

    fun reconnectTab(tab: TabSession) {
        val spec = tab.spec
        tab.engine.connect(
            host = spec.host,
            port = spec.port,
            username = spec.username,
            password = spec.password,
            authMethod = spec.authMethod,
            publicKeyOpenSsh = spec.publicKeyOpenSsh,
            privateKeyPem = spec.privateKeyPem,
            keyPassphrase = spec.keyPassphrase,
            transport = spec.transport,
            sessionKey = sessionKeyFor(tab),
            postConnectCommand = spec.postConnectCommand,
            multiplexer = spec.multiplexer,
            multiplexerSessionName = spec.multiplexerSessionName,
            multiplexerCreateIfMissing = spec.multiplexerCreateIfMissing,
            jumpHost = spec.jumpHost,
            transportId = spec.transportId,
            transportTarget = spec.transportTarget?.value,
        )
    }

    fun disconnect() {
        preflightGeneration += 1
        preflightHostKeyDecision?.cancel()
        preflightHostKeyDecision = null
        preflightEngine?.dispose()
        preflightEngine = null
        _preflightHostKeyPrompt.value = null
        val tabs = _tabs.value
        _tabs.value = emptyList()
        _activeTabId.value = null
        tabs.forEach { it.engine.dispose() }
    }

    private fun activeEngine(): TerminalSessionEngine? = activeTab.value?.engine

    private fun sessionKeyFor(tab: TabSession): String =
        if (tab.spec.transport == Transport.LocalShell) {
            "local-shell:${tab.id}"
        } else {
            tab.spec.sessionKey
        }

    private fun newLocalShellService(): NativeLocalShellService =
        NativeLocalShellService(appContext.filesDir, appContext.cacheDir)

    private fun activeSftpEngine(): TerminalSessionEngine? =
        activeTab.value?.takeIf { it.spec.transport.supportsSftp }?.engine

    private fun sftpTab(tabId: String): TabSession? =
        _tabs.value.firstOrNull { it.id == tabId && it.spec.transport.supportsSftp }

    private fun sftpEngineForTab(tabId: String): TerminalSessionEngine? = sftpTab(tabId)?.engine

    private suspend fun <T> withSftpSession(
        tabId: String,
        operation: suspend (TerminalSessionEngine) -> T,
    ): T? {
        val tab = sftpTab(tabId) ?: return null
        awaitSessionReady(tab)
        return try {
            operation(tab.engine)
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            if (!awaitReconnectAfterFailure(tab)) throw err
            operation(tab.engine)
        }
    }

    private suspend fun awaitSessionReady(tab: TabSession) {
        val settled =
            withTimeoutOrNull(SFTP_RECONNECT_TIMEOUT_MS) {
                tab.sessionState.first {
                    it.status != SessionStatus.Connecting && it.status != SessionStatus.Reconnecting
                }
            } ?: throw IllegalStateException("Timed out waiting for the session to reconnect")
        if (settled.status != SessionStatus.Connected) {
            throw IllegalStateException(
                settled.error?.let { "Session is not connected: $it" } ?: "Session is not connected"
            )
        }
    }

    private suspend fun awaitReconnectAfterFailure(tab: TabSession): Boolean {
        withTimeoutOrNull(SFTP_DROP_DETECTION_MS) {
            tab.sessionState.first { it.status != SessionStatus.Connected }
        } ?: return false
        awaitSessionReady(tab)
        return true
    }

    fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        canvasSize = CanvasSize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
        activeEngine()?.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    private data class CanvasSize(
        val cols: Int,
        val rows: Int,
        val cellWidth: Int,
        val cellHeight: Int,
        val screenWidth: Int,
        val screenHeight: Int,
    )

    // The canvas is shared by all tabs and only reports a change, so a tab that was
    // never on screen (a preloaded neighbour) gets the size from here.
    @Volatile private var canvasSize: CanvasSize? = null

    private fun TabSession.fitCanvas() {
        val size = canvasSize ?: return
        engine.resize(size.cols, size.rows, size.cellWidth, size.cellHeight, size.screenWidth, size.screenHeight)
    }

    fun scroll(delta: Int, x: Float, y: Float) {
        activeEngine()?.scroll(delta, x, y)
    }

    fun onUserInteraction() {
        activeEngine()?.onUserInteraction()
    }

    fun scrollToActive() {
        activeEngine()?.scrollToActive()
    }

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) {
        activeEngine()?.writeKey(key, codepoint, mods, action, utf8)
    }

    fun writeText(text: String) {
        activeEngine()?.writeText(text)
    }

    fun writePaste(text: String) {
        activeEngine()?.writePaste(text)
    }

    fun sendFocusEvent(focused: Boolean) {
        activeEngine()?.sendFocusEvent(focused)
    }

    fun sendMouseEvent(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) {
        activeEngine()?.sendMouseEvent(action, button, mods, x, y, anyButtonPressed, trackLastCell)
    }

    fun setColorScheme(isDark: Boolean) {
        _tabs.value.forEach { it.engine.setColorScheme(isDark) }
    }

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        _tabs.value.forEach { it.engine.setDefaultColors(fg, bg, cursor, palette) }
    }

    fun respondToHostKey(accepted: Boolean) {
        preflightHostKeyDecision?.let { decision ->
            decision.complete(accepted)
            return
        }
        activeEngine()?.respondToHostKey(accepted)
    }

    suspend fun sftpListDirectory(path: String): List<String> =
        activeSftpEngine()?.sftpListDirectory(path) ?: emptyList()

    suspend fun sftpListDirectory(tabId: String, path: String): List<String> =
        withSftpSession(tabId) { it.sftpListDirectory(path) } ?: emptyList()

    suspend fun sftpRealpath(path: String): String = activeSftpEngine()?.sftpRealpath(path) ?: "/"

    suspend fun sftpRealpath(tabId: String, path: String): String =
        withSftpSession(tabId) { it.sftpRealpath(path) } ?: "/"

    suspend fun sftpOpenWrite(path: String) {
        activeSftpEngine()?.sftpOpenWrite(path)
    }

    suspend fun sftpOpenWrite(tabId: String, path: String) {
        withSftpSession(tabId) { it.sftpOpenWrite(path) }
    }

    suspend fun sftpWriteChunk(data: ByteArray): Int = activeSftpEngine()?.sftpWriteChunk(data) ?: 0

    suspend fun sftpWriteChunk(tabId: String, data: ByteArray): Int =
        sftpEngineForTab(tabId)?.sftpWriteChunk(data) ?: 0

    suspend fun sftpCloseWrite() {
        activeSftpEngine()?.sftpCloseWrite()
    }

    suspend fun sftpCloseWrite(tabId: String) {
        sftpEngineForTab(tabId)?.sftpCloseWrite()
    }

    suspend fun sftpReadFile(tabId: String, path: String, maxBytes: Int): ByteArray =
        withSftpSession(tabId) { it.sftpReadFile(path, maxBytes) } ?: ByteArray(0)

    suspend fun sftpDelete(tabId: String, path: String, isDirectory: Boolean) {
        withSftpSession(tabId) { it.sftpDelete(path, isDirectory) }
    }

    companion object {
        private const val SFTP_RECONNECT_TIMEOUT_MS = 20_000L
        // Tabs kept connected on each side of the active one for active-only transports.
        private const val WARM_NEIGHBOURS = 1
        private const val SFTP_DROP_DETECTION_MS = 1_000L

        @Volatile private var instance: TerminalSessionRepository? = null

        fun getInstance(application: Application): TerminalSessionRepository {
            return instance
                ?: synchronized(this) {
                    instance
                        ?: TerminalSessionRepository(application).also { created ->
                            instance = created
                        }
                }
        }
    }
}

internal fun forwardingSpecsFor(tabs: List<Pair<TabSession, SessionStatus>>): Map<Long, TabSpec> =
    tabs
        .asSequence()
        .filter { (tab, status) ->
            tab.spec.transport != Transport.LocalShell &&
                (status == SessionStatus.Connected || status == SessionStatus.Reconnecting)
        }
        .mapNotNull { (tab, _) -> tab.spec.hostId?.let { it to tab.spec } }
        .toMap()

package com.jossephus.chuchu.service.terminal

import android.app.Application
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import java.util.UUID
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionRepository private constructor(application: Application) {
    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hostKeyStore =
        HostKeyStore(appContext.getSharedPreferences("host_keys", Application.MODE_PRIVATE))
    private val tailscaleStatusChecker = TailscaleStatusChecker(appContext)

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
    private var preflightPromptJob: Job? = null

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
                    combine(tabs.map { tab -> tab.sessionState.map { state -> tab to state } }) {
                        it.toList()
                    }
                }
            }
            .map { pairs ->
                pairs
                    .asSequence()
                    .filter { (_, state) ->
                        state.status == SessionStatus.Connecting ||
                            state.status == SessionStatus.Connected ||
                            state.status == SessionStatus.Reconnecting
                    }
                    .mapNotNull { (tab, _) -> tab.spec.hostId }
                    .toSet()
            }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var attachedClients = 0
    private var foregroundServiceRunning = false
    private var foregroundNotificationLabel: String? = null

    init {
        scope.launch {
            combine(_tabs, _activeTabId) { tabs, _ -> tabs }
                .flatMapLatest { tabs ->
                    if (tabs.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(tabs.map { tab -> tab.sessionState.map { tab to it } }) {
                            it.toList()
                        }
                    }
                }
                .collect { pairs ->
                    val anyAlive =
                        pairs.any { (_, state) ->
                            state.status == SessionStatus.Connecting ||
                                state.status == SessionStatus.Connected ||
                                state.status == SessionStatus.Reconnecting
                        }
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

    suspend fun resolveMultiplexerSessionName(spec: TabSpec): String = withPreflightEngine { engine ->
        engine.resolveMultiplexerSessionName(
            spec = spec,
            localSessionNames = openMultiplexerSessionNamesForHost(
                hostId = spec.hostId,
                multiplexer = spec.multiplexer ?: MultiplexerRegistry.defaultType,
            ),
        )
    }

    suspend fun listMultiplexerSessions(spec: TabSpec): List<RemoteMultiplexerSession> =
        withPreflightEngine { engine -> engine.listMultiplexerSessions(spec) }

    private suspend fun <T> withPreflightEngine(block: suspend (TerminalSessionEngine) -> T): T =
        preflightMutex.withLock {
            val engine =
                TerminalSessionEngine(
                    scope,
                    newLocalShellService(),
                    hostKeyStore,
                    tailscaleStatusChecker,
                )
            preflightEngine = engine
            val promptJob = scope.launch {
                engine.hostKeyPrompt.collect { prompt ->
                    if (preflightEngine === engine) {
                        _preflightHostKeyPrompt.value = prompt
                    }
                }
            }
            preflightPromptJob = promptJob
            return@withLock try {
                block(engine)
            } finally {
                promptJob.cancel()
                if (preflightEngine === engine) {
                    preflightEngine = null
                    if (preflightPromptJob === promptJob) preflightPromptJob = null
                    _preflightHostKeyPrompt.value = null
                }
                engine.dispose()
            }
        }

    fun openTab(spec: TabSpec): TabSession {
        val id = UUID.randomUUID().toString()
        val engine =
            TerminalSessionEngine(
                scope,
                newLocalShellService(),
                hostKeyStore,
                tailscaleStatusChecker,
            )
        val tab = TabSession(id, spec, engine)
        _tabs.value = _tabs.value + tab
        _activeTabId.value = id
        engine.connect(
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
        )
        return tab
    }

    fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) {
            _activeTabId.value = id
        }
    }

    fun closeTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            val nextSameHost = remaining.firstOrNull { it.spec.hostId == tab.spec.hostId }
            _activeTabId.value = nextSameHost?.id ?: remaining.firstOrNull()?.id
        }
        tab.engine.dispose()
    }

    fun reconnectActive() {
        val tab = activeTab.value ?: return
        reconnectTab(tab)
    }

    fun switchActiveMultiplexerSession(sessionName: String): Boolean {
        val tab = activeTab.value ?: return false
        val type = tab.spec.multiplexer ?: MultiplexerRegistry.defaultType
        MultiplexerRegistry.forType(type) ?: return false
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
        )
    }

    fun disconnect() {
        preflightPromptJob?.cancel()
        preflightPromptJob = null
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
        activeTab.value?.takeIf { it.spec.transport != Transport.LocalShell }?.engine

    private fun sftpEngineForTab(tabId: String): TerminalSessionEngine? =
        _tabs.value.firstOrNull { it.id == tabId && it.spec.transport != Transport.LocalShell }?.engine

    fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        activeEngine()?.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun scroll(delta: Int, x: Float, y: Float) {
        activeEngine()?.scroll(delta, x, y)
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
        if (_preflightHostKeyPrompt.value != null) {
            preflightEngine?.respondToHostKey(accepted)
        } else {
            activeEngine()?.respondToHostKey(accepted)
        }
    }

    suspend fun sftpListDirectory(path: String): List<String> =
        activeSftpEngine()?.sftpListDirectory(path) ?: emptyList()

    suspend fun sftpListDirectory(tabId: String, path: String): List<String> =
        sftpEngineForTab(tabId)?.sftpListDirectory(path) ?: emptyList()

    suspend fun sftpRealpath(path: String): String = activeSftpEngine()?.sftpRealpath(path) ?: "/"

    suspend fun sftpRealpath(tabId: String, path: String): String =
        sftpEngineForTab(tabId)?.sftpRealpath(path) ?: "/"

    suspend fun sftpOpenWrite(path: String) {
        activeSftpEngine()?.sftpOpenWrite(path)
    }

    suspend fun sftpOpenWrite(tabId: String, path: String) {
        sftpEngineForTab(tabId)?.sftpOpenWrite(path)
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
        sftpEngineForTab(tabId)?.sftpReadFile(path, maxBytes) ?: ByteArray(0)

    suspend fun sftpDelete(tabId: String, path: String, isDirectory: Boolean) {
        sftpEngineForTab(tabId)?.sftpDelete(path, isDirectory)
    }

    companion object {
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

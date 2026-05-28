package com.jossephus.chuchu.service.terminal

import android.app.Application
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> =
        activeTab
            .flatMapLatest { tab -> tab?.hostKeyPrompt ?: flowOf(null) }
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

    init {
        scope.launch {
            _tabs
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
                    if (anyAlive) {
                        SessionForegroundService.start(appContext, currentNotificationLabel())
                    } else {
                        SessionForegroundService.stop(appContext)
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
        val active = activeTab.value ?: tabs.first()
        if (tabs.size == 1) return active.spec.notificationLabel
        return "${tabs.size} sessions  ·  ${active.spec.notificationLabel}"
    }

    fun tabsForHost(hostId: Long?): List<TabSession> =
        _tabs.value.filter { it.spec.hostId == hostId }

    fun openTab(spec: TabSpec): TabSession {
        val id = UUID.randomUUID().toString()
        val engine =
            TerminalSessionEngine(
                scope,
                appContext.filesDir.toPath(),
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
            sessionKey = spec.sessionKey,
            postConnectCommand = spec.postConnectCommand,
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
            sessionKey = spec.sessionKey,
            postConnectCommand = spec.postConnectCommand,
        )
    }

    fun disconnect() {
        val tabs = _tabs.value
        _tabs.value = emptyList()
        _activeTabId.value = null
        tabs.forEach { it.engine.dispose() }
    }

    private fun activeEngine(): TerminalSessionEngine? = activeTab.value?.engine

    private fun engineForTab(tabId: String): TerminalSessionEngine? =
        _tabs.value.firstOrNull { it.id == tabId }?.engine

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

    fun scroll(delta: Int) {
        activeEngine()?.scroll(delta)
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
        activeEngine()?.respondToHostKey(accepted)
    }

    suspend fun sftpListDirectory(path: String): List<String> =
        activeEngine()?.sftpListDirectory(path) ?: emptyList()

    suspend fun sftpListDirectory(tabId: String, path: String): List<String> =
        engineForTab(tabId)?.sftpListDirectory(path) ?: emptyList()

    suspend fun sftpRealpath(path: String): String = activeEngine()?.sftpRealpath(path) ?: "/"

    suspend fun sftpRealpath(tabId: String, path: String): String =
        engineForTab(tabId)?.sftpRealpath(path) ?: "/"

    suspend fun sftpOpenWrite(path: String) {
        activeEngine()?.sftpOpenWrite(path)
    }

    suspend fun sftpOpenWrite(tabId: String, path: String) {
        engineForTab(tabId)?.sftpOpenWrite(path)
    }

    suspend fun sftpWriteChunk(data: ByteArray): Int = activeEngine()?.sftpWriteChunk(data) ?: 0

    suspend fun sftpWriteChunk(tabId: String, data: ByteArray): Int =
        engineForTab(tabId)?.sftpWriteChunk(data) ?: 0

    suspend fun sftpCloseWrite() {
        activeEngine()?.sftpCloseWrite()
    }

    suspend fun sftpCloseWrite(tabId: String) {
        engineForTab(tabId)?.sftpCloseWrite()
    }

    suspend fun sftpReadFile(tabId: String, path: String, maxBytes: Int): ByteArray =
        engineForTab(tabId)?.sftpReadFile(path, maxBytes) ?: ByteArray(0)

    suspend fun sftpDelete(tabId: String, path: String, isDirectory: Boolean) {
        engineForTab(tabId)?.sftpDelete(path, isDirectory)
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

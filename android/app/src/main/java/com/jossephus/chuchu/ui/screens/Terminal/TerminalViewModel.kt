package com.jossephus.chuchu.ui.screens.Terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Multiplexer
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.multiplexer.MultiplexerAvailability
import com.jossephus.chuchu.service.multiplexer.MultiplexerCommandResult
import com.jossephus.chuchu.service.multiplexer.MultiplexerConnectionSpec
import com.jossephus.chuchu.service.multiplexer.MultiplexerInstallCandidate
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerService
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.multiplexer.TmuxMultiplexerCommands
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.FileBrowserEntry
import com.jossephus.chuchu.ui.screens.Files.FileBrowserUiState
import com.jossephus.chuchu.ui.screens.Files.FileEntryType
import com.jossephus.chuchu.ui.screens.Files.FileSort
import com.jossephus.chuchu.ui.screens.Files.UploadProgress
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val tailscaleStatusChecker = TailscaleStatusChecker(application)
    private val sessionRepository = TerminalSessionRepository.getInstance(application)
    private val database = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(database.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(database.sshKeyDao())
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val hostKeyStore = HostKeyStore(
        application.getSharedPreferences("host_keys", Application.MODE_PRIVATE),
    )
    private val remoteMultiplexerService = RemoteMultiplexerService(
        hostKeyStore = hostKeyStore,
        hostKeyPolicy = ::verifyMultiplexerHostKey,
    )

    private sealed class PendingMultiplexerAction(open val spec: TabSpec) {
        data class Open(override val spec: TabSpec) : PendingMultiplexerAction(spec)
        data class Reconnect(val tabId: String, override val spec: TabSpec) : PendingMultiplexerAction(spec)
    }

    private val _multiplexerState = MutableStateFlow(MultiplexerUiState())
    val multiplexerState: StateFlow<MultiplexerUiState> = _multiplexerState.asStateFlow()
    private var pendingMultiplexerAction: PendingMultiplexerAction? = null
    private var multiplexerActionGeneration = 0L
    private var multiplexerSessionListGeneration = 0L
    private val multiplexerHostKeyDecisionLock = Any()
    private var multiplexerHostKeyDecision: CompletableDeferred<Boolean>? = null

    private val _tailscaleActive = MutableStateFlow(tailscaleStatusChecker.isActive())
    val tailscaleActive: StateFlow<Boolean> = _tailscaleActive.asStateFlow()

    val tabs: StateFlow<List<TabSession>> = sessionRepository.tabs
    val activeTabId: StateFlow<String?> = sessionRepository.activeTabId
    val activeTab: StateFlow<TabSession?> = sessionRepository.activeTab
    val sessionState: StateFlow<SessionState> = sessionRepository.sessionState
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt
    val hosts: StateFlow<List<HostProfile>> =
        hostRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hostsLoaded: StateFlow<Boolean> =
        hosts.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        sessionRepository.attachClient()
    }

    private val _connectionTabByTab = MutableStateFlow<Map<String, ConnectionTab>>(emptyMap())
    private val _fileBrowserStateByTab =
        MutableStateFlow<Map<String, FileBrowserUiState>>(emptyMap())
    private val fileHomeByTab = mutableMapOf<String, String>()

    val selectedTab: StateFlow<ConnectionTab> =
        combine(activeTabId, _connectionTabByTab) { id, map ->
                if (id == null) ConnectionTab.Terminal else map[id] ?: ConnectionTab.Terminal
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionTab.Terminal)

    val fileBrowserState: StateFlow<FileBrowserUiState> =
        combine(activeTabId, _fileBrowserStateByTab) { id, map ->
                if (id == null) FileBrowserUiState() else map[id] ?: FileBrowserUiState()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, FileBrowserUiState())

    fun selectTabForHost(hostId: Long?): TabSession? {
        val existing = sessionRepository.tabsForHost(hostId)
        if (existing.isEmpty()) return null
        val activeId = sessionRepository.activeTabId.value
        val target = existing.firstOrNull { it.id == activeId } ?: existing.last()
        sessionRepository.selectTab(target.id)
        return target
    }

    fun openTab(spec: TabSpec): TabSession {
        refreshTailscaleStatus()
        return sessionRepository.openTab(spec)
    }

    data class PreparedTabOpen(
        val spec: TabSpec,
        val requiresVerification: Boolean,
    )

    suspend fun prepareTabOpenForHost(hostId: Long): PreparedTabOpen? {
        val host = hostRepository.getById(hostId) ?: return null
        return prepareTabOpen(host)
    }

    suspend fun prepareTabOpen(host: HostProfile): PreparedTabOpen {
        val key = host.keyId?.let { sshKeyRepository.getById(it) }
        val spec = TabSpec.fromHostProfile(
            host = host,
            publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
            privateKeyPem = key?.privateKeyPem.orEmpty(),
        )
        val requiresVerification =
            settingsRepository.requireAuthOnConnect.value || host.requireAuthOnConnect
        return PreparedTabOpen(spec = spec, requiresVerification = requiresVerification)
    }

    private fun multiplexerConnectionSpec(spec: TabSpec): MultiplexerConnectionSpec =
        MultiplexerConnectionSpec(
            multiplexer = spec.multiplexer ?: Multiplexer.Tmux,
            host = spec.host,
            port = spec.port,
            username = spec.username,
            password = spec.password,
            authMethod = spec.authMethod,
            publicKeyOpenSsh = spec.publicKeyOpenSsh,
            privateKeyPem = spec.privateKeyPem,
            keyPassphrase = spec.keyPassphrase,
            transport = spec.transport,
        )

    private fun parseSuccessfulMultiplexerSessionList(
        commandResult: MultiplexerCommandResult,
        fallbackMessage: String = "Failed to list multiplexer sessions",
    ): List<RemoteMultiplexerSession> {
        if (!commandResult.isSuccess) {
            throw IllegalStateException(commandResult.stderr.ifBlank { fallbackMessage })
        }
        return TmuxMultiplexerCommands.parseSessionList(commandResult.stdout)
    }

    private fun verifyMultiplexerHostKey(
        host: String,
        port: Int,
        algorithm: String,
        keyBytes: ByteArray,
    ): Boolean {
        val existing = hostKeyStore.loadKey(host, port, algorithm)
        if (existing != null && existing.contentEquals(keyBytes)) return true

        val deferred = synchronized(multiplexerHostKeyDecisionLock) {
            val current = multiplexerHostKeyDecision
            if (current != null) {
                null
            } else {
                CompletableDeferred<Boolean>().also { multiplexerHostKeyDecision = it }
            }
        } ?: return false
        _multiplexerState.value = _multiplexerState.value.copy(
            hostKeyPrompt = HostKeyPrompt(
                host = host,
                port = port,
                algorithm = algorithm,
                fingerprint = hostKeyStore.fingerprintSha256(keyBytes),
                previousFingerprint = existing?.let { hostKeyStore.fingerprintSha256(it) },
            ),
        )
        val accepted = runBlocking { deferred.await() }
        if (accepted) {
            hostKeyStore.saveKey(host, port, algorithm, keyBytes)
        }
        synchronized(multiplexerHostKeyDecisionLock) {
            if (multiplexerHostKeyDecision === deferred) multiplexerHostKeyDecision = null
        }
        _multiplexerState.value = _multiplexerState.value.copy(hostKeyPrompt = null)
        return accepted
    }

    fun onMultiplexerHostKeyDecision(accepted: Boolean) {
        val deferred = synchronized(multiplexerHostKeyDecisionLock) { multiplexerHostKeyDecision }
        deferred?.complete(accepted)
        _multiplexerState.value = _multiplexerState.value.copy(hostKeyPrompt = null)
    }

    fun duplicateActiveTab(): TabSession? {
        val current = sessionRepository.activeTab.value ?: return null
        if (current.spec.usesRuntimeMultiplexer) {
            startMultiplexerDuplicate(current.spec)
            return null
        }
        return openTab(current.spec)
    }

    fun duplicateTab(tabId: String): TabSession? {
        val tab = sessionRepository.tabs.value.firstOrNull { it.id == tabId } ?: return null
        if (tab.spec.usesRuntimeMultiplexer) {
            startMultiplexerDuplicate(tab.spec)
            return null
        }
        return openTab(tab.spec)
    }

    private fun startMultiplexerDuplicate(spec: TabSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val listResult = remoteMultiplexerService.listSessions(multiplexerConnectionSpec(spec))
                val remoteSessions = parseSuccessfulMultiplexerSessionList(listResult)
                sessionRepository.nextMultiplexerSessionName(spec.hostId, remoteSessions)
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { name ->
                        openTab(
                            spec.copy(
                                multiplexer = spec.multiplexer ?: Multiplexer.Tmux,
                                multiplexerSessionName = name,
                                multiplexerCreateIfMissing = true,
                            ),
                        )
                    },
                    onFailure = { error ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            preflightError = error.message ?: "Could not create multiplexer session",
                        )
                    },
                )
            }
        }
    }

    fun initiateMultiplexerOpen(spec: TabSpec) {
        beginMultiplexerAction(PendingMultiplexerAction.Open(spec))
    }

    private fun initiateMultiplexerReconnect(tab: TabSession) {
        beginMultiplexerAction(PendingMultiplexerAction.Reconnect(tab.id, tab.spec))
    }

    private fun beginMultiplexerAction(action: PendingMultiplexerAction) {
        val actionGeneration = ++multiplexerActionGeneration
        pendingMultiplexerAction = action
        val spec = action.spec
        val label = spec.multiplexer?.label ?: Multiplexer.Tmux.label
        val reconnectRecovery = action is PendingMultiplexerAction.Reconnect
        if (spec.transport == Transport.Mosh) {
            _multiplexerState.value = MultiplexerUiState(
                preflightError = "$label is not supported for Mosh connections",
                reconnectRecovery = reconnectRecovery,
            )
            return
        }
        if (spec.multiplexer != null && spec.multiplexer.runtimeSupported.not()) {
            _multiplexerState.value = MultiplexerUiState(
                preflightError = "${spec.multiplexer.label} is not supported yet",
                reconnectRecovery = reconnectRecovery,
            )
            return
        }
        _multiplexerState.value = MultiplexerUiState(reconnectRecovery = reconnectRecovery)
        viewModelScope.launch(Dispatchers.IO) {
            val multiplexerSpec = multiplexerConnectionSpec(spec)
            when (val availability = remoteMultiplexerService.checkAvailability(multiplexerSpec)) {
                is MultiplexerAvailability.Available -> {
                    val result = runCatching { resolveMultiplexerSessionName(action, multiplexerSpec) }
                    withContext(Dispatchers.Main) {
                        if (!isCurrentMultiplexerAction(actionGeneration, action)) return@withContext
                        result.fold(
                            onSuccess = { sessionName ->
                                completeMultiplexerAction(actionGeneration, action, sessionName)
                            },
                            onFailure = { error ->
                                _multiplexerState.value = _multiplexerState.value.copy(
                                    preflightError = error.message ?: "Could not prepare multiplexer session",
                                )
                            },
                        )
                    }
                }
                is MultiplexerAvailability.Missing -> {
                    withContext(Dispatchers.Main) {
                        if (!isCurrentMultiplexerAction(actionGeneration, action)) return@withContext
                        _multiplexerState.value = _multiplexerState.value.copy(
                            missingDialogVisible = true,
                            installCandidate = availability.installCandidate,
                            installOutput = "",
                            installRunning = false,
                            installError = null,
                        )
                    }
                }
                is MultiplexerAvailability.UnsupportedMultiplexer -> {
                    withContext(Dispatchers.Main) {
                        if (!isCurrentMultiplexerAction(actionGeneration, action)) return@withContext
                        _multiplexerState.value = _multiplexerState.value.copy(
                            preflightError = "${availability.multiplexer.label} is not supported yet",
                        )
                    }
                }
                is MultiplexerAvailability.UnsupportedTransport -> {
                    withContext(Dispatchers.Main) {
                        if (!isCurrentMultiplexerAction(actionGeneration, action)) return@withContext
                        _multiplexerState.value = _multiplexerState.value.copy(
                            preflightError = "$label is not supported for ${spec.transport}",
                        )
                    }
                }
                is MultiplexerAvailability.Error -> {
                    withContext(Dispatchers.Main) {
                        if (!isCurrentMultiplexerAction(actionGeneration, action)) return@withContext
                        _multiplexerState.value = _multiplexerState.value.copy(
                            preflightError = availability.message,
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolveMultiplexerSessionName(
        action: PendingMultiplexerAction,
        multiplexerSpec: MultiplexerConnectionSpec,
    ): String {
        val existingName = action.spec.multiplexerSessionName?.takeIf { it.isNotBlank() }
        if (existingName != null && action.spec.multiplexerCreateIfMissing) return existingName
        val listResult = remoteMultiplexerService.listSessions(multiplexerSpec)
        val remoteSessions = parseSuccessfulMultiplexerSessionList(listResult)
        if (existingName != null) {
            if (remoteSessions.any { it.name == existingName }) return existingName
            throw IllegalStateException("multiplexer session \"$existingName\" is no longer available")
        }
        return sessionRepository.nextMultiplexerSessionName(action.spec.hostId, remoteSessions)
    }

    private fun isCurrentMultiplexerAction(actionGeneration: Long, action: PendingMultiplexerAction): Boolean =
        multiplexerActionGeneration == actionGeneration && pendingMultiplexerAction == action

    private fun completeMultiplexerAction(
        actionGeneration: Long,
        action: PendingMultiplexerAction,
        sessionName: String,
    ) {
        if (!isCurrentMultiplexerAction(actionGeneration, action)) return
        val nextSpec = action.spec.copy(
            multiplexer = action.spec.multiplexer ?: Multiplexer.Tmux,
            multiplexerSessionName = sessionName,
            multiplexerCreateIfMissing = action.spec.multiplexerCreateIfMissing,
        )
        pendingMultiplexerAction = null
        _multiplexerState.value = MultiplexerUiState()
        when (action) {
            is PendingMultiplexerAction.Open -> openTab(nextSpec)
            is PendingMultiplexerAction.Reconnect -> {
                if (!reconnectTabWithSpec(action.tabId, nextSpec)) {
                    _multiplexerState.value = MultiplexerUiState(
                        preflightError = "Terminal session is no longer available",
                        reconnectRecovery = true,
                    )
                }
            }
        }
    }

    private fun reconnectTabWithSpec(tabId: String, spec: TabSpec): Boolean {
        val tab = sessionRepository.tabs.value.firstOrNull { it.id == tabId } ?: return false
        refreshTailscaleStatus()
        tab.spec = spec
        sessionRepository.reconnectTab(tab)
        return true
    }

    fun listMultiplexerSessionsForCurrentHost() {
        val tab = sessionRepository.activeTab.value ?: return
        val requestGeneration = ++multiplexerSessionListGeneration
        val sourceTabId = tab.id
        val sourceHostId = tab.spec.hostId
        _multiplexerState.value = _multiplexerState.value.copy(
            sessions = emptyList(),
            sessionsLoading = true,
            sessionsError = null,
            sessionsSourceTabId = sourceTabId,
            sessionsSourceHostId = sourceHostId,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { remoteMultiplexerService.listSessions(multiplexerConnectionSpec(tab.spec)) }
            withContext(Dispatchers.Main) {
                val activeTab = sessionRepository.activeTab.value
                if (requestGeneration != multiplexerSessionListGeneration || activeTab?.id != sourceTabId) {
                    return@withContext
                }
                result.fold(
                    onSuccess = { commandResult ->
                        if (commandResult.isSuccess) {
                            _multiplexerState.value = _multiplexerState.value.copy(
                                sessions = TmuxMultiplexerCommands.parseSessionList(commandResult.stdout),
                                sessionsLoading = false,
                                sessionsError = null,
                                sessionsSourceTabId = sourceTabId,
                                sessionsSourceHostId = sourceHostId,
                            )
                        } else {
                            _multiplexerState.value = _multiplexerState.value.copy(
                                sessions = emptyList(),
                                sessionsLoading = false,
                                sessionsError = commandResult.stderr.ifBlank { "Failed to list sessions" },
                                sessionsSourceTabId = sourceTabId,
                                sessionsSourceHostId = sourceHostId,
                            )
                        }
                    },
                    onFailure = { error ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            sessions = emptyList(),
                            sessionsLoading = false,
                            sessionsError = error.message ?: "Failed to list sessions",
                            sessionsSourceTabId = sourceTabId,
                            sessionsSourceHostId = sourceHostId,
                        )
                    },
                )
            }
        }
    }

    fun createNextMultiplexerSession() {
        val tab = sessionRepository.activeTab.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val listResult = remoteMultiplexerService.listSessions(multiplexerConnectionSpec(tab.spec))
                val remoteSessions = parseSuccessfulMultiplexerSessionList(listResult)
                sessionRepository.nextMultiplexerSessionName(tab.spec.hostId, remoteSessions)
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { name ->
                        openTab(
                            tab.spec.copy(
                                multiplexer = tab.spec.multiplexer ?: Multiplexer.Tmux,
                                multiplexerSessionName = name,
                                multiplexerCreateIfMissing = true,
                            ),
                        )
                        listMultiplexerSessionsForCurrentHost()
                    },
                    onFailure = { error ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            sessionsError = error.message ?: "Could not create multiplexer session",
                        )
                    },
                )
            }
        }
    }

    fun switchToMultiplexerSession(name: String, sourceTabId: String?) {
        val activeTab = sessionRepository.activeTab.value
        val state = _multiplexerState.value
        if (activeTab == null || sourceTabId == null || activeTab.id != sourceTabId || state.sessionsSourceTabId != sourceTabId) {
            _multiplexerState.value = state.copy(
                sessions = emptyList(),
                sessionsLoading = false,
                sessionsError = "Session list is stale. Refresh sessions and try again.",
                sessionsSourceTabId = activeTab?.id,
                sessionsSourceHostId = activeTab?.spec?.hostId,
            )
            return
        }
        sessionRepository.switchActiveMultiplexerSession(name)
    }

    fun confirmRunInstall() {
        val candidate = _multiplexerState.value.installCandidate ?: return
        val action = pendingMultiplexerAction ?: return
        val actionGeneration = multiplexerActionGeneration
        val spec = action.spec
        _multiplexerState.value = _multiplexerState.value.copy(
            installRunning = true,
            installOutput = "",
            installError = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                remoteMultiplexerService.installMultiplexer(multiplexerConnectionSpec(spec), candidate)
            }
            withContext(Dispatchers.Main) {
                if (!isCurrentMultiplexerAction(actionGeneration, action)) return@withContext
                result.fold(
                    onSuccess = { commandResult ->
                        if (commandResult.isSuccess) {
                            _multiplexerState.value = _multiplexerState.value.copy(
                                installRunning = false,
                                installOutput = commandResult.stdout,
                                missingDialogVisible = false,
                            )
                            beginMultiplexerAction(action)
                        } else {
                            _multiplexerState.value = _multiplexerState.value.copy(
                                installRunning = false,
                                installOutput = commandResult.output,
                                installError = "Install failed (exit ${commandResult.exitCode})",
                            )
                        }
                    },
                    onFailure = { error ->
                        _multiplexerState.value = _multiplexerState.value.copy(
                            installRunning = false,
                            installError = error.message ?: "Install failed",
                        )
                    },
                )
            }
        }
    }

    fun retryPendingMultiplexerOpen() {
        val action = pendingMultiplexerAction ?: return
        beginMultiplexerAction(action)
    }

    fun connectPendingWithoutMultiplexer(): Boolean {
        val action = pendingMultiplexerAction ?: return false
        val plainSpec = action.spec.copy(
            multiplexer = null,
            multiplexerSessionName = null,
            multiplexerCreateIfMissing = true,
        )
        _multiplexerState.value = MultiplexerUiState()
        pendingMultiplexerAction = null
        multiplexerActionGeneration += 1
        return when (action) {
            is PendingMultiplexerAction.Open -> {
                openTab(plainSpec)
                true
            }
            is PendingMultiplexerAction.Reconnect -> reconnectTabWithSpec(action.tabId, plainSpec)
        }
    }

    fun dismissMissingMultiplexerDialog(onBack: () -> Unit) {
        val shouldNavigateBack = pendingMultiplexerAction is PendingMultiplexerAction.Open
        _multiplexerState.value = MultiplexerUiState()
        pendingMultiplexerAction = null
        multiplexerActionGeneration += 1
        if (shouldNavigateBack) onBack()
    }

    fun selectTab(id: String) {
        sessionRepository.selectTab(id)
    }

    fun closeTab(id: String) {
        _connectionTabByTab.value = _connectionTabByTab.value - id
        _fileBrowserStateByTab.value = _fileBrowserStateByTab.value - id
        fileHomeByTab.remove(id)
        sessionRepository.closeTab(id)
    }

    fun reconnect() {
        refreshTailscaleStatus()
        val tab = sessionRepository.activeTab.value ?: return
        if (tab.spec.usesRuntimeMultiplexer) {
            initiateMultiplexerReconnect(tab)
        } else {
            sessionRepository.reconnectActive()
        }
    }

    fun selectConnectionTab(tab: ConnectionTab) {
        val id = activeTabId.value ?: return
        _connectionTabByTab.value = _connectionTabByTab.value + (id to tab)
        if (tab == ConnectionTab.Files) {
            val state = _fileBrowserStateByTab.value[id]
            if (state == null || state.entries.isEmpty()) {
                resolveInitialFilePathAndRefresh(id)
            }
        }
    }

    private fun updateFileBrowserState(
        id: String,
        transform: (FileBrowserUiState) -> FileBrowserUiState,
    ) {
        val current = _fileBrowserStateByTab.value
        val previous = current[id] ?: FileBrowserUiState()
        _fileBrowserStateByTab.value = current + (id to transform(previous))
    }

    private fun resolveInitialFilePathAndRefresh(tabId: String) {
        val cachedHome = fileHomeByTab[tabId]
        if (cachedHome != null) {
            updateFileBrowserState(tabId) { it.copy(currentPath = cachedHome) }
            refreshFileBrowser(tabId)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val tabState = sessionRepository.tabs.value.firstOrNull { it.id == tabId }
            val fallback = tabState?.engine?.state?.value?.pwd?.takeIf { it.isNotBlank() } ?: "/"
            val resolved = runCatching { sessionRepository.sftpRealpath(tabId, "~") }.getOrNull()
            val initial = resolved?.takeIf { it.isNotBlank() } ?: fallback
            fileHomeByTab[tabId] = initial
            updateFileBrowserState(tabId) {
                it.copy(currentPath = initial, resolvedHomePath = initial)
            }
            refreshFileBrowser(tabId)
        }
    }

    fun refreshFileBrowser() {
        val tabId = activeTabId.value ?: return
        refreshFileBrowser(tabId)
    }

    private fun refreshFileBrowser(tabId: String) {
        val pwd =
            sessionRepository.tabs.value
                .firstOrNull { it.id == tabId }
                ?.sessionState
                ?.value
                ?.pwd
                ?.takeIf { it.isNotBlank() } ?: "/"
        val targetPath = (_fileBrowserStateByTab.value[tabId]?.currentPath?.ifBlank { pwd }) ?: pwd
        updateFileBrowserState(tabId) {
            it.copy(currentPath = targetPath, isLoading = true, error = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionRepository.sftpListDirectory(tabId, targetPath) }
                .onSuccess { rows ->
                    val entries =
                        rows.map { row ->
                            val parts = row.split('\t')
                            val name = parts.getOrNull(0).orEmpty()
                            val kind = parts.getOrNull(1).orEmpty()
                            val size = parts.getOrNull(2)?.toLongOrNull()
                            val mtime = parts.getOrNull(3)?.toLongOrNull()
                            val normalizedBase = targetPath.trimEnd('/').ifEmpty { "/" }
                            val type =
                                when (kind) {
                                    "dir" -> FileEntryType.Directory
                                    "file" -> FileEntryType.File
                                    "link" -> FileEntryType.Symlink
                                    else -> FileEntryType.Other
                                }
                            FileBrowserEntry(
                                name = name,
                                path =
                                    if (normalizedBase == "/") "/$name"
                                    else "$normalizedBase/$name",
                                type = type,
                                sizeBytes = if (size == null || size <= 0L) null else size,
                                modifiedAtText = mtime?.takeIf { it > 0L }?.let { formatMtime(it) },
                            )
                        }
                    val current = _fileBrowserStateByTab.value[tabId] ?: FileBrowserUiState()
                    val sortedEntries =
                        when (current.sort) {
                            FileSort.Name -> entries.sortedBy { it.name.lowercase() }
                            FileSort.Size -> entries.sortedByDescending { it.sizeBytes ?: -1L }
                            FileSort.Modified ->
                                entries.sortedByDescending { it.modifiedAtText ?: "" }
                        }
                    if (current.currentPath == targetPath) {
                        updateFileBrowserState(tabId) {
                            it.copy(entries = sortedEntries, isLoading = false, error = null)
                        }
                    }
                }
                .onFailure { err ->
                    val current = _fileBrowserStateByTab.value[tabId]
                    if (current?.currentPath == targetPath) {
                        updateFileBrowserState(tabId) {
                            it.copy(
                                isLoading = false,
                                error = err.message ?: "Failed to list directory",
                            )
                        }
                    }
                }
        }
    }

    suspend fun beginUpload(fileName: String) {
        val tabId = activeTabId.value ?: return
        val current = _fileBrowserStateByTab.value[tabId] ?: return
        val targetPath = current.currentPath.trimEnd('/') + "/" + fileName
        sessionRepository.sftpOpenWrite(tabId, targetPath)
    }

    suspend fun writeUploadChunk(data: ByteArray) {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpWriteChunk(tabId, data)
    }

    suspend fun finishUpload() {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpCloseWrite(tabId)
        refreshFileBrowser()
    }

    fun setUploadProgress(progress: UploadProgress?) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(uploadProgress = progress) }
    }

    fun goUpDirectory() {
        val tabId = activeTabId.value ?: return
        val current = _fileBrowserStateByTab.value[tabId]?.currentPath ?: return
        val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
        openPath(parent)
    }

    fun openPath(path: String) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(currentPath = path) }
        refreshFileBrowser()
    }

    fun selectFileSort(sort: FileSort) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(sort = sort) }
        refreshFileBrowser()
    }

    suspend fun deleteFile(tabId: String, entry: FileBrowserEntry) {
        sessionRepository.sftpDelete(tabId, entry.path, entry.type == FileEntryType.Directory)
        withContext(Dispatchers.Main) { refreshFileBrowser() }
    }

    suspend fun readFile(tabId: String, entry: FileBrowserEntry, maxBytes: Int): ByteArray {
        return sessionRepository.sftpReadFile(tabId, entry.path, maxBytes)
    }

    private fun formatMtime(epochSeconds: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(epochSeconds * 1000L))
    }

    fun refreshTailscaleStatus() {
        _tailscaleActive.value = tailscaleStatusChecker.isActive()
    }

    fun onCanvasSizeChanged(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        sessionRepository.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun onScroll(delta: Int, x: Float, y: Float) {
        sessionRepository.scroll(delta, x, y)
    }

    fun onPrimaryMouseClick(x: Float, y: Float) {
        sessionRepository.sendMouseEvent(
            action = GhosttyMouseAction.Press,
            button = GhosttyMouseButton.Left,
            mods = 0,
            x = x,
            y = y,
            anyButtonPressed = false,
            trackLastCell = false,
        )
        sessionRepository.sendMouseEvent(
            action = GhosttyMouseAction.Release,
            button = GhosttyMouseButton.Left,
            mods = 0,
            x = x,
            y = y,
            anyButtonPressed = false,
            trackLastCell = false,
        )
    }

    fun onHardwareKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int = 0) {
        val hasNonTextModifier = mods and ((1 shl 1) or (1 shl 2) or (1 shl 3)) != 0
        val isRelease = action == GhosttyKeyAction.Release
        if (!isRelease) {
            sessionRepository.scrollToActive()
        }
        val effectiveCodepoint = if (charCode > 0) charCode else codepoint

        val utf8 =
            if (effectiveCodepoint > 0 && !hasNonTextModifier && !isRelease) effectiveCodepoint.toChar().toString()
            else null
        sessionRepository.writeKey(key, effectiveCodepoint, mods, action, utf8)
    }

    fun onTextInput(text: String) {
        sessionRepository.scrollToActive()
        sessionRepository.writeText(text)
    }

    fun onSpecialKeyInput(key: TerminalSpecialKey, mods: Int) {
        sessionRepository.scrollToActive()
        sessionRepository.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Press)
        sessionRepository.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Release)
    }

    fun onPasteText(text: String) {
        if (text.isEmpty()) return
        sessionRepository.scrollToActive()
        val chunkSize = 512
        viewModelScope.launch {
            var index = 0
            while (index < text.length) {
                val end = (index + chunkSize).coerceAtMost(text.length)
                sessionRepository.writeText(text.substring(index, end))
                index = end
                delay(8)
            }
        }
    }

    fun onFocusChanged(focused: Boolean) {
        sessionRepository.sendFocusEvent(focused)
    }

    fun onColorSchemeChanged(isDark: Boolean) {
        sessionRepository.setColorScheme(isDark)
    }

    fun onDefaultColorsChanged(
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    ) {
        sessionRepository.setDefaultColors(fg, bg, cursor, palette)
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    override fun onCleared() {
        multiplexerHostKeyDecision?.cancel()
        multiplexerHostKeyDecision = null
        sessionRepository.detachClient()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(TerminalViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return TerminalViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

private object GhosttyMouseAction {
    const val Release = 0
    const val Press = 1
}

private object GhosttyMouseButton {
    const val Left = 1
}

data class MultiplexerUiState(
    val missingDialogVisible: Boolean = false,
    val installCandidate: MultiplexerInstallCandidate? = null,
    val installOutput: String = "",
    val installRunning: Boolean = false,
    val installError: String? = null,
    val preflightError: String? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val sessions: List<RemoteMultiplexerSession> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val sessionsSourceTabId: String? = null,
    val sessionsSourceHostId: Long? = null,
    val reconnectRecovery: Boolean = false,
)

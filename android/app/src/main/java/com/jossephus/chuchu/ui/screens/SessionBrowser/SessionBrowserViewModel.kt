package com.jossephus.chuchu.ui.screens.SessionBrowser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.BrowserItem
import com.jossephus.chuchu.plugin.api.HostBrowser
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.service.transport.TransportRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionBrowserUiState(
    val hostName: String = "",
    val sourceLabel: String = "",
    val groups: List<BrowserGroup> = emptyList(),
    val newGroupTarget: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

class SessionBrowserViewModel(
    application: Application,
    private val hostId: Long,
) : AndroidViewModel(application) {
    private val app = application
    private val sessionRepository = TerminalSessionRepository.getInstance(application)
    private val database = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(database.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(database.sshKeyDao())
    private val _state = MutableStateFlow(SessionBrowserUiState())
    private val host = MutableStateFlow<HostProfile?>(null)
    private var spec: TabSpec? = null
    private var job: Job? = null
    private var transportBrowser: HostBrowser? = null

    val state: StateFlow<SessionBrowserUiState> = _state.asStateFlow()

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt

    val openTargets: StateFlow<Set<String>> =
        combine(sessionRepository.tabs, host) { tabs, profile ->
            val isTransport = profile?.transport == Transport.Plugin
            tabs.filter { it.spec.hostId == hostId }
                .mapNotNullTo(HashSet()) { tab ->
                    if (isTransport) tab.transportTarget else tab.spec.multiplexerSessionName
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun start() {
        if (transportBrowser != null && _state.value.error == null) return
        stop()
        _state.value = _state.value.copy(loading = true, error = null)
        job =
            viewModelScope.launch {
                val profile = host.value ?: loadHost() ?: return@launch
                if (profile.transport == Transport.Plugin) {
                    startTransport(profile)
                } else {
                    listMultiplexerSessions(profile)
                }
            }
    }

    fun refresh() {
        stop()
        start()
    }

    fun stop() {
        job?.cancel()
        job = null
        transportBrowser?.close()
        transportBrowser = null
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    override fun onCleared() {
        stop()
    }

    private suspend fun loadHost(): HostProfile? {
        val profile = hostRepository.getById(hostId)
        if (profile == null) {
            fail(app.getString(R.string.session_browser_host_missing))
            return null
        }
        host.value = profile
        _state.value = _state.value.copy(hostName = profile.name)
        return profile
    }

    private suspend fun startTransport(profile: HostProfile) {
        val provider = TransportRegistry.forId(profile.transportId)
        val factory = provider?.hostBrowser
        if (factory == null) {
            fail(app.getString(R.string.add_server_transport_unavailable, profile.transportId.orEmpty()))
            return
        }
        _state.value = _state.value.copy(sourceLabel = provider.displayName)
        try {
            val browser = factory.open(profile.host)
            transportBrowser = browser
            viewModelScope.launch { browser.state.collect(::applyTransportState) }
            browser.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: app.getString(R.string.session_browser_connect_failed))
        }
    }

    private fun applyTransportState(update: HostBrowserState) {
        _state.value =
            when (update) {
                HostBrowserState.Connecting -> _state.value.copy(loading = true, error = null)
                is HostBrowserState.Ready ->
                    _state.value.copy(
                        groups = update.groups,
                        newGroupTarget = update.newGroupTarget,
                        loaded = true,
                        loading = false,
                        error = null,
                    )
                is HostBrowserState.Failed -> _state.value.copy(loading = false, error = update.message)
            }
    }

    private suspend fun listMultiplexerSessions(profile: HostProfile) {
        val current = spec ?: multiplexerSpec(profile).also { spec = it }
        _state.value = _state.value.copy(sourceLabel = current.multiplexer?.label.orEmpty())
        try {
            val sessions = withContext(Dispatchers.IO) { sessionRepository.listMultiplexerSessions(current) }
            _state.value =
                _state.value.copy(
                    groups = listOf(multiplexerGroup(sessions)),
                    newGroupTarget = nextSessionName(current, sessions),
                    loaded = true,
                    loading = false,
                    error = null,
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: app.getString(R.string.terminal_error_prepare_mux))
        }
    }

    private suspend fun multiplexerSpec(profile: HostProfile): TabSpec {
        val key = profile.keyId?.let { sshKeyRepository.getById(it) }
        val built =
            TabSpec.fromHostProfile(
                host = profile,
                publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
                privateKeyPem = key?.privateKeyPem.orEmpty(),
            )
        return built.copy(multiplexer = built.multiplexer ?: MultiplexerRegistry.defaultType)
    }

    private fun multiplexerGroup(sessions: List<RemoteMultiplexerSession>): BrowserGroup =
        BrowserGroup(
            id = MULTIPLEXER_GROUP_ID,
            label = "",
            items =
                sessions.map { session ->
                    BrowserItem(
                        target = session.name,
                        title = session.name,
                        detail = if (session.attached) app.getString(R.string.mux_attached) else null,
                    )
                },
        )

    private fun nextSessionName(current: TabSpec, sessions: List<RemoteMultiplexerSession>): String? {
        val type = current.multiplexer ?: return null
        val multiplexer = MultiplexerRegistry.forType(type) ?: return null
        return multiplexer.defaultSessionName(sessions, sessionRepository.openMultiplexerSessionNamesForHost(hostId, type))
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(loading = false, error = message)
    }

    companion object {
        private const val MULTIPLEXER_GROUP_ID = "sessions"

        fun factory(application: Application, hostId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SessionBrowserViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return SessionBrowserViewModel(application, hostId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

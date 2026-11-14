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
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.plugin.PluginManager
import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.HostBrowser
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionBrowserUiState(
    val hostName: String = "",
    val sourceLabel: String = "",
    val groups: List<BrowserGroup> = emptyList(),
    val newGroupTarget: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val updatesLive: Boolean = false,
)

class SessionBrowserViewModel(
    application: Application,
    private val hostId: Long,
) : AndroidViewModel(application) {
    private val app = application
    private val plugins = PluginManager.instance
    private val sessionRepository = TerminalSessionRepository.getInstance(application)
    private val database = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(database.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(database.sshKeyDao())
    private val _state = MutableStateFlow(SessionBrowserUiState())
    private var job: Job? = null
    private var browser: HostBrowser? = null

    val state: StateFlow<SessionBrowserUiState> = _state.asStateFlow()

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt

    val openTargets: StateFlow<Set<String>> =
        sessionRepository.tabs
            .map { tabs -> tabs.filter { it.spec.hostId == hostId }.mapNotNullTo(HashSet()) { it.target } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun start() {
        if (browser != null && _state.value.error == null) return
        stop()
        _state.value = _state.value.copy(loading = true, error = null)
        job =
            viewModelScope.launch {
                val profile = loadHost() ?: return@launch
                val opened = openBrowser(profile) ?: return@launch
                browser = opened
                _state.value = _state.value.copy(updatesLive = opened.updatesLive)
                launch { opened.state.collect(::apply) }
                try {
                    opened.start()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: app.getString(R.string.session_browser_connect_failed))
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
        browser?.close()
        browser = null
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
        _state.value = _state.value.copy(hostName = profile.name)
        return profile
    }

    private suspend fun openBrowser(profile: HostProfile): HostBrowser? {
        val transportId = profile.transportId ?: return multiplexerBrowser(profile)
        val provider = plugins.transports[transportId]
        val factory = provider?.hostBrowser
        if (factory == null) {
            fail(app.getString(R.string.add_server_transport_unavailable, transportId))
            return null
        }
        _state.value = _state.value.copy(sourceLabel = provider.displayName)
        return factory.open(profile.host)
    }

    private suspend fun multiplexerBrowser(profile: HostProfile): HostBrowser {
        val key = profile.keyId?.let { sshKeyRepository.getById(it) }
        val built =
            TabSpec.fromHostProfile(
                host = profile,
                multiplexers = plugins.multiplexers,
                publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
                privateKeyPem = key?.privateKeyPem.orEmpty(),
            )
        val spec = built.copy(multiplexer = built.multiplexer ?: MultiplexerType.Default)
        _state.value = _state.value.copy(sourceLabel = spec.multiplexer?.label.orEmpty())
        return MultiplexerHostBrowser(app, sessionRepository, spec)
    }

    private fun apply(update: HostBrowserState) {
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

    private fun fail(message: String) {
        _state.value = _state.value.copy(loading = false, error = message)
    }

    companion object {
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

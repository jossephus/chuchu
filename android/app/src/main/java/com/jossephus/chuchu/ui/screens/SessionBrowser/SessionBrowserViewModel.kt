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
import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionBrowserUiState(
    val hostName: String = "",
    val multiplexerLabel: String = "",
    val sessions: List<RemoteMultiplexerSession> = emptyList(),
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
    private var spec: TabSpec? = null
    private var loadJob: Job? = null

    val state: StateFlow<SessionBrowserUiState> = _state.asStateFlow()

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt

    val openSessionNames: StateFlow<Set<String>> =
        sessionRepository.tabs
            .map { tabs ->
                tabs.filter { it.spec.hostId == hostId }.mapNotNullTo(HashSet()) { it.spec.multiplexerSessionName }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun refresh() {
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        loadJob =
            viewModelScope.launch {
                val current = spec ?: loadSpec() ?: return@launch
                try {
                    val sessions = withContext(Dispatchers.IO) { sessionRepository.listMultiplexerSessions(current) }
                    _state.value = _state.value.copy(sessions = sessions, loaded = true, loading = false, error = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            loading = false,
                            error = e.message ?: app.getString(R.string.terminal_error_prepare_mux),
                        )
                }
            }
    }

    fun newSessionName(): String? {
        val type = spec?.multiplexer ?: return null
        val multiplexer = MultiplexerRegistry.forType(type) ?: return null
        return multiplexer.defaultSessionName(
            _state.value.sessions,
            sessionRepository.openMultiplexerSessionNamesForHost(hostId, type),
        )
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    private suspend fun loadSpec(): TabSpec? {
        val host = hostRepository.getById(hostId)
        if (host == null) {
            _state.value = _state.value.copy(loading = false, error = app.getString(R.string.session_browser_host_missing))
            return null
        }
        val key = host.keyId?.let { sshKeyRepository.getById(it) }
        val built =
            TabSpec.fromHostProfile(
                host = host,
                publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
                privateKeyPem = key?.privateKeyPem.orEmpty(),
            ).let { it.copy(multiplexer = it.multiplexer ?: MultiplexerRegistry.defaultType) }
        spec = built
        _state.value = _state.value.copy(hostName = host.name, multiplexerLabel = built.multiplexer?.label.orEmpty())
        return built
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

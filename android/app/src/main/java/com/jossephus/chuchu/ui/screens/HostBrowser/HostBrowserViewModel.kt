package com.jossephus.chuchu.ui.screens.HostBrowser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.HostBrowser
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.service.transport.TransportRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HostBrowserUiState(
    val hostName: String = "",
    val transportName: String = "",
    val groups: List<BrowserGroup> = emptyList(),
    val newGroupTarget: String? = null,
    val loaded: Boolean = false,
    val connecting: Boolean = true,
    val error: String? = null,
)

class HostBrowserViewModel(
    application: Application,
    private val hostId: Long,
) : AndroidViewModel(application) {
    private val hostRepository = HostRepository(AppDatabase.getInstance(application).hostProfileDao())
    private val _state = MutableStateFlow(HostBrowserUiState())
    private var browser: HostBrowser? = null
    private var job: Job? = null

    val state: StateFlow<HostBrowserUiState>
        get() = _state

    fun connect() {
        if (browser != null && _state.value.error == null) return
        disconnect()
        _state.value = _state.value.copy(connecting = true, error = null)
        job = viewModelScope.launch {
            val host = hostRepository.getById(hostId)
            if (host == null) {
                _state.value = _state.value.copy(connecting = false, error = "This server no longer exists")
                return@launch
            }
            val provider = TransportRegistry.forId(host.transportId)
            _state.value = _state.value.copy(hostName = host.name, transportName = provider?.displayName.orEmpty())
            val factory = provider?.hostBrowser
            if (factory == null) {
                _state.value =
                    _state.value.copy(
                        connecting = false,
                        error = "The '${host.transportId}' transport plugin isn't available",
                    )
                return@launch
            }
            val opened =
                try {
                    factory.open(host.host)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(connecting = false, error = e.message ?: "Could not connect")
                    return@launch
                }
            browser = opened
            launch { opened.state.collect(::apply) }
            try {
                opened.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = e.message ?: "Could not connect")
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        browser?.close()
        browser = null
    }

    override fun onCleared() {
        disconnect()
    }

    private fun apply(update: HostBrowserState) {
        _state.value =
            when (update) {
                HostBrowserState.Connecting -> _state.value.copy(connecting = true, error = null)
                is HostBrowserState.Ready ->
                    _state.value.copy(
                        groups = update.groups,
                        newGroupTarget = update.newGroupTarget,
                        loaded = true,
                        connecting = false,
                        error = null,
                    )
                is HostBrowserState.Failed -> _state.value.copy(connecting = false, error = update.message)
            }
    }

    companion object {
        fun factory(application: Application, hostId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HostBrowserViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return HostBrowserViewModel(application, hostId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

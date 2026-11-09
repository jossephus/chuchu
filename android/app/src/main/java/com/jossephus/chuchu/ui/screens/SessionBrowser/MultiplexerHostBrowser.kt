package com.jossephus.chuchu.ui.screens.SessionBrowser

import android.app.Application
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.BrowserItem
import com.jossephus.chuchu.plugin.api.HostBrowser
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.plugin.api.RemoteMultiplexerSession
import com.jossephus.chuchu.service.multiplexer.MultiplexerSessionAllocator
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class MultiplexerHostBrowser(
    private val app: Application,
    private val repository: TerminalSessionRepository,
    private val spec: TabSpec,
) : HostBrowser {
    private val type: MultiplexerType = spec.multiplexer ?: MultiplexerType.Default
    private val _state = MutableStateFlow<HostBrowserState>(HostBrowserState.Connecting)

    override val state: StateFlow<HostBrowserState> = _state.asStateFlow()

    override suspend fun start() {
        _state.value = HostBrowserState.Connecting
        _state.value =
            try {
                val sessions = withContext(Dispatchers.IO) { repository.listMultiplexerSessions(spec) }
                HostBrowserState.Ready(groups = listOf(group(sessions)), newGroupTarget = nextSessionName(sessions))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HostBrowserState.Failed(e.message ?: app.getString(R.string.terminal_error_prepare_mux))
            }
    }

    override fun close() = Unit

    private fun group(sessions: List<RemoteMultiplexerSession>): BrowserGroup =
        BrowserGroup(
            id = GROUP_ID,
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

    private fun nextSessionName(sessions: List<RemoteMultiplexerSession>): String =
        MultiplexerSessionAllocator.nextChuchuSessionName(
            sessions,
            repository.openMultiplexerSessionNamesForHost(spec.hostId, type),
        )

    private companion object {
        const val GROUP_ID = "sessions"
    }
}

package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.ProgramStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class TabSession(
    val id: String,
    var spec: TabSpec,
    val engine: TerminalSessionEngine,
) {
    val sessionState: StateFlow<SessionState> get() = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> get() = engine.hostKeyPrompt
    val titleOverride = MutableStateFlow<String?>(null)
    private val hostStatus = MutableStateFlow<HostStatus?>(null)

    val programStatus: Flow<ProgramStatus?> =
        combine(engine.state.map { it.programStatus }, hostStatus) { own, host -> if (host != null) host.status else own }
            .distinctUntilChanged()

    fun showHostStatus(status: ProgramStatus?) {
        hostStatus.value = HostStatus(status)
    }

    private data class HostStatus(val status: ProgramStatus?)

    val target: String?
        get() = engine.attachedTarget.value ?: spec.attachedTargetHint ?: spec.multiplexerSessionName
}

data class TabDescriptor(
    val id: String,
    val hostId: Long?,
    val label: String,
)

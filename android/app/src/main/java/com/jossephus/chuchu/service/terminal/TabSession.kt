package com.jossephus.chuchu.service.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TabSession(
    val id: String,
    var spec: TabSpec,
    val engine: TerminalSessionEngine,
) {
    val sessionState: StateFlow<SessionState> get() = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> get() = engine.hostKeyPrompt
    val titleOverride = MutableStateFlow<String?>(null)

    val transportTarget: String?
        get() = engine.attachedTarget.value ?: spec.attachedTargetHint
}

data class TabDescriptor(
    val id: String,
    val hostId: Long?,
    val label: String,
)

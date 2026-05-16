package com.jossephus.chuchu.service.terminal

import kotlinx.coroutines.flow.StateFlow

class TabSession(
    val id: String,
    val spec: TabSpec,
    val engine: TerminalSessionEngine,
) {
    val sessionState: StateFlow<SessionState> get() = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> get() = engine.hostKeyPrompt
}

data class TabDescriptor(
    val id: String,
    val hostId: Long?,
    val label: String,
)

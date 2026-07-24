package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

internal fun readLoopExitAction(transport: Transport?): ReadLoopExitAction =
    when (transport) {
        Transport.LocalShell -> ReadLoopExitAction.Disconnect
        Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }

package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport

data class RemoteMultiplexerSession(
    val name: String,
    val attached: Boolean,
)

sealed interface MultiplexerAvailability {
    data object Available : MultiplexerAvailability
    data class Missing(val multiplexer: MultiplexerType) : MultiplexerAvailability
    data class UnsupportedMultiplexer(val multiplexer: MultiplexerType) : MultiplexerAvailability
    data class UnsupportedTransport(val transport: Transport) : MultiplexerAvailability
    data class Error(val message: String, val output: String = "") : MultiplexerAvailability
}

data class MultiplexerCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val output: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

    val isSuccess: Boolean
        get() = exitCode == 0
}

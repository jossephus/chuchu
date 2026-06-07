package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Multiplexer
import com.jossephus.chuchu.model.Transport

data class RemoteMultiplexerSession(
    val name: String,
    val attached: Boolean,
)

data class MultiplexerInstallCandidate(
    val platformLabel: String,
    val command: String?,
    val packageManager: String? = null,
    val guidance: String,
)

sealed interface MultiplexerAvailability {
    data object Available : MultiplexerAvailability
    data class Missing(val installCandidate: MultiplexerInstallCandidate) : MultiplexerAvailability
    data class UnsupportedMultiplexer(val multiplexer: Multiplexer) : MultiplexerAvailability
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

data class MultiplexerConnectionSpec(
    val multiplexer: Multiplexer,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val authMethod: AuthMethod,
    val publicKeyOpenSsh: String,
    val privateKeyPem: String,
    val keyPassphrase: String,
    val transport: Transport,
) {
    override fun toString(): String =
        "MultiplexerConnectionSpec(" +
            "multiplexer=$multiplexer, host=$host, port=$port, username=$username, " +
            "password=<redacted>, authMethod=$authMethod, publicKeyOpenSsh=<redacted>, " +
            "privateKeyPem=<redacted>, keyPassphrase=<redacted>, transport=$transport)"
}

package com.jossephus.chuchu.service.tmux

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport

data class RemoteTmuxSession(
    val name: String,
    val attached: Boolean,
)

data class TmuxInstallCandidate(
    val platformLabel: String,
    val command: String?,
    val packageManager: String? = null,
    val guidance: String,
)

sealed interface TmuxAvailability {
    data object Available : TmuxAvailability
    data class Missing(val installCandidate: TmuxInstallCandidate) : TmuxAvailability
    data class UnsupportedTransport(val transport: Transport) : TmuxAvailability
    data class Error(val message: String, val output: String = "") : TmuxAvailability
}

data class TmuxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val output: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

    val isSuccess: Boolean
        get() = exitCode == 0
}

data class TmuxConnectionSpec(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val authMethod: AuthMethod,
    val publicKeyOpenSsh: String,
    val privateKeyPem: String,
    val keyPassphrase: String,
    val transport: Transport,
)

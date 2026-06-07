package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Multiplexer
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class RemoteMultiplexerService(
    private val hostKeyStore: HostKeyStore,
    private val hostKeyPolicy: ((String, Int, String, ByteArray) -> Boolean)? = null,
    private val sshFactory: (((String, Int, String, ByteArray) -> Boolean) -> NativeSshService) = { policy ->
        NativeSshService(hostKeyPolicy = policy)
    },
) {
    suspend fun checkAvailability(spec: MultiplexerConnectionSpec): MultiplexerAvailability {
        if (!spec.multiplexer.runtimeSupported) {
            return MultiplexerAvailability.UnsupportedMultiplexer(spec.multiplexer)
        }
        if (!isSupportedTransport(spec.transport)) return MultiplexerAvailability.UnsupportedTransport(spec.transport)
        return runCatching {
            val availability = runCommand(spec, TmuxMultiplexerCommands.availabilityCommand())
            if (availability.isSuccess) return MultiplexerAvailability.Available

            val probe = runCommand(spec, TmuxMultiplexerCommands.platformProbeCommand())
            if (probe.isSuccess || probe.output.isNotBlank()) {
                MultiplexerAvailability.Missing(TmuxInstallMatrix.fromProbeOutput(probe.output))
            } else {
                MultiplexerAvailability.Error(
                    message = "Could not check ${spec.multiplexer.label} on this host",
                    output = availability.output.ifBlank { probe.output },
                )
            }
        }.getOrElse { error ->
            MultiplexerAvailability.Error(message = error.message ?: "Could not check ${spec.multiplexer.label} on this host")
        }
    }

    suspend fun listSessions(spec: MultiplexerConnectionSpec): MultiplexerCommandResult =
        runCommand(spec, TmuxMultiplexerCommands.listSessionsCommand())

    suspend fun installMultiplexer(
        spec: MultiplexerConnectionSpec,
        candidate: MultiplexerInstallCandidate,
    ): MultiplexerCommandResult {
        val command = candidate.command ?: return MultiplexerCommandResult(
            exitCode = 1,
            stdout = "",
            stderr = candidate.guidance,
        )
        return runCommand(spec, TmuxMultiplexerCommands.installCommand(command), timeoutMs = 120_000)
    }

    suspend fun runCommand(
        spec: MultiplexerConnectionSpec,
        command: String,
        timeoutMs: Long = 20_000,
    ): MultiplexerCommandResult = withContext(Dispatchers.IO) {
        if (!spec.multiplexer.runtimeSupported) {
            return@withContext MultiplexerCommandResult(1, "", "${spec.multiplexer.label} is not supported yet")
        }
        if (!isSupportedTransport(spec.transport)) {
            return@withContext MultiplexerCommandResult(1, "", "${spec.multiplexer.label} is not supported for ${spec.transport}")
        }
        val ssh = sshFactory { host, port, algorithm, keyBytes ->
            hostKeyPolicy?.invoke(host, port, algorithm, keyBytes)
                ?: verifyKnownHost(host, port, algorithm, keyBytes)
        }
        ssh.use { service ->
            service.connect(
                host = spec.host,
                port = spec.port,
                username = spec.username,
                authMethod = spec.authMethod,
                password = if (spec.authMethod == AuthMethod.Password) spec.password else "",
                publicKeyOpenSsh = spec.publicKeyOpenSsh,
                privateKeyPem = spec.privateKeyPem,
                keyPassphrase = spec.keyPassphrase,
            )
            if (!service.openExec(withExitEnvelope(command))) {
                return@withContext MultiplexerCommandResult(1, "", "Remote server did not open an exec channel")
            }
            readExecOutput(service, timeoutMs)
        }
    }

    private fun isSupportedTransport(transport: Transport): Boolean =
        transport == Transport.SSH || transport == Transport.TailscaleSSH

    private fun verifyKnownHost(
        host: String,
        port: Int,
        algorithm: String,
        keyBytes: ByteArray,
    ): Boolean {
        val existing = hostKeyStore.loadKey(host, port, algorithm) ?: return false
        return existing.contentEquals(keyBytes)
    }

    private fun withExitEnvelope(command: String): String =
        "$command; printf '\nCHUCHU_EXIT:%s\n' \"\$?\""

    private suspend fun readExecOutput(
        service: NativeSshService,
        timeoutMs: Long,
    ): MultiplexerCommandResult {
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = service.read(4096)
            if (chunk != null && chunk.isNotEmpty()) {
                output.append(String(chunk, Charsets.UTF_8))
            } else if (service.isChannelEof()) {
                return parseCommandEnvelope(output.toString())
            } else {
                delay(25)
            }
        }
        return MultiplexerCommandResult(124, output.toString(), "Command timed out")
    }

    private fun parseCommandEnvelope(output: String): MultiplexerCommandResult {
        val marker = Regex("(?:^|\\n)CHUCHU_EXIT:(\\d+)\\s*$").find(output)
            ?: return MultiplexerCommandResult(
                exitCode = 125,
                stdout = output,
                stderr = "Missing command exit marker",
            )
        val exitCode = marker.groupValues.getOrNull(1)?.toIntOrNull() ?: 125
        val cleanOutput = output.substring(0, marker.range.first).trimEnd()
        return MultiplexerCommandResult(exitCode = exitCode, stdout = cleanOutput, stderr = "")
    }
}

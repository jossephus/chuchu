package com.jossephus.chuchu.service.ssh

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.service.multiplexer.MultiplexerCommandResult
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SharedSshConnection(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val authMethod: AuthMethod,
    private val password: String,
    private val publicKeyOpenSsh: String,
    private val privateKeyPem: String,
    private val keyPassphrase: String,
    private val hostKeyPolicy: HostKeyPolicy,
    private val bridge: NativeSshBridge = NativeSshBridge(),
) : Closeable {
    private val connDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ssh-conn").apply { isDaemon = true }
            }
            .asCoroutineDispatcher()
    private val service = NativeSshService(bridge, hostKeyPolicy)

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile private var closed = false

    suspend fun connect() {
        withContext(connDispatcher) {
            check(!closed) { "Shared SSH connection is closed" }
            if (connected) return@withContext
            try {
                service.connect(
                    host = host,
                    port = port,
                    username = username,
                    authMethod = authMethod,
                    password = password,
                    publicKeyOpenSsh = publicKeyOpenSsh,
                    privateKeyPem = privateKeyPem,
                    keyPassphrase = keyPassphrase,
                )
                connected = true
            } catch (error: Exception) {
                service.close()
                throw error
            }
        }
    }

    suspend fun openExecChannel(command: String): Int =
        withContext(connDispatcher) {
            val handle = requireHandle()
            val channelId = bridge.nativeOpenExecChannel(handle, command)
            if (channelId < 0) {
                throw IllegalStateException(
                    bridge.nativeGetLastError(handle) ?: "Remote server did not open an exec channel",
                )
            }
            channelId
        }

    suspend fun writeChannel(id: Int, data: ByteArray) {
        if (data.isEmpty()) return
        withContext(connDispatcher) {
            val handle = requireHandle()
            var offset = 0
            var stalledWrites = 0
            while (offset < data.size) {
                val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
                val decoded = NativeSshService.Ipc.decode(
                    bridge.nativeChannelExchange(handle, id, NativeSshService.Ipc.encodeWrite(chunk)),
                )
                val written =
                    when (decoded.tag) {
                        NativeSshService.Ipc.tagAck ->
                            NativeSshService.Ipc.parseAckWritten(decoded.payload)
                        NativeSshService.Ipc.tagError ->
                            throw IllegalStateException(NativeSshService.Ipc.parseError(decoded.payload))
                        else ->
                            throw IllegalStateException(
                                "Unexpected IPC write response tag: ${decoded.tag}",
                            )
                    }
                if (written == 0) {
                    stalledWrites += 1
                    if (stalledWrites > 64) throw IllegalStateException("Native SSH write stalled")
                    Thread.sleep(4)
                    continue
                }
                val remaining = data.size - offset
                if (written < 0 || written > remaining) {
                    throw IllegalStateException(
                        "Invalid native SSH ACK size: $written (remaining=$remaining)",
                    )
                }
                stalledWrites = 0
                offset += written
            }
        }
    }

    suspend fun readChannel(id: Int, maxBytes: Int = 8192): ByteArray? =
        withContext(connDispatcher) {
            val handle = requireHandle()
            val decoded = NativeSshService.Ipc.decode(
                bridge.nativeChannelExchange(handle, id, NativeSshService.Ipc.encodeRead(maxBytes)),
            )
            when (decoded.tag) {
                NativeSshService.Ipc.tagData -> decoded.payload
                NativeSshService.Ipc.tagError ->
                    throw IllegalStateException(NativeSshService.Ipc.parseError(decoded.payload))
                else -> throw IllegalStateException("Unexpected IPC read response tag: ${decoded.tag}")
            }
        }

    suspend fun channelEof(id: Int): Boolean =
        withContext(connDispatcher) {
            if (!connected || closed) true else bridge.nativeChannelEofById(requireHandle(), id)
        }

    suspend fun closeChannel(id: Int) {
        withContext(connDispatcher) {
            if (connected && !closed) bridge.nativeCloseChannel(requireHandle(), id)
        }
    }

    suspend fun runCommand(
        command: String,
        timeoutMs: Long = 20_000,
    ): MultiplexerCommandResult {
        val channelId = openExecChannel(withExitEnvelope(command))
        return try {
            readExecOutput(channelId, timeoutMs)
        } finally {
            closeChannel(channelId)
        }
    }

    override fun close() {
        if (closed) return
        runBlocking {
            withContext(connDispatcher) {
                if (closed) return@withContext
                closed = true
                connected = false
                service.close()
            }
        }
        connDispatcher.close()
    }

    private fun requireHandle(): Long =
        service.sessionHandle.takeIf { connected && !closed }
            ?: throw IllegalStateException("Shared SSH connection is not connected")

    private suspend fun readExecOutput(
        channelId: Int,
        timeoutMs: Long,
    ): MultiplexerCommandResult {
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = readChannel(channelId, 4096)
            if (chunk != null && chunk.isNotEmpty()) {
                output.append(String(chunk, Charsets.UTF_8))
            } else if (channelEof(channelId)) {
                return parseCommandEnvelope(output.toString())
            } else {
                delay(25)
            }
        }
        return MultiplexerCommandResult(124, output.toString(), "Command timed out")
    }

    internal companion object {
        fun withExitEnvelope(command: String): String =
            "$command; printf '\nCHUCHU_EXIT:%s\n' \"\$?\""

        fun parseCommandEnvelope(output: String): MultiplexerCommandResult {
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
}

package com.jossephus.chuchu.service.terminal

import android.util.Log
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.mosh.MoshBootstrapParser
import com.jossephus.chuchu.service.mosh.MoshEventType
import com.jossephus.chuchu.service.mosh.MoshState
import com.jossephus.chuchu.service.mosh.NativeMoshService
import com.jossephus.chuchu.service.multiplexer.MultiplexerAvailability
import com.jossephus.chuchu.service.multiplexer.MultiplexerCommandResult
import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class SessionStatus {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Error,
}

data class SessionState(
    val status: SessionStatus = SessionStatus.Disconnected,
    val sessionKey: String? = null,
    val snapshot: TerminalSnapshot? = null,
    val title: String? = null,
    val pwd: String? = null,
    val bellCount: Int = 0,
    val nativeVersion: String? = null,
    val reconnectAttempt: Int = 0,
    val error: String? = null,
    val handle: Long = 0,
)

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val previousFingerprint: String?,
)

class TerminalSessionEngine(
    private val scope: CoroutineScope,
    private val hostKeyStore: HostKeyStore,
    private val tailscaleStatusChecker: TailscaleStatusChecker,
) {
    private data class ConnectionParams(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val authMethod: AuthMethod,
        val publicKeyOpenSsh: String,
        val privateKeyPem: String,
        val keyPassphrase: String,
        val transport: Transport,
        val postConnectCommand: String? = null,
        val multiplexer: MultiplexerType? = null,
        val multiplexerSessionName: String? = null,
        val multiplexerCreateIfMissing: Boolean = true,
    ) {
        fun multiplexerStartupCommand(): String? {
            val type = multiplexer ?: return null
            if (!type.runtimeSupported || transport == Transport.Mosh) return null
            val sessionName = multiplexerSessionName?.takeIf { it.isNotBlank() } ?: return null
            val runtime = MultiplexerRegistry.forType(type) ?: return null
            return runtime.launchCommand(
                sessionName = sessionName,
                createIfMissing = multiplexerCreateIfMissing,
                trustedRemoteName = true,
            )
        }
    }

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
                Thread(r, "terminal-session").apply { isDaemon = true }
            }
            .asCoroutineDispatcher()
    @Volatile private var disposed = false

    private val bridge = GhosttyBridge()
    private val nativeSsh = NativeSshService(hostKeyPolicy = ::verifyHostKey)
    private val moshService = NativeMoshService()

    private var handle: Long = 0L
    private var readJob: Job? = null
    private var cols = 80
    private var rows = 24
    private var screenWidth = 0
    private var screenHeight = 0
    private var cellWidth = 1
    private var cellHeight = 1
    private var lastSnapshotAtMs = 0L
    private var snapshotScheduled = false
    private val snapshotIntervalMs = 16L
    private var title: String? = null
    private var pwd: String? = null
    private var images: List<ImagePlacement> = emptyList()
    private var pendingColorScheme: Int? = null
    private var pendingDefaultColors: DefaultColors? = null
    private var lastConnectionParams: ConnectionParams? = null
    private var reconnectJob: Job? = null
    private var disconnectRequested = false


    private val nativeVersion =
        if (bridge.isLoaded()) {
            runCatching { bridge.nativeVersion() }.getOrNull()
        } else {
            null
        }

    private val _state = MutableStateFlow(SessionState(nativeVersion = nativeVersion))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    data class DefaultColors(
        val fg: IntArray?,
        val bg: IntArray?,
        val cursor: IntArray?,
        val palette: ByteArray?,
    )

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()
    private var hostKeyDecision: CompletableDeferred<Boolean>? = null

    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        authMethod: AuthMethod,
        publicKeyOpenSsh: String,
        privateKeyPem: String,
        keyPassphrase: String,
        transport: Transport,
        sessionKey: String,
        postConnectCommand: String? = null,
        multiplexer: MultiplexerType? = null,
        multiplexerSessionName: String? = null,
        multiplexerCreateIfMissing: Boolean = true,
    ) {
        disconnectRequested = false
        val params =
            ConnectionParams(
                host = host,
                port = port,
                username = username,
                password = password,
                authMethod = authMethod,
                publicKeyOpenSsh = publicKeyOpenSsh,
                privateKeyPem = privateKeyPem,
                keyPassphrase = keyPassphrase,
                transport = transport,
                postConnectCommand = postConnectCommand,
                multiplexer = multiplexer,
                multiplexerSessionName = multiplexerSessionName,
                multiplexerCreateIfMissing = multiplexerCreateIfMissing,
            )
        lastConnectionParams = params
        scope.launch(dispatcher) {
            reconnectJob?.cancel()
            reconnectJob = null
            _state.value =
                _state.value.copy(
                    status = SessionStatus.Connecting,
                    error = null,
                    reconnectAttempt = 0,
                    sessionKey = sessionKey,
                )
            if (!bridge.isLoaded()) {
                _state.value =
                    SessionState(
                        status = SessionStatus.Error,
                        sessionKey = sessionKey,
                        error =
                            "Native terminal library ${bridge.nativeStatus()}. Check ABI/NDK build.",
                    )
                return@launch
            }
            if (transport != Transport.Mosh && !nativeSsh.isAvailable()) {
                _state.value =
                    SessionState(
                        status = SessionStatus.Error,
                        sessionKey = sessionKey,
                        error = "Native SSH unavailable. Check ABI/NDK build.",
                    )
                return@launch
            }
            if (transport == Transport.Mosh && !moshService.isLoaded) {
                _state.value =
                    SessionState(
                        status = SessionStatus.Error,
                        sessionKey = sessionKey,
                        error = "Native mosh unavailable. Check ABI/NDK build.",
                    )
                return@launch
            }
            if (username.isBlank()) {
                _state.value =
                    SessionState(
                        status = SessionStatus.Error,
                        sessionKey = sessionKey,
                        error = "Username required",
                    )
                return@launch
            }
            val multiplexerAvailability = checkMultiplexerAvailability(params)
            val multiplexerError = multiplexerAvailabilityErrorMessage(
                availability = multiplexerAvailability,
                selectedType = params.multiplexer,
            )
            if (multiplexerError != null) {
                _state.value =
                    SessionState(
                        status = SessionStatus.Error,
                        sessionKey = sessionKey,
                        error = multiplexerError,
                    )
                return@launch
            }
            try {
                establishConnection(params, username)
                _state.value =
                    _state.value.copy(
                        status = SessionStatus.Connected,
                        error = null,
                        reconnectAttempt = 0,
                        sessionKey = sessionKey,
                    )
                requestSnapshot(force = true)
                startReadLoop()
                sendStartupCommand(params)
            } catch (e: Exception) {
                Log.e("TerminalSession", "Connect failed", e)
                _state.value =
                    SessionState(
                        status = SessionStatus.Error,
                        sessionKey = sessionKey,
                        error = "${e::class.simpleName}: ${e.message}",
                    )
            }
        }
    }

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded =
                bridge.nativeEncodeKey(handle, key, codepoint, mods, action, utf8) ?: return@launch
            if (encoded.isEmpty()) return@launch
            try {
                writeRemote(encoded)
            } catch (_: Exception) {}
        }
    }

    fun writeText(text: String) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            if (text.isEmpty()) return@launch
            try {
                writeRemote(text.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {}
        }
    }

    fun setColorScheme(isDark: Boolean) {
        val scheme = if (isDark) 1 else 0
        pendingColorScheme = scheme
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            bridge.nativeSetColorScheme(handle, scheme)
        }
    }

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        pendingDefaultColors = DefaultColors(fg, bg, cursor, palette)
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            bridge.nativeSetDefaultColors(handle, fg, bg, cursor, palette)
            requestSnapshot(force = true)
        }
    }

    fun sendFocusEvent(focused: Boolean) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded = bridge.nativeEncodeFocus(handle, focused) ?: return@launch
            if (encoded.isEmpty()) return@launch
            try {
                writeRemote(encoded)
            } catch (_: Exception) {}
        }
    }

    fun sendMouseEvent(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded =
                bridge.nativeEncodeMouse(
                    handle,
                    action,
                    button,
                    mods,
                    x,
                    y,
                    anyButtonPressed,
                    trackLastCell,
                ) ?: return@launch
            if (encoded.isEmpty()) return@launch
            try {
                writeRemote(encoded)
            } catch (_: Exception) {}
        }
    }

    fun resize(
        newCols: Int,
        newRows: Int,
        newCellWidth: Int,
        newCellHeight: Int,
        newScreenWidth: Int,
        newScreenHeight: Int,
    ) {
        scope.launch(dispatcher) {
            try {
                if (newCols <= 0 || newRows <= 0) return@launch
                if (newCellWidth <= 0 || newCellHeight <= 0) return@launch
                cols = newCols
                rows = newRows
                cellWidth = newCellWidth
                cellHeight = newCellHeight
                screenWidth = newScreenWidth
                screenHeight = newScreenHeight
                if (handle != 0L) {
                    bridge.nativeResize(handle, cols, rows, cellWidth, cellHeight)
                    bridge.nativeSetMouseEncodingSize(
                        handle,
                        screenWidth,
                        screenHeight,
                        cellWidth,
                        cellHeight,
                        0,
                        0,
                        0,
                        0,
                    )
                    resizeRemote(cols, rows, screenWidth, screenHeight)
                    requestSnapshot(force = true)
                }
            } catch (e: Exception) {
                Log.e("TerminalSession", "Resize failed", e)
                if (!disconnectRequested && lastConnectionParams != null) {
                    scheduleReconnect("Connection interrupted: ${e.message ?: "resize failed"}")
                } else {
                    _state.value =
                        _state.value.copy(
                            status = SessionStatus.Error,
                            error = "Resize failed: ${e.message}",
                        )
                }
            }
        }
    }

    fun scroll(delta: Int, x: Float, y: Float) {
        scope.launch(dispatcher) {
            if (handle == 0L || delta == 0) {
                return@launch
            }
            bridge.nativeScroll(handle, delta, x, y)
            flushPtyWrites()
            requestSnapshot(force = true)
        }
    }

    fun scrollToActive() {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            bridge.nativeScrollToActive(handle)
            requestSnapshot(force = true)
        }
    }

    suspend fun sftpListDirectory(path: String): List<String> =
        withContext(dispatcher) { nativeSsh.sftpListDirectory(path) }

    suspend fun sftpRealpath(path: String): String =
        withContext(dispatcher) { nativeSsh.sftpRealpath(path) }

    suspend fun sftpOpenWrite(path: String) =
        withContext(dispatcher) { nativeSsh.sftpOpenWrite(path) }

    suspend fun sftpWriteChunk(data: ByteArray): Int =
        withContext(dispatcher) { nativeSsh.sftpWriteChunk(data) }

    suspend fun sftpCloseWrite() = withContext(dispatcher) { nativeSsh.sftpCloseWrite() }

    suspend fun sftpReadFile(path: String, maxBytes: Int): ByteArray =
        withContext(dispatcher) { nativeSsh.sftpReadFile(path, maxBytes) }

    suspend fun sftpDelete(path: String, isDirectory: Boolean) =
        withContext(dispatcher) {
            if (isDirectory) nativeSsh.sftpDeleteDirectory(path) else nativeSsh.sftpDeleteFile(path)
        }

    suspend fun checkMultiplexerAvailability(spec: TabSpec): MultiplexerAvailability =
        withContext(dispatcher) { checkMultiplexerAvailability(spec.toConnectionParams()) }

    private fun checkMultiplexerAvailability(params: ConnectionParams): MultiplexerAvailability {
        val type = params.multiplexer ?: return MultiplexerAvailability.Available
        val multiplexer = MultiplexerRegistry.forType(type)
            ?: return MultiplexerAvailability.UnsupportedMultiplexer(type)
        if (params.transport == Transport.Mosh) {
            return MultiplexerAvailability.UnsupportedTransport(params.transport)
        }
        return runCatching {
            val result = runMultiplexerCommand(params, multiplexer.availabilityCommand())
            if (result.isSuccess) {
                MultiplexerAvailability.Available
            } else {
                MultiplexerAvailability.Missing(type)
            }
        }.getOrElse { error ->
            MultiplexerAvailability.Error(
                message = error.message ?: "Could not check ${type.label} on this host",
            )
        }
    }

    private fun multiplexerAvailabilityErrorMessage(
        availability: MultiplexerAvailability,
        selectedType: MultiplexerType?,
    ): String? = when (availability) {
        MultiplexerAvailability.Available -> null
        is MultiplexerAvailability.Missing ->
            "${availability.multiplexer.label} executable was not found on the remote host"
        is MultiplexerAvailability.UnsupportedMultiplexer ->
            "${availability.multiplexer.label} is not supported yet"
        is MultiplexerAvailability.UnsupportedTransport ->
            "${selectedType?.label ?: "Multiplexer"} is not supported for ${availability.transport}"
        is MultiplexerAvailability.Error -> availability.message
    }

    suspend fun listMultiplexerSessions(spec: TabSpec): List<RemoteMultiplexerSession> =
        withContext(dispatcher) {
            val type = spec.multiplexer ?: throw IllegalStateException("No multiplexer selected")
            val multiplexer = MultiplexerRegistry.forType(type)
                ?: throw IllegalStateException("${type.label} is not supported yet")
            if (spec.transport == Transport.Mosh) {
                throw IllegalStateException("${type.label} is not supported for Mosh connections")
            }
            val result = runMultiplexerCommand(spec.toConnectionParams(), multiplexer.listSessionsCommand())
            if (!result.isSuccess) {
                throw IllegalStateException(result.stderr.ifBlank { "Failed to list ${type.label} sessions" })
            }
            multiplexer.parseSessions(result.stdout)
        }

    suspend fun resolveMultiplexerSessionName(
        spec: TabSpec,
        localSessionNames: Collection<String>,
    ): String = withContext(dispatcher) {
        val type = spec.multiplexer ?: MultiplexerRegistry.defaultType
        val multiplexer = MultiplexerRegistry.forType(type)
            ?: throw IllegalStateException("${type.label} is not supported yet")
        if (spec.transport == Transport.Mosh) {
            throw IllegalStateException("${type.label} is not supported for Mosh connections")
        }
        when (val availability = checkMultiplexerAvailability(spec.copy(multiplexer = type))) {
            MultiplexerAvailability.Available -> Unit
            is MultiplexerAvailability.Missing -> throw IllegalStateException(
                "${availability.multiplexer.label} executable was not found on the remote host",
            )
            is MultiplexerAvailability.UnsupportedMultiplexer -> throw IllegalStateException(
                "${availability.multiplexer.label} is not supported yet",
            )
            is MultiplexerAvailability.UnsupportedTransport -> throw IllegalStateException(
                "${type.label} is not supported for ${availability.transport}",
            )
            is MultiplexerAvailability.Error -> throw IllegalStateException(availability.message)
        }
        val remoteSessions = listMultiplexerSessions(spec.copy(multiplexer = type))
        val existingName = spec.multiplexerSessionName?.takeIf { it.isNotBlank() }
        if (existingName != null && spec.multiplexerCreateIfMissing) return@withContext existingName
        if (existingName != null) {
            if (remoteSessions.any { it.name == existingName }) return@withContext existingName
            throw IllegalStateException("${type.label} session \"$existingName\" is no longer available")
        }
        multiplexer.defaultSessionName(remoteSessions, localSessionNames)
    }

    fun disconnect() {
        disconnectRequested = true
        reconnectJob?.cancel()
        reconnectJob = null
        lastConnectionParams = null
        cancelHostKeyPrompt()
        scope.launch(dispatcher) {
            readJob?.cancel()
            readJob = null
            nativeSsh.close()
            moshService.close()
            if (handle != 0L) {
                bridge.nativeDestroy(handle)
                handle = 0L
            }
            snapshotScheduled = false
            title = null
            pwd = null
            images = emptyList()
            _state.value =
                SessionState(
                    status = SessionStatus.Disconnected,
                    nativeVersion = nativeVersion,
                    sessionKey = _state.value.sessionKey,
                )
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        disconnect()
        dispatcher.close()
    }

    private fun cancelHostKeyPrompt() {
        hostKeyDecision?.cancel()
        hostKeyDecision = null
        _hostKeyPrompt.value = null
    }

    fun respondToHostKey(accepted: Boolean) {
        hostKeyDecision?.complete(accepted)
        hostKeyDecision = null
        _hostKeyPrompt.value = null
    }

    private fun verifyHostKey(
        host: String,
        port: Int,
        algorithm: String,
        keyBytes: ByteArray,
    ): Boolean {
        val existing = hostKeyStore.loadKey(host, port, algorithm)
        if (existing != null && existing.contentEquals(keyBytes)) return true

        val previousFingerprint = existing?.let { hostKeyStore.fingerprintSha256(it) }
        val fingerprint = hostKeyStore.fingerprintSha256(keyBytes)
        val deferred =
            hostKeyDecision
                ?: CompletableDeferred<Boolean>().also {
                    hostKeyDecision = it
                    _hostKeyPrompt.value =
                        HostKeyPrompt(
                            host = host,
                            port = port,
                            algorithm = algorithm,
                            fingerprint = fingerprint,
                            previousFingerprint = previousFingerprint,
                        )
                }
        val accepted = runBlocking { deferred.await() }
        if (accepted) {
            hostKeyStore.saveKey(host, port, algorithm, keyBytes)
        }
        return accepted
    }

    private fun startReadLoop() {
        readJob =
            scope.launch(dispatcher) {
                try {
                    if (lastConnectionParams?.transport == Transport.Mosh) {
                        startMoshReadLoop()
                    } else {
                        startSshReadLoop()
                    }
                } catch (e: Exception) {
                    Log.e("TerminalSession", "Read loop failed", e)
                }
                scheduleReconnect("Connection interrupted")
            }
    }

    private suspend fun startSshReadLoop() {
        val buf = ByteArray(65536)
        var lastActivityMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val chunk = nativeSsh.read(buf.size)
            if (chunk == null) {
                break
            }
            if (chunk.isEmpty()) {
                // Adaptive poll: stay snappy while data is flowing, back off when
                // idle so a quiet session doesn't spin at 500 wakeups/sec.
                delay(idleReadDelayMs(System.currentTimeMillis() - lastActivityMs))
                continue
            }
            lastActivityMs = System.currentTimeMillis()
            if (handle == 0L) continue
            val wasImageLoading = bridge.nativeIsImageLoading(handle)
            flushPtyWrites()
            bridge.nativeWriteRemote(handle, chunk)
            flushPtyWrites()
            val isImageLoading = bridge.nativeIsImageLoading(handle)
            when {
                wasImageLoading && !isImageLoading -> {
                    requestSnapshot(force = true)
                }
                !isImageLoading -> requestSnapshot()
            }
        }
    }

    private suspend fun startMoshReadLoop() {
        Log.d("TerminalSession", "MOSH: read loop started")
        var loopCount = 0
        var lastActivityMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            loopCount++

            // Drive retransmissions, acks, heartbeats, timeout checks
            val tickOk = moshService.maintenanceTick()
            if (!tickOk) {
                Log.w("TerminalSession", "MOSH: maintenanceTick returned false")
            }

            // Drive the UDP socket (send queued datagrams, receive inbound)
            val pumpOk = moshService.pumpNetwork()
            if (!pumpOk) {
                Log.w("TerminalSession", "MOSH: pumpNetwork returned false")
            }

            // Drain all available output events
            var hadEvent = false
            while (true) {
                val event = moshService.pollOutput() ?: break
                hadEvent = true
                when (event.eventType) {
                    MoshEventType.HostBytes.code -> {
                        if (event.payload.isNotEmpty() && handle != 0L) {
                            bridge.nativeWriteRemote(handle, event.payload)
                        }
                    }
                    MoshEventType.Resize.code -> {
                        if (event.cols > 0 && event.rows > 0) {
                            cols = event.cols
                            rows = event.rows
                            bridge.nativeResize(handle, cols, rows, cellWidth, cellHeight)
                        }
                    }
                    MoshEventType.EchoAck.code -> Unit
                    MoshEventType.StateChanged.code -> {
                        Log.d("TerminalSession", "MOSH: STATE_CHANGED: ${String(event.payload)}")
                    }
                    MoshEventType.Diagnostic.code -> {
                        Log.d("TerminalSession", "MOSH: DIAG: ${String(event.payload)}")
                    }
                    else -> {
                        Log.d("TerminalSession", "MOSH: unknown event type=${event.eventType}")
                    }
                }
            }

            if (hadEvent && handle != 0L) {
                requestSnapshot()
                lastActivityMs = System.currentTimeMillis()
            }

            // Check session health
            val runtime = moshService.pollState()
            if (runtime != null) {
                if (runtime.state == MoshState.Failed.code) {
                    Log.e("TerminalSession", "MOSH: session FAILED code=${runtime.lastFailureCode}")
                    break
                }
            }

            // Adaptive cadence: Mosh's retransmit/heartbeat timers are coarse, so
            // backing the pump off while idle is safe and avoids a 500 Hz spin.
            delay(idleReadDelayMs(System.currentTimeMillis() - lastActivityMs))
        }
        Log.d("TerminalSession", "MOSH: read loop exited after $loopCount iterations")
    }

    private suspend fun establishConnection(params: ConnectionParams, username: String) {
        if (handle != 0L) {
            bridge.nativeDestroy(handle)
            handle = 0L
        }
        nativeSsh.close()
        moshService.close()
        handle = bridge.nativeCreate(cols, rows, 1000)
        applyTerminalOptions()

        when (params.transport) {
            Transport.Mosh -> establishMoshConnection(params, username)
            else -> establishSshConnection(params, username)
        }
    }

    private fun establishSshConnection(params: ConnectionParams, username: String) {
        check(nativeSsh.isAvailable()) { "Native SSH unavailable" }
        nativeSsh.connect(
            host = params.host,
            port = params.port,
            username = username,
            authMethod = params.authMethod,
            password = if (params.authMethod == AuthMethod.Password) params.password else "",
            publicKeyOpenSsh = params.publicKeyOpenSsh,
            privateKeyPem = params.privateKeyPem,
            keyPassphrase = params.keyPassphrase,
        )
        val startupCommand = params.multiplexerStartupCommand()?.trim().orEmpty()
        if (startupCommand.isNotEmpty()) {
            nativeSsh.openExecPty(startupCommand, cols, rows, screenWidth, screenHeight)
        } else {
            nativeSsh.openShell(cols, rows, screenWidth, screenHeight)
        }
    }

    private suspend fun establishMoshConnection(params: ConnectionParams, username: String) {
        Log.d("TerminalSession", "MOSH: Phase 1 — SSH bootstrap start")
        check(nativeSsh.isAvailable()) { "Native SSH unavailable for mosh bootstrap" }
        nativeSsh.connect(
            host = params.host,
            port = params.port,
            username = username,
            authMethod = params.authMethod,
            password = if (params.authMethod == AuthMethod.Password) params.password else "",
            publicKeyOpenSsh = params.publicKeyOpenSsh,
            privateKeyPem = params.privateKeyPem,
            keyPassphrase = params.keyPassphrase,
        )
        // Use exec channel to bypass shell init noise and MOTD.
        // Falls back to shell if the remote server doesn't support exec.
        val moshCommand = "env LANG=C.UTF-8 LC_ALL=C.UTF-8 PATH=\"/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:/opt/local/bin:\$PATH\" mosh-server new -s -c 256"
        val execOpened = runCatching { nativeSsh.openExec(moshCommand) }.getOrDefault(false)
        if (!execOpened) {
            Log.w("TerminalSession", "MOSH: exec channel unavailable, falling back to shell")
            nativeSsh.openShell(cols, rows, screenWidth, screenHeight)
            val fallback = "$moshCommand\n"
            nativeSsh.write(fallback.toByteArray(Charsets.UTF_8))
        }
        Log.d(
            "TerminalSession",
            "MOSH: SSH connected, ${if (execOpened) "exec" else "shell"} channel opened",
        )

        // Read output until we find MOSH CONNECT, the channel hits EOF, or
        // we time out. If the first window has no output and no EOF on exec,
        // extend once to distinguish slow remote bootstrap from hard failure.
        val outputBuffer = StringBuilder()
        suspend fun readBootstrapWindow(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val chunk = nativeSsh.read(4096)
                if (chunk != null && chunk.isNotEmpty()) {
                    val text = String(chunk, Charsets.UTF_8)
                    outputBuffer.append(text)
                    val result = MoshBootstrapParser.parse(params.host, outputBuffer.toString())
                    if (result is MoshBootstrapParser.ParseResult.Success) {
                        Log.d(
                            "TerminalSession",
                            "MOSH: Parsed endpoint host=${result.endpoint.host} port=${result.endpoint.port}",
                        )
                        nativeSsh.close()
                        val configJson =
                            JSONObject()
                                .apply {
                                    put("host", result.endpoint.host)
                                    put("port", result.endpoint.port)
                                    put("keyBase64_22", result.endpoint.key)
                                    put("useNetworkCrypto", true)
                                }
                                .toString()
                        if (!moshService.create(configJson)) {
                            throw IllegalStateException("Failed to create mosh client")
                        }
                        Log.d("TerminalSession", "MOSH: mosh client created, starting...")
                        if (!moshService.start()) {
                            throw IllegalStateException("Failed to start mosh client")
                        }
                        Log.d("TerminalSession", "MOSH: mosh client started, resizing $cols x $rows")
                        moshService.resize(cols, rows)
                        return true
                    }
                } else {
                    if (execOpened && nativeSsh.isChannelEof()) {
                        Log.d("TerminalSession", "MOSH: exec channel EOF reached")
                        return false
                    }
                    delay(50)
                }
            }
            return false
        }

        var found = readBootstrapWindow(timeoutMs = 10_000)
        if (!found && execOpened && outputBuffer.isEmpty() && !nativeSsh.isChannelEof()) {
            Log.w("TerminalSession", "MOSH: bootstrap still running after 10s with no output; extending window by 15s")
            found = readBootstrapWindow(timeoutMs = 15_000)
        }
        if (!found) {
            val eofBeforeClose = if (execOpened) nativeSsh.isChannelEof() else true
            nativeSsh.close()
            val rawOutput = outputBuffer.toString()
            val reason =
                if (rawOutput.isBlank()) {
                    if (execOpened && !eofBeforeClose) {
                        "Mosh bootstrap command still running after timeout (no output, no EOF)"
                    } else {
                        "Mosh bootstrap produced no output"
                    }
                } else {
                    rawOutput.trim()
                }
            Log.e("TerminalSession", "MOSH: Bootstrap failed: $reason")
            throw IllegalStateException(reason)
        }
        Log.d("TerminalSession", "MOSH: Phase 2 — mosh client ready")
    }

    private fun TabSpec.toConnectionParams(): ConnectionParams = ConnectionParams(
        host = host,
        port = port,
        username = username,
        password = password,
        authMethod = authMethod,
        publicKeyOpenSsh = publicKeyOpenSsh,
        privateKeyPem = privateKeyPem,
        keyPassphrase = keyPassphrase,
        transport = transport,
        postConnectCommand = postConnectCommand,
        multiplexer = multiplexer,
        multiplexerSessionName = multiplexerSessionName,
        multiplexerCreateIfMissing = multiplexerCreateIfMissing,
    )

    private fun runMultiplexerCommand(
        params: ConnectionParams,
        command: String,
        timeoutMs: Long = 20_000,
    ): MultiplexerCommandResult {
        val ssh = NativeSshService(hostKeyPolicy = ::verifyHostKey)
        ssh.use { service ->
            service.connect(
                host = params.host,
                port = params.port,
                username = params.username,
                authMethod = params.authMethod,
                password = if (params.authMethod == AuthMethod.Password) params.password else "",
                publicKeyOpenSsh = params.publicKeyOpenSsh,
                privateKeyPem = params.privateKeyPem,
                keyPassphrase = params.keyPassphrase,
            )
            if (!service.openExec(withExitEnvelope(command))) {
                return MultiplexerCommandResult(1, "", "Remote server did not open an exec channel")
            }
            return readExecOutput(service, timeoutMs)
        }
    }

    private fun withExitEnvelope(command: String): String =
        "$command; printf '\nCHUCHU_EXIT:%s\n' \"\$?\""

    private fun readExecOutput(
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
                Thread.sleep(25)
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

    private fun scheduleReconnect(reason: String) {
        if (disconnectRequested) {
            _state.value = _state.value.copy(status = SessionStatus.Disconnected)
            return
        }
        val params =
            lastConnectionParams
                ?: run {
                    _state.value = _state.value.copy(status = SessionStatus.Disconnected)
                    return
                }
        if (reconnectJob?.isActive == true) return
        reconnectJob =
            scope.launch(dispatcher) {
                readJob?.cancel()
                readJob = null
                var attempt = 0
                while (currentCoroutineContext().isActive && !disconnectRequested) {
                    attempt += 1
                    _state.value =
                        _state.value.copy(
                            status = SessionStatus.Reconnecting,
                            reconnectAttempt = attempt,
                            error = reason,
                        )
                    val delayMs = (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(60_000L)
                    delay(delayMs)
                    if (params.username.isBlank()) {
                        _state.value =
                            _state.value.copy(
                                status = SessionStatus.Error,
                                error = "Username required",
                            )
                        return@launch
                    }
                    try {
                        establishConnection(params, params.username)
                        _state.value =
                            _state.value.copy(
                                status = SessionStatus.Connected,
                                reconnectAttempt = 0,
                                error = null,
                            )
                        requestSnapshot(force = true)
                        startReadLoop()
                        sendStartupCommand(params)
                        return@launch
                    } catch (e: Exception) {
                        Log.e("TerminalSession", "Reconnect attempt $attempt failed", e)
                        if (attempt >= 8) {
                            _state.value =
                                _state.value.copy(
                                    status = SessionStatus.Error,
                                    error = "Reconnect failed: ${e.message}",
                                )
                            return@launch
                        }
                    }
                }
            }
    }

    private fun applyTerminalOptions() {
        val localHandle = handle
        if (localHandle == 0L) return
        pendingColorScheme?.let { scheme -> bridge.nativeSetColorScheme(localHandle, scheme) }
        pendingDefaultColors?.let { colors ->
            bridge.nativeSetDefaultColors(
                localHandle,
                colors.fg,
                colors.bg,
                colors.cursor,
                colors.palette,
            )
        }
        if (screenWidth > 0 && screenHeight > 0) {
            bridge.nativeSetMouseEncodingSize(
                localHandle,
                screenWidth,
                screenHeight,
                cellWidth,
                cellHeight,
                0,
                0,
                0,
                0,
            )
        }
    }

    private fun writeRemote(data: ByteArray) {
        if (lastConnectionParams?.transport == Transport.Mosh) {
            moshService.sendInput(data)
        } else {
            nativeSsh.write(data)
        }
    }

    private suspend fun sendStartupCommand(params: ConnectionParams) {
        val multiplexerCommand = params.multiplexerStartupCommand()?.trim().orEmpty()
        if (multiplexerCommand.isNotEmpty() && params.transport != Transport.Mosh) return
        sendPostConnectCommand(params.postConnectCommand)
    }

    private fun sendPostConnectCommand(command: String?) {
        val trimmed = command?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        sendInteractiveCommand(trimmed, "post-connect command")
    }

    private fun sendInteractiveCommand(command: String, logLabel: String) {
        try {
            writeRemote("$command\n".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e("TerminalSession", "$logLabel failed", e)
        }
    }

    private fun flushPtyWrites() {
        if (handle == 0L) return
        repeat(8) {
            val ptyWrites = bridge.nativeDrainPtyWrites(handle)
            if (ptyWrites.isEmpty()) return
            try {
                writeRemote(ptyWrites)
            } catch (e: Exception) {
                Log.e("TerminalSession", "flushPtyWrites failed: ${e.message}")
                scope.launch(dispatcher) {
                    _state.value =
                        _state.value.copy(
                            status = SessionStatus.Error,
                            error = "Connection lost: ${e.message}",
                        )
                }
                return
            }
        }
    }

    private fun resizeRemote(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (lastConnectionParams?.transport == Transport.Mosh) {
            Log.d("TerminalSession", "MOSH: resizeRemote ${cols}x${rows}")
            moshService.resize(cols, rows)
        } else {
            nativeSsh.resize(cols, rows, widthPx, heightPx)
        }
    }

    private companion object {
        // Idle thresholds and the poll delay for each tier.
        private const val ACTIVE_WINDOW_MS = 50L
        private const val NEAR_IDLE_WINDOW_MS = 500L
        private const val IDLE_WINDOW_MS = 3_000L
        private const val MIN_READ_DELAY_MS = 2L
        private const val NEAR_IDLE_DELAY_MS = 8L
        private const val IDLE_DELAY_MS = 24L
        private const val MAX_READ_DELAY_MS = 64L
    }

    // Read-loop poll interval as a function of how long the session has been idle:
    // MIN while data is flowing (snappy echo), ramping to MAX once quiet so an idle
    // terminal stops spinning. The first byte after idle restores MIN within one
    // MAX interval.
    private fun idleReadDelayMs(idleForMs: Long): Long =
        when {
            idleForMs < ACTIVE_WINDOW_MS -> MIN_READ_DELAY_MS
            idleForMs < NEAR_IDLE_WINDOW_MS -> NEAR_IDLE_DELAY_MS
            idleForMs < IDLE_WINDOW_MS -> IDLE_DELAY_MS
            else -> MAX_READ_DELAY_MS
        }

    private fun requestSnapshot(force: Boolean = false) {
        if (handle == 0L) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastSnapshotAtMs
        if (force || elapsed >= snapshotIntervalMs) {
            snapshotScheduled = false
            emitSnapshot()
            lastSnapshotAtMs = now
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        val waitMs = (snapshotIntervalMs - elapsed).coerceAtLeast(1L)
        scope.launch(dispatcher) {
            delay(waitMs)
            snapshotScheduled = false
            if (handle == 0L) return@launch
            emitSnapshot()
            lastSnapshotAtMs = System.currentTimeMillis()
        }
    }

    private fun emitSnapshot() {
        if (handle == 0L) return
        try {
            val raw = bridge.nativeSnapshot(handle)
            val rawImages = bridge.nativeSnapshotImages(handle)
            images = TerminalSnapshot.parseImages(rawImages)
            val snap = TerminalSnapshot.fromByteBuffer(raw, images)
            val nextTitle = bridge.nativePollTitle(handle)
            val nextPwd = bridge.nativePollPwd(handle)
            val bellCount = bridge.nativeDrainBellCount(handle)
            if (nextTitle != null) {
                title = nextTitle
            }
            if (nextPwd != null) {
                pwd = nextPwd
            }
            _state.value =
                _state.value.copy(
                    snapshot = snap,
                    title = title,
                    pwd = pwd,
                    bellCount = bellCount,
                    nativeVersion = nativeVersion,
                    handle = handle,
                )
        } catch (e: Exception) {
            Log.e("TerminalSession", "emitSnapshot failed", e)
        }
    }

}

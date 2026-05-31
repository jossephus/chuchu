package com.jossephus.chuchu.service.terminal

import android.util.Log
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.mosh.MoshBootstrapParser
import com.jossephus.chuchu.service.mosh.MoshEventType
import com.jossephus.chuchu.service.mosh.MoshState
import com.jossephus.chuchu.service.mosh.NativeMoshService
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import java.nio.file.Path
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
    _userHomeDir: Path,
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
    )

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
                sendPostConnectCommand(params.postConnectCommand)
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

    fun disconnect() {
        disconnectRequested = true
        reconnectJob?.cancel()
        reconnectJob = null
        lastConnectionParams = null
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
            hostKeyDecision?.cancel()
            hostKeyDecision = null
            _hostKeyPrompt.value = null
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
        while (currentCoroutineContext().isActive) {
            val chunk = nativeSsh.read(buf.size)
            if (chunk == null) {
                break
            }
            if (chunk.isEmpty()) {
                delay(2)
                continue
            }
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
            }

            // Check session health
            val runtime = moshService.pollState()
            if (runtime != null) {
                if (runtime.state == MoshState.Failed.code) {
                    Log.e("TerminalSession", "MOSH: session FAILED code=${runtime.lastFailureCode}")
                    break
                }
            }

            delay(2)
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
        nativeSsh.openShell(cols, rows, screenWidth, screenHeight)
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
        val moshCommand = "env LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256"
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
                        sendPostConnectCommand(params.postConnectCommand)
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

    private fun sendPostConnectCommand(command: String?) {
        val trimmed = command?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        try {
            writeRemote("$trimmed\n".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e("TerminalSession", "post-connect command failed", e)
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

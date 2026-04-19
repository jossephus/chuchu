package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.Executors
import android.util.Log

enum class SessionStatus {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

data class SessionState(
    val status: SessionStatus = SessionStatus.Disconnected,
    val snapshot: TerminalSnapshot? = null,
    val title: String? = null,
    val pwd: String? = null,
    val bellCount: Int = 0,
    val nativeVersion: String? = null,
    val error: String? = null,
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
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terminal-session").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val bridge = GhosttyBridge()
    private val nativeSsh = NativeSshService(hostKeyPolicy = ::verifyHostKey)

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

    private val nativeVersion = if (bridge.isLoaded()) {
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
    ) {
        scope.launch(dispatcher) {
            _state.value = SessionState(status = SessionStatus.Connecting)
            if (!bridge.isLoaded()) {
                _state.value = SessionState(
                    status = SessionStatus.Error,
                    error = "Native terminal library ${bridge.nativeStatus()}. Check ABI/NDK build.",
                )
                return@launch
            }
            if (transport == Transport.TailscaleSSH && !tailscaleStatusChecker.isActive()) {
                _state.value = SessionState(
                    status = SessionStatus.Error,
                    error = "Tailscale VPN is not active",
                )
                return@launch
            }
            val effectiveUsername = if (transport == Transport.TailscaleSSH && username.isBlank()) {
                "root"
            } else {
                username
            }
            if (effectiveUsername.isBlank()) {
                _state.value = SessionState(
                    status = SessionStatus.Error,
                    error = "Username required",
                )
                return@launch
            }
            try {
                handle = bridge.nativeCreate(cols, rows, 1000)
                applyTerminalOptions()
                val effectiveAuthMethod = if (transport == Transport.TailscaleSSH) {
                    AuthMethod.None
                } else {
                    authMethod
                }
                val authPassword = if (effectiveAuthMethod == AuthMethod.Password) password else null
                check(nativeSsh.isAvailable()) { "Native SSH unavailable" }
                nativeSsh.connect(
                    host = host,
                    port = port,
                    username = effectiveUsername,
                    authMethod = effectiveAuthMethod,
                    password = authPassword.orEmpty(),
                    publicKeyOpenSsh = publicKeyOpenSsh,
                    privateKeyPem = privateKeyPem,
                    keyPassphrase = keyPassphrase,
                )
                nativeSsh.openShell(cols, rows, screenWidth, screenHeight)
                _state.value = SessionState(status = SessionStatus.Connected)
                requestSnapshot(force = true)
                startReadLoop()
            } catch (e: Exception) {
                Log.e("TerminalSession", "Connect failed", e)
                _state.value = SessionState(
                    status = SessionStatus.Error,
                    error = "${e::class.simpleName}: ${e.message}",
                )
            }
        }
    }

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) {
        scope.launch(dispatcher) {
            if (handle == 0L) return@launch
            val encoded = bridge.nativeEncodeKey(handle, key, codepoint, mods, action, utf8) ?: return@launch
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
            val encoded = bridge.nativeEncodeMouse(
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
        }
    }

    fun scroll(delta: Int) {
        scope.launch(dispatcher) {
            if (handle == 0L || delta == 0) {
                return@launch
            }
            bridge.nativeScroll(handle, delta)
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

    fun disconnect() {
        scope.launch(dispatcher) {
            readJob?.cancel()
            readJob = null
            nativeSsh.close()
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
            _state.value = SessionState(
                status = SessionStatus.Disconnected,
                nativeVersion = nativeVersion,
            )
        }
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
        val deferred = hostKeyDecision ?: CompletableDeferred<Boolean>().also {
            hostKeyDecision = it
            _hostKeyPrompt.value = HostKeyPrompt(
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
        readJob = scope.launch(dispatcher) {
            val buf = ByteArray(65536)
            try {
                while (isActive) {
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
            } catch (e: Exception) {
                android.util.Log.e("TerminalSession", "Read loop failed", e)
            }
            scope.launch(dispatcher) {
                _state.value = _state.value.copy(status = SessionStatus.Disconnected)
            }
        }
    }

    private fun applyTerminalOptions() {
        val localHandle = handle
        if (localHandle == 0L) return
        pendingColorScheme?.let { scheme ->
            bridge.nativeSetColorScheme(localHandle, scheme)
        }
        pendingDefaultColors?.let { colors ->
            bridge.nativeSetDefaultColors(localHandle, colors.fg, colors.bg, colors.cursor, colors.palette)
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
        nativeSsh.write(data)
    }

    private fun flushPtyWrites() {
        if (handle == 0L) return
        repeat(8) {
            val ptyWrites = bridge.nativeDrainPtyWrites(handle)
            if (ptyWrites.isEmpty()) return
            try {
                nativeSsh.write(ptyWrites)
            } catch (e: Exception) {
                Log.e("TerminalSession", "flushPtyWrites failed: ${e.message}")
                scope.launch(dispatcher) {
                    _state.value = _state.value.copy(
                        status = SessionStatus.Error,
                        error = "Connection lost: ${e.message}"
                    )
                }
                return
            }
        }
    }

    private fun resizeRemote(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        nativeSsh.resize(cols, rows, widthPx, heightPx)
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
            _state.value = _state.value.copy(
                snapshot = snap,
                title = title,
                pwd = pwd,
                bellCount = bellCount,
                nativeVersion = nativeVersion,
            )
        } catch (e: Exception) {
            Log.e("TerminalSession", "emitSnapshot failed", e)
        }
    }
}

package com.jossephus.chuchu.service.terminal

import android.app.Application
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TerminalSessionRepository private constructor(
    application: Application,
) {
    private data class ConnectionSpec(
        val host: String,
        val port: Int,
        val username: String,
        val authMethod: AuthMethod,
        val transport: Transport,
    )

    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hostKeyStore = HostKeyStore(
        appContext.getSharedPreferences("host_keys", Application.MODE_PRIVATE),
    )
    private val tailscaleStatusChecker = TailscaleStatusChecker(appContext)
    private val engine = TerminalSessionEngine(
        scope,
        appContext.filesDir.toPath(),
        hostKeyStore,
        tailscaleStatusChecker,
    )

    private var attachedClients = 0
    private var activeConnectionSpec: ConnectionSpec? = null

    val sessionState: StateFlow<SessionState> = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = engine.hostKeyPrompt

    init {
        scope.launch {
            engine.state.collectLatest { state ->
                val shouldRunInBackground = attachedClients == 0 && (
                    state.status == SessionStatus.Connecting ||
                        state.status == SessionStatus.Connected
                    )
                if (shouldRunInBackground) {
                    SessionForegroundService.start(appContext)
                } else {
                    SessionForegroundService.stop(appContext)
                }
            }
        }
    }

    fun attachClient() {
        attachedClients += 1
        if (attachedClients == 1) {
            SessionForegroundService.stop(appContext)
        }
    }

    fun detachClient() {
        attachedClients = (attachedClients - 1).coerceAtLeast(0)
        val state = sessionState.value
        val shouldRunInBackground = attachedClients == 0 && (
            state.status == SessionStatus.Connecting ||
                state.status == SessionStatus.Connected
            )
        if (shouldRunInBackground) {
            SessionForegroundService.start(appContext)
        }
    }

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
    ) {
        val requestedSpec = ConnectionSpec(
            host = host,
            port = port,
            username = username,
            authMethod = authMethod,
            transport = transport,
        )
        val state = sessionState.value.status
        val alreadyActive = state == SessionStatus.Connecting || state == SessionStatus.Connected
        if (alreadyActive && activeConnectionSpec == requestedSpec) {
            return
        }
        activeConnectionSpec = requestedSpec
        engine.connect(
            host = host,
            port = port,
            username = username,
            password = password,
            authMethod = authMethod,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyPem = privateKeyPem,
            keyPassphrase = keyPassphrase,
            transport = transport,
            sessionKey = sessionKey,
        )
    }

    fun disconnect() {
        engine.disconnect()
        activeConnectionSpec = null
        SessionForegroundService.stop(appContext)
    }

    fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) = engine.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)

    fun scroll(delta: Int) = engine.scroll(delta)

    fun scrollToActive() = engine.scrollToActive()

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) =
        engine.writeKey(key, codepoint, mods, action, utf8)

    fun writeText(text: String) = engine.writeText(text)

    fun sendFocusEvent(focused: Boolean) = engine.sendFocusEvent(focused)

    fun sendMouseEvent(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) = engine.sendMouseEvent(action, button, mods, x, y, anyButtonPressed, trackLastCell)

    fun setColorScheme(isDark: Boolean) = engine.setColorScheme(isDark)

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) =
        engine.setDefaultColors(fg, bg, cursor, palette)

    fun respondToHostKey(accepted: Boolean) = engine.respondToHostKey(accepted)

    companion object {
        @Volatile
        private var instance: TerminalSessionRepository? = null

        fun getInstance(application: Application): TerminalSessionRepository {
            return instance ?: synchronized(this) {
                instance ?: TerminalSessionRepository(application).also { created ->
                    instance = created
                }
            }
        }
    }
}

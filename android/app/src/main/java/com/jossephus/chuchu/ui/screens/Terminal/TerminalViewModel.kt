package com.jossephus.chuchu.ui.screens.Terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TerminalViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val tailscaleStatusChecker = TailscaleStatusChecker(application)
    private val sessionRepository = TerminalSessionRepository.getInstance(application)

    private val _tailscaleActive = MutableStateFlow(tailscaleStatusChecker.isActive())
    val tailscaleActive: StateFlow<Boolean> = _tailscaleActive.asStateFlow()

    val sessionState: StateFlow<SessionState> = sessionRepository.sessionState
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt

    init {
        sessionRepository.attachClient()
    }

    private val _connectForm = MutableStateFlow(ConnectForm())
    val connectForm: StateFlow<ConnectForm> = _connectForm.asStateFlow()
    private var selectedHostId: Long? = null

    fun setSelectedHostId(hostId: Long?) {
        selectedHostId = hostId
    }

    fun updateHost(host: String) {
        _connectForm.value = _connectForm.value.copy(host = host)
    }

    fun updatePort(port: String) {
        _connectForm.value = _connectForm.value.copy(port = port)
    }

    fun updateUsername(username: String) {
        _connectForm.value = _connectForm.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _connectForm.value = _connectForm.value.copy(password = password)
    }

    fun updateTransport(transport: Transport) {
        val current = _connectForm.value
        val nextAuthMethod = when {
            transport == Transport.TailscaleSSH -> AuthMethod.Password
            transport == Transport.SSH && current.authMethod == AuthMethod.None -> AuthMethod.Password
            else -> current.authMethod
        }
        _connectForm.value = if (transport == Transport.TailscaleSSH) {
            current.copy(
                transport = transport,
                authMethod = nextAuthMethod,
                password = "",
                privateKeyPem = "",
                publicKeyOpenSsh = "",
                keyPassphrase = "",
            )
        } else {
            current.copy(transport = transport, authMethod = nextAuthMethod)
        }
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val transport = _connectForm.value.transport
        if (transport == Transport.TailscaleSSH) {
            return
        }
        if (transport == Transport.SSH && authMethod == AuthMethod.None) {
            return
        }
        _connectForm.value = _connectForm.value.copy(authMethod = authMethod)
    }

    fun updatePrivateKey(privateKeyPem: String, publicKeyOpenSsh: String = "") {
        _connectForm.value = _connectForm.value.copy(
            privateKeyPem = privateKeyPem,
            publicKeyOpenSsh = publicKeyOpenSsh,
        )
    }

    fun updateKeyPassphrase(keyPassphrase: String) {
        _connectForm.value = _connectForm.value.copy(keyPassphrase = keyPassphrase)
    }

    fun refreshTailscaleStatus() {
        _tailscaleActive.value = tailscaleStatusChecker.isActive()
    }

    fun connect() {
        val form = _connectForm.value
        val port = form.port.toIntOrNull() ?: 22
        val sessionKey = selectedHostId?.let { "host:$it" } ?: "${form.transport.name}:${form.username}@${form.host}:$port"
        refreshTailscaleStatus()
        sessionRepository.connect(
            host = form.host,
            port = port,
            username = form.username,
            password = form.password,
            authMethod = form.authMethod,
            publicKeyOpenSsh = form.publicKeyOpenSsh,
            privateKeyPem = form.privateKeyPem,
            keyPassphrase = form.keyPassphrase,
            transport = form.transport,
            sessionKey = sessionKey,
        )
    }

    fun disconnect() {
        sessionRepository.disconnect()
    }

    fun onCanvasSizeChanged(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        sessionRepository.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun onScroll(delta: Int) {
        sessionRepository.scroll(delta)
    }

    fun onPrimaryMouseClick(x: Float, y: Float) {
        sessionRepository.sendMouseEvent(
            action = GhosttyMouseAction.Press,
            button = GhosttyMouseButton.Left,
            mods = 0,
            x = x,
            y = y,
            anyButtonPressed = false,
            trackLastCell = false,
        )
        sessionRepository.sendMouseEvent(
            action = GhosttyMouseAction.Release,
            button = GhosttyMouseButton.Left,
            mods = 0,
            x = x,
            y = y,
            anyButtonPressed = false,
            trackLastCell = false,
        )
    }

    fun onHardwareKey(key: Int, codepoint: Int, mods: Int, action: Int) {
        val hasNonTextModifier = mods and ((1 shl 1) or (1 shl 2) or (1 shl 3)) != 0
        val isRelease = action == GhosttyKeyAction.Release
        if (!isRelease) {
            sessionRepository.scrollToActive()
        }
        val utf8 = if (codepoint > 0 && !hasNonTextModifier && !isRelease) codepoint.toChar().toString() else null
        sessionRepository.writeKey(key, codepoint, mods, action, utf8)
    }

    fun onTextInput(text: String) {
        sessionRepository.scrollToActive()
        sessionRepository.writeText(text)
    }

    fun onSpecialKeyInput(key: TerminalSpecialKey, mods: Int) {
        // Send press followed by release so terminal apps that track key state
        // see a complete key cycle.
        sessionRepository.scrollToActive()
        sessionRepository.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Press)
        sessionRepository.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Release)
    }

    fun onPasteText(text: String) {
        if (text.isEmpty()) return
        sessionRepository.scrollToActive()
        val chunkSize = 512
        viewModelScope.launch {
            var index = 0
            while (index < text.length) {
                val end = (index + chunkSize).coerceAtMost(text.length)
                sessionRepository.writeText(text.substring(index, end))
                index = end
                delay(8)
            }
        }
    }

    fun onFocusChanged(focused: Boolean) {
        sessionRepository.sendFocusEvent(focused)
    }

    fun onColorSchemeChanged(isDark: Boolean) {
        sessionRepository.setColorScheme(isDark)
    }

    fun onDefaultColorsChanged(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        sessionRepository.setDefaultColors(fg, bg, cursor, palette)
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    override fun onCleared() {
        sessionRepository.detachClient()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(TerminalViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return TerminalViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

private object GhosttyMouseAction {
    const val Release = 0
    const val Press = 1
}

private object GhosttyMouseButton {
    const val Left = 1
}

data class ConnectForm(
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.Password,
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
)

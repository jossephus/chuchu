package com.jossephus.chuchu.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.TerminalSessionEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TerminalViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val hostKeyStore = HostKeyStore(
        application.getSharedPreferences("host_keys", Application.MODE_PRIVATE),
    )
    private val tailscaleStatusChecker = TailscaleStatusChecker(application)
    private val engine = TerminalSessionEngine(
        viewModelScope,
        application.filesDir.toPath(),
        hostKeyStore,
        tailscaleStatusChecker,
    )

    private val _tailscaleActive = MutableStateFlow(tailscaleStatusChecker.isActive())
    val tailscaleActive: StateFlow<Boolean> = _tailscaleActive.asStateFlow()

    val sessionState: StateFlow<SessionState> = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = engine.hostKeyPrompt

    private val _connectForm = MutableStateFlow(ConnectForm())
    val connectForm: StateFlow<ConnectForm> = _connectForm.asStateFlow()

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
                keyPath = "",
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

    fun updateKeyPath(keyPath: String) {
        _connectForm.value = _connectForm.value.copy(keyPath = keyPath)
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
        refreshTailscaleStatus()
        engine.connect(
            host = form.host,
            port = port,
            username = form.username,
            password = form.password,
            authMethod = form.authMethod,
            keyPath = form.keyPath,
            keyPassphrase = form.keyPassphrase,
            transport = form.transport,
        )
    }

    fun disconnect() {
        engine.disconnect()
    }

    fun onCanvasSizeChanged(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        engine.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun onScroll(delta: Int) {
        engine.scroll(delta)
    }

    fun onHardwareKey(key: Int, codepoint: Int, mods: Int, action: Int) {
        val mapped = KeyMapper.map(
            keyCode = key,
            codepoint = codepoint,
            metaState = mods,
        ) ?: return
        engine.writeKey(mapped.key, mapped.codepoint, mapped.mods, action)
    }

    fun onTextInput(text: String) {
        engine.writeText(text)
    }

    fun onPasteText(text: String) {
        if (text.isEmpty()) return
        val chunkSize = 512
        viewModelScope.launch {
            var index = 0
            while (index < text.length) {
                val end = (index + chunkSize).coerceAtMost(text.length)
                engine.writeText(text.substring(index, end))
                index = end
                delay(8)
            }
        }
    }

    fun onFocusChanged(focused: Boolean) {
        engine.sendFocusEvent(focused)
    }

    fun onColorSchemeChanged(isDark: Boolean) {
        engine.setColorScheme(isDark)
    }

    fun onDefaultColorsChanged(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        engine.setDefaultColors(fg, bg, cursor, palette)
    }

    fun onHostKeyDecision(accepted: Boolean) {
        engine.respondToHostKey(accepted)
    }

    override fun onCleared() {
        engine.disconnect()
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

data class ConnectForm(
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.Password,
    val keyPath: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
)

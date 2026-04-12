package com.jossephus.chuchu.ui.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.HostKeyPolicy
import com.jossephus.chuchu.service.ssh.NativeSshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddServerViewModel(
    application: Application,
    private val hostId: Long?,
) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())

    private val _form = MutableStateFlow(AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val _testState = MutableStateFlow(ConnectionTestState())
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    init {
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                _form.value = AddServerForm(
                    id = profile.id,
                    name = profile.name,
                    host = profile.host,
                    port = profile.port.toString(),
                    username = profile.username,
                    password = profile.password,
                    keyPath = profile.keyPath,
                    keyPassphrase = profile.keyPassphrase,
                    transport = profile.transport,
                    authMethod = profile.authMethod,
                )
            }
        }
    }

    fun updateName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun updateHost(host: String) {
        _form.value = _form.value.copy(host = host)
    }

    fun updatePort(port: String) {
        _form.value = _form.value.copy(port = port)
    }

    fun updateUsername(username: String) {
        _form.value = _form.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _form.value = _form.value.copy(password = password)
    }

    fun updateKeyPath(keyPath: String) {
        _form.value = _form.value.copy(keyPath = keyPath)
    }

    fun updateKeyPassphrase(passphrase: String) {
        _form.value = _form.value.copy(keyPassphrase = passphrase)
    }

    fun updateTransport(transport: Transport) {
        val current = _form.value
        val nextAuthMethod = when {
            transport == Transport.TailscaleSSH -> AuthMethod.Password
            transport == Transport.SSH && current.authMethod == AuthMethod.None -> AuthMethod.Password
            else -> current.authMethod
        }
        _form.value = if (transport == Transport.TailscaleSSH) {
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
        val transport = _form.value.transport
        if (transport == Transport.TailscaleSSH) {
            return
        }
        if (transport == Transport.SSH && authMethod == AuthMethod.None) {
            return
        }
        _form.value = _form.value.copy(authMethod = authMethod)
    }

    fun testConnection() {
        val current = _form.value
        if (current.host.isBlank()) return
        val effectiveUsername = if (
            current.transport == Transport.TailscaleSSH && current.username.isBlank()
        ) {
            "root"
        } else {
            current.username.trim()
        }
        if (effectiveUsername.isBlank()) return
        _testState.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val effectiveAuthMethod = if (current.transport == Transport.TailscaleSSH) {
                        AuthMethod.None
                    } else {
                        current.authMethod
                    }
                    check(
                        effectiveAuthMethod == AuthMethod.Password || effectiveAuthMethod == AuthMethod.None,
                    ) { "Native SSH currently supports only password auth" }
                    val port = current.port.toIntOrNull() ?: 22
                    val policy = HostKeyPolicy { _, _, _, _ -> true }
                    val nativeSsh = NativeSshService(hostKeyPolicy = policy)
                    check(nativeSsh.isAvailable()) { "Native SSH unavailable" }
                    nativeSsh.connect(
                        host = current.host.trim(),
                        port = port,
                        username = effectiveUsername,
                        authMethod = effectiveAuthMethod,
                        password = if (effectiveAuthMethod == AuthMethod.Password) current.password else "",
                    )
                    nativeSsh.close()
                }
            }
            _testState.value = if (result.isSuccess) {
                ConnectionTestState(status = ConnectionTestStatus.Success, message = "Connected")
            } else {
                ConnectionTestState(
                    status = ConnectionTestStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "Connection failed",
                )
            }
        }
    }

    fun save(onComplete: () -> Unit) {
        val current = _form.value
        val port = current.port.toIntOrNull() ?: 22
        if (current.name.isBlank() || current.host.isBlank()) return
        val effectiveUsername = if (
            current.transport == Transport.TailscaleSSH && current.username.isBlank()
        ) {
            "root"
        } else {
            current.username.trim()
        }
        if (effectiveUsername.isBlank()) return

        viewModelScope.launch {
            val isTailscale = current.transport == Transport.TailscaleSSH
            val profile = HostProfile(
                id = current.id ?: 0L,
                name = current.name.trim(),
                host = current.host.trim(),
                port = port,
                username = effectiveUsername,
                password = if (isTailscale) "" else current.password,
                keyPath = if (isTailscale) "" else current.keyPath,
                keyPassphrase = if (isTailscale) "" else current.keyPassphrase,
                transport = current.transport,
                authMethod = if (isTailscale) AuthMethod.Password else current.authMethod,
            )
            hostRepository.upsert(profile)
            onComplete()
        }
    }

    companion object {
        fun factory(application: Application, hostId: Long?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddServerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddServerViewModel(application, hostId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

data class AddServerForm(
    val id: Long? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val keyPath: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
)

enum class ConnectionTestStatus {
    Idle,
    Running,
    Success,
    Error,
}

data class ConnectionTestState(
    val status: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val message: String? = null,
)

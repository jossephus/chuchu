package com.jossephus.chuchu.ui.screens.AddServer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.ssh.HostKeyPolicy
import com.jossephus.chuchu.service.ssh.NativeSshService
import com.jossephus.chuchu.service.ssh.Ed25519KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddServerViewModel(
    application: Application,
    private val hostId: Long?,
) : AndroidViewModel(application) {
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

    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val keyGenerator = Ed25519KeyGenerator()

    private val _form = MutableStateFlow(AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val _testState = MutableStateFlow(ConnectionTestState())
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val _allKeys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _allKeys.asStateFlow()

    init {
        viewModelScope.launch {
            sshKeyRepository.observeAll().collect { _allKeys.value = it }
        }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                val key = profile.keyId?.let { sshKeyRepository.getById(it) }
                _form.value = AddServerForm(
                    id = profile.id,
                    name = profile.name,
                    host = profile.host,
                    port = profile.port.toString(),
                    username = profile.username,
                    password = profile.password,
                    keyId = profile.keyId,
                    privateKeyPem = key?.privateKeyPem ?: "",
                    publicKeyOpenSsh = key?.publicKeyOpenSsh ?: "",
                    keyPassphrase = profile.keyPassphrase,
                    transport = profile.transport,
                    authMethod = profile.authMethod,
                    requireAuthOnConnect = profile.requireAuthOnConnect,
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

    fun updateKeyPassphrase(passphrase: String) {
        _form.value = _form.value.copy(keyPassphrase = passphrase)
    }

    fun selectStoredKey(keyId: Long?) {
        viewModelScope.launch {
            val selected = keyId?.let { id ->
                keys.value.firstOrNull { it.id == id } ?: sshKeyRepository.getById(id)
            }
            _form.value = _form.value.copy(
                keyId = keyId,
                privateKeyPem = selected?.privateKeyPem ?: "",
                publicKeyOpenSsh = selected?.publicKeyOpenSsh ?: "",
            )
        }
    }

    fun generateKey(nameHint: String = "") {
        viewModelScope.launch {
            val current = _form.value
            val baseName = if (nameHint.isNotBlank()) nameHint else current.name
            val generatedName = baseName.trim().ifBlank { "android-ed25519" }
            val existingNames = _allKeys.value.map { it.name }.toSet()
            val uniqueName = if (generatedName in existingNames) {
                var index = 2
                var candidate = "$generatedName-$index"
                while (candidate in existingNames) {
                    index += 1
                    candidate = "$generatedName-$index"
                }
                candidate
            } else {
                generatedName
            }
            val passphrase = current.keyPassphrase
            val key = withContext(Dispatchers.Default) { keyGenerator.generate(uniqueName, passphrase) }
            val id = sshKeyRepository.insert(key)
            _form.value = _form.value.copy(
                keyId = id,
                privateKeyPem = key.privateKeyPem,
                publicKeyOpenSsh = key.publicKeyOpenSsh,
            )
        }
    }

    fun deleteStoredKey(keyId: Long) {
        val current = _form.value
        if (current.keyId == keyId) {
            _form.value = current.copy(
                keyId = null,
                privateKeyPem = "",
                publicKeyOpenSsh = "",
                keyPassphrase = "",
            )
        }
        viewModelScope.launch {
            hostRepository.clearKeyReference(keyId)
            sshKeyRepository.deleteById(keyId)
        }
    }

    fun updateTransport(transport: Transport) {
        val current = _form.value
        val nextAuthMethod = when {
            transport == Transport.SSH && current.authMethod == AuthMethod.None -> AuthMethod.Password
            else -> current.authMethod
        }
        _form.value = current.copy(transport = transport, authMethod = nextAuthMethod)
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val current = _form.value
        if (authMethod == AuthMethod.None) {
            _form.value = current.copy(
                transport = Transport.TailscaleSSH,
                authMethod = AuthMethod.None,
            )
            return
        }
        _form.value = current.copy(authMethod = authMethod)
    }

    fun updateRequireAuthOnConnect(enabled: Boolean) {
        _form.value = _form.value.copy(requireAuthOnConnect = enabled)
    }

    fun testConnection() {
        val current = _form.value
        if (current.host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return
        _testState.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val port = current.port.toIntOrNull() ?: 22
                    val policy = HostKeyPolicy { _, _, _, _ -> true }
                    val nativeSsh = NativeSshService(hostKeyPolicy = policy)
                    check(nativeSsh.isAvailable()) { "Native SSH unavailable" }
                    nativeSsh.connect(
                        host = current.host.trim(),
                        port = port,
                        username = username,
                        authMethod = current.authMethod,
                        password = if (current.authMethod == AuthMethod.Password) current.password else "",
                        publicKeyOpenSsh = current.publicKeyOpenSsh,
                        privateKeyPem = current.privateKeyPem,
                        keyPassphrase = current.keyPassphrase,
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
        if (!current.canSave()) return
        val username = current.username.trim()
        if (username.isBlank()) return

        viewModelScope.launch {
            val profile = HostProfile(
                id = current.id ?: 0L,
                name = current.name.trim(),
                host = current.host.trim(),
                port = port,
                username = username,
                password = current.password,
                keyId = current.keyId,
                keyPassphrase = current.keyPassphrase,
                transport = current.transport,
                authMethod = current.authMethod,
                requireAuthOnConnect = current.requireAuthOnConnect,
            )
            hostRepository.upsert(profile)
            onComplete()
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
    val keyId: Long? = null,
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    val requireAuthOnConnect: Boolean = false,
)

fun AddServerForm.canSave(): Boolean {
    if (name.isBlank() || host.isBlank() || username.isBlank()) return false
    return when (authMethod) {
        AuthMethod.Key,
        AuthMethod.KeyWithPassphrase -> keyId != null && privateKeyPem.isNotBlank()
        else -> true
    }
}

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

package com.jossephus.chuchu.ui.screens.AddServer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.KeyGenerationResult
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.PluginManager
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.plugin.api.TransportProvider
import java.net.URI
import com.jossephus.chuchu.service.ssh.HostKeyCheck
import com.jossephus.chuchu.service.ssh.HostKeyPolicy
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import com.jossephus.chuchu.service.ssh.SshJumpHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddServerViewModel(
    application: Application,
    private val hostId: Long?,
    private val copyFromHostId: Long? = null,
) : AndroidViewModel(application) {
    companion object {
        fun factory(
            application: Application,
            hostId: Long?,
            copyFromHostId: Long? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddServerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddServerViewModel(application, hostId, copyFromHostId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val plugins = PluginManager.instance

    val transportProviders: StateFlow<List<TransportProvider>> = plugins.transports.items

    val multiplexerProviders: StateFlow<List<MultiplexerProvider>> = plugins.multiplexers.items

    fun transportProvider(form: AddServerForm): TransportProvider? = plugins.transports[form.transportId]
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val hostKeyStore =
        HostKeyStore(
            application.applicationContext.getSharedPreferences(
                HostKeyStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            )
        )

    private val _form = MutableStateFlow(AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val _testState = MutableStateFlow(ConnectionTestState())
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val _allKeys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _allKeys.asStateFlow()

    private val _jumpHostCandidates = MutableStateFlow<List<HostProfile>>(emptyList())
    val jumpHostCandidates: StateFlow<List<HostProfile>> = _jumpHostCandidates.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    init {
        viewModelScope.launch { sshKeyRepository.observeAll().collect { _allKeys.value = it } }
        viewModelScope.launch {
            hostRepository.observeAll().collect { hosts ->
                _jumpHostCandidates.value = hosts.filter { it.id != hostId }
            }
        }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                _form.value = AddServerForm.fromProfile(profile, loadKey(profile))
            }
        } else if (copyFromHostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(copyFromHostId) ?: return@launch
                val takenNames = hostRepository.observeAll().first().mapTo(HashSet()) { it.name }
                val copyLabel = getApplication<Application>().getString(R.string.add_server_copy_suffix)
                _form.value =
                    AddServerForm.copyOf(
                        profile,
                        loadKey(profile),
                        copyHostName(profile.name, copyLabel, takenNames),
                    )
            }
        }
    }

    private suspend fun loadKey(profile: HostProfile): SshKey? =
        profile.keyId?.let { sshKeyRepository.getById(it) }

    fun updateName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun updateHost(host: String) {
        _form.value = _form.value.copy(host = host.filterNot { it.isWhitespace() })
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
            val selected =
                keyId?.let { id ->
                    keys.value.firstOrNull { it.id == id } ?: sshKeyRepository.getById(id)
                }
            _form.value =
                _form.value.copy(
                    keyId = keyId,
                    privateKeyPem = selected?.privateKeyPem ?: "",
                    publicKeyOpenSsh = selected?.publicKeyOpenSsh ?: "",
                    keyPassphrase = "",
                )
        }
    }

    fun generateKey(nameHint: String = "") {
        viewModelScope.launch {
            val current = _form.value
            val baseName = if (nameHint.isNotBlank()) nameHint else current.name
            when (val result = sshKeyRepository.generate(baseName, current.keyPassphrase)) {
                is KeyGenerationResult.Success ->
                    _form.value =
                        _form.value.copy(
                            keyId = result.key.id,
                            privateKeyPem = result.key.privateKeyPem,
                            publicKeyOpenSsh = result.key.publicKeyOpenSsh,
                        )
                KeyGenerationResult.Failed ->
                    _errorMessage.emit(getApplication<Application>().getString(R.string.add_server_key_generation_failed))
            }
        }
    }

    fun updateTransport(transport: Transport) {
        val current = _form.value
        val nextAuthMethod =
            when {
                transport == Transport.SSH && current.authMethod == AuthMethod.None ->
                    AuthMethod.Password
                else -> current.authMethod
            }
        _form.value =
            current.copy(
                transport = transport,
                transportId = null,
                authMethod = nextAuthMethod,
                multiplexer = current.multiplexer.takeIf { transport.hostsMultiplexer },
                jumpHostId = current.jumpHostId.takeIf { transport.supportsJumpHost },
            )
    }

    fun updateJumpHost(jumpHostId: Long?) {
        val current = _form.value
        _form.value =
            current.copy(
                jumpHostId = jumpHostId.takeIf { current.transport.supportsJumpHost },
            )
        _testState.value = ConnectionTestState()
    }

    fun selectPluginTransport(transportId: String) {
        val current = _form.value
        _form.value = current.copy(transport = Transport.Plugin, transportId = transportId, multiplexer = null)
        _testState.value = ConnectionTestState()
    }

    fun applyPairingCode(code: String): Boolean {
        val current = _form.value
        val address = transportProvider(current)?.addressFromPairingCode(code) ?: return false
        val hostName = runCatching { URI(address).host }.getOrNull()?.substringBefore('.').orEmpty()
        _form.value =
            current.copy(
                host = address,
                name = current.name.ifBlank { hostName },
            )
        _testState.value = ConnectionTestState()
        return true
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val current = _form.value
        if (authMethod == AuthMethod.None) {
            _form.value =
                current.copy(transport = Transport.TailscaleSSH, authMethod = AuthMethod.None)
            return
        }
        _form.value = current.copy(authMethod = authMethod)
    }

    fun updateRequireAuthOnConnect(enabled: Boolean) {
        _form.value = _form.value.copy(requireAuthOnConnect = enabled)
    }

    fun updatePostConnectCommand(command: String) {
        _form.value = _form.value.copy(postConnectCommand = command)
    }

    fun updateMultiplexer(multiplexer: MultiplexerType?) {
        val current = _form.value
        _form.value =
            current.copy(
                multiplexer =
                    multiplexer?.takeIf { current.transport.hostsMultiplexer }
            )
    }

    fun testConnection() {
        val current = _form.value
        if (current.transport == Transport.Plugin) {
            testPluginConnection(current)
            return
        }
        val host = current.host.filterNot { it.isWhitespace() }
        if (host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return
        _testState.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        viewModelScope.launch {
            var hostKeyError: String? = null
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val port = current.port.toIntOrNull() ?: 22
                        val policy = HostKeyPolicy { host, port, algorithm, keyBytes ->
                            when (
                                val result = hostKeyStore.check(host, port, algorithm, keyBytes)
                            ) {
                                is HostKeyCheck.Match -> true
                                is HostKeyCheck.Unknown -> {
                                    hostKeyError =
                                        getApplication<Application>()
                                            .getString(R.string.add_server_host_key_unverified)
                                    false
                                }
                                is HostKeyCheck.Changed -> {
                                    hostKeyError =
                                        getApplication<Application>()
                                            .getString(
                                                R.string.add_server_host_key_changed,
                                                result.previousFingerprint,
                                                result.fingerprint,
                                            )
                                    false
                                }
                            }
                        }
                        val jumpHost = resolveJumpHost(current.jumpHostId)
                        NativeSshService(hostKeyPolicy = policy).use { nativeSsh ->
                            check(nativeSsh.isAvailable()) {
                                getApplication<Application>()
                                    .getString(R.string.add_server_native_ssh_unavailable)
                            }
                            nativeSsh.connect(
                                host = host,
                                port = port,
                                username = username,
                                authMethod = current.authMethod,
                                password =
                                    if (current.authMethod == AuthMethod.Password) current.password
                                    else "",
                                publicKeyOpenSsh = current.publicKeyOpenSsh,
                                privateKeyPem = current.privateKeyPem,
                                keyPassphrase = current.keyPassphrase,
                                jumpHost = jumpHost,
                            )
                        }
                    }
                }
            _testState.value =
                if (result.isSuccess) {
                    ConnectionTestState(
                        status = ConnectionTestStatus.Success,
                        message =
                            getApplication<Application>()
                                .getString(R.string.add_server_test_connected_message),
                    )
                } else {
                    ConnectionTestState(
                        status = ConnectionTestStatus.Error,
                        message =
                            hostKeyError
                                ?: result.exceptionOrNull()?.message
                                ?: getApplication<Application>()
                                    .getString(R.string.terminal_connection_failed),
                    )
                }
        }
    }

    private fun testPluginConnection(current: AddServerForm) {
        val provider = transportProvider(current) ?: return
        val address = provider.normalizeAddress(current.host) ?: return
        _testState.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        viewModelScope.launch {
            val result = runCatching { provider.test(address) }
            _testState.value =
                result.fold(
                    onSuccess = { message ->
                        ConnectionTestState(status = ConnectionTestStatus.Success, message = message)
                    },
                    onFailure = { error ->
                        ConnectionTestState(
                            status = ConnectionTestStatus.Error,
                            message = error.message ?: "Connection failed",
                        )
                    },
                )
        }
    }

    fun save(onComplete: (Long) -> Unit) {
        val current = _form.value
        if (current.transport == Transport.Plugin) {
            savePluginHost(current, onComplete)
            return
        }
        val host = current.host.filterNot { it.isWhitespace() }
        val port = current.port.toIntOrNull() ?: 22
        if (!current.canSave(null) || host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return

        viewModelScope.launch {
            val profile =
                HostProfile(
                    id = current.id ?: 0L,
                    name = current.name.trim(),
                    host = host,
                    port = port,
                    username = username,
                    password = current.password,
                    keyId = current.keyId,
                    keyPassphrase = current.keyPassphrase,
                    transport = current.transport,
                    authMethod = current.authMethod,
                    requireAuthOnConnect = current.requireAuthOnConnect,
                    postConnectCommand = current.postConnectCommand.trim().ifBlank { null },
                    multiplexer = current.multiplexer.takeIf { current.transport.hostsMultiplexer },
                    jumpHostId = current.jumpHostId.takeIf { current.transport.supportsJumpHost },
                )
            val savedId = hostRepository.upsert(profile)
            onComplete(current.id ?: savedId)
        }
    }

    private suspend fun resolveJumpHost(jumpHostId: Long?): SshJumpHost? {
        val id = jumpHostId ?: return null
        val jump =
            hostRepository.getById(id)
                ?: throw IllegalStateException("Jump host no longer exists")
        val key = jump.keyId?.let { sshKeyRepository.getById(it) }
        return SshJumpHost.fromHostProfile(jump, key)
    }

    private fun savePluginHost(current: AddServerForm, onComplete: (Long) -> Unit) {
        val provider = transportProvider(current)
        if (!current.canSave(provider)) return
        val address = provider?.normalizeAddress(current.host) ?: return
        viewModelScope.launch {
            val savedId = hostRepository.upsert(
                HostProfile(
                    id = current.id ?: 0L,
                    name = current.name.trim(),
                    host = address,
                    port = 0,
                    username = "",
                    transport = Transport.Plugin,
                    authMethod = AuthMethod.None,
                    requireAuthOnConnect = current.requireAuthOnConnect,
                    transportId = current.transportId,
                ),
            )
            onComplete(current.id ?: savedId)
        }
    }
}

fun copyHostName(name: String, copyLabel: String, takenNames: Set<String>): String {
    val base = "${name.trim()} $copyLabel".trim()
    if (base !in takenNames) return base
    return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in takenNames }
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
    val postConnectCommand: String = "",
    val multiplexer: MultiplexerType? = null,
    val jumpHostId: Long? = null,
    val transportId: String? = null,
) {
    companion object {
        fun fromProfile(profile: HostProfile, key: SshKey?): AddServerForm =
            AddServerForm(
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
                postConnectCommand = profile.postConnectCommand.orEmpty(),
                multiplexer = profile.multiplexer,
                jumpHostId = profile.jumpHostId,
                transportId = profile.transportId,
            )

        fun copyOf(profile: HostProfile, key: SshKey?, name: String): AddServerForm =
            fromProfile(profile, key).copy(id = null, name = name)
    }
}

fun AddServerForm.canSave(transportProvider: TransportProvider?): Boolean {
    if (transport == Transport.Plugin) return name.isNotBlank() && transportProvider?.normalizeAddress(host) != null
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

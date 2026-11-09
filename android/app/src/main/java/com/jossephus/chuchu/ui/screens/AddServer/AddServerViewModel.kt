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
import com.jossephus.chuchu.plugin.PluginSettingsEntry
import com.jossephus.chuchu.plugin.api.ToggleField
import com.jossephus.chuchu.plugin.api.TransportProvider
import com.jossephus.chuchu.service.transport.TransportRegistry
import java.net.URI
import com.jossephus.chuchu.service.ssh.HostKeyCheck
import com.jossephus.chuchu.service.ssh.HostKeyPolicy
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddServerViewModel(application: Application, private val hostId: Long?) :
    AndroidViewModel(application) {
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

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    init {
        viewModelScope.launch { sshKeyRepository.observeAll().collect { _allKeys.value = it } }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                val key = profile.keyId?.let { sshKeyRepository.getById(it) }
                _form.value =
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
                        transportId = profile.transportId,
                    )
            }
        }
    }

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
                // Keep an id whose plugin isn't loaded: reinstalling the plugin restores it.
                multiplexer = current.multiplexer.takeIf { transport != Transport.Mosh },
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
        val address = current.transportProvider?.addressFromPairingCode(code) ?: return false
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
                    multiplexer?.takeIf {
                        current.transport != Transport.Mosh && it.runtimeSupported
                    }
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

    private val pluginManager = PluginManager.getInstance(application)

    val pluginHostSettings: StateFlow<List<PluginSettingsEntry>> =
        pluginManager.settings
            .map { entries -> entries.filter { it.host != null } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Held until save so cancel discards them and a new host can be configured before it has an id.
    private val _pluginHostEdits = MutableStateFlow<Map<String, Map<ToggleField, String>>>(emptyMap())
    val pluginHostEdits: StateFlow<Map<String, Map<ToggleField, String>>> = _pluginHostEdits.asStateFlow()

    fun pluginHostValue(pluginId: String, field: ToggleField): String {
        _pluginHostEdits.value[pluginId]?.get(field)?.let { return it }
        val id = hostId ?: return defaultRaw(field)
        return pluginManager.hostSettingValues(pluginId, id).raw(field)
    }

    fun updatePluginHostValue(pluginId: String, field: ToggleField, raw: String) {
        val edits = _pluginHostEdits.value
        _pluginHostEdits.value = edits + (pluginId to (edits[pluginId].orEmpty() + (field to raw)))
    }

    private fun defaultRaw(field: ToggleField): String = field.default.toString()

    private fun testPluginConnection(current: AddServerForm) {
        val provider = current.transportProvider ?: return
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

    fun save(onComplete: () -> Unit) {
        val current = _form.value
        if (current.transport == Transport.Plugin) {
            savePluginHost(current, onComplete)
            return
        }
        val host = current.host.filterNot { it.isWhitespace() }
        val port = current.port.toIntOrNull() ?: 22
        if (!current.canSave() || host.isBlank()) return
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
                    multiplexer = current.multiplexer.takeIf { current.transport != Transport.Mosh },
                )
            val savedId = hostRepository.upsert(profile)
            savePluginHostEdits(savedId)
            onComplete()
        }
    }

    private fun savePluginHost(current: AddServerForm, onComplete: () -> Unit) {
        if (!current.canSave()) return
        val address = current.transportProvider?.normalizeAddress(current.host) ?: return
        viewModelScope.launch {
            val savedId =
                hostRepository.upsert(
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
                    )
                )
            savePluginHostEdits(savedId)
            onComplete()
        }
    }

    private fun savePluginHostEdits(savedId: Long) {
        _pluginHostEdits.value.forEach { (pluginId, edits) ->
            val values = pluginManager.hostSettingValues(pluginId, savedId)
            edits.forEach { (field, raw) -> values.set(field, raw) }
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
    val postConnectCommand: String = "",
    val multiplexer: MultiplexerType? = null,
    val transportId: String? = null,
) {
    val transportProvider: TransportProvider?
        get() = if (transport == Transport.Plugin) TransportRegistry.forId(transportId) else null
}

fun AddServerForm.canSave(): Boolean {
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

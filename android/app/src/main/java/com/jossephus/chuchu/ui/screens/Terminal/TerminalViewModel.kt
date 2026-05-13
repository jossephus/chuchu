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
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.FileBrowserEntry
import com.jossephus.chuchu.ui.screens.Files.FileBrowserUiState
import com.jossephus.chuchu.ui.screens.Files.FileEntryType
import com.jossephus.chuchu.ui.screens.Files.FileSort
import com.jossephus.chuchu.ui.screens.Files.UploadProgress
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val tabByHostKey = mutableMapOf<String, ConnectionTab>()
    private val fileHomeByHostKey = mutableMapOf<String, String>()
    private val _selectedTab = MutableStateFlow(ConnectionTab.Terminal)
    val selectedTab: StateFlow<ConnectionTab> = _selectedTab.asStateFlow()
    private val _fileBrowserState = MutableStateFlow(FileBrowserUiState())
    val fileBrowserState: StateFlow<FileBrowserUiState> = _fileBrowserState.asStateFlow()

    fun setSelectedHostId(hostId: Long?) {
        selectedHostId = hostId
        _selectedTab.value = tabByHostKey[hostKey()] ?: ConnectionTab.Terminal
    }

    fun selectTab(tab: ConnectionTab) {
        _selectedTab.value = tab
        tabByHostKey[hostKey()] = tab
        if (tab == ConnectionTab.Files && _fileBrowserState.value.entries.isEmpty()) {
            resolveInitialFilePathAndRefresh()
        }
    }

    private fun resolveInitialFilePathAndRefresh() {
        val key = hostKey()
        val cachedHome = fileHomeByHostKey[key]
        if (cachedHome != null) {
            _fileBrowserState.value = _fileBrowserState.value.copy(currentPath = cachedHome)
            refreshFileBrowser()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val fallback = sessionState.value.pwd?.takeIf { it.isNotBlank() } ?: "/"
            val resolved = runCatching { sessionRepository.sftpRealpath("~") }.getOrNull()
            val initial = resolved?.takeIf { it.isNotBlank() } ?: fallback
            fileHomeByHostKey[key] = initial
            _fileBrowserState.value = _fileBrowserState.value.copy(currentPath = initial, resolvedHomePath = initial)
            refreshFileBrowser()
        }
    }

    fun refreshFileBrowser() {
        val pwd = sessionState.value.pwd?.takeIf { it.isNotBlank() } ?: "/"
        val targetPath = _fileBrowserState.value.currentPath.ifBlank { pwd }
        _fileBrowserState.value = _fileBrowserState.value.copy(currentPath = targetPath, isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                sessionRepository.sftpListDirectory(targetPath)
            }.onSuccess { rows ->
                val entries = rows.map { row ->
                    val parts = row.split('\t')
                    val name = parts.getOrNull(0).orEmpty()
                    val kind = parts.getOrNull(1).orEmpty()
                    val size = parts.getOrNull(2)?.toLongOrNull()
                    val mtime = parts.getOrNull(3)?.toLongOrNull()
                    val normalizedBase = targetPath.trimEnd('/').ifEmpty { "/" }
                    val type = when (kind) {
                        "dir" -> FileEntryType.Directory
                        "file" -> FileEntryType.File
                        "link" -> FileEntryType.Symlink
                        else -> FileEntryType.Other
                    }
                    FileBrowserEntry(
                        name = name,
                        path = if (normalizedBase == "/") "/$name" else "$normalizedBase/$name",
                        type = type,
                        sizeBytes = if (size == null || size <= 0L) null else size,
                        modifiedAtText = mtime?.takeIf { it > 0L }?.let { formatMtime(it) },
                    )
                }
                val sortedEntries = when (_fileBrowserState.value.sort) {
                    FileSort.Name -> entries.sortedBy { it.name.lowercase() }
                    FileSort.Size -> entries.sortedByDescending { it.sizeBytes ?: -1L }
                    FileSort.Modified -> entries.sortedByDescending { it.modifiedAtText ?: "" }
                }
                // Only update if we're still looking at the same path
                if (_fileBrowserState.value.currentPath == targetPath) {
                    _fileBrowserState.value = _fileBrowserState.value.copy(entries = sortedEntries, isLoading = false, error = null)
                }
            }.onFailure { err ->
                if (_fileBrowserState.value.currentPath == targetPath) {
                    _fileBrowserState.value = _fileBrowserState.value.copy(isLoading = false, error = err.message ?: "Failed to list directory")
                }
            }
        }
    }

    suspend fun beginUpload(fileName: String) {
        val targetPath = _fileBrowserState.value.currentPath.trimEnd('/') + "/" + fileName
        sessionRepository.sftpOpenWrite(targetPath)
    }

    suspend fun writeUploadChunk(data: ByteArray) {
        sessionRepository.sftpWriteChunk(data)
    }

    suspend fun finishUpload() {
        sessionRepository.sftpCloseWrite()
        refreshFileBrowser()
    }

    fun setUploadProgress(progress: UploadProgress?) {
        _fileBrowserState.value = _fileBrowserState.value.copy(uploadProgress = progress)
    }

    fun goUpDirectory() {
        val current = _fileBrowserState.value.currentPath
        val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
        openPath(parent)
    }

    fun openPath(path: String) {
        _fileBrowserState.value = _fileBrowserState.value.copy(currentPath = path)
        refreshFileBrowser()
    }

    fun selectFileSort(sort: FileSort) {
        _fileBrowserState.value = _fileBrowserState.value.copy(sort = sort)
        refreshFileBrowser()
    }

    private fun hostKey(): String = selectedHostId?.toString() ?: "adhoc"

    private fun formatMtime(epochSeconds: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(epochSeconds * 1000L))
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
            transport == Transport.SSH && current.authMethod == AuthMethod.None -> AuthMethod.Password
            else -> current.authMethod
        }
        _connectForm.value = current.copy(transport = transport, authMethod = nextAuthMethod)
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val transport = _connectForm.value.transport
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

    fun updateDisplayName(displayName: String) {
        _connectForm.value = _connectForm.value.copy(displayName = displayName)
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
            hostLabel = form.displayName.ifBlank { "${form.username}@${form.host}:$port" },
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
    val displayName: String = "",
)

package com.jossephus.chuchu.ui.screens.Terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.service.ssh.TailscaleStatusChecker
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.FileBrowserEntry
import com.jossephus.chuchu.ui.screens.Files.FileBrowserUiState
import com.jossephus.chuchu.ui.screens.Files.FileEntryType
import com.jossephus.chuchu.ui.screens.Files.FileSort
import com.jossephus.chuchu.ui.screens.Files.UploadProgress
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val tailscaleStatusChecker = TailscaleStatusChecker(application)
    private val sessionRepository = TerminalSessionRepository.getInstance(application)

    private val _tailscaleActive = MutableStateFlow(tailscaleStatusChecker.isActive())
    val tailscaleActive: StateFlow<Boolean> = _tailscaleActive.asStateFlow()

    val tabs: StateFlow<List<TabSession>> = sessionRepository.tabs
    val activeTabId: StateFlow<String?> = sessionRepository.activeTabId
    val activeTab: StateFlow<TabSession?> = sessionRepository.activeTab
    val sessionState: StateFlow<SessionState> = sessionRepository.sessionState
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt

    init {
        sessionRepository.attachClient()
    }

    private val _connectionTabByTab = MutableStateFlow<Map<String, ConnectionTab>>(emptyMap())
    private val _fileBrowserStateByTab =
        MutableStateFlow<Map<String, FileBrowserUiState>>(emptyMap())
    private val fileHomeByTab = mutableMapOf<String, String>()

    val selectedTab: StateFlow<ConnectionTab> =
        combine(activeTabId, _connectionTabByTab) { id, map ->
                if (id == null) ConnectionTab.Terminal else map[id] ?: ConnectionTab.Terminal
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionTab.Terminal)

    val fileBrowserState: StateFlow<FileBrowserUiState> =
        combine(activeTabId, _fileBrowserStateByTab) { id, map ->
                if (id == null) FileBrowserUiState() else map[id] ?: FileBrowserUiState()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, FileBrowserUiState())

    fun selectTabForHost(hostId: Long?): TabSession? {
        val existing = sessionRepository.tabsForHost(hostId)
        if (existing.isEmpty()) return null
        val activeId = sessionRepository.activeTabId.value
        val target = existing.firstOrNull { it.id == activeId } ?: existing.last()
        sessionRepository.selectTab(target.id)
        return target
    }

    fun openTab(spec: TabSpec): TabSession {
        refreshTailscaleStatus()
        return sessionRepository.openTab(spec)
    }

    fun duplicateActiveTab(): TabSession? {
        val current = sessionRepository.activeTab.value ?: return null
        return openTab(current.spec)
    }

    fun selectTab(id: String) {
        sessionRepository.selectTab(id)
    }

    fun closeTab(id: String) {
        _connectionTabByTab.value = _connectionTabByTab.value - id
        _fileBrowserStateByTab.value = _fileBrowserStateByTab.value - id
        fileHomeByTab.remove(id)
        sessionRepository.closeTab(id)
    }

    fun reconnect() {
        refreshTailscaleStatus()
        sessionRepository.reconnectActive()
    }

    fun selectConnectionTab(tab: ConnectionTab) {
        val id = activeTabId.value ?: return
        _connectionTabByTab.value = _connectionTabByTab.value + (id to tab)
        if (tab == ConnectionTab.Files) {
            val state = _fileBrowserStateByTab.value[id]
            if (state == null || state.entries.isEmpty()) {
                resolveInitialFilePathAndRefresh(id)
            }
        }
    }

    private fun updateFileBrowserState(
        id: String,
        transform: (FileBrowserUiState) -> FileBrowserUiState,
    ) {
        val current = _fileBrowserStateByTab.value
        val previous = current[id] ?: FileBrowserUiState()
        _fileBrowserStateByTab.value = current + (id to transform(previous))
    }

    private fun resolveInitialFilePathAndRefresh(tabId: String) {
        val cachedHome = fileHomeByTab[tabId]
        if (cachedHome != null) {
            updateFileBrowserState(tabId) { it.copy(currentPath = cachedHome) }
            refreshFileBrowser(tabId)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val tabState = sessionRepository.tabs.value.firstOrNull { it.id == tabId }
            val fallback = tabState?.engine?.state?.value?.pwd?.takeIf { it.isNotBlank() } ?: "/"
            val resolved = runCatching { sessionRepository.sftpRealpath(tabId, "~") }.getOrNull()
            val initial = resolved?.takeIf { it.isNotBlank() } ?: fallback
            fileHomeByTab[tabId] = initial
            updateFileBrowserState(tabId) {
                it.copy(currentPath = initial, resolvedHomePath = initial)
            }
            refreshFileBrowser(tabId)
        }
    }

    fun refreshFileBrowser() {
        val tabId = activeTabId.value ?: return
        refreshFileBrowser(tabId)
    }

    private fun refreshFileBrowser(tabId: String) {
        val pwd =
            sessionRepository.tabs.value
                .firstOrNull { it.id == tabId }
                ?.sessionState
                ?.value
                ?.pwd
                ?.takeIf { it.isNotBlank() } ?: "/"
        val targetPath = (_fileBrowserStateByTab.value[tabId]?.currentPath?.ifBlank { pwd }) ?: pwd
        updateFileBrowserState(tabId) {
            it.copy(currentPath = targetPath, isLoading = true, error = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionRepository.sftpListDirectory(tabId, targetPath) }
                .onSuccess { rows ->
                    val entries =
                        rows.map { row ->
                            val parts = row.split('\t')
                            val name = parts.getOrNull(0).orEmpty()
                            val kind = parts.getOrNull(1).orEmpty()
                            val size = parts.getOrNull(2)?.toLongOrNull()
                            val mtime = parts.getOrNull(3)?.toLongOrNull()
                            val normalizedBase = targetPath.trimEnd('/').ifEmpty { "/" }
                            val type =
                                when (kind) {
                                    "dir" -> FileEntryType.Directory
                                    "file" -> FileEntryType.File
                                    "link" -> FileEntryType.Symlink
                                    else -> FileEntryType.Other
                                }
                            FileBrowserEntry(
                                name = name,
                                path =
                                    if (normalizedBase == "/") "/$name"
                                    else "$normalizedBase/$name",
                                type = type,
                                sizeBytes = if (size == null || size <= 0L) null else size,
                                modifiedAtText = mtime?.takeIf { it > 0L }?.let { formatMtime(it) },
                            )
                        }
                    val current = _fileBrowserStateByTab.value[tabId] ?: FileBrowserUiState()
                    val sortedEntries =
                        when (current.sort) {
                            FileSort.Name -> entries.sortedBy { it.name.lowercase() }
                            FileSort.Size -> entries.sortedByDescending { it.sizeBytes ?: -1L }
                            FileSort.Modified ->
                                entries.sortedByDescending { it.modifiedAtText ?: "" }
                        }
                    if (current.currentPath == targetPath) {
                        updateFileBrowserState(tabId) {
                            it.copy(entries = sortedEntries, isLoading = false, error = null)
                        }
                    }
                }
                .onFailure { err ->
                    val current = _fileBrowserStateByTab.value[tabId]
                    if (current?.currentPath == targetPath) {
                        updateFileBrowserState(tabId) {
                            it.copy(
                                isLoading = false,
                                error = err.message ?: "Failed to list directory",
                            )
                        }
                    }
                }
        }
    }

    suspend fun beginUpload(fileName: String) {
        val tabId = activeTabId.value ?: return
        val current = _fileBrowserStateByTab.value[tabId] ?: return
        val targetPath = current.currentPath.trimEnd('/') + "/" + fileName
        sessionRepository.sftpOpenWrite(tabId, targetPath)
    }

    suspend fun writeUploadChunk(data: ByteArray) {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpWriteChunk(tabId, data)
    }

    suspend fun finishUpload() {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpCloseWrite(tabId)
        refreshFileBrowser()
    }

    fun setUploadProgress(progress: UploadProgress?) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(uploadProgress = progress) }
    }

    fun goUpDirectory() {
        val tabId = activeTabId.value ?: return
        val current = _fileBrowserStateByTab.value[tabId]?.currentPath ?: return
        val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
        openPath(parent)
    }

    fun openPath(path: String) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(currentPath = path) }
        refreshFileBrowser()
    }

    fun selectFileSort(sort: FileSort) {
        val tabId = activeTabId.value ?: return
        updateFileBrowserState(tabId) { it.copy(sort = sort) }
        refreshFileBrowser()
    }

    suspend fun deleteFile(tabId: String, entry: FileBrowserEntry) {
        sessionRepository.sftpDelete(tabId, entry.path, entry.type == FileEntryType.Directory)
        withContext(Dispatchers.Main) { refreshFileBrowser() }
    }

    suspend fun readFile(tabId: String, entry: FileBrowserEntry, maxBytes: Int): ByteArray {
        return sessionRepository.sftpReadFile(tabId, entry.path, maxBytes)
    }

    private fun formatMtime(epochSeconds: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(epochSeconds * 1000L))
    }

    fun refreshTailscaleStatus() {
        _tailscaleActive.value = tailscaleStatusChecker.isActive()
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

    fun onHardwareKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int = 0) {
        val hasNonTextModifier = mods and ((1 shl 1) or (1 shl 2) or (1 shl 3)) != 0
        val isRelease = action == GhosttyKeyAction.Release
        if (!isRelease) {
            sessionRepository.scrollToActive()
        }
        val effectiveCodepoint = if (charCode > 0) charCode else codepoint

        val utf8 =
            if (effectiveCodepoint > 0 && !hasNonTextModifier && !isRelease) effectiveCodepoint.toChar().toString()
            else null
        sessionRepository.writeKey(key, effectiveCodepoint, mods, action, utf8)
    }

    fun onTextInput(text: String) {
        sessionRepository.scrollToActive()
        sessionRepository.writeText(text)
    }

    fun onSpecialKeyInput(key: TerminalSpecialKey, mods: Int) {
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

    fun onDefaultColorsChanged(
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    ) {
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

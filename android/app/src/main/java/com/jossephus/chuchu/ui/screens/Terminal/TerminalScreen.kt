package com.jossephus.chuchu.ui.screens.Terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.FileBrowserScreen
import com.jossephus.chuchu.ui.screens.Files.UploadProgress
import com.jossephus.chuchu.ui.screens.Files.formatFileSize
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.ChuchuHint
import com.jossephus.chuchu.ui.terminal.ChuchuKeyBindings
import com.jossephus.chuchu.ui.terminal.CustomActionModifier
import com.jossephus.chuchu.ui.terminal.GhosttyKey
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.KeyboardAccessoryBar
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryDispatcher
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.terminal.TerminalCustomAction
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey
import com.jossephus.chuchu.ui.terminal.decodeCustomActionValue
import com.jossephus.chuchu.ui.terminal.modifierStateForCustomAction
import com.jossephus.chuchu.ui.terminal.toGhosttyKey
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import com.jossephus.chuchu.ui.theme.toRgbIntArray
import com.jossephus.chuchu.ui.theme.toTerminalPaletteBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun TerminalViewModel.dispatchTextWithModifierState(
    text: String,
    modifierState: ModifierState,
) {
    val mods = modifierState.terminalMods()
    for (char in text) {
        val ghosttyKey =
            when (char) {
                '\r',
                '\n' -> GhosttyKey.enter
                '\t' -> GhosttyKey.tab
                else -> char.toGhosttyKey()
            }
        if (ghosttyKey != null) {
            val codepoint =
                when (char) {
                    '\r',
                    '\n',
                    '\t' -> 0
                    else -> char.code
                }
            onHardwareKey(ghosttyKey, codepoint, mods, GhosttyKeyAction.Press)
            onHardwareKey(ghosttyKey, codepoint, mods, GhosttyKeyAction.Release)
        } else {
            onTextInput(modifierState.applyToText(char.toString()))
        }
    }
}

@Composable
private fun TerminalCustomActionsFab(
    groups: List<TerminalCustomKeyGroup>,
    onActionClick: (TerminalCustomAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var expanded by remember { mutableStateOf(false) }
    var selectedGroupKey by remember { mutableStateOf<String?>(null) }
    val selectedGroup =
        remember(selectedGroupKey, groups) {
            groups.firstOrNull { it.keyLabel == selectedGroupKey }
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedGroup == null) {
                    groups.forEach { group ->
                        ChuButton(
                            onClick = {
                                if (group.actions.size == 1) {
                                    onActionClick(group.actions.first())
                                    expanded = false
                                    selectedGroupKey = null
                                } else {
                                    selectedGroupKey = group.keyLabel
                                }
                            },
                            variant = ChuButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ChuText(group.keyLabel, style = typography.label)
                        }
                    }
                } else {
                    selectedGroup.actions.forEach { action ->
                        ChuButton(
                            onClick = {
                                onActionClick(action)
                                expanded = false
                                selectedGroupKey = null
                            },
                            variant = ChuButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ChuText(action.label, style = typography.label)
                        }
                    }
                    ChuButton(
                        onClick = { selectedGroupKey = null },
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        ChuText("<", style = typography.label, color = colors.textMuted)
                    }
                }
            }
        }

        // TUI-style action toggle — square bracketed button instead of a
        // circular Material FAB. Reads like a macro/quick-key trigger.
        ChuButton(
            onClick = {
                expanded = !expanded
                if (!expanded) {
                    selectedGroupKey = null
                }
            },
            variant = if (expanded) ChuButtonVariant.Ghost else ChuButtonVariant.Filled,
            bracketed = true,
            borderColor = if (expanded) colors.textMuted else null,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            ChuText(
                if (expanded) "x" else "+",
                style = typography.label,
                color = if (expanded) colors.textMuted else colors.onAccent,
            )
        }
    }
}

@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    hostId: Long?,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val activeTabForHost =
        remember(activeTab, hostId) { activeTab?.takeIf { it.spec.hostId == hostId } }
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val fileBrowserState by vm.fileBrowserState.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val screenInsetsModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    var lastSessionStatus by remember { mutableStateOf<SessionStatus?>(null) }
    val settingsRepo = remember(context) { SettingsRepository.getInstance(context) }
    val terminalPrefs =
        remember(context) { context.getSharedPreferences("chuchu_terminal", Context.MODE_PRIVATE) }
    val currentTheme by settingsRepo.themeName.collectAsStateWithLifecycle()
    val currentAccessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
    val useSingleRowAccessoryBar by settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
    val currentTerminalCustomKeyGroups by
        settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
    val accessoryLayout =
        remember(currentAccessoryLayoutIds) {
            TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
        }
    val ghosttyTheme =
        remember(context, currentTheme) { GhosttyThemeRegistry.getTheme(context, currentTheme) }
    val isDarkTheme = (ghosttyTheme?.background ?: colors.background).luminance() < 0.5f
    var selectedText by remember { mutableStateOf<String?>(null) }
    var hasSelectionActive by remember { mutableStateOf(false) }
    var selectionAnchorOffset by remember { mutableStateOf(Offset.Zero) }
    var selectionResetKey by remember { mutableStateOf(0) }
    var showPassphrasePrompt by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var pendingTabSpec by remember { mutableStateOf<TabSpec?>(null) }
    var showTabSheet by remember { mutableStateOf(false) }
    var hasSeenTabsForHost by remember(hostId) { mutableStateOf(false) }
    var focusedTabIndex by remember { mutableStateOf(0) }
    var terminalFontSizeSp by remember {
        mutableStateOf(terminalPrefs.getFloat("terminal_font_size_sp", 14f).coerceAtLeast(0.1f))
    }
    val chuchuKeys =
        remember(vm) {
            ChuchuKeyBindings(
                hints =
                    listOf(
                        ChuchuHint(key = "t", description = "tabs"),
                        ChuchuHint(key = "n", description = "new tab"),
                    ),
                handlers =
                    mapOf(
                        't' to { showTabSheet = true },
                        'n' to
                            {
                                vm.duplicateActiveTab()
                                vm.selectConnectionTab(ConnectionTab.Terminal)
                                showTabSheet = false
                            },
                    ),
            )
        }

    LaunchedEffect(terminalFontSizeSp) {
        terminalPrefs.edit().putFloat("terminal_font_size_sp", terminalFontSizeSp).apply()
    }

    LaunchedEffect(hostId) {
        showPassphrasePrompt = false
        passphraseInput = ""
        pendingTabSpec = null
        if (hostId == null) return@LaunchedEffect
        val existing = vm.selectTabForHost(hostId)
        if (existing != null) {
            return@LaunchedEffect
        }
        val db = AppDatabase.getInstance(context)
        val host = HostRepository(db.hostProfileDao()).getById(hostId) ?: return@LaunchedEffect
        val key = host.keyId?.let { SshKeyRepository(db.sshKeyDao()).getById(it) }
        vm.refreshTailscaleStatus()
        val baseSpec =
            TabSpec(
                hostId = host.id,
                displayName = host.name,
                host = host.host,
                port = host.port,
                username = host.username,
                password = host.password,
                authMethod = host.authMethod,
                publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
                privateKeyPem = key?.privateKeyPem.orEmpty(),
                keyPassphrase = "",
                transport = host.transport,
            )
        if (host.authMethod == AuthMethod.KeyWithPassphrase && key != null) {
            pendingTabSpec = baseSpec
            showPassphrasePrompt = true
        } else {
            vm.openTab(baseSpec)
        }
    }

    val hasTabsForHost =
        remember(tabs, hostId) {
            if (hostId == null) false else tabs.any { it.spec.hostId == hostId }
        }

    LaunchedEffect(hostId, hasTabsForHost) {
        if (hostId == null) return@LaunchedEffect
        if (hasTabsForHost) {
            hasSeenTabsForHost = true
        } else if (hasSeenTabsForHost) {
            onBack()
        }
    }

    LaunchedEffect(showTabSheet, tabs, activeTabId) {
        if (!showTabSheet || tabs.isEmpty()) return@LaunchedEffect
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        focusedTabIndex =
            if (activeIndex >= 0) activeIndex else focusedTabIndex.coerceIn(0, tabs.lastIndex)
    }

    if (showPassphrasePrompt) {
        ChuDialog(
            title = "Key passphrase",
            confirmLabel = "Connect",
            onConfirm = {
                val spec = pendingTabSpec
                showPassphrasePrompt = false
                if (spec != null) {
                    vm.openTab(spec.copy(keyPassphrase = passphraseInput))
                }
                passphraseInput = ""
                pendingTabSpec = null
            },
            onDismiss = {
                showPassphrasePrompt = false
                passphraseInput = ""
                pendingTabSpec = null
                onBack()
            },
        ) {
            ChuTextField(
                value = passphraseInput,
                onValueChange = { passphraseInput = it },
                label = "Passphrase",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    LaunchedEffect(sessionState.status, sessionState.error) {
        val previous = lastSessionStatus
        lastSessionStatus = sessionState.status
        if (sessionState.status == SessionStatus.Error && previous != SessionStatus.Error) {
            val message = sessionState.error ?: "Connection failed"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    if (hostKeyPrompt != null) {
        val prompt = hostKeyPrompt
        ChuDialog(
            onDismiss = { vm.onHostKeyDecision(false) },
            title = "Verify host key",
            confirmLabel = "Accept",
            dismissLabel = "Reject",
            onConfirm = { vm.onHostKeyDecision(true) },
        ) {
            val previous = prompt?.previousFingerprint
            val message = buildString {
                append("Host: ${prompt?.host}:${prompt?.port}\n")
                append("Algorithm: ${prompt?.algorithm}\n")
                if (previous != null) {
                    append("WARNING: host key changed!\n")
                    append("Old: $previous\n")
                }
                append("New: ${prompt?.fingerprint}")
            }
            ChuText(message, style = typography.body)
        }
    }

    when (sessionState.status) {
        SessionStatus.Disconnected,
        SessionStatus.Error -> {
            Column(
                modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (sessionState.error != null) {
                    ChuText(sessionState.error!!, color = colors.error, style = typography.body)
                    Spacer(modifier = Modifier.height(16.dp))
                    ChuButton(
                        onClick = vm::reconnect,
                        modifier = Modifier.fillMaxWidth(),
                        variant = ChuButtonVariant.Filled,
                    ) {
                        ChuText("Retry", style = typography.label, color = colors.onAccent)
                    }
                }
            }
        }

        SessionStatus.Connecting -> {
            Column(
                modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                ChuText(
                    "Connecting to ${activeTabForHost?.spec?.host ?: ""}...",
                    style = typography.body,
                )
            }
        }

        SessionStatus.Connected,
        SessionStatus.Reconnecting -> {
            val isReconnecting = sessionState.status == SessionStatus.Reconnecting
            val snapshot = sessionState.snapshot
            if (snapshot != null) {
                Box(modifier = screenInsetsModifier.fillMaxSize()) {
                    LaunchedEffect(ghosttyTheme, colors, isDarkTheme) {
                        vm.onColorSchemeChanged(isDarkTheme)
                        vm.onDefaultColorsChanged(
                            fg =
                                ghosttyTheme?.foreground?.toRgbIntArray()
                                    ?: colors.textPrimary.toRgbIntArray(),
                            bg =
                                ghosttyTheme?.background?.toRgbIntArray()
                                    ?: colors.background.toRgbIntArray(),
                            cursor =
                                ghosttyTheme?.cursorColor?.toRgbIntArray()
                                    ?: colors.accent.toRgbIntArray(),
                            palette = ghosttyTheme?.toTerminalPaletteBytes(),
                        )
                    }

                    LaunchedEffect(sessionState.bellCount) {
                        if (sessionState.bellCount > 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }

                    val inputViewRef = remember { mutableStateOf<TerminalInputView?>(null) }
                    val titleText = sessionState.title?.takeIf { it.isNotBlank() }
                    val pwdText = sessionState.pwd?.takeIf { it.isNotBlank() }
                    val inputMethodManager = remember {
                        context.getSystemService(InputMethodManager::class.java)
                    }
                    val requestInputFocus: () -> Unit = {
                        inputViewRef.value?.let { view -> view.showKeyboard(inputMethodManager) }
                    }
                    val hideSoftKeyboard: () -> Unit = {
                        val view = inputViewRef.value
                        if (view != null) {
                            inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    }
                    val clipboard = remember {
                        context.getSystemService(ClipboardManager::class.java)
                    }
                    var hasClipboardText by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(clipboard) {
                        fun check() {
                            hasClipboardText =
                                try {
                                    clipboard?.hasPrimaryClip() == true &&
                                        clipboard!!.primaryClip?.getItemAt(0)?.text?.isNotEmpty() ==
                                            true
                                } catch (_: SecurityException) {
                                    false
                                }
                        }
                        check()
                        val listener = ClipboardManager.OnPrimaryClipChangedListener { check() }
                        clipboard?.addPrimaryClipChangedListener(listener)
                        try {
                            awaitCancellation()
                        } finally {
                            clipboard?.removePrimaryClipChangedListener(listener)
                        }
                    }
                    var modifierState by remember { mutableStateOf(ModifierState()) }

                    fun resetModifiers() {
                        modifierState = modifierState.reset()
                    }

                    fun pasteClipboard(): Boolean {
                        val clip = clipboard?.primaryClip
                        if (clip == null || clip.itemCount == 0) {
                            resetModifiers()
                            return false
                        }
                        val text = clip.getItemAt(0).coerceToText(context).toString()
                        if (text.isNotEmpty()) {
                            vm.onPasteText(modifierState.applyToText(text))
                            resetModifiers()
                            selectedText = null
                            hasSelectionActive = false
                            selectionAnchorOffset = Offset.Zero
                            selectionResetKey += 1
                            return true
                        }
                        resetModifiers()
                        return false
                    }

                    fun dispatchAccessoryAction(action: AccessoryAction) {
                        if (
                            action is AccessoryAction.SendText && chuchuKeys.handleText(action.text)
                        ) {
                            return
                        }
                        if (chuchuKeys.isPrefixActive) {
                            chuchuKeys.reset()
                        }
                        val currentModifierState = modifierState
                        val result =
                            TerminalAccessoryDispatcher.dispatch(action, currentModifierState)
                        modifierState = result.modifierState

                        if (result.suppressImeInput) {
                            inputViewRef.value?.armInputSuppression(action.toString())
                        }

                        result.specialKey?.let { key ->
                            vm.onSpecialKeyInput(key, currentModifierState.terminalMods())
                        }

                        result.text?.let { text -> vm.onTextInput(text) }

                        if (result.shouldPaste) {
                            pasteClipboard()
                        }
                    }

                    fun copySelection() {
                        val text = selectedText ?: return
                        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal selection", text))
                        Toast.makeText(context, "Copied selection", Toast.LENGTH_SHORT).show()
                        selectedText = null
                        hasSelectionActive = false
                        selectionAnchorOffset = Offset.Zero
                        selectionResetKey += 1
                    }

                    val importFileLauncher =
                        rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetMultipleContents()
                        ) { uris: List<Uri> ->
                            if (uris.isEmpty()) return@rememberLauncherForActivityResult
                            scope.launch(Dispatchers.IO) {
                                var success = 0
                                var failed = 0
                                var lastError: String? = null
                                val total = uris.size
                                uris.forEachIndexed { index, uri ->
                                    val fileName =
                                        context.contentResolver
                                            .query(uri, null, null, null, null)
                                            ?.use { cursor ->
                                                val idx =
                                                    cursor.getColumnIndex(
                                                        OpenableColumns.DISPLAY_NAME
                                                    )
                                                if (cursor.moveToFirst() && idx >= 0)
                                                    cursor.getString(idx)
                                                else null
                                            } ?: uri.lastPathSegment ?: "uploaded_${index}"
                                    val fileSize =
                                        context.contentResolver
                                            .query(uri, null, null, null, null)
                                            ?.use { cursor ->
                                                val idx =
                                                    cursor.getColumnIndex(OpenableColumns.SIZE)
                                                if (cursor.moveToFirst() && idx >= 0)
                                                    cursor.getLong(idx)
                                                else 0L
                                            } ?: 0L
                                    try {
                                        val stream =
                                            context.contentResolver.openInputStream(uri)
                                                ?: throw IllegalStateException("Cannot open file")
                                        stream.use { input ->
                                            vm.beginUpload(fileName)
                                            vm.setUploadProgress(
                                                UploadProgress(
                                                    fileName = fileName,
                                                    bytesWritten = 0,
                                                    totalBytes = fileSize,
                                                    fileIndex = index,
                                                    totalFiles = total,
                                                )
                                            )
                                            val buffer = ByteArray(65536)
                                            var bytesWritten = 0L
                                            var lastProgressBytes = 0L
                                            var read: Int
                                            while (input.read(buffer).also { read = it } != -1) {
                                                vm.writeUploadChunk(buffer.copyOf(read))
                                                bytesWritten += read
                                                if (
                                                    bytesWritten - lastProgressBytes >= 262144 ||
                                                        bytesWritten == fileSize
                                                ) {
                                                    lastProgressBytes = bytesWritten
                                                    vm.setUploadProgress(
                                                        UploadProgress(
                                                            fileName = fileName,
                                                            bytesWritten = bytesWritten,
                                                            totalBytes = fileSize,
                                                            fileIndex = index,
                                                            totalFiles = total,
                                                        )
                                                    )
                                                }
                                            }
                                            vm.finishUpload()
                                        }
                                        success++
                                    } catch (e: Exception) {
                                        failed++
                                        lastError = e.message ?: e.javaClass.simpleName
                                        runCatching { vm.finishUpload() }
                                    } finally {
                                        vm.setUploadProgress(null)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    val msg =
                                        when {
                                            failed == 0 -> "Uploaded $success file(s)"
                                            lastError != null ->
                                                "Uploaded $success, $failed failed: $lastError"
                                            else -> "Uploaded $success, $failed failed"
                                        }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                    var pendingDownloadBytes by remember { mutableStateOf<ByteArray?>(null) }
                    var pendingDownloadName by remember { mutableStateOf("download.bin") }
                    var pendingDeleteEntry by remember {
                        mutableStateOf<com.jossephus.chuchu.ui.screens.Files.FileBrowserEntry?>(
                            null
                        )
                    }

                    val downloadLauncher =
                        rememberLauncherForActivityResult(
                            contract =
                                ActivityResultContracts.CreateDocument("application/octet-stream")
                        ) { uri: Uri? ->
                            val bytes = pendingDownloadBytes
                            if (uri == null || bytes == null)
                                return@rememberLauncherForActivityResult
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                        context.contentResolver.openOutputStream(uri)?.use {
                                            it.write(bytes)
                                        } ?: error("Cannot open destination")
                                    }
                                    .onSuccess {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                    context,
                                                    "Downloaded ${pendingDownloadName}",
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    }
                                    .onFailure {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                    context,
                                                    "Download failed: ${it.message}",
                                                    Toast.LENGTH_LONG,
                                                )
                                                .show()
                                        }
                                    }
                            }
                        }

                    LaunchedEffect(Unit) {
                        requestInputFocus()
                        vm.onFocusChanged(true)
                    }
                    Column(
                        modifier =
                            Modifier.fillMaxSize()
                                .blur(if (showTabSheet) 10.dp else 0.dp)
                                .imePadding()
                    ) {
                        if (selectedTab == ConnectionTab.Files) {
                            FileBrowserScreen(
                                state = fileBrowserState,
                                onGoUp = vm::goUpDirectory,
                                onRefresh = vm::refreshFileBrowser,
                                onSelectSort = vm::selectFileSort,
                                onOpenPath = vm::openPath,
                                onBackToTerminal = {
                                    vm.selectConnectionTab(ConnectionTab.Terminal)
                                },
                                onCopyPath = { path ->
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("path", path))
                                    Toast.makeText(context, "Copied path", Toast.LENGTH_SHORT)
                                        .show()
                                },
                                onImportFile = { importFileLauncher.launch("*/*") },
                                onOpenFile = { entry ->
                                    val tabId = activeTabId ?: return@FileBrowserScreen
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                                val bytes = vm.readFile(tabId, entry, 32 * 1024 * 1024)
                                                val safeName = entry.name.ifBlank { "remote_file" }
                                                val outFile = File(context.cacheDir, safeName)
                                                outFile.writeBytes(bytes)
                                                val uri =
                                                    FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        outFile,
                                                    )
                                                val ext =
                                                    safeName.substringAfterLast('.', "").lowercase()
                                                val mime =
                                                    MimeTypeMap.getSingleton()
                                                        .getMimeTypeFromExtension(ext)
                                                        ?: "application/octet-stream"
                                                val viewIntent =
                                                    Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, mime)
                                                        addFlags(
                                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        )
                                                    }
                                                withContext(Dispatchers.Main) {
                                                    context.startActivity(
                                                        Intent.createChooser(
                                                            viewIntent,
                                                            "Open with",
                                                        )
                                                    )
                                                }
                                            }
                                            .onFailure {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                            context,
                                                            "Open failed: ${it.message}",
                                                            Toast.LENGTH_LONG,
                                                        )
                                                        .show()
                                                }
                                            }
                                    }
                                },
                                onDeleteFile = { entry -> pendingDeleteEntry = entry },
                                onDownloadFile = { entry ->
                                    val tabId = activeTabId ?: return@FileBrowserScreen
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { vm.readFile(tabId, entry, 16 * 1024 * 1024) }
                                            .onSuccess { bytes ->
                                                pendingDownloadBytes = bytes
                                                pendingDownloadName = entry.name
                                                withContext(Dispatchers.Main) {
                                                    downloadLauncher.launch(entry.name)
                                                }
                                            }
                                            .onFailure {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                            context,
                                                            "Download failed: ${it.message}",
                                                            Toast.LENGTH_LONG,
                                                        )
                                                        .show()
                                                }
                                            }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            pendingDeleteEntry?.let { entry ->
                                ChuDialog(
                                    title = "Delete ${entry.name}?",
                                    confirmLabel = "Delete",
                                    dismissLabel = "Cancel",
                                    onConfirm = {
                                        val tabId = activeTabId ?: run {
                                            pendingDeleteEntry = null
                                            return@ChuDialog
                                        }
                                        pendingDeleteEntry = null
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { vm.deleteFile(tabId, entry) }
                                                .onSuccess {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                context,
                                                                "Deleted ${entry.name}",
                                                                Toast.LENGTH_SHORT,
                                                            )
                                                            .show()
                                                    }
                                                }
                                                .onFailure {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                context,
                                                                "Delete failed: ${it.message}",
                                                                Toast.LENGTH_LONG,
                                                            )
                                                            .show()
                                                    }
                                                }
                                        }
                                    },
                                    onDismiss = { pendingDeleteEntry = null },
                                ) {
                                    ChuText(
                                        "This action cannot be undone.",
                                        style = typography.body,
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                TerminalCanvas(
                                    snapshot = snapshot,
                                    fontSizeSp = terminalFontSizeSp,
                                    cursorColor =
                                        ghosttyTheme?.cursorColor
                                            ?: Color.White.copy(alpha = 0.28f),
                                    cursorTextColor = ghosttyTheme?.cursorText,
                                    selectionBackgroundColor =
                                        ghosttyTheme?.selectionBackground
                                            ?: colors.accent.copy(alpha = 0.45f),
                                    selectionForegroundColor =
                                        ghosttyTheme?.selectionForeground ?: colors.onAccent,
                                    selectionResetKey = selectionResetKey,
                                    modifier = Modifier.fillMaxSize(),
                                    onResize = vm::onCanvasSizeChanged,
                                    onTap = requestInputFocus,
                                    onPrimaryClick = vm::onPrimaryMouseClick,
                                    onScroll = vm::onScroll,
                                    onZoom = { zoomFactor ->
                                        terminalFontSizeSp =
                                            (terminalFontSizeSp * zoomFactor).coerceAtLeast(0.1f)
                                    },
                                    onSelectionChanged = { active, text, anchorX, anchorY ->
                                        hasSelectionActive = active
                                        selectedText = text
                                        selectionAnchorOffset = Offset(anchorX, anchorY)
                                    },
                                )

                                Row(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isReconnecting) {
                                        ChuText(
                                            text =
                                                "Reconnecting${sessionState.reconnectAttempt.takeIf { it > 0 }?.let { " ($it)" } ?: ""}",
                                            style = typography.labelSmall,
                                            color = colors.error,
                                        )
                                    }
                                    if (pwdText != null) {
                                        ChuText(
                                            text = pwdText,
                                            style = typography.labelSmall,
                                            color = colors.textPrimary.copy(alpha = 0.7f),
                                        )
                                    }
                                }

                                if (hasSelectionActive) {
                                    val menuOffsetX =
                                        with(density) { selectionAnchorOffset.x.toDp() }
                                    val menuOffsetY =
                                        with(density) {
                                            (selectionAnchorOffset.y - 44f)
                                                .toDp()
                                                .coerceAtLeast(0.dp)
                                        }
                                    Row(
                                        modifier =
                                            Modifier.offset(x = menuOffsetX, y = menuOffsetY)
                                                .background(colors.background)
                                                .border(1.dp, colors.border)
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (!selectedText.isNullOrEmpty()) {
                                            ChuButton(
                                                onClick = ::copySelection,
                                                variant = ChuButtonVariant.Ghost,
                                                bracketed = true,
                                                borderColor = colors.textMuted,
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = 12.dp,
                                                        vertical = 6.dp,
                                                    ),
                                            ) {
                                                ChuText(
                                                    "copy",
                                                    style = typography.label,
                                                    color = colors.textMuted,
                                                )
                                            }
                                        }
                                        if (hasClipboardText) {
                                            ChuButton(
                                                onClick = { pasteClipboard() },
                                                variant = ChuButtonVariant.Ghost,
                                                bracketed = true,
                                                borderColor = colors.textMuted,
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = 12.dp,
                                                        vertical = 6.dp,
                                                    ),
                                            ) {
                                                ChuText(
                                                    "paste",
                                                    style = typography.label,
                                                    color = colors.textMuted,
                                                )
                                            }
                                        }
                                    }
                                }

                                AndroidView(
                                    modifier =
                                        Modifier.align(Alignment.BottomStart).size(1.dp).alpha(0f),
                                    factory = { viewContext ->
                                        TerminalInputView(viewContext)
                                            .apply {
                                                onTerminalText = { text ->
                                                    if (!chuchuKeys.handleText(text)) {
                                                        vm.dispatchTextWithModifierState(
                                                            text,
                                                            modifierState,
                                                        )
                                                        resetModifiers()
                                                    }
                                                }
                                                onTerminalKey = { key, codepoint, mods, action ->
                                                    var shouldForwardToTerminal = true
                                                    if (showTabSheet && tabs.isNotEmpty()) {
                                                        var consumedByTabSwitcher = true
                                                        val isPress =
                                                            action == GhosttyKeyAction.Press
                                                        if (isPress && chuchuKeys.isPrefixActive) {
                                                            when (
                                                                codepoint.toChar().lowercaseChar()
                                                            ) {
                                                                'n' -> {
                                                                    vm.duplicateActiveTab()
                                                                    vm.selectConnectionTab(
                                                                        ConnectionTab.Terminal
                                                                    )
                                                                    showTabSheet = false
                                                                }
                                                                't' -> {
                                                                    showTabSheet = true
                                                                }
                                                                else -> {}
                                                            }
                                                            chuchuKeys.reset()
                                                            shouldForwardToTerminal = false
                                                            consumedByTabSwitcher = true
                                                        }
                                                        if (isPress) {
                                                            when (key) {
                                                                TerminalSpecialKey.Left.engineKey,
                                                                TerminalSpecialKey.Up.engineKey ->
                                                                    focusedTabIndex =
                                                                        (focusedTabIndex - 1).mod(
                                                                            tabs.size
                                                                        )

                                                                TerminalSpecialKey.Right.engineKey,
                                                                TerminalSpecialKey.Down.engineKey ->
                                                                    focusedTabIndex =
                                                                        (focusedTabIndex + 1).mod(
                                                                            tabs.size
                                                                        )

                                                                TerminalSpecialKey.Enter
                                                                    .engineKey -> {
                                                                    tabs
                                                                        .getOrNull(focusedTabIndex)
                                                                        ?.let {
                                                                            vm.selectTab(it.id)
                                                                            showTabSheet = false
                                                                        }
                                                                }

                                                                TerminalSpecialKey.Escape
                                                                    .engineKey -> {
                                                                    showTabSheet = false
                                                                }

                                                                else ->
                                                                    consumedByTabSwitcher = false
                                                            }
                                                        }
                                                        if (consumedByTabSwitcher) {
                                                            shouldForwardToTerminal = false
                                                        }
                                                    }
                                                    if (shouldForwardToTerminal) {
                                                        val mergedMods =
                                                            mods or modifierState.terminalMods()
                                                        vm.onHardwareKey(
                                                            key,
                                                            codepoint,
                                                            mergedMods,
                                                            action,
                                                        )
                                                        if (modifierState.hasActiveModifiers()) {
                                                            resetModifiers()
                                                        }
                                                    }
                                                }
                                                setOnFocusChangeListener { _, hasFocus ->
                                                    vm.onFocusChanged(hasFocus)
                                                    if (hasFocus) {
                                                        showKeyboard(inputMethodManager)
                                                    }
                                                }
                                            }
                                            .also { view -> inputViewRef.value = view }
                                    },
                                    update = { view ->
                                        if (inputViewRef.value == null) {
                                            inputViewRef.value = view
                                        }
                                    },
                                )

                                if (currentTerminalCustomKeyGroups.isNotEmpty()) {
                                    TerminalCustomActionsFab(
                                        groups = currentTerminalCustomKeyGroups,
                                        onActionClick = { action ->
                                            val decoded = decodeCustomActionValue(action.payload)
                                            val rawText =
                                                decoded.text +
                                                    if (
                                                        CustomActionModifier.Enter in
                                                            decoded.modifiers
                                                    )
                                                        "\n"
                                                    else ""
                                            val actionModifierState =
                                                modifierStateForCustomAction(decoded.modifiers)
                                            vm.dispatchTextWithModifierState(
                                                rawText,
                                                actionModifierState,
                                            )
                                            resetModifiers()
                                            requestInputFocus()
                                        },
                                        modifier =
                                            Modifier.align(Alignment.BottomEnd)
                                                .padding(end = 14.dp, bottom = 12.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        if (selectedTab == ConnectionTab.Terminal) {
                            AnimatedVisibility(
                                visible = chuchuKeys.isPrefixActive,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .background(colors.surface)
                                            .border(1.dp, colors.border)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ChuText(
                                        "⌘ key",
                                        style = typography.label,
                                        color = colors.accent,
                                    )
                                    chuchuKeys.hints().forEach { hint ->
                                        ChuText(
                                            "${hint.key}: ${hint.description}",
                                            style = typography.labelSmall,
                                            color = colors.textSecondary,
                                        )
                                    }
                                }
                            }
                            KeyboardAccessoryBar(
                                items = accessoryLayout,
                                modifierState = modifierState,
                                onAction = ::dispatchAccessoryAction,
                                onSettings = onOpenSettings,
                                onChuchuKey = {
                                    chuchuKeys.togglePrefix()
                                    requestInputFocus()
                                },
                                chuchuKeyActive = chuchuKeys.isPrefixActive,
                                onOpenFiles = { vm.selectConnectionTab(ConnectionTab.Files) },
                                useSingleRow = useSingleRowAccessoryBar,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }

                val uploadProgress = fileBrowserState.uploadProgress
                if (uploadProgress != null) {
                    UploadProgressDialog(progress = uploadProgress)
                }
                if (showTabSheet) {
                    val paletteAccessoryAction: (AccessoryAction) -> Unit = { action ->
                        if (
                            !(action is AccessoryAction.SendText &&
                                chuchuKeys.handleText(action.text))
                        ) {
                            if (chuchuKeys.isPrefixActive) {
                                chuchuKeys.reset()
                            }
                            val result =
                                TerminalAccessoryDispatcher.dispatch(action, ModifierState())
                            when (result.specialKey) {
                                TerminalSpecialKey.Left,
                                TerminalSpecialKey.Up -> {
                                    if (tabs.isNotEmpty())
                                        focusedTabIndex = (focusedTabIndex - 1).mod(tabs.size)
                                }
                                TerminalSpecialKey.Right,
                                TerminalSpecialKey.Down -> {
                                    if (tabs.isNotEmpty())
                                        focusedTabIndex = (focusedTabIndex + 1).mod(tabs.size)
                                }
                                TerminalSpecialKey.Enter -> {
                                    tabs.getOrNull(focusedTabIndex)?.let {
                                        vm.selectTab(it.id)
                                        showTabSheet = false
                                    }
                                }
                                TerminalSpecialKey.Escape -> {
                                    showTabSheet = false
                                }
                                else -> {
                                    result.text?.let { text ->
                                        if (!chuchuKeys.handleText(text)) {
                                            vm.onTextInput(text)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    CommandPalette(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        focusedTabIndex = focusedTabIndex,
                        onFocusedTabIndexChange = { focusedTabIndex = it },
                        accessoryItems = accessoryLayout,
                        onAccessoryAction = paletteAccessoryAction,
                        onChuchuKey = { chuchuKeys.togglePrefix() },
                        chuchuKeyActive = chuchuKeys.isPrefixActive,
                        onOpenFiles = {
                            vm.selectConnectionTab(ConnectionTab.Files)
                            showTabSheet = false
                        },
                        onOpenSettings = onOpenSettings,
                        useSingleRowAccessoryBar = useSingleRowAccessoryBar,
                        onSelectTab = {
                            vm.selectTab(it)
                            showTabSheet = false
                        },
                        onCloseTab = vm::closeTab,
                        onAddTab = {
                            vm.duplicateActiveTab()
                            showTabSheet = false
                        },
                        onDismiss = { showTabSheet = false },
                    )
                }
            } else {
                Column(
                    modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val message =
                        if (isReconnecting) {
                            "Reconnecting to ${activeTabForHost?.spec?.host ?: ""}..."
                        } else {
                            "Preparing terminal..."
                        }
                    ChuText(message, style = typography.body)
                }
            }
        }
    }
}

@Composable
private fun UploadProgressDialog(progress: UploadProgress) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val barWidthDp = 240.dp
    val barHeightDp = 12.dp
    val filledWidth =
        if (progress.totalBytes > 0) {
            barWidthDp * progress.percent.coerceAtMost(100) / 100f
        } else {
            // Unknown total: show a thin sliver as indeterminate indicator
            barWidthDp * 0.15f
        }

    Box(
        modifier =
            Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.7f)).clickable(
                enabled = false
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier.background(colors.surfaceVariant)
                    .border(1.dp, colors.border)
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChuText(
                "\u2500\u2500\u25B2\u2500\u2500",
                style = typography.title,
                color = colors.accent,
            )

            ChuText(text = progress.fileName, style = typography.body, color = colors.textPrimary)

            if (progress.totalFiles > 1) {
                ChuText(
                    text = "file ${progress.fileIndex + 1} of ${progress.totalFiles}",
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }

            Box(
                modifier =
                    Modifier.width(barWidthDp)
                        .height(barHeightDp)
                        .background(colors.surface)
                        .border(1.dp, colors.border),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier.width(filledWidth).fillMaxHeight().background(colors.accent)
                )
            }

            ChuText(
                text =
                    if (progress.totalBytes > 0) {
                        val written = formatFileSize(progress.bytesWritten)
                        val total = formatFileSize(progress.totalBytes)
                        "$written / $total  ${progress.percent}%"
                    } else {
                        formatFileSize(progress.bytesWritten)
                    },
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

package com.jossephus.chuchu.ui.screens.Terminal

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.GhosttyKey
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.KeyboardAccessoryBar
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryDispatcher
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.terminal.TerminalCustomAction
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.terminal.CustomActionModifier
import com.jossephus.chuchu.ui.terminal.decodeCustomActionValue
import com.jossephus.chuchu.ui.terminal.modifierStateForCustomAction
import com.jossephus.chuchu.ui.terminal.toGhosttyKey
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.UploadProgress
import com.jossephus.chuchu.ui.screens.Files.FileBrowserScreen
import com.jossephus.chuchu.ui.screens.Files.formatFileSize
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import com.jossephus.chuchu.ui.theme.toRgbIntArray
import com.jossephus.chuchu.ui.theme.toTerminalPaletteBytes

private fun TerminalViewModel.dispatchTextWithModifierState(
    text: String,
    modifierState: ModifierState,
) {
    val mods = modifierState.terminalMods()
    for (char in text) {
        val ghosttyKey = when (char) {
            '\r', '\n' -> GhosttyKey.enter
            '\t' -> GhosttyKey.tab
            else -> char.toGhosttyKey()
        }
        if (ghosttyKey != null) {
            val codepoint = when (char) {
                '\r', '\n', '\t' -> 0
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
    val selectedGroup = remember(selectedGroupKey, groups) {
        groups.firstOrNull { it.keyLabel == selectedGroupKey }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
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
    val tabsForHost = remember(tabs, hostId) { tabs.filter { it.spec.hostId == hostId } }
    val activeTabForHost = remember(activeTab, hostId) {
        activeTab?.takeIf { it.spec.hostId == hostId }
    }
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
    val currentTheme by settingsRepo.themeName.collectAsStateWithLifecycle()
    val currentAccessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
    val useSingleRowAccessoryBar by settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
    val currentTerminalCustomKeyGroups by settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
    val accessoryLayout = remember(currentAccessoryLayoutIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
    }
    val ghosttyTheme = remember(context, currentTheme) {
        GhosttyThemeRegistry.getTheme(context, currentTheme)
    }
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
        val baseSpec = TabSpec(
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

    LaunchedEffect(tabsForHost.isNotEmpty(), hostId) {
        if (tabsForHost.isNotEmpty()) hasSeenTabsForHost = true
    }

    LaunchedEffect(hasSeenTabsForHost, tabsForHost.isEmpty()) {
        if (hasSeenTabsForHost && tabsForHost.isEmpty()) {
            onBack()
        }
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
        SessionStatus.Disconnected, SessionStatus.Error -> {
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

        SessionStatus.Connected, SessionStatus.Reconnecting -> {
            val isReconnecting = sessionState.status == SessionStatus.Reconnecting
            val snapshot = sessionState.snapshot
            if (snapshot != null) {
                Box(modifier = screenInsetsModifier.fillMaxSize()) {
                    LaunchedEffect(ghosttyTheme, colors, isDarkTheme) {
                        vm.onColorSchemeChanged(isDarkTheme)
                        vm.onDefaultColorsChanged(
                            fg = ghosttyTheme?.foreground?.toRgbIntArray() ?: colors.textPrimary.toRgbIntArray(),
                            bg = ghosttyTheme?.background?.toRgbIntArray() ?: colors.background.toRgbIntArray(),
                            cursor = ghosttyTheme?.cursorColor?.toRgbIntArray() ?: colors.accent.toRgbIntArray(),
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
                        inputViewRef.value?.let { view ->
                            view.showKeyboard(inputMethodManager)
                        }
                    }
                    val clipboard = remember {
                        context.getSystemService(ClipboardManager::class.java)
                    }
                    var hasClipboardText by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(clipboard) {
                        fun check() {
                            hasClipboardText = try {
                                clipboard?.hasPrimaryClip() == true &&
                                    clipboard!!.primaryClip?.getItemAt(0)?.text?.isNotEmpty() == true
                            } catch (_: SecurityException) { false }
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
                    val terminalPrefs = remember(context) {
                        context.getSharedPreferences("chuchu_terminal", Context.MODE_PRIVATE)
                    }
                    var modifierState by remember { mutableStateOf(ModifierState()) }
                    var terminalFontSizeSp by remember {
                        mutableStateOf(terminalPrefs.getFloat("terminal_font_size_sp", 14f).coerceAtLeast(0.1f))
                    }

                    LaunchedEffect(terminalFontSizeSp) {
                        terminalPrefs.edit().putFloat("terminal_font_size_sp", terminalFontSizeSp).apply()
                    }

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
                        val currentModifierState = modifierState
                        val result = TerminalAccessoryDispatcher.dispatch(action, currentModifierState)
                        modifierState = result.modifierState

                        if (result.suppressImeInput) {
                            inputViewRef.value?.armInputSuppression(action.toString())
                        }

                        result.specialKey?.let { key ->
                            vm.onSpecialKeyInput(key, currentModifierState.terminalMods())
                        }

                        result.text?.let { text ->
                            vm.onTextInput(text)
                        }

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

                    val importFileLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetMultipleContents(),
                    ) { uris: List<Uri> ->
                        if (uris.isEmpty()) return@rememberLauncherForActivityResult
                        scope.launch(Dispatchers.IO) {
                            var success = 0
                            var failed = 0
                            var lastError: String? = null
                            val total = uris.size
                            uris.forEachIndexed { index, uri ->
                                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                                } ?: uri.lastPathSegment ?: "uploaded_${index}"
                                val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else 0L
                                } ?: 0L
                                try {
                                    val stream = context.contentResolver.openInputStream(uri)
                                        ?: throw IllegalStateException("Cannot open file")
                                    stream.use { input ->
                                        vm.beginUpload(fileName)
                                        vm.setUploadProgress(UploadProgress(
                                            fileName = fileName,
                                            bytesWritten = 0,
                                            totalBytes = fileSize,
                                            fileIndex = index,
                                            totalFiles = total,
                                        ))
                                        val buffer = ByteArray(65536)
                                        var bytesWritten = 0L
                                        var lastProgressBytes = 0L
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            vm.writeUploadChunk(buffer.copyOf(read))
                                            bytesWritten += read
                                            if (bytesWritten - lastProgressBytes >= 262144 || bytesWritten == fileSize) {
                                                lastProgressBytes = bytesWritten
                                                vm.setUploadProgress(UploadProgress(
                                                    fileName = fileName,
                                                    bytesWritten = bytesWritten,
                                                    totalBytes = fileSize,
                                                    fileIndex = index,
                                                    totalFiles = total,
                                                ))
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
                                val msg = when {
                                    failed == 0 -> "Uploaded $success file(s)"
                                    lastError != null -> "Uploaded $success, $failed failed: $lastError"
                                    else -> "Uploaded $success, $failed failed"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        requestInputFocus()
                        vm.onFocusChanged(true)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(if (showTabSheet) 10.dp else 0.dp)
                            .imePadding(),
                    ) {
                    if (selectedTab == ConnectionTab.Files) {
                        FileBrowserScreen(
                            state = fileBrowserState,
                            onGoUp = vm::goUpDirectory,
                            onRefresh = vm::refreshFileBrowser,
                            onSelectSort = vm::selectFileSort,
                            onOpenPath = vm::openPath,
                            onBackToTerminal = { vm.selectConnectionTab(ConnectionTab.Terminal) },
                            onCopyPath = { path ->
                                clipboard?.setPrimaryClip(ClipData.newPlainText("path", path))
                                Toast.makeText(context, "Copied path", Toast.LENGTH_SHORT).show()
                            },
                            onImportFile = { importFileLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                    Box(
                        modifier = Modifier.weight(1f),
                    ) {
                        TerminalCanvas(
                            snapshot = snapshot,
                            fontSizeSp = terminalFontSizeSp,
                            cursorColor = ghosttyTheme?.cursorColor ?: Color.White.copy(alpha = 0.28f),
                            cursorTextColor = ghosttyTheme?.cursorText,
                            selectionBackgroundColor = ghosttyTheme?.selectionBackground ?: colors.accent.copy(alpha = 0.45f),
                            selectionForegroundColor = ghosttyTheme?.selectionForeground ?: colors.onAccent,
                            selectionResetKey = selectionResetKey,
                            modifier = Modifier.fillMaxSize(),
                            onResize = vm::onCanvasSizeChanged,
                            onTap = requestInputFocus,
                            onPrimaryClick = vm::onPrimaryMouseClick,
                            onScroll = vm::onScroll,
                            onZoom = { zoomFactor ->
                                terminalFontSizeSp = (terminalFontSizeSp * zoomFactor).coerceAtLeast(0.1f)
                            },
                            onSelectionChanged = { active, text, anchorX, anchorY ->
                                hasSelectionActive = active
                                selectedText = text
                                selectionAnchorOffset = Offset(anchorX, anchorY)
                            },
                        )

                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isReconnecting) {
                                ChuText(
                                    text = "Reconnecting${sessionState.reconnectAttempt.takeIf { it > 0 }?.let { " ($it)" } ?: ""}",
                                    style = typography.labelSmall,
                                    color = colors.error,
                                )
                            }
                            if (pwdText != null) {
                                ChuText(text = pwdText, style = typography.labelSmall, color = colors.textPrimary.copy(alpha = 0.7f))
                            }
                            ChuButton(
                                onClick = { showTabSheet = true },
                                variant = ChuButtonVariant.Outlined,
                                bracketed = true,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                ChuText("+", style = typography.label, color = colors.accent)
                            }
                        }

                        if (hasSelectionActive) {
                            val menuOffsetX = with(density) { selectionAnchorOffset.x.toDp() }
                            val menuOffsetY = with(density) { (selectionAnchorOffset.y - 44f).toDp().coerceAtLeast(0.dp) }
                            Row(
                                modifier = Modifier
                                    .offset(x = menuOffsetX, y = menuOffsetY)
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
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        ChuText("copy", style = typography.label, color = colors.textMuted)
                                    }
                                }
                                if (hasClipboardText) {
                                    ChuButton(
                                        onClick = { pasteClipboard() },
                                        variant = ChuButtonVariant.Ghost,
                                        bracketed = true,
                                        borderColor = colors.textMuted,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        ChuText("paste", style = typography.label, color = colors.textMuted)
                                    }
                                }
                            }
                        }

                        AndroidView(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .size(1.dp)
                                .alpha(0f),
                            factory = { viewContext ->
                                TerminalInputView(viewContext).apply {
                                    onTerminalText = { text ->
                                        vm.dispatchTextWithModifierState(text, modifierState)
                                        resetModifiers()
                                    }
                                    onTerminalKey = { key, codepoint, mods, action ->
                                        val mergedMods = mods or modifierState.terminalMods()
                                        vm.onHardwareKey(key, codepoint, mergedMods, action)
                                        if (modifierState.hasActiveModifiers()) {
                                            resetModifiers()
                                        }
                                    }
                                    setOnFocusChangeListener { _, hasFocus ->
                                        vm.onFocusChanged(hasFocus)
                                        if (hasFocus) {
                                            showKeyboard(inputMethodManager)
                                        }
                                    }
                                }.also { view ->
                                    inputViewRef.value = view
                                }
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
                                    val rawText = decoded.text + if (CustomActionModifier.Enter in decoded.modifiers) "\n" else ""
                                    val actionModifierState = modifierStateForCustomAction(decoded.modifiers)
                                    vm.dispatchTextWithModifierState(rawText, actionModifierState)
                                    resetModifiers()
                                    requestInputFocus()
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 14.dp, bottom = 12.dp),
                            )
                        }
                    }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    if (selectedTab == ConnectionTab.Terminal) {
                        KeyboardAccessoryBar(
                            items = accessoryLayout,
                            modifierState = modifierState,
                            onAction = ::dispatchAccessoryAction,
                            onSettings = onOpenSettings,
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
                    TabSwitcherOverlay(
                        tabs = tabsForHost,
                        activeTabId = activeTabId,
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
                    val message = if (isReconnecting) {
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
private fun TabSwitcherOverlay(
    tabs: List<TabSession>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val labeledTabs = remember(tabs) {
        tabs.map { tab -> tab to tabAlias(tab) }
    }
    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 5 }, exit = fadeOut() + slideOutVertically { it / 4 }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .clickable { onDismiss() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .clickable(enabled = false) {},
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ChuText("tabs", style = typography.title)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChuButton(onClick = onDismiss, variant = ChuButtonVariant.Outlined, bracketed = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            ChuText("done", style = typography.label, color = colors.textMuted)
                        }
                        ChuButton(onClick = onAddTab, variant = ChuButtonVariant.Outlined, bracketed = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            ChuText("+", style = typography.label, color = colors.accent)
                        }
                    }
                }

                val rows = remember(labeledTabs) { labeledTabs.chunked(2) }
                androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows.size) { rowIndex ->
                        val row = rows[rowIndex]
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { (tab, label) ->
                                val isActive = tab.id == activeTabId
                                val state = tab.sessionState.value
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (isActive) colors.surface else colors.surfaceVariant)
                                        .border(
                                            2.dp,
                                            if (isActive) colors.accent else colors.border.copy(alpha = 0.55f),
                                        )
                                        .clickable { onSelectTab(tab.id) },
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ChuText(label, style = typography.label, color = colors.textPrimary)
                                        if (tabs.size > 1) {
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clickable { onCloseTab(tab.id) },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                ChuText("x", style = typography.label, color = colors.textMuted)
                                            }
                                        }
                                    }
                                    val previewBackground = if (isActive) {
                                        colors.background.copy(alpha = 0.9f)
                                    } else {
                                        colors.background.copy(alpha = 0.75f)
                                    }
                                    val snapshot = state.snapshot
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .background(previewBackground)
                                            .clickable { onSelectTab(tab.id) },
                                    ) {
                                        if (snapshot != null) {
                                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                                val widthDp = maxWidth.value.coerceAtLeast(1f)
                                                val heightDp = maxHeight.value.coerceAtLeast(1f)
                                                val cols = snapshot.cols.coerceAtLeast(1)
                                                val rows = snapshot.rows.coerceAtLeast(1)
                                                val charAspect = 2.05f
                                                val byWidth = widthDp / cols.toFloat()
                                                val byHeight = (heightDp / rows.toFloat()) / charAspect
                                                val previewFontSizeSp = minOf(byWidth, byHeight).coerceIn(2.8f, 6.5f)
                                                TerminalCanvas(
                                                    snapshot = snapshot,
                                                    fontSizeSp = previewFontSizeSp,
                                                    cursorColor = Color.Transparent,
                                                    selectionBackgroundColor = Color.Transparent,
                                                    onTap = { onSelectTab(tab.id) },
                                                    onPrimaryClick = { _, _ -> onSelectTab(tab.id) },
                                                    onScroll = {},
                                                    onZoom = {},
                                                    onSelectionChanged = { _, _, _, _ -> },
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RectangleShape),
                                                )
                                            }
                                        } else {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                ChuText(tab.spec.notificationLabel, style = typography.labelSmall, color = colors.textMuted)
                                                ChuText("status: ${state.status}", style = typography.bodySmall, color = colors.textSecondary)
                                                state.title?.let { ChuText(it, style = typography.bodySmall, color = colors.textPrimary) }
                                            }
                                        }
                                    }
                                }
                            }
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tabAlias(tab: TabSession): String {
    val adjectives = listOf(
        "amber", "brisk", "cedar", "delta", "ember", "frost", "golden", "hazel",
        "indigo", "jade", "kilo", "lunar", "mango", "nova", "onyx", "pluto",
    )
    val nouns = listOf(
        "otter", "falcon", "pine", "river", "comet", "harbor", "meadow", "quartz",
        "signal", "orbit", "anchor", "summit", "thunder", "voyager", "willow", "zenith",
    )
    val seed = tab.id.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    val adjective = adjectives[seed % adjectives.size]
    val noun = nouns[(seed / adjectives.size) % nouns.size]
    return "$adjective-$noun"
}

@Composable
private fun UploadProgressDialog(progress: UploadProgress) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val barWidthDp = 240.dp
    val barHeightDp = 12.dp
    val filledWidth = if (progress.totalBytes > 0) {
        barWidthDp * progress.percent.coerceAtMost(100) / 100f
    } else {
        // Unknown total: show a thin sliver as indeterminate indicator
        barWidthDp * 0.15f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.7f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(colors.surfaceVariant)
                .border(1.dp, colors.border)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChuText("\u2500\u2500\u25B2\u2500\u2500", style = typography.title, color = colors.accent)

            ChuText(
                text = progress.fileName,
                style = typography.body,
                color = colors.textPrimary,
            )

            if (progress.totalFiles > 1) {
                ChuText(
                    text = "file ${progress.fileIndex + 1} of ${progress.totalFiles}",
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }

            Box(
                modifier = Modifier
                    .width(barWidthDp)
                    .height(barHeightDp)
                    .background(colors.surface)
                    .border(1.dp, colors.border),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .width(filledWidth)
                        .fillMaxHeight()
                        .background(colors.accent),
                )
            }

            ChuText(
                text = if (progress.totalBytes > 0) {
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

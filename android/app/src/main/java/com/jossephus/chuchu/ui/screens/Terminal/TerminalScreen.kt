package com.jossephus.chuchu.ui.screens.Terminal

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.service.terminal.SessionStatus
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
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.ui.screens.Settings.SettingsSheet
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
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ChuText(action.label, style = typography.label)
                        }
                    }
                    ChuButton(
                        onClick = { selectedGroupKey = null },
                        variant = ChuButtonVariant.Ghost,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        ChuText("<", style = typography.label, color = colors.textMuted)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable {
                    expanded = !expanded
                    if (!expanded) {
                        selectedGroupKey = null
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChuText(
                if (expanded) "x" else "+",
                style = typography.label,
                color = colors.onAccent,
            )
        }
    }
}

@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    hostId: Long?,
    modifier: Modifier = Modifier,
) {
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val connectForm by vm.connectForm.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val screenInsetsModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    var lastSessionStatus by remember { mutableStateOf<SessionStatus?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val settingsRepo = remember(context) { SettingsRepository.getInstance(context) }
    val currentTheme by settingsRepo.themeName.collectAsStateWithLifecycle()
    val currentAccessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
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

    LaunchedEffect(hostId) {
        vm.setSelectedHostId(hostId)
        if (hostId == null) return@LaunchedEffect
        showPassphrasePrompt = false
        passphraseInput = ""
        val db = AppDatabase.getInstance(context)
        val host = HostRepository(db.hostProfileDao()).getById(hostId) ?: return@LaunchedEffect
        val key = host.keyId?.let { SshKeyRepository(db.sshKeyDao()).getById(it) }
        vm.updateHost(host.host)
        vm.updatePort(host.port.toString())
        vm.updateUsername(host.username)
        vm.updatePassword(host.password)
        vm.updateTransport(host.transport)
        vm.updateAuthMethod(host.authMethod)
        if (key != null) {
            vm.updatePrivateKey(key.privateKeyPem, key.publicKeyOpenSsh)
        }
        vm.refreshTailscaleStatus()
        if (host.authMethod == AuthMethod.KeyWithPassphrase && key != null) {
            showPassphrasePrompt = true
        } else {
            vm.updateKeyPassphrase("")
            vm.connect()
        }
    }

    if (showPassphrasePrompt) {
        ChuDialog(
            title = "Key passphrase",
            confirmLabel = "Connect",
            onConfirm = {
                vm.updateKeyPassphrase(passphraseInput)
                showPassphrasePrompt = false
                passphraseInput = ""
                vm.connect()
            },
            onDismiss = {
                showPassphrasePrompt = false
                passphraseInput = ""
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
        when (sessionState.status) {
            SessionStatus.Connected -> Unit
            SessionStatus.Error -> {
                if (previous != SessionStatus.Error) {
                    val message = sessionState.error ?: "Connection failed"
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            else -> Unit
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
                        onClick = vm::connect,
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
                ChuText("Connecting to ${connectForm.host}...", style = typography.body)
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

                    LaunchedEffect(Unit) {
                        requestInputFocus()
                        vm.onFocusChanged(true)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding(),
                    ) {
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
                        }

                        if (hasSelectionActive) {
                            val menuOffsetX = with(density) { selectionAnchorOffset.x.toDp() }
                            val menuOffsetY = with(density) { (selectionAnchorOffset.y - 44f).toDp().coerceAtLeast(0.dp) }
                            Row(
                                modifier = Modifier
                                    .offset(x = menuOffsetX, y = menuOffsetY)
                                    .shadow(4.dp, shape = RoundedCornerShape(8.dp))
                                    .background(colors.background, shape = RoundedCornerShape(8.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!selectedText.isNullOrEmpty()) {
                                    ChuButton(
                                        onClick = ::copySelection,
                                        variant = ChuButtonVariant.Ghost,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        ChuText("Copy", style = typography.label, color = colors.textPrimary)
                                    }
                                }
                                if (hasClipboardText) {
                                    ChuButton(
                                        onClick = { pasteClipboard() },
                                        variant = ChuButtonVariant.Ghost,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        ChuText("Paste", style = typography.label, color = colors.textPrimary)
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

                    Spacer(modifier = Modifier.height(6.dp))
                    KeyboardAccessoryBar(
                        items = accessoryLayout,
                        modifierState = modifierState,
                        onAction = ::dispatchAccessoryAction,
                        onSettings = { showSettings = true },
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }

                if (showSettings) {
                    SettingsSheet(
                        visible = true,
                        currentTheme = currentTheme,
                        currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                        currentTerminalCustomKeyGroups = currentTerminalCustomKeyGroups,
                        onThemeSelected = { settingsRepo.setTheme(it) },
                        onAccessoryLayoutChanged = { settingsRepo.setAccessoryLayoutIds(it) },
                        onTerminalCustomActionsChanged = { settingsRepo.setTerminalCustomKeyGroups(it) },
                        onDismiss = { showSettings = false },
                    )
                }
                }
            } else {
                Column(
                    modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val message = if (isReconnecting) {
                        "Reconnecting to ${connectForm.host}..."
                    } else {
                        "Preparing terminal..."
                    }
                    ChuText(message, style = typography.body)
                }
            }
        }
    }
}

package com.jossephus.chuchu.ui.screens.Terminal

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.KeyboardAccessoryBar
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryDispatcher
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.terminal.toGhosttyKey
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

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
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val accessoryLayout = remember { TerminalAccessoryLayoutStore.defaultLayout() }
    val screenInsetsModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    var lastSessionStatus by remember { mutableStateOf<SessionStatus?>(null) }

    LaunchedEffect(hostId) {
        if (hostId == null) return@LaunchedEffect
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
        vm.updateKeyPassphrase(host.keyPassphrase)
        vm.refreshTailscaleStatus()
        vm.connect()
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

        SessionStatus.Connected -> {
            val snapshot = sessionState.snapshot
            if (snapshot != null) {
                LaunchedEffect(colors) {
                    vm.onColorSchemeChanged(true)
                    vm.onDefaultColorsChanged(
                        fg = intArrayOf(
                            (colors.textPrimary.red * 255).toInt(),
                            (colors.textPrimary.green * 255).toInt(),
                            (colors.textPrimary.blue * 255).toInt(),
                        ),
                        bg = intArrayOf(
                            (colors.background.red * 255).toInt(),
                            (colors.background.green * 255).toInt(),
                            (colors.background.blue * 255).toInt(),
                        ),
                        cursor = intArrayOf(
                            (colors.accent.red * 255).toInt(),
                            (colors.accent.green * 255).toInt(),
                            (colors.accent.blue * 255).toInt(),
                        ),
                        palette = null,
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

                LaunchedEffect(Unit) {
                    requestInputFocus()
                    vm.onFocusChanged(true)
                }
                Column(
                    modifier = screenInsetsModifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                    ) {
                        TerminalCanvas(
                            snapshot = snapshot,
                            fontSizeSp = terminalFontSizeSp,
                            modifier = Modifier.fillMaxSize(),
                            onResize = vm::onCanvasSizeChanged,
                            onTap = requestInputFocus,
                            onScroll = vm::onScroll,
                            onZoom = { zoomFactor ->
                                terminalFontSizeSp = (terminalFontSizeSp * zoomFactor).coerceAtLeast(0.1f)
                            },
                        )

                        if (titleText != null || pwdText != null) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                if (titleText != null) {
                                    // ChuText(text = titleText, style = typography.label, color = colors.textPrimary)
                                }
                                if (pwdText != null) {
                                    ChuText(text = pwdText, style = typography.labelSmall, color = colors.textPrimary.copy(alpha = 0.7f))
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
                                        if (modifierState.cmd && !modifierState.alt) {
                                            val mods = modifierState.terminalMods()
                                            for (char in text) {
                                                val ghosttyKey = char.toGhosttyKey()
                                                if (ghosttyKey != null) {
                                                    vm.onHardwareKey(ghosttyKey, char.code, mods, GhosttyKeyAction.Press)
                                                    vm.onHardwareKey(ghosttyKey, char.code, mods, GhosttyKeyAction.Release)
                                                }
                                            }
                                        } else {
                                            vm.onTextInput(modifierState.applyToText(text))
                                        }
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
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    KeyboardAccessoryBar(
                        items = accessoryLayout,
                        modifierState = modifierState,
                        onAction = ::dispatchAccessoryAction,
                        nativeVersion = sessionState.nativeVersion,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

package com.example.chuchu.ui.terminal

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chuchu.data.db.AppDatabase
import com.example.chuchu.data.repository.HostRepository
import com.example.chuchu.model.Transport
import com.example.chuchu.service.terminal.SessionStatus
import com.example.chuchu.ui.components.ChuButton
import com.example.chuchu.ui.components.ChuButtonVariant
import com.example.chuchu.ui.components.ChuDialog
import com.example.chuchu.ui.components.ChuSegmentedControl
import com.example.chuchu.ui.components.ChuText
import com.example.chuchu.ui.components.ChuTextField
import com.example.chuchu.ui.theme.ChuColors
import com.example.chuchu.ui.theme.ChuTypography

@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    hostId: Long?,
    modifier: Modifier = Modifier,
) {
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val connectForm by vm.connectForm.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val tailscaleActive by vm.tailscaleActive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val screenInsetsModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    var lastSessionStatus by remember { mutableStateOf<SessionStatus?>(null) }

    LaunchedEffect(hostId) {
        if (hostId == null) return@LaunchedEffect
        val db = AppDatabase.getInstance(context)
        val host = HostRepository(db.hostProfileDao()).getById(hostId) ?: return@LaunchedEffect
        vm.updateHost(host.host)
        vm.updatePort(host.port.toString())
        vm.updateUsername(host.username)
        vm.updatePassword(host.password)
        vm.updateTransport(host.transport)
        vm.updateAuthMethod(host.authMethod)
        vm.updateKeyPath(host.keyPath)
        vm.updateKeyPassphrase(host.keyPassphrase)
        vm.refreshTailscaleStatus()
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
            ConnectScreen(
                host = connectForm.host,
                port = connectForm.port,
                username = connectForm.username,
                password = connectForm.password,
                transport = connectForm.transport,
                tailscaleActive = tailscaleActive,
                error = sessionState.error,
                onHostChange = vm::updateHost,
                onPortChange = vm::updatePort,
                onUsernameChange = vm::updateUsername,
                onPasswordChange = vm::updatePassword,
                onTransportChange = vm::updateTransport,
                onConnect = vm::connect,
                modifier = screenInsetsModifier,
            )
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
                val accessoryBarReservedHeight = 44.dp
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
                var ctrlEnabled by remember { mutableStateOf(false) }
                var cmdEnabled by remember { mutableStateOf(false) }
                var altEnabled by remember { mutableStateOf(false) }
                var shiftEnabled by remember { mutableStateOf(false) }
                var terminalFontSizeSp by remember {
                    mutableStateOf(terminalPrefs.getFloat("terminal_font_size_sp", 14f).coerceAtLeast(0.1f))
                }

                LaunchedEffect(terminalFontSizeSp) {
                    terminalPrefs.edit().putFloat("terminal_font_size_sp", terminalFontSizeSp).apply()
                }

                fun modifierParam(): Int {
                    val metaEnabled = altEnabled || cmdEnabled
                    return 1 +
                        (if (shiftEnabled) 1 else 0) +
                        (if (metaEnabled) 2 else 0) +
                        (if (ctrlEnabled) 4 else 0)
                }

                fun sendEscape(sequence: String) {
                    vm.onTextInput(sequence)
                }

                fun sendVirtualKey(key: VirtualKey) {
                    val mod = modifierParam()
                    when (key) {
                        VirtualKey.Escape -> sendEscape("\u001b")
                        VirtualKey.Tab -> {
                            if (shiftEnabled) sendEscape("\u001b[Z") else sendEscape("\t")
                        }

                        VirtualKey.Up -> sendEscape(if (mod == 1) "\u001b[A" else "\u001b[1;${mod}A")
                        VirtualKey.Down -> sendEscape(if (mod == 1) "\u001b[B" else "\u001b[1;${mod}B")
                        VirtualKey.Right -> sendEscape(if (mod == 1) "\u001b[C" else "\u001b[1;${mod}C")
                        VirtualKey.Left -> sendEscape(if (mod == 1) "\u001b[D" else "\u001b[1;${mod}D")
                        VirtualKey.Home -> sendEscape(if (mod == 1) "\u001b[H" else "\u001b[1;${mod}H")
                        VirtualKey.End -> sendEscape(if (mod == 1) "\u001b[F" else "\u001b[1;${mod}F")
                        VirtualKey.PageUp -> sendEscape(if (mod == 1) "\u001b[5~" else "\u001b[5;${mod}~")
                        VirtualKey.PageDown -> sendEscape(if (mod == 1) "\u001b[6~" else "\u001b[6;${mod}~")
                        VirtualKey.Insert -> sendEscape(if (mod == 1) "\u001b[2~" else "\u001b[2;${mod}~")
                        VirtualKey.Delete -> sendEscape(if (mod == 1) "\u001b[3~" else "\u001b[3;${mod}~")
                        VirtualKey.F1 -> sendEscape(if (mod == 1) "\u001bOP" else "\u001b[1;${mod}P")
                        VirtualKey.F2 -> sendEscape(if (mod == 1) "\u001bOQ" else "\u001b[1;${mod}Q")
                        VirtualKey.F3 -> sendEscape(if (mod == 1) "\u001bOR" else "\u001b[1;${mod}R")
                        VirtualKey.F4 -> sendEscape(if (mod == 1) "\u001bOS" else "\u001b[1;${mod}S")
                        VirtualKey.F5 -> sendEscape(if (mod == 1) "\u001b[15~" else "\u001b[15;${mod}~")
                        VirtualKey.F6 -> sendEscape(if (mod == 1) "\u001b[17~" else "\u001b[17;${mod}~")
                        VirtualKey.F7 -> sendEscape(if (mod == 1) "\u001b[18~" else "\u001b[18;${mod}~")
                        VirtualKey.F8 -> sendEscape(if (mod == 1) "\u001b[19~" else "\u001b[19;${mod}~")
                        VirtualKey.F9 -> sendEscape(if (mod == 1) "\u001b[20~" else "\u001b[20;${mod}~")
                        VirtualKey.F10 -> sendEscape(if (mod == 1) "\u001b[21~" else "\u001b[21;${mod}~")
                        VirtualKey.F11 -> sendEscape(if (mod == 1) "\u001b[23~" else "\u001b[23;${mod}~")
                        VirtualKey.F12 -> sendEscape(if (mod == 1) "\u001b[24~" else "\u001b[24;${mod}~")
                    }
                }

                fun applyModifiers(text: String): String {
                    if (!ctrlEnabled && !cmdEnabled) return text

                    val ctrlApplied = if (ctrlEnabled) {
                        val sb = StringBuilder(text.length)
                        text.forEach { ch ->
                            val code = when (ch) {
                                '@' -> 0
                                '[' -> 27
                                '\\' -> 28
                                ']' -> 29
                                '^' -> 30
                                '_' -> 31
                                in 'a'..'z' -> ch.code - 96
                                in 'A'..'Z' -> ch.code - 64
                                else -> ch.code
                            }
                            sb.append(code.toChar())
                        }
                        sb.toString()
                    } else {
                        text
                    }

                    if (!cmdEnabled) return ctrlApplied

                    val meta = StringBuilder(ctrlApplied.length * 2)
                    ctrlApplied.forEach { ch ->
                        meta.append('\u001b')
                        meta.append(ch)
                    }
                    return meta.toString()
                }

                fun pasteClipboard() {
                    val clip = clipboard?.primaryClip ?: return
                    if (clip.itemCount == 0) return
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    if (text.isNotEmpty()) {
                        vm.onPasteText(applyModifiers(text))
                    }
                }

                LaunchedEffect(Unit) {
                    requestInputFocus()
                    vm.onFocusChanged(true)
                }
                Box(
                    modifier = screenInsetsModifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    TerminalCanvas(
                        snapshot = snapshot,
                        fontSizeSp = terminalFontSizeSp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = accessoryBarReservedHeight),
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
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                        ) {
                            if (titleText != null) {
                                ChuText(text = titleText, style = typography.label, color = colors.textPrimary)
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
                                    vm.onTextInput(applyModifiers(text))
                                }
                                setOnFocusChangeListener { v, hasFocus ->
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

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.ime),
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        KeyboardAccessoryBar(
                            cmdEnabled = cmdEnabled,
                            ctrlEnabled = ctrlEnabled,
                            altEnabled = altEnabled,
                            shiftEnabled = shiftEnabled,
                            onToggleCmd = { cmdEnabled = !cmdEnabled },
                            onToggleCtrl = { ctrlEnabled = !ctrlEnabled },
                            onToggleAlt = { altEnabled = !altEnabled },
                            onToggleShift = { shiftEnabled = !shiftEnabled },
                            onSendKey = ::sendVirtualKey,
                            onPaste = ::pasteClipboard,
                            nativeVersion = sessionState.nativeVersion,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(
    host: String,
    port: String,
    username: String,
    password: String,
    transport: Transport,
    tailscaleActive: Boolean,
    error: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTransportChange: (Transport) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ChuText("Chuchu SSH", style = typography.headline)
        Spacer(modifier = Modifier.height(12.dp))

        if (error != null) {
            ChuText(text = error, color = colors.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        ChuText("Transport", style = typography.label)
        ChuSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH),
            labels = mapOf(Transport.SSH to "SSH", Transport.TailscaleSSH to "Tailscale"),
            selected = transport,
            onSelect = onTransportChange,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (transport == Transport.TailscaleSSH && !tailscaleActive) {
            ChuText(text = "Tailscale VPN is not active", color = colors.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChuTextField(
                value = host,
                onValueChange = onHostChange,
                label = "Host",
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            ChuTextField(
                value = port,
                onValueChange = onPortChange,
                label = "Port",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.3f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        ChuTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = "Username",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (transport == Transport.SSH) {
            ChuTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        val hasCredentials = host.isNotBlank() &&
            (transport == Transport.TailscaleSSH || username.isNotBlank())
        val canConnect = hasCredentials && !(transport == Transport.TailscaleSSH && !tailscaleActive)
        ChuButton(
            onClick = onConnect,
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(),
            variant = ChuButtonVariant.Filled,
        ) {
            ChuText("Connect", style = typography.label, color = colors.onAccent)
        }
    }
}

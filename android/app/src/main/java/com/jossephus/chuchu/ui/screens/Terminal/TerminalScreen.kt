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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.repository.SettingsRepository
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
import com.jossephus.chuchu.ui.screens.Terminal.TerminalTabMode
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.BuiltinCommand
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
import com.jossephus.chuchu.ui.theme.resolveActiveThemeName
import com.jossephus.chuchu.ui.theme.toRgbIntArray
import com.jossephus.chuchu.ui.theme.toTerminalPaletteBytes
import com.jossephus.chuchu.ui.security.requireUserVerification
import com.jossephus.chuchu.ui.security.VerificationResult
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
    filteredActions: List<TerminalCustomAction>? = null,
    onClearFilter: () -> Unit = {},
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var expanded by remember { mutableStateOf(filteredActions != null) }
    var selectedGroupKey by remember { mutableStateOf<String?>(null) }
    val selectedGroup =
        remember(selectedGroupKey, groups) {
            groups.firstOrNull { it.keyLabel == selectedGroupKey }
        }

    LaunchedEffect(filteredActions) {
        if (filteredActions != null) {
            expanded = true
            selectedGroupKey = null
        }
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
                if (filteredActions != null) {
                    filteredActions.forEach { action ->
                        ChuButton(
                            onClick = {
                                onActionClick(action)
                                expanded = false
                                onClearFilter()
                            },
                            variant = ChuButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ChuText(action.label, style = typography.label)
                        }
                    }
                    ChuButton(
                        onClick = {
                            expanded = false
                            onClearFilter()
                        },
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        ChuText("<", style = typography.label, color = colors.textMuted)
                    }
                } else if (selectedGroup == null) {
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
                    onClearFilter()
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

@OptIn(ExperimentalLayoutApi::class)
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
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val hostsLoaded by vm.hostsLoaded.collectAsStateWithLifecycle()
    val activeTabForHost =
        remember(activeTab, hostId) { activeTab?.takeIf { it.spec.hostId == hostId } }
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val fileBrowserState by vm.fileBrowserState.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
    val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
    val resolvedThemeName = resolveActiveThemeName(
        themeMode = themeMode,
        darkThemeName = currentTheme,
        lightThemeName = lightThemeName,
    )
    val tabMode by settingsRepo.terminalTabMode.collectAsStateWithLifecycle()
    val currentAccessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
    val useSingleRowAccessoryBar by settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
    val currentTerminalCustomKeyGroups by
        settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()

    val accessoryLayout =
        remember(currentAccessoryLayoutIds) {
            TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
        }
    val ghosttyTheme =
        remember(context, resolvedThemeName) { GhosttyThemeRegistry.getTheme(context, resolvedThemeName) }
    val isDarkTheme = (ghosttyTheme?.background ?: colors.background).luminance() < 0.5f
    var selectedText by remember { mutableStateOf<String?>(null) }
    var hasSelectionActive by remember { mutableStateOf(false) }
    var selectionAnchorOffset by remember { mutableStateOf(Offset.Zero) }
    var selectionResetKey by remember { mutableStateOf(0) }
    var showPassphrasePrompt by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var pendingTabSpec by remember { mutableStateOf<TabSpec?>(null) }
    var passphraseFromPicker by remember { mutableStateOf(false) }
    var showTabSheet by remember { mutableStateOf(false) }
    var showServerPicker by remember { mutableStateOf(false) }
    var showGlobalTabManager by remember { mutableStateOf(false) }
    var hasSeenTabsForHost by remember(hostId) { mutableStateOf(false) }
    var focusedTabIndex by remember { mutableStateOf(0) }
    var terminalFontSizeSp by remember {
        mutableStateOf(terminalPrefs.getFloat("terminal_font_size_sp", 14f).coerceAtLeast(0.1f))
    }
    val showCustomActionsFab by settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
    val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
    var fabFilteredActions by remember { mutableStateOf<List<TerminalCustomAction>?>(null) }
    val chuchuKeys =
        remember(vm, tabMode, currentTerminalCustomKeyGroups, builtinShortcuts) {
            val isStrip = tabMode == TerminalTabMode.Strip
            val builtinCommandHandlers: Map<BuiltinCommand, () -> Unit> = mapOf(
                BuiltinCommand.Tabs to {
                    if (isStrip) {
                        showGlobalTabManager = true
                    } else {
                        showTabSheet = true
                    }
                },
                BuiltinCommand.NewTab to {
                    vm.duplicateActiveTab()
                    vm.selectConnectionTab(ConnectionTab.Terminal)
                    showTabSheet = false
                },
                BuiltinCommand.Close to {
                    val activeId = vm.activeTabId.value
                    if (activeId != null) vm.closeTab(activeId)
                },
                BuiltinCommand.Actions to { settingsRepo.setShowCustomActionsFab(!showCustomActionsFab) },
                BuiltinCommand.Settings to { onOpenSettings() },
            )
            // Build builtin hints and handlers in one pass so they stay consistent.
            // First binding for a key wins (settings already prevents duplicates).
            val builtinHints = mutableListOf<ChuchuHint>()
            val builtinHandlers = mutableMapOf<Char, () -> Unit>()
            builtinShortcuts.forEach { (commandId, shortcut) ->
                if (shortcut.isEmpty()) return@forEach
                val command = BuiltinCommand.fromId(commandId) ?: return@forEach
                val handler = builtinCommandHandlers[command] ?: return@forEach
                val keyChar = shortcut.first().lowercaseChar()
                if (keyChar in builtinHandlers) return@forEach
                builtinHandlers[keyChar] = handler
                builtinHints += ChuchuHint(key = shortcut, description = command.label)
            }
            val builtinKeys = builtinHandlers.keys.toSet()
            val customHints = mutableListOf<ChuchuHint>()
            val customHandlers = mutableMapOf<Char, () -> Unit>()
            val seenShortcuts = builtinKeys.toMutableSet()
            val shortcutActionsMap = mutableMapOf<Char, MutableList<TerminalCustomAction>>()
            currentTerminalCustomKeyGroups.forEach { group ->
                group.actions.forEach { action ->
                    val shortcut = action.shortcut?.takeIf { it.length == 1 } ?: return@forEach
                    val keyChar = shortcut.first().lowercaseChar()
                    if (keyChar in builtinKeys) return@forEach
                    shortcutActionsMap.getOrPut(keyChar) { mutableListOf() }.add(action)
                }
            }
            shortcutActionsMap.forEach { (keyChar, actions) ->
                if (!seenShortcuts.add(keyChar)) return@forEach
                customHints += ChuchuHint(key = keyChar.toString(), description = "[${actions.joinToString(", ") { it.label }}]")
                customHandlers[keyChar] = {
                    if (actions.size == 1) {
                        val decoded = decodeCustomActionValue(actions.first().payload)
                        val rawText = decoded.text +
                            if (CustomActionModifier.Enter in decoded.modifiers) "\n" else ""
                        val actionModifierState = modifierStateForCustomAction(decoded.modifiers)
                        vm.dispatchTextWithModifierState(rawText, actionModifierState)
                    } else {
                        fabFilteredActions = actions
                    }
                }
            }
            ChuchuKeyBindings(
                hints = builtinHints + customHints,
                handlers = builtinHandlers + customHandlers,
            )
        }
    val multiplexerState by vm.multiplexerState.collectAsStateWithLifecycle()

    LaunchedEffect(terminalFontSizeSp) {
        terminalPrefs.edit().putFloat("terminal_font_size_sp", terminalFontSizeSp).apply()
    }

    val hasTabsForHost =
        remember(tabs, hostId) {
            if (hostId == null) false else tabs.any { it.spec.hostId == hostId }
        }
    val tabsForHost =
        remember(tabs, hostId) {
            if (hostId == null) emptyList() else tabs.filter { it.spec.hostId == hostId }
        }
    val activeHostCount =
        remember(tabs) {
            tabs.map { it.spec.hostId ?: it.spec.sessionKey }
                .distinct()
                .size
        }
    val currentHostName = activeTab?.spec?.displayName?.takeIf { it.isNotBlank() }
        ?: activeTab?.spec?.host?.takeIf { it.isNotBlank() }
    val pickerScope = rememberCoroutineScope()

    val openPreparedTab: (TabSpec, Boolean, Boolean) -> Unit = { spec, requiresVerification, fromPicker ->
        val openOrPrompt: (TabSpec) -> Unit = { preparedSpec ->
            if (
                preparedSpec.authMethod == AuthMethod.KeyWithPassphrase &&
                    preparedSpec.keyPassphrase.isBlank()
            ) {
                passphraseFromPicker = fromPicker
                pendingTabSpec = preparedSpec
                showPassphrasePrompt = true
            } else if (preparedSpec.usesRuntimeMultiplexer) {
                vm.initiateMultiplexerOpen(preparedSpec)
            } else {
                vm.openTab(preparedSpec)
            }
        }

        if (requiresVerification) {
            requireUserVerification(
                context = context,
                title = "Verify to connect",
                subtitle = "Authenticate to open this server session",
            ) { result ->
                if (
                    result == VerificationResult.Success &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    openOrPrompt(spec)
                }
            }
        } else {
            openOrPrompt(spec)
        }
    }

    val openAnotherSessionForCurrentHost: () -> Unit = {
        val currentHostId = activeTab?.spec?.hostId ?: hostId
        when {
            activeTab != null -> {
                vm.duplicateActiveTab()
                vm.selectConnectionTab(ConnectionTab.Terminal)
            }

            currentHostId != null -> {
                pickerScope.launch(Dispatchers.IO) {
                    val prepared = vm.prepareTabOpenForHost(currentHostId) ?: return@launch
                    withContext(Dispatchers.Main) {
                        openPreparedTab(prepared.spec, prepared.requiresVerification, false)
                    }
                }
            }

            else -> {
                showServerPicker = true
            }
        }
    }

    LaunchedEffect(hostId) {
        showPassphrasePrompt = false
        passphraseInput = ""
        pendingTabSpec = null
        passphraseFromPicker = false
        if (hostId == null) return@LaunchedEffect
        val existing = vm.selectTabForHost(hostId)
        if (existing != null) {
            return@LaunchedEffect
        }
        val prepared = vm.prepareTabOpenForHost(hostId) ?: return@LaunchedEffect
        vm.refreshTailscaleStatus()
        openPreparedTab(prepared.spec, prepared.requiresVerification, false)
    }

    // Strip mode: never auto-back from host-scoped empty state.
    // Classic mode: back when all tabs for the current host are gone.
    LaunchedEffect(hostId, hasTabsForHost, tabMode) {
        if (tabMode == TerminalTabMode.Strip) return@LaunchedEffect
        if (hostId == null) return@LaunchedEffect
        if (hasTabsForHost) {
            hasSeenTabsForHost = true
        } else if (hasSeenTabsForHost) {
            onBack()
        }
    }

    LaunchedEffect(showTabSheet, tabsForHost, activeTabId) {
        if (!showTabSheet || tabsForHost.isEmpty()) return@LaunchedEffect
        val activeIndex = tabsForHost.indexOfFirst { it.id == activeTabId }
        focusedTabIndex =
            if (activeIndex >= 0) activeIndex else focusedTabIndex.coerceIn(0, tabsForHost.lastIndex)
    }

    LaunchedEffect(showTabSheet, activeTab?.id) {
        if (showTabSheet && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.listMultiplexerSessionsForCurrentHost()
        }
    }

    LaunchedEffect(showGlobalTabManager, activeTab?.id) {
        if (showGlobalTabManager && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.listMultiplexerSessionsForCurrentHost()
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
                    val preparedSpec = spec.copy(keyPassphrase = passphraseInput)
                    if (preparedSpec.usesRuntimeMultiplexer) {
                        vm.initiateMultiplexerOpen(preparedSpec)
                    } else {
                        vm.openTab(preparedSpec)
                    }
                }
                passphraseInput = ""
                pendingTabSpec = null
                passphraseFromPicker = false
            },
            onDismiss = {
                showPassphrasePrompt = false
                passphraseInput = ""
                pendingTabSpec = null
                if (!passphraseFromPicker) {
                    onBack()
                }
                passphraseFromPicker = false
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

    val preflightError = multiplexerState.preflightError
    LaunchedEffect(preflightError) {
        if (preflightError != null) {
            Toast.makeText(context, preflightError, Toast.LENGTH_LONG).show()
        }
    }
    when (sessionState.status) {
        SessionStatus.Disconnected,
        SessionStatus.Error -> {
            if (tabMode == TerminalTabMode.Strip) {
                Column(modifier = screenInsetsModifier.fillMaxSize()) {
                    TerminalTabStrip(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onTabSelected = { id -> vm.selectTab(id) },
                        onAddTab = openAnotherSessionForCurrentHost,
                        onOpenManager = { showGlobalTabManager = true },
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val errorMessage = preflightError ?: sessionState.error
                        if (errorMessage != null) {
                            TerminalRecoveryActions(
                                message = errorMessage,
                                isMultiplexerPreflight = preflightError != null,
                                dismissMultiplexerLabel = if (multiplexerState.reconnectRecovery) "dismiss" else "back",
                                onRetryMultiplexer = vm::retryPendingMultiplexerOpen,
                                onConnectWithoutMultiplexer = {
                                    if (vm.connectPendingWithoutMultiplexer()) {
                                        vm.selectConnectionTab(ConnectionTab.Terminal)
                                    }
                                },
                                onBack = { vm.dismissMultiplexerRecovery(onBack) },
                                onReconnect = vm::reconnect,
                            )
                        } else if (tabs.isEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ChuText(
                                    "no terminal sessions",
                                    style = typography.body,
                                    color = colors.textMuted,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                ChuButton(
                                    onClick = openAnotherSessionForCurrentHost,
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                    variant = ChuButtonVariant.Outlined,
                                    bracketed = true,
                                ) {
                                    ChuText(
                                        "+ new connection",
                                        style = typography.label,
                                        color = colors.accent,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val errorMessage = preflightError ?: sessionState.error
                    if (errorMessage != null) {
                        TerminalRecoveryActions(
                            message = errorMessage,
                            isMultiplexerPreflight = preflightError != null,
                            dismissMultiplexerLabel = if (multiplexerState.reconnectRecovery) "dismiss" else "back",
                            onRetryMultiplexer = vm::retryPendingMultiplexerOpen,
                            onConnectWithoutMultiplexer = {
                                if (vm.connectPendingWithoutMultiplexer()) {
                                    vm.selectConnectionTab(ConnectionTab.Terminal)
                                }
                            },
                            onBack = { vm.dismissMultiplexerRecovery(onBack) },
                            onReconnect = vm::reconnect,
                        )
                    }
                }
            }
        }

        SessionStatus.Connecting -> {
            val hostLabel = if (tabMode == TerminalTabMode.Strip) {
                activeTab?.spec?.tabLabel?.let { "$it..." } ?: "..."
            } else {
                activeTabForHost?.spec?.host?.let { "$it..." } ?: "..."
            }
            if (tabMode == TerminalTabMode.Strip) {
                Column(modifier = screenInsetsModifier.fillMaxSize()) {
                    TerminalTabStrip(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onTabSelected = { id -> vm.selectTab(id) },
                        onAddTab = openAnotherSessionForCurrentHost,
                        onOpenManager = { showGlobalTabManager = true },
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChuText(
                            "Connecting to $hostLabel",
                            style = typography.body,
                        )
                    }
                }
            } else {
                Column(
                    modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ChuText(
                        "Connecting to $hostLabel",
                        style = typography.body,
                    )
                }
            }
        }

        SessionStatus.Connected,
        SessionStatus.Reconnecting -> {
            val isReconnecting = sessionState.status == SessionStatus.Reconnecting
            val snapshot = sessionState.snapshot
            if (snapshot != null) {
                var modifierState by remember { mutableStateOf(ModifierState()) }
                val inputViewRef = remember { mutableStateOf<TerminalInputView?>(null) }
                val clipboard = remember {
                    context.getSystemService(ClipboardManager::class.java)
                }

                fun pasteClipboard(): Boolean {
                    val clip = clipboard?.primaryClip
                    if (clip == null || clip.itemCount == 0) {
                        return false
                    }
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    if (text.isNotEmpty()) {
                        vm.onPasteText(modifierState.applyToText(text))
                        selectedText = null
                        hasSelectionActive = false
                        selectionAnchorOffset = Offset.Zero
                        selectionResetKey += 1
                        return true
                    }
                    return false
                }

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
                                .blur(
                                    if (showTabSheet || showGlobalTabManager || showServerPicker) 10.dp
                                    else 0.dp
                                )
                                .imePadding()
                    ) {
                        // Tab strip (strip mode only — always visible even with zero tabs)
                        if (tabMode == TerminalTabMode.Strip) {
                            TerminalTabStrip(
                                tabs = tabs,
                                activeTabId = activeTabId,
                                onTabSelected = { id ->
                                    vm.selectTab(id)
                                },
                                onAddTab = openAnotherSessionForCurrentHost,
                                onOpenManager = {
                                    showGlobalTabManager = true
                                },
                            )
                        }

                        // Empty state in strip mode when all tabs are closed
                        if (tabMode == TerminalTabMode.Strip && tabs.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ChuText(
                                        "no terminal sessions",
                                        style = typography.body,
                                        color = colors.textMuted,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    ChuButton(
                                        onClick = openAnotherSessionForCurrentHost,
                                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                        variant = ChuButtonVariant.Outlined,
                                        bracketed = true,
                                    ) {
                                        ChuText(
                                            "+ new connection",
                                            style = typography.label,
                                            color = colors.accent,
                                        )
                                    }
                                }
                            }
                        } else if (selectedTab == ConnectionTab.Files) {
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
                                    terminalHandle = sessionState.handle,
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
                                                    }
                                                }
                                                onTerminalKey = { key, codepoint, mods, action, charCode ->
                                                    var shouldForwardToTerminal = true
                                                    val overlayOpen = showTabSheet || showGlobalTabManager
                                                    val overlayTabs = if (showGlobalTabManager) tabs else tabsForHost
                                                    if (
                                                        overlayOpen &&
                                                            overlayTabs.isEmpty() &&
                                                            action == GhosttyKeyAction.Press &&
                                                            key == TerminalSpecialKey.Escape.engineKey
                                                    ) {
                                                        showTabSheet = false
                                                        showGlobalTabManager = false
                                                        shouldForwardToTerminal = false
                                                    } else if (overlayOpen && overlayTabs.isNotEmpty()) {
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
                                                                    showGlobalTabManager = false
                                                                }
                                                                't' -> {
                                                                    if (tabMode == TerminalTabMode.Strip) {
                                                                        showGlobalTabManager = true
                                                                    } else {
                                                                        showTabSheet = true
                                                                    }
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
                                                                            overlayTabs.size
                                                                        )

                                                                TerminalSpecialKey.Right.engineKey,
                                                                TerminalSpecialKey.Down.engineKey ->
                                                                    focusedTabIndex =
                                                                        (focusedTabIndex + 1).mod(
                                                                            overlayTabs.size
                                                                        )

                                                                TerminalSpecialKey.Enter
                                                                    .engineKey -> {
                                                                    overlayTabs
                                                                        .getOrNull(focusedTabIndex)
                                                                        ?.let {
                                                                            vm.selectTab(it.id)
                                                                            showTabSheet = false
                                                                            showGlobalTabManager = false
                                                                        }
                                                                }

                                                                TerminalSpecialKey.Escape
                                                                    .engineKey -> {
                                                                    showTabSheet = false
                                                                    showGlobalTabManager = false
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
                                                            charCode,
                                                        )
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

                                if (currentTerminalCustomKeyGroups.isNotEmpty() &&
                                    (showCustomActionsFab || fabFilteredActions != null)
                                ) {
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
                                            requestInputFocus()
                                        },
                                        modifier =
                                            Modifier.align(Alignment.BottomEnd)
                                                .padding(end = 14.dp, bottom = 12.dp),
                                        filteredActions = fabFilteredActions,
                                        onClearFilter = { fabFilteredActions = null },
                                    )
                                }

                                androidx.compose.animation.AnimatedVisibility(
                                    visible = chuchuKeys.isPrefixActive,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                                .background(colors.surface)
                                                .border(1.dp, colors.border)
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ChuText(
                                            "⌘",
                                            style = typography.label,
                                            color = colors.accent,
                                            modifier = Modifier.width(24.dp),
                                        )
                                        FlowRow(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            chuchuKeys.hints().forEach { hint ->
                                                ChuText(
                                                    "${hint.key}: ${hint.description}",
                                                    style = typography.labelSmall,
                                                    color = colors.textSecondary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        if (selectedTab == ConnectionTab.Terminal) {
                            if (activeHostCount > 1 && currentHostName != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    ChuText(
                                        text = currentHostName,
                                        style = typography.labelSmall,
                                        color = colors.textMuted.copy(alpha = 0.7f),
                                    )
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
                            val preDispatchModifierState = modifierState
                            val result =
                                TerminalAccessoryDispatcher.dispatch(action, preDispatchModifierState)
                            modifierState = result.modifierState

                            // Mirror main-handler IME suppression
                            if (result.suppressImeInput) {
                                inputViewRef.value?.armInputSuppression(action.toString())
                            }

                            when (result.specialKey) {
                                TerminalSpecialKey.Left,
                                TerminalSpecialKey.Up -> {
                                    if (tabsForHost.isNotEmpty())
                                        focusedTabIndex =
                                            (focusedTabIndex - 1).mod(tabsForHost.size)
                                }
                                TerminalSpecialKey.Right,
                                TerminalSpecialKey.Down -> {
                                    if (tabsForHost.isNotEmpty())
                                        focusedTabIndex =
                                            (focusedTabIndex + 1).mod(tabsForHost.size)
                                }
                                TerminalSpecialKey.Enter -> {
                                    tabsForHost.getOrNull(focusedTabIndex)?.let {
                                        vm.selectTab(it.id)
                                        showTabSheet = false
                                    }
                                }
                                TerminalSpecialKey.Escape -> {
                                    showTabSheet = false
                                }
                                else -> {
                                    result.specialKey?.let { key ->
                                        vm.onSpecialKeyInput(
                                            key,
                                            preDispatchModifierState.terminalMods(),
                                        )
                                    }
                                    result.text?.let { text ->
                                        if (!chuchuKeys.handleText(text)) {
                                            vm.onTextInput(text)
                                        }
                                    }
                                }
                            }

                            // Preserve sticky modifiers: paste applies active modifiers
                            // but does not clear them.
                            if (result.shouldPaste) {
                                pasteClipboard()
                            }
                        }
                    }
                    CommandPalette(
                        tabs = tabsForHost,
                        activeTabId = activeTabId,
                        focusedTabIndex = focusedTabIndex,
                        onFocusedTabIndexChange = { focusedTabIndex = it },
                        accessoryItems = accessoryLayout,
                        accessoryModifierState = modifierState,
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
                        multiplexerEnabled = activeTab?.spec?.usesRuntimeMultiplexer == true,
                        multiplexerSessions = multiplexerState.sessions,
                        multiplexerSessionsLoading = multiplexerState.sessionsLoading,
                        multiplexerSessionsError = multiplexerState.sessionsError,
                        onMultiplexerRefresh = vm::listMultiplexerSessionsForCurrentHost,
                        onMultiplexerNew = vm::createNextMultiplexerSession,
                        onMultiplexerAttach = { name ->
                            vm.switchToMultiplexerSession(name, multiplexerState.sessionsSourceTabId)
                        },
                    )
                }

            } else {
                if (tabMode == TerminalTabMode.Strip) {
                    Column(modifier = screenInsetsModifier.fillMaxSize()) {
                        TerminalTabStrip(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            onTabSelected = { id -> vm.selectTab(id) },
                            onAddTab = openAnotherSessionForCurrentHost,
                            onOpenManager = { showGlobalTabManager = true },
                        )
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val hostForMessage = activeTab?.spec?.tabLabel?.let { " $it" } ?: ""
                            val message =
                                if (isReconnecting) {
                                    "Reconnecting to${hostForMessage}..."
                                } else {
                                    "Preparing terminal..."
                                }
                            ChuText(message, style = typography.body)
                        }
                    }
                } else {
                    Column(
                        modifier = screenInsetsModifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val hostForMessage = activeTabForHost?.spec?.host?.let { " $it" } ?: ""
                        val message =
                            if (isReconnecting) {
                                "Reconnecting to${hostForMessage}..."
                            } else {
                                "Preparing terminal..."
                            }
                        ChuText(message, style = typography.body)
                    }
                }
            }
        }
    }

    BackHandler(enabled = showServerPicker) { showServerPicker = false }
    BackHandler(enabled = showGlobalTabManager) { showGlobalTabManager = false }

    // Strip mode overlays — hoisted outside the when block so they are
    // available from disconnected, error, connecting, and connected states.
    if (tabMode == TerminalTabMode.Strip) {
        TerminalServerPicker(
            visible = showServerPicker,
            hosts = hosts,
            loaded = hostsLoaded,
            onHostSelected = { host ->
                showServerPicker = false
                pickerScope.launch(Dispatchers.IO) {
                    val prepared = vm.prepareTabOpen(host)
                    withContext(Dispatchers.Main) {
                        openPreparedTab(prepared.spec, prepared.requiresVerification, true)
                    }
                }
            },
            onDismiss = { showServerPicker = false },
        )

        TerminalTabManager(
            visible = showGlobalTabManager,
            tabs = tabs,
            activeTabId = activeTabId,
            focusedTabIndex = focusedTabIndex,
            onFocusedTabIndexChange = { focusedTabIndex = it },
            onSelectTab = { id ->
                vm.selectTab(id)
                showGlobalTabManager = false
            },
            onCloseTab = { id ->
                vm.closeTab(id)
            },
            onDuplicateTab = { id ->
                vm.duplicateTab(id)
            },
            onAddTab = openAnotherSessionForCurrentHost,
            onDismiss = { showGlobalTabManager = false },
            multiplexerEnabled = activeTab?.spec?.usesRuntimeMultiplexer == true,
            multiplexerSessions = multiplexerState.sessions,
            multiplexerSessionsLoading = multiplexerState.sessionsLoading,
            multiplexerSessionsError = multiplexerState.sessionsError,
            onMultiplexerRefresh = vm::listMultiplexerSessionsForCurrentHost,
            onMultiplexerNew = vm::createNextMultiplexerSession,
            onMultiplexerAttach = { name ->
                vm.switchToMultiplexerSession(name, multiplexerState.sessionsSourceTabId)
            },
        )
    }
}

@Composable
private fun TerminalRecoveryActions(
    message: String,
    isMultiplexerPreflight: Boolean,
    dismissMultiplexerLabel: String = "back",
    onRetryMultiplexer: () -> Unit,
    onConnectWithoutMultiplexer: () -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ChuText(message, color = colors.error, style = typography.body)
        Spacer(modifier = Modifier.height(16.dp))
        if (isMultiplexerPreflight) {
            ChuButton(
                onClick = onRetryMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Filled,
            ) {
                ChuText("retry multiplexer", style = typography.label, color = colors.onAccent)
            }
            Spacer(modifier = Modifier.height(8.dp))
            ChuButton(
                onClick = onConnectWithoutMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
            ) {
                ChuText("connect without multiplexer", style = typography.label)
            }
            Spacer(modifier = Modifier.height(8.dp))
            ChuButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                borderColor = colors.textMuted,
            ) {
                ChuText(dismissMultiplexerLabel, style = typography.label, color = colors.textMuted)
            }
        } else {
            ChuButton(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Filled,
            ) {
                ChuText("Retry", style = typography.label, color = colors.onAccent)
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

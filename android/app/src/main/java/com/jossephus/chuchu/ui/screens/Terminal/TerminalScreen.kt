package com.jossephus.chuchu.ui.screens.Terminal

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.HostKeyPromptDialog
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.terminal.BuiltinCommand
import com.jossephus.chuchu.ui.terminal.ChuchuKeyBindings
import com.jossephus.chuchu.ui.terminal.CustomActionModifier
import com.jossephus.chuchu.ui.terminal.GhosttyKey
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.terminal.decodeCustomActionValue
import com.jossephus.chuchu.ui.terminal.modifierStateForCustomAction
import com.jossephus.chuchu.ui.terminal.toGhosttyKey
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import com.jossephus.chuchu.ui.theme.resolveActiveThemeName
import com.jossephus.chuchu.ui.security.requireUserVerification
import com.jossephus.chuchu.ui.security.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun TerminalViewModel.dispatchTextWithModifierState(
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
private fun NewTabHostPicker(
    currentHostName: String,
    otherHosts: List<HostProfile>,
    onCurrentHost: () -> Unit,
    onHostSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current

    Popup(
        alignment = Alignment.TopEnd,
        offset = with(density) { IntOffset(0, 36.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier =
                Modifier.width(320.dp)
                    .background(colors.background)
                    .border(1.dp, colors.border)
                    .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ChuButton(
                onClick = onCurrentHost,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                ChuText(
                    stringResource(R.string.terminal_new_session_on, currentHostName),
                    style = typography.label,
                    color = colors.textPrimary,
                )
            }

            if (otherHosts.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(1.dp)
                            .background(colors.border),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(otherHosts, key = { it.id }) { host ->
                        ChuButton(
                            onClick = { onHostSelected(host.id) },
                            modifier = Modifier.fillMaxWidth(),
                            variant = ChuButtonVariant.Ghost,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ChuText(
                                    host.name,
                                    style = typography.label,
                                    color = colors.textPrimary,
                                )
                                ChuText(
                                    "${host.username}@${host.host}:${host.port}",
                                    style = typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
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
    openLocalShell: Boolean = false,
    multiplexerSession: String? = null,
    createMultiplexerSession: Boolean = false,
) {
    val sessionState by vm.sessionChrome.collectAsStateWithLifecycle()
    val hasSnapshot by vm.hasSnapshot.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val activeTabForHost =
        remember(activeTab, hostId, openLocalShell) {
            if (openLocalShell) {
                activeTab?.takeIf {
                    it.spec.hostId == null && it.spec.transport == Transport.LocalShell
                }
            } else {
                activeTab?.takeIf { it.spec.hostId == hostId }
            }
        }
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val filesSupported = activeTab?.spec?.transport != Transport.LocalShell
    val fileBrowserState by vm.fileBrowserState.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = ChuColors.current
    val screenInsetsModifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    var lastSessionStatus by remember { mutableStateOf<SessionStatus?>(null) }
    val settingsRepo = remember(context) { SettingsRepository.getInstance(context) }
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
    val settingsFontSize by settingsRepo.terminalFontSize.collectAsStateWithLifecycle()
    val keepScreenAwake by settingsRepo.keepScreenAwake.collectAsStateWithLifecycle()
    val disableAutocorrect by settingsRepo.disableAutocorrect.collectAsStateWithLifecycle()
    val keepAwakeView = LocalView.current
    DisposableEffect(keepScreenAwake, keepAwakeView) {
        val window = (keepAwakeView.context as? Activity)?.window
        if (keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val accessoryLayout =
        remember(currentAccessoryLayoutIds) {
            TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
        }
    val ghosttyTheme =
        remember(context, resolvedThemeName) { GhosttyThemeRegistry.getTheme(context, resolvedThemeName) }
    val isDarkTheme = (ghosttyTheme?.background ?: colors.background).luminance() < 0.5f
    val ui = remember {
        TerminalScreenUiState(
            settingsFontSize.coerceIn(
                SettingsRepository.MIN_TERMINAL_FONT_SIZE,
                SettingsRepository.MAX_TERMINAL_FONT_SIZE,
            ),
        )
    }
    var showPassphrasePrompt by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var pendingTabSpec by remember { mutableStateOf<TabSpec?>(null) }
    var passphraseFromPicker by remember { mutableStateOf(false) }
    var hasSeenTabsForHost by remember(hostId, openLocalShell) { mutableStateOf(false) }
    LaunchedEffect(settingsFontSize) {
        ui.terminalFontSizeSp =
            settingsFontSize.coerceIn(
                SettingsRepository.MIN_TERMINAL_FONT_SIZE,
                SettingsRepository.MAX_TERMINAL_FONT_SIZE,
            )
    }
    val showCustomActionsFab by settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
    val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
    val builtinCommandLabels =
        BuiltinCommand.entries.associateWith { stringResource(it.labelRes) }
    val chuchuKeys =
        remember(
            vm,
            tabMode,
            currentTerminalCustomKeyGroups,
            builtinShortcuts,
            showCustomActionsFab,
            builtinCommandLabels,
        ) {
            val isStrip = tabMode == TerminalTabMode.Strip
            val builtinCommandHandlers: Map<BuiltinCommand, () -> Unit> = mapOf(
                BuiltinCommand.Tabs to {
                    if (isStrip) {
                        ui.showGlobalTabManager = true
                    } else {
                        ui.showTabSheet = true
                    }
                },
                BuiltinCommand.NewTab to {
                    vm.duplicateActiveTab()
                    vm.selectConnectionTab(ConnectionTab.Terminal)
                    ui.showTabSheet = false
                },
                BuiltinCommand.Close to {
                    val activeId = vm.activeTabId.value
                    if (activeId != null) vm.closeTab(activeId)
                },
                BuiltinCommand.Actions to { settingsRepo.setShowCustomActionsFab(!showCustomActionsFab) },
                BuiltinCommand.Settings to { onOpenSettings() },
            )
            ChuchuKeyBindings.build(
                builtinShortcuts = builtinShortcuts,
                builtinLabels = builtinCommandLabels,
                builtinCommandHandlers = builtinCommandHandlers,
                customGroups = currentTerminalCustomKeyGroups,
                onDispatchAction = { action ->
                    val decoded = decodeCustomActionValue(action.payload)
                    val rawText = decoded.text +
                        if (CustomActionModifier.Enter in decoded.modifiers) "\n" else ""
                    val actionModifierState = modifierStateForCustomAction(decoded.modifiers)
                    vm.dispatchTextWithModifierState(rawText, actionModifierState)
                },
                onSelectAmongActions = { actions -> ui.fabFilteredActions = actions },
            )
        }
    val multiplexerState by vm.multiplexerState.collectAsStateWithLifecycle()

    LaunchedEffect(ui.terminalFontSizeSp) {
        settingsRepo.setTerminalFontSize(ui.terminalFontSizeSp)
    }

    LaunchedEffect(openLocalShell) {
        if (!openLocalShell) return@LaunchedEffect
        showPassphrasePrompt = false
        passphraseInput = ""
        pendingTabSpec = null
        passphraseFromPicker = false
        val existing = vm.selectLocalShellTab()
        if (existing != null) {
            return@LaunchedEffect
        }
        vm.openTab(
            TabSpec(
                hostId = null,
                displayName = context.getString(R.string.server_list_local_shell),
                host = "localhost",
                username = "android",
                authMethod = AuthMethod.None,
                transport = Transport.LocalShell,
            )
        )
    }


    val hasTabsForHost =
        remember(tabs, hostId, openLocalShell) {
            when {
                openLocalShell ->
                    tabs.any { it.spec.hostId == null && it.spec.transport == Transport.LocalShell }
                hostId != null -> tabs.any { it.spec.hostId == hostId }
                else -> false
            }
        }
    val tabsForHost =
        remember(tabs, hostId, openLocalShell) {
            when {
                openLocalShell ->
                    tabs.filter { it.spec.hostId == null && it.spec.transport == Transport.LocalShell }
                hostId != null -> tabs.filter { it.spec.hostId == hostId }
                else -> emptyList()
            }
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
                title = context.getString(R.string.verification_connect_title),
                subtitle = context.getString(R.string.verification_connect_subtitle),
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

        }
    }
    val pickerCurrentHostId = activeTab?.spec?.hostId ?: hostId
    val pickerCurrentHostName =
        currentHostName ?: hosts.firstOrNull { it.id == pickerCurrentHostId }?.name
            ?: stringResource(R.string.terminal_current_host)
    val pickerOtherHosts =
        remember(hosts, pickerCurrentHostId) { hosts.filter { it.id != pickerCurrentHostId } }
    val openNewSessionForHost: (Long) -> Unit = { pickerHostId ->
        pickerScope.launch(Dispatchers.IO) {
            val prepared = vm.prepareTabOpenForHost(pickerHostId) ?: return@launch
            withContext(Dispatchers.Main) {
                openPreparedTab(prepared.spec, prepared.requiresVerification, false)
            }
        }
    }
    val newTabPicker: @Composable () -> Unit = {
        if (ui.showNewTabPicker) {
            NewTabHostPicker(
                currentHostName = pickerCurrentHostName,
                otherHosts = pickerOtherHosts,
                onCurrentHost = {
                    ui.showNewTabPicker = false
                    openAnotherSessionForCurrentHost()
                },
                onHostSelected = { pickerHostId ->
                    ui.showNewTabPicker = false
                    openNewSessionForHost(pickerHostId)
                },
                onDismiss = { ui.showNewTabPicker = false },
            )
        }
    }
    val tabStrip: @Composable () -> Unit = {
        TerminalTabStrip(
            tabs = tabs,
            activeTabId = activeTabId,
            onTabSelected = { id -> vm.selectTab(id) },
            onAddTab = { ui.showNewTabPicker = true },
            onOpenManager = { ui.showGlobalTabManager = true },
            newTabPicker = newTabPicker,
        )
    }

    var multiplexerSessionHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hostId, openLocalShell) {
        showPassphrasePrompt = false
        passphraseInput = ""
        pendingTabSpec = null
        passphraseFromPicker = false
        if (hostId == null || openLocalShell) return@LaunchedEffect
        if (multiplexerSession != null) {
            if (multiplexerSessionHandled) return@LaunchedEffect
            multiplexerSessionHandled = true
            if (!createMultiplexerSession && vm.selectMultiplexerTab(hostId, multiplexerSession) != null) {
                return@LaunchedEffect
            }
            val prepared = vm.prepareTabOpenForHost(hostId) ?: return@LaunchedEffect
            vm.refreshTailscaleStatus()
            openPreparedTab(
                prepared.spec.copy(
                    multiplexerSessionName = multiplexerSession,
                    multiplexerCreateIfMissing = createMultiplexerSession,
                ),
                false,
                false,
            )
            return@LaunchedEffect
        }
        val existing = vm.selectTabForHost(hostId)
        if (existing != null) {
            return@LaunchedEffect
        }
        val prepared = vm.prepareTabOpenForHost(hostId) ?: return@LaunchedEffect
        vm.refreshTailscaleStatus()
        // Biometric verification already happened in ApplicationNavController
        // before navigating to this route — skip the redundant re-prompt.
        openPreparedTab(prepared.spec, false, false)
    }

    // Strip mode: never auto-back from normal host-scoped empty state.
    // Local-shell routes still close when their local tab is gone.
    LaunchedEffect(hostId, hasTabsForHost, openLocalShell, tabMode) {
        if (openLocalShell) {
            if (hasTabsForHost) {
                hasSeenTabsForHost = true
            } else if (hasSeenTabsForHost) {
                onBack()
            }
            return@LaunchedEffect
        }
        if (tabMode == TerminalTabMode.Strip) return@LaunchedEffect
        if (hostId == null) return@LaunchedEffect
        if (hasTabsForHost) {
            hasSeenTabsForHost = true
        } else if (hasSeenTabsForHost) {
            onBack()
        }
    }

    LaunchedEffect(ui.showTabSheet, tabsForHost, activeTabId) {
        if (!ui.showTabSheet || tabsForHost.isEmpty()) return@LaunchedEffect
        val activeIndex = tabsForHost.indexOfFirst { it.id == activeTabId }
        ui.focusedTabIndex =
            if (activeIndex >= 0) activeIndex else ui.focusedTabIndex.coerceIn(0, tabsForHost.lastIndex)
    }

    LaunchedEffect(ui.showTabSheet, activeTab?.id) {
        if (ui.showTabSheet && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.listMultiplexerSessionsForCurrentHost()
        }
    }

    LaunchedEffect(ui.showGlobalTabManager, activeTab?.id) {
        if (ui.showGlobalTabManager && activeTab?.spec?.usesRuntimeMultiplexer == true) {
            vm.listMultiplexerSessionsForCurrentHost()
        }
    }

    if (showPassphrasePrompt) {
        ChuDialog(
            title = stringResource(R.string.terminal_key_passphrase),
            confirmLabel = stringResource(R.string.dialog_confirm_connect),
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
                label = stringResource(R.string.field_passphrase),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    fun showLocalShellFilesUnsupported() {
        val message = context.getString(R.string.terminal_files_unsupported)
        ui.localShellFilesMessage = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(filesSupported, selectedTab) {
        if (filesSupported) {
            ui.localShellFilesMessage = null
        }
        if (!filesSupported && selectedTab == ConnectionTab.Files) {
            vm.selectConnectionTab(ConnectionTab.Terminal)
        }
    }

    LaunchedEffect(sessionState.status, sessionState.error) {
        val previous = lastSessionStatus
        lastSessionStatus = sessionState.status
        if (sessionState.status == SessionStatus.Error && previous != SessionStatus.Error) {
            val message =
                sessionState.error ?: context.getString(R.string.terminal_connection_failed)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    hostKeyPrompt?.let { prompt -> HostKeyPromptDialog(prompt, vm::onHostKeyDecision) }

    val preflightError = multiplexerState.preflightError
    LaunchedEffect(preflightError) {
        if (preflightError != null) {
            Toast.makeText(context, preflightError, Toast.LENGTH_LONG).show()
        }
    }
    val displaySessionStatus =
        if (multiplexerState.preflightLoading) SessionStatus.Connecting else sessionState.status
    val dismissMultiplexerLabel =
        if (multiplexerState.reconnectRecovery) {
            stringResource(R.string.action_dismiss)
        } else {
            stringResource(R.string.action_back)
        }
    when (displaySessionStatus) {
        SessionStatus.Disconnected,
        SessionStatus.Error -> {
            TerminalDisconnectedContent(
                isStrip = tabMode == TerminalTabMode.Strip,
                tabStrip = tabStrip,
                errorMessage = preflightError ?: sessionState.error,
                isMultiplexerPreflight = preflightError != null,
                dismissMultiplexerLabel = dismissMultiplexerLabel,
                showEmptyTabs = tabs.isEmpty(),
                onNewConnection = openAnotherSessionForCurrentHost,
                onRetryMultiplexer = vm::retryPendingMultiplexerOpen,
                onConnectWithoutMultiplexer = {
                    if (vm.connectPendingWithoutMultiplexer()) {
                        vm.selectConnectionTab(ConnectionTab.Terminal)
                    }
                },
                onBack = { vm.dismissMultiplexerRecovery(onBack) },
                onReconnect = vm::reconnect,
                modifier = screenInsetsModifier,
            )
        }

        SessionStatus.Connecting -> {
            val hostLabel = if (multiplexerState.preflightLoading) {
                "${multiplexerState.preflightHostLabel ?: stringResource(R.string.terminal_unknown_server)}..."
            } else if (tabMode == TerminalTabMode.Strip) {
                activeTab?.spec?.tabLabel?.let { "$it..." } ?: "..."
            } else {
                activeTabForHost?.spec?.host?.let { "$it..." } ?: "..."
            }
            TerminalConnectingContent(
                isStrip = tabMode == TerminalTabMode.Strip,
                tabStrip = tabStrip,
                hostLabel = hostLabel,
                modifier = screenInsetsModifier,
            )
        }

        SessionStatus.Connected,
        SessionStatus.Reconnecting -> {
            val isReconnecting = sessionState.status == SessionStatus.Reconnecting
            if (hasSnapshot) {
                TerminalConnectedContent(
                    vm = vm,
                    ui = ui,
                    sessionState = sessionState,
                    tabs = tabs,
                    tabsForHost = tabsForHost,
                    activeTabId = activeTabId,
                    tabMode = tabMode,
                    selectedTab = selectedTab,
                    filesSupported = filesSupported,
                    fileBrowserState = fileBrowserState,
                    ghosttyTheme = ghosttyTheme,
                    isDarkTheme = isDarkTheme,
                    multiplexerState = multiplexerState,
                    chuchuKeys = chuchuKeys,
                    accessoryLayout = accessoryLayout,
                    useSingleRowAccessoryBar = useSingleRowAccessoryBar,
                    customKeyGroups = currentTerminalCustomKeyGroups,
                    showCustomActionsFab = showCustomActionsFab,
                    disableAutocorrect = disableAutocorrect,
                    activeHostCount = activeHostCount,
                    currentHostName = currentHostName,
                    activeTabUsesMultiplexer = activeTab?.spec?.usesRuntimeMultiplexer == true,
                    tabStrip = tabStrip,
                    onNewConnection = openAnotherSessionForCurrentHost,
                    onShowLocalShellFilesUnsupported = { showLocalShellFilesUnsupported() },
                    onOpenSettings = onOpenSettings,
                    modifier = screenInsetsModifier,
                )
            } else {
                val hostForMessage =
                    if (tabMode == TerminalTabMode.Strip) {
                        activeTab?.spec?.tabLabel?.let { " $it" } ?: ""
                    } else {
                        activeTabForHost?.spec?.host?.let { " $it" } ?: ""
                    }
                TerminalPreparingContent(
                    isStrip = tabMode == TerminalTabMode.Strip,
                    tabStrip = tabStrip,
                    isReconnecting = isReconnecting,
                    hostForMessage = hostForMessage,
                    modifier = screenInsetsModifier,
                )
            }
        }
    }

    BackHandler(enabled = ui.showGlobalTabManager) { ui.showGlobalTabManager = false }

    // Strip mode overlays — hoisted outside the when block so they are
    // available from disconnected, error, connecting, and connected states.
    if (tabMode == TerminalTabMode.Strip) {
        TerminalTabManager(
            visible = ui.showGlobalTabManager,
            tabs = tabs,
            activeTabId = activeTabId,
            focusedTabIndex = ui.focusedTabIndex,
            onFocusedTabIndexChange = { ui.focusedTabIndex = it },
            onSelectTab = { id ->
                vm.selectTab(id)
                ui.showGlobalTabManager = false
            },
            onCloseTab = { id ->
                vm.closeTab(id)
            },
            onDuplicateTab = { id ->
                vm.duplicateTab(id)
            },
            onAddTab = openAnotherSessionForCurrentHost,
            onDismiss = { ui.showGlobalTabManager = false },
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

package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.ChuchuKeyBindings
import com.jossephus.chuchu.ui.terminal.KeyboardAccessoryBar
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.ResolvedAccessoryEntry
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryDispatcher
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalBottomBar(
    hostName: String?,
    showHostName: Boolean,
    localShellFilesMessage: String?,
    accessoryLayout: List<ResolvedAccessoryEntry>,
    modifierState: ModifierState,
    onAccessoryAction: (AccessoryAction) -> Unit,
    onOpenSettings: () -> Unit,
    onChuchuKey: () -> Unit,
    chuchuKeyActive: Boolean,
    onOpenFiles: () -> Unit,
    useSingleRow: Boolean,
    onlyWithKeyboard: Boolean,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    if (showHostName && hostName != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ChuText(
                text = hostName,
                style = typography.labelSmall,
                color = colors.textMuted.copy(alpha = 0.7f),
            )
        }
    }
    localShellFilesMessage?.let { message ->
        ChuText(
            text = message,
            style = typography.labelSmall,
            color = colors.textSecondary,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (onlyWithKeyboard && !WindowInsets.isImeVisible) {
        return
    }
    KeyboardAccessoryBar(
        entries = accessoryLayout,
        modifierState = modifierState,
        onAction = onAccessoryAction,
        onSettings = onOpenSettings,
        onChuchuKey = onChuchuKey,
        chuchuKeyActive = chuchuKeyActive,
        onOpenFiles = onOpenFiles,
        useSingleRow = useSingleRow,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
internal fun TerminalCommandPaletteSheet(
    vm: TerminalViewModel,
    ui: TerminalScreenUiState,
    chuchuKeys: ChuchuKeyBindings,
    modifierState: ModifierState,
    onModifierStateChange: (ModifierState) -> Unit,
    inputViewRef: MutableState<TerminalInputView?>,
    tabsForHost: List<TabSession>,
    activeTabId: String?,
    accessoryLayout: List<ResolvedAccessoryEntry>,
    useSingleRowAccessoryBar: Boolean,
    filesSupported: Boolean,
    onShowLocalShellFilesUnsupported: () -> Unit,
    onOpenSettings: () -> Unit,
    multiplexerEnabled: Boolean,
    multiplexerState: MultiplexerUiState,
    onPaste: () -> Unit,
) {
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
            onModifierStateChange(result.modifierState)

            if (result.suppressImeInput) {
                inputViewRef.value?.armInputSuppression(action.toString())
            }

            when (result.specialKey) {
                TerminalSpecialKey.Left,
                TerminalSpecialKey.Up -> {
                    if (tabsForHost.isNotEmpty())
                        ui.focusedTabIndex =
                            (ui.focusedTabIndex - 1).mod(tabsForHost.size)
                }
                TerminalSpecialKey.Right,
                TerminalSpecialKey.Down -> {
                    if (tabsForHost.isNotEmpty())
                        ui.focusedTabIndex =
                            (ui.focusedTabIndex + 1).mod(tabsForHost.size)
                }
                TerminalSpecialKey.Enter -> {
                    tabsForHost.getOrNull(ui.focusedTabIndex)?.let {
                        vm.selectTab(it.id)
                        ui.showTabSheet = false
                    }
                }
                TerminalSpecialKey.Escape -> {
                    ui.showTabSheet = false
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

            if (result.shouldPaste) {
                onPaste()
            }
        }
    }
    CommandPalette(
        tabs = tabsForHost,
        activeTabId = activeTabId,
        focusedTabIndex = ui.focusedTabIndex,
        onFocusedTabIndexChange = { ui.focusedTabIndex = it },
        accessoryEntries = accessoryLayout,
        accessoryModifierState = modifierState,
        onAccessoryAction = paletteAccessoryAction,
        onChuchuKey = { chuchuKeys.togglePrefix() },
        chuchuKeyActive = chuchuKeys.isPrefixActive,
        onOpenFiles = {
            if (filesSupported) {
                vm.selectConnectionTab(ConnectionTab.Files)
                ui.showTabSheet = false
            } else {
                onShowLocalShellFilesUnsupported()
            }
        },
        onOpenSettings = onOpenSettings,
        useSingleRowAccessoryBar = useSingleRowAccessoryBar,
        onSelectTab = {
            vm.selectTab(it)
            ui.showTabSheet = false
        },
        onCloseTab = vm::closeTab,
        onAddTab = {
            vm.duplicateActiveTab()
            ui.showTabSheet = false
        },
        onDismiss = { ui.showTabSheet = false },
        multiplexerEnabled = multiplexerEnabled,
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

package com.jossephus.chuchu.ui.screens.Terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.FileBrowserUiState
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.ChuchuKeyBindings
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.ResolvedAccessoryEntry
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryDispatcher
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.GhosttyTheme
import com.jossephus.chuchu.ui.theme.toRgbIntArray
import com.jossephus.chuchu.ui.theme.toTerminalPaletteBytes
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun TerminalConnectedContent(
    vm: TerminalViewModel,
    ui: TerminalScreenUiState,
    sessionState: SessionState,
    tabs: List<TabSession>,
    tabsForHost: List<TabSession>,
    activeTabId: String?,
    tabMode: TerminalTabMode,
    selectedTab: ConnectionTab,
    filesSupported: Boolean,
    fileBrowserState: FileBrowserUiState,
    ghosttyTheme: GhosttyTheme?,
    isDarkTheme: Boolean,
    multiplexerState: MultiplexerUiState,
    chuchuKeys: ChuchuKeyBindings,
    accessoryLayout: List<ResolvedAccessoryEntry>,
    useSingleRowAccessoryBar: Boolean,
    accessoryBarOnlyWithKeyboard: Boolean,
    customKeyGroups: List<TerminalCustomKeyGroup>,
    showCustomActionsFab: Boolean,
    disableAutocorrect: Boolean,
    activeHostCount: Int,
    currentHostName: String?,
    activeTabUsesMultiplexer: Boolean,
    tabStrip: @Composable () -> Unit,
    onNewConnection: () -> Unit,
    onShowLocalShellFilesUnsupported: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val colors = ChuColors.current
    var modifierState by remember { mutableStateOf(ModifierState()) }
    val inputViewRef = remember { mutableStateOf<TerminalInputView?>(null) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val clipboard = remember {
        context.getSystemService(ClipboardManager::class.java)
    }
    LaunchedEffect(sessionState.handle, activeTabId) {
        ui.clearSelection()
        menuSize = IntSize.Zero
    }

    fun pasteClipboard(): Boolean {
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return false
        }
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isNotEmpty()) {
            vm.onPasteText(modifierState.applyToText(text))
            ui.clearSelection()
            return true
        }
        return false
    }

    Box(modifier = modifier.fillMaxSize()) {
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

        val inputMethodManager = remember {
            context.getSystemService(InputMethodManager::class.java)
        }
        val requestInputFocus: () -> Unit = {
            inputViewRef.value?.let { view -> view.showKeyboard(inputMethodManager) }
        }
        var hasClipboardText by remember { mutableStateOf(false) }
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
            val text = ui.selectionState?.text ?: return
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(
                    context.getString(R.string.terminal_clipboard_selection_label),
                    text,
                )
            )
            Toast.makeText(
                    context,
                    context.getString(R.string.terminal_copied_selection),
                    Toast.LENGTH_SHORT,
                )
                .show()
            ui.clearSelection()
        }

        val transfers = rememberTerminalFileTransfers(vm)

        LaunchedEffect(Unit) {
            requestInputFocus()
            vm.onFocusChanged(true)
        }
        Column(
            modifier =
                Modifier.fillMaxSize()
                    // Only when a sheet is open: even a 0dp blur keeps the whole screen in
                    // its own graphics layer, re-recorded on every frame.
                    .then(if (ui.showTabSheet || ui.showGlobalTabManager) Modifier.blur(10.dp) else Modifier)
                    .imePadding(),
        ) {
            if (tabMode == TerminalTabMode.Strip) {
                tabStrip()
            }

            if (tabMode == TerminalTabMode.Strip && tabs.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TerminalEmptyTabsMessage(onNewConnection)
                }
            } else if (selectedTab == ConnectionTab.Files && filesSupported) {
                TerminalFilesPane(
                    vm = vm,
                    state = fileBrowserState,
                    transfers = transfers,
                    activeTabId = activeTabId,
                    clipboard = clipboard,
                    modifier = Modifier.weight(1f),
                )
            } else {
                TerminalViewport(
                    vm = vm,
                    ui = ui,
                    sessionState = sessionState,
                    ghosttyTheme = ghosttyTheme,
                    isReconnecting = sessionState.status == SessionStatus.Reconnecting,
                    duplicateLoading = multiplexerState.duplicateLoading,
                    tabs = tabs,
                    tabsForHost = tabsForHost,
                    tabMode = tabMode,
                    chuchuKeys = chuchuKeys,
                    modifierState = modifierState,
                    inputViewRef = inputViewRef,
                    inputMethodManager = inputMethodManager,
                    disableAutocorrect = disableAutocorrect,
                    menuSize = menuSize,
                    onMenuSizeChange = { menuSize = it },
                    hasClipboardText = hasClipboardText,
                    onCopySelection = ::copySelection,
                    onPaste = { pasteClipboard() },
                    requestInputFocus = requestInputFocus,
                    customKeyGroups = customKeyGroups,
                    showCustomActionsFab = showCustomActionsFab,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            if (selectedTab == ConnectionTab.Terminal) {
                TerminalBottomBar(
                    hostName = currentHostName,
                    showHostName = activeHostCount > 1,
                    localShellFilesMessage = ui.localShellFilesMessage,
                    accessoryLayout = accessoryLayout,
                    modifierState = modifierState,
                    onAccessoryAction = ::dispatchAccessoryAction,
                    onOpenSettings = onOpenSettings,
                    onChuchuKey = {
                        chuchuKeys.togglePrefix()
                        requestInputFocus()
                    },
                    chuchuKeyActive = chuchuKeys.isPrefixActive,
                    onOpenFiles = {
                        if (filesSupported) {
                            vm.selectConnectionTab(ConnectionTab.Files)
                        } else {
                            onShowLocalShellFilesUnsupported()
                        }
                    },
                    useSingleRow = useSingleRowAccessoryBar,
                    onlyWithKeyboard = accessoryBarOnlyWithKeyboard,
                )
            }
        }
    }

    fileBrowserState.uploadProgress?.let { progress ->
        UploadProgressDialog(progress = progress)
    }
    if (ui.showTabSheet) {
        TerminalCommandPaletteSheet(
            vm = vm,
            ui = ui,
            chuchuKeys = chuchuKeys,
            modifierState = modifierState,
            onModifierStateChange = { modifierState = it },
            inputViewRef = inputViewRef,
            tabsForHost = tabsForHost,
            activeTabId = activeTabId,
            accessoryLayout = accessoryLayout,
            useSingleRowAccessoryBar = useSingleRowAccessoryBar,
            filesSupported = filesSupported,
            onShowLocalShellFilesUnsupported = onShowLocalShellFilesUnsupported,
            onOpenSettings = onOpenSettings,
            multiplexerEnabled = activeTabUsesMultiplexer,
            multiplexerState = multiplexerState,
            onPaste = { pasteClipboard() },
        )
    }
}

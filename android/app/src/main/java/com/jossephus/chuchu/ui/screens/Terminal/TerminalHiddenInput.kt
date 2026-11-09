package com.jossephus.chuchu.ui.screens.Terminal

import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.terminal.ChuchuKeyBindings
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.terminal.TerminalSpecialKey

@Composable
internal fun TerminalHiddenInput(
    inputViewRef: MutableState<TerminalInputView?>,
    inputMethodManager: InputMethodManager?,
    disableAutocorrect: Boolean,
    onText: (String) -> Unit,
    onKey: (key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnText = rememberUpdatedState(onText)
    val currentOnKey = rememberUpdatedState(onKey)
    val currentOnFocusChanged = rememberUpdatedState(onFocusChanged)
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TerminalInputView(viewContext)
                .apply {
                    onTerminalText = { text -> currentOnText.value(text) }
                    onTerminalKey = { key, codepoint, mods, action, charCode ->
                        currentOnKey.value(key, codepoint, mods, action, charCode)
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        currentOnFocusChanged.value(hasFocus)
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
            view.disableAutocorrect = disableAutocorrect
        },
    )
}

internal fun handleTerminalKey(
    key: Int,
    codepoint: Int,
    mods: Int,
    action: Int,
    charCode: Int,
    ui: TerminalScreenUiState,
    tabs: List<TabSession>,
    tabsForHost: List<TabSession>,
    tabMode: TerminalTabMode,
    chuchuKeys: ChuchuKeyBindings,
    modifierState: ModifierState,
    vm: TerminalViewModel,
) {
    var shouldForwardToTerminal = true
    val overlayOpen = ui.showTabSheet || ui.showGlobalTabManager
    val overlayTabs = if (ui.showGlobalTabManager) tabs else tabsForHost
    if (
        overlayOpen &&
            overlayTabs.isEmpty() &&
            action == GhosttyKeyAction.Press &&
            key == TerminalSpecialKey.Escape.engineKey
    ) {
        ui.showTabSheet = false
        ui.showGlobalTabManager = false
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
                    ui.showTabSheet = false
                    ui.showGlobalTabManager = false
                }
                't' -> {
                    if (tabMode == TerminalTabMode.Strip) {
                        ui.showGlobalTabManager = true
                    } else {
                        ui.showTabSheet = true
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
                    ui.focusedTabIndex =
                        (ui.focusedTabIndex - 1).mod(
                            overlayTabs.size
                        )

                TerminalSpecialKey.Right.engineKey,
                TerminalSpecialKey.Down.engineKey ->
                    ui.focusedTabIndex =
                        (ui.focusedTabIndex + 1).mod(
                            overlayTabs.size
                        )

                TerminalSpecialKey.Enter
                    .engineKey -> {
                    overlayTabs
                        .getOrNull(ui.focusedTabIndex)
                        ?.let {
                            vm.selectTab(it.id)
                            ui.showTabSheet = false
                            ui.showGlobalTabManager = false
                        }
                }

                TerminalSpecialKey.Escape
                    .engineKey -> {
                    ui.showTabSheet = false
                    ui.showGlobalTabManager = false
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

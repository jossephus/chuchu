package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jossephus.chuchu.ui.terminal.TerminalCustomAction
import com.jossephus.chuchu.ui.terminal.TerminalSelection
import com.jossephus.chuchu.ui.terminal.TerminalSelectionState

@Stable
internal class TerminalScreenUiState(initialFontSizeSp: Float) {
    var selection by mutableStateOf<TerminalSelection?>(null)
    var selectionState by mutableStateOf<TerminalSelectionState?>(null)
    var showTabSheet by mutableStateOf(false)
    var showGlobalTabManager by mutableStateOf(false)
    var showNewTabPicker by mutableStateOf(false)
    var focusedTabIndex by mutableStateOf(0)
    var localShellFilesMessage by mutableStateOf<String?>(null)
    var terminalFontSizeSp by mutableStateOf(initialFontSizeSp)
    var fabFilteredActions by mutableStateOf<List<TerminalCustomAction>?>(null)

    fun clearSelection() {
        selection = null
        selectionState = null
    }
}

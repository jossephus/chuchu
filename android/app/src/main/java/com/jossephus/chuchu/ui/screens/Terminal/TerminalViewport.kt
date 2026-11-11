package com.jossephus.chuchu.ui.screens.Terminal

import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.terminal.ChuchuKeyBindings
import com.jossephus.chuchu.ui.terminal.CustomActionModifier
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.terminal.TerminalCustomAction
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.terminal.decodeCustomActionValue
import com.jossephus.chuchu.ui.terminal.modifierStateForCustomAction
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyTheme

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

@Composable
internal fun TerminalViewport(
    vm: TerminalViewModel,
    ui: TerminalScreenUiState,
    sessionState: SessionState,
    ghosttyTheme: GhosttyTheme?,
    isReconnecting: Boolean,
    duplicateLoading: Boolean,
    tabs: List<TabSession>,
    tabsForHost: List<TabSession>,
    tabMode: TerminalTabMode,
    chuchuKeys: ChuchuKeyBindings,
    // Read on each event: several key or text events can arrive within one frame, before recomposition.
    currentModifierState: () -> ModifierState,
    onModifierUsed: () -> Unit,
    inputViewRef: MutableState<TerminalInputView?>,
    inputMethodManager: InputMethodManager?,
    disableAutocorrect: Boolean,
    menuSize: IntSize,
    onMenuSizeChange: (IntSize) -> Unit,
    hasClipboardText: Boolean,
    onCopySelection: () -> Unit,
    onPaste: () -> Unit,
    requestInputFocus: () -> Unit,
    customKeyGroups: List<TerminalCustomKeyGroup>,
    showCustomActionsFab: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    // Read here rather than by the screen, so a new frame of output recomposes only the viewport.
    val liveState by vm.sessionState.collectAsStateWithLifecycle()
    val snapshot = liveState.snapshot ?: return

    Box(modifier = modifier) {
        TerminalCanvas(
            snapshot = snapshot,
            fontSizeSp = ui.terminalFontSizeSp,
            minFontSizeSp = SettingsRepository.MIN_TERMINAL_FONT_SIZE,
            maxFontSizeSp = SettingsRepository.MAX_TERMINAL_FONT_SIZE,
            cursorColor =
                ghosttyTheme?.cursorColor
                    ?: Color.White.copy(alpha = 0.28f),
            cursorTextColor = ghosttyTheme?.cursorText,
            selectionBackgroundColor =
                ghosttyTheme?.selectionBackground
                    ?: colors.accent.copy(alpha = 0.45f),
            selectionForegroundColor =
                ghosttyTheme?.selectionForeground ?: colors.onAccent,
            selection = ui.selection,
            onSelectionChange = { ui.selection = it },
            terminalHandle = sessionState.handle,
            modifier = Modifier.fillMaxSize(),
            onResize = vm::onCanvasSizeChanged,
            onTap = requestInputFocus,
            onPrimaryClick = vm::onPrimaryMouseClick,
            onAppSelectionDrag = vm::onAppSelectionDrag,
            onScroll = vm::onScroll,
            onFontSizeChange = { sizeSp -> ui.terminalFontSizeSp = sizeSp },
            onSelectionChanged = { state -> ui.selectionState = state },
        )

        TerminalStatusBadges(
            isReconnecting = isReconnecting,
            reconnectAttempt = sessionState.reconnectAttempt,
            duplicateLoading = duplicateLoading,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        val selState = ui.selectionState
        if (selState != null) {
            TerminalSelectionOverlay(
                selectionState = selState,
                selection = { ui.selection },
                onSelectionChange = { ui.selection = it },
                menuSize = menuSize,
                onMenuSizeChange = onMenuSizeChange,
                hasClipboardText = hasClipboardText,
                onCopy = onCopySelection,
                onPaste = onPaste,
            )
        }

        TerminalHiddenInput(
            inputViewRef = inputViewRef,
            inputMethodManager = inputMethodManager,
            disableAutocorrect = disableAutocorrect,
            onText = { text ->
                if (!chuchuKeys.handleText(text)) {
                    for ((run, runModifierState) in currentModifierState().textRuns(text)) {
                        vm.dispatchTextWithModifierState(run, runModifierState)
                    }
                    onModifierUsed()
                }
            },
            onKey = { key, codepoint, mods, action, charCode ->
                val forwarded =
                    handleTerminalKey(
                        key,
                        codepoint,
                        mods,
                        action,
                        charCode,
                        ui,
                        tabs,
                        tabsForHost,
                        tabMode,
                        chuchuKeys,
                        currentModifierState(),
                        vm,
                    )
                if (forwarded && action == GhosttyKeyAction.Press) {
                    onModifierUsed()
                }
            },
            onFocusChanged = vm::onFocusChanged,
            modifier = Modifier.align(Alignment.BottomStart).size(1.dp).alpha(0f),
        )

        if (customKeyGroups.isNotEmpty() &&
            (showCustomActionsFab || ui.fabFilteredActions != null)
        ) {
            TerminalCustomActionsFab(
                groups = customKeyGroups,
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
                filteredActions = ui.fabFilteredActions,
                onClearFilter = { ui.fabFilteredActions = null },
            )
        }

        ChuchuPrefixHints(
            chuchuKeys = chuchuKeys,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
    }
}

@Composable
internal fun TerminalStatusBadges(
    isReconnecting: Boolean,
    reconnectAttempt: Int,
    duplicateLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Row(
        modifier = modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isReconnecting) {
            ChuText(
                text =
                    if (reconnectAttempt > 0) {
                        stringResource(
                            R.string.terminal_reconnecting_attempt,
                            reconnectAttempt,
                        )
                    } else {
                        stringResource(R.string.terminal_reconnecting)
                    },
                style = typography.labelSmall,
                color = colors.error,
            )
        }
        if (duplicateLoading) {
            ChuText(
                text = stringResource(R.string.terminal_preparing_mux),
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChuchuPrefixHints(
    chuchuKeys: ChuchuKeyBindings,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    androidx.compose.animation.AnimatedVisibility(
        visible = chuchuKeys.isPrefixActive,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
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

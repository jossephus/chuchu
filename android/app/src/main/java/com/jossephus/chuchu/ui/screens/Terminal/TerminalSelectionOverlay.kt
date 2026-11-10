package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.terminal.TerminalSelection
import com.jossephus.chuchu.ui.terminal.TerminalSelectionHandle
import com.jossephus.chuchu.ui.terminal.TerminalSelectionState
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
internal fun TerminalSelectionOverlay(
    selectionState: TerminalSelectionState,
    selection: () -> TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    menuSize: IntSize,
    onMenuSizeChange: (IntSize) -> Unit,
    hasClipboardText: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val selState = selectionState
    val sel = selection()
    val isAnchorStart = sel != null && sel.anchorIndex <= sel.focusIndex
    TerminalSelectionHandle(
        tipX = selState.startOffset.x,
        tipY = selState.startOffset.y + selState.cellHeightPx / 2f,
        color = colors.accent,
        borderColor = colors.background,
        cellWidthPx = selState.cellWidthPx,
        cellHeightPx = selState.cellHeightPx,
        cols = selState.cols,
        startCellProvider = {
            val s = selection()
            if (s != null) minOf(s.anchorIndex, s.focusIndex) else 0
        },
        onDragToCell = { newCell ->
            onSelectionChange(selection()?.withStart(newCell, updateAnchor = isAnchorStart))
        },
    )
    TerminalSelectionHandle(
        tipX = selState.endOffset.x,
        tipY = selState.endOffset.y - selState.cellHeightPx / 2f,
        color = colors.accent,
        borderColor = colors.background,
        cellWidthPx = selState.cellWidthPx,
        cellHeightPx = selState.cellHeightPx,
        cols = selState.cols,
        startCellProvider = {
            val s = selection()
            if (s != null) maxOf(s.anchorIndex, s.focusIndex) else 0
        },
        onDragToCell = { newCell ->
            onSelectionChange(selection()?.withEnd(newCell, updateAnchor = !isAnchorStart))
        },
    )

    val menuGapPx = with(density) { 8.dp.toPx() }
    val selLeft = selState.boundsLeft
    val selRight = selState.boundsRight
    val selTop = selState.boundsTop
    val selBottom = selState.boundsBottom
    val centerX = (selLeft + selRight) / 2f
    val menuWidth = menuSize.width.coerceAtLeast(1)
    val menuHeight = menuSize.height.coerceAtLeast(1)
    val maxMenuX = (selState.canvasWidthPx - menuWidth).coerceAtLeast(0)
    val menuX = (centerX - menuWidth / 2f).toInt().coerceIn(0, maxMenuX)
    val aboveY = (selTop - menuHeight - menuGapPx).toInt()
    val belowY = (selBottom + menuGapPx).toInt()
    val maxMenuY = (selState.canvasHeightPx - menuHeight).coerceAtLeast(0)
    val menuY = if (aboveY >= 0) aboveY else belowY.coerceIn(0, maxMenuY)
    Row(
        modifier =
            Modifier.offset { IntOffset(menuX, menuY) }
                .onGloballyPositioned { onMenuSizeChange(it.size) }
                .background(colors.background)
                .border(1.dp, colors.border)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!selState.text.isNullOrEmpty()) {
            ChuButton(
                onClick = onCopy,
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
                    stringResource(R.string.action_copy),
                    style = typography.label,
                    color = colors.textMuted,
                )
            }
        }
        if (hasClipboardText) {
            ChuButton(
                onClick = onPaste,
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
                    stringResource(R.string.action_paste),
                    style = typography.label,
                    color = colors.textMuted,
                )
            }
        }
    }
}

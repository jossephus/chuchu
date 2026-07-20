package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.terminal.HerdrPaneStreamStatus
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.terminal.TerminalSelection
import com.jossephus.chuchu.ui.terminal.TerminalSelectionState
import com.jossephus.chuchu.ui.theme.ChuColorPalette
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyTheme

@Composable
fun HerdrSplitLayout(
    state: HerdrNativeUiState,
    fontSizeSp: Float,
    ghosttyTheme: GhosttyTheme?,
    colors: ChuColorPalette,
    selection: TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onSelectionChanged: (TerminalSelectionState?) -> Unit,
    onPaneTap: (String) -> Unit,
    onPaneViewport: (String, Int, Int, Int, Int, Int, Int) -> Unit,
    onPaneScroll: (String, Int) -> Unit,
    onTakeover: () -> Unit,
    requestInputFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.layout ?: return
    val focusedPaneId = state.focusedPaneId ?: layout.focusedPaneId
    val panes = if (layout.zoomed) {
        layout.panes.filter { it.paneId == focusedPaneId }
    } else {
        layout.panes
    }

    BoxWithConstraints(modifier = modifier) {
        panes.forEach { layoutPane ->
            val paneModifier = if (layout.zoomed) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .offset(
                        x = scaledOffset(layoutPane.rect.x - layout.area.x, layout.area.width, maxWidth),
                        y = scaledOffset(layoutPane.rect.y - layout.area.y, layout.area.height, maxHeight),
                    )
                    .width(scaledSize(layoutPane.rect.width, layout.area.width, maxWidth))
                    .height(scaledSize(layoutPane.rect.height, layout.area.height, maxHeight))
                    .padding(0.5.dp)
            }
            HerdrSplitPane(
                paneId = layoutPane.paneId,
                paneState = state.panes[layoutPane.paneId],
                focused = layoutPane.paneId == focusedPaneId,
                fontSizeSp = fontSizeSp,
                ghosttyTheme = ghosttyTheme,
                colors = colors,
                selection = selection,
                onSelectionChange = onSelectionChange,
                onSelectionChanged = onSelectionChanged,
                onPaneTap = onPaneTap,
                onPaneViewport = onPaneViewport,
                onPaneScroll = onPaneScroll,
                onTakeover = onTakeover,
                requestInputFocus = requestInputFocus,
                modifier = paneModifier,
            )
        }
    }
}

@Composable
private fun HerdrSplitPane(
    paneId: String,
    paneState: com.jossephus.chuchu.service.terminal.HerdrPaneState?,
    focused: Boolean,
    fontSizeSp: Float,
    ghosttyTheme: GhosttyTheme?,
    colors: ChuColorPalette,
    selection: TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onSelectionChanged: (TerminalSelectionState?) -> Unit,
    onPaneTap: (String) -> Unit,
    onPaneViewport: (String, Int, Int, Int, Int, Int, Int) -> Unit,
    onPaneScroll: (String, Int) -> Unit,
    onTakeover: () -> Unit,
    requestInputFocus: () -> Unit,
    modifier: Modifier,
) {
    val typography = ChuTypography.current
    val borderColor = if (focused) colors.accent else colors.border.copy(alpha = 0.55f)
    val borderWidth = if (focused) 2.dp else 1.dp
    Box(modifier = modifier.border(borderWidth, borderColor)) {
        val snapshot = paneState?.snapshot
        if (snapshot == null) {
            val message = if (paneState?.status == HerdrPaneStreamStatus.Error) {
                paneState.error ?: "pane stream failed"
            } else {
                "connecting…"
            }
            ChuText(
                message,
                style = typography.labelSmall,
                color = if (paneState?.status == HerdrPaneStreamStatus.Error) colors.error else colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            TerminalCanvas(
                snapshot = snapshot,
                fitSnapshotToCanvas = false,
                terminalHandle = paneState.handle,
                enableGestures = focused,
                fontSizeSp = fontSizeSp,
                cursorColor = ghosttyTheme?.cursorColor ?: Color.White.copy(alpha = 0.28f),
                cursorTextColor = ghosttyTheme?.cursorText,
                selectionBackgroundColor = ghosttyTheme?.selectionBackground ?: colors.accent.copy(alpha = 0.45f),
                selectionForegroundColor = ghosttyTheme?.selectionForeground ?: colors.onAccent,
                selection = if (focused) selection else null,
                onSelectionChange = if (focused) onSelectionChange else { _: TerminalSelection? -> },
                onResize = { cols, rows, cellWidth, cellHeight, widthPx, heightPx ->
                    onPaneViewport(paneId, cols, rows, cellWidth, cellHeight, widthPx, heightPx)
                },
                onTap = {
                    onPaneTap(paneId)
                    requestInputFocus()
                },
                onPrimaryClick = { _, _ -> },
                onAppSelectionDrag = { _, _, _ -> },
                onScroll = { delta, _, _ -> onPaneScroll(paneId, delta) },
                onZoom = {},
                onSelectionChanged = if (focused) onSelectionChanged else { _: TerminalSelectionState? -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!focused) {
            Box(
                modifier = Modifier.fillMaxSize().clickable {
                    onPaneTap(paneId)
                    requestInputFocus()
                },
            )
        }
        if (paneState?.readOnly == true && focused) {
            ChuButton(
                onClick = onTakeover,
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                borderColor = colors.textMuted,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                ChuText("read-only — tap to take over", style = typography.labelSmall, color = colors.textMuted)
            }
        }
    }
}

private fun scaledOffset(value: Int, extent: Int, container: Dp): Dp =
    if (extent > 0) container * (value.toFloat() / extent) else 0.dp

private fun scaledSize(value: Int, extent: Int, container: Dp): Dp =
    if (extent > 0) (container * (value.toFloat() / extent)).coerceAtLeast(0.dp) else 0.dp

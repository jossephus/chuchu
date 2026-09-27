package com.jossephus.chuchu.plugins.herdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.plugin.api.LocalPluginTheme
import com.jossephus.chuchu.plugin.api.PluginTheme
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrAgentStatus
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrSplitDirection
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrTabLayout

/** The terminal area of a herdr tab in native mode: herdr's tabs and its split layout. */
@Composable
internal fun HerdrSessionView(controller: HerdrController, modifier: Modifier) {
    val theme = LocalPluginTheme.current
    val state by controller.state.collectAsState()
    val panes by controller.panes.collectAsState()
    // The keyboard follows an explicit tap on the focused pane, like the stock terminal,
    // instead of popping up whenever herdr moves focus.
    var keyboardPaneId by remember { mutableStateOf<String?>(null) }
    // Like PR #69, a herdr tab opens on the switcher home; picking a workspace, tab or agent
    // drops into its panes.
    var homeVisible by remember { mutableStateOf(true) }

    Column(modifier.background(theme.background)) {
        HerdrTopBar(
            controller = controller,
            state = state,
            panes = panes,
            homeVisible = homeVisible,
            onHomeVisibleChange = { homeVisible = it },
        )
        state.actionError?.let { message ->
            Banner(message, theme.error, onClick = controller::dismissActionError)
        }
        val snapshot = state.snapshot
        val layout = snapshot?.layouts?.firstOrNull { it.tabId == state.focusedTabId }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                homeVisible && snapshot != null ->
                    HerdrHome(
                        snapshot = snapshot,
                        sessionName = controller.hostName,
                        onEnterWorkspace = { workspaceId ->
                            homeVisible = false
                            controller.focusWorkspace(workspaceId)
                        },
                        onEnterTab = { tabId ->
                            homeVisible = false
                            controller.focusTab(tabId)
                        },
                        onEnterAgent = { paneId, tabId ->
                            homeVisible = false
                            controller.focusAgent(paneId, tabId)
                        },
                        onCreateWorkspace = controller::createWorkspace,
                        onCloseWorkspace = controller::closeWorkspace,
                    )
                layout != null -> {
                    val focusedPaneId = state.focusedPaneId ?: layout.focusedPaneId
                    HerdrSplitLayout(
                        layout = layout,
                        panes = panes,
                        focusedPaneId = focusedPaneId,
                        keyboardPaneId = keyboardPaneId,
                        onPaneTap = { paneId ->
                            if (paneId == focusedPaneId) {
                                keyboardPaneId = paneId
                            } else {
                                // Keep the keyboard up if it was: typing moves with focus.
                                if (keyboardPaneId != null) keyboardPaneId = paneId
                                controller.focusPane(paneId)
                            }
                        },
                    )
                }
                else ->
                    CenteredText(
                        state.error ?: if (snapshot == null) "connecting to herdr…" else "no herdr tab selected",
                        if (state.error != null) theme.error else theme.textMuted,
                    )
            }
        }
    }
}

@Composable
private fun HerdrTopBar(
    controller: HerdrController,
    state: HerdrUiState,
    panes: Map<String, HerdrPane>,
    homeVisible: Boolean,
    onHomeVisibleChange: (Boolean) -> Unit,
) {
    val theme = LocalPluginTheme.current
    val snapshot = state.snapshot ?: return
    var menu by remember { mutableStateOf<TopBarMenu?>(null) }
    val workspace = snapshot.workspaces.firstOrNull { it.workspaceId == snapshot.focusedWorkspaceId }
    val tabs = snapshot.tabs.filter { it.workspaceId == snapshot.focusedWorkspaceId }.sortedBy { it.number }
    val focusedPaneId = state.focusedPaneId ?: snapshot.layouts.firstOrNull { it.tabId == state.focusedTabId }?.focusedPaneId
    val readOnly = focusedPaneId?.let { panes[it] }?.state?.collectAsState()?.value?.readOnly == true

    Column(Modifier.fillMaxWidth().background(theme.surface)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(text = "⌂ herdr", selected = homeVisible, onClick = { onHomeVisibleChange(!homeVisible) })
            Chip(
                text = workspace?.label?.takeIf { it.isNotBlank() } ?: "workspace ${workspace?.number ?: "?"}",
                selected = menu == TopBarMenu.Workspaces,
                onClick = { menu = if (menu == TopBarMenu.Workspaces) null else TopBarMenu.Workspaces },
            )
            tabs.forEach { tab ->
                Chip(
                    text = tab.label?.takeIf { it.isNotBlank() } ?: "${tab.number}",
                    selected = !homeVisible && tab.tabId == state.focusedTabId,
                    dot = statusColor(tab.agentStatus, theme),
                    onClick = {
                        onHomeVisibleChange(false)
                        controller.focusTab(tab.tabId)
                    },
                )
            }
            Chip(text = "+", selected = menu == TopBarMenu.Actions, onClick = {
                menu = if (menu == TopBarMenu.Actions) null else TopBarMenu.Actions
            })
            if (readOnly) {
                Chip(text = "read-only · take over", selected = false, onClick = controller::takeoverFocusedPane)
            }
        }
        when (menu) {
            TopBarMenu.Workspaces ->
                MenuRow(
                    snapshot.workspaces.sortedBy { it.number }.map { ws ->
                        (ws.label?.takeIf { it.isNotBlank() } ?: "workspace ${ws.number}") to {
                            onHomeVisibleChange(false)
                            controller.focusWorkspace(ws.workspaceId)
                            menu = null
                        }
                    },
                )
            TopBarMenu.Actions ->
                MenuRow(
                    listOf(
                        "split →" to { controller.splitFocused(HerdrSplitDirection.Right) },
                        "split ↓" to { controller.splitFocused(HerdrSplitDirection.Down) },
                        "close pane" to controller::closeFocusedPane,
                        "new tab" to controller::newTab,
                    ).map { (label, action) ->
                        label to {
                            action()
                            menu = null
                        }
                    },
                )
            null -> Unit
        }
    }
}

private enum class TopBarMenu { Workspaces, Actions }

/**
 * herdr's layout for one tab, scaled from herdr's cell rectangles to the available space.
 * Ported from `HerdrSplitLayout` in chuchu PR #69.
 */
@Composable
private fun HerdrSplitLayout(
    layout: HerdrTabLayout,
    panes: Map<String, HerdrPane>,
    focusedPaneId: String?,
    keyboardPaneId: String?,
    onPaneTap: (String) -> Unit,
) {
    val theme = LocalPluginTheme.current
    val visible = if (layout.zoomed) layout.panes.filter { it.paneId == focusedPaneId } else layout.panes
    val multiPane = visible.size > 1
    BoxWithConstraints(
        Modifier.fillMaxSize().then(if (multiPane) Modifier.background(theme.border) else Modifier),
    ) {
        visible.forEach { layoutPane ->
            val paneModifier =
                if (layout.zoomed) {
                    Modifier.fillMaxSize()
                } else {
                    val left = scaled(layoutPane.rect.x - layout.area.x, layout.area.width, maxWidth)
                    val right = scaled(layoutPane.rect.x + layoutPane.rect.width - layout.area.x, layout.area.width, maxWidth)
                    val top = scaled(layoutPane.rect.y - layout.area.y, layout.area.height, maxHeight)
                    val bottom = scaled(layoutPane.rect.y + layoutPane.rect.height - layout.area.y, layout.area.height, maxHeight)
                    Modifier.offset(x = left, y = top)
                        .width((right - left).coerceAtLeast(0.dp))
                        .height((bottom - top).coerceAtLeast(0.dp))
                        .padding(if (multiPane) 0.5.dp else 0.dp)
                }
            HerdrPaneView(
                pane = panes[layoutPane.paneId],
                focused = layoutPane.paneId == focusedPaneId,
                keyboard = layoutPane.paneId == keyboardPaneId,
                onTap = { onPaneTap(layoutPane.paneId) },
                modifier = paneModifier,
            )
        }
    }
}

@Composable
private fun HerdrPaneView(
    pane: HerdrPane?,
    focused: Boolean,
    keyboard: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalPluginTheme.current
    Box(modifier.background(theme.background)) {
        if (pane == null) {
            CenteredText("connecting…", theme.textMuted)
            return@Box
        }
        val state by pane.state.collectAsState()
        pane.terminal.Content(Modifier.fillMaxSize(), focused = focused && keyboard, onTap = onTap)
        if (state.status != HerdrPaneStatus.Streaming) {
            CenteredText(
                if (state.status == HerdrPaneStatus.Error) state.error ?: "pane stream failed" else "connecting…",
                if (state.status == HerdrPaneStatus.Error) theme.error else theme.textMuted,
            )
        }
        if (!focused) {
            // Dim unfocused panes; a tap focuses them instead of reaching the terminal.
            Box(Modifier.fillMaxSize().background(theme.background.copy(alpha = 0.4f)).clickable(onClick = onTap))
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, dot: Color? = null) {
    val theme = LocalPluginTheme.current
    Row(
        Modifier.border(1.dp, if (selected) theme.accent else theme.border)
            .background(if (selected) theme.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        dot?.let { Box(Modifier.size(6.dp).background(it)) }
        BasicText(text, style = theme.label.copy(color = if (selected) theme.accent else theme.textSecondary))
    }
}

@Composable
private fun MenuRow(items: List<Pair<String, () -> Unit>>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (label, action) -> Chip(text = label, selected = false, onClick = action) }
    }
}

@Composable
private fun Banner(message: String, color: Color, onClick: () -> Unit) {
    val theme = LocalPluginTheme.current
    BasicText(
        message,
        style = theme.small.copy(color = color),
        modifier = Modifier.fillMaxWidth().background(theme.surface).clickable(onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun CenteredText(text: String, color: Color) {
    val theme = LocalPluginTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text, style = theme.small.copy(color = color), modifier = Modifier.padding(16.dp))
    }
}

// herdr's attention model: blocked needs you now, done finished unseen, working is busy.
internal fun statusColor(status: HerdrAgentStatus, theme: PluginTheme): Color? =
    when (status) {
        HerdrAgentStatus.Blocked -> theme.error
        HerdrAgentStatus.Done -> theme.success
        HerdrAgentStatus.Working -> theme.warning
        HerdrAgentStatus.Idle, HerdrAgentStatus.Unknown -> null
    }

private fun scaled(value: Int, extent: Int, container: Dp): Dp =
    if (extent > 0) container * (value.toFloat() / extent) else 0.dp

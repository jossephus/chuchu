package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.multiplexer.HerdrAgentStatus
import com.jossephus.chuchu.service.multiplexer.HerdrControlState
import com.jossephus.chuchu.service.multiplexer.HerdrPane
import com.jossephus.chuchu.service.multiplexer.HerdrSnapshot
import com.jossephus.chuchu.service.multiplexer.HerdrTab
import com.jossephus.chuchu.service.multiplexer.HerdrWorkspace
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuColorPalette
import com.jossephus.chuchu.ui.theme.ChuTypography

internal fun herdrAgentStatusColor(status: HerdrAgentStatus, colors: ChuColorPalette): Color =
    when (status) {
        HerdrAgentStatus.Working -> colors.accent
        HerdrAgentStatus.Blocked -> colors.error
        HerdrAgentStatus.Done -> colors.success
        HerdrAgentStatus.Idle -> colors.textMuted
        HerdrAgentStatus.Unknown -> colors.disabledText
    }

@Composable
fun HerdrPanel(
    state: HerdrControlState,
    onFocusTab: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onCreateTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRefresh: () -> Unit,
    actionError: String?,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Column(modifier = modifier.fillMaxWidth()) {
        when (state) {
            HerdrControlState.Inactive -> {
                ChuText(
                    "herdr controls inactive",
                    style = typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            HerdrControlState.Connecting -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChuText("herdr", style = typography.body, color = colors.textSecondary)
                    ChuText("connecting …", style = typography.labelSmall, color = colors.textMuted)
                }
            }

            is HerdrControlState.Error -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChuText(
                        state.message,
                        style = typography.labelSmall,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    HerdrTextButton("retry", colors.textMuted, onRefresh)
                }
            }

            is HerdrControlState.Active -> {
                HerdrActivePanel(
                    snapshot = state.snapshot,
                    protocolWarning = state.protocolWarning,
                    onFocusTab = onFocusTab,
                    onFocusPane = onFocusPane,
                    onCreateTab = onCreateTab,
                    onCloseTab = onCloseTab,
                    onRefresh = onRefresh,
                )
            }
        }

        if (actionError != null) {
            ChuText(
                actionError,
                style = typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun HerdrActivePanel(
    snapshot: HerdrSnapshot,
    protocolWarning: Boolean,
    onFocusTab: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onCreateTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("herdr", style = typography.body, color = colors.textSecondary)
        Spacer(modifier = Modifier.weight(1f))
        ChuText("${snapshot.workspaces.size}", style = typography.labelSmall, color = colors.textMuted)
        Spacer(modifier = Modifier.size(8.dp))
        HerdrTextButton("refresh", colors.textMuted, onRefresh)
    }
    if (protocolWarning) {
        ChuText(
            "herdr protocol newer than supported",
            style = typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
    if (snapshot.workspaces.isEmpty()) {
        ChuText(
            "no herdr workspaces",
            style = typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        items(snapshot.workspaces, key = { it.workspaceId }) { workspace ->
            HerdrWorkspaceGroup(
                workspace = workspace,
                tabs = snapshot.tabs.filter { it.workspaceId == workspace.workspaceId },
                panes = snapshot.panes,
                onFocusTab = onFocusTab,
                onFocusPane = onFocusPane,
                onCreateTab = onCreateTab,
                onCloseTab = onCloseTab,
            )
        }
    }
}

@Composable
private fun HerdrWorkspaceGroup(
    workspace: HerdrWorkspace,
    tabs: List<HerdrTab>,
    panes: List<HerdrPane>,
    onFocusTab: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onCreateTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val label = workspace.label?.takeIf { it.isNotBlank() } ?: "workspace ${workspace.number}"

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuText(label, style = typography.label, color = colors.textPrimary, modifier = Modifier.weight(1f))
        HerdrStatusBadge(workspace.agentStatus)
        HerdrTextButton("+", colors.accent) { onCreateTab(workspace.workspaceId) }
    }
    tabs.forEach { tab ->
        HerdrTabRow(
            tab = tab,
            panes = panes.filter { it.tabId == tab.tabId },
            onFocusTab = onFocusTab,
            onFocusPane = onFocusPane,
            onCloseTab = onCloseTab,
        )
    }
}

@Composable
private fun HerdrTabRow(
    tab: HerdrTab,
    panes: List<HerdrPane>,
    onFocusTab: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onCloseTab: (String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val label = tab.label?.takeIf { it.isNotBlank() } ?: "tab ${tab.number}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(if (tab.focused) colors.surfaceVariant else Color.Transparent)
            .border(1.dp, if (tab.focused) colors.accent.copy(alpha = 0.65f) else colors.border.copy(alpha = 0.35f))
            .clickable { onFocusTab(tab.tabId) }
            .padding(start = 20.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuText(label, style = typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        ChuText("${tab.paneCount}", style = typography.labelSmall, color = colors.textMuted)
        HerdrStatusBadge(tab.agentStatus)
        HerdrTextButton("×", colors.textMuted) { onCloseTab(tab.tabId) }
    }
    panes.forEach { pane ->
        HerdrPaneRow(pane, onFocusPane)
    }
}

@Composable
private fun HerdrPaneRow(pane: HerdrPane, onFocusPane: (String) -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val agent = pane.agent?.takeIf { it.isNotBlank() } ?: "shell"
    val title = pane.terminalTitleStripped?.takeIf { it.isNotBlank() }
    val label = if (title == null) agent else "$agent · $title"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(if (pane.focused) colors.surfaceVariant else Color.Transparent)
            .border(1.dp, if (pane.focused) colors.accent.copy(alpha = 0.5f) else colors.border.copy(alpha = 0.2f))
            .clickable { onFocusPane(pane.paneId) }
            .padding(start = 36.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuText(label, style = typography.labelSmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        HerdrStatusBadge(pane.agentStatus)
    }
}

@Composable
private fun HerdrStatusBadge(status: HerdrAgentStatus) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val color = herdrAgentStatusColor(status, colors)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(6.dp).background(color))
        ChuText(status.name.lowercase(), style = typography.labelSmall, color = color)
    }
}

@Composable
private fun HerdrTextButton(label: String, color: Color, onClick: () -> Unit) {
    val typography = ChuTypography.current
    ChuButton(
        onClick = onClick,
        variant = ChuButtonVariant.Ghost,
        bracketed = true,
        borderColor = color,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) {
        ChuText(label, style = typography.labelSmall, color = color)
    }
}

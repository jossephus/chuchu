package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.multiplexer.HerdrAgent
import com.jossephus.chuchu.service.multiplexer.HerdrAgentStatus
import com.jossephus.chuchu.service.multiplexer.HerdrSnapshot
import com.jossephus.chuchu.service.multiplexer.HerdrWorkspace
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColorPalette
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun HerdrSwitcherHome(
    snapshot: HerdrSnapshot,
    onEnterWorkspace: (String) -> Unit,
    onEnterAgent: (agentPaneId: String, tabId: String) -> Unit,
    colors: ChuColorPalette,
    modifier: Modifier = Modifier,
    sessionHint: String? = null,
    connections: List<TabSession> = emptyList(),
    activeConnectionId: String? = null,
    onSelectConnection: (String) -> Unit = {},
    onOpenServerList: () -> Unit = {},
) {
    val typography = ChuTypography.current
    val workspaces = snapshot.workspaces.sortedBy { it.number }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChuText("herdr", style = typography.body, color = colors.textPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            ChuText(
                text = sessionHint?.takeIf { it.isNotBlank() } ?: "session ${snapshot.version}",
                style = typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "connections") {
                HerdrConnections(
                    connections = connections,
                    activeConnectionId = activeConnectionId,
                    onSelectConnection = onSelectConnection,
                    onOpenServerList = onOpenServerList,
                )
            }
            if (workspaces.isEmpty()) {
                item(key = "no-workspaces") {
                    ChuText(
                        "no herdr workspaces",
                        style = typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            } else {
                items(workspaces, key = { it.workspaceId }) { workspace ->
                    HerdrSwitcherWorkspace(
                        workspace = workspace,
                        agents = snapshot.agents.filter { it.workspaceId == workspace.workspaceId },
                        onEnterWorkspace = onEnterWorkspace,
                        onEnterAgent = onEnterAgent,
                    )
                }
            }
        }
    }
}

@Composable
private fun HerdrConnections(
    connections: List<TabSession>,
    activeConnectionId: String?,
    onSelectConnection: (String) -> Unit,
    onOpenServerList: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("connections", style = typography.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
        ChuText(
            "server list",
            style = typography.labelSmall,
            color = colors.accent,
            modifier = Modifier.clickable { onOpenServerList() }.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    connections.forEach { tab ->
        val active = tab.id == activeConnectionId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .background(if (active) colors.surfaceVariant else Color.Transparent)
                .border(
                    1.dp,
                    if (active) colors.accent.copy(alpha = 0.5f) else colors.border.copy(alpha = 0.2f),
                )
                .clickable { onSelectConnection(tab.id) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuText(
                terminalTabDisplayLabel(tab),
                style = typography.body,
                color = if (active) colors.accent else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HerdrSwitcherWorkspace(
    workspace: HerdrWorkspace,
    agents: List<HerdrAgent>,
    onEnterWorkspace: (String) -> Unit,
    onEnterAgent: (agentPaneId: String, tabId: String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val label = workspace.label?.takeIf { it.isNotBlank() } ?: "workspace ${workspace.number}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(if (workspace.focused) colors.surfaceVariant else Color.Transparent)
            .border(
                1.dp,
                if (workspace.focused) colors.accent.copy(alpha = 0.65f) else colors.border.copy(alpha = 0.35f),
            )
            .clickable { onEnterWorkspace(workspace.workspaceId) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuText(label, style = typography.label, color = colors.textPrimary, modifier = Modifier.weight(1f))
        HerdrSwitcherStatus(workspace.agentStatus)
        ChuText(
            "${workspace.tabCount} tabs · ${workspace.paneCount} panes",
            style = typography.labelSmall,
            color = colors.textMuted,
        )
    }
    agents.forEach { agent ->
        HerdrSwitcherAgent(agent, onEnterAgent)
    }
}

@Composable
private fun HerdrSwitcherAgent(
    agent: HerdrAgent,
    onEnterAgent: (agentPaneId: String, tabId: String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val name = agent.agent?.takeIf { it.isNotBlank() } ?: "shell"
    val cwd = agent.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 12.dp, top = 1.dp, bottom = 1.dp)
            .background(if (agent.focused) colors.surfaceVariant else Color.Transparent)
            .border(
                1.dp,
                if (agent.focused) colors.accent.copy(alpha = 0.5f) else colors.border.copy(alpha = 0.2f),
            )
            .clickable { onEnterAgent(agent.paneId, agent.tabId) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChuText(name, style = typography.body, color = colors.textSecondary)
                Spacer(modifier = Modifier.width(8.dp))
                HerdrSwitcherStatus(agent.agentStatus)
            }
            agent.terminalTitleStripped?.takeIf { it.isNotBlank() }?.let { title ->
                ChuText(
                    title,
                    style = typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        cwd?.let {
            ChuText(
                it,
                style = typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HerdrSwitcherStatus(status: HerdrAgentStatus) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val color = herdrAgentStatusColor(status, colors)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(6.dp).background(color))
        ChuText(status.name.lowercase(), style = typography.labelSmall, color = color)
    }
}

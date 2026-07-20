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
    onCreateWorkspace: () -> Unit = {},
    onCloseWorkspace: (workspaceId: String, label: String) -> Unit = { _, _ -> },
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

        val needsYou = snapshot.agents
            .filter { it.agentStatus == HerdrAgentStatus.Blocked || it.agentStatus == HerdrAgentStatus.Done }
            .sortedBy { if (it.agentStatus == HerdrAgentStatus.Blocked) 0 else 1 }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (needsYou.isNotEmpty()) {
                item(key = "needs-you") {
                    HerdrNeedsYou(agents = needsYou, onEnterAgent = onEnterAgent)
                }
            }
            item(key = "connections") {
                HerdrConnections(
                    connections = connections,
                    activeConnectionId = activeConnectionId,
                    onSelectConnection = onSelectConnection,
                    onOpenServerList = onOpenServerList,
                )
            }
            item(key = "workspaces-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChuText("workspaces", style = typography.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    ChuText(
                        "+ new",
                        style = typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier.clickable { onCreateWorkspace() }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
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
                    val workspaceAgents = snapshot.agents.filter { it.workspaceId == workspace.workspaceId }
                    val hasAttention = workspaceAgents.any {
                        it.agentStatus == HerdrAgentStatus.Blocked || it.agentStatus == HerdrAgentStatus.Done
                    }
                    HerdrSwitcherWorkspace(
                        workspace = workspace,
                        agents = workspaceAgents,
                        expanded = workspace.focused || hasAttention,
                        onEnterWorkspace = onEnterWorkspace,
                        onEnterAgent = onEnterAgent,
                        onCloseWorkspace = onCloseWorkspace,
                    )
                }
            }
        }
    }
}

@Composable
private fun HerdrNeedsYou(
    agents: List<HerdrAgent>,
    onEnterAgent: (agentPaneId: String, tabId: String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    ChuText(
        "needs you",
        style = typography.label,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    )
    agents.forEach { agent ->
        val color = herdrAgentStatusColor(agent.agentStatus, colors)
        val name = agent.agent?.takeIf { it.isNotBlank() } ?: "shell"
        val title = agent.terminalTitleStripped?.let { cleanAgentTitle(it, "") }?.takeIf { it.isNotBlank() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .border(1.dp, color.copy(alpha = 0.55f))
                .clickable { onEnterAgent(agent.paneId, agent.tabId) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.size(6.dp).background(color))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChuText(name, style = typography.body, color = colors.textSecondary)
                    ChuText(agent.agentStatus.name.lowercase(), style = typography.labelSmall, color = color)
                }
                title?.let {
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
    val duplicateHosts = connections
        .groupBy { it.spec.hostId ?: it.spec.sessionKey }
        .filterValues { it.size > 1 }
        .keys
    connections.forEach { tab ->
        val active = tab.id == activeConnectionId
        val serverName = tab.spec.tabLabel
        val ambiguous = (tab.spec.hostId ?: tab.spec.sessionKey) in duplicateHosts
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
                serverName,
                style = typography.body,
                color = if (active) colors.accent else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (ambiguous) {
                ChuText(
                    terminalTabAlias(tab),
                    style = typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HerdrSwitcherWorkspace(
    workspace: HerdrWorkspace,
    agents: List<HerdrAgent>,
    expanded: Boolean,
    onEnterWorkspace: (String) -> Unit,
    onEnterAgent: (agentPaneId: String, tabId: String) -> Unit,
    onCloseWorkspace: (workspaceId: String, label: String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val label = workspace.label?.takeIf { it.isNotBlank() } ?: "workspace ${workspace.number}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 3.dp)
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
        HerdrAgentStatusSummary(agents)
        ChuText(
            "${pluralCount(workspace.tabCount, "tab")} · ${pluralCount(workspace.paneCount, "pane")}",
            style = typography.labelSmall,
            color = colors.textMuted,
        )
        ChuText(
            "✕",
            style = typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier
                .clickable { onCloseWorkspace(workspace.workspaceId, label) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    if (expanded) {
        agents.forEach { agent ->
            HerdrSwitcherAgent(agent, label, onEnterAgent)
        }
    }
}

@Composable
private fun HerdrAgentStatusSummary(agents: List<HerdrAgent>) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val order = listOf(
        HerdrAgentStatus.Blocked,
        HerdrAgentStatus.Working,
        HerdrAgentStatus.Done,
        HerdrAgentStatus.Idle,
    )
    val counts = order.mapNotNull { status ->
        val count = agents.count { it.agentStatus == status }
        if (count > 0) status to count else null
    }
    if (counts.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        counts.forEach { (status, count) ->
            val color = herdrAgentStatusColor(status, colors)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(modifier = Modifier.size(6.dp).background(color))
                ChuText("$count ${status.name.lowercase()}", style = typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
private fun HerdrSwitcherAgent(
    agent: HerdrAgent,
    workspaceLabel: String,
    onEnterAgent: (agentPaneId: String, tabId: String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val name = agent.agent?.takeIf { it.isNotBlank() } ?: "shell"
    val cwd = agent.cwd?.trimEnd('/')?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() && !it.equals(workspaceLabel, ignoreCase = true) }
    val title = agent.terminalTitleStripped?.let { cleanAgentTitle(it, workspaceLabel) }?.takeIf { it.isNotBlank() }

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
            title?.let {
                ChuText(
                    it,
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

private fun pluralCount(count: Int, unit: String): String =
    "$count $unit${if (count == 1) "" else "s"}"

private fun cleanAgentTitle(title: String, workspaceLabel: String): String {
    var cleaned = title
    val colon = cleaned.indexOf(": ")
    if (colon in 1..40) cleaned = cleaned.substring(colon + 2)
    if (workspaceLabel.isNotBlank()) {
        val suffix = " - $workspaceLabel"
        if (cleaned.endsWith(suffix, ignoreCase = true)) {
            cleaned = cleaned.dropLast(suffix.length)
        }
    }
    return cleaned.trim().ifBlank { title }
}

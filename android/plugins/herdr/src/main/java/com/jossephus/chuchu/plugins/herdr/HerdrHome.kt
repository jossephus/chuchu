package com.jossephus.chuchu.plugins.herdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.plugin.api.LocalPluginTheme
import com.jossephus.chuchu.plugin.api.PluginTheme
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrAgent
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrAgentStatus
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrSnapshot
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrTab
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrWorkspace

/**
 * herdr's switcher home: agents that need you first, then every workspace with its agent
 * counts, expandable in place to jump straight to a tab or an agent's pane.
 *
 * Ported from `HerdrSwitcherHome` in chuchu PR #69. Its "connections" section (switching
 * chuchu tabs, opening the server list) isn't here: that's chuchu's own navigation.
 */
@Composable
internal fun HerdrHome(
    snapshot: HerdrSnapshot,
    sessionName: String,
    onEnterWorkspace: (workspaceId: String) -> Unit,
    onEnterTab: (tabId: String) -> Unit,
    onEnterAgent: (paneId: String, tabId: String) -> Unit,
    onCreateWorkspace: () -> Unit,
    onCloseWorkspace: (workspaceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPluginTheme.current
    val workspaces = snapshot.workspaces.sortedBy { it.number }
    // User overrides for expand/collapse; absent means auto (focused or needs attention).
    val expandOverrides = remember { mutableStateMapOf<String, Boolean>() }
    // Closing a workspace terminates its agents, so it asks first.
    var confirmClose by remember { mutableStateOf<HerdrWorkspace?>(null) }
    val needsYou =
        snapshot.agents
            .filter { it.agentStatus.needsAttention }
            .sortedBy { if (it.agentStatus == HerdrAgentStatus.Blocked) 0 else 1 }

    LazyColumn(modifier.fillMaxSize().background(theme.background)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("herdr", theme.body, theme.textPrimary)
                Text(sessionName, theme.small, theme.textMuted, Modifier.padding(start = 8.dp))
            }
        }
        if (needsYou.isNotEmpty()) {
            item(key = "needs-you-header") { SectionHeader("needs you") }
            items(needsYou, key = { "needs-" + it.paneId }) { agent ->
                val color = statusColorOrMuted(agent.agentStatus, theme)
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .border(1.dp, color.copy(alpha = 0.55f))
                        .clickable { onEnterAgent(agent.paneId, agent.tabId) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).background(color))
                    AgentText(agent, workspaceLabel = "", modifier = Modifier.weight(1f))
                }
            }
        }
        item(key = "workspaces-header") {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("workspaces", theme.label, theme.textSecondary, Modifier.weight(1f))
                Text("+ new", theme.small, theme.accent, Modifier.clickable(onClick = onCreateWorkspace).padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
        confirmClose?.let { workspace ->
            item(key = "confirm-close") {
                ConfirmClose(
                    label = workspace.displayLabel,
                    onConfirm = {
                        onCloseWorkspace(workspace.workspaceId)
                        confirmClose = null
                    },
                    onCancel = { confirmClose = null },
                )
            }
        }
        if (workspaces.isEmpty()) {
            item(key = "empty") { Text("no herdr workspaces", theme.small, theme.textMuted, Modifier.padding(12.dp)) }
        }
        items(workspaces, key = { it.workspaceId }) { workspace ->
            val agents = snapshot.agents.filter { it.workspaceId == workspace.workspaceId }
            val tabs = snapshot.tabs.filter { it.workspaceId == workspace.workspaceId }.sortedBy { it.number }
            val expanded = expandOverrides[workspace.workspaceId] ?: (workspace.focused || agents.any { it.agentStatus.needsAttention })
            Column {
                WorkspaceRow(
                    workspace = workspace,
                    agents = agents,
                    expanded = expanded,
                    onToggle = { expandOverrides[workspace.workspaceId] = !expanded },
                    onEnter = { onEnterWorkspace(workspace.workspaceId) },
                    onClose = { confirmClose = workspace },
                )
                if (expanded) {
                    tabs.forEach { tab ->
                        val tabAgents = agents.filter { it.tabId == tab.tabId }
                        if (tabAgents.isEmpty()) {
                            TabRow(tab, onClick = { onEnterTab(tab.tabId) })
                        } else {
                            tabAgents.forEach { AgentRow(it, workspace.displayLabel, onEnterAgent) }
                        }
                    }
                    // Safety net: agents whose tab the snapshot doesn't list.
                    agents.filter { agent -> tabs.none { it.tabId == agent.tabId } }
                        .forEach { AgentRow(it, workspace.displayLabel, onEnterAgent) }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    workspace: HerdrWorkspace,
    agents: List<HerdrAgent>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalPluginTheme.current
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 3.dp)
            .background(if (workspace.focused) theme.surface else Color.Transparent)
            .border(1.dp, if (workspace.focused) theme.accent.copy(alpha = 0.65f) else theme.border)
            .clickable(onClick = onEnter)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (expanded) "▾" else "▸", theme.small, theme.textMuted, Modifier.clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 4.dp))
        Text(workspace.displayLabel, theme.label, theme.textPrimary, Modifier.weight(1f))
        StatusSummary(agents)
        Text("${plural(workspace.tabCount, "tab")} · ${plural(workspace.paneCount, "pane")}", theme.small, theme.textMuted)
        Text("✕", theme.small, theme.textMuted, Modifier.clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun TabRow(tab: HerdrTab, onClick: () -> Unit) {
    val theme = LocalPluginTheme.current
    NestedRow(focused = tab.focused, onClick = onClick) {
        Box(Modifier.size(6.dp).background(statusColorOrMuted(tab.agentStatus, theme)))
        Text(tab.label?.takeIf { it.isNotBlank() } ?: "tab ${tab.number}", theme.body, theme.textSecondary, Modifier.weight(1f))
        Text(plural(tab.paneCount, "pane"), theme.small, theme.textMuted)
    }
}

@Composable
private fun AgentRow(agent: HerdrAgent, workspaceLabel: String, onEnterAgent: (String, String) -> Unit) {
    val theme = LocalPluginTheme.current
    val cwd = agent.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() && !it.equals(workspaceLabel, ignoreCase = true) }
    NestedRow(focused = agent.focused, onClick = { onEnterAgent(agent.paneId, agent.tabId) }) {
        AgentText(agent, workspaceLabel, Modifier.weight(1f))
        cwd?.let { Text(it, theme.small, theme.textMuted) }
    }
}

@Composable
private fun AgentText(agent: HerdrAgent, workspaceLabel: String, modifier: Modifier) {
    val theme = LocalPluginTheme.current
    val color = statusColorOrMuted(agent.agentStatus, theme)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(agent.agent?.takeIf { it.isNotBlank() } ?: "shell", theme.body, theme.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(6.dp).background(color))
                Text(agent.agentStatus.name.lowercase(), theme.small, color)
            }
        }
        agent.terminalTitleStripped?.let { cleanAgentTitle(it, workspaceLabel) }?.takeIf { it.isNotBlank() }?.let {
            Text(it, theme.small, theme.textMuted)
        }
    }
}

@Composable
private fun NestedRow(focused: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val theme = LocalPluginTheme.current
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 28.dp, end = 12.dp, top = 1.dp, bottom = 1.dp)
            .background(if (focused) theme.surface else Color.Transparent)
            .border(1.dp, if (focused) theme.accent.copy(alpha = 0.5f) else theme.border.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun StatusSummary(agents: List<HerdrAgent>) {
    val theme = LocalPluginTheme.current
    val order = listOf(HerdrAgentStatus.Blocked, HerdrAgentStatus.Working, HerdrAgentStatus.Done, HerdrAgentStatus.Idle)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        order.forEach { status ->
            val count = agents.count { it.agentStatus == status }
            if (count > 0) {
                val color = statusColorOrMuted(status, theme)
                Box(Modifier.size(6.dp).background(color))
                Text("$count ${status.name.lowercase()}", theme.small, color)
            }
        }
    }
}

@Composable
private fun ConfirmClose(label: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val theme = LocalPluginTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).border(1.dp, theme.error).padding(10.dp)) {
        Text("close $label? its agents will be terminated.", theme.small, theme.textPrimary)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("close", theme.label, theme.error, Modifier.clickable(onClick = onConfirm).padding(4.dp))
            Text("cancel", theme.label, theme.textSecondary, Modifier.clickable(onClick = onCancel).padding(4.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val theme = LocalPluginTheme.current
    Text(text, theme.label, theme.textSecondary, Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp))
}

@Composable
private fun Text(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private val HerdrAgentStatus.needsAttention: Boolean
    get() = this == HerdrAgentStatus.Blocked || this == HerdrAgentStatus.Done

private val HerdrWorkspace.displayLabel: String
    get() = label?.takeIf { it.isNotBlank() } ?: "workspace $number"

internal fun statusColorOrMuted(status: HerdrAgentStatus, theme: PluginTheme): Color =
    statusColor(status, theme) ?: theme.textMuted

internal fun plural(count: Int, unit: String): String = "$count $unit${if (count == 1) "" else "s"}"

/** "pi: Friendly reply - web-app" → "Friendly reply" when the workspace is web-app. From PR #69. */
internal fun cleanAgentTitle(title: String, workspaceLabel: String): String {
    var cleaned = title
    val colon = cleaned.indexOf(": ")
    if (colon in 1..40) cleaned = cleaned.substring(colon + 2)
    if (workspaceLabel.isNotBlank()) {
        val suffix = " - $workspaceLabel"
        if (cleaned.endsWith(suffix, ignoreCase = true)) cleaned = cleaned.dropLast(suffix.length)
    }
    return cleaned.trim().ifBlank { title }
}

package com.jossephus.chuchu.ui.screens.HostBrowser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.BrowserItem
import com.jossephus.chuchu.service.terminal.TransportTarget
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

// Matches the 28dp header row in the design so "+" and the chevron sit level with the divider.
private val HEADER_ACTION_SIZE = 28.dp

@Composable
fun HostBrowserScreen(
    vm: HostBrowserViewModel,
    openTargets: Set<String>,
    onOpen: (TransportTarget) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.connect()
                Lifecycle.Event.ON_STOP -> vm.disconnect()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.disconnect()
        }
    }
    var collapsedSessionIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    BackHandler(onBack = onBack)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChuText("$ ", style = typography.headline, color = colors.textMuted)
            ChuText(
                state.hostName.ifBlank { state.transportName },
                style = typography.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val error = state.error
        when {
            error != null -> ErrorCard(error, onRetry = vm::connect)
            state.connecting && !state.loaded ->
                ChuText("# connecting to ${state.transportName.ifBlank { "server" }}…", style = typography.body, color = colors.textMuted)
        }

        if (state.loaded) {
            val actionsEnabled = state.error == null
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.groups.isEmpty()) {
                    item(key = "empty") {
                        ChuText("# no sessions yet.", style = typography.body, color = colors.textMuted)
                    }
                }
                state.groups.forEach { group ->
                    val collapsed = group.id in collapsedSessionIds
                    item(key = "group:${group.id}") {
                        SessionHeader(
                            group = group,
                            enabled = actionsEnabled,
                            collapsed = collapsed,
                            onNewItem = group.newItemTarget?.let { target -> { onOpen(TransportTarget(target, creates = true)) } },
                            onToggleCollapsed = {
                                collapsedSessionIds =
                                    if (collapsed) collapsedSessionIds - group.id
                                    else collapsedSessionIds + group.id
                            },
                        )
                    }
                    if (!collapsed) {
                        items(group.items, key = { "item:${it.target}" }) { item ->
                            PaneCard(
                                item = item,
                                isOpen = item.target in openTargets,
                                onClick = { onOpen(TransportTarget(item.target)) },
                            )
                        }
                    }
                }
                val newGroupTarget = state.newGroupTarget
                if (newGroupTarget != null) {
                    item(key = "new-session") {
                        NewSessionRow(
                            enabled = actionsEnabled,
                            onClick = { onOpen(TransportTarget(newGroupTarget, creates = true)) },
                            // Sits one gap further out so it reads as the start of the next session group.
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(
    group: BrowserGroup,
    enabled: Boolean,
    collapsed: Boolean,
    onNewItem: (() -> Unit)?,
    onToggleCollapsed: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            ChuText(
                "── ${group.label} ",
                style = typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(colors.background),
            )
        }
        if (onNewItem != null) {
            ChuButton(
                onClick = onNewItem,
                enabled = enabled,
                modifier = Modifier.height(HEADER_ACTION_SIZE),
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                contentDescription = "New pane in ${group.label}",
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                ChuText("+", style = typography.label, color = colors.accent)
            }
        }
        ChuButton(
            onClick = onToggleCollapsed,
            modifier = Modifier.size(HEADER_ACTION_SIZE),
            variant = ChuButtonVariant.Ghost,
            contentDescription = if (collapsed) "Expand ${group.label}" else "Collapse ${group.label}",
            contentPadding = PaddingValues(0.dp),
        ) {
            ChuText(if (collapsed) "▸" else "▾", style = typography.label, color = colors.textSecondary)
        }
    }
}

@Composable
private fun PaneCard(item: BrowserItem, isOpen: Boolean, onClick: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        background = colors.surfaceVariant,
        border = if (isOpen) colors.success else colors.border,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChuText(">_", style = typography.label, color = colors.success)
            ChuText(
                item.title,
                style = typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ChuText(
                item.detail.orEmpty(),
                style = typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChuText("›", style = typography.label, color = colors.textMuted)
        }
    }
}

/** Dashed row that closes the list, standing in for the session it creates. */
@Composable
private fun NewSessionRow(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val borderColor = colors.border
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        color = borderColor,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = "New session" }
                .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("+", style = typography.label, color = if (enabled) colors.accent else colors.disabledText)
        ChuText("new session", style = typography.body, color = if (enabled) colors.textSecondary else colors.disabledText)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard(background = colors.surfaceVariant, border = colors.error) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuText(message, style = typography.bodySmall, color = colors.error)
            ChuButton(
                onClick = onRetry,
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                ChuText("retry", style = typography.label, color = colors.textSecondary)
            }
        }
    }
}

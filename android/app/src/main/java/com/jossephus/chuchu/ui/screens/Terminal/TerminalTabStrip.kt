package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jossephus.chuchu.service.multiplexer.HerdrControlState
import com.jossephus.chuchu.service.multiplexer.HerdrSplitDirection
import com.jossephus.chuchu.service.terminal.SessionStatus
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/**
 * Human-readable status text for accessibility.
 */
internal fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Connected -> "connected"
    SessionStatus.Connecting -> "connecting"
    SessionStatus.Reconnecting -> "reconnecting"
    SessionStatus.Disconnected -> "disconnected"
    SessionStatus.Error -> "error"
}

/**
 * Always-visible global top tab strip for strip mode.
 *
 * Shows all active terminal sessions in a compact top strip.
 * The tab list scrolls; primary actions stay pinned on the right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalTabStrip(
    tabs: List<TabSession>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onAddTab: () -> Unit,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier,
    herdrEnabled: Boolean = false,
    herdrState: HerdrControlState = HerdrControlState.Inactive,
    onHerdrFocusTab: (String) -> Unit = {},
    onHerdrCreateTab: (String) -> Unit = {},
    onHerdrHome: () -> Unit = {},
    herdrNativeMode: Boolean = false,
    hostChipLabel: String? = null,
    onHerdrSplitPane: (HerdrSplitDirection) -> Unit = {},
    onHerdrRequestClosePane: () -> Unit = {},
    onHerdrRequestCloseTab: (tabId: String, label: String) -> Unit = { _, _ -> },
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val scrollState = rememberScrollState()
    val trailingActionWidth = if (tabs.size > 1) 72.dp else 40.dp
    val tabOffsets = remember { mutableStateMapOf<String, Int>() }
    val rowRootLeft = remember { mutableStateOf(0) }
    val herdrSnapshot = (herdrState as? HerdrControlState.Active)?.snapshot
    val focusedWorkspaceId = herdrSnapshot?.focusedWorkspaceId
    val focusedWorkspace =
        herdrSnapshot?.workspaces?.firstOrNull { it.workspaceId == focusedWorkspaceId }
    val herdrTabs =
        herdrSnapshot
            ?.tabs
            ?.filter { it.workspaceId == focusedWorkspaceId }
            ?.sortedBy { it.number }
            .orEmpty()
    val showHerdrTabs = herdrEnabled && herdrSnapshot != null && herdrTabs.isNotEmpty()
    val focusedHerdrTabId = herdrTabs.firstOrNull { it.focused }?.tabId ?: herdrSnapshot?.focusedTabId
    val scrollTargetId = if (showHerdrTabs) focusedHerdrTabId else activeTabId

    LaunchedEffect(scrollTargetId) {
        val target = scrollTargetId?.let { tabOffsets[it] } ?: return@LaunchedEffect
        scrollState.animateScrollTo(target)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 5.dp),
    ) {
        if (showHerdrTabs) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = trailingActionWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val hostLabel =
                    if (herdrNativeMode) {
                        val workspaceLabel =
                            focusedWorkspace?.label?.takeIf { it.isNotBlank() }
                                ?: focusedWorkspace?.let { "ws ${it.number}" }
                                ?: "herdr"
                        "⌂ $workspaceLabel"
                    }
                    else hostChipLabel
                        ?: tabs.firstOrNull { it.id == activeTabId }?.let(::terminalTabDisplayLabel)
                        ?: "terminal"
                Box(
                    modifier = Modifier
                        .semantics { contentDescription = hostLabel }
                        .defaultMinSize(minHeight = 32.dp)
                        .widthIn(min = 44.dp, max = 160.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable { if (herdrNativeMode) onHerdrHome() else onOpenManager() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ChuText(
                        text = hostLabel,
                        style = typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                        .onGloballyPositioned { coords ->
                            rowRootLeft.value = coords.localToRoot(Offset.Zero).x.roundToInt()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    herdrTabs.forEach { tab ->
                        val isFocused = tab.focused
                        val label = tab.label?.takeIf { it.isNotBlank() } ?: "tab ${tab.number}"
                        val statusColor = herdrAgentStatusColor(tab.agentStatus, colors)

                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    tabOffsets[tab.tabId] = coords.localToRoot(Offset.Zero).x.roundToInt() - rowRootLeft.value + scrollState.value
                                }
                                .semantics { contentDescription = label }
                                .defaultMinSize(minHeight = 32.dp)
                                .widthIn(min = 44.dp, max = 160.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(
                                    if (isFocused) colors.accent.copy(alpha = 0.16f)
                                    else Color.Transparent
                                )
                                .combinedClickable(
                                    onClick = { onHerdrFocusTab(tab.tabId) },
                                    onLongClick = if (herdrNativeMode) {
                                        { onHerdrRequestCloseTab(tab.tabId, label) }
                                    } else {
                                        null
                                    },
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier.size(6.dp).background(
                                            statusColor,
                                            CircleShape,
                                        ),
                                )
                                ChuText(
                                    text = label,
                                    style = typography.labelSmall,
                                    color = if (isFocused) colors.accent else colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isFocused) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(start = 2.dp, end = 2.dp, bottom = 1.dp)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.accent),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = trailingActionWidth)
                    .horizontalScroll(scrollState)
                    .onGloballyPositioned { coords ->
                        rowRootLeft.value = coords.localToRoot(Offset.Zero).x.roundToInt()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEach { tab ->
                    val isActive = tab.id == activeTabId
                    val alias = terminalTabDisplayLabel(tab)
                    val title by remember(tab) {
                        tab.sessionState.map { it.title?.takeIf(String::isNotBlank) }
                    }.collectAsStateWithLifecycle(initialValue = null)
                    val label = title ?: alias

                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                tabOffsets[tab.id] = coords.localToRoot(Offset.Zero).x.roundToInt() - rowRootLeft.value + scrollState.value
                            }
                            .semantics { contentDescription = label }
                            .defaultMinSize(minHeight = 32.dp)
                            .widthIn(min = 44.dp, max = 160.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(
                                if (isActive) colors.accent.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable { onTabSelected(tab.id) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChuText(
                            text = label,
                            style = typography.labelSmall,
                            color = if (isActive) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 2.dp, end = 2.dp, bottom = 1.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.accent),
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (tabs.size > 1) {
                ChuButton(
                    onClick = onOpenManager,
                    modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    contentDescription = "all tabs",
                ) {
                    ChuText(
                        if (tabs.size > 99) "≡" else "${tabs.size}",
                        style = typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }

            if (showHerdrTabs && herdrNativeMode) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    ChuButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                        variant = ChuButtonVariant.Ghost,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        contentDescription = "herdr actions",
                    ) {
                        ChuText("+", style = typography.label, color = colors.accent)
                    }
                    if (menuExpanded) {
                        Popup(
                            alignment = Alignment.TopEnd,
                            onDismissRequest = { menuExpanded = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(184.dp)
                                    .background(colors.background, RectangleShape)
                                    .border(1.dp, colors.border, RectangleShape)
                                    .padding(vertical = 4.dp),
                            ) {
                                HerdrMenuItem("split right", colors.textPrimary) {
                                    menuExpanded = false
                                    onHerdrSplitPane(HerdrSplitDirection.Right)
                                }
                                HerdrMenuItem("split down", colors.textPrimary) {
                                    menuExpanded = false
                                    onHerdrSplitPane(HerdrSplitDirection.Down)
                                }
                                HerdrMenuItem("close pane", colors.error) {
                                    menuExpanded = false
                                    onHerdrRequestClosePane()
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .height(1.dp)
                                        .background(colors.border.copy(alpha = 0.4f)),
                                )
                                HerdrMenuItem("new tab", colors.textPrimary) {
                                    menuExpanded = false
                                    focusedWorkspaceId?.let(onHerdrCreateTab)
                                }
                            }
                        }
                    }
                }
            } else {
                ChuButton(
                    onClick = {
                        if (showHerdrTabs) focusedWorkspaceId?.let(onHerdrCreateTab)
                        else onAddTab()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    contentDescription = if (showHerdrTabs) "new herdr tab" else "new connection",
                ) {
                    ChuText("+", style = typography.label, color = colors.accent)
                }
            }
        }
    }
}

@Composable
private fun HerdrMenuItem(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    val typography = ChuTypography.current
    ChuText(
        text = label,
        style = typography.body,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

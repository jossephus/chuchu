package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
@Composable
fun TerminalTabStrip(
    tabs: List<TabSession>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onAddTab: () -> Unit,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val scrollState = rememberScrollState()
    val trailingActionWidth = if (tabs.size > 1) 72.dp else 40.dp
    val tabOffsets = remember { mutableStateMapOf<String, Int>() }
    val rowRootLeft = remember { mutableStateOf(0) }

    LaunchedEffect(activeTabId) {
        val target = activeTabId?.let { tabOffsets[it] } ?: return@LaunchedEffect
        scrollState.animateScrollTo(target)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 5.dp),
    ) {
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
                val alias = terminalTabAlias(tab)
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

            ChuButton(
                onClick = onAddTab,
                modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                variant = ChuButtonVariant.Ghost,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                contentDescription = "new connection",
            ) {
                ChuText("+", style = typography.label, color = colors.accent)
            }
        }
    }
}

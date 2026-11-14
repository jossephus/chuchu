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
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.service.terminal.SessionStatus
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ProgramStatusMarker
import com.jossephus.chuchu.ui.components.programStatusDescription
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

// Approximates the visible title width before the fixed-size tab ellipsizes it.
private const val TAB_TITLE_COLLISION_PREFIX_LENGTH = 14

/**
 * Human-readable status text for accessibility.
 */
@Composable
internal fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Connected -> stringResource(R.string.session_status_connected)
    SessionStatus.Connecting -> stringResource(R.string.session_status_connecting)
    SessionStatus.Reconnecting -> stringResource(R.string.session_status_reconnecting)
    SessionStatus.Disconnected -> stringResource(R.string.session_status_disconnected)
    SessionStatus.Error -> stringResource(R.string.session_status_error)
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
    newTabPicker: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val scrollState = rememberScrollState()
    val trailingActionWidth = if (tabs.isNotEmpty()) 72.dp else 40.dp
    val tabBounds = remember { mutableStateMapOf<String, IntRange>() }
    val terminalTitles = tabs.map { tab ->
        val title by remember(tab) {
            tab.sessionState.map { it.title?.takeIf(String::isNotBlank) }
        }.collectAsStateWithLifecycle(initialValue = null)
        title
    }
    val overrides = tabs.map { tab ->
        val override by tab.titleOverride.collectAsStateWithLifecycle()
        override?.takeIf(String::isNotBlank)
    }
    val titles = tabs.indices.map { overrides[it] ?: terminalTitles[it] }
    val programStatuses = tabs.map { tab ->
        val status by tab.programStatus.collectAsStateWithLifecycle(initialValue = null)
        status
    }
    val titlePrefixCounts = titles
        .filterNotNull()
        .groupingBy { it.take(TAB_TITLE_COLLISION_PREFIX_LENGTH) }
        .eachCount()

    val activeTabBounds = activeTabId?.let { tabBounds[it] }

    LaunchedEffect(activeTabId, activeTabBounds) {
        val bounds = activeTabBounds ?: return@LaunchedEffect
        val target = scrollToReveal(bounds, scrollState.value, scrollState.viewportSize) ?: return@LaunchedEffect
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
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val isActive = tab.id == activeTabId
                val alias = terminalTabDisplayLabel(tab)
                val title = titles[index]
                val label = when {
                    title == null -> alias
                    titlePrefixCounts.getValue(title.take(TAB_TITLE_COLLISION_PREFIX_LENGTH)) == 1 -> title
                    // A browser-named tab's alias is that same name (e.g. two panes of one Rex
                    // window). Number them: the terminal's own title can be stale, left behind
                    // by a program that has since exited.
                    overrides[index] != null -> {
                        val position = overrides.take(index + 1).count { it == title }
                        if (position > 1) "$title · $position" else title
                    }
                    else -> "$alias · $title"
                }
                val programStatus = programStatuses[index]
                val description = programStatusDescription(programStatus)?.let { "$label, $it" } ?: label

                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val start = coords.positionInParent().x.roundToInt()
                            tabBounds[tab.id] = start until start + coords.size.width
                        }
                        .semantics { contentDescription = description }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ProgramStatusMarker(programStatus)
                        ChuText(
                            text = label,
                            style = typography.labelSmall,
                            color = if (isActive) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
            if (tabs.isNotEmpty()) {
                ChuButton(
                    onClick = onOpenManager,
                    modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    contentDescription = stringResource(R.string.tabs_all),
                ) {
                    ChuText(
                        if (tabs.size > 99) "≡" else "${tabs.size}",
                        style = typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }

            Box {
                ChuButton(
                    onClick = onAddTab,
                    modifier = Modifier.defaultMinSize(minHeight = 32.dp, minWidth = 32.dp),
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    contentDescription = stringResource(R.string.tabs_new_connection),
                ) {
                    ChuText("+", style = typography.label, color = colors.accent)
                }
                newTabPicker()
            }
        }
    }
}

internal fun scrollToReveal(bounds: IntRange, scroll: Int, viewport: Int): Int? =
    when {
        bounds.first < scroll -> bounds.first
        bounds.last + 1 > scroll + viewport -> bounds.last + 1 - viewport
        else -> null
    }

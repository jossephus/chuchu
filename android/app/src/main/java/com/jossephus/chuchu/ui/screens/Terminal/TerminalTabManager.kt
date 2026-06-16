package com.jossephus.chuchu.ui.screens.Terminal

import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.SessionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColorPalette
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

private data class TabRowState(
    val status: SessionStatus,
    val title: String?,
    val pwd: String?,
)

/**
 * Global expanded tab manager for strip mode.
 *
 * Full-screen overlay listing all active sessions with host/profile name,
 * status, optional terminal title, user@host, PWD.  Each row exposes
 * labeled duplicate and close actions.
 */
@Composable
fun TerminalTabManager(
    visible: Boolean,
    tabs: List<TabSession>,
    activeTabId: String?,
    focusedTabIndex: Int,
    onFocusedTabIndexChange: (Int) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onDuplicateTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onDismiss: () -> Unit,
    // Multiplexer session controls (optional, current-host only)
    multiplexerEnabled: Boolean = false,
    multiplexerSessions: List<RemoteMultiplexerSession> = emptyList(),
    multiplexerSessionsLoading: Boolean = false,
    multiplexerSessionsError: String? = null,
    onMultiplexerRefresh: () -> Unit = {},
    onMultiplexerNew: () -> Unit = {},
    onMultiplexerAttach: (String) -> Unit = {},
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val entries = tabs
    val listState = rememberLazyListState()
    val maxIndex = (entries.size - 1).coerceAtLeast(0)
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = visible) { onDismiss() }

    LaunchedEffect(entries.size) {
        onFocusedTabIndexChange(focusedTabIndex.coerceIn(0, maxIndex))
    }

    LaunchedEffect(activeTabId, entries) {
        val activeIndex = entries.indexOfFirst { it.id == activeTabId }
        if (activeIndex >= 0) onFocusedTabIndexChange(activeIndex)
    }

    LaunchedEffect(focusedTabIndex, entries.size, visible) {
        if (visible && entries.isNotEmpty()) {
            listState.animateScrollToItem(focusedTabIndex.coerceIn(0, maxIndex))
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            keyboardController?.hide()
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            focusRequester.requestFocus()
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .semantics { contentDescription = "close tab manager" }
                .clickable(onClick = onDismiss)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.Escape -> {
                            if (event.type == KeyEventType.KeyUp) { onDismiss() }; true
                        }
                        Key.Enter -> {
                            if (event.type == KeyEventType.KeyUp) {
                                entries.getOrNull(focusedTabIndex)?.let { onSelectTab(it.id) }
                                onDismiss()
                            }; true
                        }
                        Key.DirectionUp, Key.PageUp -> {
                            if (event.type == KeyEventType.KeyUp && entries.isNotEmpty()) {
                                onFocusedTabIndexChange((focusedTabIndex - 1).mod(entries.size))
                            }; true
                        }
                        Key.DirectionDown, Key.PageDown -> {
                            if (event.type == KeyEventType.KeyUp && entries.isNotEmpty()) {
                                onFocusedTabIndexChange((focusedTabIndex + 1).mod(entries.size))
                            }; true
                        }
                        else -> false
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .background(colors.surface)
                    .border(1.dp, colors.border.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    ChuText(
                        "tabs",
                        style = typography.body,
                        color = colors.textSecondary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    ChuText(
                        "${entries.size} session${if (entries.size != 1) "s" else ""}",
                        style = typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                if (entries.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        itemsIndexed(entries, key = { _, tab -> tab.id }) { idx, tab ->
                            val isActive = tab.id == activeTabId
                            val isFocused = idx == focusedTabIndex
                            val label = terminalTabAlias(tab)
                            val userHost = "${tab.spec.username}@${tab.spec.host}:${tab.spec.port}"
                            val rowState by remember(tab) {
                                tab.sessionState
                                    .map { TabRowState(it.status, it.title, it.pwd) }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(
                                initialValue = TabRowState(SessionStatus.Disconnected, null, null)
                            )
                            val status = rowState.status
                            val title = rowState.title?.takeIf { it.isNotBlank() }
                            val workingDirectory = rowState.pwd?.takeIf { it.isNotBlank() }
                            val statusText = statusLabel(status)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isFocused) colors.surface
                                        else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (isFocused) colors.accent.copy(alpha = 0.7f)
                                        else colors.border.copy(alpha = 0.35f),
                                    )
                                    .semantics {
                                        contentDescription = "$statusText — $label, ${if (isActive) "active" else "inactive"}"
                                    }
                                    .clickable { onSelectTab(tab.id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Focus indicator
                                Box(modifier = Modifier.width(10.dp)) {
                                    if (isFocused) {
                                        ChuText(
                                            "\u258c",
                                            style = typography.body,
                                            color = colors.accent,
                                        )
                                    }
                                }

                                // Status dot
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusDotColor(status, colors)),
                                )

                                // Tab info column
                                Column(modifier = Modifier.weight(1f)) {
                                    val displayLabel = title ?: label
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        ChuText(
                                            displayLabel,
                                            style = typography.body,
                                            color = if (isFocused) colors.accent
                                            else colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isActive) {
                                            ChuText(
                                                "\u25C9",
                                                style = typography.labelSmall,
                                                color = colors.success,
                                            )
                                        }
                                    }
                                    // Show alias as secondary when title is primary
                                    if (title != null) {
                                        ChuText(
                                            label,
                                            style = typography.bodySmall,
                                            color = colors.textSecondary,
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ChuText(
                                            userHost,
                                            style = typography.labelSmall,
                                            color = colors.textMuted,
                                        )
                                        if (workingDirectory != null) {
                                            ChuText(
                                                workingDirectory,
                                                style = typography.labelSmall,
                                                color = colors.textMuted,
                                            )
                                        }
                                    }
                                }

                                // Actions
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ChuButton(
                                        onClick = {
                                            onDuplicateTab(tab.id)
                                            onDismiss()
                                        },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                                        variant = ChuButtonVariant.Ghost,
                                        bracketed = true,
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 4.dp,
                                        ),
                                        contentDescription = "duplicate ${label}",
                                    ) {
                                        ChuText(
                                            "dup",
                                            style = typography.labelSmall,
                                            color = colors.textSecondary,
                                        )
                                    }
                                    ChuButton(
                                        onClick = { onCloseTab(tab.id) },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                                        variant = ChuButtonVariant.Ghost,
                                        bracketed = true,
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 4.dp,
                                        ),
                                        contentDescription = "close ${label}",
                                    ) {
                                        ChuText(
                                            "close",
                                            style = typography.labelSmall,
                                            color = colors.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.border),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PaletteHint("\u21B5", "open")
                        PaletteHint("esc", "dismiss")
                        Spacer(modifier = Modifier.weight(1f))
                        ChuButton(
                            onClick = {
                                onAddTab()
                                onDismiss()
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                            variant = ChuButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            ChuText("+ new", style = typography.labelSmall, color = colors.accent)
                        }
                    }
                } else {
                    // Empty state inside the manager
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ChuText(
                                "no terminal sessions",
                                style = typography.body,
                                color = colors.textMuted,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            ChuButton(
                                onClick = {
                                    onAddTab()
                                    onDismiss()
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                                variant = ChuButtonVariant.Outlined,
                                bracketed = true,
                            ) {
                                ChuText(
                                    "+ new connection",
                                    style = typography.label,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
                }

                // Multiplexer sessions section (current-host only)
                if (multiplexerEnabled) {
                    MultiplexerSessionPanel(
                        sessions = multiplexerSessions,
                        loading = multiplexerSessionsLoading,
                        error = multiplexerSessionsError,
                        onRefresh = onMultiplexerRefresh,
                        onNew = onMultiplexerNew,
                        onAttach = onMultiplexerAttach,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

/**
 * Status dot color matching the one used in [TerminalTabStrip].
 */
private fun statusDotColor(status: SessionStatus, colors: ChuColorPalette): Color = when (status) {
    SessionStatus.Connected -> colors.success
    SessionStatus.Connecting,
    SessionStatus.Reconnecting -> colors.accent
    SessionStatus.Disconnected,
    SessionStatus.Error -> colors.error
}

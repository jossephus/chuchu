package com.jossephus.chuchu.ui.screens.SessionBrowser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.R
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.HostKeyPromptDialog
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun SessionBrowserScreen(
    vm: SessionBrowserViewModel,
    onOpenSession: (name: String, create: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val openNames by vm.openSessionNames.collectAsStateWithLifecycle()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onBack)
    hostKeyPrompt?.let { prompt -> HostKeyPromptDialog(prompt, vm::onHostKeyDecision) }

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
                state.hostName,
                style = typography.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChuButton(
                onClick = vm::refresh,
                enabled = !state.loading,
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                ChuText(stringResource(R.string.session_browser_refresh), style = typography.labelSmall, color = colors.textMuted)
            }
        }

        val error = state.error
        when {
            error != null -> ErrorCard(error, onRetry = vm::refresh)
            state.loading && !state.loaded ->
                ChuText(
                    "# " + stringResource(R.string.session_browser_connecting, state.multiplexerLabel),
                    style = typography.body,
                    color = colors.textMuted,
                )
        }

        if (state.loaded) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.sessions.isEmpty()) {
                    item(key = "empty") {
                        ChuText(
                            "# " + stringResource(R.string.session_browser_empty),
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }
                }
                items(state.sessions, key = { "session:${it.name}" }) { session ->
                    SessionCard(
                        session = session,
                        isOpen = session.name in openNames,
                        onClick = { onOpenSession(session.name, false) },
                    )
                }
                item(key = "new-session") {
                    NewSessionRow(
                        enabled = !state.loading && state.error == null,
                        onClick = { vm.newSessionName()?.let { onOpenSession(it, true) } },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: RemoteMultiplexerSession, isOpen: Boolean, onClick: () -> Unit) {
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
                session.name,
                style = typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.attached) {
                ChuText(stringResource(R.string.mux_attached), style = typography.bodySmall, color = colors.textMuted)
            }
            ChuText("›", style = typography.label, color = colors.textMuted)
        }
    }
}

@Composable
private fun NewSessionRow(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val borderColor = colors.border
    val label = stringResource(R.string.session_browser_new)
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
                .semantics { contentDescription = label }
                .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("+", style = typography.label, color = if (enabled) colors.accent else colors.disabledText)
        ChuText(label, style = typography.body, color = if (enabled) colors.textSecondary else colors.disabledText)
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
                ChuText(stringResource(R.string.session_browser_retry), style = typography.label, color = colors.textSecondary)
            }
        }
    }
}

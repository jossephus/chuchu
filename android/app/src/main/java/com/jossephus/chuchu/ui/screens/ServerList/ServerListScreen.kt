package com.jossephus.chuchu.ui.screens.ServerList

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.screens.Settings.SettingsSheet
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun ServerListScreen(
    hosts: List<HostProfile>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    onConnectServer: (Long) -> Unit,
    onDeleteServer: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settingsRepo = remember(context) { SettingsRepository.getInstance(context) }
    val application = context.applicationContext as Application
    val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
    val currentTheme by settingsRepo.themeName.collectAsStateWithLifecycle()
    val currentAccessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
    val currentTerminalCustomKeyGroups by settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
    val sessionState by sessionRepo.sessionState.collectAsStateWithLifecycle()
    val activeSessionKey = sessionState.sessionKey
    val hasActiveSession = sessionState.status == SessionStatus.Connecting ||
        sessionState.status == SessionStatus.Connected ||
        sessionState.status == SessionStatus.Reconnecting
    var showSettings by remember { mutableStateOf(false) }

    val colors = ChuColors.current
    val typography = ChuTypography.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ChuText("Chuchu", style = typography.headline)

                ChuButton(
                    onClick = { showSettings = true },
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(8.dp),
                ) {
                    ChuText("⚙", style = typography.title, color = colors.textMuted)
                }
            }

            SectionHeader("Active connections and saved servers")

            if (hosts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(hosts, key = { it.id }) { host ->
                        val targetSessionKey = "host:${host.id}"
                        val isConnected = sessionState.status == SessionStatus.Connected && activeSessionKey == targetSessionKey
                        HostCard(
                            host = host,
                            isConnected = isConnected,
                            onEdit = { onEditServer(host.id) },
                            onConnect = {
                                if (!hasActiveSession || activeSessionKey == targetSessionKey) {
                                    onConnectServer(host.id)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Disconnect current session first",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onDisconnect = { sessionRepo.disconnect() },
                            onDelete = { onDeleteServer(host.id) },
                        )
                    }
                }
            }
        }

        ChuButton(
            onClick = onAddServer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .height(44.dp),
        ) {
            ChuText("Add Server", style = typography.label, color = colors.onAccent)
        }

        if (showSettings) {
            SettingsSheet(
                visible = true,
                currentTheme = currentTheme,
                currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                currentTerminalCustomKeyGroups = currentTerminalCustomKeyGroups,
                onThemeSelected = { settingsRepo.setTheme(it) },
                onAccessoryLayoutChanged = { settingsRepo.setAccessoryLayoutIds(it) },
                onTerminalCustomActionsChanged = { settingsRepo.setTerminalCustomKeyGroups(it) },
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    ChuText(label, style = ChuTypography.current.label, color = ChuColors.current.textSecondary)
}

@Composable
private fun EmptyState() {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            ChuText("No servers yet", style = typography.title)
            Spacer(modifier = Modifier.height(8.dp))
            ChuText(
                "Add your first host profile to connect quickly.",
                style = typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun HostCard(
    host: HostProfile,
    isConnected: Boolean,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 120.dp.toPx() }
    val deleteThresholdPx = with(density) { 72.dp.toPx() }
    val offsetX = remember(host.id) { Animatable(0f) }
    val cardShape = RoundedCornerShape(4.dp)
    val activeBorderColor = colors.success.copy(alpha = 0.8f)

    Box(modifier = Modifier.fillMaxWidth().height(114.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .background(if (isConnected) colors.warning.copy(alpha = 0.78f) else colors.error.copy(alpha = 0.78f))
                .padding(end = 14.dp),
        ) {
            ChuText(
                if (isConnected) "Disconnect" else "Delete",
                style = typography.label,
                color = colors.background,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(cardShape)
                .background(colors.surface)
                .then(if (isConnected) Modifier.border(1.dp, activeBorderColor, cardShape) else Modifier)
                .clickable(onClick = onConnect)
                .pointerInput(host.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            if (offsetX.value <= -deleteThresholdPx) {
                                if (isConnected) {
                                    onDisconnect()
                                } else {
                                    onDelete()
                                }
                                scope.launch { offsetX.snapTo(0f) }
                            } else {
                                scope.launch { offsetX.animateTo(0f, animationSpec = tween(140)) }
                            }
                        },
                    )
                },
        ) {
            ChuCard(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChuText(host.name, style = typography.title)
                        if (isConnected) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(colors.success.copy(alpha = 0.2f))
                                    .border(1.dp, colors.success.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                ChuText(
                                    "Connected",
                                    style = typography.labelSmall,
                                    color = colors.success,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChuText(
                            "${host.username}@${host.host}:${host.port}",
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                        if (host.transport == Transport.TailscaleSSH) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(colors.accent.copy(alpha = 0.15f))
                                    .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                ChuText(
                                    "Tailscale",
                                    style = typography.labelSmall,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChuButton(
                            onClick = onEdit,
                            variant = ChuButtonVariant.Outlined,
                            testTag = "host_edit_${host.id}",
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ChuText("Edit", style = typography.label)
                        }
                        ChuButton(
                            onClick = onConnect,
                            testTag = "host_connect_${host.id}",
                            contentDescription = "Connect to ${host.name}",
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ChuText("Connect", style = typography.label, color = colors.onAccent)
                        }
                    }
                }
            }
        }
    }
}

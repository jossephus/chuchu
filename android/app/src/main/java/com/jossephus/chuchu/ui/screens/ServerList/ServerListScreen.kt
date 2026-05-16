package com.jossephus.chuchu.ui.screens.ServerList

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.TuiBadge
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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
    val connectedHostIds by sessionRepo.connectedHostIds.collectAsStateWithLifecycle()
    val openTabs by sessionRepo.tabs.collectAsStateWithLifecycle()
    val hasActiveSession = openTabs.isNotEmpty()

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var pendingConnectHostId by remember { mutableStateOf<Long?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val hostId = pendingConnectHostId
        pendingConnectHostId = null
        if (hostId != null) {
            onConnectServer(hostId)
        }
    }
    LaunchedEffect(hosts, selectedHostId) {
        val selected_id = selectedHostId
        if (selected_id != null && hosts.none { it.id == selected_id }) {
            selectedHostId = null
        }
    }

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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChuText("$ ", style = typography.headline, color = colors.textMuted)
                    ChuText("chuchu", style = typography.headline)
                }

                ChuButton(
                    onClick = onOpenSettings,
                    variant = ChuButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    ChuText("settings", style = typography.label, color = colors.textSecondary)
                }
            }

            SectionHeader("HOSTS")

            if (hosts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(hosts, key = { it.id }) { host ->
                        val targetSessionKey = "host:${host.id}"
                        val isConnected = host.id in connectedHostIds
                        HostCard(
                            host = host,
                            isConnected = isConnected,
                            isSelected = selectedHostId == host.id,
                            onLongPress = { selectedHostId = host.id },
                            onCancelSelection = { selectedHostId = null },
                            onDeleteSelection = {
                                val is_selected_connected = host.id in connectedHostIds
                                if (is_selected_connected) {
                                    Toast.makeText(
                                        context,
                                        "Disconnect before deleting this host",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    onDeleteServer(host.id)
                                    selectedHostId = null
                                }
                            },
                            onEdit = {
                                selectedHostId = null
                                onEditServer(host.id)
                            },
                            onConnect = {
                                selectedHostId = null
                                val canNavigate = true
                                if (canNavigate) {
                                    val needsNotificationPermission =
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED
                                    if (needsNotificationPermission) {
                                        pendingConnectHostId = host.id
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        onConnectServer(host.id)
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Disconnect current session first",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onDisconnect = {
                                selectedHostId = null
                                sessionRepo.disconnect()
                            },
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuButton(
                onClick = onAddServer,
                variant = ChuButtonVariant.Filled,
                bracketed = true,
                modifier = Modifier.height(44.dp),
            ) {
                ChuText("+ add server", style = typography.label, color = colors.onAccent)
            }

        }

    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )
        ChuText(
            "── $label",
            style = typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.background(colors.background),
        )
    }
}

@Composable
private fun EmptyState() {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard(background = colors.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            ChuText("# no hosts configured", style = typography.body, color = colors.textMuted)
            Spacer(modifier = Modifier.height(8.dp))
            ChuText(
                "add your first host to connect quickly.",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: HostProfile,
    isConnected: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 132.dp.toPx() }
    val disconnectThresholdPx = with(density) { 72.dp.toPx() }
    val swipeOffsetX = remember(host.id) { Animatable(0f) }
    val hostLabel = "> ${host.username}@${host.host}:${host.port}"

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            swipeOffsetX.snapTo(0f)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSelected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChuButton(
                        onClick = onCancelSelection,
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ChuText("cancel", style = typography.label, color = colors.textSecondary)
                    }
                    ChuButton(
                        onClick = onDeleteSelection,
                        variant = ChuButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ChuText("delete", style = typography.label, color = colors.error)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(114.dp),
        ) {
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.warning.copy(alpha = 0.82f))
                        .padding(end = 14.dp),
                ) {
                    ChuText(
                        "[disconnect]",
                        style = typography.label,
                        color = colors.background,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                    .fillMaxSize()
                    .pointerInput(host.id, isConnected) {
                        if (!isConnected) {
                            return@pointerInput
                        }
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (swipeOffsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                                scope.launch { swipeOffsetX.snapTo(next) }
                            },
                            onDragEnd = {
                                if (swipeOffsetX.value <= -disconnectThresholdPx) {
                                    onDisconnect()
                                    scope.launch { swipeOffsetX.snapTo(0f) }
                                } else {
                                    scope.launch {
                                        swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                    }
                                }
                            },
                        )
                    },
            ) {
                ChuCard(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = onConnect,
                            onLongClick = onLongPress,
                        ),
                    background = colors.surfaceVariant,
                    border = when {
                        isSelected -> colors.warning
                        isConnected -> colors.success
                        else -> colors.border
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChuText(
                                    if (isConnected) "● " else "○ ",
                                    style = typography.title,
                                    color = if (isConnected) colors.success else colors.textMuted,
                                )
                                ChuText(host.name, style = typography.title)
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChuText(
                                hostLabel,
                                style = typography.body,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (host.transport == Transport.TailscaleSSH) {
                                TuiBadge(
                                    text = "tailscale",
                                    color = colors.accent,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChuButton(
                                onClick = onEdit,
                                variant = ChuButtonVariant.Ghost,
                                bracketed = true,
                                borderColor = colors.textMuted,
                                testTag = "host_edit_${host.id}",
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                ChuText("edit", style = typography.label, color = colors.textMuted)
                            }
                            ChuButton(
                                onClick = {
                                    if (isConnected) onDisconnect() else onConnect()
                                },
                                variant = ChuButtonVariant.Outlined,
                                bracketed = true,
                                borderColor = if (isConnected) colors.warning else colors.accentSecondary,
                                testTag = "host_connect_${host.id}",
                                contentDescription = if (isConnected) "Disconnect from ${host.name}" else "Connect to ${host.name}",
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                ChuText(
                                    if (isConnected) "disconnect" else "connect",
                                    style = typography.label,
                                    color = if (isConnected) colors.warning else colors.accentSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

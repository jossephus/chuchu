package com.jossephus.chuchu.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
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
    modifier: Modifier = Modifier,
) {
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
            Column {
                ChuText("Chuchu", style = typography.headline)
                ChuText(
                    "Active connections and saved hosts",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }

            ChuTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = "Search",
                placeholder = "Search servers",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ActiveConnectionsSection()

            if (hosts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(hosts, key = { it.id }) { host ->
                        HostCard(
                            host = host,
                            onEdit = { onEditServer(host.id) },
                            onConnect = { onConnectServer(host.id) },
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
    }
}

@Composable
private fun ActiveConnectionsSection() {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Active Connections")
        ChuCard {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                ChuText("No active sessions", style = typography.title)
                Spacer(modifier = Modifier.height(6.dp))
                ChuText(
                    "Active tabs will appear here.",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }
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
    onEdit: () -> Unit,
    onConnect: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            ChuText(host.name, style = typography.title)
            Spacer(modifier = Modifier.height(4.dp))
            ChuText(
                "${host.username}@${host.host}:${host.port}",
                style = typography.body,
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChuButton(
                    onClick = onEdit,
                    variant = ChuButtonVariant.Outlined,
                    testTag = "host_edit_${host.id}",
                ) {
                    ChuText("Edit", style = typography.label)
                }
                ChuButton(
                    onClick = onConnect,
                    testTag = "host_connect_${host.id}",
                    contentDescription = "Connect to ${host.name}",
                ) {
                    ChuText("Connect", style = typography.label, color = colors.onAccent)
                }
            }
        }
    }
}

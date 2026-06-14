package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun MultiplexerSessionPanel(
    sessions: List<RemoteMultiplexerSession>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val visibleSessions = if (loading) emptyList() else sessions

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChuText("multiplexer sessions", style = typography.body, color = colors.textSecondary)
                Spacer(modifier = Modifier.weight(1f))
                if (loading) {
                    ChuText("…", style = typography.labelSmall, color = colors.textMuted)
                } else {
                    ChuText("${visibleSessions.size}", style = typography.labelSmall, color = colors.textMuted)
                }
            }
        }
        if (error != null) {
            ChuText(
                error,
                style = typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (visibleSessions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                items(visibleSessions) { session ->
                    MultiplexerSessionRow(
                        session = session,
                        onAttach = {
                            onAttach(session.name)
                            onDismiss()
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuButton(
                onClick = onRefresh,
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                borderColor = colors.textMuted,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                ChuText("refresh", style = typography.labelSmall, color = colors.textMuted)
            }
            ChuButton(
                onClick = {
                    onNew()
                    onDismiss()
                },
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                borderColor = colors.textMuted,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                ChuText("new", style = typography.labelSmall, color = colors.textMuted)
            }
        }
    }
}

@Composable
private fun MultiplexerSessionRow(
    session: RemoteMultiplexerSession,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                colors.border.copy(alpha = if (session.attached) 0.65f else 0.35f),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (session.attached) colors.success else Color.Transparent),
        )
        ChuText(
            session.name,
            style = typography.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (session.attached) {
            ChuText("attached", style = typography.labelSmall, color = colors.textMuted)
        }
        ChuButton(
            onClick = onAttach,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            ChuText("attach", style = typography.labelSmall, color = colors.accent)
        }
    }
}

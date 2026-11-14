package com.jossephus.chuchu.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.plugin.api.ProgramState
import com.jossephus.chuchu.plugin.api.ProgramStatus
import com.jossephus.chuchu.service.terminal.ProgramIcons
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun ProgramStatusMarker(status: ProgramStatus?) {
    if (status == null) return
    val colors = ChuColors.current
    val (marker, color) =
        when (status.state) {
            ProgramState.Working -> (status.progress?.let { "$it%" } ?: "●") to colors.accent
            ProgramState.Blocked -> "!" to colors.warning
            ProgramState.Error -> "✗" to colors.error
            ProgramState.Done -> "✓" to colors.success
            ProgramState.Idle -> return
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ProgramIcons.forName(status.app)?.let { icon ->
            Image(
                painter = painterResource(icon.drawable),
                contentDescription = null,
                colorFilter = ColorFilter.tint(color),
                modifier = Modifier.size(12.dp),
            )
        }
        ChuText(marker, style = ChuTypography.current.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun programStatusDescription(status: ProgramStatus?): String? =
    when (status?.state) {
        ProgramState.Working -> stringResource(R.string.program_status_a11y_working)
        ProgramState.Blocked -> stringResource(R.string.program_status_a11y_blocked)
        ProgramState.Error -> stringResource(R.string.program_status_a11y_error)
        ProgramState.Done -> stringResource(R.string.program_status_a11y_done)
        ProgramState.Idle, null -> null
    }

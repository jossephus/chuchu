package com.jossephus.chuchu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jossephus.chuchu.R
import com.jossephus.chuchu.plugin.api.ProgramState
import com.jossephus.chuchu.plugin.api.ProgramStatus
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
    ChuText(marker, style = ChuTypography.current.labelSmall, color = color, maxLines = 1)
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

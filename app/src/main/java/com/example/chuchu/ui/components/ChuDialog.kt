package com.example.chuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import com.example.chuchu.ui.theme.ChuColors
import com.example.chuchu.ui.theme.ChuTypography

@Composable
fun ChuDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    content: @Composable () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val shape = RoundedCornerShape(4.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, shape)
                .border(BorderStroke(1.dp, colors.border), shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChuText(text = title, style = typography.title)
            content()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChuButton(
                    onClick = onDismiss,
                    variant = ChuButtonVariant.Ghost,
                ) {
                    ChuText(dismissLabel, style = typography.label, color = colors.accent)
                }
                ChuButton(onClick = onConfirm) {
                    ChuText(confirmLabel, style = typography.label, color = colors.onAccent)
                }
            }
        }
    }
}

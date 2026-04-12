package com.jossephus.chuchu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun <T> ChuSegmentedControl(
    options: List<T>,
    labels: Map<T, String>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val label = labels[option] ?: option.toString()
            ChuButton(
                onClick = { onSelect(option) },
                variant = if (isSelected) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                ChuText(
                    text = label,
                    style = typography.label,
                    color = if (isSelected) colors.onAccent else colors.textSecondary,
                )
            }
        }
    }
}

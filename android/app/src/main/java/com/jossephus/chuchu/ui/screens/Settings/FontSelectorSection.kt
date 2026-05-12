package com.jossephus.chuchu.ui.screens.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuFontOption
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
internal fun FontSelectorSection(
    currentFont: String,
    onFontSelected: (String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val current = remember(currentFont) { ChuFontOption.fromId(currentFont) }
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ChuText("── ", style = typography.labelSmall, color = colors.textMuted)
        ChuText("FONT", style = typography.labelSmall, color = colors.textMuted)
        ChuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChuButton(
            onClick = { expanded = !expanded },
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuText(current.label, style = typography.label)
                ChuText(if (expanded) "▲" else "▼", style = typography.labelSmall, color = colors.textMuted)
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                ChuFontOption.entries.forEach { option ->
                    val isSelected = option == current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) colors.surface else colors.surfaceVariant)
                            .clickable {
                                onFontSelected(option.id)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChuText(
                                option.label,
                                style = typography.label,
                                color = if (isSelected) colors.accent else colors.textPrimary,
                            )
                            if (isSelected) ChuText("●", style = typography.label, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}

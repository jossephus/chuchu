package com.jossephus.chuchu.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun KeyboardAccessoryBar(
    items: List<AccessoryKeyItem>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    nativeVersion: String? = null,
    modifier: Modifier = Modifier,
) {
    val buttonHeight = 30.dp
    val buttonPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
    val typography = ChuTypography.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
            if (toggleModifier != null) {
                ToggleButton(
                    label = item.label,
                    enabled = modifierState.isEnabled(toggleModifier),
                    onClick = { onAction(item.action) },
                    modifier = Modifier.height(buttonHeight),
                    contentPadding = buttonPadding,
                )
            } else {
                ChuButton(
                    onClick = { onAction(item.action) },
                    variant = ChuButtonVariant.Outlined,
                    modifier = Modifier.height(buttonHeight),
                    contentPadding = buttonPadding,
                ) {
                    ChuText(item.label, style = typography.label)
                }
            }
        }

        if (nativeVersion != null) {
            Spacer(modifier = Modifier.size(4.dp))
            ChuText(
                text = nativeVersion,
                style = typography.labelSmall,
                color = ChuColors.current.textMuted,
            )
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val activeLabel = if (enabled) "• $label" else label
    ChuButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = contentPadding,
        variant = if (enabled) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
    ) {
        ChuText(
            activeLabel,
            style = typography.labelSmall,
            color = if (enabled) colors.onAccent else colors.textSecondary,
        )
    }
}

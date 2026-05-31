package com.jossephus.chuchu.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuSymbolsFontFamily
import com.jossephus.chuchu.ui.theme.ChuTypography

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardAccessoryBar(
    items: List<AccessoryKeyItem>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onSettings: (() -> Unit)? = null,
    onChuchuKey: (() -> Unit)? = null,
    chuchuKeyActive: Boolean = false,
    onOpenFiles: (() -> Unit)? = null,
    onToggleDictation: (() -> Unit)? = null,
    dictationActive: Boolean = false,
    useSingleRow: Boolean = false,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    val buttonHeight = 30.dp
    val buttonPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)

    if (useSingleRow) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                AccessoryButton(
                    item = item,
                    modifierState = modifierState,
                    onAction = onAction,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
            FilesButton(
                onChuchuKey = onChuchuKey,
                chuchuKeyActive = chuchuKeyActive,
                onOpenFiles = onOpenFiles,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
            DictationButton(
                onToggleDictation = onToggleDictation,
                dictationActive = dictationActive,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
            SettingsButton(
                onSettings = onSettings,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        }
        return
    }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxLines = 2,
    ) {
        items.forEach { item ->
            AccessoryButton(
                item = item,
                modifierState = modifierState,
                onAction = onAction,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        }
        FilesButton(
            onChuchuKey = onChuchuKey,
            chuchuKeyActive = chuchuKeyActive,
            onOpenFiles = onOpenFiles,
            buttonHeight = buttonHeight,
            buttonPadding = buttonPadding,
        )
        DictationButton(
            onToggleDictation = onToggleDictation,
            dictationActive = dictationActive,
            buttonHeight = buttonHeight,
            buttonPadding = buttonPadding,
        )
        SettingsButton(
            onSettings = onSettings,
            buttonHeight = buttonHeight,
            buttonPadding = buttonPadding,
        )
    }
}

@Composable
private fun AccessoryButton(
    item: AccessoryKeyItem,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = ChuTypography.current
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

@Composable
private fun FilesButton(
    onChuchuKey: (() -> Unit)?,
    chuchuKeyActive: Boolean,
    onOpenFiles: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    if (onChuchuKey != null) {
        ChuButton(
            onClick = onChuchuKey,
            variant = if (chuchuKeyActive) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
            modifier = Modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            ChuText(
                "⌘",
                style = typography.label,
                color = if (chuchuKeyActive) colors.onAccent else colors.textPrimary,
            )
        }
    }
    if (onOpenFiles == null) return
    ChuButton(
        onClick = onOpenFiles,
        variant = ChuButtonVariant.Outlined,
        modifier = Modifier.height(buttonHeight),
        contentPadding = buttonPadding,
    ) {
        ChuText(
            text = "",
            style = TextStyle(
                fontFamily = ChuSymbolsFontFamily,
                fontWeight = typography.label.fontWeight,
                fontStyle = typography.label.fontStyle,
                fontSize = 16.sp,
                lineHeight = 16.sp,
            ),
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun SettingsButton(
    onSettings: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = ChuTypography.current
    if (onSettings == null) return
    ChuButton(
        onClick = onSettings,
        variant = ChuButtonVariant.Outlined,
        modifier = Modifier.height(buttonHeight),
        contentPadding = buttonPadding,
    ) {
        ChuText("⚙", style = typography.label)
    }
}

@Composable
private fun DictationButton(
    onToggleDictation: (() -> Unit)?,
    dictationActive: Boolean,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    if (onToggleDictation == null) return
    ChuButton(
        onClick = onToggleDictation,
        variant = if (dictationActive) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
        modifier = Modifier.height(buttonHeight),
        contentPadding = buttonPadding,
    ) {
        ChuText(
            text = "\uf130",
            style = TextStyle(
                fontFamily = ChuSymbolsFontFamily,
                fontWeight = typography.label.fontWeight,
                fontStyle = typography.label.fontStyle,
                fontSize = 16.sp,
                lineHeight = 16.sp,
            ),
            color = if (dictationActive) colors.onAccent else colors.textPrimary,
        )
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

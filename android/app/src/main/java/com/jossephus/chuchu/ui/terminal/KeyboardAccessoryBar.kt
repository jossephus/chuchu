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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun KeyboardAccessoryBar(
    cmdEnabled: Boolean,
    ctrlEnabled: Boolean,
    altEnabled: Boolean,
    shiftEnabled: Boolean,
    onToggleCmd: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onSendKey: (VirtualKey) -> Unit,
    onPaste: () -> Unit,
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
        ChuButton(
            onClick = { onSendKey(VirtualKey.Tab) },
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            ChuText("Tab", style = typography.label)
        }
        ChuButton(
            onClick = { onSendKey(VirtualKey.Escape) },
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            ChuText("Esc", style = typography.label)
        }
        ToggleButton("Ctrl", ctrlEnabled, onToggleCtrl, Modifier.height(buttonHeight), buttonPadding)
        ToggleButton("Cmd", cmdEnabled, onToggleCmd, Modifier.height(buttonHeight), buttonPadding)
        ToggleButton("Alt", altEnabled, onToggleAlt, Modifier.height(buttonHeight), buttonPadding)
        ToggleButton("Shift", shiftEnabled, onToggleShift, Modifier.height(buttonHeight), buttonPadding)

        listOf(VirtualKey.Up, VirtualKey.Down, VirtualKey.Left, VirtualKey.Right).forEach { key ->
            ChuButton(
                onClick = { onSendKey(key) },
                variant = ChuButtonVariant.Outlined,
                modifier = Modifier.height(buttonHeight),
                contentPadding = buttonPadding,
            ) {
                ChuText(key.label, style = typography.label)
            }
        }

        VirtualKey.values().filter { it.isExtended && it !in setOf(VirtualKey.Up, VirtualKey.Down, VirtualKey.Left, VirtualKey.Right) }.forEach { key ->
            ChuButton(
                onClick = { onSendKey(key) },
                variant = ChuButtonVariant.Outlined,
                modifier = Modifier.height(buttonHeight),
                contentPadding = buttonPadding,
            ) {
                ChuText(key.label, style = typography.label)
            }
        }

        ChuButton(
            onClick = onPaste,
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            ChuText("Paste", style = typography.label)
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

enum class VirtualKey(
    val label: String,
    val isExtended: Boolean,
) {
    Escape("Esc", false),
    Tab("Tab", false),
    Up("↑", true),
    Down("↓", true),
    Left("←", true),
    Right("→", true),
    Home("Home", true),
    End("End", true),
    PageUp("PgUp", true),
    PageDown("PgDn", true),
    Insert("Ins", true),
    Delete("Del", true),
    F1("F1", true),
    F2("F2", true),
    F3("F3", true),
    F4("F4", true),
    F5("F5", true),
    F6("F6", true),
    F7("F7", true),
    F8("F8", true),
    F9("F9", true),
    F10("F10", true),
    F11("F11", true),
    F12("F12", true),
}

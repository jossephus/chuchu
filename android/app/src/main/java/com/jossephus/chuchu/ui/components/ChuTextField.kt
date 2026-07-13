package com.jossephus.chuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun ChuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = false,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
    ),
    autoFocus: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RectangleShape
    val focusRequester = remember { FocusRequester() }

    // Only request focus if autoFocus is true
    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    Column(modifier = modifier) {
        if (showLabel) {
            ChuText(
                text = "> $label",
                style = typography.labelSmall,
                color = if (focused) colors.accent else colors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .background(if (focused) colors.surface else Color.Transparent, shape)
                .border(
                    BorderStroke(1.dp, if (focused) colors.accent else colors.border),
                    shape,
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            singleLine = singleLine,
            textStyle = typography.body.copy(color = colors.textPrimary, textAlign = textAlign),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        BasicText(
                            text = placeholder,
                            style = typography.body.copy(color = colors.textMuted),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

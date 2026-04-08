package com.example.chuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.chuchu.ui.theme.ChuColors
import com.example.chuchu.ui.theme.ChuTypography

@Composable
fun ChuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)

    Column(modifier = modifier) {
        ChuText(
            text = label,
            style = typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, shape)
                .border(BorderStroke(1.dp, if (focused) colors.accent else colors.border), shape)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            singleLine = singleLine,
            textStyle = typography.body.copy(color = colors.textPrimary),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    BasicText(
                        text = placeholder,
                        style = typography.body.copy(color = colors.textMuted),
                    )
                }
                innerTextField()
            },
        )
    }
}

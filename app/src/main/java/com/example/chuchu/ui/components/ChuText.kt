package com.example.chuchu.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.example.chuchu.ui.theme.ChuColors
import com.example.chuchu.ui.theme.ChuTypography

@Composable
fun ChuText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ChuTypography.current.body,
    color: Color = ChuColors.current.textPrimary,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
    )
}

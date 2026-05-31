package com.jossephus.chuchu.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun ChuText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ChuTypography.current.body,
    color: Color = ChuColors.current.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

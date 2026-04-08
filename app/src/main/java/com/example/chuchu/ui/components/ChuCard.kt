package com.example.chuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.chuchu.ui.theme.ChuColors

@Composable
fun ChuCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ChuColors.current
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), shape),
        content = content,
    )
}

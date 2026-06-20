package com.jossephus.chuchu.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.theme.ChuColors

@Composable
fun ChuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ChuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val trackColor = if (checked) colors.accent else colors.surface
    val thumbColor = if (checked) colors.onAccent else colors.textPrimary
    val thumbOffset by animateDpAsState(if (checked) 17.dp else 2.dp, label = "chu-switch")

    Box(
        modifier = modifier
            .size(width = 36.dp, height = 18.dp)
            .background(trackColor)
            .border(1.dp, colors.border)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 1.dp)
                .size(16.dp)
                .background(thumbColor),
        )
    }
}

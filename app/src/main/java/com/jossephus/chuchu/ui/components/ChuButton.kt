package com.jossephus.chuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.theme.ChuColors

enum class ChuButtonVariant {
    Filled,
    Outlined,
    Ghost,
}

@Composable
fun ChuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ChuButtonVariant = ChuButtonVariant.Filled,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    testTag: String? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = ChuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(4.dp)

    val background: Color = when {
        !enabled -> colors.disabledSurface
        variant == ChuButtonVariant.Filled -> colors.accent
        else -> Color.Transparent
    }

    val border: BorderStroke? = when {
        !enabled && variant != ChuButtonVariant.Ghost -> BorderStroke(1.dp, colors.border)
        variant == ChuButtonVariant.Outlined -> BorderStroke(1.dp, colors.border)
        else -> null
    }

    val semanticsModifier = Modifier.semantics {
        if (testTag != null) {
            this.testTag = testTag
        }
        if (contentDescription != null) {
            this.contentDescription = contentDescription
        }
    }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .defaultMinSize(minHeight = 36.dp)
            .alpha(if (pressed && enabled) 0.7f else 1f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

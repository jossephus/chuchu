package com.jossephus.chuchu.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonSurface
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import kotlinx.coroutines.withTimeoutOrNull

private const val INITIAL_REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

@Composable
fun KeyboardAccessoryBar(
	line1Items: List<AccessoryKeyItem>,
	line2Items: List<AccessoryKeyItem>,
	modifierState: ModifierState,
	onAction: (AccessoryAction) -> Unit,
	chuchuKeyActive: Boolean = false,
	useSingleRow: Boolean = false,
	verticalPadding: Dp = 0.dp,
	modifier: Modifier = Modifier,
) {
	val buttonHeight = 30.dp
	val buttonPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
	val rowSpacing = 0.dp

	if (useSingleRow) {
		val allItems = line1Items + line2Items
		Row(
			modifier = modifier
				.fillMaxWidth()
				.padding(vertical = verticalPadding)
				.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(rowSpacing),
			verticalAlignment = Alignment.CenterVertically,
		) {
			allItems.forEach { item ->
				AccessoryButton(
					item = item,
					modifierState = modifierState,
					onAction = onAction,
					chuchuKeyActive = chuchuKeyActive,
					buttonHeight = buttonHeight,
					buttonPadding = buttonPadding,
					modifier = Modifier,
				)
			}
		}
		return
	}

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(vertical = verticalPadding),
		verticalArrangement = Arrangement.spacedBy(rowSpacing),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(rowSpacing),
			verticalAlignment = Alignment.CenterVertically,
		) {
			line1Items.forEach { item ->
				AccessoryButton(
					item = item,
					modifierState = modifierState,
					onAction = onAction,
					chuchuKeyActive = chuchuKeyActive,
					buttonHeight = buttonHeight,
					buttonPadding = buttonPadding,
					modifier = Modifier.weight(1f),
				)
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(rowSpacing),
			verticalAlignment = Alignment.CenterVertically,
		) {
			line2Items.forEach { item ->
				AccessoryButton(
					item = item,
					modifierState = modifierState,
					onAction = onAction,
					chuchuKeyActive = chuchuKeyActive,
					buttonHeight = buttonHeight,
					buttonPadding = buttonPadding,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

@Composable
private fun AccessoryButton(
	item: AccessoryKeyItem,
	modifierState: ModifierState,
	onAction: (AccessoryAction) -> Unit,
	chuchuKeyActive: Boolean,
	buttonHeight: Dp,
	buttonPadding: PaddingValues,
	modifier: Modifier,
) {
	val colors = ChuColors.current
	val typography = ChuTypography.current
	val labelStyle = if (item.labelFontFamily != null) {
		TextStyle(
			fontFamily = item.labelFontFamily,
			fontWeight = typography.label.fontWeight,
			fontStyle = typography.label.fontStyle,
			fontSize = 16.sp,
			lineHeight = 16.sp,
		)
	} else {
		typography.label
	}
	val labelColor = when {
		item.action is AccessoryAction.ChuchuKey && chuchuKeyActive -> colors.onAccent
		else -> colors.textPrimary
	}

	val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
	if (toggleModifier != null) {
		ToggleButton(
			label = item.label,
			enabled = modifierState.isEnabled(toggleModifier),
			onClick = { onAction(item.action) },
			modifier = modifier.height(buttonHeight),
			contentPadding = buttonPadding,
		)
	} else {
		val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key
		if (specialKey != null && specialKey.isRepeatable) {
			RepeatableAccessoryButton(
				item = item,
				onAction = onAction,
				buttonHeight = buttonHeight,
				buttonPadding = buttonPadding,
				labelStyle = labelStyle,
				modifier = modifier,
			)
		} else {
			val variant = when {
				item.action is AccessoryAction.ChuchuKey && chuchuKeyActive -> ChuButtonVariant.Filled
				else -> ChuButtonVariant.Ghost
			}
			ChuButton(
				onClick = { onAction(item.action) },
				variant = variant,
				modifier = modifier.height(buttonHeight),
				contentPadding = buttonPadding,
			) {
				AccessoryKeyLabel(item.label, style = labelStyle, color = labelColor, modifier = Modifier.fillMaxWidth())
			}
		}
	}
}

@Composable
private fun RepeatableAccessoryButton(
	item: AccessoryKeyItem,
	onAction: (AccessoryAction) -> Unit,
	buttonHeight: Dp,
	buttonPadding: PaddingValues,
	labelStyle: TextStyle,
	modifier: Modifier,
) {
	val colors = ChuColors.current
	val haptics = LocalHapticFeedback.current
	var pressed by remember { mutableStateOf(false) }
	val currentOnAction by rememberUpdatedState(onAction)

	Box(
		modifier = modifier
			.height(buttonHeight)
			.pointerInput(item.action) {
				awaitEachGesture {
					awaitFirstDown().consume()
					pressed = true
					currentOnAction(item.action)

					try {
						val releasedDuringDelay = withTimeoutOrNull(INITIAL_REPEAT_DELAY_MS) {
							waitForUpOrCancellation()
						}

						if (releasedDuringDelay == null) {
							haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							while (true) {
								currentOnAction(item.action)
								val released = withTimeoutOrNull(REPEAT_INTERVAL_MS) {
									waitForUpOrCancellation()
								}
								if (released != null) break
							}
						}
					} finally {
						pressed = false
					}
				}
			}
			.semantics {
				role = Role.Button
				contentDescription = item.label
				onClick(label = item.label) {
					currentOnAction(item.action)
					true
				}
			},
	) {
		ChuButtonSurface(
			modifier = Modifier.matchParentSize(),
			pressed = pressed,
			variant = ChuButtonVariant.Ghost,
			contentPadding = buttonPadding,
		) {
			AccessoryKeyLabel(item.label, style = labelStyle, color = colors.textPrimary, modifier = Modifier.fillMaxWidth())
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
	ChuButton(
		onClick = onClick,
		modifier = modifier,
		contentPadding = contentPadding,
		variant = if (enabled) ChuButtonVariant.Filled else ChuButtonVariant.Ghost,
	) {
		AccessoryKeyLabel(
			label,
			style = typography.label,
			color = if (enabled) colors.onAccent else colors.textSecondary,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * Renders a single-line accessory key label, shrinking the font size until the text fits the
 * available width.  This prevents labels like "Shft" or "Enter" from wrapping onto a second row
 * when many keys share a narrow row.
 */
@Composable
private fun AccessoryKeyLabel(
	text: String,
	style: TextStyle,
	color: Color,
	modifier: Modifier = Modifier,
) {
	val density = LocalDensity.current
	val textMeasurer = rememberTextMeasurer()
	val minFontSize = 8.sp
	val fontSizeStep = 0.5f

	BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
		val maxWidthPx = with(density) { maxWidth.toPx() }
		val fittedStyle = remember(text, style, maxWidthPx) {
			var currentStyle = style
			var fontSize = currentStyle.fontSize
			while (fontSize.value > minFontSize.value + fontSizeStep) {
				val result = textMeasurer.measure(
					text = text,
					style = currentStyle,
					maxLines = 1,
					overflow = TextOverflow.Clip,
					constraints = Constraints(maxWidth = maxWidthPx.toInt()),
				)
				if (result.lineCount <= 1 && result.size.width <= maxWidthPx) break
				fontSize = (fontSize.value - fontSizeStep).sp
				currentStyle = currentStyle.copy(fontSize = fontSize)
			}
			currentStyle
		}

		ChuText(
			text = text,
			style = fittedStyle,
			color = color,
			maxLines = 1,
			overflow = TextOverflow.Clip,
		)
	}
}

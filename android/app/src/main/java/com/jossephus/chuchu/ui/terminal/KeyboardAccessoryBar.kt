package com.jossephus.chuchu.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** Keys visible at once before the termux-style single-row bar starts scrolling. */
private const val TERMUX_SINGLE_ROW_KEYS_PER_SCREEN = 8

private const val MIN_LABEL_FONT_SIZE_SP = 8f
private const val LABEL_FONT_SIZE_STEP_SP = 0.5f

private val ButtonHeight = 30.dp
private val DefaultButtonPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
private val TermuxButtonPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)

/**
 * The accessory key bar that sits above the keyboard.
 *
 * The default style draws bordered, content-sized keys that wrap across up to two rows. The termux
 * style instead divides the bar into equal, borderless columns so keys line up on a grid, the way
 * Termux's extra-keys row does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardAccessoryBar(
    entries: List<ResolvedAccessoryEntry>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    chuchuKeyActive: Boolean = false,
    useSingleRow: Boolean = false,
    termuxStyle: Boolean = false,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    val buttonPadding = if (termuxStyle) TermuxButtonPadding else DefaultButtonPadding
    val keySpacing = if (termuxStyle) 0.dp else 6.dp
    val barHorizontalPadding = if (termuxStyle) 0.dp else horizontalPadding
    val barVerticalPadding = if (termuxStyle) 2.dp else verticalPadding

    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    val toggleGroup: (String) -> Unit = { id ->
        expandedGroupId = if (expandedGroupId == id) null else id
    }

    Column(modifier = modifier) {
        val expandedGroup = expandedGroupId?.let { groupId ->
            entries.filterIsInstance<ResolvedAccessoryEntry.Group>()
                .firstOrNull { it.group.id == groupId }
        }
        if (expandedGroup != null) {
            GroupPopover(
                group = expandedGroup.group,
                modifierState = modifierState,
                onAction = onAction,
                chuchuKeyActive = chuchuKeyActive,
                horizontalPadding = barHorizontalPadding,
            )
        }

        when {
            useSingleRow && termuxStyle -> BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = barVerticalPadding),
            ) {
                // Size keys off the bar width instead of their content so they stay on the grid,
                // then let the row scroll once they overflow.
                val keyWidth = maxWidth / TERMUX_SINGLE_ROW_KEYS_PER_SCREEN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(keySpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    entries.forEach { entry ->
                        AccessoryEntry(
                            entry = entry,
                            expanded = expandedGroupId == entry.id,
                            modifierState = modifierState,
                            onAction = onAction,
                            onToggleGroup = toggleGroup,
                            chuchuKeyActive = chuchuKeyActive,
                            termuxStyle = true,
                            buttonPadding = buttonPadding,
                            modifier = Modifier.width(keyWidth),
                        )
                    }
                }
            }

            useSingleRow -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = barHorizontalPadding, vertical = barVerticalPadding)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(keySpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = modifierState,
                        onAction = onAction,
                        onToggleGroup = toggleGroup,
                        chuchuKeyActive = chuchuKeyActive,
                        termuxStyle = false,
                        buttonPadding = buttonPadding,
                    )
                }
            }

            termuxStyle -> {
                val (topRow, bottomRow) = TerminalAccessoryLayoutStore.splitIntoTwoRows(entries)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = barVerticalPadding),
                    verticalArrangement = Arrangement.spacedBy(keySpacing),
                ) {
                    listOf(topRow, bottomRow).filter { it.isNotEmpty() }.forEach { rowEntries ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(keySpacing),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowEntries.forEach { entry ->
                                AccessoryEntry(
                                    entry = entry,
                                    expanded = expandedGroupId == entry.id,
                                    modifierState = modifierState,
                                    onAction = onAction,
                                    onToggleGroup = toggleGroup,
                                    chuchuKeyActive = chuchuKeyActive,
                                    termuxStyle = true,
                                    buttonPadding = buttonPadding,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            else -> FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = barHorizontalPadding, vertical = barVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(keySpacing),
                verticalArrangement = Arrangement.spacedBy(keySpacing),
                maxLines = 2,
            ) {
                entries.forEach { entry ->
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = modifierState,
                        onAction = onAction,
                        onToggleGroup = toggleGroup,
                        chuchuKeyActive = chuchuKeyActive,
                        termuxStyle = false,
                        buttonPadding = buttonPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupPopover(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    chuchuKeyActive: Boolean,
    horizontalPadding: Dp,
) {
    val colors = ChuColors.current
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 4.dp)
                .background(colors.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.children.forEach { child ->
                // The popover scrolls freely, so its keys keep the default look even when the bar
                // underneath is termux-styled.
                AccessoryButton(
                    item = child,
                    modifierState = modifierState,
                    onAction = onAction,
                    chuchuKeyActive = chuchuKeyActive,
                    termuxStyle = false,
                    buttonPadding = DefaultButtonPadding,
                )
            }
        }
    }
}

@Composable
private fun AccessoryEntry(
    entry: ResolvedAccessoryEntry,
    expanded: Boolean,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onToggleGroup: (String) -> Unit,
    chuchuKeyActive: Boolean,
    termuxStyle: Boolean,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is ResolvedAccessoryEntry.Single -> AccessoryButton(
            item = entry.item,
            modifierState = modifierState,
            onAction = onAction,
            chuchuKeyActive = chuchuKeyActive,
            termuxStyle = termuxStyle,
            buttonPadding = buttonPadding,
            modifier = modifier,
        )

        is ResolvedAccessoryEntry.Group -> GroupButton(
            group = entry.group,
            expanded = expanded,
            onToggle = { onToggleGroup(entry.group.id) },
            termuxStyle = termuxStyle,
            buttonPadding = buttonPadding,
            modifier = modifier,
        )
    }
}

@Composable
private fun GroupButton(
    group: AccessoryKeyGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    termuxStyle: Boolean,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuButton(
        onClick = onToggle,
        variant = keyVariant(active = expanded, termuxStyle = termuxStyle),
        modifier = modifier.height(ButtonHeight),
        contentPadding = buttonPadding,
    ) {
        AccessoryLabel(
            text = group.label,
            style = typography.label,
            color = if (expanded) colors.onAccent else colors.textPrimary,
            termuxStyle = termuxStyle,
        )
    }
}

@Composable
private fun AccessoryButton(
    item: AccessoryKeyItem,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    chuchuKeyActive: Boolean,
    termuxStyle: Boolean,
    buttonPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val labelStyle = item.labelFontFamily?.let { family ->
        TextStyle(
            fontFamily = family,
            fontWeight = typography.label.fontWeight,
            fontStyle = typography.label.fontStyle,
            fontSize = 16.sp,
            lineHeight = 16.sp,
        )
    } ?: typography.label

    val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
    val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key

    when {
        toggleModifier != null -> {
            val active = modifierState.isEnabled(toggleModifier)
            ChuButton(
                onClick = { onAction(item.action) },
                variant = keyVariant(active = active, termuxStyle = termuxStyle),
                modifier = modifier.height(ButtonHeight),
                contentPadding = buttonPadding,
            ) {
                // A leading dot marks a sticky modifier. Termux-style keys are too narrow for the
                // extra glyph, so there the fill alone carries the state.
                AccessoryLabel(
                    text = if (active && !termuxStyle) "• ${item.label}" else item.label,
                    style = typography.label,
                    color = if (active) colors.onAccent else colors.textSecondary,
                    termuxStyle = termuxStyle,
                )
            }
        }

        specialKey?.isRepeatable == true -> RepeatableAccessoryButton(
            item = item,
            onAction = onAction,
            termuxStyle = termuxStyle,
            buttonPadding = buttonPadding,
            labelStyle = labelStyle,
            modifier = modifier,
        )

        else -> {
            val active = item.action is AccessoryAction.ChuchuKey && chuchuKeyActive
            ChuButton(
                onClick = { onAction(item.action) },
                variant = keyVariant(active = active, termuxStyle = termuxStyle),
                modifier = modifier.height(ButtonHeight),
                contentPadding = buttonPadding,
            ) {
                AccessoryLabel(
                    text = item.label,
                    style = labelStyle,
                    color = if (active) colors.onAccent else colors.textPrimary,
                    termuxStyle = termuxStyle,
                )
            }
        }
    }
}

@Composable
private fun RepeatableAccessoryButton(
    item: AccessoryKeyItem,
    onAction: (AccessoryAction) -> Unit,
    termuxStyle: Boolean,
    buttonPadding: PaddingValues,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnAction by rememberUpdatedState(onAction)

    Box(
        modifier = modifier
            .height(ButtonHeight)
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
            modifier = if (termuxStyle) Modifier.matchParentSize() else Modifier.height(ButtonHeight),
            pressed = pressed,
            variant = keyVariant(active = false, termuxStyle = termuxStyle),
            contentPadding = buttonPadding,
        ) {
            AccessoryLabel(
                text = item.label,
                style = labelStyle,
                color = colors.textPrimary,
                termuxStyle = termuxStyle,
            )
        }
    }
}

private fun keyVariant(active: Boolean, termuxStyle: Boolean): ChuButtonVariant = when {
    active -> ChuButtonVariant.Filled
    termuxStyle -> ChuButtonVariant.Ghost
    else -> ChuButtonVariant.Outlined
}

/**
 * Draws a key label, shrinking the font until it fits when the key is termux-styled. Those keys are
 * sized off the bar width rather than their text, so a label like "Enter" would otherwise wrap onto
 * a second line in a narrow column.
 */
@Composable
private fun AccessoryLabel(
    text: String,
    style: TextStyle,
    color: Color,
    termuxStyle: Boolean,
) {
    if (!termuxStyle) {
        ChuText(text, style = style, color = color)
        return
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val fittedStyle = remember(text, style, maxWidthPx) {
            var candidate = style
            while (candidate.fontSize.value > MIN_LABEL_FONT_SIZE_SP + LABEL_FONT_SIZE_STEP_SP) {
                val measured = textMeasurer.measure(
                    text = text,
                    style = candidate,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = maxWidthPx.toInt()),
                )
                if (measured.lineCount <= 1 && measured.size.width <= maxWidthPx) break
                candidate = candidate.copy(
                    fontSize = (candidate.fontSize.value - LABEL_FONT_SIZE_STEP_SP).sp,
                )
            }
            candidate
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

package com.jossephus.chuchu.ui.screens.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.terminal.AccessoryKeyItem
import com.jossephus.chuchu.ui.terminal.KeyboardAccessoryBar
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import kotlin.math.roundToInt

@Composable
internal fun TerminalSettings(
    currentAccessoryLayoutIds: List<String>,
    onEditAccessoryLayout: () -> Unit,
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    onEditCustomActions: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val selectedItems = remember(currentAccessoryLayoutIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChuText("Terminal", style = typography.title)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChuText("Accessory keys", style = typography.label)
                    ChuText(
                        if (selectedItems.isEmpty()) "No keys enabled" else "${selectedItems.size} keys enabled",
                        style = typography.body,
                        color = colors.textMuted,
                    )
                }

                ChuButton(
                    onClick = onEditAccessoryLayout,
                    variant = ChuButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    ChuText("Customize", style = typography.label)
                }
            }

            if (selectedItems.isEmpty()) {
                ChuText(
                    "Choose the accessory keys you want in the terminal bar.",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant),
                ) {
                    KeyboardAccessoryBar(
                        items = selectedItems,
                        modifierState = ModifierState(),
                        onAction = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChuText("Custom actions", style = typography.label)
                    val actionCount = currentTerminalCustomKeyGroups.sumOf { it.actions.size }
                    ChuText(
                        if (actionCount == 0) "No custom actions" else "$actionCount actions",
                        style = typography.body,
                        color = colors.textMuted,
                    )
                }

                ChuButton(
                    onClick = onEditCustomActions,
                    variant = ChuButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    ChuText("Customize", style = typography.label)
                }
            }

            ChuText(
                "Create quick keys.",
                style = typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun AccessoryLayoutEditorSheet(
    visible: Boolean,
    selectedIds: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val reorderStepPx = with(density) { 40.dp.toPx() }
    val allItems = remember { TerminalAccessoryLayoutStore.catalog() }
    var draftSelectedIds by remember(selectedIds) {
        mutableStateOf(TerminalAccessoryLayoutStore.normalizeIds(selectedIds))
    }
    var previewDraggingId by remember { mutableStateOf<String?>(null) }
    var previewDragRemainderPx by remember { mutableFloatStateOf(0f) }
    var previewDragOffsetPx by remember { mutableFloatStateOf(0f) }

    val selectedItems = remember(draftSelectedIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(draftSelectedIds)
    }

    fun toggleItem(item: AccessoryKeyItem) {
        draftSelectedIds = if (item.id in draftSelectedIds) {
            draftSelectedIds.filterNot { it == item.id }
        } else {
            draftSelectedIds + item.id
        }
    }

    fun moveSelectedItem(itemId: String, direction: Int): Boolean {
        val index = draftSelectedIds.indexOf(itemId)
        if (index < 0) return false
        val target = (index + direction).coerceIn(0, draftSelectedIds.lastIndex)
        if (target == index) return false
        val updated = draftSelectedIds.toMutableList()
        updated.removeAt(index)
        updated.add(target, itemId)
        draftSelectedIds = updated
        return true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.textMuted),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChuText("Accessory keys", style = typography.headline)
                        ChuText(
                            "Choose which keys appear in the bar.",
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }

                    ChuButton(
                        onClick = onDismiss,
                        variant = ChuButtonVariant.Ghost,
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        ChuText("Close", style = typography.label, color = colors.textMuted)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChuText("Preview", style = typography.label)
                        ChuText(
                            if (selectedItems.isEmpty()) "0" else selectedItems.size.toString(),
                            style = typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }

                    if (selectedItems.isEmpty()) {
                        ChuText(
                            "No accessory keys selected.",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            selectedItems.forEach { item ->
                                key(item.id) {
                                    val isDragging = previewDraggingId == item.id
                                    PreviewKeyChip(
                                        item = item,
                                        dragging = isDragging,
                                        modifier = Modifier.pointerInput(item.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    previewDraggingId = item.id
                                                    previewDragRemainderPx = 0f
                                                    previewDragOffsetPx = 0f
                                                },
                                                onDragEnd = {
                                                    previewDraggingId = null
                                                    previewDragRemainderPx = 0f
                                                    previewDragOffsetPx = 0f
                                                },
                                                onDragCancel = {
                                                    previewDraggingId = null
                                                    previewDragRemainderPx = 0f
                                                    previewDragOffsetPx = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    previewDragRemainderPx += dragAmount.x
                                                    previewDragOffsetPx += dragAmount.x
                                                    if (previewDragRemainderPx >= reorderStepPx) {
                                                        val stepCount = (previewDragRemainderPx / reorderStepPx).toInt()
                                                        repeat(stepCount) {
                                                            if (!moveSelectedItem(item.id, 1)) return@repeat
                                                            previewDragRemainderPx -= reorderStepPx
                                                            previewDragOffsetPx -= reorderStepPx
                                                        }
                                                    } else if (previewDragRemainderPx <= -reorderStepPx) {
                                                        val stepCount = (-previewDragRemainderPx / reorderStepPx).toInt()
                                                        repeat(stepCount) {
                                                            if (!moveSelectedItem(item.id, -1)) return@repeat
                                                            previewDragRemainderPx += reorderStepPx
                                                            previewDragOffsetPx += reorderStepPx
                                                        }
                                                    }
                                                },
                                            )
                                        },
                                        dragOffsetPx = if (isDragging) previewDragOffsetPx else 0f,
                                    )
                                }
                            }
                        }

                        ChuText(
                            "Long-press a key in preview, then drag to place it where you want.",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allItems.forEach { item ->
                        val selected = item.id in draftSelectedIds
                        AccessoryChooserRow(
                            item = item,
                            selected = selected,
                            onClick = { toggleItem(item) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChuButton(
                        onClick = { draftSelectedIds = TerminalAccessoryLayoutStore.defaultLayoutIds() },
                        variant = ChuButtonVariant.Outlined,
                        modifier = Modifier.weight(1f),
                    ) {
                        ChuText("Reset", style = typography.label)
                    }

                    ChuButton(
                        onClick = { onSave(draftSelectedIds) },
                        modifier = Modifier.weight(1f),
                    ) {
                        ChuText("Save", style = typography.label, color = colors.onAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewKeyChip(
    item: AccessoryKeyItem,
    dragging: Boolean,
    dragOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Box(
        modifier = modifier
            .offset { IntOffset(x = dragOffsetPx.roundToInt(), y = 0) }
            .clip(RoundedCornerShape(6.dp))
            .background(if (dragging) colors.accent.copy(alpha = 0.2f) else colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ChuText(
            text = item.label,
            style = typography.label,
            color = if (dragging) colors.accent else colors.textPrimary,
        )
    }
}

@Composable
private fun AccessoryChooserRow(
    item: AccessoryKeyItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.surface else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ChuText(
                text = if (selected) "✓" else "+",
                style = typography.label,
                color = if (selected) colors.accent else colors.textMuted,
            )
        }

        ChuText(
            text = item.label,
            style = typography.label,
            color = colors.textPrimary,
        )
    }
}

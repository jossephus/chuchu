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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
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
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuSwitch
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
    accessoryBarSingleRow: Boolean,
    onAccessoryBarSingleRowChanged: (Boolean) -> Unit,
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    onEditCustomActions: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val selectedItems = remember(currentAccessoryLayoutIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ChuText("── ", style = typography.labelSmall, color = colors.textMuted)
        ChuText("TERMINAL", style = typography.labelSmall, color = colors.textMuted)
        ChuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChuCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChuText("accessory keys", style = typography.label)
                        ChuText(
                            if (selectedItems.isEmpty()) "no keys enabled" else "${selectedItems.size} keys enabled",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }

                    ChuButton(
                        onClick = onEditAccessoryLayout,
                        variant = ChuButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        ChuText("customize", style = typography.label)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ChuText("single-row accessory bar", style = typography.label)
                        ChuText(
                            "off = two rows (default), on = one horizontal row",
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                    ChuSwitch(
                        checked = accessoryBarSingleRow,
                        onCheckedChange = onAccessoryBarSingleRowChanged,
                    )
                }

                if (selectedItems.isEmpty()) {
                    ChuText(
                        "choose the accessory keys you want in the terminal bar.",
                        style = typography.body,
                        color = colors.textSecondary,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceVariant),
                    ) {
                        KeyboardAccessoryBar(
                            items = selectedItems,
                            modifierState = ModifierState(),
                            onAction = {},
                            useSingleRow = accessoryBarSingleRow,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        ChuCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChuText("custom actions", style = typography.label)
                        val actionCount = currentTerminalCustomKeyGroups.sumOf { it.actions.size }
                        ChuText(
                            if (actionCount == 0) "no custom actions" else "$actionCount actions",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }

                    ChuButton(
                        onClick = onEditCustomActions,
                        variant = ChuButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        ChuText("customize", style = typography.label)
                    }
                }

                ChuText(
                    "create quick keys.",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }
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
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChuText("accessory keys", style = typography.headline)
                        ChuText(
                            "choose which keys appear in the bar.",
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }

                    ChuButton(
                        onClick = onDismiss,
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        ChuText("x", style = typography.label, color = colors.textMuted)
                    }
                }

                ChuCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChuText("preview", style = typography.label)
                            ChuText(
                                if (selectedItems.isEmpty()) "0" else selectedItems.size.toString(),
                                style = typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }

                        if (selectedItems.isEmpty()) {
                            ChuText(
                                "no accessory keys selected.",
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
                            "long-press a key in preview, then drag to place it where you want.",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }
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
                        bracketed = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        ChuText("reset", style = typography.label)
                    }

                    ChuButton(
                        onClick = { onSave(draftSelectedIds) },
                        bracketed = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        ChuText("save", style = typography.label, color = colors.onAccent)
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
                .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ChuText(
                text = if (selected) "●" else "+",
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

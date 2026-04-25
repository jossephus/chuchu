package com.jossephus.chuchu.ui.screens.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.terminal.CustomActionModifier
import com.jossephus.chuchu.ui.terminal.TerminalCustomAction
import com.jossephus.chuchu.ui.terminal.TerminalCustomActionStore
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.terminal.decodeCustomActionValue
import com.jossephus.chuchu.ui.terminal.encodeCustomActionValue
import com.jossephus.chuchu.ui.terminal.modifierStateForCustomAction
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class CustomKeyValueDraft(
    val id: Long,
    val key: String,
    val value: String,
)

@Composable
internal fun TerminalCustomActionsEditorSheet(
    visible: Boolean,
    initialGroups: List<TerminalCustomKeyGroup>,
    onSave: (List<TerminalCustomKeyGroup>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val reorderStepPx = with(density) { 64.dp.toPx() }
    var draftItems by remember(initialGroups) {
        mutableStateOf(
            groupsToDraftItems(TerminalCustomActionStore.normalize(initialGroups)),
        )
    }
    var showAddRow by remember { mutableStateOf(false) }
    var showModifierDropdown by remember { mutableStateOf(false) }
    var enabledModifiers by remember { mutableStateOf(setOf<CustomActionModifier>()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingItemId by remember { mutableStateOf<Long?>(null) }
    var dragRemainderPx by remember { mutableFloatStateOf(0f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var reorderDirty by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var valueInput by remember { mutableStateOf("") }

    fun persistDraft(items: List<CustomKeyValueDraft>) {
        onSave(TerminalCustomActionStore.normalize(draftItemsToGroups(items)))
    }

    fun moveDraftItem(fromIndex: Int, direction: Int): Boolean {
        if (fromIndex !in draftItems.indices) return false
        val toIndex = (fromIndex + direction).coerceIn(0, draftItems.lastIndex)
        if (toIndex == fromIndex) return false
        val updated = draftItems.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)
        draftItems = updated
        reorderDirty = true
        if (editingIndex == fromIndex) {
            editingIndex = toIndex
        } else if (editingIndex == toIndex) {
            editingIndex = fromIndex
        }
        return true
    }

    fun registerDraftItem() {
        val baseKey = keyInput.trim()
        val baseValue = valueInput.trim().trimEnd('\n', '\r')
        val normalizedValue = encodeCustomActionValue(baseValue, enabledModifiers)
        if (baseKey.isEmpty() || normalizedValue.isEmpty()) return
        val nextId = (draftItems.maxOfOrNull { it.id } ?: 0L) + 1L
        val item = CustomKeyValueDraft(id = nextId, key = baseKey, value = normalizedValue)
        val nextItems = editingIndex?.let { index ->
            draftItems.toMutableList().also { list ->
                if (index in list.indices) {
                    list[index] = item.copy(id = list[index].id)
                } else {
                    list += item
                }
            }
        } ?: (draftItems + item)
        draftItems = nextItems
        persistDraft(nextItems)
        keyInput = ""
        valueInput = ""
        enabledModifiers = emptySet()
        showModifierDropdown = false
        showAddRow = false
        editingIndex = null
    }

    val valuePreview = remember(valueInput, enabledModifiers) {
        val baseValue = valueInput.trim().trimEnd('\n', '\r')
        readableActionPreview(baseValue, enabledModifiers)
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
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChuText("Custom actions", style = typography.headline)
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
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    draftItems.forEachIndexed { index, item ->
                        key(item.id) {
                            val isDragging = draggingItemId == item.id
                            CustomActionSwipeRow(
                                keyText = item.key,
                                valueText = item.value,
                                dragging = isDragging,
                                dragOffsetPx = if (isDragging) dragOffsetPx else 0f,
                                onEdit = {
                                    keyInput = item.key
                                    val decoded = decodeCustomActionValue(item.value)
                                    enabledModifiers = decoded.modifiers
                                    valueInput = decoded.text
                                    showModifierDropdown = true
                                    showAddRow = true
                                    editingIndex = index
                                },
                                onDelete = {
                                    val nextItems = draftItems.filterIndexed { rowIndex, _ -> rowIndex != index }
                                    draftItems = nextItems
                                    persistDraft(nextItems)
                                    if (editingIndex == index) {
                                        editingIndex = null
                                        showAddRow = false
                                        keyInput = ""
                                        valueInput = ""
                                        enabledModifiers = emptySet()
                                        showModifierDropdown = false
                                    }
                                },
                                onReorderStart = {
                                    draggingItemId = item.id
                                    dragRemainderPx = 0f
                                    dragOffsetPx = 0f
                                    reorderDirty = false
                                },
                                onReorderDrag = reorder@{ deltaY ->
                                    val draggingId = draggingItemId ?: return@reorder
                                    dragRemainderPx += deltaY
                                    dragOffsetPx += deltaY
                                    while (dragRemainderPx >= reorderStepPx) {
                                        val activeIndex = draftItems.indexOfFirst { it.id == draggingId }
                                        if (activeIndex < 0) break
                                        if (!moveDraftItem(activeIndex, 1)) break
                                        dragRemainderPx -= reorderStepPx
                                        dragOffsetPx -= reorderStepPx
                                    }
                                    while (dragRemainderPx <= -reorderStepPx) {
                                        val activeIndex = draftItems.indexOfFirst { it.id == draggingId }
                                        if (activeIndex < 0) break
                                        if (!moveDraftItem(activeIndex, -1)) break
                                        dragRemainderPx += reorderStepPx
                                        dragOffsetPx += reorderStepPx
                                    }
                                },
                                onReorderEnd = {
                                    if (reorderDirty) {
                                        persistDraft(draftItems)
                                    }
                                    draggingItemId = null
                                    dragRemainderPx = 0f
                                    dragOffsetPx = 0f
                                    reorderDirty = false
                                },
                            )
                        }
                    }

                    if (!showAddRow) {
                        ChuButton(
                            onClick = {
                                editingIndex = null
                                showAddRow = true
                            },
                            variant = ChuButtonVariant.Outlined,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ChuText("+ Add", style = typography.label)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ChuTextField(
                                value = keyInput,
                                onValueChange = { keyInput = it },
                                label = "Key",
                                placeholder = "a",
                                singleLine = true,
                                autoFocus = false,
                            )
                            ChuButton(
                                onClick = { showModifierDropdown = !showModifierDropdown },
                                variant = ChuButtonVariant.Outlined,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val enabledLabel = if (enabledModifiers.isEmpty()) {
                                    "No modifiers"
                                } else {
                                    CustomActionModifier.entries
                                        .filter { it in enabledModifiers }
                                        .joinToString(separator = "+") { it.label }
                                }
                                ChuText("Modifiers: $enabledLabel", style = typography.label)
                            }

                            AnimatedVisibility(visible = showModifierDropdown) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CustomActionModifier.entries.forEach { modifier ->
                                        val enabled = modifier in enabledModifiers
                                        ChuButton(
                                            onClick = {
                                                enabledModifiers = if (enabled) {
                                                    enabledModifiers - modifier
                                                } else {
                                                    enabledModifiers + modifier
                                                }
                                            },
                                            variant = if (enabled) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            ChuText(
                                                modifier.label,
                                                style = typography.labelSmall,
                                                color = if (enabled) colors.onAccent else colors.textPrimary,
                                            )
                                        }
                                    }
                                }
                            }

                            ChuTextField(
                                value = valueInput,
                                onValueChange = { updated ->
                                    if (updated.contains('\n') || updated.contains('\r')) {
                                        valueInput = updated
                                        registerDraftItem()
                                    } else {
                                        valueInput = updated
                                    }
                                },
                                label = "Value",
                                placeholder = ":q",
                                singleLine = false,
                                autoFocus = false,
                                modifier = Modifier.heightIn(min = 72.dp),
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                ChuText(
                                    text = if (valuePreview.isBlank()) "Preview: (empty)" else "Preview: $valuePreview",
                                    style = typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChuButton(
                                    onClick = ::registerDraftItem,
                                    variant = ChuButtonVariant.Outlined,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    ChuText("Add", style = typography.label)
                                }
                                ChuButton(
                                    onClick = {
                                        showAddRow = false
                                        keyInput = ""
                                        valueInput = ""
                                        enabledModifiers = emptySet()
                                        showModifierDropdown = false
                                        editingIndex = null
                                    },
                                    variant = ChuButtonVariant.Ghost,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    ChuText("Cancel", style = typography.label, color = colors.textMuted)
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun CustomActionSwipeRow(
    keyText: String,
    valueText: String,
    dragging: Boolean,
    dragOffsetPx: Float,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReorderStart: () -> Unit,
    onReorderDrag: (Float) -> Unit,
    onReorderEnd: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 120.dp.toPx() }
    val deleteThresholdPx = with(density) { 72.dp.toPx() }
    val offsetX = remember(keyText, valueText) { Animatable(0f) }
    val cardShape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .background(colors.error.copy(alpha = 0.78f))
                .padding(end = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ChuText("Delete", style = typography.label, color = colors.background)
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), dragOffsetPx.roundToInt()) }
                .fillMaxSize()
                .clip(cardShape)
                .background(if (dragging) colors.accent.copy(alpha = 0.12f) else colors.surface)
                .padding(horizontal = 12.dp)
                .clickable(onClick = onEdit)
                .pointerInput(keyText, valueText) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            if (offsetX.value <= -deleteThresholdPx) {
                                onDelete()
                            } else {
                                scope.launch { offsetX.animateTo(0f, animationSpec = tween(140)) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, animationSpec = tween(140)) }
                        },
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(40.dp)
                    .width(28.dp)
                    .pointerInput(keyText, valueText) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onReorderStart() },
                            onDragEnd = { onReorderEnd() },
                            onDragCancel = { onReorderEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onReorderDrag(dragAmount.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                ChuText("⋮", style = typography.label, color = colors.textMuted)
            }
            ChuText(keyText, style = typography.label, modifier = Modifier.weight(1f))
            ChuText(readableStoredActionPreview(valueText), style = typography.body, color = colors.textSecondary)
        }
    }
}

private fun readableActionPreview(baseValue: String, modifiers: Set<CustomActionModifier>): String {
    if (baseValue.isEmpty()) {
        if (CustomActionModifier.Enter in modifiers) return "⏎"
        return ""
    }
    val transformed = modifierStateForCustomAction(modifiers - CustomActionModifier.Cmd).applyToText(baseValue)
    val prefix = if (CustomActionModifier.Cmd in modifiers) "Cmd+" else ""
    val suffix = if (CustomActionModifier.Enter in modifiers) "⏎" else ""
    return prefix + readablePreview(transformed) + suffix
}

private fun readableStoredActionPreview(stored: String): String {
    val decoded = decodeCustomActionValue(stored)
    return readableActionPreview(decoded.text, decoded.modifiers)
}

private fun readablePreview(value: String): String {
    return buildString {
        value.forEach { ch ->
            when (ch) {
                '\u001b' -> append("Esc+")
                '\n' -> append('⏎')
                in '\u0000'..'\u001f' -> append("^${(ch.code + 64).toChar()}")
                else -> append(ch)
            }
        }
    }
}

private fun groupsToDraftItems(groups: List<TerminalCustomKeyGroup>): List<CustomKeyValueDraft> {
    var nextId = 1L
    return groups.flatMap { group ->
        group.actions.map { action ->
            CustomKeyValueDraft(id = nextId++, key = group.keyLabel, value = action.payload)
        }
    }
}

private fun draftItemsToGroups(items: List<CustomKeyValueDraft>): List<TerminalCustomKeyGroup> {
    val grouped = linkedMapOf<String, MutableList<TerminalCustomAction>>()
    items.forEach { item ->
        val key = item.key.trim()
        val value = item.value
        if (key.isEmpty() || value.isEmpty()) return@forEach
        val actions = grouped.getOrPut(key) { mutableListOf() }
        actions += TerminalCustomAction(label = key, payload = value)
    }
    return grouped.map { (key, actions) -> TerminalCustomKeyGroup(keyLabel = key, actions = actions) }
}

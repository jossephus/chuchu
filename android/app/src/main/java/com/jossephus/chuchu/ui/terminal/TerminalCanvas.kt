package com.jossephus.chuchu.ui.terminal

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun TerminalCanvas(
    snapshot: TerminalSnapshot,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
    cursorColor: Color = Color.White.copy(alpha = 0.28f),
    cursorTextColor: Color? = null,
    selectionBackgroundColor: Color = Color(0x663B82F6),
    selectionForegroundColor: Color? = null,
    selectionResetKey: Int = 0,
    onResize: (cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, widthPx: Int, heightPx: Int) -> Unit =
        { _, _, _, _, _, _ -> },
    onTap: () -> Unit = {},
    onPrimaryClick: (x: Float, y: Float) -> Unit = { _, _ -> },
    onScroll: (delta: Int) -> Unit = {},
    onZoom: (zoomFactor: Float) -> Unit = {},
    onSelectionChanged: (selectionActive: Boolean, text: String?, anchorOffsetX: Float, anchorOffsetY: Float) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastResizedGrid by remember { mutableStateOf(Pair(0, 0)) }
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    var selectionAnchorOffset by remember { mutableStateOf(Offset.Zero) }
    val doubleTapState = remember { DoubleTapState() }
    val androidViewConfiguration = remember(context) { ViewConfiguration.get(context) }
    val touchSlopPx = remember(androidViewConfiguration) { androidViewConfiguration.scaledTouchSlop.toFloat() }
    val longPressTimeoutMillis = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    val doubleTapTimeoutMillis = remember { ViewConfiguration.getDoubleTapTimeout().toLong() }
    val doubleTapSlopPx = remember(androidViewConfiguration) { androidViewConfiguration.scaledDoubleTapSlop.toFloat() }
    val typeface = remember {
        runCatching {
            Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
        }.getOrDefault(Typeface.MONOSPACE)
    }
    val textPaint = remember(typeface, fontSizePx) {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            textSize = fontSizePx
            this.typeface = typeface
        }
    }
    val bgPaint = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
    }
    val cursorPaint = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
    }
    val glyphCache = remember {
        HashMap<Int, String>(256).apply {
            for (cp in 33..126) {
                this[cp] = cp.toChar().toString()
            }
        }
    }
    val drawBuffer = remember { StringBuilder(256) }
    val fontMetrics = textPaint.fontMetrics
    val measuredHeight = fontMetrics.descent - fontMetrics.ascent
    val cellHeightPx = if (measuredHeight > 1f) measuredHeight else 16f
    val measuredWidth = textPaint.measureText("M")
    val cellWidthPx = if (measuredWidth > 1f) measuredWidth else 8f
    val baselineOffset = -fontMetrics.ascent
    val cellWidthInt = max(1, ceil(cellWidthPx).toInt())
    val cellHeightInt = max(1, ceil(cellHeightPx).toInt())
    val selectionBackgroundArgb = selectionBackgroundColor.toArgb()
    val selectionForegroundArgb = selectionForegroundColor?.toArgb()
    val hasSelectionFg = selectionForegroundArgb != null
    val cursorColorArgb = cursorColor.toArgb()
    val cursorTextColorArgb = cursorTextColor?.toArgb()

    val currentOnSelectionChanged = rememberUpdatedState(onSelectionChanged)

    LaunchedEffect(selectionResetKey) {
        selection = null
        selectionAnchorOffset = Offset.Zero
    }

    val currentSelection = selection
    if (currentSelection != null) {
        LaunchedEffect(snapshot, currentSelection) {
            val text = extractSelectionText(snapshot, currentSelection)
            currentOnSelectionChanged.value(true, text, selectionAnchorOffset.x, selectionAnchorOffset.y)
        }
    } else {
        LaunchedEffect(Unit) {
            currentOnSelectionChanged.value(false, null, 0f, 0f)
        }
    }

    LaunchedEffect(canvasSize, cellWidthPx, cellHeightPx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = max(1, floor(canvasSize.width / cellWidthPx).toInt())
        val rows = max(1, floor(canvasSize.height / cellHeightPx).toInt())
        val grid = Pair(cols, rows)
        if (grid != lastResizedGrid) {
            lastResizedGrid = grid
            onResize(cols, rows, cellWidthInt, cellHeightInt, canvasSize.width, canvasSize.height)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                canvasSize = size
            }
            .pointerInput(cellWidthPx, cellHeightPx, selectionResetKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragRemainder = 0f
                    var lastPinchDistance: Float? = null
                    var didScroll = false
                    var didPinch = false
                    var didDragGesture = false
                    var didSelect = false
                    var selectionCleared = false
                    var lastSinglePointerId = down.id
                    var longPressActive = false
                    var lastEventUptime = down.uptimeMillis
                    val longPressDeadline = down.uptimeMillis + longPressTimeoutMillis

                    while (true) {
                        val timeoutMs = (longPressDeadline - lastEventUptime).coerceAtLeast(1L)
                        val event = if (!longPressActive && !didScroll && !didPinch && !didSelect && !didDragGesture) {
                            withTimeoutOrNull(timeoutMs) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            val s = snapshot
                            val selectedCell = s.cellAt(down.position.x, down.position.y, cellWidthPx, cellHeightPx)
                            selection = selectedCell?.let { TerminalSelection(it, it) }
                            if (selectedCell != null) {
                                selectionAnchorOffset = down.position
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                didSelect = true
                                longPressActive = true
                            }
                            continue
                        }

                        lastEventUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventUptime
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (didSelect) {
                                break
                            }
                            if (!didScroll && !didPinch && !didDragGesture) {
                                val tapTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventUptime
                                val tapPos = down.position
                                val timeSinceLastTap = tapTime - doubleTapState.lastTime
                                val distSinceLastTap = hypot(
                                    (tapPos.x - doubleTapState.lastPos.x).toDouble(),
                                    (tapPos.y - doubleTapState.lastPos.y).toDouble(),
                                ).toFloat()
                                doubleTapState.lastTime = tapTime
                                doubleTapState.lastPos = tapPos

                                if (timeSinceLastTap < doubleTapTimeoutMillis && distSinceLastTap < doubleTapSlopPx) {
                                    val s = snapshot
                                    val cellIdx = s.cellAt(tapPos.x, tapPos.y, cellWidthPx, cellHeightPx)
                                    if (cellIdx != null) {
                                        val wordRange = s.wordAt(cellIdx)
                                        if (wordRange != null) {
                                            selection = TerminalSelection(wordRange.first, wordRange.last)
                                            selectionAnchorOffset = tapPos
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                } else {
                                    if (selection != null) {
                                        selection = null
                                        selectionAnchorOffset = Offset.Zero
                                    }
                                    onPrimaryClick(tapPos.x, tapPos.y)
                                    onTap()
                                }
                            }
                            break
                        }

                        if (pressed.size >= 2) {
                            didPinch = true
                            val first = pressed[0].position
                            val second = pressed[1].position
                            val distance = hypot(
                                (first.x - second.x).toDouble(),
                                (first.y - second.y).toDouble(),
                            ).toFloat()
                            val previous = lastPinchDistance
                            if (previous != null && previous > 0f && distance > 0f) {
                                val zoomFactor = distance / previous
                                if (abs(zoomFactor - 1f) > 0.02f) {
                                    onZoom(zoomFactor)
                                }
                            }
                            lastPinchDistance = distance
                            pressed.forEach { change ->
                                if (change.position != change.previousPosition) change.consume()
                            }
                            continue
                        }

                        lastPinchDistance = null
                        val change = pressed.firstOrNull { it.id == lastSinglePointerId } ?: pressed.first().also {
                            lastSinglePointerId = it.id
                        }

                        // Selection drag takes priority once activated
                        val s = snapshot
                        val selectedCell = s.cellAt(change.position.x, change.position.y, cellWidthPx, cellHeightPx)
                        if (longPressActive && selectedCell != null) {
                            val currentSelection = selection
                            if (currentSelection == null || currentSelection.focusIndex != selectedCell) {
                                selection = (currentSelection ?: TerminalSelection(selectedCell, selectedCell)).copy(focusIndex = selectedCell)
                            }
                            if (change.position != change.previousPosition) {
                                change.consume()
                            }
                            continue
                        }

                        val movedDistance = hypot(
                            (change.position.x - down.position.x).toDouble(),
                            (change.position.y - down.position.y).toDouble(),
                        ).toFloat()
                        if (movedDistance > touchSlopPx) {
                            didDragGesture = true
                            if (selection != null && !selectionCleared) {
                                selection = null
                                selectionAnchorOffset = Offset.Zero
                                selectionCleared = true
                            }
                        }
                        // Accumulate scroll deltas even before touch slop is exceeded.
                        // This gives responsive scrolling from the first move event.
                        // The didScroll/didDragGesture flags still gate gesture
                        // classification, so taps are preserved.
                        val dragAmount = change.position.y - change.previousPosition.y
                        dragRemainder += dragAmount / cellHeightPx

                        if (abs(dragRemainder) >= 1f) {
                            val delta = dragRemainder.toInt()
                            dragRemainder -= delta
                            if (delta != 0) {
                                didScroll = true
                                onScroll(-delta)
                            }
                        }

                        if (change.position != change.previousPosition) {
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val cols = max(snapshot.cols, 1)
        val rows = max(snapshot.rows, 1)
        val cellWidth = cellWidthPx
        val cellHeight = cellHeightPx
        val sel = selection?.normalized(snapshot.codepoints.size)
        val selStart = sel?.first ?: -1
        val selEnd = sel?.last ?: -1
        val hasSel = sel != null

        drawRect(color = Color(snapshot.defaultBgArgb))

        drawIntoCanvas { canvas ->
            val nCanvas = canvas.nativeCanvas
            val sb = drawBuffer
            val defaultBg = snapshot.defaultBgArgb

            for (row in 0 until rows) {
                val rowStart = row * cols
                val y = row * cellHeight
                val baseline = y + baselineOffset

                // Background runs
                var i = rowStart
                val rowEnd = rowStart + cols
                while (i < rowEnd) {
                    val iSelected = hasSel && i in selStart..selEnd
                    val bg = if (iSelected) selectionBackgroundArgb else snapshot.bgArgb[i]
                    var j = i + 1
                    while (j < rowEnd) {
                        val jSelected = hasSel && j in selStart..selEnd
                        val nextBg = if (jSelected) selectionBackgroundArgb else snapshot.bgArgb[j]
                        if (nextBg != bg) break
                        j++
                    }
                    if (iSelected || bg != defaultBg) {
                        bgPaint.color = bg
                        nCanvas.drawRect(
                            (i - rowStart) * cellWidth,
                            y,
                            (j - rowStart) * cellWidth,
                            y + cellHeight,
                            bgPaint,
                        )
                    }
                    i = j
                }

                // Text runs
                i = rowStart
                while (i < rowEnd) {
                    val cp = snapshot.codepoints[i]
                    if (cp == 0 || cp == 32) { i++; continue }

                    val fg = if (hasSel && i in selStart..selEnd && hasSelectionFg) {
                        selectionForegroundArgb
                    } else {
                        snapshot.fgArgb[i]
                    }
                    sb.setLength(0)
                    val startCol = i - rowStart

                    while (i < rowEnd) {
                        val c = snapshot.codepoints[i]
                        val nextFg = if (hasSel && i in selStart..selEnd && hasSelectionFg) {
                            selectionForegroundArgb
                        } else {
                            snapshot.fgArgb[i]
                        }
                        if ((c == 0 || c == 32) || nextFg != fg) break
                        val glyph = glyphCache.getOrPut(c) { String(Character.toChars(c)) }
                        sb.append(glyph)
                        i++
                    }

                    textPaint.color = fg
                    nCanvas.drawText(sb.toString(), startCol * cellWidth, baseline, textPaint)
                }
            }

            // Images
            for (img in snapshot.images) {
                val srcRect = Rect(img.srcX, img.srcY, img.srcX + img.srcW, img.srcY + img.srcH)
                val dstRect = RectF(
                    img.destX.toFloat(),
                    img.destY.toFloat(),
                    (img.destX + img.destW).toFloat(),
                    (img.destY + img.destH).toFloat(),
                )
                nCanvas.drawBitmap(img.bitmap, srcRect, dstRect, null)
            }

            if (snapshot.cursorVisible && snapshot.cursorX in 0 until cols && snapshot.cursorY in 0 until rows) {
                val cursorLeft = snapshot.cursorX * cellWidth
                val cursorTop = snapshot.cursorY * cellHeight
                cursorPaint.color = cursorColorArgb
                nCanvas.drawRect(
                    cursorLeft,
                    cursorTop,
                    cursorLeft + cellWidth,
                    cursorTop + cellHeight,
                    cursorPaint,
                )

                val cursorIndex = snapshot.cursorY * cols + snapshot.cursorX
                if (cursorTextColorArgb != null && cursorIndex in snapshot.codepoints.indices) {
                    val codepoint = snapshot.codepoints[cursorIndex]
                    if (codepoint != 0 && codepoint != 32) {
                        val glyph = glyphCache.getOrPut(codepoint) {
                            String(Character.toChars(codepoint))
                        }
                        textPaint.color = cursorTextColorArgb
                        nCanvas.drawText(
                            glyph,
                            cursorLeft,
                            cursorTop + baselineOffset,
                            textPaint,
                        )
                    }
                }
            }
        }
    }
}

private data class TerminalSelection(
    val anchorIndex: Int,
    val focusIndex: Int,
) {
    fun normalized(cellCount: Int): IntRange? {
        if (cellCount <= 0) return null
        val start = minOf(anchorIndex, focusIndex).coerceIn(0, cellCount - 1)
        val end = maxOf(anchorIndex, focusIndex).coerceIn(0, cellCount - 1)
        return start..end
    }
}

private fun TerminalSnapshot.cellAt(x: Float, y: Float, cellWidthPx: Float, cellHeightPx: Float): Int? {
    if (cols <= 0 || rows <= 0 || cellWidthPx <= 0f || cellHeightPx <= 0f) return null
    val col = floor(x / cellWidthPx).toInt().coerceIn(0, cols - 1)
    val row = floor(y / cellHeightPx).toInt().coerceIn(0, rows - 1)
    return row * cols + col
}

/** Find the word boundaries around [cellIndex], expanding left and right within the same row. */
private fun TerminalSnapshot.wordAt(cellIndex: Int): IntRange? {
    if (cols <= 0 || cellIndex !in codepoints.indices) return null
    val row = cellIndex / cols
    val rowStart = row * cols
    val rowEnd = rowStart + cols - 1

    val cp = codepoints[cellIndex]
    if (cp == 0 || cp == 32) return null

    var start = cellIndex
    while (start > rowStart && codepoints[start - 1] != 0 && codepoints[start - 1] != 32) {
        start--
    }
    var end = cellIndex
    while (end < rowEnd && codepoints[end + 1] != 0 && codepoints[end + 1] != 32) {
        end++
    }
    return start..end
}

private fun extractSelectionText(snapshot: TerminalSnapshot, selection: TerminalSelection?): String? {
    if (selection == null) return null
    val range = selection.normalized(snapshot.codepoints.size) ?: return null
    if (snapshot.cols <= 0) return null

    val startRow = range.first / snapshot.cols
    val endRow = range.last / snapshot.cols
    val builder = StringBuilder(range.last - range.first + 1 + (endRow - startRow))

    for (row in startRow..endRow) {
        val rowStart = row * snapshot.cols
        val from = maxOf(range.first, rowStart)
        val until = minOf(range.last, rowStart + snapshot.cols - 1)
        // Find last non-blank cell in this row range to trim trailing whitespace
        var lastContentIdx = until
        while (lastContentIdx >= from) {
            val cp = snapshot.codepoints[lastContentIdx]
            if (cp != 0 && cp != 32) break
            lastContentIdx--
        }
        for (index in from..lastContentIdx) {
            val codepoint = snapshot.codepoints[index]
            if (codepoint == 0 || codepoint == 32) {
                builder.append(' ')
            } else {
                builder.appendCodePoint(codepoint)
            }
        }
        if (row != endRow) {
            builder.append('\n')
        }
    }

    return builder.toString()
}

/** Tracks double-tap timing/position without triggering Compose recomposition. */
private class DoubleTapState {
    var lastTime: Long = 0L
    var lastPos: Offset = Offset.Zero
}

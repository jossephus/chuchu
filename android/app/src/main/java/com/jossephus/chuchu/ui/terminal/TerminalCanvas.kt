package com.jossephus.chuchu.ui.terminal

import com.jossephus.chuchu.R
import com.jossephus.chuchu.service.terminal.GhosttyBridge

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

import androidx.core.content.res.ResourcesCompat
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
import androidx.compose.runtime.withFrameNanos
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
import com.jossephus.chuchu.ui.theme.LocalChuFont
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun TerminalCanvas(
    snapshot: TerminalSnapshot,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
    fitSnapshotToCanvas: Boolean = false,
    enableGestures: Boolean = true,
    cursorColor: Color = Color.White.copy(alpha = 0.28f),
    cursorTextColor: Color? = null,
    selectionBackgroundColor: Color = Color(0x663B82F6),
    selectionForegroundColor: Color? = null,
    selectionResetKey: Int = 0,
    terminalHandle: Long = 0,
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
    val fontOption = LocalChuFont.current
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
    val primaryTypeface = remember(context, fontOption) {
        runCatching {
            ResourcesCompat.getFont(context, fontOption.regularFontResId)
                ?: Typeface.MONOSPACE
        }.getOrDefault(Typeface.MONOSPACE)
    }
    val symbolsTypeface = remember(context) {
        runCatching {
            ResourcesCompat.getFont(context, R.font.symbols_nerd_font_mono_regular)
                ?: Typeface.MONOSPACE
        }.getOrDefault(Typeface.MONOSPACE)
    }
    val primaryTextPaint = remember(primaryTypeface, fontSizePx) {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            textSize = fontSizePx
            typeface = primaryTypeface
        }
    }
    val symbolsTextPaint = remember(symbolsTypeface, fontSizePx) {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            textSize = fontSizePx
            typeface = symbolsTypeface
        }
    }
    // System typeface paint used for color emoji + ZWJ/VS16 shaping. The
    // "symbols" Nerd Font has no color emoji or ZWJ shaping, so emoji clusters
    // (families, flags, ❤️‍🔥, 🧑‍💻, …) must go through this paint to hit
    // Android's NotoColorEmoji fallback.
    val emojiTextPaint = remember(fontSizePx) {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            textSize = fontSizePx
            typeface = Typeface.DEFAULT
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
    val drawBuffer = remember { StringBuilder(256) }
    val singleGlyphCache = remember {
        HashMap<Int, String>(256).apply {
            for (cp in 33..126) this[cp] = cp.toChar().toString()
        }
    }
    // Single-codepoint paint choice cache. 0 = primary (text font),
    // 1 = symbols (Nerd Font icons / PUA), 2 = emoji (system default).
    val singlePaintChoiceCache = remember(primaryTypeface, symbolsTypeface) { HashMap<Int, Int>(256) }
    // Multi-codepoint grapheme cluster paint choice cache.
    val clusterPaintChoiceCache = remember(primaryTypeface, symbolsTypeface) { HashMap<String, Int>(64) }
    val fontMetrics = primaryTextPaint.fontMetrics
    val measuredHeight = fontMetrics.descent - fontMetrics.ascent
    val cellHeightPx = if (measuredHeight > 1f) measuredHeight else 16f
    val measuredWidth = primaryTextPaint.measureText("M")
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
    val currentSnapshot = rememberUpdatedState(snapshot)
    val currentOnScroll = rememberUpdatedState(onScroll)
    val ghosttyBridge = remember { GhosttyBridge() }

    val scrollDeltaChannel = remember { Channel<Int>(capacity = Channel.UNLIMITED) }
    LaunchedEffect(scrollDeltaChannel) {
        while (isActive) {
            val first = scrollDeltaChannel.receive()
            var accumulated = first
            while (true) {
                val next = scrollDeltaChannel.tryReceive().getOrNull() ?: break
                accumulated += next
            }
            if (accumulated != 0) {
                currentOnScroll.value(accumulated)
            }
            withFrameNanos { }
        }
    }

    LaunchedEffect(selectionResetKey) {
        selection = null
        selectionAnchorOffset = Offset.Zero
    }

    val currentSelection = selection
    if (currentSelection != null) {
        LaunchedEffect(snapshot, currentSelection) {
            val text = if (terminalHandle != 0L && snapshot.cols > 0) {
                val normalized = currentSelection.normalized(snapshot.codepoints.size)
                if (normalized != null) {
                    ghosttyBridge.nativeFormatSelectionRange(terminalHandle, normalized.first, normalized.last)
                } else null
            } else {
                extractSelectionText(snapshot, currentSelection)
            }
            currentOnSelectionChanged.value(true, text, selectionAnchorOffset.x, selectionAnchorOffset.y)
        }
    } else {
        LaunchedEffect(currentSelection) {
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

    val canvasModifier = modifier
        .fillMaxSize()
        .onSizeChanged { size ->
            canvasSize = size
        }
        .let { baseModifier ->
            if (!enableGestures) {
                baseModifier
            } else {
                baseModifier.pointerInput(cellWidthPx, cellHeightPx, selectionResetKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun toSnapshotSpace(position: Offset, s: TerminalSnapshot): Offset {
                            if (!fitSnapshotToCanvas) return position
                            val cols = max(s.cols, 1)
                            val rows = max(s.rows, 1)
                            val contentWidth = cols * cellWidthPx
                            val contentHeight = rows * cellHeightPx
                            if (contentWidth <= 0f || contentHeight <= 0f) return position
                            val scale = minOf(canvasSize.width / contentWidth, canvasSize.height / contentHeight)
                            if (scale <= 0f) return position
                            val offsetX = ((canvasSize.width - (contentWidth * scale)) * 0.5f).coerceAtLeast(0f)
                            val offsetY = ((canvasSize.height - (contentHeight * scale)) * 0.5f).coerceAtLeast(0f)
                            return Offset((position.x - offsetX) / scale, (position.y - offsetY) / scale)
                        }
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
                                val s = currentSnapshot.value
                                val downPos = toSnapshotSpace(down.position, s)
                                val selectedCell = s.cellAt(downPos.x, downPos.y, cellWidthPx, cellHeightPx)
                                selection = selectedCell?.let { TerminalSelection(it, it) }
                                if (selectedCell != null) {
                                    selectionAnchorOffset = downPos
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
                                    val s = currentSnapshot.value
                                    val tapPos = toSnapshotSpace(down.position, s)
                                    val timeSinceLastTap = tapTime - doubleTapState.lastTime
                                    val distSinceLastTap = hypot(
                                        (tapPos.x - doubleTapState.lastPos.x).toDouble(),
                                        (tapPos.y - doubleTapState.lastPos.y).toDouble(),
                                    ).toFloat()
                                    doubleTapState.lastTime = tapTime
                                    doubleTapState.lastPos = tapPos

                                    if (timeSinceLastTap < doubleTapTimeoutMillis && distSinceLastTap < doubleTapSlopPx) {
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
                                val s = currentSnapshot.value
                                val first = toSnapshotSpace(pressed[0].position, s)
                                val second = toSnapshotSpace(pressed[1].position, s)
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
                            val s = currentSnapshot.value
                            val changePos = toSnapshotSpace(change.position, s)
                            val changePrevPos = toSnapshotSpace(change.previousPosition, s)
                            val downPos = toSnapshotSpace(down.position, s)
                            val selectedCell = s.cellAt(changePos.x, changePos.y, cellWidthPx, cellHeightPx)
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

                            val dragX = changePos.x - changePrevPos.x
                            val dragY = changePos.y - changePrevPos.y
                            val movedDistance = hypot(
                                (changePos.x - downPos.x).toDouble(),
                                (changePos.y - downPos.y).toDouble(),
                            ).toFloat()
                            if (movedDistance > touchSlopPx) {
                                didDragGesture = true
                                if (selection != null && !selectionCleared) {
                                    selection = null
                                    selectionAnchorOffset = Offset.Zero
                                    selectionCleared = true
                                }
                            }
                            val verticalIntent = abs(dragY) > abs(dragX) * 1.2f
                            if (verticalIntent) {
                                dragRemainder += dragY / cellHeightPx
                            }

                            if (didDragGesture && abs(dragRemainder) >= 1f) {
                                val delta = dragRemainder.toInt()
                                dragRemainder -= delta
                                if (delta != 0) {
                                    didScroll = true
                                    scrollDeltaChannel.trySend(-delta)
                                }
                            }

                            if (change.position != change.previousPosition) {
                                change.consume()
                            }
                        }
                    }
                }
            }
        }

    Canvas(modifier = canvasModifier) {
        val cols = max(snapshot.cols, 1)
        val rows = max(snapshot.rows, 1)
        val cellWidth = cellWidthPx
        val cellHeight = cellHeightPx
        val contentWidth = cols * cellWidth
        val contentHeight = rows * cellHeight
        val previewScale = if (fitSnapshotToCanvas && contentWidth > 0f && contentHeight > 0f) {
            minOf(size.width / contentWidth, size.height / contentHeight)
        } else {
            1f
        }
        val contentOffsetX = if (fitSnapshotToCanvas) {
            ((size.width - (contentWidth * previewScale)) * 0.5f).coerceAtLeast(0f)
        } else {
            0f
        }
        val contentOffsetY = if (fitSnapshotToCanvas) {
            ((size.height - (contentHeight * previewScale)) * 0.5f).coerceAtLeast(0f)
        } else {
            0f
        }
        val sel = selection?.normalized(snapshot.codepoints.size)
        val selStart = sel?.first ?: -1
        val selEnd = sel?.last ?: -1
        val hasSel = sel != null

        drawRect(color = Color(snapshot.defaultBgArgb))

        drawIntoCanvas { canvas ->
            val nCanvas = canvas.nativeCanvas
            nCanvas.save()
            nCanvas.translate(contentOffsetX, contentOffsetY)
            if (previewScale != 1f) {
                nCanvas.scale(previewScale, previewScale)
            }
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
                    if (cp == 0 || cp == 32) {
                        i++
                        continue
                    }

                    val fg = if (hasSel && i in selStart..selEnd && hasSelectionFg) {
                        selectionForegroundArgb
                    } else {
                        snapshot.fgArgb[i]
                    }
                    val firstExtras = snapshot.graphemeExtras[i]
                    val firstGlyph = if (firstExtras == null || firstExtras.isEmpty()) null else snapshot.glyphAt(i)
                    val firstPaintChoice = if (firstGlyph == null) {
                        pickPaintChoice(
                            codepoint = cp,
                            glyphCache = singleGlyphCache,
                            cache = singlePaintChoiceCache,
                            primaryPaint = primaryTextPaint,
                            symbolsPaint = symbolsTextPaint,
                        )
                    } else {
                        pickPaintChoice(
                            glyph = firstGlyph,
                            cache = clusterPaintChoiceCache,
                            primaryPaint = primaryTextPaint,
                            symbolsPaint = symbolsTextPaint,
                        )
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
                        if (c == 0 || nextFg != fg) break
                        if (c == 32 && !snapshot.isSpacerContinuation(i)) break

                        if (c == 32) {
                            i++
                            continue
                        }

                        val extras = snapshot.graphemeExtras[i]
                        val glyph = if (extras == null || extras.isEmpty()) null else snapshot.glyphAt(i)
                        val nextPaintChoice = if (glyph == null) {
                            pickPaintChoice(
                                codepoint = c,
                                glyphCache = singleGlyphCache,
                                cache = singlePaintChoiceCache,
                                primaryPaint = primaryTextPaint,
                                symbolsPaint = symbolsTextPaint,
                            )
                        } else {
                            pickPaintChoice(
                                glyph = glyph,
                                cache = clusterPaintChoiceCache,
                                primaryPaint = primaryTextPaint,
                                symbolsPaint = symbolsTextPaint,
                            )
                        }
                        if (nextPaintChoice != firstPaintChoice) break

                        if (glyph == null) {
                            sb.appendCodePoint(c)
                        } else {
                            sb.append(glyph)
                        }
                        i++
                    }

                    val paint = paintForChoice(
                        choice = firstPaintChoice,
                        primaryPaint = primaryTextPaint,
                        symbolsPaint = symbolsTextPaint,
                        emojiPaint = emojiTextPaint,
                    )
                    paint.color = fg
                    nCanvas.drawText(sb.toString(), startCol * cellWidth, baseline, paint)
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
                        val extras = snapshot.graphemeExtras[cursorIndex]
                        val glyph = if (extras == null || extras.isEmpty()) {
                            singleGlyphCache.getOrPut(codepoint) { String(Character.toChars(codepoint)) }
                        } else {
                            snapshot.glyphAt(cursorIndex)
                        }
                        val cursorPaintChoice = if (extras == null || extras.isEmpty()) {
                            pickPaintChoice(
                                codepoint = codepoint,
                                glyphCache = singleGlyphCache,
                                cache = singlePaintChoiceCache,
                                primaryPaint = primaryTextPaint,
                                symbolsPaint = symbolsTextPaint,
                            )
                        } else {
                            pickPaintChoice(
                                glyph = glyph,
                                cache = clusterPaintChoiceCache,
                                primaryPaint = primaryTextPaint,
                                symbolsPaint = symbolsTextPaint,
                            )
                        }
                        val paint = paintForChoice(
                            choice = cursorPaintChoice,
                            primaryPaint = primaryTextPaint,
                            symbolsPaint = symbolsTextPaint,
                            emojiPaint = emojiTextPaint,
                        )
                        paint.color = cursorTextColorArgb
                        nCanvas.drawText(
                            glyph,
                            cursorLeft,
                            cursorTop + baselineOffset,
                            paint,
                        )
                    }
                }
            }
            nCanvas.restore()
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
    if (cp == 0 || (cp == 32 && !isSpacerContinuation(cellIndex))) return null

    var start = cellIndex
    while (start > rowStart) {
        val prev = start - 1
        val prevCp = codepoints[prev]
        if (prevCp == 0 || (prevCp == 32 && !isSpacerContinuation(prev))) break
        start--
    }
    var end = cellIndex
    while (end < rowEnd) {
        val next = end + 1
        val nextCp = codepoints[next]
        if (nextCp == 0 || (nextCp == 32 && !isSpacerContinuation(next))) break
        end++
    }
    return start..end
}

private fun extractSelectionText(snapshot: TerminalSnapshot, selection: TerminalSelection?): String? {
    if (selection == null) return null
    val range = selection.normalized(snapshot.codepoints.size) ?: return null
    return extractSelectionText(snapshot, range)
}

internal fun extractSelectionText(snapshot: TerminalSnapshot, range: IntRange): String? {
    if (snapshot.cols <= 0 || snapshot.codepoints.isEmpty()) return null

    val normalizedRange = IntRange(
        start = range.first.coerceIn(0, snapshot.codepoints.lastIndex),
        endInclusive = range.last.coerceIn(0, snapshot.codepoints.lastIndex),
    )
    val startRow = normalizedRange.first / snapshot.cols
    val endRow = normalizedRange.last / snapshot.cols
    val builder = StringBuilder(normalizedRange.last - normalizedRange.first + 1 + (endRow - startRow))

    for (row in startRow..endRow) {
        val rowStart = row * snapshot.cols
        val from = maxOf(normalizedRange.first, rowStart)
        val until = minOf(normalizedRange.last, rowStart + snapshot.cols - 1)
        // Find last non-blank cell in this row range to trim trailing whitespace
        var lastContentIdx = until
        while (lastContentIdx >= from) {
            val cp = snapshot.codepoints[lastContentIdx]
            if (cp != 0 && (cp != 32 || snapshot.isSpacerContinuation(lastContentIdx))) break
            lastContentIdx--
        }
        for (index in from..lastContentIdx) {
            val codepoint = snapshot.codepoints[index]
            if (codepoint == 0 || (codepoint == 32 && !snapshot.isSpacerContinuation(index))) {
                builder.append(' ')
            } else if (codepoint != 32) {
                builder.append(snapshot.glyphAt(index))
            }
        }
        if (row != endRow) {
            builder.append('\n')
        }
    }

    return builder.toString()
}

internal fun TerminalSnapshot.isSpacerContinuation(cellIndex: Int): Boolean {
    return ((flags[cellIndex].toInt() and 0xFF) and TerminalSnapshot.CELL_FLAG_SPACER) != 0
}

private fun TerminalSnapshot.glyphAt(cellIndex: Int): String {
    val codepoint = codepoints[cellIndex]
    val extras = graphemeExtras[cellIndex]
    if (extras == null || extras.isEmpty()) return String(Character.toChars(codepoint))
    val builder = StringBuilder(1 + extras.size)
    builder.appendCodePoint(codepoint)
    for (cp in extras) builder.appendCodePoint(cp)
    return builder.toString()
}

private const val PAINT_PRIMARY: Int = 0
private const val PAINT_SYMBOLS: Int = 1
private const val PAINT_EMOJI: Int = 2

/**
 * Decide which paint should render a grapheme cluster.
 *
 * Order of preference:
 *  1. If the primary text font can render the cluster, use it (keeps code
 *     glyph metrics intact).
 *  2. Else, if the first codepoint lives in a Nerd Font private-use range
 *     and the symbols paint has the glyph, use the symbols (Nerd Font) paint.
 *  3. Else, use the emoji paint (system default Typeface), which routes
 *     through Android's NotoColorEmoji + font fallback chain and is the only
 *     paint that can shape emoji ZWJ sequences, regional-indicator flag
 *     pairs, and VS16-promoted color emoji.
 */
private fun pickPaintChoice(
    glyph: String,
    cache: HashMap<String, Int>,
    primaryPaint: Paint,
    symbolsPaint: Paint,
): Int {
    return cache.getOrPut(glyph) {
        if (primaryPaint.hasGlyph(glyph)) return@getOrPut PAINT_PRIMARY
        val firstCp = glyph.codePointAt(0)
        if (isNerdFontPrivateUse(firstCp) && symbolsPaint.hasGlyph(glyph)) {
            return@getOrPut PAINT_SYMBOLS
        }
        PAINT_EMOJI
    }
}

private fun pickPaintChoice(
    codepoint: Int,
    glyphCache: HashMap<Int, String>,
    cache: HashMap<Int, Int>,
    primaryPaint: Paint,
    symbolsPaint: Paint,
): Int {
    // Fast path for the overwhelmingly common command-output ASCII glyphs.
    if (codepoint in 0x21..0x7E) return PAINT_PRIMARY

    return cache.getOrPut(codepoint) {
        val glyph = glyphCache.getOrPut(codepoint) { String(Character.toChars(codepoint)) }
        if (primaryPaint.hasGlyph(glyph)) return@getOrPut PAINT_PRIMARY
        if (isNerdFontPrivateUse(codepoint) && symbolsPaint.hasGlyph(glyph)) {
            return@getOrPut PAINT_SYMBOLS
        }
        PAINT_EMOJI
    }
}

private fun paintForChoice(
    choice: Int,
    primaryPaint: Paint,
    symbolsPaint: Paint,
    emojiPaint: Paint,
): Paint = when (choice) {
    PAINT_SYMBOLS -> symbolsPaint
    PAINT_EMOJI -> emojiPaint
    else -> primaryPaint
}

/**
 * Private Use Area ranges where Nerd Font glyphs live (devicons, powerline,
 * weather icons, file-type icons, etc.). Everything outside these ranges that
 * the primary font cannot render is treated as an emoji/symbol cluster.
 */
private fun isNerdFontPrivateUse(cp: Int): Boolean {
    return (cp in 0xE000..0xF8FF) ||
        (cp in 0xF0000..0xFFFFD) ||
        (cp in 0x100000..0x10FFFD)
}


/** Tracks double-tap timing/position without triggering Compose recomposition. */
private class DoubleTapState {
    var lastTime: Long = 0L
    var lastPos: Offset = Offset.Zero
}

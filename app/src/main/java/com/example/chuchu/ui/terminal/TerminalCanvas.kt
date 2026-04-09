package com.example.chuchu.ui.terminal

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.example.chuchu.service.terminal.TerminalSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

@Composable
fun TerminalCanvas(
    snapshot: TerminalSnapshot,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
    onResize: (cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, widthPx: Int, heightPx: Int) -> Unit =
        { _, _, _, _, _, _ -> },
    onTap: () -> Unit = {},
    onScroll: (delta: Int) -> Unit = {},
    onZoom: (zoomFactor: Float) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
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
    val glyphCache = remember {
        HashMap<Int, String>(256).apply {
            for (cp in 33..126) {
                this[cp] = cp.toChar().toString()
            }
        }
    }
    val fontMetrics = textPaint.fontMetrics
    val measuredHeight = fontMetrics.descent - fontMetrics.ascent
    val cellHeightPx = if (measuredHeight > 1f) measuredHeight else 16f
    val measuredWidth = textPaint.measureText("M")
    val cellWidthPx = if (measuredWidth > 1f) measuredWidth else 8f
    val baselineOffset = -fontMetrics.ascent
    val cellWidthInt = max(1, ceil(cellWidthPx).toInt())
    val cellHeightInt = max(1, ceil(cellHeightPx).toInt())

    LaunchedEffect(canvasSize, cellWidthPx, cellHeightPx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = max(1, floor(canvasSize.width / cellWidthPx).toInt())
        val rows = max(1, floor(canvasSize.height / cellHeightPx).toInt())
        onResize(cols, rows, cellWidthInt, cellHeightInt, canvasSize.width, canvasSize.height)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                canvasSize = size
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragRemainder = 0f
                    var lastPinchDistance: Float? = null
                    var didScroll = false
                    var didPinch = false
                    var lastSinglePointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!didScroll && !didPinch) {
                                onTap()
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

        val bgDefault = Color(snapshot.defaultBgArgb)
        drawRect(color = bgDefault)

        drawIntoCanvas { canvas ->
            val nCanvas = canvas.nativeCanvas
            val sb = StringBuilder(cols)

            for (row in 0 until rows) {
                val rowStart = row * cols
                val y = row * cellHeight
                val baseline = y + baselineOffset

                // Background runs
                var i = rowStart
                val rowEnd = rowStart + cols
                while (i < rowEnd) {
                    val bg = snapshot.bgArgb[i]
                    var j = i + 1
                    while (j < rowEnd && snapshot.bgArgb[j] == bg) j++
                    if (bg != snapshot.defaultBgArgb) {
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

                    val fg = snapshot.fgArgb[i]
                    sb.setLength(0)
                    val startCol = i - rowStart

                    while (i < rowEnd) {
                        val c = snapshot.codepoints[i]
                        if ((c == 0 || c == 32) || snapshot.fgArgb[i] != fg) break
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

            // Cursor
            if (snapshot.cursorVisible && snapshot.cursorX >= 0 && snapshot.cursorY >= 0) {
                drawRect(
                    color = Color.White.copy(alpha = 0.28f),
                    topLeft = Offset(snapshot.cursorX * cellWidth, snapshot.cursorY * cellHeight),
                    size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                )
            }
        }
    }
}

package com.jossephus.chuchu.ui.terminal

import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSnapshotRegressionTest {
    @Test
    fun parsesGraphemeExtrasAndSpacerFlagsForZwjBridgeCells() {
        val cols = 4
        val rows = 1
        val cellCount = cols * rows
        val headerBytes = 12 * 4
        val gridBytes = cellCount * 11
        val extrasOffset = headerBytes + gridBytes

        val extrasRecordCount = 1
        val extrasCodepoints = 1
        val extrasBytes = 4 + (extrasRecordCount * 8) + (extrasCodepoints * 4)

        val raw = ByteBuffer.allocate(headerBytes + gridBytes + extrasBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        raw.putInt(cols)
        raw.putInt(rows)
        raw.putInt(0) // cursor x
        raw.putInt(0) // cursor y
        raw.putInt(1) // cursor visible
        raw.putInt(0)
        raw.putInt(0)
        raw.putInt(0)
        raw.putInt(255)
        raw.putInt(255)
        raw.putInt(255)
        raw.putInt(extrasOffset)

        fun putCell(codepoint: Int, flags: Int) {
            raw.putInt(codepoint)
            raw.put(255.toByte())
            raw.put(255.toByte())
            raw.put(255.toByte())
            raw.put(0.toByte())
            raw.put(0.toByte())
            raw.put(0.toByte())
            raw.put((flags and 0xFF).toByte())
        }

        // Cell 0: 🧑 + ZWJ (extras), Cell 1: 💻, Cell 2: spacer continuation, Cell 3: plain space
        putCell(0x1F9D1, TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME)
        putCell(0x1F4BB, 0)
        putCell(32, TerminalSnapshot.CELL_FLAG_SPACER)
        putCell(32, 0)

        // Extras section: one record for cell 0 with one extra codepoint U+200D
        raw.putInt(1)
        raw.putInt(0)
        raw.putInt(1)
        raw.putInt(0x200D)

        raw.position(0)
        val snapshot = TerminalSnapshot.fromByteBuffer(raw)

        assertArrayEquals(intArrayOf(0x200D), snapshot.graphemeExtras[0])
        assertTrue(snapshot.isSpacerContinuation(2))
        assertFalse(snapshot.isSpacerContinuation(3))
    }

    @Test
    fun selectionFallbackReconstructsZwjEmojiAcrossSpacerCells() {
        val snapshot = snapshotOf(
            cols = 4,
            rows = 1,
            cells = listOf(
                Cell(0x1F9D1, TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME),
                Cell(0x1F4BB, 0),
                Cell(32, TerminalSnapshot.CELL_FLAG_SPACER),
                Cell(32, 0),
            ),
            extras = mapOf(0 to intArrayOf(0x200D)),
        )

        assertEquals("🧑‍💻", extractSelectionText(snapshot, 0..3))
    }

    @Test
    fun selectionFallbackReconstructsFlagsAcrossSpacerCells() {
        val snapshot = snapshotOf(
            cols = 5,
            rows = 1,
            cells = listOf(
                Cell(0x1F1FA, 0),
                Cell(32, TerminalSnapshot.CELL_FLAG_SPACER),
                Cell(0x1F1F8, 0),
                Cell(32, TerminalSnapshot.CELL_FLAG_SPACER),
                Cell(32, 0),
            ),
        )

        assertEquals("🇺🇸", extractSelectionText(snapshot, 0..4))
    }

    @Test
    fun selectionFallbackReconstructsMode2027SingleCellCoupleZwjCluster() {
        val snapshot = snapshotOf(
            cols = 1,
            rows = 1,
            cells = listOf(
                Cell(0x1F469, TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME),
            ),
            extras = mapOf(
                0 to intArrayOf(
                    0x200D, // ZWJ
                    0x2764, // HEART
                    0xFE0F, // VS16
                    0x200D, // ZWJ
                    0x1F48B, // KISS MARK
                    0x200D, // ZWJ
                    0x1F468, // MAN
                ),
            ),
        )

        assertEquals("👩‍❤️‍💋‍👨", extractSelectionText(snapshot, 0..0))
    }

    @Test
    fun parsingMode2027ExtrasDoesNotInjectSkinToneModifiers() {
        val cols = 3
        val rows = 1
        val cellCount = cols * rows
        val headerBytes = 12 * 4
        val gridBytes = cellCount * 11
        val extrasOffset = headerBytes + gridBytes

        // Record 0: 🧑 + ZWJ + 💻
        // Record 1: 👩 + ZWJ + ❤️ + ZWJ + 👨
        val extrasRecordCount = 2
        val record0 = intArrayOf(0x200D, 0x1F4BB)
        val record1 = intArrayOf(0x200D, 0x2764, 0xFE0F, 0x200D, 0x1F468)
        val extrasCodepoints = record0.size + record1.size
        val extrasBytes = 4 + (extrasRecordCount * 8) + (extrasCodepoints * 4)

        val raw = ByteBuffer.allocate(headerBytes + gridBytes + extrasBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        raw.putInt(cols)
        raw.putInt(rows)
        raw.putInt(0)
        raw.putInt(0)
        raw.putInt(1)
        raw.putInt(0)
        raw.putInt(0)
        raw.putInt(0)
        raw.putInt(255)
        raw.putInt(255)
        raw.putInt(255)
        raw.putInt(extrasOffset)

        fun putCell(codepoint: Int, flags: Int) {
            raw.putInt(codepoint)
            raw.put(255.toByte())
            raw.put(255.toByte())
            raw.put(255.toByte())
            raw.put(0.toByte())
            raw.put(0.toByte())
            raw.put(0.toByte())
            raw.put((flags and 0xFF).toByte())
        }

        putCell(0x1F9D1, TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME)
        putCell(0x1F469, TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME)
        putCell(0x1F44B, 0)

        raw.putInt(extrasRecordCount)
        raw.putInt(0)
        raw.putInt(record0.size)
        record0.forEach { raw.putInt(it) }
        raw.putInt(1)
        raw.putInt(record1.size)
        record1.forEach { raw.putInt(it) }

        raw.position(0)
        val snapshot = TerminalSnapshot.fromByteBuffer(raw)

        assertArrayEquals(record0, snapshot.graphemeExtras[0])
        assertArrayEquals(record1, snapshot.graphemeExtras[1])
        assertEquals("🧑‍💻", extractSelectionText(snapshot, 0..0))
        assertEquals("👩‍❤️‍👨", extractSelectionText(snapshot, 1..1))

        val skinToneRange = 0x1F3FB..0x1F3FF
        val reconstructed = buildString {
            append(extractSelectionText(snapshot, 0..0))
            append(extractSelectionText(snapshot, 1..1))
        }
        assertTrue(reconstructed.codePoints().noneMatch { cp -> cp in skinToneRange })
    }

    private data class Cell(
        val codepoint: Int,
        val flags: Int,
    )

    private fun snapshotOf(
        cols: Int,
        rows: Int,
        cells: List<Cell>,
        extras: Map<Int, IntArray> = emptyMap(),
    ): TerminalSnapshot {
        val cellCount = cols * rows
        require(cells.size == cellCount)

        val codepoints = IntArray(cellCount)
        val fgArgb = IntArray(cellCount) { 0xFFFFFFFF.toInt() }
        val bgArgb = IntArray(cellCount) { 0xFF000000.toInt() }
        val flags = ByteArray(cellCount)

        for ((index, cell) in cells.withIndex()) {
            codepoints[index] = cell.codepoint
            flags[index] = (cell.flags and 0xFF).toByte()
        }

        return TerminalSnapshot(
            cols = cols,
            rows = rows,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0xFF000000.toInt(),
            defaultFgArgb = 0xFFFFFFFF.toInt(),
            codepoints = codepoints,
            fgArgb = fgArgb,
            bgArgb = bgArgb,
            flags = flags,
            graphemeExtras = extras,
        )
    }
}

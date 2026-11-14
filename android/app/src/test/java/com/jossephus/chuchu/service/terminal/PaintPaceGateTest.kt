package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintPaceGateTest {
    @Test
    fun calmOutputKeepsTheInteractiveInterval() {
        val gate = PaintPaceGate()

        gate.onBytes(512, nowMs = 0)
        gate.onBytes(512, nowMs = 20)

        assertFalse(gate.isFlooded(nowMs = 30))
        assertEquals(0L, gate.paintIntervalFloorMs(nowMs = 30))
    }

    @Test
    fun aBurstOfChunksRaisesThePaintInterval() {
        val gate = PaintPaceGate()

        gate.onBytes(8 * 1024, nowMs = 0)
        gate.onBytes(8 * 1024, nowMs = 20)

        assertTrue(gate.isFlooded(nowMs = 40))
        assertEquals(150L, gate.paintIntervalFloorMs(nowMs = 40))
    }

    @Test
    fun oneLargeChunkIsEnoughToLookFlooded() {
        val gate = PaintPaceGate()

        gate.onBytes(64 * 1024, nowMs = 0)

        assertTrue(gate.isFlooded(nowMs = 1))
    }

    @Test
    fun theBurstExpiresOnItsOwnOnceOutputStops() {
        val gate = PaintPaceGate()
        gate.onBytes(64 * 1024, nowMs = 0)
        assertTrue(gate.isFlooded(nowMs = 10))

        // Still paced while the hold lasts, then back to the interactive interval.
        assertTrue(gate.isFlooded(nowMs = 199))
        assertFalse(gate.isFlooded(nowMs = 200))
        assertEquals(0L, gate.paintIntervalFloorMs(nowMs = 200))
    }

    @Test
    fun streamingForeverKeepsTheFloodFloorInsteadOfFreezing() {
        val gate = PaintPaceGate()

        // `yes`-style output: a chunk every millisecond, well past several window boundaries.
        for (offset in 0..300) {
            gate.onBytes(1024, nowMs = offset.toLong())
        }

        // Once the threshold is reached the floor must survive every window boundary rather than
        // flap back to the interactive interval in between.
        for (offset in 15..300) {
            assertEquals(150L, gate.paintIntervalFloorMs(nowMs = offset.toLong()))
        }
    }

    @Test
    fun aNewBurstAfterCalmIsDetectedAgain() {
        val gate = PaintPaceGate()
        gate.onBytes(64 * 1024, nowMs = 0)
        assertFalse(gate.isFlooded(nowMs = 500))

        gate.onBytes(64 * 1024, nowMs = 600)

        assertTrue(gate.isFlooded(nowMs = 600))
    }
}

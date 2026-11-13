package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderHoldGateTest {
    @Test
    fun chunkWithoutHoldPaintsThroughTheCoalescer() {
        val gate = RenderHoldGate()

        assertEquals(RenderHoldAction.Paint, gate.onChunk(holdActive = false, nowMs = 0))
        assertFalse(gate.isHeld)
    }

    @Test
    fun holdSkipsFramesAndTheReleaseForcesOne() {
        val gate = RenderHoldGate()

        assertEquals(RenderHoldAction.Skip, gate.onChunk(holdActive = true, nowMs = 0))
        assertEquals(RenderHoldAction.Skip, gate.onChunk(holdActive = true, nowMs = 16))
        assertTrue(gate.isHeld)

        assertEquals(RenderHoldAction.PaintForced, gate.onChunk(holdActive = false, nowMs = 20))
        assertFalse(gate.isHeld)
        assertEquals(RenderHoldAction.Paint, gate.onChunk(holdActive = false, nowMs = 36))
    }

    @Test
    fun stallFiresAfterTheDataStopsArriving() {
        val gate = RenderHoldGate()
        gate.onChunk(holdActive = true, nowMs = 0)
        gate.onChunk(holdActive = true, nowMs = 700)

        assertFalse(gate.stallDue(nowMs = 1_400))
        assertTrue(gate.stallDue(nowMs = 1_500))
    }

    @Test
    fun stallKeepsWaitingWhileDataStillArrives() {
        val gate = RenderHoldGate()
        gate.onChunk(holdActive = true, nowMs = 0)
        gate.onChunk(holdActive = true, nowMs = 750)

        // A single check would give up here and freeze the screen for good; the gate
        // must keep deferring while data still arrives.
        assertFalse(gate.stallDue(nowMs = 800))
        assertTrue(gate.isHeld)

        gate.onChunk(holdActive = true, nowMs = 1_550)
        assertFalse(gate.stallDue(nowMs = 1_600))
        assertTrue(gate.isHeld)

        // Data stops here, so the quiet window expires and the frame must be painted.
        gate.onChunk(holdActive = true, nowMs = 1_590)
        assertTrue(gate.stallDue(nowMs = 2_390))
    }

    @Test
    fun hardCapPaintsEvenWhileDataKeepsFlowing() {
        val gate = RenderHoldGate()
        gate.onChunk(holdActive = true, nowMs = 0)
        gate.onChunk(holdActive = true, nowMs = 1_950)

        assertFalse(gate.stallDue(nowMs = 1_999))
        assertTrue(gate.stallDue(nowMs = 2_000))
    }

    @Test
    fun releaseForgetsTheHoldSoAStaleCheckDoesNothing() {
        val gate = RenderHoldGate()
        gate.onChunk(holdActive = true, nowMs = 0)

        gate.release()

        assertFalse(gate.isHeld)
        assertFalse(gate.stallDue(nowMs = 60_000))
    }

    @Test
    fun aSecondHoldGetsItsOwnStallWindow() {
        val gate = RenderHoldGate()
        gate.onChunk(holdActive = true, nowMs = 0)
        gate.onChunk(holdActive = false, nowMs = 100)

        assertEquals(RenderHoldAction.Skip, gate.onChunk(holdActive = true, nowMs = 200))

        assertFalse(gate.stallDue(nowMs = 900))
        assertTrue(gate.stallDue(nowMs = 1_000))
    }
}

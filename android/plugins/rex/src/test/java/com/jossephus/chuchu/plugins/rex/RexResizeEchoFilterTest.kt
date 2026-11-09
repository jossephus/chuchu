package com.jossephus.chuchu.plugins.rex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RexResizeEchoFilterTest {
    @Test
    fun dropsLateEchoesOfAKeyboardAnimation() {
        val filter = RexResizeEchoFilter()
        // Keyboard opening shrinks the grid row by row, then closing grows it back.
        listOf(40, 34, 28, 22, 28, 34, 40).forEach { filter.onSent(80, it) }

        listOf(40, 34, 28, 22, 28, 34).forEach { rows ->
            assertFalse("echo of $rows rows", filter.shouldApply(80, rows))
        }
        assertTrue(filter.shouldApply(80, 40))
    }

    @Test
    fun appliesTheLatestEchoSoASnapshotAtTheOldSizeIsCorrected() {
        val filter = RexResizeEchoFilter()
        filter.onSent(80, 24)

        assertTrue(filter.shouldApply(80, 24))
    }

    @Test
    fun appliesResizesThisClientNeverAskedFor() {
        val filter = RexResizeEchoFilter()
        filter.onSent(80, 24)

        assertTrue(filter.shouldApply(120, 50))
        // A foreign resize leaves our pending request alone.
        assertTrue(filter.shouldApply(80, 24))
    }

    @Test
    fun aSizeIsAnEchoAgainOnlyAfterItIsRequestedAgain() {
        val filter = RexResizeEchoFilter()
        filter.onSent(80, 24)
        filter.onSent(80, 30)

        assertFalse(filter.shouldApply(80, 24))
        // Already answered, so a second 80x24 now comes from somewhere else.
        assertTrue(filter.shouldApply(80, 24))
    }
}

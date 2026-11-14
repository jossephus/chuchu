package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.ProgramState
import com.jossephus.chuchu.plugin.api.ProgramStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramStatusRecordsTest {
    @Test
    fun reportsBecomeTheCurrentStatus() {
        val records = ProgramStatusRecords()
        records.apply(report("working", app = "cargo", progress = "40", message = "compiling"))

        assertEquals(
            ProgramStatus(ProgramState.Working, app = "cargo", progress = 40, message = "compiling"),
            records.current,
        )
    }

    @Test
    fun aReportReplacesItsRecordCompletely() {
        val records = ProgramStatusRecords()
        records.apply(report("working", app = "cargo", progress = "40"))
        records.apply(report("done"))

        assertEquals(ProgramStatus(ProgramState.Done), records.current)
    }

    @Test
    fun theRecordThatMostNeedsTheUserWins() {
        val records = ProgramStatusRecords()
        records.apply(
            report("blocked", id = "a", kind = "permission") +
                report("working", id = "b") +
                report("done", id = "c"),
        )

        assertEquals("a", records.current?.id)
        assertEquals("permission", records.current?.kind)
    }

    @Test
    fun theLatestReportWinsATie() {
        val records = ProgramStatusRecords()
        records.apply(report("working", id = "a") + report("working", id = "b"))
        records.apply(report("working", id = "a", progress = "10"))

        assertEquals("a", records.current?.id)
    }

    @Test
    fun clearRemovesARecordAndEverythingBeneathIt() {
        val records = ProgramStatusRecords()
        records.apply(
            report("working", id = "deploy") +
                report("blocked", id = "deploy/us-east") +
                report("done", id = "deployment"),
        )
        records.apply(report("clear", id = "deploy"))

        assertEquals("deployment", records.current?.id)
    }

    @Test
    fun clearWithoutAnIdRemovesEveryRecord() {
        val records = ProgramStatusRecords()
        records.apply(report("working", id = "a") + report("error", id = "b"))
        records.apply(report("clear"))

        assertNull(records.current)
    }

    @Test
    fun idleRecordsShowNothing() {
        val records = ProgramStatusRecords()
        records.apply(report("idle", app = "claude-code"))

        assertNull(records.current)
    }

    @Test
    fun aRecordWithoutAnAppTakesItsNearestAncestors() {
        val records = ProgramStatusRecords()
        records.apply(report("idle", id = "deploy", app = "terraform") + report("error", id = "deploy/us-east/db"))

        assertEquals("terraform", records.current?.app)
    }

    @Test
    fun malformedReportsAreIgnored() {
        val records = ProgramStatusRecords()
        records.apply("working\u001fonly-two-fields\u001e".toByteArray() + report("sleeping"))

        assertNull(records.current)
    }

    @Test
    fun hostStatesParseCaseInsensitivelyAndUnknownOnesReadAsIdle() {
        assertEquals(ProgramState.Blocked, ProgramState.parse("blocked"))
        assertEquals(ProgramState.Error, ProgramState.parse("ERROR"))
        assertEquals(ProgramState.Idle, ProgramState.parse("paused"))
        assertEquals(ProgramState.Idle, ProgramState.parse(null))
    }

    private fun report(
        state: String,
        id: String = "",
        app: String = "",
        kind: String = "",
        progress: String = "",
        title: String = "",
        message: String = "",
    ): ByteArray =
        listOf(state, id, app, kind, progress, title, message)
            .joinToString("\u001f", postfix = "\u001e")
            .toByteArray()
}

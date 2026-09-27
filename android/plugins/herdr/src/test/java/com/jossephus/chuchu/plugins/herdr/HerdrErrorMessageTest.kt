package com.jossephus.chuchu.plugins.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class HerdrErrorMessageTest {
    @Test
    fun extractsMessageFromHerdrErrorJson() {
        val output = """{"error":{"code":"agent_not_found","message":"agent target w7:p3 not found"},"id":"cli:agent:focus"}"""

        assertEquals("herdr: agent target w7:p3 not found", herdrErrorMessage(output, "", 1))
    }

    @Test
    fun fallsBackToPlainStderrThenExitCode() {
        assertEquals("usage: herdr pane focus", herdrErrorMessage("", "usage: herdr pane focus\n", 2))
        assertEquals("herdr exited with 3", herdrErrorMessage("", "", 3))
    }
}

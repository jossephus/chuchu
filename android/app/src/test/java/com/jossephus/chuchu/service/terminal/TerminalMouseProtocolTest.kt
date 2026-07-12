package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalMouseProtocolTest {
    @Test
    fun actionAndButtonValuesMatchGhosttyAbi() {
        assertEquals(0, TerminalMouseAction.Press)
        assertEquals(1, TerminalMouseAction.Release)
        assertEquals(2, TerminalMouseAction.Motion)
        assertEquals(1, TerminalMouseButton.Left)
    }
}

package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeInputTypeTest {

    @Test
    fun `resolves normal terminal input type when autocorrect is enabled`() {
        assertEquals(0xA0001, terminalImeInputType(disableAutocorrect = false))
    }

    @Test
    fun `uses null input type when autocorrect is disabled`() {
        assertEquals(0x0, terminalImeInputType(disableAutocorrect = true))
    }
}

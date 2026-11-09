package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPwdTest {
    @Test
    fun osc7FileUriBecomesItsDecodedPath() {
        assertEquals(
            "/Users/me/my projects/chuchu",
            terminalPwdPath("file://my-mac/Users/me/my%20projects/chuchu"),
        )
    }

    @Test
    fun plainPathsAreKept() {
        assertEquals("/home/me/src", terminalPwdPath("/home/me/src"))
        assertEquals("/home/me/a b", terminalPwdPath("/home/me/a b"))
    }

    @Test
    fun longPathsKeepTheirTail() {
        val label = pwdLabel("/Users/me/workspace/projects/jebena/jebena-monorepo", maxChars = 20)
        assertEquals(20, label.length)
        assertEquals("…ena/jebena-monorepo", label)
    }
}

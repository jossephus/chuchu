package com.jossephus.chuchu.plugins.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class RexPaneNameTest {
    private fun pane(label: String?, custom: Boolean, title: String?) =
        RexPane("session:s", "block:b", label, custom, title, program = "bash", cwd = null)

    @Test
    fun liveTitleBeatsRexsDefaultWindowName() {
        assertEquals("Register ./a authorized keys", paneName(pane("Window 668c4bd7", custom = false, title = "Register ./a authorized keys")))
    }

    @Test
    fun aNameTheUserSetBeatsTheTitle() {
        assertEquals("rex-plugin-build", paneName(pane("rex-plugin-build", custom = true, title = "make app")))
    }

    @Test
    fun withoutATitleTheDefaultWindowNameIsKept() {
        assertEquals("Window 9976c71a", paneName(pane("Window 9976c71a", custom = false, title = null)))
    }
}

package com.jossephus.chuchu.plugins.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class HerdrHomeTest {
    @Test
    fun agentTitleDropsAgentPrefixAndWorkspaceSuffix() {
        assertEquals("Friendly one-sentence reply", cleanAgentTitle("π - web-app: Friendly one-sentence reply - web-app", "web-app"))
    }

    @Test
    fun agentTitleKeepsTextItCannotSimplify() {
        assertEquals("build server", cleanAgentTitle("build server", "web-app"))
        assertEquals(": only", cleanAgentTitle(": only", ""))
    }

    @Test
    fun pluralisesCounts() {
        assertEquals("1 tab", plural(1, "tab"))
        assertEquals("2 panes", plural(2, "pane"))
        assertEquals("0 panes", plural(0, "pane"))
    }
}

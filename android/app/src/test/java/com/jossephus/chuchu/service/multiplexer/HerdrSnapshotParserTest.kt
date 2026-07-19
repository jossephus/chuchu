package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HerdrSnapshotParserTest {
    @Test
    fun parsesRealSnapshotFixture() {
        val snapshot = parseHerdrSnapshot(realSnapshotFixture)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(16, snapshot.protocol)
        assertEquals(1, snapshot.workspaces.size)
        assertEquals(2, snapshot.tabs.size)
        assertEquals(2, snapshot.panes.size)

        val pane = snapshot.panes.first { it.paneId == "wJ:p5Y" }
        assertEquals("pi", pane.agent)
        assertEquals(HerdrAgentStatus.Working, pane.agentStatus)
        assertEquals("/home/salem/coding/ERBareeq", pane.cwd)
        assertEquals("π - ERBareeq: safely implement Postgres migration scope - ERBareeq", pane.terminalTitleStripped)

        assertEquals("wJ", snapshot.focusedWorkspaceId)
        assertEquals("wJ:t1H", snapshot.focusedTabId)
        assertEquals("wJ:p5Y", snapshot.focusedPaneId)
    }

    @Test
    fun mapsKnownAndUnknownAgentStatuses() {
        val snapshot = parseHerdrSnapshot(
            """
            {"id":"cli:api:snapshot","result":{"snapshot":{"panes":[
              {"pane_id":"idle","agent_status":"idle"},
              {"pane_id":"working","agent_status":"working"},
              {"pane_id":"blocked","agent_status":"blocked"},
              {"pane_id":"done","agent_status":"done"},
              {"pane_id":"future","agent_status":"garbage-future-status"},
              {"pane_id":"missing"}
            ]}}}
            """.trimIndent(),
        )!!

        assertEquals(
            listOf(
                HerdrAgentStatus.Idle,
                HerdrAgentStatus.Working,
                HerdrAgentStatus.Blocked,
                HerdrAgentStatus.Done,
                HerdrAgentStatus.Unknown,
                HerdrAgentStatus.Unknown,
            ),
            snapshot.panes.map { it.agentStatus },
        )
    }

    @Test
    fun parsesNoisePrefixedSnapshotFixture() {
        val expected = parseHerdrSnapshot(realSnapshotFixture)
        val actual = parseHerdrSnapshot("Welcome!\nmotd line\n$realSnapshotFixture")

        assertEquals(expected, actual)
    }

    @Test
    fun returnsNullForInvalidOutput() {
        assertNull(parseHerdrSnapshot(""))
        assertNull(parseHerdrSnapshot("not json"))
        assertNull(parseHerdrSnapshot(realSnapshotFixture.dropLast(1)))
    }

    @Test
    fun returnsNullForMissingEnvelopeFields() {
        assertNull(parseHerdrSnapshot("{\"id\":\"cli:api:snapshot\"}"))
        assertNull(parseHerdrSnapshot("{\"id\":\"cli:api:snapshot\",\"result\":{}}"))
    }

    private companion object {
        val realSnapshotFixture =
            """{"id":"cli:api:snapshot","result":{"snapshot":{"agents":[{"agent":"pi","agent_session":{"agent":"pi","kind":"path","source":"herdr:pi","value":"/home/salem/.pi/agent/sessions/--home-salem-coding-ERBareeq--/2026-07-16T07-13-22-703Z_019f69c5-dd0f-7da2-a8c5-559c1d548757.jsonl"},"agent_status":"idle","cwd":"/home/salem/coding/ERBareeq","focused":false,"foreground_cwd":"/home/salem/coding/ERBareeq","pane_id":"wJ:p3D","revision":10,"screen_detection_skipped":true,"tab_id":"wJ:tR","terminal_id":"term_656acc0585f9a2","terminal_title":"\u03c0 - ERBareeq: MSSQL Sync Layer Refactor - ERBareeq","terminal_title_stripped":"\u03c0 - ERBareeq: MSSQL Sync Layer Refactor - ERBareeq","workspace_id":"wJ"},{"agent":"pi","agent_session":{"agent":"pi","kind":"path","source":"herdr:pi","value":"/home/salem/.pi/agent/sessions/--home-salem-coding-ERBareeq--/2026-07-18T21-36-52-186Z_019f7729-21da-79d6-833b-6af0d72af2cb.jsonl"},"agent_status":"idle","cwd":"/home/salem/coding/ERBareeq","focused":false,"foreground_cwd":"/home/salem/coding/ERBareeq","pane_id":"wJ:p52","revision":16,"screen_detection_skipped":true,"tab_id":"wJ:t16","terminal_id":"term_656e384d6906144","terminal_title":"\u03c0 - ERBareeq: Fix Bareeq Chat UI Papercuts - ERBareeq","terminal_title_stripped":"\u03c0 - ERBareeq: Fix Bareeq Chat UI Papercuts - ERBareeq","tokens":{"pi_session_name":"ERBareeq: Fix Bareeq Chat UI Papercuts"},"workspace_id":"wJ"}],"focused_pane_id":"wJ:p5Y","focused_tab_id":"wJ:t1H","focused_workspace_id":"wJ","layouts":[],"panes":[{"agent":"pi","agent_session":{"agent":"pi","kind":"path","source":"herdr:pi","value":"/home/salem/.pi/agent/sessions/--home-salem-coding-ERBareeq--/2026-07-16T07-13-22-703Z_019f69c5-dd0f-7da2-a8c5-559c1d548757.jsonl"},"agent_status":"idle","cwd":"/home/salem/coding/ERBareeq","focused":false,"foreground_cwd":"/home/salem/coding/ERBareeq","pane_id":"wJ:p3D","revision":10,"scroll":{"max_offset_from_bottom":535,"offset_from_bottom":0,"viewport_rows":56},"tab_id":"wJ:tR","terminal_id":"term_656acc0585f9a2","terminal_title":"\u03c0 - ERBareeq: MSSQL Sync Layer Refactor - ERBareeq","terminal_title_stripped":"\u03c0 - ERBareeq: MSSQL Sync Layer Refactor - ERBareeq","workspace_id":"wJ"},{"agent":"pi","agent_session":{"agent":"pi","kind":"path","source":"herdr:pi","value":"/home/salem/.pi/agent/sessions/--home-salem-coding-ERBareeq--/2026-07-19T19-19-42-452Z_019f7bd1-ea74-79d0-ae10-d4306bddc29c.jsonl"},"agent_status":"working","cwd":"/home/salem/coding/ERBareeq","focused":true,"foreground_cwd":"/home/salem/coding/ERBareeq","pane_id":"wJ:p5Y","revision":9,"scroll":{"max_offset_from_bottom":670,"offset_from_bottom":0,"viewport_rows":54},"tab_id":"wJ:t1H","terminal_id":"term_656fad1f3c60b63","terminal_title":"\u03c0 - ERBareeq: safely implement Postgres migration scope - ERBareeq","terminal_title_stripped":"\u03c0 - ERBareeq: safely implement Postgres migration scope - ERBareeq","tokens":{"pi_session_name":"ERBareeq: safely implement Postgres migration scope"},"workspace_id":"wJ"}],"protocol":16,"tabs":[{"agent_status":"idle","focused":false,"label":"1","number":24,"pane_count":1,"tab_id":"wJ:tR","workspace_id":"wJ"},{"agent_status":"working","focused":true,"label":"3","number":49,"pane_count":3,"tab_id":"wJ:t1H","workspace_id":"wJ"}],"version":"0.7.4","workspaces":[{"active_tab_id":"wJ:t1H","agent_status":"working","focused":true,"label":"ERBareeq","number":1,"pane_count":5,"tab_count":3,"workspace_id":"wJ"}]}}}"""
    }
}

package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class HerdrStreamFramingTest {
    @Test
    fun extractsSingleCompleteFrame() {
        val buffer = StringBuilder()

        assertEquals(
            listOf("snapshot"),
            appendHerdrStreamChunk(buffer, "CHUCHU_SNAP_BEGIN\nsnapshot\nCHUCHU_SNAP_END\n"),
        )
        assertEquals("", buffer.toString())
    }

    @Test
    fun extractsFrameWhenMarkersAreSplitAcrossChunks() {
        val buffer = StringBuilder()

        assertEquals(emptyList<String>(), appendHerdrStreamChunk(buffer, "noiseCHUCHU_SNA"))
        assertEquals(
            emptyList<String>(),
            appendHerdrStreamChunk(buffer, "P_BEGIN\nsnapshot\nCHUCHU_SNAP_E"),
        )
        assertEquals(
            listOf("snapshot"),
            appendHerdrStreamChunk(buffer, "ND\n"),
        )
    }

    @Test
    fun extractsTwoFramesFromOneChunk() {
        val buffer = StringBuilder()

        assertEquals(
            listOf("first", "second"),
            appendHerdrStreamChunk(
                buffer,
                "CHUCHU_SNAP_BEGIN\nfirst\nCHUCHU_SNAP_END\n" +
                    "CHUCHU_SNAP_BEGIN\nsecond\nCHUCHU_SNAP_END\n",
            ),
        )
    }

    @Test
    fun discardsGarbageBetweenFrames() {
        val buffer = StringBuilder()

        assertEquals(
            listOf("first", "second"),
            appendHerdrStreamChunk(
                buffer,
                "noiseCHUCHU_SNAP_BEGIN\nfirst\nCHUCHU_SNAP_END\n" +
                    "unrelated outputCHUCHU_SNAP_BEGIN\nsecond\nCHUCHU_SNAP_END\ntrailing",
            ),
        )
        assertEquals("", buffer.toString())
    }

    @Test
    fun retainsPartialFrameUntilTheNextChunkCompletesIt() {
        val buffer = StringBuilder()

        assertEquals(
            emptyList<String>(),
            appendHerdrStreamChunk(buffer, "CHUCHU_SNAP_BEGIN\npartial"),
        )
        assertEquals("CHUCHU_SNAP_BEGIN\npartial", buffer.toString())
        assertEquals(
            listOf("partial frame"),
            appendHerdrStreamChunk(buffer, " frame\nCHUCHU_SNAP_END"),
        )
    }

    @Test
    fun buildsSnapshotStreamAndControlCommands() {
        val prelude = "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; "

        assertEquals(
            prelude +
                "while IFS= read -r _; do printf 'CHUCHU_SNAP_BEGIN\\n'; herdr api snapshot 2>/dev/null; " +
                "printf '\\nCHUCHU_SNAP_END\\n'; done",
            HerdrMultiplexer.snapshotStreamCommand(),
        )
        assertEquals(prelude + "herdr tab focus 'tab-1'", HerdrMultiplexer.focusTabCommand("tab-1"))
        assertEquals(prelude + "herdr agent focus 'pane-1'", HerdrMultiplexer.focusPaneCommand("pane-1"))
        assertEquals(
            prelude + "herdr tab create --workspace 'workspace-1' --focus",
            HerdrMultiplexer.createTabCommand("workspace-1"),
        )
        assertEquals(prelude + "herdr tab close 'tab-1'", HerdrMultiplexer.closeTabCommand("tab-1"))
        assertEquals(
            prelude + "herdr tab focus 'tab'\\''; touch /tmp/nope; '\\'''",
            HerdrMultiplexer.focusTabCommand("tab'; touch /tmp/nope; '"),
        )
    }
}

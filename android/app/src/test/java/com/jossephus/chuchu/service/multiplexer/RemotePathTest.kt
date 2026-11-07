package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathTest {
    @Test
    fun leavesCommandAloneWithoutConfiguredPath() {
        assertEquals("exec zmx attach 'x'", RemotePath.apply(null, "zmx", "exec zmx attach 'x'"))
        assertEquals("exec zmx attach 'x'", RemotePath.apply("   ", "zmx", "exec zmx attach 'x'"))
    }

    @Test
    fun takesTheDirectoryWhenTheExecutablePathIsPasted() {
        // What `which zmx` prints on a mise install.
        assertEquals(
            "export PATH='/home/x/.local/share/mise/installs/zmx/latest':\"\$PATH\"; exec zmx attach 'x'",
            RemotePath.apply(
                "/home/x/.local/share/mise/installs/zmx/latest/zmx",
                "zmx",
                "exec zmx attach 'x'",
            ),
        )
    }

    @Test
    fun keepsDirectoriesAndToleratesTrailingSlashes() {
        assertEquals(
            "export PATH='/opt/bin:/usr/local/bin:/home/x/.local/bin':\"\$PATH\"; true",
            RemotePath.apply(
                " /opt/bin/ : /usr/local/bin :/home/x/.local/bin ",
                "zmx",
                "true",
            ),
        )
    }

    @Test
    fun onlyStripsTheConfiguredMultiplexerItself() {
        // Tradeoff: a directory that happens to be named like the multiplexer is read as the
        // executable and loses its last segment. The field is documented as "multiplexer path",
        // so pasting an executable is the normal case.
        assertEquals(
            "export PATH='/opt':\"\$PATH\"; true",
            RemotePath.apply("/opt/zmx", "zmx", "true"),
        )
        assertEquals(
            "export PATH='/opt/bin/tmux':\"\$PATH\"; true",
            RemotePath.apply("/opt/bin/tmux", "zmx", "true"),
        )
    }

    @Test
    fun quotesEntriesSoTheyCannotBreakTheCommand() {
        assertEquals(
            "export PATH='/tmp/a'\\''b':\"\$PATH\"; true",
            RemotePath.apply("/tmp/a'b", "zmx", "true"),
        )
    }
}

package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class ZmxMultiplexerTest {
    @Test
    fun launchCreateUsesAttachUpsert() {
        val command = ZmxMultiplexer.launchCommand(
            sessionName = "chuchu-1",
            createIfMissing = true,
            trustedRemoteName = false,
        )

        assertEquals("exec zmx attach 'chuchu-1'", command)
    }

    @Test
    fun launchExistingChecksListBeforeAttach() {
        val command = ZmxMultiplexer.launchCommand(
            sessionName = "main",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "if zmx list --short 2>/dev/null | grep -Fqx -- 'main'; then exec zmx attach 'main'; " +
                "else printf 'zmx session %s is no longer available\\n' 'main'; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun launchQuotesTrustedRemoteNames() {
        val command = ZmxMultiplexer.launchCommand(
            sessionName = "work'space; rm -rf /",
            createIfMissing = true,
            trustedRemoteName = true,
        )

        assertEquals("exec zmx attach 'work'\\''space; rm -rf /'", command)
    }

    @Test
    fun listSessionsCommandChecksExecutable() {
        val command = ZmxMultiplexer.listSessionsCommand()

        assertEquals(
            "if ! command -v zmx >/dev/null 2>&1; then printf 'zmx executable not found\\n' >&2; false; else zmx list 2>/dev/null; fi",
            command,
        )
    }

    @Test
    fun parsesSessionList() {
        val sessions = ZmxMultiplexer.parseSessions(
            "name=main\tpid=123\tclients=1\tcreated=0\tstart_dir=/tmp\n" +
                "name=work\tpid=456\tclients=0\tcreated=1\n",
        )

        assertEquals(
            listOf(
                RemoteMultiplexerSession(name = "main", attached = true),
                RemoteMultiplexerSession(name = "work", attached = false),
            ),
            sessions,
        )
    }
}

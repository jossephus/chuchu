package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class HerdrMultiplexerTest {
    @Test
    fun availabilityCommandChecksExecutable() {
        val command = HerdrMultiplexer.availabilityCommand()

        assertEquals(
            "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; command -v herdr >/dev/null 2>&1",
            command,
        )
    }

    @Test
    fun listSessionsCommandChecksExecutable() {
        val command = HerdrMultiplexer.listSessionsCommand()

        assertEquals(
            "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; " +
                "if ! command -v herdr >/dev/null 2>&1; then printf 'herdr executable not found\\n' >&2; false; " +
                "else herdr session list --json 2>/dev/null; fi",
            command,
        )
    }

    @Test
    fun parsesSessionList() {
        val sessions = HerdrMultiplexer.parseSessions(
            "{\"sessions\":[{\"default\":true,\"name\":\"default\",\"running\":true," +
                "\"session_dir\":\"/home/salem/.config/herdr\",\"socket_path\":\"/home/salem/.config/herdr/herdr.sock\"}]}",
        )

        assertEquals(
            listOf(RemoteMultiplexerSession(name = "default", attached = true)),
            sessions,
        )
    }

    @Test
    fun parsesDefaultSessionFirst() {
        val sessions = HerdrMultiplexer.parseSessions(
            "{\"sessions\":[{\"default\":false,\"name\":\"work\",\"running\":false}," +
                "{\"default\":true,\"name\":\"default\",\"running\":true}]}",
        )

        assertEquals(
            listOf(
                RemoteMultiplexerSession(name = "default", attached = true),
                RemoteMultiplexerSession(name = "work", attached = false),
            ),
            sessions,
        )
    }

    @Test
    fun parsesEmptyListForInvalidOutput() {
        assertEquals(emptyList<RemoteMultiplexerSession>(), HerdrMultiplexer.parseSessions("garbage"))
        assertEquals(emptyList<RemoteMultiplexerSession>(), HerdrMultiplexer.parseSessions(""))
        assertEquals(
            listOf(RemoteMultiplexerSession(name = "default", attached = true)),
            HerdrMultiplexer.parseSessions("Last login: yesterday\\n{\"sessions\":[{\"default\":true,\"name\":\"default\",\"running\":true}]}"),
        )
    }

    @Test
    fun launchCreateUsesSessionUpsert() {
        val command = HerdrMultiplexer.launchCommand(
            sessionName = "default",
            createIfMissing = true,
            trustedRemoteName = false,
        )

        assertEquals(
            "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; exec herdr --session 'default'",
            command,
        )
    }

    @Test
    fun launchExistingChecksListBeforeAttach() {
        val command = HerdrMultiplexer.launchCommand(
            sessionName = "main",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; " +
                "if herdr session list --json 2>/dev/null | grep -Fq -- '\"name\":\"main\"'; " +
                "then exec herdr session attach 'main'; else printf 'herdr session %s is no longer available\\n' 'main'; " +
                "exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun launchQuotesSessionNames() {
        val command = HerdrMultiplexer.launchCommand(
            sessionName = "work';rm -rf",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; " +
                "if herdr session list --json 2>/dev/null | grep -Fq -- '\"name\":\"work'\\'';rm -rf\"'; " +
                "then exec herdr session attach 'work'\\'';rm -rf'; else printf 'herdr session %s is no longer available\\n' " +
                "'work'\\'';rm -rf'; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun terminalSessionCommandUsesRequestedModeAndQuotesPaneId() {
        val prelude = "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; "

        assertEquals(
            prelude + "herdr terminal session control 'pane-1' --cols 83 --rows 28",
            HerdrMultiplexer.terminalSessionCommand("pane-1", 83, 28, HerdrStreamMode.Control),
        )
        assertEquals(
            prelude + "herdr terminal session control 'pane-1' --cols 83 --rows 28 --takeover",
            HerdrMultiplexer.terminalSessionCommand("pane-1", 83, 28, HerdrStreamMode.ControlTakeover),
        )
        assertEquals(
            prelude + "herdr terminal session observe 'pane-1' --cols 83 --rows 28",
            HerdrMultiplexer.terminalSessionCommand("pane-1", 83, 28, HerdrStreamMode.Observe),
        )
        assertEquals(
            prelude + "herdr terminal session control 'pane'\\''; touch /tmp/nope; '\\''' --cols 83 --rows 28",
            HerdrMultiplexer.terminalSessionCommand(
                "pane'; touch /tmp/nope; '",
                83,
                28,
                HerdrStreamMode.Control,
            ),
        )
    }

    @Test
    fun focusWorkspaceCommandQuotesWorkspaceId() {
        assertEquals(
            "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; herdr workspace focus 'workspace'\\''; no'",
            HerdrMultiplexer.focusWorkspaceCommand("workspace'; no"),
        )
    }
}

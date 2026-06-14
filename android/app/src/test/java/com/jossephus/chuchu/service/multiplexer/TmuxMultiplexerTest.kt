package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxMultiplexerTest {
    @Test
    fun launchCreatesOrSwitchesOutsideTmuxAndSwitchesClientInsideTmux() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "chuchu-1",
            createIfMissing = true,
            trustedRemoteName = false,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=chuchu-1'; else exec tmux new-session -A -s 'chuchu-1'; fi",
            command,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun launchRejectsUngeneratedUntrustedNames() {
        TmuxMultiplexer.launchCommand(
            sessionName = "work'space",
            createIfMissing = true,
            trustedRemoteName = false,
        )
    }

    @Test
    fun launchQuotesTrustedRemoteNames() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "work'space; rm -rf /",
            createIfMissing = true,
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=work'\\''space; rm -rf /'; else exec tmux new-session -A -s 'work'\\''space; rm -rf /'; fi",
            command,
        )
    }

    @Test
    fun launchExistingUsesSwitchClientAndExactAttachFallback() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "chuchu-1",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=chuchu-1'; " +
                "elif tmux has-session -t '=chuchu-1' 2>/dev/null; then exec tmux attach-session -t '=chuchu-1'; " +
                "else printf 'tmux session %s is no longer available\\n' 'chuchu-1'; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun launchExistingQuotesTrustedRemoteNamesWithMetacharacters() {
        val command = TmuxMultiplexer.launchCommand(
            sessionName = "main tab; printf 'x'",
            createIfMissing = false,
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=main tab; printf '\\''x'\\'''; " +
                "elif tmux has-session -t '=main tab; printf '\\''x'\\''' 2>/dev/null; then exec tmux attach-session -t '=main tab; printf '\\''x'\\'''; " +
                "else printf 'tmux session %s is no longer available\\n' 'main tab; printf '\\''x'\\'''; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun listSessionsCommandChecksExecutableBeforeTreatingNoServerAsEmptySuccess() {
        val command = TmuxMultiplexer.listSessionsCommand()

        assertEquals(
            "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; false; " +
                "else tmux list-sessions -F '#{session_name}\t#{session_attached}' 2>/dev/null; " +
                "status=\$?; if [ \"\$status\" -eq 1 ]; then true; else [ \"\$status\" -eq 0 ]; fi; fi",
            command,
        )
    }

    @Test
    fun parsesSessionList() {
        val sessions = TmuxMultiplexer.parseSessions("main\t1\nwork\t0\n")

        assertEquals(
            listOf(
                RemoteMultiplexerSession(name = "main", attached = true),
                RemoteMultiplexerSession(name = "work", attached = false),
            ),
            sessions,
        )
    }
}

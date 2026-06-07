package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxMultiplexerCommandsTest {
    @Test
    fun attachOrSwitchUsesExecAttachOrSwitchOutsideTmuxAndSwitchClientInsideTmux() {
        val command = TmuxMultiplexerCommands.interactiveAttachOrSwitchCommand(
            sessionName = "chuchu-1",
            trustedRemoteName = false,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=chuchu-1'; else exec tmux new-session -A -s 'chuchu-1'; fi",
            command,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun attachOrSwitchRejectsUngeneratedUntrustedNames() {
        TmuxMultiplexerCommands.interactiveAttachOrSwitchCommand(
            sessionName = "work'space",
            trustedRemoteName = false,
        )
    }

    @Test
    fun attachOrSwitchQuotesTrustedRemoteNames() {
        val command = TmuxMultiplexerCommands.interactiveAttachOrSwitchCommand(
            sessionName = "work'space; rm -rf /",
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=work'\\''space; rm -rf /'; else exec tmux new-session -A -s 'work'\\''space; rm -rf /'; fi",
            command,
        )
    }

    @Test
    fun attachExistingUsesSwitchClientAndExactAttachFallback() {
        val command = TmuxMultiplexerCommands.interactiveAttachExistingCommand(
            sessionName = "chuchu-1",
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
    fun attachExistingQuotesTrustedRemoteNamesWithMetacharacters() {
        val command = TmuxMultiplexerCommands.interactiveAttachExistingCommand(
            sessionName = "main tab; printf 'x'",
            trustedRemoteName = true,
        )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=main tab; printf '\\''x'\\'''; " +
                "elif tmux has-session -t '=main tab; printf '\\''x'\\''' 2>/dev/null; then exec tmux attach-session -t '=main tab; printf '\\''x'\\'''; " +
                "else printf 'tmux session %s is no longer available\\n' 'main tab; printf '\\''x'\\'''; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }
}

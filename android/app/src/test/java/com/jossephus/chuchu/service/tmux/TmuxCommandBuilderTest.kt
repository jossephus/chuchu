package com.jossephus.chuchu.service.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCommandBuilderTest {
    @Test
    fun attachOrCreateUsesExactTargetWhenSwitchingInsideTmux() {
        val command = TmuxCommandBuilder.interactiveAttachOrSwitchCommand(
            sessionName = "work",
            trustedRemoteName = true,
        )

        assertTrue(command.contains("tmux switch-client -t '=work'"))
        assertTrue(command.contains("exec tmux new-session -A -s 'work'"))
    }

    @Test
    fun attachExistingDoesNotCreateMissingSession() {
        val command = TmuxCommandBuilder.interactiveAttachExistingCommand(
            sessionName = "shared",
            trustedRemoteName = true,
        )

        assertTrue(command.contains("tmux has-session -t '=shared'"))
        assertTrue(command.contains("exec tmux attach-session -t '=shared'"))
        assertFalse(command.contains("new-session -A"))
    }
}

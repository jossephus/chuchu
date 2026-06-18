package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType

object TmuxMultiplexer : Multiplexer {
    private val chuchuSessionRegex = Regex("^chuchu-[1-9][0-9]*$")

    override val type: MultiplexerType = MultiplexerType.Tmux

    override fun availabilityCommand(): String = "command -v tmux >/dev/null 2>&1"

    override fun listSessionsCommand(): String =
        "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; false; " +
            "else tmux list-sessions -F '#{session_name}\t#{session_attached}' 2>/dev/null; " +
            "status=\$?; if [ \"\$status\" -eq 1 ]; then true; else [ \"\$status\" -eq 0 ]; fi; fi"

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> =
        output
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trimEnd('\r')
                if (trimmed.isBlank()) return@mapNotNull null
                val parts = trimmed.split('\t')
                if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
                RemoteMultiplexerSession(
                    name = parts[0],
                    attached = parts.getOrNull(1) == "1",
                )
            }
            .toList()

    override fun launchCommand(
        sessionName: String,
        createIfMissing: Boolean,
        trustedRemoteName: Boolean,
    ): String = if (createIfMissing) {
        interactiveAttachOrSwitchCommand(sessionName, trustedRemoteName)
    } else {
        interactiveAttachExistingCommand(sessionName, trustedRemoteName)
    }

    override fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String = MultiplexerSessionAllocator.nextChuchuSessionName(
        remoteSessions = remoteSessions,
        localSessionNames = localSessionNames,
    )

    fun isGeneratedSessionName(name: String): Boolean = chuchuSessionRegex.matches(name)

    fun requireGeneratedSessionName(name: String): String {
        require(isGeneratedSessionName(name)) { "Invalid Chuchu tmux session name" }
        return name
    }

    private fun interactiveAttachOrSwitchCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = shellQuote(sessionName)
        val exactTarget = shellQuote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; else exec tmux new-session -A -s $target; fi"
    }

    private fun interactiveAttachExistingCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = shellQuote(sessionName)
        val exactTarget = shellQuote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; " +
            "elif tmux has-session -t $exactTarget 2>/dev/null; then exec tmux attach-session -t $exactTarget; " +
            "else printf 'tmux session %s is no longer available\\n' $target; exec \"\${SHELL:-/bin/sh}\" -l; fi"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

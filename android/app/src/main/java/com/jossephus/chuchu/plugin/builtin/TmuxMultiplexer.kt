package com.jossephus.chuchu.plugin.builtin

import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.plugin.api.MultiplexerSessionNames
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.RemoteMultiplexerSession
import com.jossephus.chuchu.plugin.api.Shell

class TmuxPlugin : ChuchuPlugin {
    override fun register(host: PluginHost) = host.registerMultiplexer(TmuxMultiplexer)
}

object TmuxMultiplexer : MultiplexerProvider {
    private val chuchuSessionRegex = Regex("^chuchu-[1-9][0-9]*$")

    override val id: String = "tmux"
    override val displayName: String = "tmux"

    override fun availabilityCommand(): String = "command -v tmux >/dev/null 2>&1"

    override fun listSessionsCommand(): String =
        // Do not name this variable `status`: in zsh `status` is a read-only alias
        // for `$?`, so assigning it aborts the command and the exit marker is never printed.
        "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; false; " +
            "else tmux list-sessions -F '#{session_attached} #{session_name}' 2>/dev/null; " +
            "rc=\$?; if [ \"\$rc\" -eq 1 ]; then true; else [ \"\$rc\" -eq 0 ]; fi; fi"

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> =
        output
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trimEnd('\r')
                if (trimmed.isBlank()) return@mapNotNull null
                val separatorIndex = trimmed.indexOf(' ')
                if (separatorIndex == -1) return@mapNotNull null
                val name = trimmed.substring(separatorIndex + 1)
                if (name.isBlank()) return@mapNotNull null
                val attachedCount = trimmed.substring(0, separatorIndex)
                    .toIntOrNull()
                    ?.takeIf { it >= 0 }
                    ?: return@mapNotNull null
                RemoteMultiplexerSession(
                    name = name,
                    attached = attachedCount > 0,
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
    ): String = MultiplexerSessionNames.next(remoteSessions, localSessionNames)

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
        val target = Shell.quote(sessionName)
        val exactTarget = Shell.quote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; else exec tmux new-session -A -s $target; fi"
    }

    private fun interactiveAttachExistingCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = Shell.quote(sessionName)
        val exactTarget = Shell.quote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; " +
            "elif tmux has-session -t $exactTarget 2>/dev/null; then exec tmux attach-session -t $exactTarget; " +
            "else printf 'tmux session %s is no longer available\\n' $target; exec \"\${SHELL:-/bin/sh}\" -l; fi"
    }
}

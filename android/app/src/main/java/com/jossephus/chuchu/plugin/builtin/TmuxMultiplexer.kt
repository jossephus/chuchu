package com.jossephus.chuchu.plugin.builtin

import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.plugin.api.MultiplexerSessionNames
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.RemoteMultiplexerSession
import com.jossephus.chuchu.plugin.api.Shell

/** Built-in plugin contributing tmux session persistence. */
class TmuxPlugin : ChuchuPlugin {
    override fun register(host: PluginHost) = host.registerMultiplexer(TmuxMultiplexer)
}

object TmuxMultiplexer : MultiplexerProvider {
    private val chuchuSessionRegex = Regex("^chuchu-[1-9][0-9]*$")

    override val id: String = "tmux"
    override val displayName: String = "tmux"

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

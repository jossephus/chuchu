package com.jossephus.chuchu.service.tmux

object TmuxCommandBuilder {
    private val chuchuSessionRegex = Regex("^chuchu-[1-9][0-9]*$")
    private const val SESSION_LIST_FORMAT = "#{session_name}\t#{?session_attached,1,0}"

    fun isGeneratedSessionName(name: String): Boolean = chuchuSessionRegex.matches(name)

    fun requireGeneratedSessionName(name: String): String {
        require(isGeneratedSessionName(name)) { "Invalid Chuchu tmux session name" }
        return name
    }

    fun quoteForSh(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun fixedShellCommand(command: String): String = "sh -lc ${quoteForSh(command)}"

    fun availabilityCommand(): String = fixedShellCommand("command -v tmux >/dev/null")

    fun platformProbeCommand(): String = fixedShellCommand(
        "if [ -r /etc/os-release ]; then cat /etc/os-release; " +
            "elif [ -r /usr/lib/os-release ]; then cat /usr/lib/os-release; fi; " +
            "printf '\\nCHUCHU_UNAME=%s\\n' \"$(uname -s 2>/dev/null || true)\"; " +
            "printf 'CHUCHU_COMMANDS=%s\\n' \"$(for c in apt-get dnf yum pacman zypper apk sudo doas brew pkg nix; do command -v ${'$'}c >/dev/null 2>&1 && printf '%s ' ${'$'}c; done)\"",
    )

    fun listSessionsCommand(): String = fixedShellCommand(
        "tmux list-sessions -F ${quoteForSh(SESSION_LIST_FORMAT)} 2>/dev/null || true",
    )

    fun installCommand(command: String): String = fixedShellCommand(command)

    fun interactiveAttachOrSwitchCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = quoteForSh(sessionName)
        val exactTarget = quoteForSh("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; else exec tmux new-session -A -s $target; fi"
    }

    fun interactiveAttachExistingCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = quoteForSh(sessionName)
        val exactTarget = quoteForSh("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; " +
            "elif tmux has-session -t $exactTarget 2>/dev/null; then exec tmux attach-session -t $exactTarget; " +
            "else printf 'tmux session %s is no longer available\\n' $target; exec \"\${SHELL:-/bin/sh}\" -l; fi"
    }

    fun parseSessionList(output: String): List<RemoteTmuxSession> =
        output
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trimEnd('\r')
                if (trimmed.isBlank()) return@mapNotNull null
                val parts = trimmed.split('\t')
                if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
                RemoteTmuxSession(
                    name = parts[0],
                    attached = parts.getOrNull(1) == "1",
                )
            }
            .toList()
}

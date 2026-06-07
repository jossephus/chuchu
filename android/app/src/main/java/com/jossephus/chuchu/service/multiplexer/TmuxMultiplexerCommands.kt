package com.jossephus.chuchu.service.multiplexer

object TmuxMultiplexerCommands {
    private val chuchuSessionRegex = Regex("^chuchu-[1-9][0-9]*$")

    fun isGeneratedSessionName(name: String): Boolean = chuchuSessionRegex.matches(name)

    fun requireGeneratedSessionName(name: String): String {
        require(isGeneratedSessionName(name)) { "Invalid Chuchu tmux session name" }
        return name
    }

    fun availabilityCommand(): String = "command -v tmux >/dev/null 2>&1"

    fun listSessionsCommand(): String =
        "tmux list-sessions -F '#{session_name}\t#{session_attached}' 2>/dev/null || true"

    fun platformProbeCommand(): String =
        "printf 'CHUCHU_UNAME=%s\\n' \"$(uname -s 2>/dev/null)\"; " +
            "printf 'CHUCHU_ID=%s\\n' \"$(. /etc/os-release 2>/dev/null; printf '%s' \"\$ID\")\"; " +
            "printf 'CHUCHU_ID_LIKE=%s\\n' \"$(. /etc/os-release 2>/dev/null; printf '%s' \"\$ID_LIKE\")\"; " +
            "printf 'CHUCHU_HAS_APK=%s\\n' \"$(command -v apk >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
            "printf 'CHUCHU_HAS_APT=%s\\n' \"$(command -v apt-get >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
            "printf 'CHUCHU_HAS_DNF=%s\\n' \"$(command -v dnf >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
            "printf 'CHUCHU_HAS_YUM=%s\\n' \"$(command -v yum >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
            "printf 'CHUCHU_HAS_PACMAN=%s\\n' \"$(command -v pacman >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
            "printf 'CHUCHU_HAS_BREW=%s\\n' \"$(command -v brew >/dev/null 2>&1 && echo 1 || echo 0)\""

    fun installCommand(packageCommand: String): String = packageCommand

    fun interactiveAttachOrSwitchCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = shellQuote(sessionName)
        val exactTarget = shellQuote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; else exec tmux new-session -A -s $target; fi"
    }

    fun interactiveAttachExistingCommand(
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

    fun parseSessionList(output: String): List<RemoteMultiplexerSession> =
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

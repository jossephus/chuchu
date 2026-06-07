package com.jossephus.chuchu.service.tmux

object TmuxInstallMatrix {
    fun fromProbeOutput(output: String): TmuxInstallCandidate {
        val values = parseKeyValues(output)
        val id = values["ID"].orEmpty().lowercase()
        val idLike = values["ID_LIKE"].orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val prettyName = values["PRETTY_NAME"].orEmpty().ifBlank { values["NAME"].orEmpty() }
        val uname = values["CHUCHU_UNAME"].orEmpty()
        val commands = values["CHUCHU_COMMANDS"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val platformLabel = prettyName.ifBlank { uname.ifBlank { id.ifBlank { "Unknown OS" } } }

        fun candidate(command: String, manager: String, guidance: String = "Install tmux, then reconnect.") =
            TmuxInstallCandidate(
                platformLabel = platformLabel,
                command = command,
                packageManager = manager,
                guidance = guidance,
            )

        return when {
            id == "nixos" -> candidate(
                "nix profile install nixpkgs#tmux",
                "nix",
                "Install tmux in your user profile, or add tmux to configuration.nix/system packages.",
            )
            id in setOf("debian", "ubuntu") || "debian" in idLike || "apt-get" in commands ->
                candidate("sudo apt-get update && sudo apt-get install -y tmux", "apt-get")
            id == "fedora" || ("fedora" in idLike && "dnf" in commands) ->
                candidate("sudo dnf install -y tmux", "dnf")
            id in setOf("rhel", "centos", "rocky", "almalinux") || "rhel" in idLike ->
                if ("dnf" in commands) candidate("sudo dnf install -y tmux", "dnf")
                else candidate("sudo yum install -y tmux", "yum")
            id in setOf("arch", "manjaro", "endeavouros") || "arch" in idLike || "pacman" in commands ->
                candidate("sudo pacman -S --needed tmux", "pacman")
            id.startsWith("opensuse") || id == "sles" || "suse" in idLike || "zypper" in commands ->
                candidate("sudo zypper install tmux", "zypper")
            id == "alpine" || "apk" in commands -> {
                val prefix = if ("doas" in commands && "sudo" !in commands) "doas" else "sudo"
                candidate("$prefix apk add tmux", "apk")
            }
            uname == "Darwin" && "brew" in commands -> candidate("brew install tmux", "brew")
            uname == "FreeBSD" || "pkg" in commands -> candidate("sudo pkg install tmux", "pkg")
            else -> TmuxInstallCandidate(
                platformLabel = platformLabel,
                command = null,
                packageManager = null,
                guidance = "Install tmux with this host's package manager, then reconnect.",
            )
        }
    }

    private fun parseKeyValues(output: String): Map<String, String> = buildMap {
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val equals = line.indexOf('=')
            if (equals <= 0) return@forEach
            val key = line.substring(0, equals)
            val value = unquote(line.substring(equals + 1).trim())
            put(key, value)
        }
    }

    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val quote = value.first()
        if ((quote != '\'' && quote != '"') || value.last() != quote) return value
        return value.substring(1, value.lastIndex)
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
    }
}

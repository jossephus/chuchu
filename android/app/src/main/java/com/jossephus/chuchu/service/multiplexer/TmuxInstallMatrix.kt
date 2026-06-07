package com.jossephus.chuchu.service.multiplexer

object TmuxInstallMatrix {
    fun fromProbeOutput(output: String): MultiplexerInstallCandidate {
        val values = output.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        val uname = values["CHUCHU_UNAME"].orEmpty().lowercase()
        val id = values["CHUCHU_ID"].orEmpty().lowercase()
        val idLike = values["CHUCHU_ID_LIKE"].orEmpty().lowercase()
        return when {
            values["CHUCHU_HAS_APT"] == "1" -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "Debian/Ubuntu"),
                packageManager = "apt",
                command = "sudo apt-get update && sudo apt-get install -y tmux",
                guidance = "Install tmux with apt on the remote host.",
            )
            values["CHUCHU_HAS_APK"] == "1" -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "Alpine"),
                packageManager = "apk",
                command = "sudo apk add tmux",
                guidance = "Install tmux with apk on the remote host.",
            )
            values["CHUCHU_HAS_DNF"] == "1" -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "Fedora/RHEL"),
                packageManager = "dnf",
                command = "sudo dnf install -y tmux",
                guidance = "Install tmux with dnf on the remote host.",
            )
            values["CHUCHU_HAS_YUM"] == "1" -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "RHEL/CentOS"),
                packageManager = "yum",
                command = "sudo yum install -y tmux",
                guidance = "Install tmux with yum on the remote host.",
            )
            values["CHUCHU_HAS_PACMAN"] == "1" -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "Arch"),
                packageManager = "pacman",
                command = "sudo pacman -S --needed tmux",
                guidance = "Install tmux with pacman on the remote host.",
            )
            values["CHUCHU_HAS_BREW"] == "1" && uname == "darwin" -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "macOS"),
                packageManager = "brew",
                command = "brew install tmux",
                guidance = "Install tmux with Homebrew on the remote host.",
            )
            else -> MultiplexerInstallCandidate(
                platformLabel = platformLabel(id, idLike, uname, "Unknown"),
                command = null,
                guidance = "Install tmux with this host's package manager, then retry.",
            )
        }
    }

    private fun platformLabel(
        id: String,
        idLike: String,
        uname: String,
        fallback: String,
    ): String = listOf(id, idLike, uname)
        .firstOrNull { it.isNotBlank() }
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        ?: fallback
}

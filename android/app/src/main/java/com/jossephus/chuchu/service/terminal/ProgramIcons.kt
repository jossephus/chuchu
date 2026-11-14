package com.jossephus.chuchu.service.terminal

import androidx.annotation.DrawableRes
import com.jossephus.chuchu.R

class ProgramIcon(@DrawableRes val drawable: Int, val brandColor: Long, val names: List<String>)

object ProgramIcons {
    private val icons =
        listOf(
            ProgramIcon(R.drawable.ic_program_claude, 0xFFD97757, listOf("claude", "claude-code", "claude code")),
            ProgramIcon(R.drawable.ic_program_opencode, 0xFF000000, listOf("opencode")),
            ProgramIcon(R.drawable.ic_program_googlegemini, 0xFF8E75B2, listOf("gemini", "gemini-cli")),
            ProgramIcon(R.drawable.ic_program_cursor, 0xFF000000, listOf("cursor", "cursor-agent")),
            ProgramIcon(R.drawable.ic_program_githubcopilot, 0xFF000000, listOf("copilot", "github-copilot", "gh-copilot")),
            ProgramIcon(R.drawable.ic_program_mistralai, 0xFFFA520F, listOf("mistral", "vibe", "mistral-vibe")),
            ProgramIcon(R.drawable.ic_program_ollama, 0xFF000000, listOf("ollama")),
            ProgramIcon(R.drawable.ic_program_rust, 0xFF000000, listOf("rust", "cargo", "rustc", "rustup")),
            ProgramIcon(R.drawable.ic_program_go, 0xFF00ADD8, listOf("go", "gopls")),
            ProgramIcon(R.drawable.ic_program_zig, 0xFFF7A41D, listOf("zig")),
            ProgramIcon(R.drawable.ic_program_nodedotjs, 0xFF5FA04E, listOf("node", "nodejs", "npx")),
            ProgramIcon(R.drawable.ic_program_npm, 0xFFCB3837, listOf("npm")),
            ProgramIcon(R.drawable.ic_program_pnpm, 0xFFF69220, listOf("pnpm")),
            ProgramIcon(R.drawable.ic_program_yarn, 0xFF2C8EBB, listOf("yarn")),
            ProgramIcon(R.drawable.ic_program_bun, 0xFF000000, listOf("bun", "bunx")),
            ProgramIcon(R.drawable.ic_program_deno, 0xFF000000, listOf("deno")),
            ProgramIcon(R.drawable.ic_program_python, 0xFF3776AB, listOf("python", "pip", "pytest", "ipython")),
            ProgramIcon(R.drawable.ic_program_uv, 0xFFDE5FE9, listOf("uv", "uvx")),
            ProgramIcon(R.drawable.ic_program_gradle, 0xFF02303A, listOf("gradle", "gradlew")),
            ProgramIcon(R.drawable.ic_program_nixos, 0xFF5277C3, listOf("nix", "nix-build", "nix-shell", "nix-env", "nixos-rebuild", "home-manager")),
            ProgramIcon(R.drawable.ic_program_docker, 0xFF2496ED, listOf("docker", "docker-compose")),
            ProgramIcon(R.drawable.ic_program_podman, 0xFF892CA0, listOf("podman")),
            ProgramIcon(R.drawable.ic_program_terraform, 0xFF844FBA, listOf("terraform")),
            ProgramIcon(R.drawable.ic_program_opentofu, 0xFFFFDA18, listOf("tofu", "opentofu")),
            ProgramIcon(R.drawable.ic_program_kubernetes, 0xFF326CE5, listOf("kubectl", "k9s", "kubectx", "kubens")),
            ProgramIcon(R.drawable.ic_program_vercel, 0xFF000000, listOf("vercel")),
            ProgramIcon(R.drawable.ic_program_cloudflare, 0xFFF38020, listOf("wrangler", "cloudflared")),
            ProgramIcon(R.drawable.ic_program_tailscale, 0xFF242424, listOf("tailscale")),
            ProgramIcon(R.drawable.ic_program_neovim, 0xFF57A143, listOf("nvim", "neovim")),
            ProgramIcon(R.drawable.ic_program_vim, 0xFF019733, listOf("vim", "vi")),
            ProgramIcon(R.drawable.ic_program_git, 0xFFF03C2E, listOf("git", "lazygit", "tig", "gitui")),
            ProgramIcon(R.drawable.ic_program_swift, 0xFFF05138, listOf("swift", "swiftc")),
            ProgramIcon(R.drawable.ic_program_dart, 0xFF0175C2, listOf("dart")),
            ProgramIcon(R.drawable.ic_program_flutter, 0xFF02569B, listOf("flutter")),
            ProgramIcon(R.drawable.ic_program_ruby, 0xFFCC342D, listOf("ruby", "bundle", "rake", "rails", "irb")),
            ProgramIcon(R.drawable.ic_program_php, 0xFF777BB4, listOf("php", "composer")),
            ProgramIcon(R.drawable.ic_program_elixir, 0xFF8847B9, listOf("elixir", "mix", "iex")),
        )

    private val byName: Map<String, ProgramIcon> = icons.flatMap { icon -> icon.names.map { it to icon } }.toMap()

    fun forName(name: String?): ProgramIcon? {
        val key = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return byName[key] ?: byName[key.trimEnd { it.isDigit() || it == '.' }]
    }
}

package com.jossephus.chuchu.service.multiplexer

/**
 * SSH exec channels run the command directly, without the account's shell rc files, so a
 * multiplexer installed by mise/nix/asdf/homebrew is not on PATH there even though the
 * interactive shell finds it. A host can name those directories in its profile and every
 * exec-channel command gets them prepended.
 *
 * The value may also be the executable itself (`which zmx`), which is what users have at hand;
 * PATH wants its directory, so a matching basename is stripped. `~` is not expanded by the
 * remote shell, so entries have to be absolute.
 *
 * The session the multiplexer spawns inherits this PATH too, so an attached shell starts with it.
 */
object RemotePath {
    fun apply(path: String?, executable: String, command: String): String {
        val entries =
            path.orEmpty()
                .split(':')
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .map { if (it.substringAfterLast('/') == executable) it.substringBeforeLast('/') else it }
                .filter { it.isNotEmpty() }
        if (entries.isEmpty()) return command
        return "export PATH=${shellQuote(entries.joinToString(":"))}:\"\$PATH\"; $command"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

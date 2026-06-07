package com.jossephus.chuchu.model

enum class Multiplexer(
    val id: String,
    val label: String,
    val runtimeSupported: Boolean,
) {
    Tmux("tmux", "tmux", true),
    Zellij("zellij", "zellij", false),
    Zmx("zmx", "zmx", false),
    ;

    companion object {
        val runtimeOptions: List<Multiplexer> = entries.filter { it.runtimeSupported }

        fun fromPersistedValue(value: String): Multiplexer? =
            entries.firstOrNull { it.id == value } ?: entries.firstOrNull { it.name == value }
    }
}

package com.jossephus.chuchu.model

enum class MultiplexerType(
    val id: String,
    val label: String,
    val runtimeSupported: Boolean,
) {
    Tmux("tmux", "tmux", true),
    Zellij("zellij", "zellij", false),
    Zmx("zmx", "zmx", true),
    ;

    companion object {
        val runtimeOptions: List<MultiplexerType> = entries.filter { it.runtimeSupported }

        fun fromPersistedValue(value: String): MultiplexerType? =
            entries.firstOrNull { it.id == value } ?: entries.firstOrNull { it.name == value }
    }
}

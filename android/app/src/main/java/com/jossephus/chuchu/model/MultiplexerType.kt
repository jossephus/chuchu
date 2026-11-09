package com.jossephus.chuchu.model

import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry

// Not an enum: plugins add multiplexers at runtime, and a host keeps its id while the plugin is missing.
data class MultiplexerType(val id: String) {
    val label: String
        get() = MultiplexerRegistry.forType(this)?.displayName ?: id

    val runtimeSupported: Boolean
        get() = MultiplexerRegistry.forType(this) != null

    companion object {
        val Tmux = MultiplexerType("tmux")
        val Zmx = MultiplexerType("zmx")

        // Early builds persisted enum constant names; map them to their ids.
        private val legacyNames = mapOf("Tmux" to "tmux", "Zellij" to "zellij", "Zmx" to "zmx")

        fun fromPersistedValue(value: String): MultiplexerType? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            return MultiplexerType(legacyNames[trimmed] ?: trimmed)
        }
    }
}

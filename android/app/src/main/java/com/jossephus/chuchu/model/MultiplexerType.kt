package com.jossephus.chuchu.model

import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry

/**
 * A host's chosen multiplexer, identified by the stable id its provider registered
 * (`MultiplexerProvider.id`). Deliberately open rather than an enum: plugins add
 * multiplexers at runtime, and a host keeps its id even while the providing plugin is
 * missing, so reinstalling the plugin restores it.
 */
data class MultiplexerType(val id: String) {
    /** Provider's display name, or the raw id when no loaded plugin provides it. */
    val label: String
        get() = MultiplexerRegistry.forType(this)?.displayName ?: id

    /** Whether a loaded plugin can actually run this multiplexer. */
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

package com.jossephus.chuchu.ui.terminal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The fixed set of "chuchu command" builtins a user can bind a shortcut key to.
 *
 * Single source of truth for the command id (persisted), the display label, the
 * settings description, and the default shortcut key. Mirrors the label-carrying enum
 * pattern used by [CustomActionModifier]. Enum order is the order shown in settings and
 * in the hint bar.
 */
enum class BuiltinCommand(
    val id: String,
    val label: String,
    val description: String,
    val defaultKey: Char,
) {
    Tabs("tabs", "tabs", "show tab manager", 't'),
    NewTab("new_tab", "new tab", "open a new tab", 'n'),
    Actions("actions", "actions", "toggle floating custom actions button", 'a'),
    Settings("settings", "settings", "open settings", 's'),
    Close("close", "close", "close the active tab", 'q');

    companion object {
        fun fromId(id: String): BuiltinCommand? = entries.find { it.id == id }
    }
}

/**
 * Persistence for builtin-command shortcut keys, stored as a single JSON string
 * (command id -> single key char; empty value = command hidden).
 *
 * Uses kotlinx.serialization so any key char — including `:`, `,`, `;`, quotes — is
 * safe, mirroring [TerminalCustomActionStore]. The pref key is new on this branch, so
 * there is no legacy delimited format to migrate.
 */
object BuiltinShortcutStore {
    private val json = Json { ignoreUnknownKeys = true }

    val defaults: Map<String, String> =
        BuiltinCommand.entries.associate { it.id to it.defaultKey.toString() }

    fun normalize(shortcuts: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        // Iterate in enum order so the command that keeps a contested key is the same one
        // ChuchuKeyBindings.build would give it to. Unknown ids fall out.
        BuiltinCommand.entries.forEach { command ->
            val key = shortcuts[command.id] ?: return@forEach
            // Lowercase so the stored key, the settings conflict check, the hint label,
            // and runtime matching (which lowercases) all agree.
            val normalized = key.trim().takeLast(1).lowercase()
            // A key already claimed by an earlier command is blanked rather than kept:
            // keeping it would show the command as bound in settings while build() drops it.
            result[command.id] =
                if (normalized.isEmpty() || seen.add(normalized)) normalized else ""
        }
        return result
    }

    fun serialize(shortcuts: Map<String, String>): String = json.encodeToString(shortcuts)

    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return defaults
        return try {
            val parsed = json.decodeFromString<Map<String, String>>(raw)
            normalize(parsed).ifEmpty { defaults }
        } catch (_: SerializationException) {
            defaults
        }
    }
}

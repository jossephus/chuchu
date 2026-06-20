package com.jossephus.chuchu.ui.terminal

import org.json.JSONException
import org.json.JSONObject

/**
 * The fixed set of "chuchu command" builtins a user can bind a shortcut key to.
 *
 * Single source of truth for the command id (persisted), the display label, and the
 * settings description. Mirrors the label-carrying enum pattern used by
 * [CustomActionModifier]. Enum order is the order shown in settings and in the hint bar.
 */
enum class BuiltinCommand(val id: String, val label: String, val description: String) {
    Tabs("tabs", "tabs", "show tab manager"),
    NewTab("new_tab", "new tab", "open a new tab"),
    Actions("actions", "actions", "toggle floating custom actions button"),
    Settings("settings", "settings", "open settings"),
    Close("close", "close", "close the active tab");

    companion object {
        fun fromId(id: String): BuiltinCommand? = entries.find { it.id == id }
    }
}

/**
 * Persistence for builtin-command shortcut keys, stored as a single JSON string
 * (command id -> single key char; empty value = command hidden).
 *
 * Uses JSON (built-in org.json) so any key char — including `:`, `,`, `;`, quotes — is
 * safe, mirroring [TerminalCustomActionStore]. The pref key is new on this branch, so
 * there is no legacy delimited format to migrate.
 */
object BuiltinShortcutStore {
    val defaults: Map<String, String> = mapOf(
        BuiltinCommand.Tabs.id to "t",
        BuiltinCommand.NewTab.id to "n",
        BuiltinCommand.Actions.id to "a",
        BuiltinCommand.Settings.id to "s",
        BuiltinCommand.Close.id to "q",
    )

    fun normalize(shortcuts: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        shortcuts.forEach { (id, key) ->
            if (BuiltinCommand.fromId(id) != null) {
                result[id] = key.takeLast(1)
            }
        }
        return result
    }

    fun serialize(shortcuts: Map<String, String>): String {
        val obj = JSONObject()
        shortcuts.forEach { (id, key) -> obj.put(id, key) }
        return obj.toString()
    }

    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return defaults
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            obj.keys().forEach { id ->
                if (BuiltinCommand.fromId(id) != null) {
                    result[id] = obj.optString(id)
                }
            }
            normalize(result).ifEmpty { defaults }
        } catch (_: JSONException) {
            defaults
        }
    }
}

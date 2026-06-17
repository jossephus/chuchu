package com.jossephus.chuchu.ui.terminal

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

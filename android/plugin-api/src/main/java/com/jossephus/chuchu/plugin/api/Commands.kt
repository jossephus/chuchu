package com.jossephus.chuchu.plugin.api

/**
 * A user-invoked action. It can be triggered two ways: through the chuchu key (tap the
 * chuchu key on the accessory bar, then [key]; shown in the chuchu-key hint bar), and as a
 * button on the keyboard accessory bar when [accessoryLabel] is set.
 *
 * Built-in commands and the user's own custom actions win any key conflict; a plugin
 * command whose key is taken is not bound (and not shown) rather than overriding theirs.
 */
class PluginCommand(
    /** Unique within the plugin. The host namespaces it as `<pluginId>.<id>`. */
    val id: String,
    /** Short label for the hint bar, e.g. "herdr". */
    val title: String,
    /** Letter or digit pressed after the chuchu key, or null for no shortcut. */
    val key: Char? = null,
    /**
     * Short label for a keyboard accessory bar button, or null for no button. Plugin
     * buttons come after the user's own keys; when several exist they share one "plugins"
     * key that expands to show them. Keep it to a few characters.
     */
    val accessoryLabel: String? = null,
    /**
     * Runs on the main thread. Throwing disables the plugin, like any other plugin failure,
     * so catch errors you can recover from.
     */
    val run: (CommandContext) -> Unit,
)

/** Where a command was invoked from. */
class CommandContext(
    /** The active tab's session, or null when no terminal tab is open. */
    val session: PluginSession?,
)

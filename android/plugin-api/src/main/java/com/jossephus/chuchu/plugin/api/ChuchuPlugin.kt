package com.jossephus.chuchu.plugin.api

/**
 * Entry point of a plugin. The host instantiates it through a public no-arg constructor
 * (built-ins directly, external APKs by the class named in `chuchu.plugin.entry`).
 *
 * Plugins never import each other or host internals; everything they contribute goes
 * through [PluginHost] inside [register].
 */
interface ChuchuPlugin {
    /**
     * Called once, on the main thread, right after the plugin is loaded. Register
     * contributions here and return quickly; do slow work in [PluginHost.scope].
     *
     * Throwing disables the plugin and rolls back everything it registered, so a
     * half-registered plugin never stays active.
     */
    fun register(host: PluginHost)

    /**
     * Called when the host disables the plugin after a failure. [PluginHost.scope] is
     * already cancelled by then. Release anything the scope
     * doesn't own (native handles, listeners registered outside the host).
     */
    fun onUnload() {}
}

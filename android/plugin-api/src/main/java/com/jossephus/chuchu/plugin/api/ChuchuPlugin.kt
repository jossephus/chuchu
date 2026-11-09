package com.jossephus.chuchu.plugin.api

/** Plugin entry point, created through a public no-arg constructor. */
interface ChuchuPlugin {
    /**
     * Called once on the main thread. Throwing disables the plugin and rolls back everything
     * it registered.
     */
    fun register(host: PluginHost)

    /** Called after a failure disabled the plugin; [PluginHost.scope] is already cancelled. */
    fun onUnload() {}
}

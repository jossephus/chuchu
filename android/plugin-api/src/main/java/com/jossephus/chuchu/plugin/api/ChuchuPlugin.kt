package com.jossephus.chuchu.plugin.api

/** Throwing from [register] disables the plugin and rolls back everything it registered. */
fun interface ChuchuPlugin {
    fun register(host: PluginHost)
}

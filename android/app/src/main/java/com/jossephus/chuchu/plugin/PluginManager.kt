package com.jossephus.chuchu.plugin

import android.util.Log
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.TransportProvider

/** A plugin whose [ChuchuPlugin.register] throws is skipped and everything it registered is rolled back. */
class PluginManager(
    private val logFailure: (String, Throwable) -> Unit = { message, error -> Log.e(TAG, message, error) },
) {
    val multiplexers = Registry<MultiplexerProvider>("multiplexer") { it.id }
    val transports = Registry<TransportProvider>("transport") { it.id }

    private val registries = listOf(multiplexers, transports)

    fun load(plugins: Map<String, ChuchuPlugin>) {
        plugins.forEach { (id, plugin) ->
            try {
                plugin.register(HostImpl(id))
            } catch (e: Throwable) {
                registries.forEach { it.unregisterOwner(id) }
                logFailure("plugin '$id' failed to register", e)
            }
        }
    }

    private inner class HostImpl(private val pluginId: String) : PluginHost {
        override fun registerMultiplexer(provider: MultiplexerProvider) = multiplexers.register(pluginId, provider)

        override fun registerTransport(provider: TransportProvider) = transports.register(pluginId, provider)
    }

    companion object {
        private const val TAG = "ChuchuPlugin"

        val instance: PluginManager by lazy { PluginManager().also { it.load(BuiltinPlugins.all) } }
    }
}

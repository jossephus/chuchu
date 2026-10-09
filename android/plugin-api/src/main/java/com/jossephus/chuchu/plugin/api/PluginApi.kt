package com.jossephus.chuchu.plugin.api

/**
 * Version of the plugin contract in this module.
 *
 * Plugins declare the version they were compiled against (`chuchu.plugin.apiVersion`
 * manifest meta-data). The host loads a plugin only when that version falls within
 * [MIN_SUPPORTED_VERSION]..[VERSION]: older plugins keep working until a breaking change
 * raises the floor, and newer plugins are refused because they may call API this host
 * doesn't have. Additive changes don't bump [VERSION]; removals or signature changes do.
 */
object PluginApi {
    const val VERSION: Int = 1
    const val MIN_SUPPORTED_VERSION: Int = 1

    fun isSupported(apiVersion: Int): Boolean = apiVersion in MIN_SUPPORTED_VERSION..VERSION
}

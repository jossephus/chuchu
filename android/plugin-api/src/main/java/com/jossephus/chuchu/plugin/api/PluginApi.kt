package com.jossephus.chuchu.plugin.api

/**
 * A plugin loads only when its `chuchu.plugin.apiVersion` manifest value is within
 * [MIN_SUPPORTED_VERSION]..[VERSION].
 */
object PluginApi {
    const val VERSION: Int = 2
    const val MIN_SUPPORTED_VERSION: Int = 2

    fun isSupported(apiVersion: Int): Boolean = apiVersion in MIN_SUPPORTED_VERSION..VERSION
}

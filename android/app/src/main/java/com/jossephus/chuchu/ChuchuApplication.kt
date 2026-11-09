package com.jossephus.chuchu

import android.app.Application
import com.jossephus.chuchu.plugin.AppVisibility
import com.jossephus.chuchu.plugin.BuiltinPlugins
import com.jossephus.chuchu.plugin.ExternalPlugins
import com.jossephus.chuchu.plugin.PluginManager

class ChuchuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppVisibility.install(this)
        // Before any session exists, so plugins can't miss the first connection.
        val plugins = PluginManager.getInstance(this)
        plugins.load(BuiltinPlugins.entries())
        // After built-ins, so an external plugin can't claim a built-in's id.
        plugins.load(ExternalPlugins.getInstance(this).startupEntries())
    }
}

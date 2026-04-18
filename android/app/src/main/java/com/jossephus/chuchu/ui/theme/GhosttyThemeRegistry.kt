package com.jossephus.chuchu.ui.theme

import android.content.Context

object GhosttyThemeRegistry {

    private var themeNames: List<String> = emptyList()
    private val cache = mutableMapOf<String, GhosttyTheme>()

    fun init(context: Context) {
        if (themeNames.isNotEmpty()) return
        themeNames = context.assets.list("themes")
            ?.sorted()
            ?: emptyList()
    }

    val availableThemeNames: List<String> get() = themeNames

    fun getTheme(context: Context, name: String): GhosttyTheme? {
        if (name !in themeNames) return null
        cache[name]?.let { return it }
        val content = runCatching {
            context.assets.open("themes/$name").bufferedReader().readText()
        }.getOrNull() ?: return null
        val theme = GhosttyTheme.parse(name, content)
        cache[name] = theme
        return theme
    }
}

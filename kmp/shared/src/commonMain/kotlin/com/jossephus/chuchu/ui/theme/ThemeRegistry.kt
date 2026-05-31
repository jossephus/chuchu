package com.jossephus.chuchu.ui.theme

object ThemeRegistry {
    private var themeNames: List<String> = emptyList()
    private val cache = mutableMapOf<String, GhosttyTheme>()

    val availableThemeNames: List<String> get() = themeNames

    fun init(platformContext: Any? = null) {
        if (themeNames.isNotEmpty()) return
        themeNames = platformAvailableThemeNames(platformContext)
    }

    fun getTheme(platformContext: Any? = null, name: String): GhosttyTheme? {
        if (name !in themeNames) return null
        cache[name]?.let { return it }
        val content = platformLoadThemeContent(platformContext, name) ?: return null
        val theme = GhosttyTheme.parse(name, content)
        cache[name] = theme
        return theme
    }
}

expect fun platformAvailableThemeNames(platformContext: Any?): List<String>

expect fun platformLoadThemeContent(platformContext: Any?, name: String): String?

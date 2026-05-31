package com.jossephus.chuchu.ui.theme

import android.content.Context

actual fun platformAvailableThemeNames(platformContext: Any?): List<String> {
    val ctx = platformContext as? Context ?: return emptyList()
    return ctx.assets.list("themes")?.sorted() ?: emptyList()
}

actual fun platformLoadThemeContent(platformContext: Any?, name: String): String? {
    val ctx = platformContext as? Context ?: return null
    return runCatching {
        ctx.assets.open("themes/$name").bufferedReader().readText()
    }.getOrNull()
}

package com.jossephus.chuchu.ui.theme

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.readResourceBytes

@OptIn(InternalResourceApi::class)
actual fun platformAvailableThemeNames(platformContext: Any?): List<String> {
    return runBlocking {
        runCatching {
            val indexBytes = readResourceBytes("composeResources/kotlinproject.shared.generated.resources/files/theme_index.txt")
            indexBytes.decodeToString().lines().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        }.getOrDefault(emptyList())
    }
}

@OptIn(InternalResourceApi::class)
actual fun platformLoadThemeContent(platformContext: Any?, name: String): String? {
    return runBlocking {
        runCatching {
            readResourceBytes("composeResources/kotlinproject.shared.generated.resources/files/themes/$name").decodeToString()
        }.getOrNull()
    }
}

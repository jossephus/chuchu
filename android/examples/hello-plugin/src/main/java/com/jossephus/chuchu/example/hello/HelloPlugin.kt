package com.jossephus.chuchu.example.hello

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.PluginCommand
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.SessionViewProvider
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.Shell
import com.jossephus.chuchu.plugin.api.TextField
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The smallest useful chuchu plugin: a command (chuchu key + h, or the "hello" accessory
 * button) that types a configurable greeting into the active session and shows a banner
 * above that tab's terminal.
 */
class HelloPlugin : ChuchuPlugin {
    private val greeting =
        TextField(
            key = "greeting",
            title = "greeting",
            description = "text the hello command echoes",
            default = "hello from a plugin",
        )

    // Tabs that have said hello; the banner is shown on these.
    private val greeted = MutableStateFlow<Set<String>>(emptySet())

    override fun register(host: PluginHost) {
        host.registerSettings(SettingsSchema(listOf(greeting)))
        host.registerCommand(
            PluginCommand(id = "hello", title = "hello", key = 'h', accessoryLabel = "hello") { context ->
                val session = context.session ?: return@PluginCommand
                session.writeText("echo ${Shell.quote(host.settings[greeting])}\n")
                greeted.value = greeted.value + session.id
            },
        )
        host.registerSessionView(BannerView())
        host.log.info("hello plugin registered")
    }

    private inner class BannerView : SessionViewProvider {
        override val id = "banner"

        override fun claims(session: PluginSession) = true

        @Composable
        override fun Content(session: PluginSession, modifier: Modifier, defaultTerminal: @Composable () -> Unit) {
            val tabs by greeted.collectAsState()
            if (session.id !in tabs) {
                Box(modifier) { defaultTerminal() }
                return
            }
            Column(modifier) {
                BasicText(
                    text = stringResource(R.string.banner),
                    style = TextStyle(color = Color.Black),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFE0AF68)).padding(8.dp),
                )
                Box(Modifier.fillMaxWidth().weight(1f)) { defaultTerminal() }
            }
        }
    }
}

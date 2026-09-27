package com.jossephus.chuchu.ui.screens.Settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.plugin.DiscoveredPlugin
import com.jossephus.chuchu.plugin.ExternalPluginState
import com.jossephus.chuchu.plugin.ExternalPlugins
import com.jossephus.chuchu.plugin.PluginManager
import com.jossephus.chuchu.plugin.PluginRecord
import com.jossephus.chuchu.plugin.PluginSettingsEntry
import com.jossephus.chuchu.plugin.PluginSource
import com.jossephus.chuchu.plugin.PluginStatus
import com.jossephus.chuchu.plugin.api.ChoiceField
import com.jossephus.chuchu.plugin.api.SettingField
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.TextField
import com.jossephus.chuchu.plugin.api.ToggleField
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuSegmentedControl
import com.jossephus.chuchu.ui.components.ChuSwitch
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/**
 * Settings › plugins: built-ins, installed plugin APKs (with enable/consent), each plugin's
 * load status and its app-wide settings.
 */
@Composable
internal fun PluginsSettings() {
    val context = LocalContext.current
    val manager = remember(context) { PluginManager.getInstance(context.applicationContext as Application) }
    val external = remember(context) { ExternalPlugins.getInstance(context) }
    val records by manager.plugins.collectAsStateWithLifecycle()
    val settingsEntries by manager.settings.collectAsStateWithLifecycle()
    var discovered by remember { mutableStateOf<List<DiscoveredPlugin>>(emptyList()) }
    // Bumped after enable/disable so states are re-read from storage.
    var revision by remember { mutableIntStateOf(0) }
    var restartNeeded by remember { mutableStateOf(false) }
    var pendingConsent by remember { mutableStateOf<DiscoveredPlugin?>(null) }
    val colors = ChuColors.current
    val typography = ChuTypography.current

    // Rescan on resume: the user may have just installed or removed a plugin APK.
    LifecycleResumeEffect(external) {
        discovered = external.discover()
        onPauseOrDispose {}
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (restartNeeded) RestartBanner()
        ChuText("built-in", style = typography.label, color = colors.textMuted)
        records.filter { it.descriptor.source == PluginSource.Builtin }.forEach { record ->
            val schema = settingsEntries.firstOrNull { it.pluginId == record.descriptor.id }?.global
            PluginCard(record, schema, manager)
        }
        ChuText("installed", style = typography.label, color = colors.textMuted)
        if (discovered.isEmpty()) {
            ChuText("no plugin apps installed", style = typography.body, color = colors.textMuted)
        }
        discovered.forEach { plugin ->
            val state = remember(plugin, revision) { external.state(plugin) }
            val record = records.firstOrNull { it.descriptor.packageName == plugin.descriptor.packageName }
            val schema = settingsEntries.firstOrNull { it.pluginId == plugin.descriptor.id }?.global
            ExternalPluginCard(
                plugin = plugin,
                state = state,
                record = record,
                schema = schema,
                manager = manager,
                onEnabledChange = { enabled ->
                    if (enabled) {
                        pendingConsent = plugin
                    } else {
                        external.disable(plugin.descriptor.id)
                        revision++
                        restartNeeded = true
                    }
                },
            )
        }
    }

    pendingConsent?.let { plugin ->
        ConsentDialog(
            plugin = plugin,
            onConfirm = {
                external.enable(plugin)
                pendingConsent = null
                revision++
                restartNeeded = true
            },
            onDismiss = { pendingConsent = null },
        )
    }
}

@Composable
private fun ExternalPluginCard(
    plugin: DiscoveredPlugin,
    state: ExternalPluginState,
    record: PluginRecord?,
    schema: SettingsSchema?,
    manager: PluginManager,
    onEnabledChange: (Boolean) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ChuText(plugin.descriptor.name, style = typography.label)
                    ChuText(
                        "${plugin.descriptor.packageName} · ${plugin.descriptor.version}",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                ChuSwitch(
                    checked = state == ExternalPluginState.Enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = state != ExternalPluginState.Invalid,
                )
            }
            val (text, color) = externalStatus(plugin, state, record)
            ChuText(text, style = typography.bodySmall, color = color)
            if (schema != null && record?.status == PluginStatus.Active) {
                PluginSettingsForm(plugin.descriptor.id, schema, manager)
            }
        }
    }
}

@Composable
private fun externalStatus(
    plugin: DiscoveredPlugin,
    state: ExternalPluginState,
    record: PluginRecord?,
): Pair<String, Color> {
    val colors = ChuColors.current
    return when (state) {
        ExternalPluginState.Invalid ->
            "can't load: ${plugin.manifestProblem ?: "unreadable signing certificate"}" to colors.error
        ExternalPluginState.Quarantined ->
            "disabled: chuchu stopped shortly after loading it; enable to try again" to colors.warning
        ExternalPluginState.CertificateChanged ->
            "disabled: signed by a different certificate than you approved; enable to trust it" to colors.warning
        ExternalPluginState.Disabled -> "off" to colors.textMuted
        ExternalPluginState.Enabled ->
            when (record?.status) {
                PluginStatus.Active -> "active" to colors.success
                PluginStatus.Failed -> "disabled: ${record.error.orEmpty()}" to colors.error
                PluginStatus.Incompatible -> "incompatible: ${record.error.orEmpty()}" to colors.warning
                null -> "enabled; restart chuchu to load it" to colors.textSecondary
            }
    }
}

@Composable
private fun ConsentDialog(plugin: DiscoveredPlugin, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuDialog(
        title = "Enable ${plugin.descriptor.name}?",
        confirmLabel = "Enable",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChuText(
                "Plugins run inside chuchu with full access to it, including your sessions, " +
                    "hosts and keys. Only enable plugins you trust.",
                style = typography.body,
            )
            ChuText("package: ${plugin.descriptor.packageName}", style = typography.bodySmall, color = colors.textSecondary)
            ChuText(
                "signing certificate (SHA-256):\n${plugin.certSha256?.chunked(4)?.joinToString(" ").orEmpty()}",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
            ChuText(
                "If an update is signed with a different certificate, it stays off until you approve it again.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun RestartBanner() {
    val context = LocalContext.current
    val typography = ChuTypography.current
    val colors = ChuColors.current
    ChuCard(modifier = Modifier.fillMaxWidth(), border = colors.accent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChuText(
                "restart chuchu to apply plugin changes; open sessions will close",
                style = typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            ChuButton(onClick = { restartApp(context) }, variant = ChuButtonVariant.Outlined, bracketed = true) {
                ChuText("restart", style = typography.label, color = colors.accent)
            }
        }
    }
}

// Plugins can't be unloaded in place (their classes and composables stay referenced), so
// applying changes means a fresh process.
private fun restartApp(context: Context) {
    val intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun PluginCard(record: PluginRecord, schema: SettingsSchema?, manager: PluginManager) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val descriptor = record.descriptor
    ChuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuText(descriptor.name, style = typography.label)
                val source = if (descriptor.source == PluginSource.Builtin) "built-in" else descriptor.version
                ChuText(source, style = typography.bodySmall, color = colors.textMuted)
            }
            val (statusText, statusColor) =
                when (record.status) {
                    PluginStatus.Active -> "active" to colors.success
                    PluginStatus.Failed -> "disabled: ${record.error.orEmpty()}" to colors.error
                    PluginStatus.Incompatible -> "incompatible: ${record.error.orEmpty()}" to colors.warning
                }
            ChuText(statusText, style = typography.bodySmall, color = statusColor)
            if (schema != null && record.status == PluginStatus.Active) {
                PluginSettingsForm(descriptor.id, schema, manager)
            }
        }
    }
}

/** A plugin's app-wide settings, written straight to its store. */
@Composable
private fun PluginSettingsForm(pluginId: String, schema: SettingsSchema, manager: PluginManager) {
    val values = remember(pluginId) { manager.settingValues(pluginId) }
    // Values live in SharedPreferences, not Compose state; bumping this after each write
    // makes the fields re-read them.
    var revision by remember { mutableIntStateOf(0) }
    PluginSettingFields(
        schema = schema,
        valueOf = { field ->
            revision
            values.raw(field)
        },
        onChange = { field, raw ->
            values.set(field, raw)
            revision++
        },
    )
}

/** Host editor section: each plugin's host settings for the host being edited. */
@Composable
internal fun PluginHostSettings(
    entries: List<PluginSettingsEntry>,
    valueOf: (pluginId: String, field: SettingField) -> String,
    onChange: (pluginId: String, field: SettingField, raw: String) -> Unit,
) {
    val typography = ChuTypography.current
    entries.forEach { entry ->
        val schema = entry.host ?: return@forEach
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChuText(entry.pluginName, style = typography.label)
            PluginSettingFields(
                schema = schema,
                valueOf = { field -> valueOf(entry.pluginId, field) },
                onChange = { field, raw -> onChange(entry.pluginId, field, raw) },
            )
        }
    }
}

/** Renders a plugin's declarative schema; values are raw strings ("true"/"false" for toggles). */
@Composable
internal fun PluginSettingFields(
    schema: SettingsSchema,
    valueOf: (SettingField) -> String,
    onChange: (SettingField, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        schema.fields.forEach { field ->
            when (field) {
                is ToggleField ->
                    FieldRow(field) {
                        ChuSwitch(
                            checked = valueOf(field).toBooleanStrictOrNull() ?: field.default,
                            onCheckedChange = { onChange(field, it.toString()) },
                        )
                    }
                is TextField -> {
                    FieldLabel(field)
                    ChuTextField(
                        value = valueOf(field),
                        onValueChange = { onChange(field, it) },
                        label = field.title,
                        placeholder = field.placeholder,
                        showLabel = false,
                        singleLine = true,
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is ChoiceField -> {
                    FieldLabel(field)
                    ChuSegmentedControl(
                        options = field.options.map { it.value },
                        labels = field.options.associate { it.value to it.label },
                        selected = valueOf(field),
                        onSelect = { onChange(field, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldRow(field: SettingField, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) { FieldLabel(field) }
        control()
    }
}

@Composable
private fun FieldLabel(field: SettingField) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChuText(field.title, style = typography.body)
        field.description?.let { ChuText(it, style = typography.bodySmall, color = colors.textMuted) }
    }
}

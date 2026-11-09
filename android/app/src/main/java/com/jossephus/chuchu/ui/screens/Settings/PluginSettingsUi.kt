package com.jossephus.chuchu.ui.screens.Settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.R
import com.jossephus.chuchu.plugin.PluginManager
import com.jossephus.chuchu.plugin.PluginRecord
import com.jossephus.chuchu.plugin.PluginSettingsEntry
import com.jossephus.chuchu.plugin.PluginStatus
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.ToggleField
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuSwitch
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
internal fun PluginsSettings() {
    val context = LocalContext.current
    val manager = remember(context) { PluginManager.getInstance(context.applicationContext as Application) }
    val records by manager.plugins.collectAsStateWithLifecycle()
    val settingsEntries by manager.settings.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        records.forEach { record ->
            val schema = settingsEntries.firstOrNull { it.pluginId == record.descriptor.id }?.global
            PluginCard(record, schema, manager)
        }
    }
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
                ChuText(stringResource(R.string.plugins_builtin), style = typography.bodySmall, color = colors.textMuted)
            }
            val (statusText, statusColor) =
                when (record.status) {
                    PluginStatus.Active -> stringResource(R.string.plugins_status_active) to colors.success
                    PluginStatus.Failed -> stringResource(R.string.plugins_status_failed, record.error.orEmpty()) to colors.error
                }
            ChuText(statusText, style = typography.bodySmall, color = statusColor)
            if (schema != null && record.status == PluginStatus.Active) {
                PluginSettingsForm(descriptor.id, schema, manager)
            }
        }
    }
}

@Composable
private fun PluginSettingsForm(pluginId: String, schema: SettingsSchema, manager: PluginManager) {
    val values = remember(pluginId) { manager.settingValues(pluginId) }
    // Values live in SharedPreferences, not Compose state; bumping this re-reads them.
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

@Composable
internal fun PluginHostSettings(
    entries: List<PluginSettingsEntry>,
    valueOf: (pluginId: String, field: ToggleField) -> String,
    onChange: (pluginId: String, field: ToggleField, raw: String) -> Unit,
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

@Composable
internal fun PluginSettingFields(
    schema: SettingsSchema,
    valueOf: (ToggleField) -> String,
    onChange: (ToggleField, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        schema.fields.forEach { field ->
            FieldRow(field) {
                ChuSwitch(
                    checked = valueOf(field).toBooleanStrictOrNull() ?: field.default,
                    onCheckedChange = { onChange(field, it.toString()) },
                )
            }
        }
    }
}

@Composable
private fun FieldRow(field: ToggleField, control: @Composable () -> Unit) {
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
private fun FieldLabel(field: ToggleField) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChuText(field.title, style = typography.body)
        field.description?.let { ChuText(it, style = typography.bodySmall, color = colors.textMuted) }
    }
}

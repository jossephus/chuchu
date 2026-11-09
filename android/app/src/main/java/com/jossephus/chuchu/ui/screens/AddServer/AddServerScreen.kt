package com.jossephus.chuchu.ui.screens.AddServer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry
import com.jossephus.chuchu.ui.screens.Settings.PluginHostSettings
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.api.TransportProvider
import com.jossephus.chuchu.service.transport.TransportRegistry
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuSegmentedControl
import com.jossephus.chuchu.ui.components.ChuSwitch
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun AddServerScreen(vm: AddServerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val form by vm.form.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val pluginHostSettings by vm.pluginHostSettings.collectAsStateWithLifecycle()
    val pluginHostEdits by vm.pluginHostEdits.collectAsStateWithLifecycle()
    val colors = ChuColors.current
    val typography = ChuTypography.current

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var showAdditionalSettings by remember { mutableStateOf(false) }
    var showPairingScanner by remember { mutableStateOf(false) }
    val transportProviders by TransportRegistry.providers.collectAsStateWithLifecycle()
    val pluginTransport = form.transportProvider
    val isPluginTransport = form.transport == Transport.Plugin

    LaunchedEffect(vm) {
        vm.errorMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val pairing = pluginTransport?.hostForm?.pairing
    if (showPairingScanner && pairing != null) {
        QrCodeScanner(
            title = pairing.title,
            hint = pairing.hint,
            onResult = { code ->
                showPairingScanner = false
                if (!vm.applyPairingCode(code)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.add_server_pairing_invalid, pluginTransport?.displayName.orEmpty()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onClose = { showPairingScanner = false },
        )
        return
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(scrollState)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChuText("$ ", style = typography.headline, color = colors.textMuted)
            ChuText(
                if (form.id != null) {
                    stringResource(R.string.add_server_title_edit)
                } else {
                    stringResource(R.string.add_server_title_new)
                },
                style = typography.headline,
            )
        }

        SectionHeader(stringResource(R.string.section_connection))
        ChuTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = stringResource(R.string.field_name),
            placeholder = stringResource(R.string.add_server_name_placeholder),
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ChuTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = pluginTransport?.hostForm?.addressLabel ?: stringResource(R.string.field_host),
            placeholder = pluginTransport?.hostForm?.addressPlaceholder ?: "192.168.1.10",
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!isPluginTransport) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChuTextField(
                    value = form.port,
                    onValueChange = vm::updatePort,
                    label = stringResource(R.string.field_port),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    autoFocus = false,
                    modifier = Modifier.weight(0.3f),
                )
                ChuTextField(
                    value = form.username,
                    onValueChange = vm::updateUsername,
                    label = stringResource(R.string.field_username),
                    placeholder = "root",
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    autoFocus = false,
                    modifier = Modifier.weight(0.7f),
                )
            }
        }

        SectionDivider()

        SectionHeader(stringResource(R.string.section_transport))
        val transportChoices =
            listOf(
                TransportChoice(Transport.SSH, null, "ssh"),
                TransportChoice(Transport.TailscaleSSH, null, "tailscale"),
                TransportChoice(Transport.Mosh, null, "mosh"),
            ) + transportProviders.map { TransportChoice(Transport.Plugin, it.id, it.displayName.lowercase()) }
        val selectedChoice =
            transportChoices.firstOrNull { it.transport == form.transport && it.transportId == form.transportId }
                ?: TransportChoice(form.transport, form.transportId, form.transportId ?: form.transport.name)
        ChuSegmentedControl(
            options = transportChoices,
            labels = transportChoices.associateWith { it.label },
            selected = selectedChoice,
            onSelect = { choice ->
                if (choice.transportId != null) vm.selectPluginTransport(choice.transportId) else vm.updateTransport(choice.transport)
            },
        )
        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                stringResource(R.string.add_server_tailscale_hint),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }

        if (isPluginTransport) {
            PluginTransportSection(
                provider = pluginTransport,
                transportId = form.transportId,
                address = form.host,
                onScan = { showPairingScanner = true },
            )
        }

        SectionDivider()

        if (!isPluginTransport) {
            SectionHeader(stringResource(R.string.section_authentication))
            val authOptions =
                when (form.transport) {
                    Transport.TailscaleSSH ->
                        listOf(AuthMethod.Password, AuthMethod.Key, AuthMethod.None)
                    else -> listOf(AuthMethod.Password, AuthMethod.Key)
                }
            if (form.authMethod == AuthMethod.None && form.transport != Transport.TailscaleSSH) {
                androidx.compose.runtime.SideEffect { vm.updateAuthMethod(AuthMethod.Password) }
            }
            val segmentSelected =
                if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod
            ChuSegmentedControl(
                options = authOptions,
                labels =
                    mapOf(
                        AuthMethod.Password to stringResource(R.string.auth_password),
                        AuthMethod.Key to stringResource(R.string.auth_ssh_key),
                        AuthMethod.None to stringResource(R.string.auth_none),
                    ),
                selected = segmentSelected,
                onSelect = vm::updateAuthMethod,
            )

            when (form.authMethod) {
                AuthMethod.Password -> {
                    ChuTextField(
                        value = form.password,
                        onValueChange = vm::updatePassword,
                        label = stringResource(R.string.field_password),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthMethod.Key,
                AuthMethod.KeyWithPassphrase -> {
                    KeyAuthSection(
                        form = form,
                        keys = keys,
                        onGenerate = { vm.generateKey(form.name) },
                        onSelectKey = { vm.selectStoredKey(it) },
                        onCopyPublicKey = {
                            if (form.publicKeyOpenSsh.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.add_server_no_public_key), Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(context.getString(R.string.add_server_clipboard_label), form.publicKeyOpenSsh)
                                )
                                Toast.makeText(context, context.getString(R.string.add_server_public_key_copied), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        ChuSwitch(
                            checked = form.authMethod == AuthMethod.KeyWithPassphrase,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    vm.updateAuthMethod(AuthMethod.KeyWithPassphrase)
                                } else {
                                    vm.updateAuthMethod(AuthMethod.Key)
                                    vm.updateKeyPassphrase("")
                                }
                            },
                        )
                        ChuText(stringResource(R.string.add_server_set_passphrase), style = typography.label)
                    }
                    if (form.authMethod == AuthMethod.KeyWithPassphrase) {
                        ChuTextField(
                            value = form.keyPassphrase,
                            onValueChange = vm::updateKeyPassphrase,
                            label = stringResource(R.string.field_passphrase),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            autoFocus = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                AuthMethod.None -> {
                    ChuText(
                        stringResource(R.string.add_server_ssh_key_only_hint),
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            SectionDivider()
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ChuButton(
                onClick = { showAdditionalSettings = !showAdditionalSettings },
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
            ) {
                ChuText(stringResource(R.string.add_server_additional_settings), style = typography.label)
            }
        }
        if (showAdditionalSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                ChuText(stringResource(R.string.add_server_require_biometric), style = typography.label)
                ChuSwitch(
                    checked = form.requireAuthOnConnect,
                    onCheckedChange = vm::updateRequireAuthOnConnect,
                )
            }
            SectionDivider()
            if (!isPluginTransport) {
                MultiplexerSection(selected = form.multiplexer, onSelect = vm::updateMultiplexer)
                SectionDivider()
            }
            if (pluginHostSettings.isNotEmpty()) {
                SectionHeader(stringResource(R.string.section_plugins))
                PluginHostSettings(
                    entries = pluginHostSettings,
                    valueOf = { pluginId, field ->
                        pluginHostEdits[pluginId]?.get(field) ?: vm.pluginHostValue(pluginId, field)
                    },
                    onChange = vm::updatePluginHostValue,
                )
                SectionDivider()
            }
            if (!isPluginTransport) {
                PostConnectActionSection(
                    command = form.postConnectCommand,
                    onCommandChange = vm::updatePostConnectCommand,
                )
                SectionDivider()
            }
        }

        val canTest =
            if (isPluginTransport) {
                pluginTransport?.normalizeAddress(form.host) != null
            } else {
                form.host.isNotBlank() && form.username.isNotBlank()
            }
        ChuButton(
            onClick = vm::testConnection,
            enabled = canTest,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label =
                when (testState.status) {
                    ConnectionTestStatus.Idle -> stringResource(R.string.add_server_test_idle)
                    ConnectionTestStatus.Running -> stringResource(R.string.add_server_test_running)
                    ConnectionTestStatus.Success -> stringResource(R.string.add_server_test_success)
                    ConnectionTestStatus.Error -> stringResource(R.string.add_server_test_error)
                }
            ChuText(label, style = typography.label)
        }
        if (testState.status == ConnectionTestStatus.Error && testState.message != null) {
            ChuText(testState.message ?: "", style = typography.bodySmall, color = colors.error)
        }
        if (isPluginTransport && testState.status == ConnectionTestStatus.Success && testState.message != null) {
            ChuText(testState.message ?: "", style = typography.bodySmall, color = colors.textMuted)
        }
        ChuButton(
            onClick = { vm.save(onBack) },
            enabled = form.canSave(),
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText(stringResource(R.string.action_save), style = typography.label, color = colors.accent)
        }
    }
}

private data class TransportChoice(
    val transport: Transport,
    val transportId: String?,
    val label: String,
)

@Composable
private fun PluginTransportSection(
    provider: TransportProvider?,
    transportId: String?,
    address: String,
    onScan: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    if (provider == null) {
        ChuText(
            stringResource(R.string.add_server_transport_unavailable, transportId.orEmpty()),
            style = typography.bodySmall,
            color = colors.error,
        )
        return
    }
    provider.hostForm.description?.let { description ->
        ChuText(description, style = typography.bodySmall, color = colors.textMuted)
    }
    if (provider.hostForm.pairing != null) {
        ChuButton(
            onClick = onScan,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText(
                if (address.isBlank()) stringResource(R.string.add_server_scan_pairing) else stringResource(R.string.add_server_scan_again),
                style = typography.label,
                color = colors.accent,
            )
        }
    }
    if (address.isNotBlank()) {
        val normalized = provider.normalizeAddress(address)
        ChuText(
            normalized?.let { stringResource(R.string.add_server_connects_to, it) }
                ?: stringResource(R.string.add_server_invalid_address, provider.displayName),
            style = typography.bodySmall,
            color = if (normalized == null) colors.error else colors.textMuted,
        )
    }
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChuColors.current.border))
}

@Composable
private fun SectionHeader(label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ChuText("── ", style = typography.labelSmall, color = colors.textMuted)
        ChuText(label, style = typography.labelSmall, color = colors.textMuted)
        ChuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<com.jossephus.chuchu.model.SshKey>,
    onGenerate: () -> Unit,
    onSelectKey: (Long?) -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = ChuTypography.current
    val colors = ChuColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }
    var pickerExpanded by remember { mutableStateOf(false) }

    if (selectedKey != null) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChuText("● ", style = typography.body, color = colors.accent)
            ChuText(selectedKey.name, style = typography.body, color = colors.textPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuButton(
                onClick = onCopyPublicKey,
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
                borderColor = colors.accent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                ChuText(stringResource(R.string.add_server_copy_public_key), style = typography.labelSmall, color = colors.accent)
            }
            ChuButton(
                onClick = onGenerate,
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                ChuText(stringResource(R.string.add_server_new_key), style = typography.label)
            }
        }
    } else {
        ChuText(
            stringResource(R.string.add_server_generate_key_hint),
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        ChuButton(
            onClick = onGenerate,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText(stringResource(R.string.add_server_generate_key), style = typography.label)
        }
    }

    if (keys.isNotEmpty()) {
        ChuButton(
            onClick = { pickerExpanded = !pickerExpanded },
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            ChuText(
                text =
                    if (selectedKey != null) {
                        stringResource(R.string.add_server_change_key)
                    } else {
                        stringResource(R.string.add_server_use_existing_key)
                    },
                style = typography.labelSmall,
                color = colors.accent,
            )
        }
        if (pickerExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { key ->
                    val isSelected = key.id == form.keyId
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onSelectKey(key.id)
                                    pickerExpanded = false
                                }
                                .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        ChuText(
                            text = (if (isSelected) "● " else "  ") + key.name,
                            style = typography.body,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                        ChuText(
                            key.algorithm,
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

// "" stands in for no multiplexer: the segmented control needs non-null options.
private const val MULTIPLEXER_OFF = ""

@Composable
private fun MultiplexerSection(selected: MultiplexerType?, onSelect: (MultiplexerType?) -> Unit) {
    SectionHeader(stringResource(R.string.section_session_persistence))
    val providers by MultiplexerRegistry.providers.collectAsStateWithLifecycle()
    // A saved id whose plugin isn't loaded stays visible, disabled, instead of reading as off.
    val missing = selected?.id?.takeIf { id -> providers.none { it.id == id } }
    val options = listOf(MULTIPLEXER_OFF) + providers.map { it.id } + listOfNotNull(missing)
    val labels =
        mapOf(MULTIPLEXER_OFF to stringResource(R.string.label_off)) +
            providers.associate { it.id to it.displayName } +
            listOfNotNull(missing).associateWith { stringResource(R.string.add_server_multiplexer_missing, it) }
    ChuSegmentedControl(
        options = options,
        labels = labels,
        selected = selected?.id ?: MULTIPLEXER_OFF,
        onSelect = { id -> onSelect(id.takeIf { it != MULTIPLEXER_OFF }?.let(::MultiplexerType)) },
        disabledOptions = setOfNotNull(missing),
    )
}

@Composable
private fun PostConnectActionSection(command: String, onCommandChange: (String) -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuText(stringResource(R.string.add_server_autorun), style = typography.label)
    ChuTextField(
        value = command,
        onValueChange = onCommandChange,
        label = "",
        placeholder = stringResource(R.string.add_server_autorun_placeholder),
        showLabel = false,
        singleLine = true,
        autoFocus = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ChuButton(
            onClick = { onCommandChange("") },
            enabled = command.isNotBlank(),
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            ChuText(stringResource(R.string.action_clear), style = typography.labelSmall, color = colors.textMuted)
        }
    }
}

package com.example.chuchu.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chuchu.model.AuthMethod
import com.example.chuchu.model.Transport
import com.example.chuchu.ui.components.ChuButton
import com.example.chuchu.ui.components.ChuButtonVariant
import com.example.chuchu.ui.components.ChuSegmentedControl
import com.example.chuchu.ui.components.ChuText
import com.example.chuchu.ui.components.ChuTextField
import com.example.chuchu.ui.theme.ChuColors
import com.example.chuchu.ui.theme.ChuTypography

@Composable
fun AddServerScreen(
    vm: AddServerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val colors = ChuColors.current
    val typography = ChuTypography.current

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChuText("Add Server", style = typography.headline)
        ChuText("Connection details", style = typography.body, color = colors.textSecondary)

        ChuTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = "Name",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ChuTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = "Host",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChuTextField(
                value = form.port,
                onValueChange = vm::updatePort,
                label = "Port",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.4f),
            )
            ChuTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = "Username",
                singleLine = true,
                modifier = Modifier.weight(0.6f),
            )
        }

        SectionHeader("Transport")
        ChuSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH),
            labels = mapOf(
                Transport.SSH to "SSH",
                Transport.TailscaleSSH to "Tailscale",
            ),
            selected = form.transport,
            onSelect = vm::updateTransport,
        )

        SectionHeader("Auth method")
        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                "Uses server-side Tailscale SSH policy. No password or SSH key is required.",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        } else {
            val authOptions = listOf(
                AuthMethod.Password,
                AuthMethod.Key,
                AuthMethod.KeyWithPassphrase,
            )
            ChuSegmentedControl(
                options = authOptions,
                labels = mapOf(
                    AuthMethod.Password to "Password",
                    AuthMethod.Key to "SSH Key",
                    AuthMethod.KeyWithPassphrase to "Key + Pass",
                ),
                selected = form.authMethod,
                onSelect = vm::updateAuthMethod,
            )

            when (form.authMethod) {
                AuthMethod.Password -> {
                    ChuTextField(
                        value = form.password,
                        onValueChange = vm::updatePassword,
                        label = "Password",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthMethod.Key -> {
                    ChuTextField(
                        value = form.keyPath,
                        onValueChange = vm::updateKeyPath,
                        label = "Private key path",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChuText("Key import support comes in Phase 5", style = typography.bodySmall, color = colors.textSecondary)
                }
                AuthMethod.KeyWithPassphrase -> {
                    ChuTextField(
                        value = form.keyPath,
                        onValueChange = vm::updateKeyPath,
                        label = "Private key path",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChuTextField(
                        value = form.keyPassphrase,
                        onValueChange = vm::updateKeyPassphrase,
                        label = "Key passphrase",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChuText("Key import support comes in Phase 5", style = typography.bodySmall, color = colors.textSecondary)
                }
                AuthMethod.None -> Unit
            }
        }

        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                "Tailscale SSH requires the Tailscale VPN to be active.",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val canTest = form.host.isNotBlank() &&
            (form.transport == Transport.TailscaleSSH || form.username.isNotBlank())
        ChuButton(
            onClick = vm::testConnection,
            enabled = canTest,
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = when (testState.status) {
                ConnectionTestStatus.Running -> "Testing..."
                else -> "Test Connection"
            }
            ChuText(label, style = typography.label)
        }
        if (testState.message != null) {
            ChuText(
                testState.message ?: "",
                style = typography.bodySmall,
                color = if (testState.status == ConnectionTestStatus.Error) colors.error else colors.textSecondary,
            )
        }

        val canSave = form.name.isNotBlank() && form.host.isNotBlank() &&
            (form.transport == Transport.TailscaleSSH || form.username.isNotBlank())
        ChuButton(
            onClick = { vm.save(onBack) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText("Save", style = typography.label, color = colors.onAccent)
        }

        ChuButton(
            onClick = onBack,
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText("Cancel", style = typography.label)
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    ChuText(label, style = ChuTypography.current.label, color = ChuColors.current.textSecondary)
}

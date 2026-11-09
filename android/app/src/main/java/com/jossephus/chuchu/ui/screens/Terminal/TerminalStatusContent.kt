package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
internal fun TerminalEmptyTabsMessage(onNewConnection: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ChuText(
            stringResource(R.string.tabs_empty),
            style = typography.body,
            color = colors.textMuted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChuButton(
            onClick = onNewConnection,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
        ) {
            ChuText(
                stringResource(R.string.tabs_new_connection_row),
                style = typography.label,
                color = colors.accent,
            )
        }
    }
}

@Composable
internal fun TerminalDisconnectedContent(
    isStrip: Boolean,
    tabStrip: @Composable () -> Unit,
    errorMessage: String?,
    isMultiplexerPreflight: Boolean,
    dismissMultiplexerLabel: String,
    showEmptyTabs: Boolean,
    onNewConnection: () -> Unit,
    onRetryMultiplexer: () -> Unit,
    onConnectWithoutMultiplexer: () -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isStrip) {
        Column(modifier = modifier.fillMaxSize()) {
            tabStrip()
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (errorMessage != null) {
                    TerminalRecoveryActions(
                        message = errorMessage,
                        isMultiplexerPreflight = isMultiplexerPreflight,
                        dismissMultiplexerLabel = dismissMultiplexerLabel,
                        onRetryMultiplexer = onRetryMultiplexer,
                        onConnectWithoutMultiplexer = onConnectWithoutMultiplexer,
                        onBack = onBack,
                        onReconnect = onReconnect,
                    )
                } else if (showEmptyTabs) {
                    TerminalEmptyTabsMessage(onNewConnection)
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (errorMessage != null) {
                TerminalRecoveryActions(
                    message = errorMessage,
                    isMultiplexerPreflight = isMultiplexerPreflight,
                    dismissMultiplexerLabel = dismissMultiplexerLabel,
                    onRetryMultiplexer = onRetryMultiplexer,
                    onConnectWithoutMultiplexer = onConnectWithoutMultiplexer,
                    onBack = onBack,
                    onReconnect = onReconnect,
                )
            }
        }
    }
}

@Composable
internal fun TerminalConnectingContent(
    isStrip: Boolean,
    tabStrip: @Composable () -> Unit,
    hostLabel: String,
    modifier: Modifier = Modifier,
) {
    val typography = ChuTypography.current
    if (isStrip) {
        Column(modifier = modifier.fillMaxSize()) {
            tabStrip()
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ChuText(
                    stringResource(R.string.terminal_connecting_to, hostLabel),
                    style = typography.body,
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            ChuText(
                stringResource(R.string.terminal_connecting_to, hostLabel),
                style = typography.body,
            )
        }
    }
}

@Composable
internal fun TerminalPreparingContent(
    isStrip: Boolean,
    tabStrip: @Composable () -> Unit,
    isReconnecting: Boolean,
    hostForMessage: String,
    modifier: Modifier = Modifier,
) {
    val typography = ChuTypography.current
    if (isStrip) {
        Column(modifier = modifier.fillMaxSize()) {
            tabStrip()
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val message =
                    if (isReconnecting) {
                        stringResource(R.string.terminal_reconnecting_to, hostForMessage)
                    } else {
                        stringResource(R.string.terminal_preparing)
                    }
                ChuText(message, style = typography.body)
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val message =
                if (isReconnecting) {
                    stringResource(R.string.terminal_reconnecting_to, hostForMessage)
                } else {
                    stringResource(R.string.terminal_preparing)
                }
            ChuText(message, style = typography.body)
        }
    }
}

@Composable
private fun TerminalRecoveryActions(
    message: String,
    isMultiplexerPreflight: Boolean,
    dismissMultiplexerLabel: String? = null,
    onRetryMultiplexer: () -> Unit,
    onConnectWithoutMultiplexer: () -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ChuText(message, color = colors.error, style = typography.body)
        Spacer(modifier = Modifier.height(16.dp))
        if (isMultiplexerPreflight) {
            ChuButton(
                onClick = onRetryMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Filled,
            ) {
                ChuText(
                    stringResource(R.string.terminal_retry_multiplexer),
                    style = typography.label,
                    color = colors.onAccent,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            ChuButton(
                onClick = onConnectWithoutMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
            ) {
                ChuText(
                    stringResource(R.string.terminal_connect_without_mux),
                    style = typography.label,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            ChuButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Ghost,
                bracketed = true,
                borderColor = colors.textMuted,
            ) {
                ChuText(
                    dismissMultiplexerLabel ?: stringResource(R.string.action_back),
                    style = typography.label,
                    color = colors.textMuted,
                )
            }
        } else {
            ChuButton(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                variant = ChuButtonVariant.Filled,
            ) {
                ChuText(
                    stringResource(R.string.action_retry),
                    style = typography.label,
                    color = colors.onAccent,
                )
            }
        }
    }
}

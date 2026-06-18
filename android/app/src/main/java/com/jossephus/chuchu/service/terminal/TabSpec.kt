package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport

data class TabSpec(
    val hostId: Long? = null,
    val displayName: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.Password,
    val publicKeyOpenSsh: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val postConnectCommand: String? = null,
    val multiplexer: MultiplexerType? = null,
    val multiplexerSessionName: String? = null,
    val multiplexerCreateIfMissing: Boolean = true,
) {
    val sessionKey: String
        get() =
            when (transport) {
                Transport.LocalShell -> "local-shell"
                else -> hostId?.let { "host:$it" } ?: "${transport.name}:$username@$host:$port"
            }

    val notificationLabel: String
        get() {
            if (transport == Transport.LocalShell) {
                return displayName.takeIf { it.isNotBlank() } ?: "local shell"
            }
            val target = "$username@$host:$port"
            return if (displayName.isNotBlank()) "$displayName  ·  $target" else target
        }

    val tabLabel: String
        get() =
            when (transport) {
                Transport.LocalShell -> displayName.takeIf { it.isNotBlank() } ?: "local shell"
                else -> displayName.takeIf { it.isNotBlank() } ?: "$username@$host"
            }

    val usesRuntimeMultiplexer: Boolean
        get() =
            multiplexer?.runtimeSupported == true &&
                transport != Transport.Mosh &&
                transport != Transport.LocalShell

    companion object {
        fun fromHostProfile(
            host: HostProfile,
            publicKeyOpenSsh: String = "",
            privateKeyPem: String = "",
            keyPassphrase: String = "",
        ): TabSpec = TabSpec(
            hostId = host.id,
            displayName = host.name,
            host = host.host,
            port = host.port,
            username = host.username,
            password = host.password,
            authMethod = host.authMethod,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyPem = privateKeyPem,
            keyPassphrase = keyPassphrase,
            transport = host.transport,
            postConnectCommand = host.postConnectCommand,
            multiplexer = host.multiplexer?.takeIf {
                it.runtimeSupported &&
                    host.transport != Transport.Mosh &&
                    host.transport != Transport.LocalShell
            },
        )
    }
}

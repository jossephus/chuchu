package com.jossephus.chuchu.service.ssh

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey

data class SshJumpHost(
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: AuthMethod,
    val password: String = "",
    val publicKeyOpenSsh: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
) {
    companion object {
        fun fromHostProfile(profile: HostProfile, key: SshKey?): SshJumpHost = SshJumpHost(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = profile.authMethod,
            password = if (profile.authMethod == AuthMethod.Password) profile.password else "",
            publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
            privateKeyPem = key?.privateKeyPem.orEmpty(),
            keyPassphrase = profile.keyPassphrase,
        )
    }
}

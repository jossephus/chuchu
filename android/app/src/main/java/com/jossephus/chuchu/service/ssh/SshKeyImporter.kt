package com.jossephus.chuchu.service.ssh

data class DerivedPublicKey(val algorithm: String, val publicKeyOpenSsh: String)

class SshKeyImporter(private val bridge: NativeSshBridge = NativeSshBridge()) {
    fun derivePublicKey(privateKeyPem: String): DerivedPublicKey? {
        val result = bridge.nativeDerivePublicKey(privateKeyPem) ?: return null
        val algorithm = result.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val publicKey = result.getOrNull(1)?.trimEnd()?.takeIf { it.isNotBlank() } ?: return null
        return DerivedPublicKey(algorithm = algorithm, publicKeyOpenSsh = publicKey)
    }
}

package com.jossephus.chuchu.service.ssh

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.model.canServeAsJumpHostFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshJumpHostTest {
    @Test
    fun keyAuthJumpHostCarriesKeyAndDropsSavedPassword() {
        val jump = fromProfile(profile(authMethod = AuthMethod.Key, keyId = 7L), key())

        assertEquals("bastion.example.com", jump.host)
        assertEquals(2200, jump.port)
        assertEquals("ops", jump.username)
        assertEquals("", jump.password)
        assertEquals("PEM", jump.privateKeyPem)
        assertEquals("ssh-ed25519 AAAA", jump.publicKeyOpenSsh)
    }

    @Test
    fun passwordAuthJumpHostCarriesPassword() {
        val jump = fromProfile(profile(authMethod = AuthMethod.Password), null)

        assertEquals("secret", jump.password)
        assertEquals("", jump.privateKeyPem)
    }

    @Test
    fun jumpHostEligibilityExcludesSelfLocalShellAndChainedHosts() {
        assertTrue(profile().canServeAsJumpHostFor(hostId = 9L))
        assertTrue(profile().canServeAsJumpHostFor(hostId = null))
        assertFalse(profile().canServeAsJumpHostFor(hostId = 1L))
        assertFalse(profile(transport = Transport.LocalShell).canServeAsJumpHostFor(hostId = 9L))
        assertFalse(profile().copy(jumpHostId = 5L).canServeAsJumpHostFor(hostId = 9L))
    }

    private fun fromProfile(profile: HostProfile, key: SshKey?): SshJumpHost =
        SshJumpHost.fromHostProfile(profile, key)

    private fun profile(
        authMethod: AuthMethod = AuthMethod.Password,
        keyId: Long? = null,
        transport: Transport = Transport.SSH,
    ): HostProfile = HostProfile(
        id = 1L,
        name = "bastion",
        host = "bastion.example.com",
        port = 2200,
        username = "ops",
        password = "secret",
        keyId = keyId,
        transport = transport,
        authMethod = authMethod,
    )

    private fun key(): SshKey = SshKey(
        id = 7L,
        name = "ops",
        algorithm = "ED25519",
        privateKeyPem = "PEM",
        publicKeyOpenSsh = "ssh-ed25519 AAAA",
        createdAtEpochMs = 0L,
    )
}

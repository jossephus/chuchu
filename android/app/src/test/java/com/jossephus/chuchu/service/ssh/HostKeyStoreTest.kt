package com.jossephus.chuchu.service.ssh

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [HostKeyStore].
 *
 * Unlike the pure-JVM suites, these exercise the real Android framework pieces the store
 * depends on: [android.util.Base64] and real [android.content.SharedPreferences] persistence,
 * so keys survive across store instances exactly like they do on device.
 *
 * Robolectric runs on the JVM with no device or emulator required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostKeyStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>().applicationContext
    }

    private fun newStore(): HostKeyStore =
        HostKeyStore(context.getSharedPreferences(HostKeyStore.PREFS_NAME, Context.MODE_PRIVATE))

    private val keyBytes = "ed25519-host-key-bytes".toByteArray()

    @Test
    fun `unknown host before anything is saved`() {
        val check = newStore().check("example.com", 22, "ssh-ed25519", keyBytes)
        assertTrue(check is HostKeyCheck.Unknown)
        assertEquals(
            "SHA256:",
            (check as HostKeyCheck.Unknown).fingerprint.take("SHA256:".length),
        )
    }

    @Test
    fun `saved key round-trips through Base64 and prefs and matches`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", keyBytes)

        val check = newStore().check("example.com", 22, "ssh-ed25519", keyBytes)
        assertEquals(HostKeyCheck.Match, check)
    }

    @Test
    fun `changed key reports both fingerprints`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", keyBytes)
        val newKey = "rotated-host-key-bytes".toByteArray()

        val check = newStore().check("example.com", 22, "ssh-ed25519", newKey)
        assertTrue(check is HostKeyCheck.Changed)
        val changed = check as HostKeyCheck.Changed
        assertNotEquals(changed.previousFingerprint, changed.fingerprint)
        assertFalse(changed.fingerprint.contains("\n"))
    }

    @Test
    fun `keys are stored per host, port and algorithm`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", keyBytes)

        assertEquals(HostKeyCheck.Match, store.check("example.com", 22, "ssh-ed25519", keyBytes))
        assertTrue(store.check("other.com", 22, "ssh-ed25519", keyBytes) is HostKeyCheck.Unknown)
        assertTrue(store.check("example.com", 2222, "ssh-ed25519", keyBytes) is HostKeyCheck.Unknown)
        assertTrue(store.check("example.com", 22, "ssh-rsa", keyBytes) is HostKeyCheck.Unknown)
    }

    @Test
    fun `binary key bytes survive the Base64 round-trip`() {
        val binary = ByteArray(64) { it.toByte() }
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", binary)

        assertTrue(binary.contentEquals(newStore().loadKey("example.com", 22, "ssh-ed25519")!!))
    }
}

package com.jossephus.chuchu.plugins.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RexEndpointTest {
    @Test
    fun tailnetUrlUsesTlsOnDefaultPort() {
        val endpoint = RexEndpoint.parse("https://rex.tail1234.ts.net")
        assertEquals(RexEndpoint("rex.tail1234.ts.net", 443, secure = true), endpoint)
        assertEquals("https://rex.tail1234.ts.net", endpoint?.url)
        assertEquals("wss://rex.tail1234.ts.net/api/control", endpoint?.webSocketUrl("/api/control"))
    }

    @Test
    fun bareHostDefaultsToTls() {
        assertEquals(RexEndpoint("build.example.ts.net", 443, secure = true), RexEndpoint.parse("build.example.ts.net"))
    }

    @Test
    fun bareHostWithRexPortIsPlaintext() {
        val endpoint = RexEndpoint.parse("192.168.1.20:7297")
        assertEquals(RexEndpoint("192.168.1.20", 7297, secure = false), endpoint)
        assertEquals("ws://192.168.1.20:7297/api/data", endpoint?.webSocketUrl("/api/data"))
    }

    @Test
    fun plainHttpWithoutPortUsesRexPort() {
        assertEquals(RexEndpoint("10.0.0.5", 7297, secure = false), RexEndpoint.parse("http://10.0.0.5"))
    }

    @Test
    fun explicitNonDefaultPortStaysInUrl() {
        assertEquals("http://box:8080", RexEndpoint.parse("http://box:8080")?.url)
        assertEquals("https://box:7297", RexEndpoint.parse("https://box:7297/")?.url)
    }

    @Test
    fun rejectsAddressesRexCannotServe() {
        assertNull(RexEndpoint.parse(""))
        assertNull(RexEndpoint.parse("ftp://box"))
        assertNull(RexEndpoint.parse("https://root@box.ts.net"))
        assertNull(RexEndpoint.parse("https://box.ts.net/api"))
        assertNull(RexEndpoint.parse("box with spaces"))
        assertNull(RexEndpoint.parse("https://box:99999"))
    }

    @Test
    fun pairingCodeAcceptsUrlAndJson() {
        val expected = RexEndpoint("jossephus-rex.taildbf71e.ts.net", 443, secure = true)
        assertEquals(expected, RexEndpoint.fromPairingCode("https://jossephus-rex.taildbf71e.ts.net"))
        assertEquals(expected, RexEndpoint.fromPairingCode("""{"url": "https://jossephus-rex.taildbf71e.ts.net"}"""))
    }

    @Test
    fun pairingCodeRejectsUnrelatedQrCodes() {
        assertNull(RexEndpoint.fromPairingCode("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(RexEndpoint.fromPairingCode("just some text"))
        assertNull(RexEndpoint.fromPairingCode("""{"name": "x"}"""))
    }
}

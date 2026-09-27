package com.jossephus.chuchu.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalPluginStateTest {
    private fun plugin(cert: String? = "aa11", problem: String? = null) =
        DiscoveredPlugin(
            descriptor = PluginDescriptor("hello", "Hello", "0.1.0", 1, PluginSource.External, "dev.example.hello"),
            entryClass = "dev.example.hello.HelloPlugin",
            certSha256 = cert,
            manifestProblem = problem,
        )

    @Test
    fun notApprovedIsDisabled() {
        assertEquals(ExternalPluginState.Disabled, externalPluginState(plugin(), enabledCert = null, quarantined = false))
    }

    @Test
    fun approvedForThisSignerIsEnabled() {
        assertEquals(ExternalPluginState.Enabled, externalPluginState(plugin(), enabledCert = "aa11", quarantined = false))
    }

    @Test
    fun updateFromADifferentSignerIsNotLoaded() {
        assertEquals(
            ExternalPluginState.CertificateChanged,
            externalPluginState(plugin(cert = "bb22"), enabledCert = "aa11", quarantined = false),
        )
    }

    @Test
    fun quarantineWinsOverApproval() {
        assertEquals(ExternalPluginState.Quarantined, externalPluginState(plugin(), enabledCert = "aa11", quarantined = true))
    }

    @Test
    fun brokenManifestOrUnreadableCertificateIsInvalid() {
        assertEquals(
            ExternalPluginState.Invalid,
            externalPluginState(plugin(problem = "missing chuchu.plugin.entry"), enabledCert = "aa11", quarantined = false),
        )
        assertEquals(ExternalPluginState.Invalid, externalPluginState(plugin(cert = null), enabledCert = null, quarantined = false))
    }
}

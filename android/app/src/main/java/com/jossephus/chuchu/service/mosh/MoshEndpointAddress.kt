package com.jossephus.chuchu.service.mosh

object MoshEndpointAddress {
    private const val IPV4_MAPPED_PREFIX = "::ffff:"

    fun fromSshPeer(profileHost: String, peerAddress: String): String {
        val address = peerAddress.substringBefore('%').lowercase()
        val ipv4 = address.removePrefix(IPV4_MAPPED_PREFIX)
        check(!ipv4.contains(':')) {
            "Mosh supports IPv4 hosts only, but $profileHost was reached over IPv6 ($address). " +
                "Use the host's IPv4 address or the SSH transport."
        }
        return ipv4
    }
}

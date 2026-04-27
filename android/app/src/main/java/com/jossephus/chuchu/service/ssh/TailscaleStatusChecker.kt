package com.jossephus.chuchu.service.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.net.InetAddress

class TailscaleStatusChecker(
    private val context: Context,
) {
    /**
     * Returns true if the device appears to be on a Tailscale network.
     * We check for a Tailscale IP (100.64.0.0/10) via network interfaces first.
     * If that fails (not all Android devices expose the tun interface to Java APIs),
     * we fall back to checking if any VPN transport is active.
     */
    fun isActive(): Boolean {
        if (hasTailnetAddress()) return true
        return hasVpnTransport()
    }

    private fun hasVpnTransport(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun hasTailnetAddress(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
                ?.any { addr -> isTailnetIp(addr) }
                ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun isTailnetIp(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || !addr.hostAddress.startsWith("100.")) return false
        val octets = addr.hostAddress.split(".")
        if (octets.size != 4) return false
        val second = octets.getOrNull(1)?.toIntOrNull() ?: return false
        // 100.64.0.0/10 means second octet must be in [64, 127]
        return second in 64..127
    }
}

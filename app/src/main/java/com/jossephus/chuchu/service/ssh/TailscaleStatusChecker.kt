package com.jossephus.chuchu.service.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class TailscaleStatusChecker(
    private val context: Context,
) {
    fun isActive(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

package com.jossephus.chuchu.service.mosh

import java.net.InetAddress
import java.net.UnknownHostException

/** Resolves profile host names to the numeric literal required by the Mosh UDP client. */
object MoshEndpointResolver {

    fun resolve(host: String): String = resolve(host, InetAddress::getAllByName)

    internal fun resolve(
        host: String,
        lookup: (String) -> Array<InetAddress>,
    ): String {
        val address =
            try {
                lookup(host).firstOrNull() ?: throw UnknownHostException(host)
            } catch (error: UnknownHostException) {
                throw IllegalStateException(
                    "Could not resolve Mosh host '$host' to an IP address",
                    error,
                )
            }

        // The port is a separate config field, so IPv6 must remain an unbracketed literal.
        return address.hostAddress.substringBefore('%')
    }
}

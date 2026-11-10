package com.jossephus.chuchu.model

import kotlinx.serialization.Serializable

@Serializable
enum class PortForwardType { Local, Remote }

@Serializable
data class PortForward(
    val type: PortForwardType = PortForwardType.Local,
    val localPort: Int,
    val destinationHost: String = DEFAULT_DESTINATION_HOST,
    val remotePort: Int,
) {
    val listenPort: Int
        get() = if (type == PortForwardType.Local) localPort else remotePort

    val targetPort: Int
        get() = if (type == PortForwardType.Local) remotePort else localPort

    companion object {
        const val DEFAULT_DESTINATION_HOST = "localhost"
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
        const val MIN_UNPRIVILEGED_PORT = 1024
    }
}

enum class PortForwardProblem { InvalidLocalPort, PrivilegedLocalPort, InvalidRemotePort, MissingHost, DuplicateListenPort }

fun PortForward.problem(others: List<PortForward>): PortForwardProblem? {
    val portRange = PortForward.MIN_PORT..PortForward.MAX_PORT
    if (localPort !in portRange) return PortForwardProblem.InvalidLocalPort
    if (type == PortForwardType.Local && localPort < PortForward.MIN_UNPRIVILEGED_PORT) {
        return PortForwardProblem.PrivilegedLocalPort
    }
    if (remotePort !in portRange) return PortForwardProblem.InvalidRemotePort
    if (destinationHost.isBlank()) return PortForwardProblem.MissingHost
    if (others.any { it.type == type && it.listenPort == listenPort }) return PortForwardProblem.DuplicateListenPort
    return null
}

val PortForward.label: String
    get() {
        val flag = if (type == PortForwardType.Local) "L" else "R"
        return "$flag $listenPort → $destinationHost:$targetPort"
    }

package com.jossephus.chuchu.service.mosh

import java.io.Closeable

data class MoshOutputEvent(
    val eventType: Int,
    val payload: ByteArray,
    val cols: Int,
    val rows: Int,
    val echoAck: Long,
)

data class MoshRuntimeState(
    val state: Int,
    val lastFailureCode: Int,
    val lastStateNumSent: Long,
    val lastStateNumReceived: Long,
    val pendingOutbound: Int,
    val pendingHostOps: Int,
    val currentRtoMs: Int,
)

enum class MoshEventType(val code: Int) {
    None(0),
    HostBytes(1),
    Resize(2),
    EchoAck(3),
    StateChanged(4),
    Diagnostic(5),
}

enum class MoshState(val code: Int) {
    Idle(0),
    Connecting(1),
    Connected(2),
    Reconnecting(3),
    Disconnecting(4),
    Failed(5),
}

class NativeMoshService(
    private val bridge: NativeMoshBridge = NativeMoshBridge(),
) : Closeable {
    private var handle: Long = 0L
    private val outputMeta = LongArray(5)
    private val stateBuf = LongArray(7)

    val isLoaded: Boolean get() = bridge.isLoaded()

    fun create(configJson: String): Boolean {
        if (!bridge.isLoaded()) return false
        handle = bridge.nativeCreate(configJson)
        return handle != 0L
    }

    fun start(): Boolean {
        if (handle == 0L) return false
        return bridge.nativeStart(handle) == 0
    }

    fun sendInput(data: ByteArray): Boolean {
        if (handle == 0L) return false
        return bridge.nativeSendInput(handle, data) == 0
    }

    fun resize(cols: Int, rows: Int): Boolean {
        if (handle == 0L) return false
        return bridge.nativeResize(handle, cols, rows) == 0
    }

    fun maintenanceTick(): Boolean {
        if (handle == 0L) return false
        return bridge.nativeMaintenanceTick(handle) == 0
    }

    fun pumpNetwork(): Boolean {
        if (handle == 0L) return false
        return bridge.nativePumpNetwork(handle) == 0
    }

    /// Poll one output event. Returns null if no event is queued.
    fun pollOutput(): MoshOutputEvent? {
        if (handle == 0L) return null
        val buf = ByteArray(4096)
        val rc = bridge.nativePollOutput(handle, buf, outputMeta)
        if (rc != 0) return null
        val eventType = outputMeta[0].toInt()
        if (eventType == 0) return null // MOSH_EVENT_NONE
        val written = outputMeta[1].toInt()
        val payload = if (written > 0) buf.copyOfRange(0, written) else ByteArray(0)
        return MoshOutputEvent(
            eventType = eventType,
            payload = payload,
            cols = outputMeta[2].toInt(),
            rows = outputMeta[3].toInt(),
            echoAck = outputMeta[4],
        )
    }

    fun pollState(): MoshRuntimeState? {
        if (handle == 0L) return null
        val rc = bridge.nativePollState(handle, stateBuf)
        if (rc != 0) return null
        return MoshRuntimeState(
            state = stateBuf[0].toInt(),
            lastFailureCode = stateBuf[1].toInt(),
            lastStateNumSent = stateBuf[2],
            lastStateNumReceived = stateBuf[3],
            pendingOutbound = stateBuf[4].toInt(),
            pendingHostOps = stateBuf[5].toInt(),
            currentRtoMs = stateBuf[6].toInt(),
        )
    }

    fun stop() {
        if (handle == 0L) return
        bridge.nativeStop(handle)
    }

    override fun close() {
        if (handle == 0L) return
        bridge.nativeDestroy(handle)
        handle = 0L
    }
}

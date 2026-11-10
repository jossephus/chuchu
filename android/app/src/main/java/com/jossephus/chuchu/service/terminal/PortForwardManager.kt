package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.PortForward
import com.jossephus.chuchu.service.ssh.HostKeyCheck
import com.jossephus.chuchu.service.ssh.HostKeyPolicy
import com.jossephus.chuchu.service.ssh.HostKeyStore
import com.jossephus.chuchu.service.ssh.NativeSshService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PortForwardStatus {
    data object Starting : PortForwardStatus

    data class Active(val forwards: List<PortForward>) : PortForwardStatus

    data class Failed(val message: String) : PortForwardStatus
}

class PortForwardManager(
    private val scope: CoroutineScope,
    private val hostKeyStore: HostKeyStore,
    private val loadForwards: suspend (Long) -> List<PortForward>,
    private val newService: (HostKeyPolicy) -> NativeSshService = { NativeSshService(hostKeyPolicy = it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _status = MutableStateFlow<Map<Long, PortForwardStatus>>(emptyMap())
    val status: StateFlow<Map<Long, PortForwardStatus>> = _status.asStateFlow()

    fun sync(connected: Map<Long, TabSpec>) {
        (jobs.keys - connected.keys).forEach(::stop)
        connected.forEach { (hostId, spec) ->
            if (!jobs.containsKey(hostId)) jobs[hostId] = scope.launch(ioDispatcher) { forward(hostId, spec) }
        }
    }

    private fun stop(hostId: Long) {
        jobs.remove(hostId)?.cancel()
        _status.update { it - hostId }
    }

    private suspend fun forward(hostId: Long, spec: TabSpec) {
        val forwards = loadForwards(hostId)
        if (forwards.isEmpty()) return
        while (currentCoroutineContext().isActive) {
            setStatus(hostId, PortForwardStatus.Starting)
            val failure = runOnce(hostId, spec, forwards)
            setStatus(hostId, PortForwardStatus.Failed(failure))
            delay(RETRY_DELAY_MS)
        }
    }

    private suspend fun runOnce(hostId: Long, spec: TabSpec, forwards: List<PortForward>): String {
        val service = newService(knownHostsOnly())
        try {
            service.connect(
                host = spec.host,
                port = spec.port,
                username = spec.username,
                authMethod = spec.authMethod,
                password = spec.password,
                publicKeyOpenSsh = spec.publicKeyOpenSsh,
                privateKeyPem = spec.privateKeyPem,
                keyPassphrase = spec.keyPassphrase,
                jumpHost = spec.jumpHost,
            )
            service.startPortForwards(forwards)
            setStatus(hostId, PortForwardStatus.Active(forwards))
            while (service.isPortForwardingRunning()) delay(HEALTH_CHECK_MS)
            return "connection lost"
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            return err.message ?: "port forwarding failed"
        } finally {
            service.close()
        }
    }

    private fun knownHostsOnly(): HostKeyPolicy = HostKeyPolicy { host, port, algorithm, keyBytes ->
        hostKeyStore.check(host, port, algorithm, keyBytes) is HostKeyCheck.Match
    }

    private fun setStatus(hostId: Long, status: PortForwardStatus) {
        if (jobs.containsKey(hostId)) _status.update { it + (hostId to status) }
    }

    companion object {
        const val RETRY_DELAY_MS = 5_000L
        const val HEALTH_CHECK_MS = 2_000L
    }
}

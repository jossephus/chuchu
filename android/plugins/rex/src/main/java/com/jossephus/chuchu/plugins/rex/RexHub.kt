package com.jossephus.chuchu.plugins.rex

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * One control connection to a Rex server, shared by its session list and every pane
 * open on it.
 *
 * The official clients keep a single connection for everything. A connection per
 * screen meant leaving a pane tore down two connections and rebuilt the list from
 * scratch, two list screens loaded everything twice, and each pane was a separate
 * client to Rex.
 */
internal class RexHub(val endpoint: RexEndpoint, http: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val control = RexControlConnection(http)
    val browser = RexSessionBrowser(control)
    private val ready: Deferred<Unit> =
        scope.async {
            control.open(endpoint)
            browser.start()
        }

    val isEnded: Boolean
        get() = control.isEnded || (ready.isCompleted && ready.getCompletionExceptionOrNull() != null)

    /** Suspends until the connection is open and the session list has loaded once. */
    suspend fun awaitReady() {
        ready.await()
        // A browser that failed to load closes the connection; surface that as an error.
        if (control.isEnded) throw RexConnectionException("Lost the connection to Rex")
    }

    /** Gives up a pane this client claimed; the server keeps ownership otherwise. */
    fun disown(sessionId: String, blockId: String) {
        if (control.isEnded) return
        scope.launch {
            runCatching {
                control.call(
                    "session.disown_block",
                    JSONObject().put("session_id", sessionId).put("block_ids", JSONArray().put(blockId)),
                )
            }.onFailure { Log.w(TAG, "Rex could not release $blockId", it) }
        }
    }

    fun close() {
        browser.close()
        control.close()
        scope.cancel()
    }

    private companion object {
        const val TAG = "RexHub"
    }
}

/**
 * Reference-counted [RexHub]s, one per server address. A hub stays open briefly after
 * its last user leaves, so going from a pane back to the list (or the reverse) reuses
 * the connection instead of reconnecting.
 */
internal object RexHubs {
    private class Entry(val hub: RexHub) {
        var users = 0
        var closing: Job? = null
    }

    private val entries = mutableMapOf<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Covers moving between the list and a pane without reconnecting.
    private const val LINGER_MS = 30_000L

    @Synchronized
    fun acquire(endpoint: RexEndpoint, http: OkHttpClient = RexTerminalSession.sharedHttp): RexHub {
        val existing = entries[endpoint.url]?.takeUnless { it.hub.isEnded }
        val entry =
            existing ?: Entry(RexHub(endpoint, http)).also { fresh ->
                entries.remove(endpoint.url)?.hub?.close()
                entries[endpoint.url] = fresh
            }
        entry.closing?.cancel()
        entry.closing = null
        entry.users++
        return entry.hub
    }

    @Synchronized
    fun release(hub: RexHub) {
        val entry = entries[hub.endpoint.url]?.takeIf { it.hub === hub } ?: return hub.closeIfUnused()
        entry.users--
        if (entry.users > 0) return
        if (hub.isEnded) {
            entries.remove(hub.endpoint.url)
            hub.close()
            return
        }
        entry.closing =
            scope.launch {
                delay(LINGER_MS)
                closeIfStillUnused(entry)
            }
    }

    @Synchronized
    private fun closeIfStillUnused(entry: Entry) {
        if (entry.users > 0 || entries[entry.hub.endpoint.url] !== entry) return
        entries.remove(entry.hub.endpoint.url)
        entry.hub.close()
    }

    // A hub that was already replaced (its connection ended) has no entry left to count.
    private fun RexHub.closeIfUnused() = close()
}

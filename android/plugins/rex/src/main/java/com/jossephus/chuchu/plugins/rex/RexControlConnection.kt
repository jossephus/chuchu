package com.jossephus.chuchu.plugins.rex

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

class RexCallException(val code: String, message: String) : IOException(message)

open class RexConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause)

class RexControlConnection(private val http: OkHttpClient) : Closeable {
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val welcomed = CompletableDeferred<Unit>()
    private val ended = CompletableDeferred<Throwable>()
    private val eventFlow =
        MutableSharedFlow<JSONObject>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var socket: WebSocket? = null

    val events: SharedFlow<JSONObject>
        get() = eventFlow

    suspend fun open(endpoint: RexEndpoint) {
        val request =
            Request.Builder()
                .url(endpoint.webSocketUrl("/api/control"))
                .header("Sec-WebSocket-Protocol", PROTOCOL)
                .build()
        socket = http.newWebSocket(request, listener)
        withTimeoutOrNull(OPEN_TIMEOUT_MS) { welcomed.await() }
            ?: throw RexConnectionException("Rex at ${endpoint.url} did not answer within ${OPEN_TIMEOUT_MS / 1000}s")
    }

    suspend fun awaitEnd(): Throwable = ended.await()

    suspend fun call(method: String, payload: JSONObject? = null): JSONObject {
        val ws = socket ?: throw RexConnectionException("Rex control connection is not open")
        val id = nextId.incrementAndGet().toString()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            val request = JSONObject().put("type", "request").put("id", id).put("method", method)
            if (payload != null) request.put("payload", payload)
            if (!ws.send(request.toString())) throw RexConnectionException("Rex control connection closed")
            val response =
                withTimeoutOrNull(CALL_TIMEOUT_MS) { reply.await() }
                    ?: throw RexConnectionException("$method got no answer within ${CALL_TIMEOUT_MS / 1000}s")
            val error = response.optJSONObject("error")
            if (error != null) {
                throw RexCallException(
                    code = error.optString("code", "internal"),
                    message = "$method failed: ${error.optString("message", "request failed")}",
                )
            }
            return response.optJSONObject("payload") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        end(RexConnectionException("Rex control connection closed"))
    }

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello =
                    JSONObject()
                        .put("type", "hello")
                        .put("api_revision", API_REVISION)
                        .put("api_min_revision", API_REVISION)
                        .put(
                            "client",
                            JSONObject().put("kind", "app").put("platform", "other").put("name", "chuchu"),
                        )
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message =
                    try {
                        JSONObject(text)
                    } catch (_: JSONException) {
                        return
                    }
                when (message.optString("type")) {
                    "welcome" -> {
                        if (message.optString("protocol") != PROTOCOL) {
                            webSocket.close(NORMAL_CLOSURE, null)
                            end(RexConnectionException("Rex answered with an unknown protocol"))
                            return
                        }
                        welcomed.complete(Unit)
                    }
                    "response" -> pending[message.optString("id")]?.complete(message)
                    "event" -> message.optJSONObject("payload")?.let { eventFlow.tryEmit(it) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                end(RexConnectionException(reason.ifBlank { "Rex closed the control connection ($code)" }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail =
                    when (response?.code) {
                        403 -> "Rex refused this device. Check the server's Tailscale access rules."
                        null -> t.message ?: t::class.java.simpleName
                        else -> "HTTP ${response.code} ${response.message}".trim()
                    }
                end(RexConnectionException("Rex connection failed: $detail", t))
            }
        }

    private fun end(cause: Throwable) {
        if (!ended.complete(cause)) return
        welcomed.completeExceptionally(cause)
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(cause) }
    }

    private companion object {
        const val PROTOCOL = "rex.control.v1"
        const val API_REVISION = 8
        const val NORMAL_CLOSURE = 1000
        const val OPEN_TIMEOUT_MS = 12_000L
        const val CALL_TIMEOUT_MS = 20_000L
        const val EVENT_BUFFER = 512
    }
}

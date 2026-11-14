package com.jossephus.chuchu.plugins.rex

import android.util.Base64
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class RexPaneClosedException : RexConnectionException("The Rex pane was closed")

class RexDataConnection(private val http: OkHttpClient) : Closeable {
    private val items = Channel<RexStreamItem>(Channel.UNLIMITED)
    private val welcomed = CompletableDeferred<ULong>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var decoder: RexStreamDecoder? = null

    val stream: ReceiveChannel<RexStreamItem>
        get() = items

    suspend fun open(endpoint: RexEndpoint, ticket: String) {
        val ticketBytes = Base64.decode(ticket, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        require(ticketBytes.size == TICKET_BYTES) { "Rex data ticket has ${ticketBytes.size} bytes" }
        val hello = ByteArray(1 + TICKET_BYTES)
        hello[0] = OP_HELLO
        ticketBytes.copyInto(hello, 1)
        val request =
            Request.Builder()
                .url(endpoint.webSocketUrl("/api/data"))
                .header("Sec-WebSocket-Protocol", PROTOCOL)
                .build()
        socket = http.newWebSocket(request, listener(hello.toByteString()))
        withTimeoutOrNull(OPEN_TIMEOUT_MS) { welcomed.await() }
            ?: throw RexConnectionException("Rex did not open the terminal stream within ${OPEN_TIMEOUT_MS / 1000}s")
    }

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        end(RexConnectionException("Rex terminal stream closed"))
    }

    fun fail(cause: Throwable) {
        socket?.cancel()
        end(cause)
    }

    private fun listener(hello: ByteString) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val active = decoder
                if (active == null) {
                    handleHandshake(webSocket, bytes.toByteArray())
                    return
                }
                try {
                    active.feed(bytes.toByteArray()).forEach { items.trySend(it) }
                } catch (e: IllegalStateException) {
                    webSocket.cancel()
                    end(RexConnectionException(e.message ?: "Rex sent a terminal stream that could not be read", e))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                end(
                    if (code == CLOSE_BLOCK_CLOSED) {
                        RexPaneClosedException()
                    } else {
                        RexConnectionException(reason.ifBlank { "Rex closed the terminal stream ($code)" })
                    }
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                end(RexConnectionException("Rex terminal stream failed: ${t.message ?: t::class.java.simpleName}", t))
            }
        }

    private fun handleHandshake(webSocket: WebSocket, message: ByteArray) {
        when {
            message.size == WELCOME_BYTES && message[0] == OP_WELCOME -> {
                var nonce = 0uL
                for (i in 0 until 8) {
                    nonce = nonce or (message[WELCOME_BYTES - 8 + i].toUByte().toULong() shl (8 * i))
                }
                decoder = RexStreamDecoder(nonce)
                welcomed.complete(nonce)
            }
            message.size >= 2 && message[0] == OP_REJECT -> {
                webSocket.close(NORMAL_CLOSURE, null)
                end(RexConnectionException(rejectReason(message[1].toInt() and 0xff)))
            }
            else -> {
                webSocket.cancel()
                end(RexConnectionException("Rex answered the terminal stream handshake with an unknown message"))
            }
        }
    }

    private fun end(cause: Throwable) {
        if (!welcomed.isCompleted) welcomed.completeExceptionally(cause)
        items.close(cause)
    }

    private fun rejectReason(code: Int): String =
        when (code) {
            0x01 -> "Rex refused the terminal stream: the ticket expired or was already used"
            0x02 -> "Rex refused the terminal stream: this client already streams that pane"
            0x03 -> "Rex refused the terminal stream: the pane is no longer available"
            0x04 -> "Rex refused the terminal stream: too many streams are open"
            0x07 -> "Rex refused the terminal stream: the ticket was issued for another protocol"
            else -> "Rex refused the terminal stream (reason $code)"
        }

    companion object {
        const val PROTOCOL = "rex.data.terminal.v1"
        private const val TICKET_BYTES = 16
        private const val WELCOME_BYTES = 32
        private const val OP_HELLO: Byte = 0x01
        private const val OP_WELCOME: Byte = 0x81.toByte()
        private const val OP_REJECT: Byte = 0x82.toByte()
        private const val NORMAL_CLOSURE = 1000
        private const val CLOSE_BLOCK_CLOSED = 4000
        private const val OPEN_TIMEOUT_MS = 12_000L
    }
}

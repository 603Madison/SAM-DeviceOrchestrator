package com.orchestrator.app.line

import com.orchestrator.api.ApiJson
import com.orchestrator.api.Battery
import com.orchestrator.api.Device
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.StreamFrame
import com.orchestrator.app.attempt
import com.orchestrator.app.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The live line to the server: a WebSocket at `/api/stream` (docs/PROTOCOL.md, "The live line").
 * What's for this phone arrives on it at once and is acknowledged once dealt with; the phone says
 * how its battery is. When it drops, it's tried again after a growing pause ([Backoff]), or
 * straight away when the network comes back ([kick]).
 */
class Line(
    http: OkHttpClient,
    private val settings: () -> Settings,
    /** Deals with a message; true when it's done with. */
    private val onMessage: (IncomingMessage) -> Boolean,
    private val onDevices: (List<Device>) -> Unit,
    /** The server turned the line away: is the sign-in still good, is the phone still registered? Puts it right if it can. */
    private val onRefused: suspend () -> Unit,
    private val battery: () -> Battery?,
) {
    // No read timeout (the line is quiet for long stretches); pings notice a dead connection.
    private val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastBattery: Battery? = null

    /** The network changed: drop what's open (it's on the old one) and connect now, over the new one. */
    fun kick() {
        socket?.cancel()
        wake.trySend(Unit)
    }

    /** There's a network: if the line is waiting to try again, try now. */
    fun nudge() {
        wake.trySend(Unit)
    }

    /** Tells the server how the battery is, when that's worth telling again. */
    fun battery(now: Battery) {
        val ws = socket ?: return
        if (!_connected.value || !BatteryReport.changed(lastBattery, now)) return
        if (ws.send(ApiJson.encodeToString(StreamFrame.serializer(), StreamFrame.Status(now)))) lastBattery = now
    }

    /** Keeps the line open until cancelled, or until the phone is signed out. */
    suspend fun run() {
        var failures = 0
        try {
            while (settings().signedIn) {
                val end = once()
                if (end.opened && !end.refused) failures = 0
                if (end.refused) onRefused()
                if (!settings().signedIn) break
                val pause = Backoff.pause(failures++)
                // A kick while waiting: the network is back, so start over from a short pause.
                if (withTimeoutOrNull(pause) { wake.receive() } != null) failures = 0
            }
        } finally {
            _connected.value = false
            socket?.cancel()
            socket = null
        }
    }

    private class End(val opened: Boolean, val refused: Boolean)

    /** One connection, from opening to its end. */
    private suspend fun once(): End = coroutineScope {
        val s = settings()
        val done = CompletableDeferred<End>()
        val frames = Channel<String>(Channel.UNLIMITED)
        val wasOpened = java.util.concurrent.atomic.AtomicBoolean(false)
        val request = Request.Builder()
            .url(s.server.replaceFirst("http", "ws") + "/api/stream")
            .header("Authorization", "Bearer ${s.token}")
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wasOpened.set(true)
                lastBattery = null
                _connected.value = true
                battery()?.let { now -> if (webSocket.send(ApiJson.encodeToString(StreamFrame.serializer(), StreamFrame.Status(now)))) lastBattery = now }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                // 1008: "Sign in and register first".
                done.complete(End(wasOpened.get(), refused = code == POLICY))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(End(wasOpened.get(), refused = code == POLICY))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(End(wasOpened.get(), refused = response?.code == 401 || response?.code == 403))
            }
        })
        socket = ws
        // In the order they came, one at a time: a message is acknowledged only once it's dealt with.
        val reader = launch {
            for (text in frames) {
                when (val frame = attempt { ApiJson.decodeFromString(StreamFrame.serializer(), text) }.getOrNull()) {
                    is StreamFrame.Message -> if (attempt { onMessage(frame.message) }.getOrDefault(false)) {
                        ws.send(ApiJson.encodeToString(StreamFrame.serializer(), StreamFrame.Acked(listOf(frame.message.id))))
                    }
                    is StreamFrame.Devices -> onDevices(frame.devices)
                    else -> {}
                }
            }
        }
        try {
            done.await()
        } finally {
            _connected.value = false
            ws.cancel()
            if (socket === ws) socket = null
            reader.cancel()
        }
    }

    private companion object {
        const val POLICY = 1008
    }
}

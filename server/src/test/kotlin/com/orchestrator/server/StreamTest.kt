package com.orchestrator.server

import com.orchestrator.api.ApiJson
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.DeviceRegistration
import com.orchestrator.api.Payload
import com.orchestrator.api.Role
import com.orchestrator.api.Seal
import com.orchestrator.api.SessionKind
import com.orchestrator.api.StreamFrame
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The live line, over a real socket: the server on a port of its own, devices as the apps connect. */
class StreamTest {
    private class Line(val socket: WebSocket, val frames: LinkedBlockingQueue<StreamFrame>, val closed: LinkedBlockingQueue<Int>) {
        fun take(): StreamFrame = frames.poll(10, TimeUnit.SECONDS) ?: error("nothing arrived")
        inline fun <reified T : StreamFrame> await(match: (T) -> Boolean = { true }): T {
            while (true) (take() as? T)?.takeIf(match)?.let { return it }
        }
        fun send(frame: StreamFrame) = socket.send(ApiJson.encodeToString(StreamFrame.serializer(), frame))
    }

    private class Signed(val token: String, val keys: KeyPair, val device: Device)

    @Test
    fun `a connected device gets things at once, shows as online, and is cut off when removed`() {
        val services = testServices()
        runBlocking { services.bootstrap() }
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val http = OkHttpClient()
        try {
            val user = runBlocking { services.users.create("maddie", "Maddie", Role.USER, "a-long-password") }
            fun signIn(name: String, kind: DeviceKind): Signed = runBlocking {
                val token = services.sessions.create(user.id, SessionKind.APP, name)
                val session = services.sessions.resolve(token)!!.second
                val keys = Seal.newKeyPair()
                Signed(token, keys, services.devices.register(user.id, session.id, DeviceRegistration(name, kind, publicKey = Seal.publicKeyText(keys.public))))
            }
            val laptop = signIn("Laptop", DeviceKind.LINUX)
            val phone = signIn("Phone", DeviceKind.ANDROID)

            fun post(from: Signed, path: String, body: String): Response = http.newCall(
                Request.Builder().url("http://127.0.0.1:$port$path").header("Authorization", "Bearer ${from.token}")
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute()

            fun connect(who: Signed): Line {
                val frames = LinkedBlockingQueue<StreamFrame>()
                val closed = LinkedBlockingQueue<Int>()
                val socket = http.newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:$port/api/stream").header("Authorization", "Bearer ${who.token}").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            frames += ApiJson.decodeFromString(StreamFrame.serializer(), text)
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            closed += code
                            webSocket.close(code, null)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            closed += -1
                        }
                    },
                )
                return Line(socket, frames, closed)
            }

            // Something sent while the phone was away is there when it connects.
            post(laptop, "/api/messages", sendBody(Payload.Link("https://example.com/before"), phone.device)).close()
            val line = connect(phone)
            val before = line.await<StreamFrame.Message>().message
            assertEquals(Payload.Link("https://example.com/before"), Seal.openPayload(before.sealed, phone.keys, phone.device.id))
            line.send(StreamFrame.Acked(listOf(before.id)))

            // Now it's connected: the laptop sees that, and what it sends arrives at once.
            val laptopLine = connect(laptop)
            laptopLine.await<StreamFrame.Devices> { frame -> frame.devices.any { it.name == "Phone" && it.online } }
            val sent = post(laptop, "/api/messages", sendBody(Payload.Ring(), phone.device)).use { it.body.string() }
            assertTrue(sent, "\"delivered\":[\"${phone.device.id}\"]" in sent)
            val ring = line.await<StreamFrame.Message>().message
            assertEquals(Payload.Ring(), Seal.openPayload(ring.sealed, phone.keys, phone.device.id))
            assertEquals(laptop.device.id, ring.from)

            // Its battery reaches the others.
            line.socket.send("""{"type":"status","battery":{"level":81,"charging":true}}""")
            laptopLine.await<StreamFrame.Devices> { frame -> frame.devices.any { it.name == "Phone" && it.battery?.level == 81 && it.battery?.charging == true } }

            // Acknowledged things are gone; the ring, not yet acknowledged, is still kept.
            Thread.sleep(200)
            assertEquals(listOf(ring.id), runBlocking { services.messages.waiting(phone.device.id) }.map { it.id })

            // Removed from the laptop, the phone's line ends and its token is dead.
            http.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/devices/${phone.device.id}").header("Authorization", "Bearer ${laptop.token}").delete().build(),
            ).execute().close()
            assertNotNull("the line should close", line.closed.poll(10, TimeUnit.SECONDS))
            laptopLine.await<StreamFrame.Devices> { frame -> frame.devices.none { it.name == "Phone" } }
            assertEquals(401, post(phone, "/api/messages/ack", """{"ids":[]}""").use { it.code })

            // Nobody connects without signing in.
            val stranger = LinkedBlockingQueue<Int>()
            http.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$port/api/stream").build(),
                object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { stranger += code }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stranger += -1 }
                },
            )
            assertNotNull(stranger.poll(10, TimeUnit.SECONDS))
            laptopLine.socket.close(1000, null)
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            server.stop(100, 500)
            services.close()
        }
    }
}

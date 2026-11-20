package com.orchestrator.app.net

import com.orchestrator.api.Battery
import com.orchestrator.api.Capabilities
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.OutgoingMessage
import com.orchestrator.api.Sealed
import com.orchestrator.app.data.Settings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerApiTest {
    private val server = MockWebServer()
    private var signedOut = false
    private lateinit var api: ServerApi
    private lateinit var base: String

    @Before
    fun start() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        val http = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ base }, { "tok" })).build()
        api = ServerApi(http, { base }, { "tok" }, onSignedOut = { signedOut = true })
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `devices come with the sign-in, and what a newer server adds is skipped`() = runBlocking {
        server.enqueue(json("""[{"id":"d1","name":"Laptop","kind":"LINUX","publicKey":"k","capabilities":["ring"],"online":true,"lastSeenAt":5,"createdAt":1,"battery":{"level":81,"charging":true},"shiny":1}]"""))
        val devices = api.devices()
        assertEquals(Battery(81, true), devices[0].battery)
        assertEquals(DeviceKind.LINUX, devices[0].kind)
        val request = server.takeRequest()
        assertEquals("/api/devices", request.path)
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    @Test
    fun `registering, renaming, removing and sending say what the protocol says`() = runBlocking {
        server.enqueue(json("""{"id":"d9","name":"Pixel","kind":"ANDROID","publicKey":"k","lastSeenAt":5,"createdAt":1,"current":true}"""))
        api.register(SignIn.registration("Pixel", "k", Settings().capabilities), bearer = "fresh")
        val register = server.takeRequest()
        assertEquals("POST /api/devices", "${register.method} ${register.path}")
        assertEquals("Bearer fresh", register.headers["Authorization"])
        val body = register.body.readUtf8()
        assertTrue(body, body.contains(""""kind":"ANDROID"""") && body.contains(""""publicKey":"k"""") && body.contains(""""capabilities":["notifications","clipboard","links","ring","activity","media"]"""))

        server.enqueue(json("""{"id":"d 9","name":"New","kind":"ANDROID","publicKey":"k","lastSeenAt":5,"createdAt":1}"""))
        api.rename("d 9", "New")
        val rename = server.takeRequest()
        assertEquals("PATCH /api/devices/d%209", "${rename.method} ${rename.path}")
        assertEquals("""{"name":"New"}""", rename.body.readUtf8())

        server.enqueue(json("""{"ok":true}"""))
        api.remove("d9")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json("""{"delivered":["d1"],"queued":[]}"""))
        val sent = api.sendMessage(OutgoingMessage(mapOf("d1" to Sealed("e", "n", "c")), 600, "clipboard"))
        assertEquals(listOf("d1"), sent.delivered)
        assertEquals("""{"to":{"d1":{"epk":"e","nonce":"n","ct":"c"}},"ttlSeconds":600,"collapseKey":"clipboard"}""", server.takeRequest().body.readUtf8())

        server.enqueue(json("""{"ok":true}"""))
        api.status(Battery(40))
        val status = server.takeRequest()
        assertEquals("/api/devices/me/status", status.path)
        assertEquals("""{"battery":{"level":40,"charging":false}}""", status.body.readUtf8())

        server.enqueue(json("""{"ok":true}"""))
        api.ack(listOf("m1", "m2"))
        assertEquals("""{"ids":["m1","m2"]}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `only the server's own 401 signs the app out, and never for a sign-in being tried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>Gateway</html>"))
        kotlin.runCatching { api.devices() }
        assertFalse(signedOut)
        server.enqueue(json("""{"error":"Sign in first"}""", 401))
        kotlin.runCatching { api.register(SignIn.registration("P", "k", emptyList()), bearer = "fresh") }
        assertFalse(signedOut)
        server.enqueue(json("""{"error":"Sign in first"}""", 401))
        val failed = kotlin.runCatching { api.devices() }.exceptionOrNull() as ServerException
        assertTrue(signedOut)
        assertEquals("Sign in first", failed.message)
    }

    @Test
    fun `the sign-in only goes to the server`() = runBlocking {
        val other = MockWebServer().apply { start() }
        other.enqueue(MockResponse().setBody("x"))
        val http = OkHttpClient.Builder().addInterceptor(ServerApi.authInterceptor({ base }, { "tok" })).build()
        http.newCall(okhttp3.Request.Builder().url(other.url("/elsewhere")).build()).execute().close()
        assertNull(other.takeRequest().headers["Authorization"])
        other.shutdown()
    }

    @Test
    fun `addresses as typed are made whole, and the sign-in page is asked as the protocol says`() {
        assertEquals("https://devices.local", ServerApi.normalize("devices.local/"))
        assertEquals("http://10.0.0.5:8080", ServerApi.normalize(" http://10.0.0.5:8080/ "))
        assertNull(ServerApi.normalize("not an address"))
        val challenge = SignIn.challenge("verifier")
        assertEquals(43, challenge.length)
        assertEquals(
            "https://devices.example.com/connect-app?challenge=$challenge&state=st&name=Maddie%27s%20phone&redirect=samorchestrator%3A%2F%2Fsigned-in",
            SignIn.signInUrl("https://devices.example.com", challenge, "st", "Maddie's phone"),
        )
        assertTrue(SignIn.pairingCode(challenge).matches(Regex("""\d{3} \d{3}""")))
        assertEquals(Capabilities.CLIPBOARD, Settings(showIncoming = false).capabilities.first())
    }
}

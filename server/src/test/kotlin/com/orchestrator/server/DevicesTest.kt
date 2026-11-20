package com.orchestrator.server

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import com.orchestrator.api.ApiJson
import com.orchestrator.api.Device
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.Payload
import com.orchestrator.api.Seal
import com.orchestrator.api.Sealed
import com.orchestrator.api.Ticket
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.KeyPair
import org.junit.Assert
import org.junit.Test

private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) = Assert.assertEquals(message, expected, actual)
private fun assertTrue(value: Boolean) = Assert.assertTrue(value)
private fun assertFalse(value: Boolean) = Assert.assertFalse(value)
private fun assertNull(value: Any?, message: String? = null) = Assert.assertNull(message, value)
private fun assertNotNull(value: Any?) = Assert.assertNotNull(value)

/** A signed-in device, as the apps are: a token, a key pair, and its registration. */
class TestDevice(val client: HttpClient, val token: String, val keys: KeyPair, val device: Device) {
    suspend fun get(path: String): HttpResponse = client.get(path) { header("Authorization", "Bearer $token") }
    suspend fun post(path: String, body: String): HttpResponse = client.post(path) { header("Authorization", "Bearer $token"); json(body) }
    suspend fun waiting(): List<IncomingMessage> = ApiJson.decodeFromString(ListSerializer(IncomingMessage.serializer()), get("/api/messages").bodyAsText())
    fun open(message: IncomingMessage): Payload? = Seal.openPayload(message.sealed, keys, device.id)
}

suspend fun ApplicationTestBuilder.newUser(name: String, password: String = "a-long-password") {
    val admin = adminBrowser()
    admin.postJson("/api/admin/users", """{"username":"$name","password":"$password"}""")
    // An admin's password is temporary: the person chooses their own.
    val browser = browser()
    browser.post("/api/auth/login") { json("""{"username":"$name","password":"$password"}""") }
    browser.postJson("/api/me/password", """{"current":"$password","new":"$password-mine"}""")
}

suspend fun ApplicationTestBuilder.device(user: String, name: String, kind: String = "LINUX", password: String = "a-long-password-mine"): TestDevice {
    val client = createClient { install(WebSockets) }
    val signedIn = client.post("/api/auth/login") { json("""{"username":"$user","password":"$password","app":true,"deviceName":"$name"}""") }
    assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
    val token = signedIn.obj().str("token")
    val keys = Seal.newKeyPair()
    val registered = client.post("/api/devices") {
        header("Authorization", "Bearer $token")
        json("""{"name":"$name","kind":"$kind","model":"Test","publicKey":"${Seal.publicKeyText(keys.public)}","capabilities":["notifications","links"]}""")
    }
    assertEquals(HttpStatusCode.OK, registered.status, registered.bodyAsText())
    return TestDevice(client, token, keys, ApiJson.decodeFromString(Device.serializer(), registered.bodyAsText()))
}

fun sendBody(payload: Payload, vararg to: Device, collapse: String? = null): String {
    val sealed = to.associate { it.id to Seal.seal(payload, it.publicKey, it.id) }
    val map = sealed.entries.joinToString(",") { (id, s) -> "\"$id\":${ApiJson.encodeToString(Sealed.serializer(), s)}" }
    return """{"to":{$map}${collapse?.let { ""","collapseKey":"$it"""" } ?: ""}}"""
}

class DevicesTest {
    @Test
    fun `devices register, see each other, and only each other`() = serverTest {
        newUser("maddie")
        newUser("sam")
        val laptop = device("maddie", "Laptop")
        val phone = device("maddie", "Phone", "ANDROID")
        val other = device("sam", "Sam's phone", "ANDROID")

        val mine = laptop.get("/api/devices").json().jsonArray.map { it.jsonObject }
        assertEquals(listOf("Laptop", "Phone"), mine.map { it.str("name") })
        assertEquals(listOf("true", "false"), mine.map { it.str("current") })
        assertEquals(1, other.get("/api/devices").json().jsonArray.size)

        // Nobody sends to someone else's device.
        assertEquals(HttpStatusCode.NotFound, other.post("/api/messages", sendBody(Payload.Ring(), phone.device)).status)
    }

    @Test
    fun `what's sent waits sealed until the device has it`() = serverTest { services ->
        newUser("maddie")
        val laptop = device("maddie", "Laptop")
        val phone = device("maddie", "Phone", "ANDROID")

        val note = Payload.Notification(key = "k1", app = "Signal", title = "Ada", text = "On my way", at = 5)
        val sent = laptop.post("/api/messages", sendBody(note, phone.device))
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        assertEquals("[\"${phone.device.id}\"]", sent.obj()["queued"].toString())

        // The server holds only what's sealed: the words aren't in its database.
        val stored = services.db.tx { query("SELECT sealed FROM messages") { it.string("sealed") } }
        assertEquals(1, stored.size)
        assertFalse("On my way" in stored.single())

        val waiting = phone.waiting()
        assertEquals(note, phone.open(waiting.single()))
        assertEquals("Laptop", waiting.single().fromName)
        // The laptop can't open what was sealed for the phone.
        assertTrue(runCatching { Seal.openPayload(waiting.single().sealed, laptop.keys, laptop.device.id) }.isFailure)

        phone.post("/api/messages/ack", """{"ids":["${waiting.single().id}"]}""")
        assertTrue(phone.waiting().isEmpty())
    }

    @Test
    fun `a newer clipboard replaces the one still waiting`() = serverTest {
        newUser("maddie")
        val laptop = device("maddie", "Laptop")
        val phone = device("maddie", "Phone", "ANDROID")
        laptop.post("/api/messages", sendBody(Payload.Clipboard("one"), phone.device, collapse = "clipboard"))
        laptop.post("/api/messages", sendBody(Payload.Clipboard("two"), phone.device, collapse = "clipboard"))
        assertEquals(listOf<Payload?>(Payload.Clipboard("two")), phone.waiting().map(phone::open))
    }

    @Test
    fun `removing a device signs it out`() = serverTest {
        newUser("maddie")
        val laptop = device("maddie", "Laptop")
        val phone = device("maddie", "Phone", "ANDROID")
        val removed = laptop.client.delete("/api/devices/${phone.device.id}") { header("Authorization", "Bearer ${laptop.token}") }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertEquals(HttpStatusCode.Unauthorized, phone.get("/api/devices").status)
        assertEquals(1, laptop.get("/api/devices").json().jsonArray.size)
    }

    @Test
    fun `the web page sends to a device without being one`() = serverTest {
        newUser("maddie")
        val phone = device("maddie", "Phone", "ANDROID")
        val browser = browser()
        browser.post("/api/auth/login") { json("""{"username":"maddie","password":"a-long-password-mine"}""") }
        val sent = browser.postJson("/api/messages", sendBody(Payload.Link("https://example.com"), phone.device))
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        val message = phone.waiting().single()
        assertNull(message.from)
        assertEquals(Payload.Link("https://example.com"), phone.open(message))
        // A browser isn't a device: it has no stream, and can't ask for tickets.
        assertEquals(HttpStatusCode.Forbidden, browser.postJson("/api/tickets", """{"app":"music"}""").status)
    }

    @Test
    fun `a ticket says who is signed in, for one app, and checks out against the published keys`() = serverTest { services ->
        services.settings.update { it.copy(publicUrl = "https://orchestrator.example.com", apps = Services.appsFrom("music=https://music.example.com/")) }
        newUser("maddie")
        val laptop = device("maddie", "Laptop")

        assertEquals(HttpStatusCode.NotFound, laptop.post("/api/tickets", """{"app":"health"}""").status)
        val answer = laptop.post("/api/tickets", """{"app":"music"}""")
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        val ticket = ApiJson.decodeFromString(Ticket.serializer(), answer.bodyAsText())
        assertEquals("https://music.example.com", ticket.url)

        val discovery = client.get("/api/orchestrator").obj()
        assertEquals("https://orchestrator.example.com", discovery.str("issuer"))
        val keys = JWKSet.parse(client.get("/api/orchestrator/keys").bodyAsText())
        assertNull(keys.keys.single().toECKey().d, "the private half is never published")
        val jwt = SignedJWT.parse(ticket.ticket)
        assertTrue(jwt.verify(ECDSAVerifier(keys.getKeyByKeyId(jwt.header.keyID).toECKey())))
        val claims = jwt.jwtClaimsSet
        assertEquals("https://orchestrator.example.com", claims.issuer)
        assertEquals(listOf("https://music.example.com"), claims.audience)
        assertEquals("music", claims.getStringClaim("app"))
        assertEquals("maddie", claims.getStringClaim("preferred_username"))
        assertEquals("Laptop", claims.getStringClaim("device_name"))
        assertNotNull(claims.jwtid)
        assertTrue(claims.expirationTime.time - claims.issueTime.time <= 120_000)
    }

    @Test
    fun `the break-glass admin gets no tickets`() = serverTest { services ->
        services.settings.update { it.copy(apps = Services.appsFrom("music=https://music.example.com")) }
        val admin = device("admin", "Console", password = "break-glass-pw")
        assertEquals(HttpStatusCode.Forbidden, admin.post("/api/tickets", """{"app":"music"}""").status)
    }
}

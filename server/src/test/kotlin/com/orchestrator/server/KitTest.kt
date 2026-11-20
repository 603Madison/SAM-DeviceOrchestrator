package com.orchestrator.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.orchestrator.api.AppLink
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceKind
import com.orchestrator.server.db.Database
import com.orchestrator.server.db.Schema
import com.orchestrator.server.kit.OrchestratorTickets
import com.orchestrator.server.tickets.TicketIdentity
import com.orchestrator.server.tickets.Tickets
import com.orchestrator.server.util.Random
import com.orchestrator.server.util.SecretBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

/** What the family's servers do with a ticket: the kit against the real signer. */
class KitTest {
    private val issuer = "https://orchestrator.example.com"
    private val music = AppLink("music", "Music", "https://music.example.com")
    private var now = 1_800_000_000_000L
    private val settings = SettingsStore(Database.inMemory().also(Schema::migrate), SecretBox(Random.bytes(32)))
    private val tickets = Tickets(settings) { now }
    private var fetches = 0
    private val kit = OrchestratorTickets(
        fetch = { url ->
            fetches++
            if (url == "$issuer/api/orchestrator/keys") tickets.publicKeys() else throw java.io.IOException("no such place")
        },
        clock = { now },
    )
    private val maddie = TicketIdentity("u1", "maddie", "Maddie", "maddie@example.com", "https://auth.example.com/application/o/orchestrator/", "sub-1", listOf("family"))
    private val laptop = Device("d1", "Laptop", DeviceKind.LINUX, publicKey = "", lastSeenAt = 0, createdAt = 0)

    private fun ticket(app: AppLink = music, from: String = issuer) = runBlocking { tickets.issue(from, app, maddie, laptop).ticket }

    private fun verify(ticket: String, audience: String = "https://music.example.com/", app: String = "music", orchestrator: String = "$issuer/") =
        runBlocking { kit.verify(ticket, orchestrator, listOf(audience), app) }

    private fun refused(why: String, block: () -> Unit) {
        try {
            block()
            fail("should have been refused: $why")
        } catch (e: OrchestratorTickets.Refused) {
            assertTrue("${e.message} should mention \"$why\"", why in e.message.orEmpty())
        }
    }

    @Test
    fun `a good ticket says who is signed in`() {
        val claims = verify(ticket())
        assertEquals("maddie", claims.username)
        assertEquals("Maddie", claims.displayName)
        assertEquals("maddie@example.com", claims.email)
        assertEquals("sub-1", claims.oidcSubject)
        assertEquals(setOf("family"), claims.groups)
        assertEquals("Laptop", claims.deviceName)
        // The keys are fetched once and remembered.
        verify(ticket())
        assertEquals(1, fetches)
    }

    @Test
    fun `a ticket works once`() {
        val ticket = ticket()
        verify(ticket)
        refused("already used") { verify(ticket) }
    }

    @Test
    fun `a ticket is for one server and one app`() {
        refused("another server") { verify(ticket(), audience = "https://health.example.com") }
        refused("another app") { verify(ticket(), app = "health") }
        refused("another server") { verify(ticket(AppLink("music", "Music", "https://music.evil.example")), audience = "https://music.example.com") }
    }

    @Test
    fun `a ticket goes out of date`() {
        val ticket = ticket()
        now += Tickets.LIFETIME_MS + 31_000
        refused("expired") { verify(ticket) }
    }

    @Test
    fun `only the orchestrator's own signature counts`() {
        // Signed by someone else's key, under the real key's id.
        val real = SignedJWT.parse(ticket())
        val other = ECKeyGenerator(Curve.P_256).keyID(real.header.keyID).generate()
        val forged = SignedJWT(real.header, real.jwtClaimsSet).apply { sign(ECDSASigner(other)) }.serialize()
        refused("signature") { verify(forged) }

        // A key id the orchestrator never published.
        val unknown = ECKeyGenerator(Curve.P_256).keyID("made-up").generate()
        val stranger = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("made-up").type(JOSEObjectType("samo-ticket+jwt")).build(), real.jwtClaimsSet)
            .apply { sign(ECDSASigner(unknown)) }.serialize()
        refused("doesn't publish") { verify(stranger) }

        // The public key used as a shared secret (the classic algorithm mix-up).
        val hmac = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID(real.header.keyID).type(JOSEObjectType("samo-ticket+jwt")).build(), real.jwtClaimsSet)
            .apply { sign(MACSigner(ByteArray(32) { 7 })) }.serialize()
        refused("isn't signed the way") { verify(hmac) }

        // Changed after signing.
        val parts = ticket().split('.')
        val claims = JWTClaimsSet.Builder(real.jwtClaimsSet).claim("preferred_username", "admin").jwtID("x").build()
        val tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray()) + "." + parts[2]
        refused("signature") { verify(tampered) }

        refused("isn't a ticket") { verify("hello") }
    }

    @Test
    fun `a ticket from another orchestrator is refused`() {
        refused("another orchestrator") { verify(ticket(from = "https://elsewhere.example.com")) }
    }

    @Test
    fun `a ticket that lasts too long is refused`() {
        val real = SignedJWT.parse(ticket())
        val key = com.nimbusds.jose.jwk.ECKey.parse(runBlocking { settings.secret("tickets.key") })
        val long = JWTClaimsSet.Builder(real.jwtClaimsSet).expirationTime(Date(now + 3_600_000)).jwtID("long").build()
        val signed = SignedJWT(real.header, long).apply { sign(ECDSASigner(key)) }.serialize()
        refused("too long") { verify(signed) }
    }

    @Test
    fun `addresses compare without what doesn't matter`() {
        assertEquals("https://music.example.com", OrchestratorTickets.normal("HTTPS://Music.Example.com/"))
        assertEquals("https://example.com/music", OrchestratorTickets.normal("https://example.com/music/"))
        assertTrue(OrchestratorTickets.sameProvider("https://auth.example.com/application/o/music/", "https://AUTH.example.com/application/o/orchestrator/"))
        assertTrue(!OrchestratorTickets.sameProvider("https://auth.example.com/application/o/music/", "https://auth.evil.example/application/o/music/"))
        assertTrue(!OrchestratorTickets.sameProvider(null, null))
    }
}

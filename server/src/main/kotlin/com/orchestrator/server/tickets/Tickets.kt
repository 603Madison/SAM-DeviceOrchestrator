package com.orchestrator.server.tickets

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.orchestrator.api.AppLink
import com.orchestrator.api.Device
import com.orchestrator.api.Ticket
import com.orchestrator.server.SettingsStore
import com.orchestrator.server.util.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

/** Who a ticket is about, as the identity provider (or this server) knows them. */
data class TicketIdentity(
    val userId: String,
    val username: String,
    val displayName: String,
    val email: String?,
    val oidcIssuer: String?,
    val oidcSubject: String?,
    val groups: List<String>,
)

/**
 * Sign-in tickets: short-lived signed statements ("this person is signed in on this device")
 * that each of the family's servers accepts in place of a sign-in of its own. A ticket is a JWT
 * (ES256) made for one server (its address is the audience), good for two minutes and, on the
 * server that takes it, once.
 *
 * The signing key is made at first use and kept sealed in the database; servers check tickets
 * against the public half at `/api/orchestrator/keys`.
 */
class Tickets(private val settings: SettingsStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()

    @Volatile
    private var cached: ECKey? = null

    private suspend fun key(): ECKey = cached ?: lock.withLock {
        cached ?: (
            settings.secret(KEY)?.let { runCatching { ECKey.parse(it) }.getOrNull() }
                ?: ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyID(Random.id()).algorithm(JWSAlgorithm.ES256).generate()
                    .also { settings.setSecret(KEY, it.toJSONString()) }
            ).also { cached = it }
    }

    /** The public keys, as a JWK set, for servers to check tickets with. */
    suspend fun publicKeys(): String = JWKSet(key().toPublicJWK()).toString()

    suspend fun issue(issuer: String, app: AppLink, who: TicketIdentity, device: Device): Ticket {
        val key = key()
        val now = clock()
        val expires = now + LIFETIME_MS
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(app.url.trimEnd('/'))
            .subject(who.userId)
            .jwtID(Random.token())
            .issueTime(Date(now))
            .notBeforeTime(Date(now - SKEW_MS))
            .expirationTime(Date(expires))
            .claim("app", app.id)
            .claim("preferred_username", who.username)
            .claim("name", who.displayName)
            .apply {
                who.email?.let { claim("email", it) }
                who.oidcIssuer?.let { claim("oidc_iss", it) }
                who.oidcSubject?.let { claim("oidc_sub", it) }
            }
            .claim("groups", who.groups)
            .claim("device_id", device.id)
            .claim("device_name", device.name)
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType("samo-ticket+jwt")).build(), claims)
        jwt.sign(ECDSASigner(key))
        return Ticket(app.id, app.url.trimEnd('/'), jwt.serialize(), expires)
    }

    companion object {
        private const val KEY = "tickets.key"
        const val LIFETIME_MS = 2L * 60 * 1000
        const val SKEW_MS = 30_000L
    }
}

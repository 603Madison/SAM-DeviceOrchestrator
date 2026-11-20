package com.orchestrator.server

import com.orchestrator.server.db.Database
import com.orchestrator.server.util.SecretBox
import com.orchestrator.api.AppLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Signing in through an OpenID Connect provider such as Authentik. */
@Serializable
data class OidcSettings(
    val enabled: Boolean = false,
    /** e.g. https://auth.example.com/application/o/superawesomemagicalorchestrator/ */
    val issuer: String = "",
    val clientId: String = "",
    val scopes: String = "openid profile email",
    val buttonLabel: String = "Sign in with Authentik",
    /** The claim holding the username; email and the subject are fallbacks. */
    val usernameClaim: String = "preferred_username",
    val groupsClaim: String = "groups",
    /** Only members of this group may sign in; blank lets in anyone the provider does. */
    val userGroup: String = "",
    /** Members of this group are admins, everyone else a user; blank leaves roles to the admin page. */
    val adminGroup: String = "",
    /** Make an account the first time someone signs in; off means an admin adds people first. */
    val autoCreateUsers: Boolean = true,
)

@Serializable
data class ServerSettings(
    val serverName: String = "SuperAwesomeMagicalOrchestrator",
    /** The address people reach the server at, e.g. https://video.example.com (for sign-in redirects). */
    val publicUrl: String = "",
    /** Whether accounts other than the break-glass admin may sign in with a password. */
    val localLogin: Boolean = true,
    val oidc: OidcSettings = OidcSettings(),
    /** The family's servers: where signed-in devices find each app, and who sign-in tickets are made for. */
    val apps: List<AppLink> = emptyList(),
    /** How long something sent to a device that's away is kept for it, in hours at most. */
    val keepHours: Int = 72,
)

/** The server's settings, kept in the database and changed from the admin page. */
class SettingsStore(private val db: Database, private val secrets: SecretBox) {
    @Volatile
    private var cached: ServerSettings? = null

    suspend fun get(): ServerSettings = cached ?: load().also { cached = it }

    private suspend fun load(): ServerSettings = db.tx {
        one("SELECT value FROM settings WHERE key = 'server'") { it.string("value") }
    }?.let { runCatching { SettingsJson.decodeFromString<ServerSettings>(it) }.getOrNull() } ?: ServerSettings()

    suspend fun update(transform: (ServerSettings) -> ServerSettings): ServerSettings {
        val next = transform(get())
        db.tx { update("INSERT OR REPLACE INTO settings (key, value) VALUES ('server', ?)", SettingsJson.encodeToString(next)) }
        cached = next
        return next
    }

    /** A value kept sealed under [key] (the ticket signing key). */
    suspend fun secret(key: String): String? = db.tx {
        one("SELECT value FROM settings WHERE key = ?", key) { it.string("value") }
    }?.let { runCatching { secrets.open(it, key) }.getOrNull() }

    suspend fun setSecret(key: String, value: String) = db.tx {
        update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", key, secrets.seal(value, key))
    }

    suspend fun oidcClientSecret(): String = db.tx {
        one("SELECT value FROM settings WHERE key = 'oidc.clientSecret'") { it.string("value") }
    }?.let { runCatching { secrets.open(it, "oidc.clientSecret") }.getOrNull() }.orEmpty()

    suspend fun setOidcClientSecret(secret: String) = db.tx {
        if (secret.isEmpty()) {
            update("DELETE FROM settings WHERE key = 'oidc.clientSecret'")
        } else {
            update("INSERT OR REPLACE INTO settings (key, value) VALUES ('oidc.clientSecret', ?)", secrets.seal(secret, "oidc.clientSecret"))
        }
    }

    private companion object {
        val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

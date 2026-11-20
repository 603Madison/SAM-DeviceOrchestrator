package com.orchestrator.server.admin

import com.orchestrator.server.ApiError
import com.orchestrator.server.OidcSettings
import com.orchestrator.server.ServerConfig
import com.orchestrator.server.ServerSettings
import com.orchestrator.server.Services
import com.orchestrator.api.AppLink
import com.orchestrator.api.Role
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.orchestrator.api.User
import com.orchestrator.server.auth.redirectUri
import com.orchestrator.server.auth.requireAdmin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class NewUser(val username: String, val displayName: String = "", val password: String, val role: Role = Role.USER)

@Serializable
data class UserChange(val displayName: String? = null, val role: Role? = null, val disabled: Boolean? = null)

@Serializable
data class PasswordReset(val password: String)

@Serializable
data class AdminSettings(
    val settings: ServerSettings,
    val oidcClientSecretSet: Boolean,
    /** The address to register with the identity provider as the redirect URI. */
    val redirectUri: String,
    /** Settings fixed by environment variables, which win at every start. */
    val fromEnvironment: List<String>,
    val version: String,
)

@Serializable
data class SettingsChange(
    val settings: ServerSettings,
    /** Null keeps the saved secret; empty removes it. */
    val oidcClientSecret: String? = null,
)

@Serializable
data class OidcTest(val ok: Boolean, val message: String)

fun Route.adminRoutes(services: Services) {
    route("/api/admin") {
        route("/users") {
            get {
                call.requireAdmin(services)
                call.respond(services.users.list())
            }
            post {
                call.requireAdmin(services)
                val request = call.receive<NewUser>()
                call.respond(services.users.create(request.username, request.displayName, request.role, request.password, byAdmin = true))
            }
            route("/{id}") {
                patch {
                    val admin = call.requireAdmin(services)
                    val id = call.parameters["id"].orEmpty()
                    val change = call.receive<UserChange>()
                    if (id == admin.id && (change.role == Role.USER || change.disabled == true)) {
                        throw ApiError.badRequest("You can't demote or turn off your own account")
                    }
                    val updated = services.users.update(id, change.displayName, change.role, change.disabled)
                    if (updated.disabled) services.sessions.revokeAll(updated.id)
                    call.respond(updated)
                }
                delete {
                    val admin = call.requireAdmin(services)
                    val id = call.parameters["id"].orEmpty()
                    if (id == admin.id) throw ApiError.badRequest("You can't delete your own account")
                    services.users.delete(id)
                    // The account's devices, and what was waiting for them, go with it.
                    call.respond(mapOf("ok" to true))
                }
                post("/password") {
                    call.requireAdmin(services)
                    val id = call.parameters["id"].orEmpty()
                    val user: User = services.users.byId(id) ?: throw ApiError.notFound("No such user")
                    if (user.oidc && !user.hasPassword) throw ApiError.badRequest("${user.username} signs in through single sign-on")
                    services.users.setPassword(id, call.receive<PasswordReset>().password, byAdmin = true)
                    services.sessions.revokeAll(id)
                    call.respond(mapOf("ok" to true))
                }
                delete("/sessions") {
                    call.requireAdmin(services)
                    services.sessions.revokeAll(call.parameters["id"].orEmpty())
                    call.respond(mapOf("ok" to true))
                }
            }
        }

        get("/settings") {
            call.requireAdmin(services)
            call.respond(adminSettings(services, call))
        }

        put("/settings") {
            call.requireAdmin(services)
            val change = call.receive<SettingsChange>()
            val next = validated(change.settings)
            // The saved client secret only goes to the identity provider it was given for: a new one needs it again.
            val before = services.settings.get().oidc
            val moved = before.issuer.isNotBlank() && before.issuer.trimEnd('/') != next.oidc.issuer.trimEnd('/')
            if (moved && change.oidcClientSecret == null && services.settings.oidcClientSecret().isNotEmpty()) {
                throw ApiError.badRequest("Enter the client secret again for the new identity provider")
            }
            services.settings.update { next }
            change.oidcClientSecret?.let { services.settings.setOidcClientSecret(it.trim()) }
            call.respond(adminSettings(services, call))
        }

        post("/oidc/test") {
            call.requireAdmin(services)
            val oidc = call.receive<OidcSettings>()
            val result = try {
                val discovery = services.oidc.discover(oidc.issuer)
                OidcTest(true, "Found ${discovery.issuer}. Register this redirect URI with it: ${redirectUri(services, call)}")
            } catch (e: ApiError) {
                OidcTest(false, e.message ?: "Couldn't reach the provider")
            }
            call.respond(result)
        }
    }
}

private suspend fun adminSettings(services: Services, call: io.ktor.server.application.ApplicationCall) = AdminSettings(
    settings = services.settings.get(),
    oidcClientSecretSet = services.settings.oidcClientSecret().isNotEmpty(),
    redirectUri = redirectUri(services, call),
    fromEnvironment = services.config.overrides.keys.filter { it in ServerConfig.OVERRIDES }.sorted(),
    version = Services.version,
)

private fun validated(settings: ServerSettings): ServerSettings {
    val publicUrl = settings.publicUrl.trim().trimEnd('/')
    if (publicUrl.isNotEmpty() && !publicUrl.startsWith("https://") && !publicUrl.startsWith("http://")) {
        throw ApiError.badRequest("The public address must start with https:// (or http://)")
    }
    val oidc = settings.oidc
    if (oidc.enabled && (oidc.issuer.isBlank() || oidc.clientId.isBlank())) {
        throw ApiError.badRequest("Single sign-on needs the issuer address and client ID")
    }
    if (oidc.issuer.isNotBlank() && !oidc.issuer.trim().startsWith("https://") && !oidc.issuer.trim().startsWith("http://")) {
        throw ApiError.badRequest("The issuer must be a web address")
    }
    val apps = settings.apps.map { AppLink(it.id.trim().lowercase(), it.name.trim().take(80), it.url.trim().trimEnd('/')) }.filter { it.id.isNotEmpty() || it.url.isNotEmpty() }
    apps.forEach { app ->
        if (!app.id.matches(Regex("[a-z0-9-]{1,32}"))) throw ApiError.badRequest("An app's id is lowercase letters, digits and dashes (like music)")
        val url = app.url.toHttpUrlOrNull()
        if (url == null || url.encodedPath != "/" || url.query != null) throw ApiError.badRequest("${app.id}'s address must be its server's address, like https://${app.id}.example.com")
    }
    if (apps.map { it.id }.distinct().size != apps.size) throw ApiError.badRequest("Each app can be listed once")
    return settings.copy(
        apps = apps.take(50).map { it.copy(name = it.name.ifBlank { Services.KNOWN_APPS[it.id] ?: it.id }) },
        keepHours = settings.keepHours.coerceIn(1, 24 * 30),
        serverName = settings.serverName.trim().take(80).ifBlank { "SuperAwesomeMagicalOrchestrator" },
        publicUrl = publicUrl,
        oidc = oidc.copy(
            issuer = oidc.issuer.trim(),
            clientId = oidc.clientId.trim(),
            buttonLabel = oidc.buttonLabel.trim().take(60).ifBlank { "Sign in with single sign-on" },
            usernameClaim = oidc.usernameClaim.trim().ifBlank { "preferred_username" },
            groupsClaim = oidc.groupsClaim.trim().ifBlank { "groups" },
            userGroup = oidc.userGroup.trim(),
            adminGroup = oidc.adminGroup.trim(),
        ),
    )
}

package com.orchestrator.server

import com.orchestrator.api.Role
import com.orchestrator.api.AppLink
import com.orchestrator.server.auth.LoginThrottle
import com.orchestrator.server.devices.DeviceStore
import com.orchestrator.server.devices.Hub
import com.orchestrator.server.devices.MessageStore
import com.orchestrator.server.tickets.Tickets
import com.orchestrator.server.util.WindowLimit
import com.orchestrator.server.auth.OidcClient
import com.orchestrator.server.auth.PendingLogins
import com.orchestrator.server.auth.SessionStore
import com.orchestrator.server.auth.UserStore
import com.orchestrator.server.db.Database
import com.orchestrator.server.db.Schema
import com.orchestrator.server.util.Http
import com.orchestrator.server.util.OutboundDns
import com.orchestrator.server.util.Passwords
import com.orchestrator.server.util.Random
import com.orchestrator.server.util.SecretBox
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Everything the server is made of, built once at start-up. */
class Services(
    val config: ServerConfig,
    val db: Database,
    val box: SecretBox,
    passwords: Passwords = Passwords(),
    val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    /**
     * The identity provider is the only place this server calls. It's the admin's own (often on
     * the home network), so private addresses are fine; the server itself only in tests.
     */
    private val outbound = OutboundDns(private = { true }, loopback = { config.outboundLoopback })

    val http: OkHttpClient = Http.createClient().newBuilder().dns(outbound).build()

    val users = UserStore(db, passwords, clock)
    val sessions = SessionStore(db, clock)
    val settings = SettingsStore(db, box)
    val oidc = OidcClient(http, clock)
    val pending = PendingLogins(clock)
    val throttle = LoginThrottle(clock = clock)

    val devices = DeviceStore(db, clock)
    val messages = MessageStore(db, clock)
    val hub = Hub()
    val tickets = Tickets(settings, clock)

    /** One person's sends and tickets a minute: plenty for every notification, not for a runaway loop. */
    val sendLimit = WindowLimit(1200, 60_000, clock)
    val ticketLimit = WindowLimit(60, 60_000, clock)

    override fun close() {
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("samo")

        val version: String by lazy {
            runCatching {
                Properties().apply { Services::class.java.getResourceAsStream("/samo-version.properties")?.use(::load) }.getProperty("version")
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\$") } ?: "dev"
        }

        /** The family's apps by the names their servers go by. */
        val KNOWN_APPS = mapOf(
            "health" to "SuperAwesomeMagicalHealth",
            "music" to "SuperAwesomeMagicalMusic",
            "video" to "SuperAwesomeMagicalVideo",
            "maps" to "SuperAwesomeMagicalMaps",
            "email" to "SuperAwesomeMagicalEmail",
        )

        /** `music=https://music.example.com,health=https://health.example.com` as a list of apps. */
        fun appsFrom(text: String): List<AppLink> = text.split(',', ' ', '\n').map { it.trim() }.filter { '=' in it }.map {
            val id = it.substringBefore('=').trim().lowercase()
            AppLink(id, KNOWN_APPS[id] ?: id.replaceFirstChar(Char::uppercase), it.substringAfter('=').trim().trimEnd('/'))
        }

        private fun ownerOnly(path: java.nio.file.Path, permissions: String) {
            runCatching { java.nio.file.Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(permissions)) }
        }

        /** Opens the data folder and database. */
        fun open(config: ServerConfig): Services {
            config.dataDir.mkdirs()
            // Only this server's own user may read the data folder (on systems that have such permissions).
            ownerOnly(config.dataDir.toPath(), "rwx------")
            val db = Database.file(config.databaseFile.path)
            ownerOnly(config.databaseFile.toPath(), "rw-------")
            Schema.migrate(db)
            return Services(config, db, SecretBox.fromFile(config.secretKeyFile))
        }
    }

    /** First start: makes the break-glass admin. Every start: applies settings given as environment variables. */
    suspend fun bootstrap() {
        if (users.count() == 0L) {
            val password = config.adminPassword ?: Random.password()
            users.create(config.adminUsername, "Administrator", Role.ADMIN, password, breakGlass = true)
            if (config.adminPassword == null) {
                // Straight to the console, never through the log: the log is kept in memory, and admins can read it.
                System.err.println(
                    "\n\n  Created the break-glass admin account.\n  Username: ${config.adminUsername}\n  Password: $password\n" +
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `samo-server reset-admin-password`.\n",
                )
                log.warn("Created the break-glass admin account {}; its password was printed once on the console", config.adminUsername)
            } else {
                log.info("Created the break-glass admin account {} with the password from SAMO_ADMIN_PASSWORD", config.adminUsername)
            }
        }
        val env = config.overrides
        if (env.isNotEmpty()) {
            fun flag(name: String): Boolean? = env[name]?.let { it.lowercase() in setOf("1", "true", "yes") }
            settings.update { current ->
                current.copy(
                    publicUrl = env["SAMO_PUBLIC_URL"]?.trimEnd('/') ?: current.publicUrl,
                    localLogin = flag("SAMO_LOCAL_LOGIN") ?: current.localLogin,
                    apps = env["SAMO_APPS"]?.let(::appsFrom) ?: current.apps,
                    oidc = current.oidc.copy(
                        enabled = if (env["SAMO_OIDC_ISSUER"] != null && env["SAMO_OIDC_CLIENT_ID"] != null) true else current.oidc.enabled,
                        issuer = env["SAMO_OIDC_ISSUER"] ?: current.oidc.issuer,
                        clientId = env["SAMO_OIDC_CLIENT_ID"]?.trim() ?: current.oidc.clientId,
                        buttonLabel = env["SAMO_OIDC_BUTTON_LABEL"] ?: current.oidc.buttonLabel,
                        userGroup = env["SAMO_OIDC_USER_GROUP"] ?: current.oidc.userGroup,
                        adminGroup = env["SAMO_OIDC_ADMIN_GROUP"] ?: current.oidc.adminGroup,
                        scopes = env["SAMO_OIDC_SCOPES"] ?: current.oidc.scopes,
                    ),
                )
            }
            env["SAMO_OIDC_CLIENT_SECRET"]?.let { settings.setOidcClientSecret(it.trim()) }
        }
        sessions.purgeExpired()
        messages.purgeExpired()
    }

    /** Sets a new password for the break-glass admin (from the command line) and returns it. */
    suspend fun resetBreakGlass(password: String?): Pair<String, String> {
        val admin = users.breakGlass() ?: users.create(config.adminUsername, "Administrator", Role.ADMIN, Random.password(), breakGlass = true)
        val newPassword = password ?: Random.password()
        users.setPassword(admin.id, newPassword)
        users.update(admin.id, role = Role.ADMIN, disabled = false)
        sessions.revokeAll(admin.id)
        return admin.username to newPassword
    }
}

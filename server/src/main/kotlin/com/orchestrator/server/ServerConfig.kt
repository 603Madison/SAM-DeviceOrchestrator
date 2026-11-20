package com.orchestrator.server

import java.io.File

/** Start-up settings, from environment variables (see README.md). */
data class ServerConfig(
    val dataDir: File,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Trust X-Forwarded-* headers from a reverse proxy in front of the server. */
    val trustProxy: Boolean = false,
    /** How many proxies in a row are in front (each adds the address it saw to X-Forwarded-For). */
    val proxies: Int = 1,
    /** A header the proxy in front sets to the visitor's address (Cloudflare's CF-Connecting-IP). */
    val clientIpHeader: String? = null,
    val adminUsername: String = "admin",
    /** The break-glass admin's password for a new server; generated (and logged once) if empty. */
    val adminPassword: String? = null,
    /** Settings applied at every start, overriding the admin page (for configuration as code). */
    val overrides: Map<String, String> = emptyMap(),
    /** Lets an identity provider on the server's own loopback be reached: for tests only (never from the environment). */
    val outboundLoopback: Boolean = false,
) {
    val databaseFile: File get() = File(dataDir, "samo.db")
    val secretKeyFile: File get() = File(dataDir, "secret.key")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            dataDir = File(env["SAMO_DATA_DIR"]?.ifBlank { null } ?: "/data"),
            host = env["SAMO_HOST"]?.ifBlank { null } ?: "0.0.0.0",
            port = env["SAMO_PORT"]?.toIntOrNull() ?: 8080,
            trustProxy = env["SAMO_TRUST_PROXY"]?.lowercase() in setOf("1", "true", "yes"),
            proxies = env["SAMO_PROXIES"]?.trim()?.toIntOrNull()?.coerceIn(1, 10) ?: 1,
            clientIpHeader = env["SAMO_CLIENT_IP_HEADER"]?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) },
            adminUsername = env["SAMO_ADMIN_USERNAME"]?.ifBlank { null } ?: "admin",
            // A placeholder copied from an example is known to everyone: a password is made instead.
            adminPassword = env["SAMO_ADMIN_PASSWORD"]?.ifBlank { null }?.takeUnless { it.trim().lowercase() in PLACEHOLDER_PASSWORDS },
            overrides = env.filterKeys { it in OVERRIDES }.filterValues { it.isNotBlank() },
        )

        /** Example passwords that must never become the admin's. */
        val PLACEHOLDER_PASSWORDS = setOf("change-me-please", "change-me", "changeme", "password", "admin", "...")

        val OVERRIDES = setOf(
            "SAMO_PUBLIC_URL",
            "SAMO_OIDC_ISSUER",
            "SAMO_OIDC_CLIENT_ID",
            "SAMO_OIDC_CLIENT_SECRET",
            "SAMO_OIDC_BUTTON_LABEL",
            "SAMO_OIDC_USER_GROUP",
            "SAMO_OIDC_ADMIN_GROUP",
            "SAMO_OIDC_SCOPES",
            "SAMO_LOCAL_LOGIN",
            "SAMO_APPS",
        )
    }
}

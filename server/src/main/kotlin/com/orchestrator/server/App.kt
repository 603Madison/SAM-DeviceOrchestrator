package com.orchestrator.server

import com.orchestrator.api.ApiJson
import com.orchestrator.api.ErrorBody
import com.orchestrator.server.admin.adminRoutes
import com.orchestrator.server.auth.authRoutes
import com.orchestrator.server.devices.deviceRoutes
import com.orchestrator.server.auth.onHttps
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

@Serializable
data class Health(val ok: Boolean, val version: String)

private val log = LoggerFactory.getLogger("samo")

/** The most a request to [path] may send, in bytes: something sealed for every device is the biggest. */
fun bodyLimitFor(path: String): Long = when (path) {
    "/api/messages" -> 4L * 1024 * 1024
    else -> 256L * 1024
}

/** The server's web app: the API under /api, and the web pages everywhere else. */
fun Application.module(services: Services) {
    install(ContentNegotiation) { json(ApiJson) }
    install(io.ktor.server.websocket.WebSockets) {
        pingPeriodMillis = 25_000
        timeoutMillis = 60_000
        maxFrameSize = 64 * 1024
    }
    install(Compression) {
        // Answers only: a compressed request could unpack to gigabytes before anything else looks at it.
        mode = io.ktor.server.plugins.compression.CompressionConfig.Mode.CompressResponse
        gzip { minimumSize(1024) }
        deflate { minimumSize(1024) }
    }
    // How much any request may send: a little for most, more for photos, device data and FIT files.
    install(io.ktor.server.plugins.bodylimit.RequestBodyLimit) {
        bodyLimit { call -> bodyLimitFor(call.request.path()) }
    }
    if (services.config.trustProxy) install(XForwardedHeaders)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        // Nothing the page links to learns where it was linked from.
        header("Referrer-Policy", "no-referrer")
        header("X-Frame-Options", "DENY")
    }
    // The page only ever talks to this server: no other site's scripts, fonts or pictures.
    install(
        io.ktor.server.application.createApplicationPlugin("Csp") {
            onCall { call ->
                call.response.header(
                    "Content-Security-Policy",
                    "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; " +
                        "font-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
                )
            }
        },
    )
    // On HTTPS, browsers keep to it for a year (they ignore this header over plain http).
    install(
        io.ktor.server.application.createApplicationPlugin("Hsts") {
            onCall { call -> if (call.onHttps(services)) call.response.header("Strict-Transport-Security", "max-age=31536000") }
        },
    )
    // Who has which devices is never kept in a browser's or a proxy's cache (a shared computer's disk, say).
    install(
        io.ktor.server.application.createApplicationPlugin("NoStoreApi") {
            // At the end, where a route hasn't said otherwise (pictures and video say their own).
            onCallRespond { call, _ ->
                if (call.request.path().startsWith("/api/") && call.response.headers[HttpHeaders.CacheControl] == null) {
                    call.response.header(HttpHeaders.CacheControl, "private, no-store")
                }
            }
        },
    )
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path.startsWith("/api/") && path != "/api/health" && path != "/api/orchestrator/keys"
        }
    }
    install(StatusPages) {
        exception<ApiError> { call, error -> call.respond(error.status, ErrorBody(error.message ?: "Error")) }
        exception<io.ktor.server.plugins.PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody("That's more than this server takes at once"))
        }
        exception<BadRequestException> { call, error ->
            val cause = generateSequence(error as Throwable) { it.cause }.firstOrNull { it is SerializationException }
            call.respond(HttpStatusCode.BadRequest, ErrorBody(cause?.message?.substringBefore('\n')?.let { "That request isn't right: $it" } ?: "That request isn't right"))
        }
        exception<Throwable> { call, error ->
            // A connection that ended (a closed tab, a phone going out of range) isn't a fault.
            if (error is kotlinx.coroutines.CancellationException) return@exception
            log.error("Failed: ${call.request.path()}", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Something went wrong on the server"))
        }
    }

    routing {
        get("/api/health") { call.respond(Health(true, Services.version)) }
        authRoutes(services)
        adminRoutes(services)
        deviceRoutes(services)
        route("/api/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ErrorBody("No such API")) }
        }

        // The web app. Its files carry hashes in their names, so they're cached for good; the
        // page itself is always checked, so an updated server is picked up at once.
        if (Services::class.java.getResource("/web/index.html") != null) {
            staticResources("/", "web") {
                default("index.html")
                cacheControl { url ->
                    if (url.path.contains("/assets/")) listOf(CacheControl.MaxAge(31_536_000, visibility = CacheControl.Visibility.Public))
                    else listOf(CacheControl.NoCache(null))
                }
            }
        } else {
            get("/") {
                call.respondText(
                    "The SuperAwesomeMagicalOrchestrator server is running, but this build has no web app (build web/ first).",
                    ContentType.Text.Plain,
                )
            }
        }
    }
}

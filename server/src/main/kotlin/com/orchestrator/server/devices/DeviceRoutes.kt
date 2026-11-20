package com.orchestrator.server.devices

import com.orchestrator.api.Ack
import com.orchestrator.api.ApiJson
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceChange
import com.orchestrator.api.DeviceRegistration
import com.orchestrator.api.DeviceStatus
import com.orchestrator.api.Discovery
import com.orchestrator.api.OutgoingMessage
import com.orchestrator.api.SentMessage
import com.orchestrator.api.SessionKind
import com.orchestrator.api.StreamFrame
import com.orchestrator.api.TicketRequest
import com.orchestrator.server.ApiError
import com.orchestrator.server.Services
import com.orchestrator.server.auth.Principal
import com.orchestrator.server.auth.principal
import com.orchestrator.server.auth.publicBase
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("samo.devices")

/** A person's devices as [currentId] sees them: which are connected, and which one it is. */
suspend fun Services.deviceList(userId: String, currentId: String? = null): List<Device> =
    devices.list(userId).map { it.copy(online = hub.online(it.id), current = it.id == currentId) }

/** Tells a person's connected devices that their list of devices changed. */
suspend fun Services.announceDevices(userId: String) {
    val all = devices.list(userId).map { it.copy(online = hub.online(it.id)) }
    hub.devicesChanged(userId) { current -> all.map { it.copy(current = it.id == current) } }
}

private suspend fun ApplicationCall.signedIn(services: Services): Principal = principal(services) ?: throw ApiError.unauthorized()

/** The device asking: an app sign-in that has registered. */
private suspend fun ApplicationCall.device(services: Services): Pair<Principal, Device> {
    val principal = signedIn(services)
    val device = services.devices.bySession(principal.session.id) ?: throw ApiError.forbidden("This device hasn't registered yet")
    return principal to device
}

fun Route.deviceRoutes(services: Services) {
    // For the family's servers: who this is, and the keys its sign-in tickets are checked with.
    get("/api/orchestrator") {
        val base = publicBase(services, call)
        val settings = services.settings.get()
        call.respond(Discovery(settings.serverName, Services.version, base, "$base/api/orchestrator/keys", settings.apps.map { it.id }))
    }
    get("/api/orchestrator/keys") {
        call.response.headers.append("Cache-Control", "public, max-age=300")
        call.respondText(services.tickets.publicKeys(), ContentType.Application.Json)
    }

    get("/api/apps") {
        call.signedIn(services)
        call.respond(services.settings.get().apps)
    }

    // A ticket to sign in to one of the family's servers as the person signed in on this device.
    post("/api/tickets") {
        val (principal, device) = call.device(services)
        if (principal.user.breakGlass) throw ApiError.forbidden("The break-glass admin is for emergencies: sign in as yourself to use the apps")
        if (!services.ticketLimit.allow(principal.user.id)) throw ApiError.tooMany("That's a lot of sign-ins at once. Try again in a minute.")
        val wanted = call.receive<TicketRequest>().app.trim().lowercase()
        val app = services.settings.get().apps.firstOrNull { it.id == wanted }
            ?: throw ApiError.notFound("This orchestrator doesn't know where $wanted is (an admin lists the apps)")
        val who = services.users.identity(principal.user.id) ?: throw ApiError.unauthorized()
        log.info("Ticket for {} to {} from {}", principal.user.username, app.id, device.name)
        call.respond(services.tickets.issue(publicBase(services, call), app, who, device))
    }

    route("/api/devices") {
        get {
            val principal = call.signedIn(services)
            call.respond(services.deviceList(principal.user.id, services.devices.bySession(principal.session.id)?.id))
        }
        // The app, once signed in, says what it is and gives its key. Again to change either.
        post {
            val principal = call.signedIn(services)
            if (principal.session.kind != SessionKind.APP) throw ApiError.forbidden("Only a signed-in device registers")
            val device = services.devices.register(principal.user.id, principal.session.id, call.receive<DeviceRegistration>())
            services.announceDevices(principal.user.id)
            call.respond(device.copy(online = services.hub.online(device.id), current = true))
        }
        get("/me") {
            val (_, device) = call.device(services)
            call.respond(device.copy(online = services.hub.online(device.id), current = true))
        }
        post("/me/status") {
            val (principal, device) = call.device(services)
            services.devices.seen(device.id, call.receive<DeviceStatus>().battery)
            services.announceDevices(principal.user.id)
            call.respond(mapOf("ok" to true))
        }
        route("/{id}") {
            patch {
                val principal = call.signedIn(services)
                val device = services.devices.rename(principal.user.id, call.parameters["id"].orEmpty(), call.receive<DeviceChange>().name)
                services.announceDevices(principal.user.id)
                call.respond(device.copy(online = services.hub.online(device.id)))
            }
            // Removing a device signs it out: its token stops working, and what was waiting for it goes.
            delete {
                val principal = call.signedIn(services)
                val id = call.parameters["id"].orEmpty()
                val session = services.devices.sessionOf(principal.user.id, id) ?: throw ApiError.notFound("No such device")
                services.sessions.revoke(principal.user.id, session)
                services.hub.drop(id)
                services.announceDevices(principal.user.id)
                call.respond(mapOf("ok" to true))
            }
        }
    }

    route("/api/messages") {
        // Sends something to some of the person's devices: from a device, or from the web page.
        post {
            val principal = call.signedIn(services)
            if (!services.sendLimit.allow(principal.user.id)) throw ApiError.tooMany("That's a lot at once. Try again in a minute.")
            val from = services.devices.bySession(principal.session.id)
            val outgoing = call.receive<OutgoingMessage>()
            if (outgoing.to.isEmpty()) throw ApiError.badRequest("Nobody to send it to")
            if (outgoing.to.size > DeviceStore.MAX_DEVICES) throw ApiError.badRequest("That's too many devices")
            val collapse = outgoing.collapseKey?.takeIf { it.isNotBlank() }?.take(64)
            val mine = services.devices.list(principal.user.id).associateBy { it.id }
            outgoing.to.forEach { (id, sealed) ->
                if (id !in mine) throw ApiError.notFound("No such device")
                if (sealed.ct.length > MAX_SEALED || sealed.epk.length > 128 || sealed.nonce.length > 32) throw ApiError.badRequest("That's too big to send")
            }
            val keepMs = services.settings.get().keepHours.coerceIn(1, 24 * 30) * 3_600_000L
            val ttl = (outgoing.ttlSeconds.coerceIn(10, 30L * 24 * 3600) * 1000).coerceAtMost(keepMs)
            val delivered = mutableListOf<String>()
            val queued = mutableListOf<String>()
            outgoing.to.forEach { (id, sealed) ->
                val message = services.messages.add(id, from?.id, from?.name ?: "The web page", sealed, ttl, collapse)
                if (services.hub.deliver(id, message)) delivered += id else queued += id
            }
            from?.let { services.devices.seen(it.id) }
            call.respond(SentMessage(delivered, queued))
        }
        // For a device that can't keep a stream open: what's waiting, to fetch now and then.
        get {
            val (_, device) = call.device(services)
            services.devices.seen(device.id)
            call.respond(services.messages.waiting(device.id))
        }
        post("/ack") {
            val (_, device) = call.device(services)
            services.messages.acknowledge(device.id, call.receive<Ack>().ids)
            call.respond(mapOf("ok" to true))
        }
    }

    // A device's live line: what's for it arrives at once, and the others see it as connected.
    webSocket("/api/stream") {
        val principal = try {
            call.principal(services)
        } catch (e: ApiError) {
            null
        }
        val device = principal?.let { services.devices.bySession(it.session.id) }
        if (principal == null || device == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Sign in and register first"))
            return@webSocket
        }
        val userId = principal.user.id
        val connection = services.hub.connect(userId, device.id)
        val sender = launch {
            connection.frames.consumeEach { send(Frame.Text(ApiJson.encodeToString(StreamFrame.serializer(), it))) }
            // The hub closed it: signed out, or the device connected again somewhere else.
            close(CloseReason(CloseReason.Codes.NORMAL, "Closed"))
        }
        try {
            services.devices.seen(device.id)
            services.announceDevices(userId)
            services.messages.waiting(device.id).forEach { connection.frames.send(StreamFrame.Message(it)) }
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val parsed = try {
                    ApiJson.decodeFromString(StreamFrame.serializer(), frame.readText())
                } catch (e: SerializationException) {
                    log.debug("A frame from {} that isn't one: {}", device.name, e.message)
                    continue
                } catch (e: IllegalArgumentException) {
                    log.debug("A frame from {} that isn't one: {}", device.name, e.message)
                    continue
                }
                // A device that was signed out mid-stream stops here.
                if (services.devices.bySession(principal.session.id) == null) break
                when (parsed) {
                    is StreamFrame.Acked -> services.messages.acknowledge(device.id, parsed.ids)
                    is StreamFrame.Status -> {
                        services.devices.seen(device.id, parsed.battery)
                        services.announceDevices(userId)
                    }
                    is StreamFrame.Ping -> connection.frames.trySend(StreamFrame.Ping)
                    else -> {}
                }
            }
        } finally {
            sender.cancel()
            services.hub.disconnect(connection)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                services.devices.seen(device.id)
                services.announceDevices(userId)
            }
        }
    }
}

/** The most one sealed message may be, in base64 characters (about 48 KB of content). */
const val MAX_SEALED = 65_536

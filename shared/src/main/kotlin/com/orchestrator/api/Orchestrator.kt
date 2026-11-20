package com.orchestrator.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Devices ----

@Serializable
enum class DeviceKind { LINUX, ANDROID, OTHER }

@Serializable
data class Battery(val level: Int, val charging: Boolean = false)

/** One of a person's signed-in devices. */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    /** "Pixel 9", "ThinkPad X1": whatever the device calls itself. */
    val model: String = "",
    /** The device's public key (P-256, uncompressed point, base64): what's sent to it is sealed for this. */
    val publicKey: String,
    /** What it can do with what it's sent: see [Capabilities]. */
    val capabilities: List<String> = emptyList(),
    val online: Boolean = false,
    val lastSeenAt: Long,
    val createdAt: Long,
    val battery: Battery? = null,
    /** The device asking. */
    val current: Boolean = false,
)

object Capabilities {
    const val NOTIFICATIONS = "notifications"
    const val CLIPBOARD = "clipboard"
    const val LINKS = "links"
    const val RING = "ring"
    const val ACTIVITY = "activity"
    const val MEDIA = "media"
}

@Serializable
data class DeviceRegistration(
    val name: String,
    val kind: DeviceKind,
    val model: String = "",
    val publicKey: String,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class DeviceChange(val name: String)

@Serializable
data class DeviceStatus(val battery: Battery? = null)

// ---- The family's apps, and signing in to them ----

/** One of the family's servers, as the admin listed it: where a signed-in device finds it. */
@Serializable
data class AppLink(
    /** "music", "health", "video", "maps", "email": what the app calls itself. */
    val id: String,
    val name: String,
    /** The server's public address, e.g. https://music.example.com: also the ticket's audience. */
    val url: String,
)

@Serializable
data class TicketRequest(val app: String)

/**
 * A sign-in ticket for one app's server: a short-lived signed statement of who is signed in on
 * the device, which the app hands to its server (`POST <url>/api/auth/orchestrator`) for a token
 * of its own.
 */
@Serializable
data class Ticket(val app: String, val url: String, val ticket: String, val expiresAt: Long)

/** What `GET /api/orchestrator` says, for app servers that trust this orchestrator. */
@Serializable
data class Discovery(
    val name: String,
    val version: String,
    /** The `iss` of every ticket. */
    val issuer: String,
    val jwksUri: String,
    val apps: List<String> = emptyList(),
)

// ---- Messages between devices ----

/**
 * Something sealed for one device: only it can read it (see [Seal]). The server passes these on
 * and keeps them until the device has them; it never sees what's inside.
 */
@Serializable
data class Sealed(
    /** The sender's one-time public key (P-256, uncompressed point, base64). */
    val epk: String,
    /** 12 bytes, base64. */
    val nonce: String,
    /** AES-256-GCM, tag at the end, base64. */
    val ct: String,
)

@Serializable
data class OutgoingMessage(
    /** Device id to what's sealed for it. */
    val to: Map<String, Sealed>,
    /** How long the server keeps it for a device that's away, in seconds. */
    val ttlSeconds: Long = 24 * 3600,
    /** A newer message with the same key replaces an undelivered older one from this device (a clipboard, a now-playing line). */
    val collapseKey: String? = null,
)

@Serializable
data class SentMessage(val delivered: List<String>, val queued: List<String>)

@Serializable
data class IncomingMessage(
    val id: String,
    /** The sending device; null from the web page. */
    val from: String? = null,
    val fromName: String = "",
    val sentAt: Long,
    val sealed: Sealed,
)

@Serializable
data class Ack(val ids: List<String>)

/** What a device's stream (`/api/stream`, a WebSocket) carries, each a JSON text frame. */
@Serializable
sealed interface StreamFrame {
    /** Server to device: something for it. Answer with [Acked]. */
    @Serializable
    @SerialName("message")
    data class Message(val message: IncomingMessage) : StreamFrame

    /** Server to device: the list of devices changed (one came, went, or was renamed). */
    @Serializable
    @SerialName("devices")
    data class Devices(val devices: List<Device>) : StreamFrame

    /** Device to server: these arrived, forget them. */
    @Serializable
    @SerialName("ack")
    data class Acked(val ids: List<String>) : StreamFrame

    /** Device to server: how it is. */
    @Serializable
    @SerialName("status")
    data class Status(val battery: Battery? = null) : StreamFrame

    /** Either way, to keep the connection open through proxies. */
    @Serializable
    @SerialName("ping")
    data object Ping : StreamFrame
}

// ---- What's inside a sealed message ----

/**
 * What devices say to each other, as JSON before sealing (`{"type":"notification",…}`). New
 * kinds can be added: a device skips what it doesn't know.
 */
@Serializable
sealed interface Payload {
    /** A notification shown on the sending device, to show here too. */
    @Serializable
    @SerialName("notification")
    data class Notification(
        /** The same on the sending device for as long as the notification lives: [Dismiss] names it. */
        val key: String,
        /** "Signal", "SuperAwesomeMagicalEmail". */
        val app: String,
        /** The Android package or desktop entry, for telling apps apart. */
        val appId: String = "",
        val title: String = "",
        val text: String = "",
        val at: Long,
        /** A small PNG, base64 (64 px at most). */
        val icon: String? = null,
    ) : Payload

    /** The notification [key] was dismissed there: take it away here. */
    @Serializable
    @SerialName("dismiss")
    data class Dismiss(val key: String) : Payload

    /** Put this on the clipboard. */
    @Serializable
    @SerialName("clipboard")
    data class Clipboard(val text: String) : Payload

    /** Open this (a web address or an app link). */
    @Serializable
    @SerialName("link")
    data class Link(val url: String, val title: String = "") : Payload

    /** Make a noise until someone finds you; [on] false stops it. */
    @Serializable
    @SerialName("ring")
    data class Ring(val on: Boolean = true) : Payload

    /**
     * What the person is doing in an app on the sending device, to carry on with here: a song,
     * a video, a page, a route. [url] opens it; null [title] means they stopped.
     */
    @Serializable
    @SerialName("activity")
    data class Activity(
        /** "music", "video", "browser", "maps", "email". */
        val app: String,
        val title: String? = null,
        val detail: String = "",
        val url: String = "",
        val at: Long,
    ) : Payload

    /** Play, pause or skip what's playing on the receiving device. */
    @Serializable
    @SerialName("media")
    data class Media(
        /** "play", "pause", "toggle", "next", "previous". */
        val action: String,
    ) : Payload
}

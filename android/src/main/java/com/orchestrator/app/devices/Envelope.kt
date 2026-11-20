package com.orchestrator.app.devices

import com.orchestrator.api.Capabilities
import com.orchestrator.api.Device
import com.orchestrator.api.OutgoingMessage
import com.orchestrator.api.Payload
import com.orchestrator.api.Seal
import com.orchestrator.app.attempt

/** Sealing a payload for the devices it's for, with how long the server keeps it (docs/PROTOCOL.md, "Sending"). */
object Envelope {
    /** What a device must be able to do to be sent [payload]. */
    fun capability(payload: Payload): String = when (payload) {
        is Payload.Notification, is Payload.Dismiss -> Capabilities.NOTIFICATIONS
        is Payload.Clipboard -> Capabilities.CLIPBOARD
        is Payload.Link -> Capabilities.LINKS
        is Payload.Ring -> Capabilities.RING
        is Payload.Activity -> Capabilities.ACTIVITY
        is Payload.Media -> Capabilities.MEDIA
    }

    fun ttlSeconds(payload: Payload): Long = when (payload) {
        is Payload.Notification, is Payload.Dismiss -> 86_400
        is Payload.Clipboard -> 600
        is Payload.Link -> 604_800
        is Payload.Ring -> 300
        is Payload.Activity -> 900
        is Payload.Media -> 60
    }

    /** A newer one replaces an older one still waiting: the clipboard, what's happening in an app. */
    fun collapseKey(payload: Payload): String? = when (payload) {
        is Payload.Clipboard -> "clipboard"
        is Payload.Activity -> "activity:${payload.app}"
        is Payload.Ring -> "ring"
        else -> null
    }

    /** The devices [payload] goes to: the others that can take it, or only those in [only]. */
    fun recipients(payload: Payload, devices: List<Device>, selfId: String, only: Set<String>? = null): List<Device> {
        val wanted = capability(payload)
        return devices.filter { it.id != selfId && !it.current && wanted in it.capabilities && (only == null || it.id in only) }
    }

    /** One sealed copy for each of [to]; null when there's nobody. A device whose key isn't one is left out. */
    fun seal(payload: Payload, to: List<Device>): OutgoingMessage? {
        val sealed = to.mapNotNull { device -> attempt { device.id to Seal.seal(payload, device.publicKey, device.id) }.getOrNull() }.toMap()
        if (sealed.isEmpty()) return null
        return OutgoingMessage(sealed, ttlSeconds(payload), collapseKey(payload))
    }
}

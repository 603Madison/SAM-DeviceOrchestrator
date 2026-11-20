package com.orchestrator.app.ui

import com.orchestrator.api.Capabilities
import com.orchestrator.api.Device
import com.orchestrator.app.devices.Happening

/** How things are written out, as on the web app. Pure, so they're tested on their own. */
object Format {
    /** "Just now", "5 minutes ago", "3 days ago", "2 years ago". */
    fun ago(at: Long?, now: Long = System.currentTimeMillis()): String? {
        if (at == null || at <= 0) return null
        val seconds = ((now - at) / 1000).coerceAtLeast(0)
        fun say(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> say(seconds / 60, "minute")
            seconds < 86_400 -> say(seconds / 3600, "hour")
            seconds < 7 * 86_400 -> say(seconds / 86_400, "day")
            seconds < 30 * 86_400 -> say(seconds / (7 * 86_400), "week")
            seconds < 365 * 86_400 -> say(seconds / (30 * 86_400), "month")
            else -> say(seconds / (365 * 86_400), "year")
        }
    }

    /** "81%, charging". */
    fun battery(device: Device): String? = device.battery?.let { "${it.level}%" + if (it.charging) ", charging" else "" }

    /** "Online · 81%, charging", or "Last seen 2 hours ago". */
    fun status(device: Device, now: Long = System.currentTimeMillis()): String =
        if (device.online) listOfNotNull("Online", battery(device)).joinToString(" · ")
        else ago(device.lastSeenAt, now)?.let { if (it == "Just now") "Seen just now" else "Last seen $it" } ?: "Not connected"

    /** "Now playing: Clair de lune · Music on Maddie's laptop". */
    fun carryOn(h: Happening): String = "${h.title} · ${appName(h.app)} on ${h.deviceName}"

    /** "music" as people say it. */
    fun appName(id: String): String = id.replaceFirstChar { it.uppercase() }

    /** What a device does with what it's sent, in words. */
    fun can(capability: String): String? = when (capability) {
        Capabilities.NOTIFICATIONS -> "Shows your notifications"
        Capabilities.CLIPBOARD -> "Takes text for its clipboard"
        Capabilities.LINKS -> "Opens links"
        Capabilities.RING -> "Rings to be found"
        Capabilities.ACTIVITY -> "Carries on what you're doing"
        Capabilities.MEDIA -> "Plays and pauses"
        else -> null
    }
}

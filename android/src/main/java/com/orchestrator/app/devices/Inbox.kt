package com.orchestrator.app.devices

import android.content.SharedPreferences
import androidx.core.content.edit
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.Payload
import com.orchestrator.api.Seal
import com.orchestrator.app.attempt
import com.orchestrator.app.data.Settings

/** What this phone does with each kind of thing another device sends. */
interface Effects {
    fun notification(from: String, payload: Payload.Notification)
    fun dismiss(key: String)
    fun clipboard(messageId: String, from: String, text: String)
    fun link(messageId: String, from: String, link: Payload.Link)
    fun ring(from: String, on: Boolean)
    fun media(action: String)
}

/**
 * Opens what arrives for this phone and acts on it. Everything is dealt with exactly once: the
 * server sends a message again until it's acknowledged, so notifications go by their key and the
 * rest by the message's id.
 */
class Inbox(
    private val settings: () -> Settings,
    private val effects: Effects,
    private val activities: Activities,
    private val seen: SharedPreferences?,
) {
    private val recent = LinkedHashSet<String>(seen?.getString(KEY, "").orEmpty().split(',').filter { it.isNotEmpty() })

    /** Deals with [message]. True when it's done with (acknowledge it), false to be sent it again later. */
    @Synchronized
    fun receive(message: IncomingMessage): Boolean {
        val s = settings()
        val keys = s.keys() ?: return false
        // Not sealed for this phone's key, or changed on the way: nothing can ever be done with it.
        val payload = attempt { Seal.openPayload(message.sealed, keys, s.deviceId) }.getOrElse { return true } ?: return true
        val from = message.fromName.ifBlank { "Another device" }
        if (payload !is Payload.Notification && payload !is Payload.Dismiss && !firstTime(message.id)) return true
        when (payload) {
            is Payload.Notification -> if (s.showIncoming) effects.notification(from, payload)
            is Payload.Dismiss -> effects.dismiss(payload.key)
            is Payload.Clipboard -> effects.clipboard(message.id, from, payload.text)
            is Payload.Link -> effects.link(message.id, from, payload)
            is Payload.Ring -> effects.ring(from, payload.on)
            is Payload.Activity -> activities.heard(message.from.orEmpty(), from, payload)
            is Payload.Media -> effects.media(payload.action)
        }
        return true
    }

    private fun firstTime(id: String): Boolean {
        if (!recent.add(id)) return false
        while (recent.size > REMEMBER) recent.remove(recent.first())
        seen?.edit { putString(KEY, recent.joinToString(",")) }
        return true
    }

    private companion object {
        const val KEY = "ids"
        const val REMEMBER = 300
    }
}

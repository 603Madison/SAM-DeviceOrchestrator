package com.orchestrator.app.devices

import com.orchestrator.api.ApiJson
import com.orchestrator.api.Capabilities
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.Payload
import com.orchestrator.api.Seal
import com.orchestrator.api.Sealed
import com.orchestrator.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads through [Seal], as the app sends and receives them. */
class SealTest {
    private val all = listOf(Capabilities.NOTIFICATIONS, Capabilities.CLIPBOARD, Capabilities.LINKS, Capabilities.RING, Capabilities.ACTIVITY, Capabilities.MEDIA)

    private class Peer(val id: String, caps: List<String>, current: Boolean = false) {
        val keys = Seal.newKeyPair()
        val device = Device(id, "Device $id", DeviceKind.LINUX, publicKey = Seal.publicKeyText(keys.public), capabilities = caps, lastSeenAt = 0, createdAt = 0, current = current)
    }

    private val payloads = listOf(
        Payload.Notification("0|org.signal|7|null|10123", "Signal", "org.signal", "Maddie", "On my way — 5 min 🚲", 1_700_000_000_000, icon = "iVBORw0KGgo="),
        Payload.Dismiss("0|org.signal|7|null|10123"),
        Payload.Clipboard("hello from the aurora\nsecond line"),
        Payload.Link("https://example.com/a?b=c", "A page"),
        Payload.Ring(true), Payload.Ring(false),
        Payload.Activity("music", "Clair de lune", "Debussy", "sammusic://track/1", 5),
        Payload.Activity("music", null, at = 6),
        Payload.Media("toggle"),
    )

    @Test
    fun `every payload comes back as it went`() {
        val phone = Peer("phone", all)
        payloads.forEach { payload ->
            val sealed = Seal.seal(payload, phone.device.publicKey, phone.id)
            assertEquals(payload, Seal.openPayload(sealed, phone.keys, phone.id))
            assertFalse("The words aren't in what the server sees", payload is Payload.Clipboard && sealed.ct.contains("aurora"))
        }
    }

    @Test
    fun `the protocol's test vector opens`() {
        val keys = Seal.keyPair("fPVfWTT+lmpkhYZJbYmgJjnb2Yk2z5N40jybPxGus/g=", "BLjLkiLeA9LQO1HSvascpiMSjqDzL48RyznPjsAfHRkwvfHZWmiCDUIt1/tIu0HcMP5IJjCNMrVxG2v6lxnHJic=")
        val sealed = Sealed(
            "BCb8iyUOM3J9h1vLpRI9Fb3lmP+DrST3xeOkuCKM5hRqMMj7XwcscLCPmJQH1FOH5SM9QZNPDYbkJI6ZTey43pQ=",
            "od4tYckzNodlv4sX",
            "wy4elJA1eGU8oFGPEuH4GkjWfSz0kD/j2Eq6oP97idy//cg4UoNeWX//frfkREIWSOpTXNp+tYXjJqaMcXGOB+hiqw==",
        )
        assertEquals(Payload.Clipboard("hello from the aurora"), Seal.openPayload(sealed, keys, "device-1"))
    }

    @Test
    fun `sealed for one device, it opens for no other, nor under another id`() {
        val a = Peer("a", all)
        val b = Peer("b", all)
        val sealed = Seal.seal(Payload.Clipboard("x"), a.device.publicKey, a.id)
        assertTrue(runCatching { Seal.openPayload(sealed, b.keys, a.id) }.isFailure)
        assertTrue(runCatching { Seal.openPayload(sealed, a.keys, "b") }.isFailure)
        assertTrue(runCatching { Seal.openPayload(sealed.copy(ct = sealed.ct.reversed()), a.keys, a.id) }.isFailure)
    }

    @Test
    fun `a newer kind of payload is skipped, not an error`() {
        val a = Peer("a", all)
        val sealed = Seal.seal("""{"type":"teleport","to":"mars"}""".toByteArray(), a.device.publicKey, a.id)
        assertNull(Seal.openPayload(sealed, a.keys, a.id))
    }

    @Test
    fun `the envelope has a copy for each device that can take it, and never this one`() {
        val me = Peer("me", all, current = true)
        val laptop = Peer("laptop", all)
        val tablet = Peer("tablet", listOf(Capabilities.CLIPBOARD))
        val devices = listOf(me.device, laptop.device, tablet.device)

        val clip = Payload.Clipboard("copy me")
        val message = Envelope.seal(clip, Envelope.recipients(clip, devices, "me"))!!
        assertEquals(setOf("laptop", "tablet"), message.to.keys)
        assertEquals(600L, message.ttlSeconds)
        assertEquals("clipboard", message.collapseKey)
        assertEquals(clip, Seal.openPayload(message.to.getValue("laptop"), laptop.keys, "laptop"))
        assertEquals(clip, Seal.openPayload(message.to.getValue("tablet"), tablet.keys, "tablet"))
        assertNotEquals(message.to.getValue("laptop").ct, message.to.getValue("tablet").ct)

        val notification = payloads[0]
        assertEquals(listOf("laptop"), Envelope.recipients(notification, devices, "me").map { it.id })
        assertNull(Envelope.seal(notification, Envelope.recipients(notification, devices, "me")).also { assertEquals(setOf("laptop"), it!!.to.keys) }?.collapseKey)
        assertEquals(listOf("tablet"), Envelope.recipients(clip, devices, "me", only = setOf("tablet", "me")).map { it.id })
        assertNull(Envelope.seal(Payload.Ring(), emptyList()))
        // A device whose key isn't a point on the curve is left out rather than sealed for.
        val bad = laptop.device.copy(id = "bad", publicKey = "AAAA")
        assertEquals(setOf("laptop"), Envelope.seal(clip, listOf(laptop.device, bad))!!.to.keys)
    }

    @Test
    fun `how long the server keeps each kind, and which replace their older selves`() {
        val ttl = payloads.associate { it::class.simpleName to Envelope.ttlSeconds(it) }
        assertEquals(mapOf("Notification" to 86_400L, "Dismiss" to 86_400L, "Clipboard" to 600L, "Link" to 604_800L, "Ring" to 300L, "Activity" to 900L, "Media" to 60L), ttl)
        assertEquals("activity:music", Envelope.collapseKey(payloads[6]))
        assertNull(Envelope.collapseKey(payloads[0]))
        assertNull(Envelope.collapseKey(payloads[3]))
    }

    private class Recorder : Effects {
        val seen = mutableListOf<String>()
        override fun notification(from: String, payload: Payload.Notification) { seen += "notification ${payload.key} from $from" }
        override fun dismiss(key: String) { seen += "dismiss $key" }
        override fun clipboard(messageId: String, from: String, text: String) { seen += "clipboard $text" }
        override fun link(messageId: String, from: String, link: Payload.Link) { seen += "link ${link.url}" }
        override fun ring(from: String, on: Boolean) { seen += "ring $on" }
        override fun media(action: String) { seen += "media $action" }
    }

    @Test
    fun `the inbox opens what arrives, acts once, and remembers what's happening elsewhere`() {
        val me = Peer("me", all)
        var settings = Settings(server = "https://s", token = "t", deviceId = "me", publicKey = me.device.publicKey, privateKey = Seal.privateKeyText(me.keys.private))
        val effects = Recorder()
        var now = 1_000L
        val activities = Activities(null) { now }
        val inbox = Inbox({ settings }, effects, activities, null)
        fun message(id: String, payload: Payload) = IncomingMessage(id, "laptop", "Maddie's laptop", 0, Seal.seal(payload, me.device.publicKey, "me"))

        payloads.forEachIndexed { i, p -> assertTrue(inbox.receive(message("m$i", p))) }
        assertEquals(
            listOf("notification 0|org.signal|7|null|10123 from Maddie's laptop", "dismiss 0|org.signal|7|null|10123", "clipboard hello from the aurora\nsecond line", "link https://example.com/a?b=c", "ring true", "ring false", "media toggle"),
            effects.seen,
        )
        // The activity was heard, then stopped.
        assertTrue(activities.current().isEmpty())
        inbox.receive(message("a1", Payload.Activity("video", "A film", url = "samvideo://watch/1", at = 1)))
        assertEquals("A film", activities.current().single().title)
        assertEquals("Maddie's laptop", activities.current().single().deviceName)
        now += Activities.KEEP_MS
        assertTrue(activities.current().isEmpty())

        // Sent again before the acknowledgement arrived: acknowledged, not done twice.
        effects.seen.clear()
        assertTrue(inbox.receive(message("m2", payloads[2])))
        assertTrue(inbox.receive(message("m4", payloads[4])))
        assertTrue(effects.seen.isEmpty())
        // A notification again is shown again (it replaces itself by key).
        assertTrue(inbox.receive(message("m0", payloads[0])))
        assertEquals(1, effects.seen.size)

        // Not for this phone's key: nothing to do with it, ever, so it's acknowledged and dropped.
        val other = Peer("other", all)
        effects.seen.clear()
        assertTrue(inbox.receive(IncomingMessage("x", "laptop", "L", 0, Seal.seal(Payload.Ring(), other.device.publicKey, "other"))))
        assertTrue(inbox.receive(IncomingMessage("y", "laptop", "L", 0, Sealed("AAAA", "AAAA", "AAAA"))))
        assertTrue(effects.seen.isEmpty())

        // "Show other devices' notifications here" off: they aren't shown, the rest still works.
        settings = settings.copy(showIncoming = false)
        inbox.receive(message("n9", payloads[0]))
        inbox.receive(message("r9", Payload.Ring(true)))
        assertEquals(listOf("ring true"), effects.seen)
        assertFalse(Capabilities.NOTIFICATIONS in settings.capabilities)
        assertEquals(ApiJson.encodeToString(Payload.serializer(), Payload.Ring(true)), """{"type":"ring","on":true}""")
    }
}

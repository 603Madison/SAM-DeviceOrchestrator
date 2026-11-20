package com.orchestrator.app.link

import com.orchestrator.api.Payload
import java.net.URI
import java.net.URISyntaxException

/**
 * Which links this phone will open when another device (or one of the family's apps) sends one.
 * What arrives was sealed by one of the person's own devices, but it's still only ever opened if
 * it's a web address or one of the family's own app links: never `intent:`, `file:`, `content:`,
 * `javascript:`, `tel:` or anything else that does something rather than shows something.
 */
object Links {
    /** The family's app links, e.g. `samvideo://watch/…`. */
    val FAMILY = setOf("samvideo", "sammusic", "samemail", "samaps", "samhealth")

    enum class Kind { WEB, FAMILY }

    private const val MAX = 4096

    /** What kind of link [raw] is, or null when it isn't one this phone opens. */
    fun kind(raw: String?): Kind? {
        val text = raw ?: return null
        if (text.isEmpty() || text.length > MAX || text != text.trim()) return null
        // Nothing invisible, nothing that could split or hide part of the address.
        if (text.any { it.isWhitespace() || it.isISOControl() || it == '\\' || Character.getType(it) == Character.FORMAT.toInt() }) return null
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        return when {
            scheme == "http" || scheme == "https" -> Kind.WEB.takeIf { !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
            // An app link names a place in the app (samvideo://watch/abc); `samvideo:whatever` isn't one.
            scheme in FAMILY -> Kind.FAMILY.takeIf { !uri.isOpaque && !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null }
            else -> null
        }
    }

    fun allowed(raw: String?): Boolean = kind(raw) != null

    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /**
     * What sharing [text] to a device sends: a link when it's a web address (or a short line with
     * one in it, as many apps share "Title https://…"), otherwise the text for the clipboard.
     */
    fun toSend(text: String, subject: String? = null): Payload? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (kind(trimmed) == Kind.WEB) return Payload.Link(trimmed, subject?.trim().orEmpty().take(200))
        val found = URL.findAll(trimmed).toList()
        if (found.size == 1) {
            val url = found[0].value.trimEnd('.', ',', ')', ']', '"', '\'')
            val rest = trimmed.replace(found[0].value, " ").trim()
            if (rest.length <= 200 && kind(url) == Kind.WEB) return Payload.Link(url, rest.ifBlank { subject?.trim().orEmpty() }.take(200))
        }
        return Payload.Clipboard(trimmed)
    }
}

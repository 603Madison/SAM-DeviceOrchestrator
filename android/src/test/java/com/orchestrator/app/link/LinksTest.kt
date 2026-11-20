package com.orchestrator.app.link

import com.orchestrator.api.Payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun `web addresses and the family's app links open`() {
        listOf("https://example.com", "http://10.0.0.5:8080/x?y=1#z", "HTTPS://Example.com/a%20b", "https://[::1]:8443/").forEach {
            assertEquals(it, Links.Kind.WEB, Links.kind(it))
        }
        listOf("samvideo://watch/pt%3Aabc", "sammusic://album/1", "samemail://message/abc", "samaps://route?to=1,2", "samhealth://today", "SamVideo://watch/1").forEach {
            assertEquals(it, Links.Kind.FAMILY, Links.kind(it))
        }
    }

    @Test
    fun `nothing else does`() {
        val hostile = listOf(
            null, "", " ", "example.com", "//example.com", "/etc/passwd",
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "intent:#Intent;action=android.intent.action.CALL;S.browser_fallback_url=https://evil.example;end",
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>alert(1)</script>",
            "file:///data/data/app.superawesomemagical.orchestrator/shared_prefs/approvals.xml",
            "content://app.superawesomemagical.orchestrator.link/status", "android-app://com.evil/http/x",
            "tel:911", "sms:123?body=hi", "mailto:a@b.c", "market://details?id=com.evil", "geo:0,0", "about:blank", "blob:https://x/y",
            "ftp://example.com/x", "ws://example.com", "chrome://settings", "vnd.youtube:abc", "app://x", "samevil://x", "samvideox://watch/1",
            // Family schemes that don't name a place in the app, or carry someone's sign-in.
            "samvideo:watch", "samvideo:", "samvideo://", "sammusic:///x", "samvideo://user:pw@watch/1",
            // Web addresses made to mislead.
            "https://", "https:///path", "https:example.com", "http://user:pass@example.com/", "https://example.com@evil.example/",
            " https://example.com", "https://example.com ", "https://exa mple.com", "https://example.com/\nintent://x", "https://example.com/\u0000",
            "https://example.com\\@evil.example", "‮https://example.com", "https://example.com/​x", "\tjavascript:alert(1)",
            "https://" + "a".repeat(5000),
        )
        hostile.forEach { assertNull("Must not open: $it", Links.kind(it)) }
    }

    @Test
    fun `sharing a web address sends a link, anything else the text`() {
        assertEquals(Payload.Link("https://example.com/a", "A page"), Links.toSend(" https://example.com/a ", "A page"))
        assertEquals(Payload.Link("https://example.com/a", "Look at this"), Links.toSend("Look at this https://example.com/a"))
        assertEquals(Payload.Link("https://example.com/a", "Read"), Links.toSend("Read (https://example.com/a)".replace("(", "").replace(")", ".")))
        assertEquals(Payload.Clipboard("Milk, eggs, flour"), Links.toSend("Milk, eggs, flour"))
        assertEquals(Payload.Clipboard("https://a.example and https://b.example"), Links.toSend("https://a.example and https://b.example"))
        // Not a web address: it's only ever text, never a link another device would open.
        assertEquals(Payload.Clipboard("intent://x#Intent;end"), Links.toSend("intent://x#Intent;end"))
        assertEquals(Payload.Clipboard("javascript:alert(1)"), Links.toSend("javascript:alert(1)"))
        assertTrue(Links.toSend("x".repeat(500) + " https://example.com") is Payload.Clipboard)
        assertNull(Links.toSend("   "))
    }
}

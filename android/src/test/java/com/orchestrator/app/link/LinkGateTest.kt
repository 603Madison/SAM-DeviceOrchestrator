package com.orchestrator.app.link

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.orchestrator.api.Payload
import com.orchestrator.app.data.Approvals
import com.orchestrator.app.net.ServerApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Who may sign in through the orchestrator: the provider's rules, against a pretend server. An
 * app the person hasn't approved must never cause a ticket to be asked for, nor learn who is
 * signed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkGateTest {
    private val server = MockWebServer()
    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    private lateinit var approvals: Approvals
    private lateinit var gate: LinkGate
    private var session: Session? = Session("https://devices.example.com", "maddie", "Maddie")
    private val published = mutableListOf<Payload.Activity>()
    private var now = 1_000_000L

    private val music = Caller("com.music.connect", "aa11")
    private val impostor = Caller("com.music.connect", "ee99")
    private val stranger = Caller("com.evil.app", "ff00")

    @Before
    fun start() {
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val api = ServerApi(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(), { base }, { "device-token" })
        approvals = Approvals(context.getSharedPreferences("approvals-test", Context.MODE_PRIVATE))
        approvals.clear()
        gate = LinkGate({ session }, approvals, ticket = { app -> runBlocking { api.ticket(app) } }, publish = { published += it }, now = { now })
    }

    @After
    fun stop() = server.shutdown()

    private fun ticket() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"app":"music","url":"https://music.example.com","ticket":"eyJ.signed.jwt","expiresAt":123}""")

    @Test
    fun `an approved app gets a ticket from the server`() {
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        server.enqueue(ticket())
        val answer = gate.signIn(music, "music")
        assertEquals(mapOf("ok" to true, "url" to "https://music.example.com", "ticket" to "eyJ.signed.jwt"), answer)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/tickets", request.path)
        assertEquals("Bearer device-token", request.headers["Authorization"])
        assertEquals("""{"app":"music"}""", request.body.readUtf8())
    }

    @Test
    fun `an app the person hasn't approved is told to ask, and the server never hears of it`() {
        assertEquals(mapOf("needsApproval" to true), gate.signIn(music, "music"))
        assertEquals(mapOf("needsApproval" to true), gate.signIn(stranger, "music"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `another certificate under the same package is another app`() {
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        assertEquals(mapOf("needsApproval" to true), gate.signIn(impostor, "music"))
        assertEquals(mapOf("signedIn" to true), gate.status(impostor))
        assertEquals(mapOf("needsApproval" to true), gate.publishActivity(impostor, "music", "x", "", ""))
        assertEquals(0, server.requestCount)
        assertTrue(published.isEmpty())
        // Approving the new one replaces the old: one package, one certificate.
        approvals.approve(impostor.packageName, impostor.certificate, "music", "Music?")
        assertEquals(mapOf("needsApproval" to true), gate.signIn(music, "music"))
        assertEquals(1, approvals.list.value.size)
    }

    @Test
    fun `approval is for the server that was asked about, not all of them`() {
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        assertEquals(mapOf("needsApproval" to true), gate.signIn(music, "health"))
        assertEquals(0, server.requestCount)
        approvals.approve(music.packageName, music.certificate, "health", "Music")
        assertTrue(approvals.allows(music.packageName, music.certificate, "music"))
        assertTrue(approvals.allows(music.packageName, music.certificate, "health"))
    }

    @Test
    fun `status tells an unapproved app only whether someone is signed in`() {
        assertEquals(mapOf("signedIn" to true), gate.status(stranger))
        assertEquals(mapOf("signedIn" to true), gate.status(null))
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        assertEquals(
            mapOf("signedIn" to true, "server" to "https://devices.example.com", "username" to "maddie", "displayName" to "Maddie"),
            gate.status(music),
        )
        assertEquals(mapOf("signedIn" to true), gate.status(stranger))
        session = null
        assertEquals(mapOf("signedIn" to false), gate.status(music))
        assertEquals(mapOf("signedIn" to false), gate.status(stranger))
    }

    @Test
    fun `nobody signed in, no caller, or no such app - an error, and no ticket`() {
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        assertTrue("error" in gate.signIn(null, "music"))
        assertTrue("error" in gate.signIn(music, null))
        assertTrue("error" in gate.signIn(music, "../admin"))
        assertTrue("error" in gate.signIn(music, "a b"))
        session = null
        assertTrue("error" in gate.signIn(music, "music"))
        assertTrue("error" in gate.publishActivity(music, "music", "x", "", ""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the server's refusal comes back as an error, in its words`() {
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":"This orchestrator doesn't know where music is"}"""))
        val answer = gate.signIn(music, "music")
        assertEquals("This orchestrator doesn't know where music is", answer["error"])
        assertNull(answer["ticket"])
        assertNull(answer["ok"])
    }

    @Test
    fun `a removed app has to ask again`() {
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        approvals.remove(music.packageName)
        assertEquals(mapOf("needsApproval" to true), gate.signIn(music, "music"))
        assertEquals(mapOf("signedIn" to true), gate.status(music))
        // And what's kept survives the app being restarted.
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        assertTrue(Approvals(context.getSharedPreferences("approvals-test", Context.MODE_PRIVATE)).allows(music.packageName, music.certificate, "music"))
    }

    @Test
    fun `what the person is doing goes out from approved apps only, once`() {
        assertEquals(mapOf("needsApproval" to true), gate.publishActivity(stranger, "music", "A song", "", "sammusic://track/1"))
        assertTrue(published.isEmpty())
        approvals.approve(music.packageName, music.certificate, "music", "Music")
        assertEquals(mapOf("ok" to true), gate.publishActivity(music, "Music", "A song", "Someone", "sammusic://track/1"))
        assertEquals(Payload.Activity("music", "A song", "Someone", "sammusic://track/1", now), published.single())
        // The same again within five seconds is dropped; after that, or changed, it goes.
        now += 4_000
        assertEquals(mapOf("ok" to true), gate.publishActivity(music, "music", "A song", "Someone", "sammusic://track/1"))
        assertEquals(1, published.size)
        gate.publishActivity(music, "music", "Another song", "Someone", "sammusic://track/2")
        assertEquals(2, published.size)
        now += 5_000
        gate.publishActivity(music, "music", "Another song", "Someone", "sammusic://track/2")
        assertEquals(3, published.size)
        // No title: they stopped. A link another device wouldn't open isn't passed on.
        now += 1
        gate.publishActivity(music, "music", null, null, null)
        assertNull(published.last().title)
        now += 1
        gate.publishActivity(music, "music", "Sneaky", "", "intent://evil#Intent;end")
        assertEquals("", published.last().url)
    }

    // ---- Working out who is calling ----

    private fun install(packageName: String, uid: Int, certificate: ByteArray) {
        val signing = SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(Signature(certificate)))
        val info = PackageInfo().apply {
            this.packageName = packageName
            signingInfo = signing
            applicationInfo = android.content.pm.ApplicationInfo().apply { this.packageName = packageName; this.uid = uid }
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(uid, packageName)
    }

    @Test
    fun `the caller is the package Android says made the call, with its certificate`() {
        install("com.music.connect", 10_101, byteArrayOf(1, 2, 3))
        install("com.evil.app", 10_202, byteArrayOf(9, 9, 9))
        val real = Callers.identify(context, "com.music.connect", 10_101)!!
        assertEquals("com.music.connect", real.packageName)
        assertEquals(Callers.digest(byteArrayOf(1, 2, 3)), real.certificate)
        assertEquals(64, real.certificate.length)
        // Claiming to be another package than the uid's own: nobody.
        assertNull(Callers.identify(context, "com.music.connect", 10_202))
        assertNull(Callers.identify(context, null, 10_101))
        assertNull(Callers.identify(context, "", 10_101))
        assertNull(Callers.identify(context, "com.not.installed", 10_303))
        // The same package reinstalled with another key isn't the app that was approved.
        approvals.approve(real.packageName, real.certificate, "music", "Music")
        install("com.music.connect", 10_101, byteArrayOf(4, 5, 6))
        val reinstalled = Callers.identify(context, "com.music.connect", 10_101)!!
        assertNotEquals(real.certificate, reinstalled.certificate)
        assertFalse(approvals.allows(reinstalled.packageName, reinstalled.certificate, "music"))
        assertEquals(mapOf("needsApproval" to true), gate.signIn(reinstalled, "music"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `app ids are plain`() {
        assertEquals("music", LinkGate.appId(" Music "))
        listOf(null, "", " ", "a/b", "../x", "a b", "-x", "x".repeat(40), "müsic").forEach { assertNull("$it", LinkGate.appId(it)) }
    }
}

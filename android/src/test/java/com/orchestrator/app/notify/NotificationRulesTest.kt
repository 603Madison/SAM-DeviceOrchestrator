package com.orchestrator.app.notify

import com.orchestrator.app.notify.NotificationRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private val own = "app.superawesomemagical.orchestrator"
    private val on = MirrorChoices()

    private class Case(val name: String, val posted: Posted, val choices: MirrorChoices, val expected: Verdict)

    private val cases = listOf(
        Case("an ordinary message", Posted("org.signal"), on, Verdict.MIRROR),
        Case("this app's own (a mirrored one among them)", Posted(own), on, Verdict.OWN),
        Case("this app's own, even with everything else allowed", Posted(own), MirrorChoices(appsOn = setOf(own)), Verdict.OWN),
        Case("this app's own, with mirroring off", Posted(own), MirrorChoices(mirrorOut = false), Verdict.OWN),
        Case("mirroring turned off", Posted("org.signal"), MirrorChoices(mirrorOut = false), Verdict.TURNED_OFF),
        Case("ongoing (music playing)", Posted("com.music.connect", ongoing = true), on, Verdict.ONGOING),
        Case("a foreground service's", Posted("org.maps", foregroundService = true), on, Verdict.ONGOING),
        Case("a group's summary", Posted("org.signal", groupSummary = true), on, Verdict.GROUP_SUMMARY),
        Case("for this phone only", Posted("org.signal", localOnly = true), on, Verdict.LOCAL_ONLY),
        Case("secret", Posted("org.bank", secret = true), on, Verdict.SECRET),
        Case("secret wins over an app switched on", Posted("com.android.systemui", secret = true), MirrorChoices(appsOn = setOf("com.android.systemui")), Verdict.SECRET),
        Case("an app the person switched off", Posted("org.noisy"), MirrorChoices(appsOff = setOf("org.noisy")), Verdict.APP_OFF),
        Case("another app, with one switched off", Posted("org.signal"), MirrorChoices(appsOff = setOf("org.noisy")), Verdict.MIRROR),
        Case("the system UI, off unless asked for", Posted("com.android.systemui"), on, Verdict.APP_OFF),
        Case("downloads, off unless asked for", Posted("com.android.providers.downloads"), on, Verdict.APP_OFF),
        Case("the system itself", Posted("android"), on, Verdict.APP_OFF),
        Case("the system UI, switched on", Posted("com.android.systemui"), MirrorChoices(appsOn = setOf("com.android.systemui")), Verdict.MIRROR),
        Case("off wins over on", Posted("android"), MirrorChoices(appsOff = setOf("android"), appsOn = setOf("android")), Verdict.APP_OFF),
        Case("nothing to read", Posted("org.signal", hasWords = false), on, Verdict.EMPTY),
        Case("ongoing and local-only and secret", Posted("org.signal", ongoing = true, localOnly = true, secret = true), on, Verdict.ONGOING),
        Case("a group summary that's also local-only", Posted("org.signal", groupSummary = true, localOnly = true), on, Verdict.GROUP_SUMMARY),
    )

    @Test
    fun `each rule, by the table`() {
        cases.forEach { case -> assertEquals(case.name, case.expected, NotificationRules.verdict(case.posted, own, case.choices)) }
    }

    @Test
    fun `only one verdict mirrors`() {
        assertEquals(listOf(Verdict.MIRROR), Verdict.entries.filter { it.mirrored })
        assertTrue(cases.count { it.expected.mirrored } >= 3)
    }

    @Test
    fun `any one reason to keep it here is enough`() {
        val flags = listOf<(Posted) -> Posted>(
            { it.copy(ongoing = true) }, { it.copy(foregroundService = true) }, { it.copy(groupSummary = true) },
            { it.copy(localOnly = true) }, { it.copy(secret = true) }, { it.copy(hasWords = false) }, { it.copy(packageName = own) },
        )
        flags.forEach { flag -> assertFalse(NotificationRules.verdict(flag(Posted("org.signal")), own, on).mirrored) }
    }

    @Test
    fun `the per-app switch`() {
        assertTrue(NotificationRules.appOn("org.signal", on))
        assertFalse(NotificationRules.appOn("com.android.systemui", on))
        assertTrue(NotificationRules.appOn("com.android.systemui", MirrorChoices(appsOn = setOf("com.android.systemui"))))
        assertFalse(NotificationRules.appOn("org.signal", MirrorChoices(appsOff = setOf("org.signal"))))
    }
}

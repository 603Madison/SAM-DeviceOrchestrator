package com.orchestrator.app.notify

/** What the rules need to know about a notification that was posted on this phone. */
data class Posted(
    val packageName: String,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
    val localOnly: Boolean = false,
    /** The notification's own visibility, or the person's choice for its channel: secret means "never show this on a lock screen". */
    val secret: Boolean = false,
    val hasWords: Boolean = true,
)

/** The person's choices that decide what's mirrored. */
data class MirrorChoices(
    val mirrorOut: Boolean = true,
    val appsOff: Set<String> = emptySet(),
    val appsOn: Set<String> = emptySet(),
)

/**
 * Which of this phone's notifications go to the person's other devices. A pure function, so every
 * rule has a test: anything not clearly meant to be seen elsewhere stays here.
 */
object NotificationRules {
    enum class Verdict(val mirrored: Boolean = false) {
        MIRROR(true),
        TURNED_OFF,
        OWN,
        ONGOING,
        GROUP_SUMMARY,
        LOCAL_ONLY,
        SECRET,
        APP_OFF,
        EMPTY,
    }

    /**
     * Apps that are off unless the person turns them on: the system's own chatter (charging,
     * screenshots, USB), downloads and installs.
     */
    val QUIET = setOf(
        "android",
        "com.android.systemui",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.shell",
        "com.android.bluetooth",
    )

    fun appOn(packageName: String, choices: MirrorChoices): Boolean = when (packageName) {
        in choices.appsOff -> false
        in QUIET -> packageName in choices.appsOn
        else -> true
    }

    fun verdict(n: Posted, ownPackage: String, choices: MirrorChoices): Verdict = when {
        // First, always: what this app shows (the other devices' notifications among it) never goes round again.
        n.packageName == ownPackage -> Verdict.OWN
        !choices.mirrorOut -> Verdict.TURNED_OFF
        n.ongoing || n.foregroundService -> Verdict.ONGOING
        n.groupSummary -> Verdict.GROUP_SUMMARY
        n.localOnly -> Verdict.LOCAL_ONLY
        n.secret -> Verdict.SECRET
        !appOn(n.packageName, choices) -> Verdict.APP_OFF
        !n.hasWords -> Verdict.EMPTY
        else -> Verdict.MIRROR
    }
}

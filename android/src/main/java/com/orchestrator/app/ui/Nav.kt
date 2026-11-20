package com.orchestrator.app.ui

import java.net.URLEncoder

/** Where the app can go, besides its tabs. Routes carry their values encoded. */
object Routes {
    const val DEVICES = "devices"
    const val APPS = "apps"
    const val YOU = "you"
    const val NOTIFICATION_APPS = "notification-apps"
    const val APPROVED = "approved"
    const val PERMISSIONS = "permissions"

    fun device(id: String) = "device/${enc(id)}"

    val TABS = listOf(DEVICES, APPS, YOU)

    /** The tab a route is, when it is one. */
    fun tabOf(route: String?): String? = route?.substringBefore('/')?.substringBefore('?')?.takeIf { it in TABS }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** What a page can ask for: another page, or to go back. */
class Navigator(
    val open: (String) -> Unit = {},
    val back: () -> Unit = {},
)

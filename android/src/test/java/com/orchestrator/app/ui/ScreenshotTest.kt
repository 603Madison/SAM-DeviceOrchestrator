package com.orchestrator.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.orchestrator.api.AppLink
import com.orchestrator.api.AuthConfig
import com.orchestrator.api.Battery
import com.orchestrator.api.Capabilities
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.Payload
import com.orchestrator.api.Role
import com.orchestrator.api.User
import com.orchestrator.app.data.ApprovedApp
import com.orchestrator.app.data.Palette
import com.orchestrator.app.data.Settings
import com.orchestrator.app.data.ThemeMode
import com.orchestrator.app.devices.Happening
import com.orchestrator.app.link.ApproveSheet
import com.orchestrator.app.ui.apps.AppsScreen
import com.orchestrator.app.ui.apps.FamilyApp
import com.orchestrator.app.ui.components.Backdrop
import com.orchestrator.app.ui.devices.DeviceActions
import com.orchestrator.app.ui.devices.DevicePageActions
import com.orchestrator.app.ui.devices.DeviceScreen
import com.orchestrator.app.ui.devices.DevicesScreen
import com.orchestrator.app.ui.share.ShareSheet
import com.orchestrator.app.ui.signin.SignInActions
import com.orchestrator.app.ui.signin.SignInScreen
import com.orchestrator.app.ui.theme.OrchestratorTheme
import com.orchestrator.app.ui.you.ApprovedAppsScreen
import com.orchestrator.app.ui.you.NotificationAppsScreen
import com.orchestrator.app.ui.you.NotifyingApp
import com.orchestrator.app.ui.you.PermissionActions
import com.orchestrator.app.ui.you.Permissions
import com.orchestrator.app.ui.you.PermissionsScreen
import com.orchestrator.app.ui.you.YouScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The main screens with sample data, light and dark, as PNGs in
 * android/build/outputs/screenshots (for looking at after UI changes), and what they must say.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class ScreenshotTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val all = listOf(Capabilities.NOTIFICATIONS, Capabilities.CLIPBOARD, Capabilities.LINKS, Capabilities.RING, Capabilities.ACTIVITY, Capabilities.MEDIA)
    private val phone = Device("d1", "Maddie's phone", DeviceKind.ANDROID, "Google Pixel 9", "k", all, online = true, lastSeenAt = now, createdAt = 0, battery = Battery(81, true), current = true)
    private val laptop = Device("d2", "Maddie's laptop", DeviceKind.LINUX, "ThinkPad X1", "k", all, online = true, lastSeenAt = now, createdAt = 0, battery = Battery(64))
    private val tablet = Device("d3", "Kitchen tablet", DeviceKind.ANDROID, "Lenovo Tab", "k", listOf(Capabilities.CLIPBOARD, Capabilities.LINKS), lastSeenAt = now - 2 * 3_600_000L, createdAt = 0)
    private val devices = listOf(phone, laptop, tablet)
    private val happenings = listOf(Happening("d2", "Maddie's laptop", "music", "Clair de lune", "Claude Debussy", "sammusic://track/1", now))
    private val settings = Settings(
        server = "https://devices.example.com", token = "t", user = User("u", "maddie", "Maddie", Role.USER, createdAt = 0),
        serverName = "SuperAwesomeMagicalOrchestrator", deviceId = "d1", deviceName = "Maddie's phone", publicKey = "k", privateKey = "p",
    )

    /** A screen as it sits in the app: over the backdrop, with the tabs below when it's a tab. */
    private fun render(dark: Boolean = false, palette: Palette = Palette.AURORA, tab: String? = null, content: @Composable () -> Unit) {
        compose.setContent {
            OrchestratorTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) {
                Backdrop {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        if (tab != null) TabBar(tab) {}
                    }
                }
            }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shows(text: String) = assertTrue("Shows $text", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    private fun hides(text: String) = assertTrue("Doesn't show $text", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty())

    @Test
    fun devicesLight() {
        render(tab = Routes.DEVICES) { DevicesScreen(devices, true, happenings, null, DeviceActions(), Navigator(), now) }
        listOf("Devices", "Connected to your devices", "Carry on", "Clair de lune · Music on Maddie's laptop", "This phone", "Maddie's phone", "Online · 81%, charging", "Your other devices", "Send clipboard", "Ring", "Last seen 2 hours ago", "Apps", "You").forEach(::shows)
        save("devices_light")
    }

    @Test
    fun devicesDark() {
        render(dark = true, tab = Routes.DEVICES) { DevicesScreen(devices, true, happenings, null, DeviceActions(), Navigator(), now) }
        save("devices_dark")
    }

    @Test
    fun devicesAlone() {
        render(dark = true, palette = Palette.OCEAN, tab = Routes.DEVICES) { DevicesScreen(listOf(phone), false, emptyList(), null, DeviceActions(), Navigator(), now) }
        listOf("Not connected right now", "No other devices yet").forEach(::shows)
        hides("Carry on")
        save("devices_alone_dark_ocean")
    }

    @Test
    fun device() {
        render { DeviceScreen(laptop, false, DevicePageActions(), Navigator(), now) }
        listOf("Maddie's laptop", "ThinkPad X1", "Name", "Send clipboard", "Ring", "Stop ringing", "What it does", "Rings to be found", "Remove this device").forEach(::shows)
        save("device")
    }

    private val family = listOf(
        FamilyApp(AppLink("music", "SuperAwesomeMagicalMusic", "https://music.example.com"), installed = true, signedIn = true),
        FamilyApp(AppLink("video", "SuperAwesomeMagicalVideo", "https://video.example.com"), installed = true, signedIn = false),
        FamilyApp(AppLink("health", "SuperAwesomeMagicalHealth", "https://health.example.com"), installed = false, signedIn = false),
    )

    @Test
    fun appsLight() {
        render(tab = Routes.APPS) { AppsScreen(Load.Ready(family), {}) }
        listOf("Apps", "Each one asks you first", "SuperAwesomeMagicalMusic", "On this phone", "Signed in through here", "Hasn't asked yet", "Not installed").forEach(::shows)
        save("apps_light")
    }

    @Test
    fun appsDark() {
        render(dark = true, palette = Palette.EMBER, tab = Routes.APPS) { AppsScreen(Load.Ready(family), {}) }
        save("apps_dark_ember")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun you() {
        render(tab = Routes.YOU) { YouScreen(settings, "0.1.0", notificationAccess = false, approvedApps = 1, {}, {}, Navigator()) }
        listOf("Signed in as Maddie", "Share this phone's notifications", "the server can't read them", "needs notification access", "Show other devices' notifications here", "Choose apps", "Stay connected", "Apps on this phone", "1 app signs in as you", "Permissions", "Aurora", "Ember", "Bold text", "Sign out").forEach(::shows)
        save("you_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun youDark() {
        render(dark = true, palette = Palette.VIOLET, tab = Routes.YOU) { YouScreen(settings.copy(alwaysOn = false, boldText = true), "0.1.0", notificationAccess = true, approvedApps = 0, {}, {}, Navigator()) }
        listOf("every 15 minutes", "No app signs in through here yet").forEach(::shows)
        hides("needs notification access")
        save("you_dark_violet_bold")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun firstRun() {
        render { PermissionsScreen(Permissions(notifications = true, access = false, unrestricted = false), onBack = null, onDone = {}, PermissionActions()) }
        listOf("You're signed in", "Show notifications", "Share this phone's notifications", "app, title, text and time", "sealed on this phone", "can't read them", "Not shared", "Open notification access", "(optional)", "a little battery", "within about 15 minutes", "Done").forEach(::shows)
        save("first_run_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun permissionsDark() {
        render(dark = true) { PermissionsScreen(Permissions(true, true, false), onBack = {}, onDone = null, PermissionActions()) }
        shows("Permissions")
        hides("Done")
        save("permissions_dark")
    }

    @Test
    fun notificationApps() {
        val apps = listOf(NotifyingApp("org.thoughtcrime.securesms", "Signal", true), NotifyingApp("com.android.systemui", "System UI", false), NotifyingApp("app.superawesomemagical.email", "SuperAwesomeMagicalEmail", true))
        render { NotificationAppsScreen(Load.Ready(apps), Navigator()) { _, _ -> } }
        listOf("Choose apps", "Never shared", "Signal", "System UI").forEach(::shows)
        save("notification_apps")
    }

    @Test
    fun approvedApps() {
        val apps = listOf(ApprovedApp("com.music.connect", "8de570b22b1ce693bc56e5102b74af57e32952c26ef036e6fa203b7d8721cd2b", setOf("music"), "SuperAwesomeMagicalMusic", now))
        render(dark = true) { ApprovedAppsScreen(apps, Navigator()) {} }
        listOf("Apps on this phone", "SuperAwesomeMagicalMusic", "com.music.connect", "Signs in to: Music", "Signed with 8d:e5:70", "Remove").forEach(::shows)
        save("approved_apps_dark")
    }

    @Test
    fun approve() {
        render { ApproveSheet("SuperAwesomeMagicalMusic", "com.music.connect", null, "Maddie", "music", {}, {}) }
        listOf("Let SuperAwesomeMagicalMusic sign in as Maddie?", "com.music.connect", "your Music server", "Allow", "Not now").forEach(::shows)
        save("approve")
    }

    @Test
    fun share() {
        render(dark = true) { ShareSheet(Payload.Link("https://example.com/a-long-read", "A long read"), true, listOf(laptop, tablet), null, {}, {}) }
        listOf("Send to device", "Link: https://example.com/a-long-read", "Maddie's laptop", "Kitchen tablet", "Send", "Cancel").forEach(::shows)
        save("share_dark")
    }

    @Test
    fun signIn() {
        render {
            SignInScreen(
                initialServer = "", finishing = false, error = null,
                actions = SignInActions(checkServer = { AuthConfig("SuperAwesomeMagicalOrchestrator", "0.1.0", true) }, browserSignIn = { "" }),
                initialConfig = "https://devices.example.com" to AuthConfig("SuperAwesomeMagicalOrchestrator", "0.1.0", true),
            )
        }
        listOf("SuperAwesomeMagicalOrchestrator", "Sign in", "Use another server").forEach(::shows)
        save("sign_in")
    }

    @Test
    fun signInStart() {
        render(dark = true) {
            SignInScreen("", false, null, SignInActions(checkServer = { AuthConfig("x", "0.1.0", true) }, browserSignIn = { "" }))
        }
        listOf("Server address", "Continue", "Sign in once on this phone").forEach(::shows)
        save("sign_in_start_dark")
    }
}

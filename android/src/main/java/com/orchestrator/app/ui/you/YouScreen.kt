package com.orchestrator.app.ui.you

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.orchestrator.app.BuildConfig
import com.orchestrator.app.data.Palette
import com.orchestrator.app.data.Settings
import com.orchestrator.app.data.ThemeMode
import com.orchestrator.app.notify.MirrorListener
import com.orchestrator.app.ui.Navigator
import com.orchestrator.app.ui.Routes
import com.orchestrator.app.ui.components.AppCard
import com.orchestrator.app.ui.components.InfoRow
import com.orchestrator.app.ui.components.LabeledSwitch
import com.orchestrator.app.ui.components.Notice
import com.orchestrator.app.ui.components.PageHeader
import com.orchestrator.app.ui.components.SectionTitle
import com.orchestrator.app.ui.components.SegmentedChoice
import com.orchestrator.app.ui.components.appContainer
import com.orchestrator.app.ui.rememberLoad
import kotlinx.coroutines.launch

@Composable
fun YouRoute(nav: Navigator) {
    val c = appContainer()
    val context = LocalContext.current
    val settings by c.settings.collectAsState()
    val approved by c.approvals.list.collectAsState()
    val version = rememberLoad(Unit) { c.api.authConfig().version }
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(MirrorListener.allowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = MirrorListener.allowed(context) }
    YouScreen(
        settings = settings,
        serverVersion = version.value,
        notificationAccess = access,
        approvedApps = approved.size,
        onSettings = { transform -> scope.launch { c.prefs.update(transform) } },
        onSignOut = c::signOut,
        nav = nav,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouScreen(
    settings: Settings,
    serverVersion: String?,
    notificationAccess: Boolean,
    approvedApps: Int,
    onSettings: ((Settings) -> Settings) -> Unit,
    onSignOut: () -> Unit,
    nav: Navigator,
) {
    val side = Modifier.padding(horizontal = 16.dp)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("You", subtitle = settings.user?.let { "Signed in as ${it.displayName}" }) }

        item { SectionTitle("Notifications") }
        item {
            AppCard(side) {
                LabeledSwitch(
                    "Share this phone's notifications", settings.mirrorOut, { on -> onSettings { it.copy(mirrorOut = on) } },
                    subtitle = "They appear on your other devices too. Sealed on this phone: the server can't read them.",
                )
                if (settings.mirrorOut && !notificationAccess) {
                    Notice("This needs notification access, which isn't on yet. Permissions, below, has it.", Modifier.padding(vertical = 4.dp), icon = Icons.Rounded.NotificationsNone)
                }
                LabeledSwitch(
                    "Show other devices' notifications here", settings.showIncoming, { on -> onSettings { it.copy(showIncoming = on) } },
                    subtitle = "Under \"From your other devices\"",
                )
                LinkRow(Icons.Rounded.Tune, "Choose apps", "Which apps' notifications are shared") { nav.open(Routes.NOTIFICATION_APPS) }
            }
        }

        item { SectionTitle("This phone") }
        item {
            AppCard(side) {
                LabeledSwitch(
                    "Stay connected", settings.alwaysOn, { on -> onSettings { it.copy(alwaysOn = on) } },
                    subtitle = if (settings.alwaysOn) "Things arrive straight away. Android shows a quiet \"Connected to your devices\" notification while it's on."
                    else "Off: this phone looks for what was sent every 15 minutes or so.",
                )
                LinkRow(Icons.Rounded.AppShortcut, "Apps on this phone", if (approvedApps == 0) "No app signs in through here yet" else if (approvedApps == 1) "1 app signs in as you" else "$approvedApps apps sign in as you") { nav.open(Routes.APPROVED) }
                LinkRow(Icons.Rounded.Checklist, "Permissions", "What Android lets this app do") { nav.open(Routes.PERMISSIONS) }
            }
        }

        item { SectionTitle("Appearance") }
        item {
            AppCard(side) {
                Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Palette.entries.forEach { p -> ThemeChoice(p, p == settings.palette) { onSettings { s -> s.copy(palette = p) } } }
                }
                Text("Light or dark", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                SegmentedChoice(ThemeMode.entries, settings.themeMode, { it.label }, { m -> onSettings { s -> s.copy(themeMode = m) } })
                LabeledSwitch("Bold text", settings.boldText, { b -> onSettings { s -> s.copy(boldText = b) } }, Modifier.padding(top = 8.dp), subtitle = "All text in Lexend Bold")
            }
        }

        item { SectionTitle("Account") }
        item {
            AppCard(side) {
                InfoRow(Icons.Rounded.VerifiedUser, settings.user?.displayName ?: "Signed in", settings.user?.username)
                Text(settings.serverName.ifBlank { "Your server" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(settings.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "App ${BuildConfig.VERSION_NAME}" + (serverVersion?.let { " · Server $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.padding(end = 8.dp))
                    Text("Sign out")
                }
                Text(
                    "Signing out takes this phone off your list of devices. The apps that signed in through it stay signed in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
        InfoRow(icon, title, subtitle) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
    }
}

@Composable
private fun ThemeChoice(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(palette.swatchA), Color(palette.swatchB)))))
            Text(palette.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

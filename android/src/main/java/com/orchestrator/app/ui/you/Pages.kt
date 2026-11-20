package com.orchestrator.app.ui.you

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.orchestrator.app.attempt
import com.orchestrator.app.data.ApprovedApp
import com.orchestrator.app.link.Callers
import com.orchestrator.app.notify.MirrorChoices
import com.orchestrator.app.notify.MirrorListener
import com.orchestrator.app.notify.NotificationRules
import com.orchestrator.app.ui.Load
import com.orchestrator.app.ui.Navigator
import com.orchestrator.app.ui.components.AppCard
import com.orchestrator.app.ui.components.EmptyState
import com.orchestrator.app.ui.components.ErrorCard
import com.orchestrator.app.ui.components.InfoRow
import com.orchestrator.app.ui.components.Loading
import com.orchestrator.app.ui.components.Notice
import com.orchestrator.app.ui.components.PageHeader
import com.orchestrator.app.ui.components.appContainer
import com.orchestrator.app.ui.rememberLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Which apps' notifications are shared ----

/** An app on this phone that shows notifications, and whether they're shared. */
data class NotifyingApp(val packageName: String, val label: String, val on: Boolean)

/** The apps in the launcher, and the quiet ones the phone has, by name. */
private fun notifyingApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packages = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet() +
        NotificationRules.QUIET.filter { attempt { pm.getApplicationInfo(it, 0) }.isSuccess }
    return (packages - context.packageName)
        .map { name -> name to (attempt { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrNull() ?: name) }
        .sortedBy { it.second.lowercase() }
}

@Composable
fun NotificationAppsRoute(nav: Navigator) {
    val c = appContainer()
    val context = LocalContext.current
    val settings by c.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val apps = rememberLoad(Unit) { withContext(Dispatchers.Default) { notifyingApps(context) } }
    val choices = MirrorChoices(settings.mirrorOut, settings.appsOff, settings.appsOn)
    val state: Load<List<NotifyingApp>> = when (val s = apps.state) {
        Load.Loading -> Load.Loading
        is Load.Failed -> s
        is Load.Ready -> Load.Ready(s.value.map { (name, label) -> NotifyingApp(name, label, NotificationRules.appOn(name, choices)) })
    }
    NotificationAppsScreen(state, nav) { app, on ->
        scope.launch {
            c.prefs.update {
                if (on) it.copy(appsOff = it.appsOff - app, appsOn = if (app in NotificationRules.QUIET) it.appsOn + app else it.appsOn)
                else it.copy(appsOff = it.appsOff + app, appsOn = it.appsOn - app)
            }
        }
    }
}

@Composable
fun NotificationAppsScreen(apps: Load<List<NotifyingApp>>, nav: Navigator, onToggle: (String, Boolean) -> Unit) {
    val side = Modifier.padding(horizontal = 16.dp)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Choose apps", subtitle = "Whose notifications go to your other devices", onBack = nav.back) }
        item {
            Notice(
                "Never shared, whatever is on here: ongoing notifications (music, navigation, calls in progress), ones an app marks private or for this phone only, and this app's own.",
                side, icon = Icons.Rounded.Lock,
            )
        }
        when (apps) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorCard(apps.message, side) }
            is Load.Ready -> items(apps.value, key = { it.packageName }) { app ->
                AppCard(side, padding = 12.dp, onClick = { onToggle(app.packageName, !app.on) }) {
                    InfoRow(null, app.label, app.packageName) { Switch(checked = app.on, onCheckedChange = { onToggle(app.packageName, it) }) }
                }
            }
        }
    }
}

// ---- Apps that sign in through here ----

@Composable
fun ApprovedAppsRoute(nav: Navigator) {
    val c = appContainer()
    val approved by c.approvals.list.collectAsState()
    ApprovedAppsScreen(approved, nav) { c.approvals.remove(it.packageName) }
}

@Composable
fun ApprovedAppsScreen(apps: List<ApprovedApp>, nav: Navigator, onRemove: (ApprovedApp) -> Unit) {
    val side = Modifier.padding(horizontal = 16.dp)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Apps on this phone", subtitle = "The apps you let sign in as you", onBack = nav.back) }
        if (apps.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.AppShortcut, "No apps yet", "When one of the family's apps wants to sign in as you, it asks here first. The ones you allow are listed here.")
            }
        }
        items(apps, key = { it.packageName }) { app ->
            AppCard(side, padding = 14.dp) {
                InfoRow(Icons.Rounded.AppShortcut, app.label.ifBlank { app.packageName }, app.packageName) {
                    TextButton(onClick = { onRemove(app) }) { Text("Remove") }
                }
                Text(
                    "Signs in to: " + app.apps.sorted().joinToString(", ") { com.orchestrator.app.ui.Format.appName(it) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Signed with ${Callers.pretty(app.certificate).take(23)}…",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (apps.isNotEmpty()) {
            item {
                Text(
                    "Removing an app means it asks again next time. It stays signed in to its own server until you sign out there.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = side.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---- What Android lets the app do ----

/** What's allowed right now. */
data class Permissions(val notifications: Boolean, val access: Boolean, val unrestricted: Boolean)

fun permissions(context: Context) = Permissions(
    notifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    access = MirrorListener.allowed(context),
    unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
)

class PermissionActions(val notifications: () -> Unit = {}, val access: () -> Unit = {}, val battery: () -> Unit = {})

@Composable
fun PermissionsRoute(onBack: (() -> Unit)?, onDone: (() -> Unit)?) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(permissions(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = permissions(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state = permissions(context) }
    fun open(intent: Intent, fallback: Intent? = null) {
        if (attempt { context.startActivity(intent) }.isFailure && fallback != null) attempt { context.startActivity(fallback) }
    }
    PermissionsScreen(
        state, onBack, onDone,
        PermissionActions(
            notifications = {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            access = {
                val list = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                if (Build.VERSION.SDK_INT >= 30) {
                    val mine = ComponentName(context, MirrorListener::class.java).flattenToString()
                    open(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, mine), list)
                } else {
                    open(list)
                }
            },
            battery = {
                // The direct question; the list of apps where that isn't offered.
                @Suppress("BatteryLife")
                val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                open(direct, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
        ),
    )
}

/** The permission cards: on first run before the tabs, and afterwards under You. Each says exactly what it's for. */
@Composable
fun PermissionsScreen(state: Permissions, onBack: (() -> Unit)?, onDone: (() -> Unit)?, actions: PermissionActions) {
    val side = Modifier.padding(horizontal = 16.dp)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            if (onBack != null) PageHeader("Permissions", subtitle = "What Android lets this app do", onBack = onBack)
            else PageHeader("You're signed in", subtitle = "Three things Android asks you about. Each is up to you, and can be changed later under You.")
        }
        item {
            PermissionCard(
                Icons.Rounded.Notifications, "Show notifications", state.notifications,
                "So links, text and notifications from your other devices can appear on this phone, and so it can show that it's ringing.",
                "Allow", actions.notifications, side,
            )
        }
        item {
            PermissionCard(
                Icons.Rounded.NotificationsActive, "Share this phone's notifications", state.access,
                "With notification access, this app reads the notifications this phone shows and sends each one's app, title, text and time to your other devices, so you see them there too.\n\n" +
                    "They're sealed on this phone for each of your devices before they leave: the server passes them on and can't read them.\n\n" +
                    "Not shared: ongoing notifications (music, navigation), ones marked private or for this phone only, and any app you switch off under You › Choose apps.",
                "Open notification access", actions.access, side,
            )
        }
        item {
            PermissionCard(
                Icons.Rounded.BatteryChargingFull, "Stay connected while the phone sleeps (optional)", state.unrestricted,
                "Android pauses apps' connections after the phone has been still for a while. Letting this app run unrestricted keeps its one connection to your server open, so things arrive straight away, and a ring always gets through.\n\n" +
                    "It costs a little battery. Without it everything still works: things arrive when the phone next wakes, or within about 15 minutes.",
                "Allow", actions.battery, side,
            )
        }
        if (onDone != null) {
            item {
                Button(onClick = onDone, modifier = side.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, allowed: Boolean, body: String, button: String, onClick: () -> Unit, modifier: Modifier) {
    AppCard(modifier) {
        InfoRow(icon, title, if (allowed) "On" else "Not on yet") {
            if (allowed) Icon(Icons.Rounded.CheckCircle, "On", tint = MaterialTheme.colorScheme.tertiary)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (!allowed) FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 12.dp)) { Text(button) }
    }
}

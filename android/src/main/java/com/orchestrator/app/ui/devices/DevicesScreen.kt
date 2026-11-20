package com.orchestrator.app.ui.devices

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.orchestrator.api.Capabilities
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.Payload
import com.orchestrator.app.Container
import com.orchestrator.app.attempt
import com.orchestrator.app.devices.Activities
import com.orchestrator.app.devices.Happening
import com.orchestrator.app.link.Links
import com.orchestrator.app.ui.Format
import com.orchestrator.app.ui.LocalSnackbar
import com.orchestrator.app.ui.Navigator
import com.orchestrator.app.ui.Routes
import com.orchestrator.app.ui.components.AppCard
import com.orchestrator.app.ui.components.Chip
import com.orchestrator.app.ui.components.EmptyState
import com.orchestrator.app.ui.components.ErrorCard
import com.orchestrator.app.ui.components.InfoRow
import com.orchestrator.app.ui.components.Loading
import com.orchestrator.app.ui.components.PageHeader
import com.orchestrator.app.ui.components.SectionTitle
import com.orchestrator.app.ui.components.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What's on this phone's clipboard, as text. Only an activity the person is looking at may ask. */
fun clipboardText(context: Context): String? = attempt {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
    clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()

/** Sends this phone's clipboard to [only] (or to every other device): what to tell the person afterwards. */
suspend fun sendClipboard(c: Container, context: Context, only: Set<String>? = null): String =
    sendClipboard(c, clipboardText(context) ?: return "There's no text on the clipboard", only)

suspend fun sendClipboard(c: Container, text: String, only: Set<String>? = null): String {
    return attempt { c.messenger.send(Payload.Clipboard(text), only) }.fold(
        { sent -> if (sent == null) "No other device takes text yet" else "Sent" },
        { it.message ?: "Couldn't send it" },
    )
}

/** Opens a link another device or app handed over, if it's one this phone opens. */
fun openLink(context: Context, url: String): Boolean {
    if (!Links.allowed(url)) return false
    return attempt {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

fun iconOf(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.LINUX -> Icons.Rounded.Computer
    DeviceKind.ANDROID -> Icons.Rounded.Smartphone
    DeviceKind.OTHER -> Icons.Rounded.DevicesOther
}

/** What the Devices tab can do. */
class DeviceActions(
    val sendClipboard: (Device) -> Unit = {},
    val ring: (Device) -> Unit = {},
    val carryOn: (Happening) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun DevicesRoute(nav: Navigator) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val devices by c.book.devices.collectAsState()
    val connected by c.line.connected.collectAsState()
    val happenings by c.activities.all.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableLongStateOf(0L) }
    // "Carry on" cards go stale by the clock, and the list is asked for again each time the tab is opened.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(round) {
        error = attempt { c.book.refresh() }.exceptionOrNull()?.message
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    DevicesScreen(
        devices = devices,
        connected = connected,
        happenings = Activities.fresh(happenings, now),
        error = error.takeIf { devices == null },
        actions = DeviceActions(
            sendClipboard = { device -> scope.launch { snackbar.showSnackbar(sendClipboard(c, context, setOf(device.id)).let { if (it == "Sent") "Sent to ${device.name}" else it }) } },
            ring = { device ->
                scope.launch {
                    val said = attempt { c.messenger.send(Payload.Ring(true), setOf(device.id)) }
                        .fold({ if (it == null) "${device.name} can't ring" else "Ringing ${device.name}" }, { it.message ?: "Couldn't ring it" })
                    snackbar.showSnackbar(said)
                }
            },
            carryOn = { h -> if (!openLink(context, h.url)) scope.launch { snackbar.showSnackbar("Nothing on this phone opens that yet") } },
            retry = { round++ },
        ),
        nav = nav,
        now = now,
    )
}

@Composable
fun DevicesScreen(
    devices: List<Device>?,
    connected: Boolean,
    happenings: List<Happening>,
    error: String?,
    actions: DeviceActions,
    nav: Navigator,
    now: Long = System.currentTimeMillis(),
) {
    val side = Modifier.padding(horizontal = 16.dp)
    val mine = devices?.firstOrNull { it.current }
    val others = devices?.filterNot { it.current }.orEmpty()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Devices", subtitle = if (connected) "Connected to your devices" else "Not connected right now") }
        if (happenings.isNotEmpty()) {
            item { SectionTitle("Carry on") }
            items(happenings, key = { "${it.device}/${it.app}" }) { h ->
                AppCard(side, padding = 14.dp, onClick = if (h.url.isNotBlank()) ({ actions.carryOn(h) }) else null) {
                    InfoRow(Icons.Rounded.PlayArrow, Format.carryOn(h), h.detail.ifBlank { null }) {
                        if (h.url.isNotBlank()) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open")
                    }
                }
            }
        }
        when {
            devices == null && error != null -> item { ErrorCard(error, side, onRetry = actions.retry) }
            devices == null -> item { Loading() }
            else -> {
                mine?.let { device ->
                    item { SectionTitle("This phone") }
                    item { DeviceCard(device, now, side, onOpen = { nav.open(Routes.device(device.id)) }, actions = null) }
                }
                item { SectionTitle("Your other devices") }
                if (others.isEmpty()) {
                    item {
                        EmptyState(Icons.Rounded.Devices, "No other devices yet", "Sign in on your computer or another phone, and it shows up here.")
                    }
                }
                items(others, key = { it.id }) { device ->
                    DeviceCard(device, now, side, onOpen = { nav.open(Routes.device(device.id)) }, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: Device, now: Long, modifier: Modifier, onOpen: () -> Unit, actions: DeviceActions?) {
    AppCard(modifier, padding = 14.dp, onClick = onOpen) {
        InfoRow(iconOf(device.kind), device.name, listOf(device.model, Format.status(device, now)).filter { it.isNotBlank() }.joinToString(" · ")) {
            if (device.online) Chip("Online", strong = true)
        }
        if (actions != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Capabilities.CLIPBOARD in device.capabilities) {
                    FilledTonalButton(onClick = { actions.sendClipboard(device) }) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                        Text("Send clipboard", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (Capabilities.RING in device.capabilities) {
                    FilledTonalButton(onClick = { actions.ring(device) }) {
                        Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp))
                        Text("Ring", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

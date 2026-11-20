package com.orchestrator.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orchestrator.api.Capabilities
import com.orchestrator.api.Device
import com.orchestrator.api.Payload
import com.orchestrator.app.attempt
import com.orchestrator.app.ui.Format
import com.orchestrator.app.ui.LocalSnackbar
import com.orchestrator.app.ui.Navigator
import com.orchestrator.app.ui.components.AppCard
import com.orchestrator.app.ui.components.EmptyState
import com.orchestrator.app.ui.components.InfoRow
import com.orchestrator.app.ui.components.Loading
import com.orchestrator.app.ui.components.PageHeader
import com.orchestrator.app.ui.components.SectionTitle
import com.orchestrator.app.ui.components.appContainer
import kotlinx.coroutines.launch

class DevicePageActions(
    val rename: (String) -> Unit = {},
    val remove: () -> Unit = {},
    val sendClipboard: () -> Unit = {},
    val ring: (Boolean) -> Unit = {},
)

@Composable
fun DeviceRoute(id: String, nav: Navigator) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val devices by c.book.devices.collectAsState()
    LaunchedEffect(id) { if (devices == null) attempt { c.book.refresh() } }
    val device = devices?.firstOrNull { it.id == id }
    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }
    DeviceScreen(
        device = device,
        loading = devices == null,
        actions = DevicePageActions(
            rename = { name ->
                scope.launch {
                    attempt {
                        val renamed = c.api.rename(id, name)
                        if (device?.current == true) c.prefs.device(renamed.id, renamed.name)
                        c.book.refresh()
                    }.fold({ say("Renamed") }, { say(it.message ?: "Couldn't rename it") })
                }
            },
            remove = {
                scope.launch {
                    attempt {
                        c.api.remove(id)
                        c.book.refresh()
                    }.fold({ nav.back() }, { say(it.message ?: "Couldn't remove it") })
                }
            },
            sendClipboard = { scope.launch { say(sendClipboard(c, context, setOf(id))) } },
            ring = { on ->
                scope.launch {
                    attempt { c.messenger.send(Payload.Ring(on), setOf(id)) }
                        .fold({ say(if (on) "Ringing" else "Stopped") }, { say(it.message ?: "Couldn't reach it") })
                }
            },
        ),
        nav = nav,
    )
}

/** One device: its name (to change), what it does, the things to send it, and removing it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(device: Device?, loading: Boolean, actions: DevicePageActions, nav: Navigator, now: Long = System.currentTimeMillis()) {
    val side = Modifier.padding(horizontal = 16.dp)
    var removing by remember { mutableStateOf(false) }
    if (removing && device != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("It's signed out, and anything waiting for it is dropped. Signing in on it again brings it back.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    actions.remove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep it") } },
        )
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader(device?.name ?: "Device", subtitle = device?.let { if (it.current) "This phone" else Format.status(it, now) }, onBack = nav.back) }
        when {
            device == null && loading -> item { Loading() }
            device == null -> item { EmptyState(Icons.Rounded.DeleteOutline, "This device is gone", "It was removed, or signed out.") }
            else -> {
                item {
                    var name by remember(device.name) { mutableStateOf(device.name) }
                    AppCard(side) {
                        InfoRow(iconOf(device.kind), device.model.ifBlank { device.name }, Format.status(device, now))
                        OutlinedTextField(
                            name, { name = it.take(60) },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        if (name.trim() != device.name && name.isNotBlank()) {
                            Button(onClick = { actions.rename(name.trim()) }, modifier = Modifier.padding(top = 8.dp)) { Text("Save") }
                        }
                    }
                }
                if (!device.current) {
                    item { SectionTitle("Send") }
                    item {
                        AppCard(side) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (Capabilities.CLIPBOARD in device.capabilities) {
                                    FilledTonalButton(onClick = actions.sendClipboard) {
                                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                                        Text("Send clipboard", Modifier.padding(start = 8.dp))
                                    }
                                }
                                if (Capabilities.RING in device.capabilities) {
                                    FilledTonalButton(onClick = { actions.ring(true) }) {
                                        Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp))
                                        Text("Ring", Modifier.padding(start = 8.dp))
                                    }
                                    OutlinedButton(onClick = { actions.ring(false) }) {
                                        Icon(Icons.Rounded.NotificationsOff, null, Modifier.size(18.dp))
                                        Text("Stop ringing", Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                            Text(
                                "To send a link, share it from any app and choose Send to device.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                val can = device.capabilities.mapNotNull(Format::can)
                if (can.isNotEmpty()) {
                    item { SectionTitle("What it does") }
                    item {
                        AppCard(side) {
                            can.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                    }
                }
                if (!device.current) {
                    item {
                        OutlinedButton(
                            onClick = { removing = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = side.padding(top = 12.dp),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                            Text("Remove this device", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

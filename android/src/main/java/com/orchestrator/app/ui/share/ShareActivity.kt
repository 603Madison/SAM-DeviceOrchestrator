package com.orchestrator.app.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orchestrator.api.Device
import com.orchestrator.api.Payload
import com.orchestrator.app.attempt
import com.orchestrator.app.container
import com.orchestrator.app.devices.Envelope
import com.orchestrator.app.link.Links
import com.orchestrator.app.ui.Format
import com.orchestrator.app.ui.components.InfoRow
import com.orchestrator.app.ui.devices.iconOf
import com.orchestrator.app.ui.theme.OrchestratorTheme
import kotlinx.coroutines.launch

/** A small page over whatever was open: a rounded card at the bottom of the screen. Tapping outside closes it. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 20.dp).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

@Composable
fun SheetTitle(title: String, subtitle: String? = null, subtitleLines: Int = 3) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "Send to device" in other apps' share sheets: pick which of your devices gets the link or text. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val payload = text?.let { Links.toSend(it, intent?.getStringExtra(Intent.EXTRA_SUBJECT)) }
        setContent {
            val settings by c.settings.collectAsState()
            val devices by c.book.devices.collectAsState()
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { if (settings.signedIn) attempt { c.book.refresh() }.onFailure { error = it.message } }
            OrchestratorTheme(settings.themeMode, settings.boldText, settings.palette) {
                ShareSheet(
                    payload = payload,
                    signedIn = settings.signedIn,
                    devices = payload?.let { p -> devices?.let { Envelope.recipients(p, it, settings.deviceId) } },
                    error = error,
                    onCancel = ::finish,
                    onSend = { chosen ->
                        // On its way in the app's own scope: the sheet can close at once.
                        c.scope.launch {
                            val said = attempt { c.messenger.send(payload!!, chosen) }.fold({ "Sent" }, { it.message ?: "Couldn't send it" })
                            Toast.makeText(applicationContext, said, Toast.LENGTH_SHORT).show()
                        }
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
fun ShareSheet(payload: Payload?, signedIn: Boolean, devices: List<Device>?, error: String?, onCancel: () -> Unit, onSend: (Set<String>) -> Unit) {
    // One device: it's the one. More: the person picks.
    var chosen by remember(devices) { mutableStateOf(devices?.singleOrNull()?.let { setOf(it.id) } ?: emptySet()) }
    Sheet(onCancel) {
        val what = when (payload) {
            is Payload.Link -> "Link: ${payload.url}"
            is Payload.Clipboard -> "Text for its clipboard: ${payload.text}"
            else -> null
        }
        SheetTitle("Send to device", what)
        val problem = when {
            payload == null -> "There's no text or link here to send."
            !signedIn -> "Sign in to SuperAwesomeMagicalOrchestrator first."
            devices == null && error != null -> error
            devices == null -> "Looking for your devices…"
            devices.isEmpty() -> "None of your other devices can take this yet."
            else -> null
        }
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            Column(Modifier.padding(vertical = 10.dp)) {
                devices.orEmpty().forEach { device ->
                    val on = device.id in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - device.id else chosen + device.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoRow(iconOf(device.kind), device.name, Format.status(device), Modifier.weight(1f)) {
                            Checkbox(checked = on, onCheckedChange = { chosen = if (on) chosen - device.id else chosen + device.id })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(if (problem == null) "Cancel" else "Close") }
            if (problem == null) Button(enabled = chosen.isNotEmpty(), onClick = { onSend(chosen) }) { Text("Send") }
        }
    }
}

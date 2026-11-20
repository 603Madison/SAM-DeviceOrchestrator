package com.orchestrator.app.link

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.orchestrator.app.attempt
import com.orchestrator.app.container
import com.orchestrator.app.ui.Format
import com.orchestrator.app.ui.share.Sheet
import com.orchestrator.app.ui.share.SheetTitle
import com.orchestrator.app.ui.theme.OrchestratorTheme

/**
 * "Let this app sign in as you?": what one of the family's apps starts (for a result) the first
 * time it asks for a sign-in. Android says which app started it only when it was started for a
 * result, so anything else is refused; the app's name and icon come from the system, not from
 * what the app says about itself. Allow remembers the app's package and signing certificate for
 * the one server it asked about, and answers RESULT_OK.
 */
class ApproveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        // Nothing may sit on top of the question or pass taps through to it.
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)

        val c = container
        val settings = c.settings.value
        // Null unless started with startActivityForResult: then there's nobody to answer, and nobody to trust.
        val asking = callingPackage
        val certificate = asking?.let { Callers.certificate(this, it) }
        val app = LinkGate.appId(intent?.getStringExtra("app"))
        if (asking == null || certificate == null || app == null || !settings.signedIn) {
            finish()
            return
        }
        if (c.approvals.allows(asking, certificate, app)) {
            setResult(RESULT_OK)
            finish()
            return
        }
        val pm = packageManager
        val label = attempt { pm.getApplicationLabel(pm.getApplicationInfo(asking, 0)).toString() }.getOrNull()?.ifBlank { null } ?: asking
        val icon = attempt { pm.getApplicationIcon(asking).toBitmap(144, 144).asImageBitmap() }.getOrNull()
        val who = settings.user?.displayName?.ifBlank { null } ?: settings.user?.username ?: "you"
        setContent {
            val s by c.settings.collectAsState()
            OrchestratorTheme(s.themeMode, s.boldText, s.palette) {
                ApproveSheet(
                    label = label, packageName = asking, icon = icon, who = who, app = app,
                    onAllow = {
                        c.approvals.approve(asking, certificate, app, label)
                        setResult(RESULT_OK)
                        finish()
                    },
                    onRefuse = ::finish,
                )
            }
        }
    }
}

@Composable
fun ApproveSheet(label: String, packageName: String, icon: ImageBitmap?, who: String, app: String, onAllow: () -> Unit, onRefuse: () -> Unit) {
    Sheet(onRefuse) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            icon?.let { Image(it, null, Modifier.size(52.dp)) }
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(top = 16.dp)) {
            SheetTitle(
                "Let $label sign in as $who?",
                "It will be signed in to your ${Format.appName(app)} server as you, now and whenever it asks again, and can tell your other devices what you're doing in it. You can take this back under You › Apps on this phone.",
                subtitleLines = 12,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onRefuse) { Text("Not now") }
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

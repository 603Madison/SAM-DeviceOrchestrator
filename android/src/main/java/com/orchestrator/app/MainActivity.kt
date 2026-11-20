package com.orchestrator.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.orchestrator.app.line.LineService
import com.orchestrator.app.ui.MainScaffold
import com.orchestrator.app.ui.signin.SignInRoute
import com.orchestrator.app.ui.theme.OrchestratorTheme
import com.orchestrator.app.ui.theme.isAppInDarkTheme

/** The scrims Android's default edge-to-edge puts behind three-button navigation. */
internal val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
internal val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: turning the phone recreates the activity with the same intent.
        if (savedInstanceState == null) handle(intent)
        val c = container
        setContent {
            val settings by c.settings.collectAsState()
            val finishing by c.signIn.finishing.collectAsState()
            val signInError by c.signIn.error.collectAsState()
            val dark = isAppInDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            OrchestratorTheme(settings.themeMode, settings.boldText, settings.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settings.signedIn && !finishing) MainScaffold(firstRun = !settings.setupDone) else SignInRoute(finishing, signInError)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // With the app on screen Android lets the line's service start, if it couldn't before.
        LineService.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The server's sign-in page sends the app back here with its one-time code. */
    private fun handle(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme != "samorchestrator" || link.host != "signed-in") return
        // Used once: reopening from Recents (which replays the starting intent) doesn't finish it again.
        setIntent(Intent(intent).setData(null))
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val c = container
        if (c.settings.value.signedIn) return
        c.signIn.finishInBackground(link, c.scope)
    }
}

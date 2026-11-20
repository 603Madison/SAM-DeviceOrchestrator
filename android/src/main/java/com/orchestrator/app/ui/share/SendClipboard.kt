package com.orchestrator.app.ui.share

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.orchestrator.app.container
import com.orchestrator.app.ui.devices.sendClipboard
import kotlinx.coroutines.launch

/**
 * Sends this phone's clipboard to the person's other devices, then closes. An activity because
 * Android only lets the app in front read the clipboard, and only once its window has focus; it
 * reads when the person asked (the tile), never by itself.
 */
class SendClipboardActivity : ComponentActivity() {
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!container.settings.value.signedIn) {
            Toast.makeText(this, "Sign in to SuperAwesomeMagicalOrchestrator first", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent || isFinishing) return
        sent = true
        val c = container
        val app = applicationContext
        // Read now, while in front; sent in the app's own scope, so closing doesn't cut it short.
        val text = com.orchestrator.app.ui.devices.clipboardText(this)
        if (text == null) {
            Toast.makeText(app, "There's no text on the clipboard", Toast.LENGTH_SHORT).show()
        } else {
            c.scope.launch {
                val said = sendClipboard(c, text)
                Toast.makeText(app, if (said == "Sent") "Clipboard sent to your devices" else said, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

/** The Quick Settings tile "Send clipboard": opens [SendClipboardActivity], which does it. */
class ClipboardTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (container.settings.value.signedIn) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

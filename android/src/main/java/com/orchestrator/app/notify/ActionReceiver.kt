package com.orchestrator.app.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.orchestrator.api.Payload
import com.orchestrator.app.container
import kotlinx.coroutines.launch

/** The buttons on the app's own notifications. Not exported: only the app's own PendingIntents reach it. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        when (intent.action) {
            // A mirrored notification was swiped away here: it goes on every other device too.
            DISMISSED -> intent.getStringExtra(KEY)?.let { key ->
                val pending = goAsync()
                c.scope.launch {
                    try {
                        c.messenger.sendQuietly(Payload.Dismiss(key))
                    } finally {
                        pending.finish()
                    }
                }
            }
            COPY -> intent.getStringExtra(KEY)?.let { id ->
                c.presenter.remembered(id)?.let { text ->
                    c.presenter.copy(text)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                context.getSystemService(NotificationManager::class.java).cancel(Presenter.CLIP_ID)
            }
            FOUND -> c.ringer.stop()
        }
    }

    companion object {
        private const val DISMISSED = "com.orchestrator.app.DISMISSED"
        private const val COPY = "com.orchestrator.app.COPY"
        private const val FOUND = "com.orchestrator.app.FOUND"
        private const val KEY = "key"

        private fun intent(context: Context, action: String) = Intent(context, ActionReceiver::class.java).setAction(action)

        // The key is in the data too, so each notification's PendingIntent is its own.
        fun dismissed(context: Context, key: String): Intent =
            intent(context, DISMISSED).putExtra(KEY, key).setData(android.net.Uri.fromParts("samo", key, null))

        fun copy(context: Context, messageId: String): Intent = intent(context, COPY).putExtra(KEY, messageId)

        fun found(context: Context): Intent = intent(context, FOUND)
    }
}

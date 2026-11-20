package com.orchestrator.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.orchestrator.api.Payload
import com.orchestrator.app.MainActivity
import com.orchestrator.app.R
import com.orchestrator.app.attempt
import com.orchestrator.app.devices.Effects
import com.orchestrator.app.link.Links
import java.util.Base64

/**
 * Shows on this phone what the person's other devices send: their notifications, text for the
 * clipboard, links, and makes it ring.
 */
class Presenter(
    private val context: Context,
    private val ringer: Ringer,
    /** Whether the person is looking at the app right now. */
    private val inForeground: () -> Boolean,
    /** Removes one of this phone's own notifications, when it was dismissed on another device. */
    private val cancelLocal: (key: String) -> Unit,
) : Effects {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val clips: SharedPreferences = context.getSharedPreferences("clips", Context.MODE_PRIVATE)

    init {
        channels()
    }

    private fun channels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(LINE, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the phone stays connected to your devices"
                    setShowBadge(false)
                },
                NotificationChannel(MIRRORED, "From your other devices", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications from your other devices"
                },
                NotificationChannel(SENT, "Sent to this phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Links and text your other devices send here"
                },
                NotificationChannel(RING, "Find this phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When another device rings this phone"
                    // The ring itself is played on the alarm stream, so it's heard on silent.
                    setSound(null, null)
                    enableVibration(false)
                },
            ),
        )
    }

    // ---- Notifications from other devices ----

    override fun notification(from: String, payload: Payload.Notification) {
        val app = payload.app.ifBlank { payload.appId }.ifBlank { "Notification" }
        val builder = Notification.Builder(context, MIRRORED)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle(payload.title.ifBlank { app })
            .setContentText(payload.text)
            // The header line: which app, on which device.
            .setSubText("$app · $from")
            .setWhen(payload.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // Mirrored once: never on to a watch or anything else that mirrors.
            .setLocalOnly(true)
            .setContentIntent(openApp())
            .setDeleteIntent(broadcast(ActionReceiver.dismissed(context, payload.key), payload.key.hashCode()))
        if (payload.text.length > 40) builder.setStyle(Notification.BigTextStyle().bigText(payload.text))
        icon(payload.icon)?.let(builder::setLargeIcon)
        show(payload.key, MIRROR_ID, builder.build())
    }

    /** The sender's little picture, when it really is one (a PNG, 64 px at most). */
    private fun icon(base64: String?): android.graphics.Bitmap? {
        if (base64.isNullOrEmpty() || base64.length > 16_000) return null
        return attempt {
            val bytes = Base64.getDecoder().decode(base64)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    override fun dismiss(key: String) {
        // Either it's one shown here from another device, or it's this phone's own and was dismissed there.
        manager.cancel(key, MIRROR_ID)
        cancelLocal(key)
    }

    // ---- Text and links ----

    override fun clipboard(messageId: String, from: String, text: String) {
        if (text.isEmpty()) return
        if (inForeground()) {
            copy(text)
            return
        }
        remember(messageId, text)
        val notification = Notification.Builder(context, SENT)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle("Text from $from")
            .setContentText(text.take(200))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setContentIntent(broadcast(ActionReceiver.copy(context, messageId), messageId.hashCode()))
            .addAction(Notification.Action.Builder(null, "Copy", broadcast(ActionReceiver.copy(context, messageId), messageId.hashCode())).build())
            .build()
        // One at a time: a newer text replaces the one before.
        show(null, CLIP_ID, notification)
    }

    /** Puts [text] on the clipboard. */
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From your other device", text))
    }

    private fun remember(id: String, text: String) = clips.edit {
        clear()
        putString(id, text)
    }

    /** The text the Copy button of the notification for [messageId] is for. */
    fun remembered(messageId: String): String? = clips.getString(messageId, null)

    override fun link(messageId: String, from: String, link: Payload.Link) {
        // Only a web address or one of the family's app links is ever shown, let alone opened.
        val kind = Links.kind(link.url) ?: return
        val view = Intent(Intent.ACTION_VIEW, link.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opens = kind == Links.Kind.WEB || view.resolveActivity(context.packageManager) != null
        val tap = if (opens) PendingIntent.getActivity(context, messageId.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else openApp()
        val notification = Notification.Builder(context, SENT)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle("Link from $from")
            .setContentText(link.title.ifBlank { link.url })
            .setStyle(Notification.BigTextStyle().bigText(listOf(link.title, link.url, if (opens) "" else "No app on this phone opens this yet.").filter { it.isNotBlank() }.joinToString("\n")))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setContentIntent(tap)
            .build()
        show(messageId, LINK_ID, notification)
    }

    // ---- Ring and media ----

    override fun ring(from: String, on: Boolean) {
        if (on) ringer.start(from) else ringer.stop()
    }

    override fun media(action: String) {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val at = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, code, 0))
    }

    // ---- Plumbing ----

    /** Without the notifications permission there's nothing to show; that's the person's choice, not an error. */
    private fun show(tag: String?, id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        manager.notify(tag, id, notification)
    }

    fun cancelAll() = manager.cancelAll()

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun broadcast(intent: Intent, request: Int): PendingIntent =
        PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val LINE = "line"
        const val MIRRORED = "mirrored"
        const val SENT = "sent"
        const val RING = "ring"

        const val LINE_ID = 1
        const val MIRROR_ID = 2
        const val CLIP_ID = 3
        const val LINK_ID = 4
        const val RING_ID = 5
    }
}

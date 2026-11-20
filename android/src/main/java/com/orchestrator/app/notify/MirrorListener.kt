package com.orchestrator.app.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.orchestrator.api.Payload
import com.orchestrator.app.Container
import com.orchestrator.app.attempt
import com.orchestrator.app.container
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Reads the notifications this phone shows, once the person has given the app notification access,
 * and sends the ones [NotificationRules] lets through, sealed, to their other devices; says so
 * when one is dismissed here, and removes one here when it was dismissed there.
 */
class MirrorListener : NotificationListenerService() {
    private val c: Container get() = applicationContext.container

    override fun onListenerConnected() {
        live = this
    }

    override fun onListenerDisconnected() {
        if (live === this) live = null
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val settings = c.settings.value
        if (!settings.signedIn) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        val posted = Posted(
            packageName = sbn.packageName,
            ongoing = sbn.isOngoing || n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            localOnly = n.flags and Notification.FLAG_LOCAL_ONLY != 0,
            secret = n.visibility == Notification.VISIBILITY_SECRET || channelSecret(sbn.key, rankingMap),
            hasWords = title.isNotEmpty() || text.isNotEmpty(),
        )
        val choices = MirrorChoices(settings.mirrorOut, settings.appsOff, settings.appsOn)
        if (!NotificationRules.verdict(posted, packageName, choices).mirrored) return
        val payload = Payload.Notification(
            key = wireKey(settings.deviceId, sbn.key),
            app = c.appNames.label(sbn.packageName),
            appId = sbn.packageName,
            title = title.take(200),
            text = text.take(2000),
            at = sbn.postTime,
            icon = c.appNames.icon(sbn.packageName),
        )
        c.outbox.offer(sbn.key, payload)
    }

    /** Whether the person keeps this notification's channel off the lock screen altogether. */
    private fun channelSecret(key: String, rankingMap: RankingMap?): Boolean {
        val ranking = Ranking()
        return rankingMap?.getRanking(key, ranking) == true && ranking.channel?.lockscreenVisibility == Notification.VISIBILITY_SECRET
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // Only what went out is taken back, and only when the person or the app took it away: not
        // when the listener is being disconnected, a channel is banned, the profile is locked…
        val wasSent = c.outbox.forget(sbn.key)
        if (wasSent && reason in TAKEN_AWAY && c.settings.value.signedIn) c.sendDismiss(wireKey(c.settings.value.deviceId, sbn.key))
    }

    companion object {
        /**
         * A notification's key as other devices know it: this phone's id, then Android's key, so
         * keys from different devices never collide (a dismissal names only the key).
         */
        fun wireKey(deviceId: String, key: String): String = "$deviceId:$key"

        /** Android's key for [wireKey] if it's one of this phone's own, else null. */
        fun ownKey(deviceId: String, wireKey: String): String? =
            wireKey.removePrefix("$deviceId:").takeIf { deviceId.isNotEmpty() && it != wireKey }

        /** The person swiped it, tapped it, cleared all, or the app withdrew it. */
        val TAKEN_AWAY = setOf(REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL, REASON_APP_CANCEL, REASON_APP_CANCEL_ALL)

        @Volatile
        private var live: MirrorListener? = null

        /** Removes one of this phone's notifications (it was dismissed on another device). */
        fun cancel(key: String) {
            attempt { live?.cancelNotification(key) }
        }

        /** Whether the person has given the app notification access. */
        fun allowed(context: Context): Boolean {
            val mine = ComponentName(context, MirrorListener::class.java)
            val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
        }
    }
}

/** Apps' names and little pictures for mirrored notifications, looked up once each. */
class AppNames(private val context: Context) {
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, String>()

    @Synchronized
    fun label(packageName: String): String = labels.getOrPut(packageName) {
        attempt {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.ifBlank { null } ?: packageName
    }

    /** The app's icon as a PNG, base64: 64 px, or 48 when that's over 8 KB; none when it still is. */
    @Synchronized
    fun icon(packageName: String): String? = icons.getOrPut(packageName) {
        attempt {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            listOf(64, 48).firstNotNullOfOrNull { size ->
                val bitmap = createBitmap(size, size)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray().takeIf { it.size <= MAX_ICON_BYTES }?.let { Base64.getEncoder().encodeToString(it) }
            }
        }.getOrNull().orEmpty()
    }.ifEmpty { null }

    companion object {
        const val MAX_ICON_BYTES = 8 * 1024
    }
}

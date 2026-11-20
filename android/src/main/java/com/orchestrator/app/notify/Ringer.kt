package com.orchestrator.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.orchestrator.app.R
import com.orchestrator.app.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Makes the phone ring so it can be found: the ringtone on the alarm stream at full volume (heard
 * on silent and in Do Not Disturb's "alarms only"), and vibration, until "Found it" is tapped,
 * the other device says stop, or a minute has passed. The alarm volume is put back afterwards.
 */
class Ringer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var player: MediaPlayer? = null
    private var volumeBefore: Int? = null
    private val timeUp = Runnable { stop() }

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    fun start(from: String) = handler.post {
        stopNow()
        _ringing.value = true
        notify(from)
        attempt {
            volumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        attempt {
            val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, sound)
                isLooping = true
                prepare()
                start()
            }
        }
        attempt { vibrate() }
        handler.postDelayed(timeUp, LONGEST_MS)
    }

    private fun vibrate() {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun notify(from: String) {
        if (!manager.areNotificationsEnabled()) return
        val found = PendingIntent.getBroadcast(context, 0, ActionReceiver.found(context), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, Presenter.RING)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle("$from is looking for this phone")
            .setContentText("Tap Found it to stop the ringing.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(found)
            .setDeleteIntent(found)
            .addAction(Notification.Action.Builder(null, "Found it", found).build())
            .build()
        manager.notify(Presenter.RING_ID, notification)
    }

    fun stop() {
        handler.post { stopNow() }
    }

    private fun stopNow() {
        handler.removeCallbacks(timeUp)
        attempt { player?.stop() }
        attempt { player?.release() }
        player = null
        attempt { context.getSystemService(Vibrator::class.java)?.cancel() }
        volumeBefore?.let { before -> attempt { audio.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) } }
        volumeBefore = null
        manager.cancel(Presenter.RING_ID)
        _ringing.value = false
    }

    companion object {
        const val LONGEST_MS = 60_000L
    }
}

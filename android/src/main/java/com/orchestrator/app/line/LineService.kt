package com.orchestrator.app.line

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import com.orchestrator.api.Battery
import com.orchestrator.app.MainActivity
import com.orchestrator.app.R
import com.orchestrator.app.attempt
import com.orchestrator.app.container
import com.orchestrator.app.notify.Presenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the line to the server open while the phone is signed in and "Stay connected" is on, so
 * what the other devices send arrives at once.
 *
 * Why a foreground service of type `specialUse`: the line is one long-lived connection to the
 * person's own server, held for as long as they're signed in. Android 14–16 offer no better fit:
 * - `dataSync` is for transfers that finish: since Android 15 it's stopped after 6 hours in any
 *   24, and can't be started from BOOT_COMPLETED at all;
 * - `connectedDevice` needs Bluetooth, NFC, USB or a network-change permission the app has no
 *   other use for, and is meant for talking to a device nearby, not a server;
 * - `remoteMessaging` is the closest in spirit ("continuity of messages between devices"), but
 *   it's for text messaging apps;
 * - Firebase push isn't possible: the server is the person's own, with no Google account.
 * `specialUse` is what the platform provides for a case no type covers, with the reason declared
 * in the manifest (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`); it has no time limit and may be started
 * at boot. The person can turn it off (You › Stay connected); a look every 15 minutes
 * ([FetchWorker]) then takes its place.
 */
class LineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var network: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c = container
        // Straight away: a service started in the foreground has a few seconds to show its notification.
        val shown = attempt {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Presenter.LINE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Presenter.LINE_ID, notification())
        }
        if (shown.isFailure) {
            // Android wouldn't allow it just now (started from the background): the 15-minute look carries on.
            stopSelf()
            return
        }
        scope.launch { c.line.run() }
        // Signed out, or "Stay connected" turned off: nothing to keep open.
        scope.launch { c.settings.collect { if (!wanted(it)) stopSelf() } }

        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                // The first one is just the network there already; after that, it changed.
                val changed = current != null && current != network
                current = network
                if (changed) c.line.kick() else c.line.nudge()
            }
        }
        attempt { connectivity.registerDefaultNetworkCallback(callback) }.onSuccess { network = callback }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery(intent)?.let(c.line::battery)
            }
        }
        // A system broadcast (ACTION_BATTERY_CHANGED is sticky and protected): no other app can send it.
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryReceiver = receiver
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        network?.let { attempt { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        batteryReceiver?.let { attempt { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = Notification.Builder(this, Presenter.LINE)
        .setSmallIcon(R.drawable.ic_stat_sparkle)
        .setContentTitle("Connected to your devices")
        .setOngoing(true)
        .setShowWhen(false)
        .setLocalOnly(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private fun wanted(settings: com.orchestrator.app.data.Settings) = settings.signedIn && settings.alwaysOn

        /**
         * Starts or stops the service to match the sign-in and the person's choice. From the
         * background Android may refuse to start it (unless battery optimisation is off for the
         * app): that's not an error, the periodic look carries on until the app is next opened.
         */
        fun sync(context: Context) {
            val intent = Intent(context, LineService::class.java)
            if (wanted(context.container.settings.value)) attempt { context.startForegroundService(intent) }
            else context.stopService(intent)
        }

        /** How the battery is, from ACTION_BATTERY_CHANGED. */
        fun battery(intent: Intent?): Battery? {
            intent ?: return null
            val percent = BatteryReport.percent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) ?: return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return Battery(percent, charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }

        /** How the battery is right now (the last broadcast is kept by the system). */
        fun battery(context: Context): Battery? = battery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }
}

package com.orchestrator.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.orchestrator.api.Payload
import com.orchestrator.app.data.Approvals
import com.orchestrator.app.data.KeystoreVault
import com.orchestrator.app.data.Prefs
import com.orchestrator.app.data.Settings
import com.orchestrator.app.devices.Activities
import com.orchestrator.app.devices.DeviceBook
import com.orchestrator.app.devices.Inbox
import com.orchestrator.app.devices.Messenger
import com.orchestrator.app.line.FetchWorker
import com.orchestrator.app.line.Line
import com.orchestrator.app.line.LineService
import com.orchestrator.app.link.LinkGate
import com.orchestrator.app.link.Session
import com.orchestrator.app.net.ServerApi
import com.orchestrator.app.net.SignIn
import com.orchestrator.app.notify.AppNames
import com.orchestrator.app.notify.Coalescer
import com.orchestrator.app.notify.MirrorListener
import com.orchestrator.app.notify.Presenter
import com.orchestrator.app.notify.Ringer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the app is made of, built once. */
class Container(private val context: Context) {
    /** Work that must outlive a screen (finishing a sign-in, sending something on its way). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = Prefs(context, KeystoreVault())

    val settings: StateFlow<Settings> = prefs.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { prefs.current() })

    /** The one HTTP client. The sign-in only goes to the server (never anywhere else). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(ServerApi.authInterceptor({ settings.value.server }, { settings.value.token }))
        .build()

    val api = ServerApi(http, { settings.value.server }, { settings.value.token }, onSignedOut = { scope.launch { forget() } })

    /** For answering other apps (the provider): the whole call, start to finish, within ten seconds. */
    private val quickApi = ServerApi(
        http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build(),
        { settings.value.server }, { settings.value.token }, onSignedOut = { scope.launch { forget() } },
    )

    val signIn = SignIn(context, api, prefs)

    val approvals = Approvals(context.getSharedPreferences("approvals", Context.MODE_PRIVATE))
    val book = DeviceBook(api)
    val messenger = Messenger(api, book, { settings.value })
    val activities = Activities(context.getSharedPreferences("activities", Context.MODE_PRIVATE))
    val ringer = Ringer(context)
    val appNames = AppNames(context)

    /** How many of the app's pages are on screen: with one, the person is looking at the app. */
    private var resumed = 0
    val inForeground: Boolean get() = resumed > 0

    val presenter = Presenter(context, ringer, { inForeground }, MirrorListener::cancel)

    private val inbox = Inbox({ settings.value }, presenter, activities, context.getSharedPreferences("inbox", Context.MODE_PRIVATE))

    val line = Line(
        http = http,
        settings = { settings.value },
        onMessage = inbox::receive,
        onDevices = book::set,
        onRefused = { attempt { register() } },
        battery = { LineService.battery(context) },
    )

    /** This phone's notifications on their way out: at most one for each every two seconds. */
    val outbox = Coalescer<Payload.Notification>(scope, 2_000, System::currentTimeMillis) { _, payload -> messenger.sendQuietly(payload) }

    val gate = LinkGate(
        session = { settings.value.takeIf { it.signedIn }?.let { Session(it.server, it.user?.username.orEmpty(), it.user?.displayName.orEmpty()) } },
        approvals = approvals,
        ticket = { app -> runBlocking { quickApi.ticket(app) } },
        publish = { activity -> runBlocking { Messenger(quickApi, book, { settings.value }).send(activity) } },
    )

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed++ }
            override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // The line follows the sign-in and "Stay connected"; the 15-minute look is there whenever someone is signed in.
        scope.launch {
            settings.map { it.signedIn to it.alwaysOn }.distinctUntilChanged().collect { (signedIn, _) ->
                if (signedIn) FetchWorker.schedule(context) else FetchWorker.cancel(context)
                LineService.sync(context)
            }
        }
        // What this phone can do changed ("Show others' notifications here"): tell the server.
        scope.launch {
            settings.map { it.signedIn to it.capabilities }.distinctUntilChanged().drop(1).collect { (signedIn, _) ->
                if (signedIn) attempt { register() }
            }
        }
    }

    /** Tells the server what this phone is, again: its name, key and what it can do. */
    suspend fun register() {
        val s = settings.value
        if (s.token == null || s.publicKey.isEmpty()) return
        val device = api.register(SignIn.registration(s.deviceName.ifBlank { SignIn.deviceName(context) }, s.publicKey, s.capabilities))
        if (device.id != s.deviceId || device.name != s.deviceName) prefs.device(device.id, device.name)
    }

    /** What's waiting on the server for this phone, when the line isn't open: fetched, dealt with, acknowledged. */
    suspend fun fetchWaiting() {
        val done = api.waiting().filter { attempt { inbox.receive(it) }.getOrDefault(false) }.map { it.id }
        if (done.isNotEmpty()) api.ack(done)
    }

    fun sendDismiss(key: String) {
        scope.launch { messenger.sendQuietly(Payload.Dismiss(key)) }
    }

    /** Signing out removes this phone from the person's devices, which ends its sign-in on the server. */
    fun signOut() {
        scope.launch {
            val id = settings.value.deviceId
            if (id.isNotEmpty()) attempt { api.remove(id) }
            forget()
        }
    }

    /** Forgets the sign-in and everything that came with it; the apps' approvals were for that person. */
    private suspend fun forget() {
        prefs.signOut()
        approvals.clear()
        activities.clear()
        book.clear()
        outbox.clear()
        ringer.stop()
        presenter.cancelAll()
    }
}

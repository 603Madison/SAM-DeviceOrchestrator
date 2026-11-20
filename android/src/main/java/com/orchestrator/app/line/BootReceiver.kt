package com.orchestrator.app.line

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The phone started, or the app was updated: open the line again if the person is signed in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        FetchWorker.schedule(context)
        LineService.sync(context)
    }
}

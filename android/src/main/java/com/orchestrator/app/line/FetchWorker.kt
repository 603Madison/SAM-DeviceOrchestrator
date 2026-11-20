package com.orchestrator.app.line

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orchestrator.app.attempt
import com.orchestrator.app.container
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * The fallback for when the line isn't open (the person turned "Stay connected" off, or Android
 * stopped the service): every 15 minutes or so, fetch what's waiting, deal with it, and say how
 * the battery is.
 */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.value.signedIn) return Result.success()
        // The service may be allowed to start again by now.
        LineService.sync(applicationContext)
        if (c.line.connected.value) return Result.success()
        val fetched = attempt { c.fetchWaiting() }
        attempt { c.api.status(LineService.battery(applicationContext)) }
        // Asked to ring with no service to keep the app awake: stay until it's found or the minute is up.
        var waited = 0L
        while (c.ringer.ringing.value && waited < 70_000L) {
            delay(1_000)
            waited += 1_000
        }
        return if (fetched.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "fetch"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FetchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

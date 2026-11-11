package com.prism.music.data.imports

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.container
import java.util.concurrent.TimeUnit

/**
 * YouTube Music (live)'s job (1.8.1): every few hours, on any network, one read of the History page ([YouTubeLive]).
 * Only while signed in and turned on: otherwise it cancels itself, and nothing is asked.
 */
class YouTubeLiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val prefs = applicationContext.getSharedPreferences(YouTubeLive.PREFS, Context.MODE_PRIVATE)
        if (!c.settings.current.isLoggedIn || !YouTubeLive.isOn(prefs)) { cancel(applicationContext); return Result.success() }
        c.imports.runYouTubeLive()
        return Result.success()
    }

    /** The app's [YouTubeLive.Jobs]. */
    class Jobs(private val context: Context) : YouTubeLive.Jobs {
        override fun schedule() = schedule(context)
        override fun cancel() = cancel(context)
    }

    companion object {
        private const val NAME = "youtube_live"
        const val HOURS = 3L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<YouTubeLiveWorker>(HOURS, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }

        fun workName() = NAME
    }
}

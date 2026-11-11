package com.prism.music.data.spotify

import android.content.Context
import android.net.ConnectivityManager
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
 * The Spotify connection's background job (1.8.1): every few hours while connected and a feature is on, nothing
 * otherwise ([Spotify.schedule] cancels it). Each feature decides how often it really asks ([Spotify.run]).
 */
class SpotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        // Not connected (any more): nothing to do, and no reason to come back.
        if (!c.spotify.connected) { cancel(applicationContext); return Result.success() }
        val unmetered = runCatching {
            !(applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        }.getOrDefault(false)
        for (f in SpotifyFeature.entries) {
            if (isStopped) break
            c.spotify.run(f, scheduled = true, unmetered = unmetered)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "spotify_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpotifyWorker>(3, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }

        fun workName() = NAME
    }
}

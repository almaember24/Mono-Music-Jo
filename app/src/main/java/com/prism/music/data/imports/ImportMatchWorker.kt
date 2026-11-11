package com.prism.music.data.imports

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.Notifications
import com.prism.music.tr
import java.util.concurrent.TimeUnit

/**
 * Matches imported songs to YouTube Music in the background (1.8.1): on Wi-Fi unless the listener allows mobile data,
 * a few minutes at a time (it carries on where it stopped, after a restart too), a quiet notification saying how far
 * it's got. Songs nothing was found for are tried again weekly ([enqueue]'s weekly run).
 */
class ImportMatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val until = System.currentTimeMillis() + RUN_MS
        val finished = runCatching {
            c.imports.matchSome(until, { isStopped }) { done, total ->
                setProgress(workDataOf(DONE to done, TOTAL to total))
                notify(done, total)
            }
        }.getOrDefault(false)
        cancelNotice()
        // More to do (the time ran out, or the network went): WorkManager runs it again shortly.
        return if (finished) Result.success() else Result.retry()
    }

    private fun notify(done: Int, total: Int) {
        val ctx = applicationContext
        if (!Notifications.allowed(ctx) || total <= 0) return
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.imp_title)).setDescription(tr(R.string.imp_channel_desc)).build()
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.imp_matching_notice))
            .setContentText(tr(R.string.imp_matching_n_of_n, done, total))
            .setProgress(total, done.coerceAtMost(total), false)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(false)
            .setContentIntent(Notifications.openIntent(ctx, "prism://bring", NOTICE_ID))
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(ctx).notify(CHANNEL, NOTICE_ID, n) }
    }

    private fun cancelNotice() = runCatching { NotificationManagerCompat.from(applicationContext).cancel(CHANNEL, NOTICE_ID) }

    companion object {
        private const val NAME = "import_match"
        private const val WEEKLY = "import_match_weekly"
        private const val CHANNEL = "imports"
        private const val NOTICE_ID = 4_901
        /** One run's share of time; WorkManager stops a run at 10 minutes. */
        private const val RUN_MS = 8 * 60_000L
        const val DONE = "done"
        const val TOTAL = "total"

        private fun constraints(mobile: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Starts matching (or picks up its network rule again), and keeps the weekly retry lined up. */
        fun enqueue(context: Context, mobile: Boolean) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(
                NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ImportMatchWorker>().setConstraints(constraints(mobile))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build(),
            )
            wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.UPDATE, weekly(mobile))
        }

        /** The weekly retry: its first run a week on (at once, it would match beside the one-time run just started). */
        internal fun weekly(mobile: Boolean) = PeriodicWorkRequestBuilder<ImportMatchWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(7, TimeUnit.DAYS).setConstraints(constraints(mobile)).build()

        fun workName() = NAME
    }
}

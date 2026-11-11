package com.prism.music.data.imports

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.R
import com.prism.music.data.Notifications
import com.prism.music.tr
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Keeping an imported history current (1.8.1, the owner's choice): Spotify and Google Takeout give a history only as a
 * download, so Prism says when the last one was brought in and, every few months, gently reminds the listener to ask
 * for a fresh one (bringing it adds only what's new). Only for a source the listener has imported at least once: never
 * for anyone else. Off, 1, 3 (the default) or 6 months, in the imports' own preferences; checked once a day.
 */
object ImportReminders {
    /** The sources that come as a download (Last.fm's scrobbles Prism asks for itself). */
    val SOURCES = listOf(ImportSource.SPOTIFY, ImportSource.TAKEOUT)
    val CHOICES = listOf(0, 1, 3, 6)
    const val DEFAULT_MONTHS = 3

    /** Where each source's fresh download is asked for. */
    fun requestUrl(source: ImportSource): String? = when (source) {
        ImportSource.SPOTIFY -> "https://www.spotify.com/account/privacy/"
        ImportSource.TAKEOUT -> "https://takeout.google.com/"
        else -> null
    }

    internal const val MONTHS = "remind_months"
    internal fun lastKey(s: ImportSource) = "last_import_${s.key}"
    internal fun remindedKey(s: ImportSource) = "reminded_${s.key}"

    fun months(prefs: SharedPreferences): Int = prefs.getInt(MONTHS, DEFAULT_MONTHS).takeIf { it in CHOICES } ?: DEFAULT_MONTHS
    fun lastImport(prefs: SharedPreferences, s: ImportSource): Long? = prefs.getLong(lastKey(s), 0).takeIf { it > 0 }

    /**
     * The sources a reminder is due for at [now]: imported once at least, [months] (0: never) since the last import
     * and since the last reminder, by the calendar of [zone].
     */
    fun due(now: Long, months: Int, lastImport: Map<ImportSource, Long>, reminded: Map<ImportSource, Long>, zone: ZoneId = ZoneId.systemDefault()): List<ImportSource> {
        if (months <= 0) return emptyList()
        return SOURCES.filter { s ->
            val last = lastImport[s] ?: return@filter false
            val since = maxOf(last, reminded[s] ?: 0L)
            Instant.ofEpochMilli(since).atZone(zone).plusMonths(months.toLong()).toInstant().toEpochMilli() <= now
        }
    }

    fun due(prefs: SharedPreferences, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ImportSource> = due(
        now, months(prefs),
        SOURCES.mapNotNull { s -> lastImport(prefs, s)?.let { s to it } }.toMap(),
        SOURCES.mapNotNull { s -> prefs.getLong(remindedKey(s), 0).takeIf { it > 0 }?.let { s to it } }.toMap(),
        zone,
    )

    /** Whether the daily check is wanted at all: reminders on, and a download-kind source imported. */
    fun wanted(prefs: SharedPreferences): Boolean = months(prefs) > 0 && SOURCES.any { lastImport(prefs, it) != null }

    private const val WORK = "import_reminder"

    /** Lines the daily check up when it's wanted, and takes it away when it isn't. */
    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(HistoryImports.PREFS, Context.MODE_PRIVATE)
        val wm = WorkManager.getInstance(context)
        if (wanted(prefs)) {
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ImportReminderWorker>(1, TimeUnit.DAYS).build())
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    /** Posts the reminders due now (a quiet notification each), and notes them so the next is months away. */
    fun remind(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(HistoryImports.PREFS, Context.MODE_PRIVATE)
        val due = due(prefs, now)
        if (due.isEmpty()) return
        due.forEach { s ->
            val last = lastImport(prefs, s) ?: return@forEach
            val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(last))
            val (title, text) = when (s) {
                ImportSource.TAKEOUT -> tr(R.string.imp_remind_takeout_title) to tr(R.string.imp_remind_takeout_text, day)
                else -> tr(R.string.imp_remind_spotify_title) to tr(R.string.imp_remind_spotify_text, day)
            }
            Notifications.post(context, Notifications.IMPORT_REMINDERS, NOTICE_ID + s.ordinal, title, text, "prism://bring")
        }
        prefs.edit().apply { due.forEach { putLong(remindedKey(it), now) } }.apply()
    }

    private const val NOTICE_ID = 4_910
}

/** The daily look at whether a fresh download is due ([ImportReminders]). */
class ImportReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { ImportReminders.remind(applicationContext) }
        return Result.success()
    }
}

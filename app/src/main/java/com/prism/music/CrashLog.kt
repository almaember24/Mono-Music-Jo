package com.prism.music

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If Prism crashes, what went wrong is written to a file on the phone (files/crashes), kept for the last few crashes,
 * so it can be read later: Settings → About → Diagnostics shows the last one and Share diagnostics includes them. A
 * crash's details otherwise only show over adb, and a crash that can't be reproduced can't be fixed (owner, 1.7.2: "it
 * crashed once while exiting the player"). Nothing is sent anywhere; the listener decides whether to share it.
 */
object CrashLog {
    private const val KEEP = 3
    private const val MAX_CHARS = 48_000

    private fun dir(context: Context) = File(context.filesDir, "crashes")

    /** Installed first thing at start-up, in front of Android's own handler (which still shows the crash dialog). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val d = dir(context).apply { mkdirs() }
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            append("Prism ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.APPLICATION_ID}\n")
            append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("When: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            append("Thread: ${thread.name}\n\n")
            append(stack.take(MAX_CHARS))
        }
        File(d, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        // Only the last few.
        d.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /**
     * Ways Prism ended that its own handler never sees (Android 11+ keeps the reason): killed for memory while playing or
     * on screen, a native crash, an ANR (#103 on 1.7.3: Prism kept restarting while "Last crash" still showed a Java
     * crash from the day before). Each new one since the last start is saved like a crash, with Android's own trace for
     * an ANR or a native crash, and noted in the diagnostics log. Exits the listener caused (swiping Prism away, force
     * stop, an update) and kills of Prism idling in the background are left out.
     */
    fun recordExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val seen = prefs.getLong("exit_seen", 0L)
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrNull().orEmpty()
            .filter { it.timestamp > seen && it.processName == context.packageName }
        if (exits.isEmpty()) return
        prefs.edit().putLong("exit_seen", exits.maxOf { it.timestamp }).apply()
        val d = dir(context).apply { mkdirs() }
        for (e in exits.sortedBy { it.timestamp }) {
            val visible = e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> if (visible) "closed by Android: the phone ran low on memory" else null
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "closed by Android: too much CPU or memory"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> if (visible) "closed by Android (signal ${e.status})" else null
                android.app.ApplicationExitInfo.REASON_OTHER -> if (visible) "closed by Android (other)" else null
                // Java crashes are written by the handler above; the rest the listener did (or an update).
                else -> null
            } ?: continue
            val trace = if (e.reason == android.app.ApplicationExitInfo.REASON_ANR || e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)
                runCatching { e.traceInputStream?.bufferedReader()?.use { r -> CharArray(MAX_CHARS).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrNull()
            else null
            val text = buildString {
                append("Prism ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.APPLICATION_ID}\n")
                append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                append("When: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))}\n")
                append("Prism ended: $why\n")
                append("While: ${if (visible) "playing or on screen" else "in the background"} (importance ${e.importance}); memory ${e.pss / 1024} MB PSS, ${e.rss / 1024} MB RSS\n")
                e.description?.let { append("Android says: $it\n") }
                trace?.let { append("\n").append(it) }
            }
            File(d, "crash-${e.timestamp}.txt").writeText(text)
            runCatching { com.prism.music.playback.AudioDiagnostics.log("exit", "Prism ended at ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))}: $why${e.description?.let { " ($it)" } ?: ""}") }
        }
        d.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** The saved crashes, newest first. */
    fun all(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    /** When the newest one happened (ms), or null when there's none. */
    fun lastAt(context: Context): Long? = all(context).firstOrNull()?.name?.removePrefix("crash-")?.removeSuffix(".txt")?.toLongOrNull()

    /** Every saved crash, for Share diagnostics. */
    fun text(context: Context): String = all(context).joinToString("\n\n") { f -> "--- ${f.name} ---\n" + runCatching { f.readText() }.getOrDefault("") }

    fun clear(context: Context) { all(context).forEach { it.delete() } }
}

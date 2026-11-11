package com.prism.music.playback

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Printer

/**
 * What kept the screen from drawing (1.7.4; GitHub #100: "stuttering everywhere", on a fast phone too). While
 * diagnostics are on (Settings → About → Diagnostics), every piece of main-thread work longer than [STALL_MS] goes into
 * the diagnostics log with what it was, and, for one still running after [FIRST_SAMPLE_MS], where the main thread was
 * at that moment (a stack sample, taken from a thread of its own). Compose's frames and effects all show up as one of
 * its own callbacks, so the sample is what names the code.
 *
 * Off, it costs nothing: Android only builds the per-message description while a [Printer] is set.
 */
object MainThreadWatch {
    @Volatile private var on = false
    @Volatile private var startedAt = 0L
    /** Bumped for every message, so the sampler knows which one it's looking at. */
    @Volatile private var message = 0L
    @Volatile private var what: String = ""
    private val samples = ArrayList<String>(4)
    private var sampler: Thread? = null

    /** Also logged to logcat (tag PrismStall) when [toLogcat], for looking at a test build without the log file. */
    @Volatile var toLogcat = false

    private val printer = Printer { s ->
        if (s.startsWith(">")) {
            what = s
            message++
            startedAt = SystemClock.uptimeMillis()
        } else {
            val took = SystemClock.uptimeMillis() - startedAt
            startedAt = 0L
            val taken = synchronized(samples) { samples.toList().also { samples.clear() } }
            if (took >= STALL_MS) report(took, what, taken)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == on) return
        on = enabled
        Looper.getMainLooper().setMessageLogging(if (enabled) printer else null)
        if (enabled && sampler == null) sampler = Thread(::sample, "PrismStallWatch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
        else if (enabled) sampler?.let { java.util.concurrent.locks.LockSupport.unpark(it) }
    }

    /** Samples the main thread's stack while a message runs long: at [FIRST_SAMPLE_MS], then every [SAMPLE_EVERY_MS] (≤ 4). */
    private fun sample() {
        val main = Looper.getMainLooper().thread
        var sampledMessage = -1L
        var taken = 0
        var nextAt = 0L
        while (true) {
            // Turned off: asleep until it's turned on again (it used to wake twice a second for nothing).
            if (!on) { java.util.concurrent.locks.LockSupport.park(this); continue }
            val start = startedAt
            val id = message
            if (start == 0L) { Thread.sleep(16); continue }
            if (id != sampledMessage) { sampledMessage = id; taken = 0; nextAt = start + FIRST_SAMPLE_MS }
            val now = SystemClock.uptimeMillis()
            if (taken < 4 && now >= nextAt) {
                val stack = runCatching { main.stackTrace }.getOrNull()
                // Still the same message? Then the sample belongs to it.
                if (stack != null && message == id && startedAt == start) {
                    synchronized(samples) { if (samples.size < 4) samples += "at ${now - start} ms: " + describe(stack) }
                    taken++
                }
                nextAt = now + SAMPLE_EVERY_MS
            }
            // Until the next sample is due (or a fresh look at the next message).
            Thread.sleep((nextAt - SystemClock.uptimeMillis()).coerceIn(4L, 16L))
        }
    }

    // A slow phone drops frames one after another while it scrolls: a few are logged in full, the rest counted, so the
    // log (which the audio's lines share, ~1.5 MB) isn't swamped.
    private var windowStart = 0L
    private var inWindow = 0
    private var skipped = 0

    private fun report(took: Long, what: String, taken: List<String>) {
        val now = SystemClock.uptimeMillis()
        if (now - windowStart > WINDOW_MS) {
            if (skipped > 0) AudioDiagnostics.log("stall", "… $skipped more over $STALL_MS ms in the ${WINDOW_MS / 1000} s before")
            windowStart = now; inWindow = 0; skipped = 0
        }
        if (++inWindow > PER_WINDOW) { skipped++; return }
        val line = "${took} ms: ${shorten(what)}" + if (taken.isEmpty()) "" else taken.joinToString("") { "\n    $it" }
        AudioDiagnostics.log("stall", line)
        if (toLogcat) Log.w(TAG, line)
    }

    /** The message without Android's framing: "Choreographer frame", or the handler and what it ran. */
    private fun shorten(s: String): String {
        val body = s.removePrefix(">>>>> Dispatching to ")
        return when {
            "Choreographer\$FrameDisplayEventReceiver" in body -> "a frame (composition, layout, drawing)"
            else -> body.replace(Regex("@[0-9a-f]+"), "").replace(Regex("\\{[0-9a-f]+\\}"), "").take(240)
        }
    }

    /**
     * A sampled stack, compact: the innermost frames (what was actually running) and then Prism's own frames below
     * them (which of Prism's code got there), at most [MAX_FRAMES] in all.
     */
    internal fun describe(stack: Array<StackTraceElement>): String {
        val inner = stack.take(INNER_FRAMES)
        val ours = stack.drop(INNER_FRAMES).filter { it.className.startsWith("com.prism.") }.take(MAX_FRAMES - INNER_FRAMES)
        return (inner + ours).joinToString(" < ") { e ->
            "${e.className.substringAfterLast('.')}.${e.methodName}" + (e.lineNumber.takeIf { it > 0 }?.let { ":$it" } ?: "")
        }
    }

    private const val TAG = "PrismStall"
    /** Two frames at 60 Hz: work this long drops at least one frame. */
    private const val STALL_MS = 32L
    private const val FIRST_SAMPLE_MS = 24L
    private const val SAMPLE_EVERY_MS = 40L
    private const val INNER_FRAMES = 5
    private const val MAX_FRAMES = 14
    private const val WINDOW_MS = 2_000L
    private const val PER_WINDOW = 5
}

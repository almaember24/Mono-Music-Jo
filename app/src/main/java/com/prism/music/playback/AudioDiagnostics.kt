package com.prism.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Playback diagnostics (Settings → About → Playback diagnostics, off by default): a log on the phone of what the audio
 * did, for chasing stutters on one listener's headphones. It records the AudioTrack being made and remade, underruns
 * (how long the player went without feeding it), Media3's own audio warnings, every audio device appearing and going,
 * every time Prism points the player at an output, how long Prism's own audio processing takes per buffer, and the
 * heavy background work (song scans, Sing, video sync) running beside the music. The listener marks the moments they
 * heard a stutter; [export] puts it all, with this app's own logcat, into one text file they can share. Nothing leaves
 * the phone unless they share it.
 *
 * Writing happens on its own thread (dropped when it falls behind), so the log can't slow the audio it watches.
 */
object AudioDiagnostics {
    @Volatile var enabled = false
        private set

    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4_000), ThreadPoolExecutor.DiscardPolicy())

    fun init(context: Context) {
        file = File(context.filesDir, "diagnostics/audio.log").also { it.parentFile?.mkdirs() }
    }

    /** Turned on or off from the setting; [header] describes the phone and the audio settings at the start. */
    fun setEnabled(on: Boolean, header: () -> String) {
        if (on == enabled) return
        if (on) {
            enabled = true
            log("start", header())
        } else {
            log("stop", "diagnostics switched off")
            enabled = false
        }
    }

    /** One line in the log ([kind] is a short tag: "track", "underrun", "device", "route", "scan", "mark"…). */
    fun log(kind: String, message: String) {
        if (!enabled) return
        // The release check's night hunt reads these from logcat (a release build's files can't be read over adb):
        // only when it sets `adb shell setprop log.tag.PrismDiag DEBUG`, never otherwise.
        if (android.util.Log.isLoggable("PrismDiag", android.util.Log.DEBUG)) android.util.Log.d("PrismDiag", "[$kind] $message")
        val line = "${synchronized(stamp) { stamp.format(Date()) }} [$kind] $message\n"
        writer.execute { append(line) }
    }

    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            // Kept to the last ~1.5 MB: the older half goes once it's full.
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText("… (older lines dropped)\n" + keep.substringAfter('\n'))
            }
            f.appendText(line)
        }
    }

    // ------------------------------------------------------------------ Prism's own audio processing

    private class Timing(var buffers: Int = 0, var busyNs: Long = 0, var audioNs: Long = 0, var worstNs: Long = 0, var slow: Int = 0)

    private val timings = LinkedHashMap<String, Timing>()

    /**
     * A processor ([name]) took from [startNs] until now on a buffer of [frames] frames at [rate] Hz (wall time, so a
     * thread kept waiting by other work counts too). Summed for [summary], where a buffer over a fifth of the time it
     * plays for counts as slow; one over half of it is logged on its own.
     */
    fun processed(name: String, startNs: Long, frames: Int, rate: Int) {
        if (!enabled || frames <= 0 || rate <= 0) return
        val took = System.nanoTime() - startNs
        val plays = frames * 1_000_000_000L / rate
        synchronized(timings) {
            val t = timings.getOrPut(name) { Timing() }
            t.buffers++; t.busyNs += took; t.audioNs += plays
            if (took > t.worstNs) t.worstNs = took
            if (took > plays / 5) t.slow++
        }
        if (took > plays / 2) log("slow", "$name took %.1f ms on a %.1f ms buffer".format(took / 1e6, plays / 1e6))
    }

    /** The processing since the last summary, one line per processor; called every few seconds while playing. */
    fun summary(): String? = synchronized(timings) {
        if (timings.isEmpty()) return null
        timings.entries.joinToString("; ") { (n, t) ->
            "$n %.2f%% of real time, worst %.1f ms, %d slow".format(if (t.audioNs > 0) t.busyNs * 100.0 / t.audioNs else 0.0, t.worstNs / 1e6, t.slow)
        }.also { timings.clear() }
    }

    // ------------------------------------------------------------------ Devices

    /** "Bluetooth A2DP 'AirPods Pro' #23" (no addresses: they'd identify the headphones). */
    fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "none (follow the system)"
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (calls/mic)"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) "Bluetooth LE headset"
                else if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) "Bluetooth LE speaker"
                else if (Build.VERSION.SDK_INT >= 33 && d.type == AudioDeviceInfo.TYPE_BLE_BROADCAST) "Bluetooth LE broadcast"
                else "type ${d.type}"
        }
        val dir = when { d.isSink && d.isSource -> "in/out"; d.isSink -> "out"; else -> "in" }
        return "$type '${d.productName}' $dir #${d.id}"
    }

    fun devices(context: Context, which: Int = AudioManager.GET_DEVICES_OUTPUTS): String =
        context.getSystemService(AudioManager::class.java)?.getDevices(which)?.joinToString(", ") { describe(it) } ?: "?"

    // ------------------------------------------------------------------ Export

    /**
     * Everything in one file in the share folder: [about] (the phone and settings now), the log, then this app's own
     * recent logcat (the AudioTrack's and Media3's own lines are there; an app can only read its own).
     */
    fun export(context: Context, about: String): File {
        val out = File(context.cacheDir, "share").apply { mkdirs() }
            .resolve("prism-playback-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        // What's still queued is written first.
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
        val logcat = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "4000", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor(5, TimeUnit.SECONDS) }
        }.getOrElse { "(couldn't read logcat: $it)" }
        out.writeText(
            buildString {
                append("Prism playback diagnostics\n").append(about).append("\n\n")
                append("===== Log =====\n").append(runCatching { file?.readText() }.getOrNull() ?: "(empty)").append('\n')
                // Crashes kept on the phone (CrashLog), newest first: the details of a crash that happened before now.
                val crashes = com.prism.music.CrashLog.text(context)
                if (crashes.isNotBlank()) append("===== Crashes =====\n").append(crashes).append('\n')
                append("===== This app's logcat =====\n").append(logcat)
            }
        )
        return out
    }

    /** The phone, Prism and every setting that touches the audio path, as they are now. */
    fun about(c: com.prism.music.AppContainer): String {
        val s = c.settings.current
        val n = c.nerdStats.value
        return buildString {
            append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("Prism: ${com.prism.music.BuildConfig.VERSION_NAME} (${com.prism.music.BuildConfig.VERSION_CODE}) ${com.prism.music.BuildConfig.APPLICATION_ID}\n")
            val metered = c.app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered
            // #372, #373, #377: with no secrets in it (cookie names, never their values).
            append("Account: ${com.prism.music.data.innertube.AccountSummary.of(s.cookie, s.authUser, s.dataSyncId, s.visitorData, s.behavior.accountSavedAt)}; YouTube takes requests as: ${c.innerTube.seen.value?.text ?: "not asked yet"}\n")
            append("Settings: Premium quality ${s.premiumStream} (${c.streams.premiumState.value}), Data saver ${s.dataSaver}, network metered $metered, history paused ${com.prism.music.data.HistoryPause.paused(s.behavior)}, ")
            append("quality ${s.audioQuality}, lossless ${s.lossless}, normalize ${s.normalize}, ")
            append("skip silence ${s.skipSilence}, crossfade ${s.mix} (${s.mixSeconds} s), spatial ${s.spatial} (${(s.spatialAmount * 100).toInt()}%), ")
            append("EQ ${s.eq.enabled}, Sing ${c.sing.on.value}, DJ ${c.dj.onNow}, ")
            append("headphone correction ${c.headphoneEq.active.value?.let { "'${it.profile.name}' at ${(it.strength * 100).toInt()}%" } ?: "none"}\n")
            append("Now: ${n.codec} ${n.sampleRate} Hz → ${n.outputEncoding} ${n.outputSampleRate} Hz, decoder ${n.decoder}, offload ${n.offload}, underruns ${n.underruns}\n")
            append("Outputs: ${devices(c.app)}")
        }
    }

    /** Clears the log (it starts again with a fresh header if diagnostics are on). */
    fun clear(header: () -> String) {
        runCatching { writer.submit { file?.delete() }.get(2, TimeUnit.SECONDS) }
        if (enabled) log("start", header())
    }

    private const val MAX_BYTES = 1_500_000L
}

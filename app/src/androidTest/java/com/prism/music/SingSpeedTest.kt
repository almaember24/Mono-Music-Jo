package com.prism.music

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.net.Uri
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.ModelStore
import com.prism.music.playback.sing.OrtLoader
import com.prism.music.playback.sing.PcmDecoder
import com.prism.music.playback.sing.Resampler
import com.prism.music.playback.sing.Spleeter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sing's speed on this device (1.8.x, "Sing on more devices"): how many seconds of song the separation gets through per
 * second, how much memory it takes, where the time goes (ONNX Runtime's own profile, per kind of layer), and how far
 * each faster set-up's vocals are from today's. Only with `-e singBench true` (it downloads Sing's ~50 MB); the song is
 * `-e singSong <path>` (a file this app can read; `-e singFrom` seconds in), or made-up sound for timing only.
 * `-e singVariants` picks the set-ups, comma-separated, each made of: one|two (networks), cpu|xnn|nnapi|nnapi16, a
 * thread count, nospin and noarena (Sing's own session options), w1…w4 (threads for the transforms), bg (on a
 * background-priority thread, as Sing runs), prof (a profile), or quick (the quick way) and none (the transforms
 * alone); the first is the one the others are compared with. Build with `-PsingBench` (not debuggable: a debuggable
 * app's Kotlin times several times slower). Results go to logcat (PrismSingBench) and `singbench.txt` in this app's
 * external files. [quality] is the same against songs with their parts known.
 */
@Measurement
@RunWith(AndroidJUnit4::class)
class SingSpeedTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun say(s: String) { Log.i("PrismSingBench", s); lines += s }

    private class Variant(val name: String) {
        val parts = name.split('-')
        val two = "one" !in parts
        val provider = parts.firstOrNull { it in setOf("cpu", "xnn", "nnapi", "nnapi16") } ?: "cpu"
        val threads = parts.firstNotNullOfOrNull { it.toIntOrNull() } ?: 4
        val profile = "prof" in parts
    }

    @Test fun measure() {
        assumeTrue(args.getString("singBench") == "true")
        args.getString("singPoolPriority")?.toIntOrNull()?.let { Spleeter.workerPriority = it }
        // A minute per read: OkHttp's 10 s default timed the download out while GitHub was slow (1.8.3).
        val store = ModelStore(context, OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build())
        // No engine for this processor (a 32-bit x86 TV emulator): the quick way and the transforms only.
        val runtime = ModelStore.Model.singRuntime()
        if (runtime != null) {
            val ok = runBlocking { store.download(runtime) && store.download(ModelStore.Model.SING_MODEL) }
            check(ok) { "Sing's engine didn't download: ${store.states.value}" }
        }
        val env = runtime?.let { OrtLoader.environment(store.dir(it)) }
        val dir = store.dir(ModelStore.Model.SING_MODEL)
        say("device ${android.os.Build.MODEL} (${android.os.Build.HARDWARE}), ${if (Process.is64Bit()) "64" else "32"}-bit ${runtime?.id ?: "no engine (${android.os.Build.SUPPORTED_ABIS.joinToString()})"}, ${Runtime.getRuntime().availableProcessors()} cores, Android ${android.os.Build.VERSION.RELEASE}")

        val seconds = args.getString("singSeconds")?.toDoubleOrNull() ?: 36.0
        val song = song(seconds)
        // How stereo the song is: its sides' share of its sound.
        var side = 0.0; var all = 0.0
        for (i in 0 until song.size / 2) { val l = song[i * 2]; val r = song[i * 2 + 1]; side += (l - r) * (l - r); all += l * l + r * r }
        say("sides ${"%.1f".format(10 * log10(side / all.coerceAtLeast(1e-20)))} dB of the sound")
        // The FFT alone on this device.
        run {
            val fft = com.prism.music.playback.sing.Fft(4096)
            val re = DoubleArray(4096) { kotlin.math.sin(it * 0.1) }; val im = DoubleArray(4096)
            repeat(2) {
                val t0 = System.nanoTime()
                repeat(2048) { fft.transform(re, im) }
                say("2048 FFTs of 4096: ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
        val variants = (args.getString("singVariants") ?: "two-cpu-4-w1,two-cpu-4-nospin-noarena,one-cpu-4-nospin-noarena,quick").split(',').map(::Variant)
        var reference: FloatArray? = null
        for (v in variants) {
            if (env == null && "quick" !in v.parts && "none" !in v.parts) { say("${v.name}: no engine here"); continue }
            val r = runCatching { run(env, dir, v, song) }.getOrElse { say("${v.name}: FAILED $it"); continue }
            val vocals = r.first
            val sdr = reference?.let { ref -> sdr(ref, vocals) }
            if (reference == null) reference = vocals
            say("${v.name}: ${r.second}${sdr?.let { "; vs ${variants.first().name} ${"%.1f".format(it)} dB" } ?: ""}")
        }
        File(context.getExternalFilesDir(null), "singbench.txt").appendText(lines.joinToString("\n", postfix = "\n\n"))
    }

    /**
     * How close each way's vocals and backing come to the real ones, on songs whose parts are known: `-e singQuality
     * <folder>` of multitrack stem files (MUSDB18's: the mix first, the vocals fifth). Per way, the median and mean
     * signal-to-distortion ratio (dB) of the vocals and of the backing (what Sing plays), and "as it is" for the song
     * left untouched.
     */
    @Test fun quality() {
        val folder = args.getString("singQuality")?.let(::File)
        assumeTrue(args.getString("singBench") == "true" && folder != null && ModelStore.Model.singRuntime() != null)
        // A minute per read: OkHttp's 10 s default timed the download out while GitHub was slow (1.8.3).
        val store = ModelStore(context, OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build())
        val runtime = ModelStore.Model.singRuntime()!!
        check(runBlocking { store.download(runtime) && store.download(ModelStore.Model.SING_MODEL) })
        val env = OrtLoader.environment(store.dir(runtime))
        val dir = store.dir(ModelStore.Model.SING_MODEL)
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val vocalsNet = env.createSession(File(dir, "vocals.fp16.onnx").path, opts)
        val backingNet = env.createSession(File(dir, "accompaniment.fp16.onnx").path, opts)
        val ways = (args.getString("singWays") ?: "full,light,quick-0.6,quick-0.7,quick-0.8,quick-0.9,asis").split(',')
        val vocalSdr = ways.associateWith { ArrayList<Double>() }
        val backSdr = ways.associateWith { ArrayList<Double>() }
        var skipped = 0
        // 00.mp4, 01.mp4, …: a folder adb filled can be read from but not listed.
        for (f in (0 until 100).map { File(folder, "%02d.mp4".format(it)) }.filter { it.isFile }) {
            val mix = decode(f, 0)
            val vocals = decode(f, 4)
            val n = minOf(mix.size, vocals.size)
            var ve = 0.0; var me = 0.0
            for (i in 0 until n) { ve += vocals[i] * vocals[i]; me += mix[i] * mix[i] }
            // An excerpt without singing has no vocals to come close to.
            if (ve < me * 1e-3) { skipped++; continue }
            val backing = FloatArray(n) { mix[it] - vocals[it] }
            for (way in ways) {
                val est = when {
                    way == "asis" -> FloatArray(n)
                    else -> {
                        val sp = when {
                            way == "full" -> Spleeter(env, vocalsNet, backingNet)
                            way == "light" -> Spleeter(env, vocalsNet, null)
                            else -> Spleeter(null, null, null).apply { way.substringAfter('-').toFloatOrNull()?.let { centreFrom = it } }
                        }
                        (sp.feed(mix.copyOf(n)) + sp.finish())
                    }
                }
                vocalSdr.getValue(way) += sdr(vocals, est)
                backSdr.getValue(way) += sdr(backing, FloatArray(n) { mix[it] - est[it] })
            }
        }
        say("quality on ${vocalSdr.values.first().size} songs (${skipped} without singing left out), dB, median / mean:")
        for (way in ways) {
            fun stats(l: List<Double>) = "${"%.2f".format(l.sorted()[l.size / 2])} / ${"%.2f".format(l.average())}"
            say("  $way: vocals ${if (way == "asis") "—" else stats(vocalSdr.getValue(way))}, backing ${stats(backSdr.getValue(way))}")
        }
        vocalsNet.close(); backingNet.close()
        File(context.getExternalFilesDir(null), "singbench.txt").appendText(lines.joinToString("\n", postfix = "\n\n"))
    }

    /** Track [track] of [f] as Spleeter takes it (stereo, 44.1 kHz). */
    private fun decode(f: File, track: Int): FloatArray {
        PcmDecoder(context, Uri.fromFile(f), track).use { dec ->
            val res = Resampler(2, dec.sampleRate, Spleeter.RATE)
            val parts = ArrayList<FloatArray>()
            while (true) parts += res.push(dec.read() ?: break)
            parts += res.finish()
            val out = FloatArray(parts.sumOf { it.size })
            var at = 0
            for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
            return out
        }
    }

    /** The song as Spleeter takes it (stereo, 44.1 kHz), [seconds] long. */
    private fun song(seconds: Double): FloatArray {
        val frames = (seconds * Spleeter.RATE).toInt()
        val path = args.getString("singSong")
        if (path != null) {
            // From [singFrom] seconds in (where the singing is).
            var skip = ((args.getString("singFrom")?.toDoubleOrNull() ?: 0.0) * Spleeter.RATE).toLong() * 2
            PcmDecoder(context, Uri.fromFile(File(path))).use { dec ->
                val rate = dec.sampleRate
                val res = Resampler(2, rate, Spleeter.RATE)
                val out = FloatArray(frames * 2)
                var n = 0
                while (n < frames) {
                    val piece = dec.read() ?: break
                    var p = res.push(piece)
                    if (skip > 0) { val s = minOf(skip, p.size.toLong()).toInt(); skip -= s; p = p.copyOfRange(s, p.size) }
                    val take = minOf(p.size, out.size - n * 2)
                    System.arraycopy(p, 0, out, n * 2, take)
                    n += take / 2
                }
                say("song: ${File(path).name}, ${"%.1f".format(n / Spleeter.RATE.toDouble())} s")
                return out.copyOf(n * 2)
            }
        }
        val rnd = Random(5)
        say("song: made-up sound, $seconds s (timing only)")
        return FloatArray(frames * 2) { i ->
            val t = (i / 2) / Spleeter.RATE.toDouble()
            (0.2 * sin(2 * Math.PI * 220 * t) + 0.1 * sin(2 * Math.PI * 660 * t * (1 + (i and 1) * 0.01)) + 0.05 * (rnd.nextFloat() - 0.5)).toFloat()
        }
    }

    /** One set-up on [song]: the vocals, and what it took. */
    private fun run(env: OrtEnvironment?, dir: File, v: Variant, song: FloatArray): Pair<FloatArray, String> {
        System.gc()
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        var peak = heapBefore
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) { peak = maxOf(peak, Debug.getNativeHeapAllocatedSize()); Thread.sleep(20) }
        }.apply { start() }
        // "none": the transforms alone (no networks); "quick": the quick way (no networks either, nor ONNX Runtime).
        val none = "none" in v.parts
        val quick = "quick" in v.parts
        val opts = if (none || quick) null else OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (v.provider) {
                "xnn" -> {
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to v.threads.toString()))
                }
                "nnapi" -> { setIntraOpNumThreads(v.threads); addNnapi() }
                "nnapi16" -> { setIntraOpNumThreads(v.threads); addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16)) }
                else -> setIntraOpNumThreads(v.threads)
            }
            if (v.profile) enableProfiling(File(context.cacheDir, "singprof").path)
            // Memory handed back after each run rather than kept for the next.
            if ("noarena" in v.parts) { setCPUArenaAllocator(false); setMemoryPatternOptimization(false) }
            // Waiting threads sleep rather than spin (the player shares the processor).
            if ("nospin" in v.parts) addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        val t0 = System.nanoTime()
        fun session(name: String): OrtSession = env!!.createSession(File(dir, name).path, opts!!)
        val vocals = if (none || quick) null else session("vocals.fp16.onnx")
        val accompaniment = if (v.two && !none && !quick) session("accompaniment.fp16.onnx") else null
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val heapLoaded = Debug.getNativeHeapAllocatedSize()
        // "w1", "w2", …: that many threads for the transforms.
        val defaultWorkers = Spleeter.WORKERS
        v.parts.firstOrNull { it.matches(Regex("w\\d")) }?.let { Spleeter.WORKERS = it.drop(1).toInt() }
        val sp = Spleeter(env, vocals, accompaniment, separate = !none)
        Spleeter.WORKERS = defaultWorkers
        val chunks = ArrayList<Long>()
        var kotlinMs = 0L
        sp.onChunk = { model, total -> chunks += model; kotlinMs += total - model }
        val t1 = System.nanoTime()
        // "bg": on a thread at background priority, as Sing's own separation thread is.
        var out = FloatArray(0)
        if ("bg" in v.parts) {
            Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); out = sp.feed(song) + sp.finish() }.apply { start(); join() }
        } else out = sp.feed(song) + sp.finish()
        val wall = (System.nanoTime() - t1) / 1e9
        sampling.set(false)
        sampler.join()
        val profile = if (v.profile && vocals != null) opProfile(vocals.endProfiling()).also { accompaniment?.endProfiling() } else ""
        vocals?.close(); accompaniment?.close(); opts?.close()
        val audio = song.size / 2 / Spleeter.RATE.toDouble()
        return out to ("${"%.2f".format(audio / wall)}× real time (${"%.1f".format(audio)} s in ${"%.1f".format(wall)} s; " +
            "networks per chunk ${chunks} ms, transforms ${kotlinMs} ms in all, forward ${sp.forwardMs} inverse ${sp.inverseMs}); load ${loadMs} ms; " +
            "memory: models ${(heapLoaded - heapBefore) / 1_048_576} MB, peak ${(peak - heapBefore) / 1_048_576} MB" + profile)
    }

    /** ONNX Runtime's profile, as time per kind of layer (largest first). */
    private fun opProfile(file: String): String {
        val events = JSONArray(File(file).readText())
        val byOp = HashMap<String, Long>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.optString("cat") != "Node" || !e.optString("name").endsWith("_kernel_time")) continue
            val op = e.optJSONObject("args")?.optString("op_name") ?: continue
            byOp[op] = (byOp[op] ?: 0L) + e.optLong("dur")
        }
        File(file).delete()
        val total = byOp.values.sum().coerceAtLeast(1)
        return "; per kind of layer: " + byOp.entries.sortedByDescending { it.value }.take(8)
            .joinToString { "${it.key} ${it.value / 1000} ms (${it.value * 100 / total}%)" }
    }

    /** How close [x] is to [ref] (signal to difference, dB). */
    private fun sdr(ref: FloatArray, x: FloatArray): Double {
        var s = 0.0; var d = 0.0
        for (i in 0 until minOf(ref.size, x.size)) { s += ref[i].toDouble() * ref[i]; val e = ref[i] - x[i]; d += e.toDouble() * e }
        return 10 * log10(s / d.coerceAtLeast(1e-20))
    }
}

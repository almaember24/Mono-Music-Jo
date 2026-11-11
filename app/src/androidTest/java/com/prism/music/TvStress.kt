package com.prism.music

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the TV stress tests share (1.8.2, Reddit: "clicking Next Song sometimes crashes the app repeatedly", "restoring
 * [the vocals] freezes the app and makes all buttons unresponsive"): a watch on the main thread that says how long it
 * was last held up and what it was doing, and music files that give the player and Sing real work.
 */
object TvStress {
    /**
     * Beats on the main thread every 25 ms; a thread beside it notes the longest gap between beats and, while a beat is
     * [SAMPLE_AFTER_MS] late, the main thread's stack (in the log under PrismStall, and in [worstStack]).
     */
    class MainWatch : AutoCloseable {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var lastBeat = android.os.SystemClock.uptimeMillis()
        @Volatile var longestMs = 0L
            private set
        @Volatile var worstStack = ""
            private set
        private val running = AtomicBoolean(true)
        private val beat = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                val gap = now - lastBeat
                if (gap > longestMs) longestMs = gap
                lastBeat = now
                if (running.get()) main.postDelayed(this, 25)
            }
        }
        private val sampler = Thread({
            var sampledFor = 0L
            while (running.get()) {
                Thread.sleep(50)
                val late = android.os.SystemClock.uptimeMillis() - lastBeat
                if (late >= SAMPLE_AFTER_MS && lastBeat != sampledFor) {
                    sampledFor = lastBeat
                    val stack = Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "  at $it" }
                    android.util.Log.w("PrismStall", "main thread held for $late ms so far:\n$stack")
                    if (worstStack.isEmpty() || late >= longestMs) worstStack = stack
                }
            }
        }, "TvStressSampler")

        init {
            main.post(beat)
            sampler.isDaemon = true
            sampler.start()
        }

        /** Starts counting afresh (after what's allowed to take a while, such as opening the first screen). */
        fun restart() {
            longestMs = 0
            worstStack = ""
        }

        override fun close() {
            running.set(false)
            main.removeCallbacks(beat)
        }

        private companion object { const val SAMPLE_AFTER_MS = 400L }
    }

    /** This app's busiest threads over [ms] (spent in [wait]): "name nice=N cpu=M ms", busiest first. */
    fun busiestThreads(ms: Long, wait: (Long) -> Unit): List<String> {
        fun read(): Map<String, Pair<Long, Int>> = java.io.File("/proc/self/task").listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                val stat = java.io.File(dir, "stat").readText()
                val name = stat.substringAfter('(').substringBeforeLast(')')
                val rest = stat.substringAfterLast(") ").split(' ')
                // After the name: state is 0, utime 11, stime 12, nice 16.
                "$name#${dir.name}" to ((rest[11].toLong() + rest[12].toLong()) * 10 to rest[16].toInt())
            }.getOrNull()
        }.toMap()
        val before = read()
        wait(ms)
        return read().map { (k, v) -> Triple(k, v.first - (before[k]?.first ?: 0L), v.second) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(10)
            .map { (k, cpu, nice) -> "${k.substringBefore('#')} nice=$nice cpu=$cpu ms" }
    }

    /** No sound from the test TV (its media volume down, as every test playing music leaves it). */
    fun mute() = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()

    /** Storage read for the tests' own music files. */
    fun grantAudio() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
    }

    /**
     * Stereo music-like noise in a WAV (44.1 kHz), [seconds] long, as a music file titled [title]: unlike [Fixture.tone]'s
     * silence, Sing's quick way has something to take out and to line up with. The middle of the mix is common to both
     * channels, as a singer is. Delete it with its uri.
     */
    fun music(title: String, seconds: Int): android.net.Uri {
        // RELATIVE_PATH and IS_PENDING are Android 10+; on 9 the media store refuses the write (no WRITE_EXTERNAL_STORAGE).
        org.junit.Assume.assumeTrue("music files are written through the media store (Android 10+)", android.os.Build.VERSION.SDK_INT >= 29)
        val rate = 44_100
        val samples = rate * seconds
        val data = java.io.ByteArrayOutputStream(44 + samples * 4)
        fun le32(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        data.write("RIFF".toByteArray()); le32(36 + samples * 4); data.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
        data.write("data".toByteArray()); le32(samples * 4)
        val rnd = java.util.Random(title.hashCode().toLong())
        val frame = ByteArray(4)
        var voice = 0f
        for (i in 0 until samples) {
            voice += (rnd.nextFloat() - 0.5f) * 0.05f - voice * 0.01f
            val l = (voice + (rnd.nextFloat() - 0.5f) * 0.1f).coerceIn(-1f, 1f)
            val r = (voice + (rnd.nextFloat() - 0.5f) * 0.1f).coerceIn(-1f, 1f)
            val a = (l * 20_000).toInt()
            val b = (r * 20_000).toInt()
            frame[0] = a.toByte(); frame[1] = (a shr 8).toByte(); frame[2] = b.toByte(); frame[3] = (b shr 8).toByte()
            data.write(frame)
        }
        val resolver = Fixture.context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$title.wav")
            put(android.provider.MediaStore.Audio.Media.TITLE, title)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(data.toByteArray()) }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A cover (a gradient, [px] square) in Prism's own files, for a song's picture: [file] as the song's thumbnail. */
    fun cover(n: Int, px: Int = 1080): java.io.File {
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val a = android.graphics.Color.HSVToColor(floatArrayOf((n * 47f) % 360f, 0.8f, 0.9f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((n * 47f + 120f) % 360f, 0.7f, 0.5f))
        val p = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), a, b, android.graphics.Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), p)
        val file = java.io.File(Fixture.context.cacheDir, "tvstress-cover-$n.jpg")
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        return file
    }

    /** Songs from the media store, by title, once scanned. */
    fun songs(titles: List<String>): List<com.prism.music.data.model.Song> {
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        return titles.map { t -> local.tracks.value.first { it.song.title == t }.song }
    }

    /** Lyrics saved for [song], as if found earlier (no network): a line every 6 s. */
    fun saveLyrics(song: com.prism.music.data.model.Song, lines: Int = 40) {
        val lrc = (0 until lines).joinToString("\n") { i -> "[%02d:%02d.00]Line number ${i + 1} of the song".format(i * 6 / 60, i * 6 % 60) }
        runBlocking { Fixture.container.db.lyrics().put(com.prism.music.data.db.LyricsEntity(song.id, "LRCLIB", true, lrc, System.currentTimeMillis())) }
    }

    /**
     * [ms] of real time with the screens going on as they do on a TV. Under the test's rule frames and Compose's own
     * delays only move while the rule is waiting, so a Thread.sleep here would stand the whole picture still (key presses
     * then appear to do nothing).
     */
    fun ComposeTestRule.pump(ms: Long) {
        val end = android.os.SystemClock.uptimeMillis() + ms
        waitUntil(ms + 60_000) { android.os.SystemClock.uptimeMillis() >= end }
    }

    fun ComposeTestRule.until(ms: Long, what: String, ok: () -> Boolean) = until(ms, { what }, ok)

    /** Waits for [ok] with the screens going on; [what] is worked out if it fails: it can say how things stood then. */
    fun ComposeTestRule.until(ms: Long, what: () -> String, ok: () -> Boolean) {
        try {
            waitUntil(ms) { ok() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("not after $ms ms: ${what()}", e)
        }
    }

    fun waitFor(ms: Long, what: String, ok: () -> Boolean) = waitFor(ms, { what }, ok)

    /** [what] is worked out if it fails: it can say how things stood then. */
    fun waitFor(ms: Long, what: () -> String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $ms ms: ${what()}")
            Thread.sleep(100)
        }
    }
}

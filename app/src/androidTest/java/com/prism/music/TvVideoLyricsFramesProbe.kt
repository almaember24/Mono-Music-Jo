package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.LyricsEntity
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV's frames with lyrics sung over a playing music video (1.8.0, the owner's Google TV: "the video seems to lag
 * when showing lyrics over it"; ~47 fps there, 111 of 474 frames missed in 10 s): a measurement, run only when asked
 * (`-e tvperf 1`). The test clip (a test pattern, H.264) plays as its own video with word-timed lyrics saved for it;
 * `dumpsys gfxinfo` over [SECONDS] with the video alone, then with the lyrics over it. Numbers to logcat (PrismTvPerf).
 */
@TvTest
@Measurement
@RunWith(AndroidJUnit4::class)
class TvVideoLyricsFramesProbe {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var clip: android.net.Uri? = null
    private val player get() = Fixture.container.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        org.junit.Assume.assumeTrue("the clip is written through the media store (Android 10+)", android.os.Build.VERSION.SDK_INT >= 29)
        Fixture.ready()
    }

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.setVideoMode(false); player.stop() } }
        clip?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }

    private fun measure(label: String): String {
        val pkg = Fixture.context.packageName
        shell("dumpsys gfxinfo $pkg reset")
        val videoFrames = com.prism.music.ui.tv.TvVideoProbe.frames?.sinceReset?.get() ?: 0
        Thread.sleep(SECONDS * 1000L)
        val shown = (com.prism.music.ui.tv.TvVideoProbe.frames?.sinceReset?.get() ?: 0) - videoFrames
        val g = shell("dumpsys gfxinfo $pkg")
        fun num(name: String) = Regex("$name\\s*:\\s*([\\d.]+)ms").find(g)?.groupValues?.get(1) ?: "?"
        val frames = Regex("Total frames rendered:\\s*(\\d+)").find(g)?.groupValues?.get(1)?.toInt() ?: 0
        val janky = Regex("Janky frames:\\s*(\\d+)\\s*\\(([\\d.]+)%\\)").find(g)?.let { "${it.groupValues[1]} (${it.groupValues[2]}%)" } ?: "?"
        return "%-18s window frames %4d (%.1f fps)  p50 %s  p90 %s  janky %s  video frames %d (%.1f fps)  playing %s".format(
            label, frames, frames / SECONDS.toFloat(), num("50th percentile"), num("90th percentile"), janky,
            shown, shown / SECONDS.toFloat(), player.isPlaying.value,
        )
    }

    @Test fun framesWithLyricsOverAVideo() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val title = "Prism TV frames video"
        clip = writeClip(title)
        val local = Fixture.container.localMusic
        val end0 = System.currentTimeMillis() + 20_000
        while (local.tracks.value.none { it.song.title == title } && System.currentTimeMillis() < end0) { runBlocking { local.scan() }; Thread.sleep(500) }
        val song = local.tracks.value.first { it.song.title == title }.song
        // Lines of five words, one every 3 s, each word 0.5 s (QQ's word format, as stored).
        val qrc = (0 until 14).joinToString("\n") { i ->
            val t = 1_000L + i * 3_000L
            "[$t,2800]" + (0 until 5).joinToString("") { w -> "Word${w + 1} (${t + w * 500},500)" }
        }
        runBlocking { Fixture.container.db.lyrics().put(LyricsEntity(song.id, "LRCLIB", true, "WORDS-QRC\n$qrc", System.currentTimeMillis())) }
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "TV video frames probe")) }
        waitFor(20_000) { player.isPlaying.value }
        // The clip plays as its own video (as Prism does once YouTube gave a song's video).
        instrumentation.runOnMainSync {
            val p = player.player!!
            val item = p.getMediaItemAt(0)
            val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply { putString(com.prism.music.playback.EXTRA_VIDEO, item.mediaId) }
            p.replaceMediaItem(0, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build())
        }
        waitFor(20_000) { player.videoMode.value }
        instrumentation.runOnMainSync { player.setPlaying(false) }
        // No Compose test rule (it would drive the app's frames only while a test waits on it): keys and pauses. Home
        // opens on "Now playing": the player; Down, Right, Right is its Video button.
        TvFixture.launch()
        Thread.sleep(15_000)
        TvFixture.ok(); Thread.sleep(4_000)
        val f = com.prism.music.playback.PlayerConnection::class.java.getDeclaredField("_videoAvailable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        instrumentation.runOnMainSync { (f.get(player) as MutableStateFlow<Boolean>).value = true }
        Thread.sleep(1_000)
        TvFixture.down(); Thread.sleep(800)
        TvFixture.right(); Thread.sleep(800)
        TvFixture.right(); Thread.sleep(800)
        TvFixture.ok(); Thread.sleep(5_000)
        val out = ArrayList<String>()
        fun run(label: String) {
            instrumentation.runOnMainSync { player.seekTo(4_000); player.setPlaying(true) }
            waitFor(10_000) { player.isPlaying.value }
            // The controls (or the lyrics' title) have faded.
            Thread.sleep(5_000)
            out += measure(label)
            Fixture.screenshot("tvperf-" + label.replace(' ', '-'))
            instrumentation.runOnMainSync { player.setPlaying(false) }
            Thread.sleep(1_000)
        }
        run("video")
        // The controls faded while it played: a key brings them back on play/pause, then Right, Right is Lyrics.
        TvFixture.right(); Thread.sleep(800)
        TvFixture.right(); Thread.sleep(800)
        TvFixture.right(); Thread.sleep(800)
        TvFixture.ok(); Thread.sleep(4_000)
        run("lyrics over video")
        val report = "${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})\n" + out.joinToString("\n")
        report.lines().forEach { android.util.Log.i("PrismTvPerf", it) }
    }

    /** The test clip (assets/tv-test-video.m4a: a test pattern, H.264 + silent AAC) in the TV's music, as [title]. */
    private fun writeClip(title: String): android.net.Uri {
        val bytes = instrumentation.context.assets.open("tv-test-video.m4a").use { it.readBytes() }
        val resolver = Fixture.context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$title.m4a")
            put(android.provider.MediaStore.Audio.Media.TITLE, title)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $timeoutMs ms")
            Thread.sleep(100)
        }
    }

    private companion object { const val SECONDS = 10 }
}

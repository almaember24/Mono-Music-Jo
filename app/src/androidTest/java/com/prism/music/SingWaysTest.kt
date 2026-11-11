package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import com.prism.music.data.ModelStore
import com.prism.music.playback.sing.Separation
import com.prism.music.ui.player.SingSheet
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sing on more devices (1.8.x): a phone too slow for Sing's separation sings the quick way, and its Sing sheet says so
 * (why, and what the quick way can't do) rather than leaving Sing to fall behind every song.
 */
@RunWith(AndroidJUnit4::class)
class SingWaysTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val pace get() = Fixture.context.getSharedPreferences("sing_pace", android.content.Context.MODE_PRIVATE)

    @After fun cleanUp() {
        pace.edit().clear().commit()
        listOfNotNull(ModelStore.Model.singRuntime(), ModelStore.Model.SING_MODEL).forEach { c.models.dir(it).deleteRecursively() }
        c.models.refresh()
        c.sing.rechoose()
    }

    @Test fun aSlowPhoneSingsTheQuickWayAndSaysWhy() {
        Fixture.ready()
        // Sing's download as if it were on the phone (only its ready marks: the quick way needs none of it).
        listOfNotNull(ModelStore.Model.singRuntime(), ModelStore.Model.SING_MODEL).forEach { m ->
            c.models.dir(m).mkdirs(); File(c.models.dir(m), ".ready").writeText("test")
        }
        c.models.refresh()
        // Timed here at less than the song's own speed, both ways.
        pace.edit().putFloat(Separation.FULL.name, 0.4f).putFloat(Separation.LIGHT.name, 0.6f).commit()
        c.sing.rechoose()
        assertEquals(Separation.QUICK, c.sing.method.value)

        val song = Fixture.songs(1, "Sing quick").first()
        compose.setContent { CompositionLocalProvider(LocalContainer provides c) { PrismTheme(c.settings.current, null) { SingSheet(song) {} } } }
        val why = if (ModelStore.Model.singRuntime() == null) tr(R.string.sing_doesn_t_run_on_this) else tr(R.string.sing_quick_slow)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("singQuickNote") and hasText(why), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Separating from where the song plays (1.8.x) decodes it from there: the decoder says which song frame it started
     * at, and from there on its samples are the song's own at those frames (a WAV: exactly; `-e singSong` an AAC
     * file: past the first frames after the jump, which an AAC decoder can't make whole).
     */
    @Test fun aDecodeFromPartWayThroughLinesUpWithTheSong() {
        fun decode(uri: android.net.Uri, fromUs: Long): Pair<Long, FloatArray> =
            com.prism.music.playback.sing.PcmDecoder(Fixture.context, uri).use { d ->
                if (fromUs > 0) d.seekTo(fromUs)
                val parts = ArrayList<FloatArray>()
                while (true) parts += d.read() ?: break
                val all = FloatArray(parts.sumOf { it.size }).also { a -> var at = 0; parts.forEach { System.arraycopy(it, 0, a, at, it.size); at += it.size } }
                (if (fromUs > 0) d.firstFrame else 0L) to all
            }
        val rate = 44_100
        val wav = File(Fixture.context.cacheDir, "sing-seek.wav")
        val frames = rate * 20
        val data = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        data.write("RIFF".toByteArray()); le32(36 + frames * 4); data.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
        data.write("data".toByteArray()); le32(frames * 4)
        // A chirp, different in each channel: no two places in it look alike.
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            le16((8000 * kotlin.math.sin(2 * Math.PI * (200 + 40 * t) * t)).toInt())
            le16((8000 * kotlin.math.sin(2 * Math.PI * (300 + 25 * t) * t + 1)).toInt())
        }
        wav.writeBytes(data.toByteArray())
        val files = listOfNotNull(wav to 0, InstrumentationRegistry.getArguments().getString("singSong")?.let(::File)?.takeIf { it.isFile }?.let { it to 4096 })
        for ((file, settle) in files) {
            val uri = android.net.Uri.fromFile(file)
            val (_, whole) = decode(uri, 0)
            val (from, part) = decode(uri, 8_000_000)
            assertTrue("${file.name}: started part way ($from)", from in 1..(9L * rate))
            var worst = 0f
            for (i in settle until settle + rate) for (ch in 0..1) {
                worst = maxOf(worst, kotlin.math.abs(part[i * 2 + ch] - whole[((from + i) * 2 + ch).toInt()]))
            }
            println("[sing] ${file.name}: from frame $from, worst difference $worst")
            assertTrue("${file.name}: lines up with the song at frame $from (worst $worst)", worst < 1e-3f)
        }
        wav.delete()
    }

    @Test fun aPhoneThatKeepsUpSaysNothingOfTheQuickWay() {
        Fixture.ready()
        listOfNotNull(ModelStore.Model.singRuntime(), ModelStore.Model.SING_MODEL).forEach { m ->
            c.models.dir(m).mkdirs(); File(c.models.dir(m), ".ready").writeText("test")
        }
        c.models.refresh()
        pace.edit().putFloat(Separation.FULL.name, 7.4f).commit()
        c.sing.rechoose()
        val expected = if (ModelStore.Model.singRuntime() == null) Separation.QUICK else Separation.FULL
        assertEquals(expected, c.sing.method.value)
        val song = Fixture.songs(1, "Sing full").first()
        compose.setContent { CompositionLocalProvider(LocalContainer provides c) { PrismTheme(c.settings.current, null) { SingSheet(song) {} } } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(tr(R.string.sing))).fetchSemanticsNodes().isNotEmpty() }
        val notes = compose.onAllNodes(hasTestTag("singQuickNote"), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(if (expected == Separation.QUICK) 1 else 0, notes)
    }
}

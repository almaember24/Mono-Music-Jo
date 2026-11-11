package com.prism.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.LyricsColor
import com.prism.music.data.prefs.LyricsLookPrefs
import com.prism.music.data.prefs.LyricsMotion
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.cardColor
import com.prism.music.ui.player.lyricsLookOf
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.PrismTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What one frame of the sung line costs on the CPU (recomposition, layout and a software draw of the whole window), for
 * each "Start from" template, 300 frames at 16 ms steps through a word-timed line (build/lyrics-previews/frame-cost.txt).
 * Robolectric's software canvas, so the numbers compare looks with each other rather than say what a phone takes.
 * #253 found some animations choppy: on 1.7.9's code this measured Karaoke 5.1 ms, Bold card 4.9, Glow 9.8 (median),
 * against 0.2–0.7 for the rest: their sweep drew a second copy of the line into an offscreen layer every frame.
 * Fails if a template costs more than [BUDGET_MS] a frame (median).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h240dp-xxhdpi")
class LyricsFrameCostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val words = listOf("Every ", "color ", "finds ", "its ", "way ", "back ", "to ", "the ", "light")
    private val line = LyricLine(0, words.joinToString(""), 5000, words.mapIndexed { i, w -> LyricWord(i * 540L, i * 540L + 500L, w) })

    @Test
    fun frameCostPerStyle() {
        val dark = AppSettings(themeMode = ThemeMode.DARK)
        val axis = AppFonts.axis(FontChoice.PRISM)
        val style = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight(300), fontFamily = AppFonts.prism)
        val out = StringBuilder()
        val clock = mutableLongStateOf(0L)
        var look by androidx.compose.runtime.mutableStateOf(LyricsLook())
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides dark) {
                PrismTheme(dark, artworkColor = null) {
                    Box(Modifier.width(412.dp).background(Color(0xFF0A0A0D)).padding(12.dp)) {
                        SungLine(line, clock, style, look, axis, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        compose.mainClock.autoAdvance = false
        fun frames(): List<Double> = (0 until 300).map { i ->
            val t0 = System.nanoTime()
            compose.runOnUiThread { clock.longValue = i * 16L }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { decor.draw(canvas) }
            (System.nanoTime() - t0) / 1e6
        }
        val card = cardColor(Color(0xFF8A6BFF))
        val looks = LyricsStyle.entries.map { st ->
            val p = LyricsLookPrefs().startFrom(st)
            st.name to lyricsLookOf(p, null, Color.White, Color.White, if (p.onCard) card else null)
        } + listOf(
            "SWEEP_LIGHT" to lyricsLookOf(LyricsLookPrefs(motion = LyricsMotion.SWEEP, sungColor = LyricsColor.SPECTRUM), PrismTokens.spectrum(true), Color.White, Color.White, null),
            "GROW_GLOW" to lyricsLookOf(LyricsLookPrefs(halo = true), null, Color.White, Color.White, null),
        )
        val medians = LinkedHashMap<String, Double>()
        for ((name, l) in looks) {
            compose.runOnUiThread { look = l }
            frames() // warm up
            val f = frames().sorted()
            medians[name] = f[f.size / 2]
            out.appendLine("%-12s median %.2f ms  p90 %.2f ms  max %.2f ms".format(name, f[f.size / 2], f[f.size * 9 / 10], f.last()))
        }
        println(out)
        File("build/lyrics-previews").apply { mkdirs() }.resolve("frame-cost.txt").writeText(out.toString())
        val slow = medians.filter { it.value > BUDGET_MS }
        org.junit.Assert.assertTrue("Over $BUDGET_MS ms a frame: $slow", slow.isEmpty())
    }

    /** 1.8.0 measured 0.2–2.6 ms (Karaoke's fill in your light the most); 1.7.9's sweeps were 4.9–9.8. */
    private companion object { const val BUDGET_MS = 4.0 }
}

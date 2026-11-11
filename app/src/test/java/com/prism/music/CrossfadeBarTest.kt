package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.CrossfadeCurve
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.ScrubberThumb
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.playback.BlendIn
import com.prism.music.playback.BlendMark
import com.prism.music.ui.player.BlendBarPath
import com.prism.music.ui.player.BlendShape
import com.prism.music.ui.player.ThinSlider
import com.prism.music.ui.player.barHeights
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 1.8.1, the owner's: the progress bar shaped by the crossfade. The shape's geometry for every curve (the ends meet the
 * bar, the window is the blend's length, the next song grows in), and a contact sheet of each curve × style
 * (app/build/crossfade-previews/) to hold against the mockup (.claude/mockups/crossfade-bar.html).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w760dp-h1600dp-xhdpi")
class CrossfadeBarTest {
    @get:Rule val compose = createComposeRule()

    private val eps = 1e-4f

    /** A 3:41 song whose last 8 s blend into the next (the blend from 3:33). */
    private val ending = BlendMark("a", 213_000, 8_000)

    @Test fun theEndFollowsTheFadeOutForEveryCurve() {
        for (curve in CrossfadeCurve.entries) {
            val s = BlendShape.of("a", 221_000, ending, null, curve)!!
            assertTrue(curve.name, s.fadesOut && !s.fadesIn)
            // The window is the blend: from its start, its length long.
            assertEquals(curve.name, 213_000f / 221_000, s.outFrom, eps)
            assertEquals(curve.name, 8_000f / 221_000, s.outTo - s.outFrom, eps)
            // Full height up to the blend, meeting the line where it starts (no step), nothing after it ends.
            assertEquals(curve.name, 1f, s.height(0.5f), eps)
            assertEquals(curve.name, 1f, s.height(s.outFrom), eps)
            assertEquals(curve.name, 1f, s.height(s.outFrom + 1e-6f), 1e-3f)
            assertEquals(curve.name, BlendShape.FLOOR, s.height(s.outTo), eps)
            assertEquals(curve.name, BlendShape.FLOOR, s.height(1f), eps)
            // In between, the outgoing volume itself.
            for (k in 1..9) {
                val p = k / 10f
                val x = s.outFrom + p * (s.outTo - s.outFrom)
                assertEquals("${curve.name} at $p", curve.gains(p).second.coerceAtLeast(BlendShape.FLOOR), s.height(x), 1e-3f)
            }
            // Never rising on the way down.
            var last = 1f
            for (i in 0..200) {
                val h = s.height(s.outFrom + (s.outTo - s.outFrom) * i / 200f)
                assertTrue("${curve.name} falls", h <= last + 1e-5f)
                last = h
            }
        }
    }

    @Test fun eachCurveHasItsOwnShape() {
        val mid = { c: CrossfadeCurve -> BlendShape.of("a", 221_000, ending, null, c)!!.let { it.height((it.outFrom + it.outTo) / 2) } }
        // Half way: Smooth still holds (cos 45° ≈ 0.71), Straight and Gentle are at half, DJ cut is at its swap.
        assertEquals(0.707f, mid(CrossfadeCurve.EQUAL_POWER), 0.01f)
        assertEquals(0.5f, mid(CrossfadeCurve.LINEAR), 0.01f)
        assertEquals(0.5f, mid(CrossfadeCurve.S_CURVE), 0.01f)
        // A quarter in: DJ cut is still at full, Gentle has barely moved, Straight is at three quarters.
        val quarter = { c: CrossfadeCurve -> BlendShape.of("a", 221_000, ending, null, c)!!.let { it.height(it.outFrom + (it.outTo - it.outFrom) / 4) } }
        assertEquals(1f, quarter(CrossfadeCurve.DJ_CUT), eps)
        assertEquals(0.75f, quarter(CrossfadeCurve.LINEAR), 0.01f)
        assertEquals(0.844f, quarter(CrossfadeCurve.S_CURVE), 0.01f)
    }

    @Test fun theNextSongGrowsInOverTheBlend() {
        for (curve in CrossfadeCurve.entries) {
            // It came in 2 s into its file (silence skipped), over the 8 s blend.
            val s = BlendShape.of("b", 245_000, null, BlendIn("b", 2_000, 8_000), curve)!!
            assertTrue(curve.name, s.fadesIn && !s.fadesOut)
            assertEquals(curve.name, 2_000f / 245_000, s.inFrom, eps)
            assertEquals(curve.name, 8_000f / 245_000, s.inTo - s.inFrom, eps)
            assertEquals(curve.name, BlendShape.FLOOR, s.height(0f), eps)
            assertEquals(curve.name, BlendShape.FLOOR, s.height(s.inFrom), eps)
            assertEquals(curve.name, 1f, s.height(s.inTo), eps)
            assertEquals(curve.name, 1f, s.height(0.9f), eps)
            for (k in 1..9) {
                val p = k / 10f
                assertEquals("${curve.name} at $p", curve.gains(p).first.coerceAtLeast(BlendShape.FLOOR), s.height(s.inFrom + p * (s.inTo - s.inFrom)), 1e-3f)
            }
        }
    }

    @Test fun aSongCanBothGrowInAndTaperOff() {
        val s = BlendShape.of("b", 200_000, BlendMark("b", 190_000, 6_000), BlendIn("b", 0, 6_000), CrossfadeCurve.LINEAR)!!
        assertTrue(s.fadesIn && s.fadesOut)
        assertEquals(0.5f, s.height(3_000f / 200_000), 0.01f)
        assertEquals(1f, s.height(0.5f), eps)
        assertEquals(0.5f, s.height(193_000f / 200_000), 0.01f)
    }

    @Test fun noShapeWhenThereIsNoBlendForThisSong() {
        val c = CrossfadeCurve.EQUAL_POWER
        assertNull("nothing planned", BlendShape.of("a", 221_000, null, null, c))
        assertNull("another song's blend", BlendShape.of("a", 221_000, BlendMark("x", 200_000, 8_000), BlendIn("y", 0, 8_000), c))
        assertNull("an automatic length not known yet", BlendShape.of("a", 221_000, BlendMark("a", 200_000, 0), null, c))
        assertNull("no song", BlendShape.of(null, 221_000, ending, null, c))
        assertNull("no length", BlendShape.of("a", 0, ending, null, c))
    }

    @Test fun theOutlineKeepsTheBarsRoundEnds() {
        val s = BlendShape(CrossfadeCurve.EQUAL_POWER, outFrom = 0.75f, outTo = 1f)
        val w = 400f; val h = 10f
        // The full-height part is the bar as before: round at its start, full in its middle.
        assertEquals(0f, BlendBarPath.halfAt(s, 0f, w, h), eps)
        assertEquals(h / 2, BlendBarPath.halfAt(s, h / 2, w, h), eps)
        assertEquals(h / 2, BlendBarPath.halfAt(s, 200f, w, h), eps)
        assertEquals(h / 2, BlendBarPath.halfAt(s, 300f, w, h), eps)
        // Half way through the blend: the curve's height.
        assertEquals(h / 2 * CrossfadeCurve.EQUAL_POWER.gains(0.5f).second, BlendBarPath.halfAt(s, 350f, w, h), 1e-3f)
        // At the end: a hairline at most.
        assertTrue(BlendBarPath.halfAt(s, w, w, h) <= h / 2 * BlendShape.FLOOR)
    }

    @Test fun anOptionOffUnlessTurnedOnAndOffInPerformanceMode(): Unit = kotlinx.coroutines.runBlocking {
        assertTrue("off by default (design changes are options)", !AppSettings().view.crossfadeShape)
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        repo.setCrossfadeShape(true)
        val on = kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.view.crossfadeShape } }
        assertTrue(on.view.crossfadeShape)
        // Performance mode leaves it off (as it does the other new looks), keeping the listener's choice for later.
        assertTrue(!on.copy(performanceMode = true).effective().view.crossfadeShape)
        repo.setCrossfadeShape(false)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { !it.view.crossfadeShape } }
    }

    @Test fun theKnobRidesTheShape() {
        // The mockup's dot: 14.5 of a 24-high bar at full, 3 where the bar is all but gone.
        assertEquals(1f, BlendShape.knobScale(1f), eps)
        assertEquals(3f / 14.5f, BlendShape.knobScale(BlendShape.FLOOR), eps)
        assertTrue(BlendShape.knobScale(0.5f) in 0.5f..0.6f)
    }

    /** Each curve × style: the end of song A (the playhead at 86% as in the mockup) and the start of song B (8%). */
    private fun sheet(file: String, dark: Boolean) {
        val settings = AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, reduceMotion = true)
        // The mockup's proportions: the blend is the last quarter of song A and the first quarter of song B.
        val styles = ScrubberStyle.entries
        val wave = FloatArray(48) { i -> (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.9f) * kotlin.math.cos(i * 0.37f))) }
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides settings) {
                PrismTheme(settings, artworkColor = null) {
                    // The player is dark in both themes; the page around it follows the theme.
                    Column(
                        Modifier.width(720.dp).background(if (dark) Color(0xFF0D0D12) else Color(0xFFF2F2F5)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val ink = if (dark) Color.White.copy(alpha = 0.75f) else Color.Black.copy(alpha = 0.75f)
                        for (curve in CrossfadeCurve.entries) {
                            Text("${curve.name} (${curve.label})", color = ink, fontSize = 12.sp)
                            Column(Modifier.background(Color(0xFF17171F)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (style in styles) {
                                    // Wave and Ribbons keep the crossfade mark (the waveform can't take the shape).
                                    val lines = style != ScrubberStyle.WAVE && style != ScrubberStyle.RIBBONS
                                    val (idle, held) = if (style == ScrubberStyle.WAVE) 22.dp to 26.dp else barHeights(style, 1f)
                                    val knob = if (style == ScrubberStyle.THUMB || style == ScrubberStyle.RIBBONS) ScrubberThumb.DOT else ScrubberThumb.NONE
                                    Row {
                                        Text(style.name, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, modifier = Modifier.width(64.dp))
                                        for ((shape, at) in listOf(
                                            BlendShape(curve, outFrom = 0.75f, outTo = 1f) to 0.86f,
                                            BlendShape(curve, inFrom = 0f, inTo = 0.25f) to 0.08f,
                                        )) {
                                            ThinSlider(
                                                at, {}, Modifier.width(290.dp), idleHeight = idle, activeHeight = held, spectrum = true,
                                                thumb = knob != ScrubberThumb.NONE, thumbShape = knob,
                                                marks = if (!lines && shape.fadesOut) listOf(0.75f) else emptyList(),
                                                wave = if (style == ScrubberStyle.WAVE) wave else null,
                                                ribbons = if (style == ScrubberStyle.RIBBONS) ({ 0.35f }) else null,
                                                shape = if (lines) shape else null,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/crossfade-previews").apply { mkdirs() }
        File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun contactSheetDark() = sheet("crossfade-bar-dark.png", dark = true)

    @Test fun contactSheetLight() = sheet("crossfade-bar-light.png", dark = false)
}

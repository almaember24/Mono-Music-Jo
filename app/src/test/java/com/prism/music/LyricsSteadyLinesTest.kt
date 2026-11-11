package com.prism.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.ui.player.LyricLineWords
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.SweptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #399: synced lyrics jumped at every line change, and some sung words' light was cut off.
 * - Only the sung line had the progress line's row under it, so at each change the old line shrank and the new one grew
 *   in the middle of the scroll to it: every line keeps that room now, drawn only under the sung one.
 * - The sweep clipped each row of a wrapped line to its own band: at tight line spacing a sung row's descenders (g j p q
 *   y) reach into the row under it and were cut off there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class LyricsSteadyLinesTest {
    @get:Rule val compose = createComposeRule()

    private val holdOn = listOf(LyricWord(0, 400, "Hold "), LyricWord(400, 900, "on"))
    private val synced = LyricLine(0, "Hold on", 900, holdOn)

    @Test fun aSyncedLineIsAsTallSungAsResting() {
        val clock = mutableLongStateOf(300L)
        val style = TextStyle(fontSize = 34.sp, lineHeight = 41.sp)
        compose.setContent {
            Column {
                for (active in listOf(true, false)) Column(Modifier.width(300.dp).testTag(if (active) "sung" else "resting")) {
                    LyricLineWords(synced, 0, 900, active, synced = true, progress = true, clock, style, LyricsLook(), null, onCard = false, alpha = 0.5f, origin = 0f)
                }
            }
        }
        compose.waitForIdle()
        val sung = compose.onNodeWithTag("sung").fetchSemanticsNode().size.height
        val resting = compose.onNodeWithTag("resting").fetchSemanticsNode().size.height
        assertEquals("the sung line (with its progress line) is as tall as when it rests", resting, sung)
    }

    @Test fun aLineWithNoEndHasNoProgressRoom() {
        // Lines timed without an end never show the progress line: they get no room for it either.
        val clock = mutableLongStateOf(300L)
        val style = TextStyle(fontSize = 34.sp, lineHeight = 41.sp)
        val noEnd = LyricLine(0, "Hold on")
        compose.setContent {
            Column {
                Column(Modifier.width(300.dp).testTag("words")) {
                    LyricLineWords(noEnd, 0, -1, active = false, synced = true, progress = true, clock, style, LyricsLook(), null, onCard = false, alpha = 0.5f, origin = 0f)
                }
                Column(Modifier.width(300.dp).testTag("plain")) {
                    androidx.compose.material3.Text("Hold on", style = style)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(
            compose.onNodeWithTag("plain").fetchSemanticsNode().size.height,
            compose.onNodeWithTag("words").fetchSemanticsNode().size.height,
        )
    }

    @Test fun theSettleFollowsALineThatMovesOnTheWay() {
        val curve = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.6f, 0.25f, 1f)
        val frames = 30
        // 300 px to go; 10 frames in, a line above grows 12 px and the sung line's place moves with it.
        var target = 300f
        var at = 0f
        var before = 0f
        for (f in 1..frames) {
            if (f == 10) target += 12f
            val now = curve.transform(f / frames.toFloat())
            at += com.prism.music.ui.player.settleStep(target - at, before, now)
            before = now
            // Until anything moves, it is the curve itself.
            if (f < 10) assertEquals(300f * now, at, 0.01f)
        }
        assertEquals("ends on the line's new place", target, at, 0.01f)
    }

    /** "gjpqy " sung (red) on the first row, "now" still to come (white) on the second, at tight line spacing. */
    private fun sungDescendersBelowTheRow(shadow: Shadow?): Pair<Int, Int> {
        val words = listOf(LyricWord(0, 400, "gjpqy "), LyricWord(1_000, 1_400, "now"))
        val line = LyricLine(0, "gjpqy now", 1_400, words)
        // 40 sp type on 28 sp rows: the descenders go well past the row's own band.
        val style = TextStyle(fontSize = 40.sp, lineHeight = 28.sp, shadow = shadow)
        val clock = mutableLongStateOf(600L)
        var layout: TextLayoutResult? = null
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val px = with(LocalDensity.current) { 150.dp.roundToPx() }
            layout = measurer.measure(line.text, style, constraints = Constraints(maxWidth = px))
            Box(Modifier.width(150.dp).background(Color.Black).testTag("line")) {
                SweptLine(line, clock, style, litBrush = SolidColor(Color.Red), dimColor = Color.White)
            }
        }
        compose.waitForIdle()
        val l = layout!!
        assertEquals("the line wraps after \"gjpqy\"", 2, l.lineCount)
        val rowBottom = l.getLineBottom(0)
        val bmp = compose.onNodeWithTag("line").captureToImage().asAndroidBitmap()
        java.io.File("build/lyrics-previews").apply { mkdirs() }.resolve("399-descenders${if (shadow != null) "-shadow" else ""}.png")
            .outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        var below = 0
        var above = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (r > 120 && g < 60 && b < 60) { if (y > rowBottom + 1) below++ else above++ }
        }
        return above to below
    }

    @Test fun sungDescendersAreNotCutAtTheRowsEdge() {
        val (above, below) = sungDescendersBelowTheRow(null)
        assertTrue("the sung row is drawn in red", above > 50)
        assertTrue("the sung row's descenders go on below its band (red pixels below it: $below)", below > 20)
    }

    @Test fun sungDescendersAreNotCutWithAShadowEither() {
        // A shadow draws the sweep in layers of its own (erasing by row), not with the clipped rows.
        val (above, below) = sungDescendersBelowTheRow(Shadow(Color.Black, blurRadius = 4f))
        assertTrue("the sung row is drawn in red", above > 50)
        assertTrue("the sung row's descenders go on below its band (red pixels below it: $below)", below > 20)
    }
}

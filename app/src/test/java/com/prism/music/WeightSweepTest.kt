package com.prism.music

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prism.music.ui.player.weightSpacing
import com.prism.music.ui.theme.AppFonts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The lyrics' weight sweep (1.7): a word growing from Light to Medium gives its extra width back as letter spacing, so
 * a line being sung never shifts. Also draws a line at five moments onto build/type-previews/lyric-sweep.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WeightSweepTest {
    private val density = 2.625f
    private val measurer = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(density), LayoutDirection.Ltr)
    private val style = TextStyle(fontFamily = AppFonts.prismAxis, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight(300))
    private val fontPx = 34f * density

    private fun width(word: String, weight: Int, spacingEm: Float, st: TextStyle = style) =
        measurer.measure(AnnotatedString(word, SpanStyle(fontWeight = FontWeight(weight), letterSpacing = spacingEm.em)), st).size.width

    /** Every font Prism ships sweeps: Outfit and the 1.7 additions (Manrope, Plus Jakarta Sans, Urbanist, Nunito, Fraunces, Geist Mono). */
    @Test
    fun heavierWordsKeepTheirLightWidth() {
        val words = listOf("Waterfalls", "I", "said", "mind", "Somebody", "you", "WOW", "Lights")
        val fonts = com.prism.music.data.prefs.FontChoice.entries.mapNotNull { c -> AppFonts.axis(c)?.let { c to it } }
        // Every bundled variable font (1.7.7 added Legible); OpenDyslexic comes in two weights, so it has no axis to sweep.
        assertTrue("expected the eight bundled variable fonts, got ${fonts.map { it.first }}", fonts.size == 8)
        for ((choice, axis) in fonts) {
            val st = style.copy(fontFamily = axis)
            // Some words come out the same width either way; most widen (else the weight axis isn't being applied).
            // A monospaced font keeps every letter's width at every weight, by design.
            if (choice != com.prism.music.data.prefs.FontChoice.MONO) {
                val widened = words.count { width(it, 500, 0f, st) > width(it, 300, 0f, st) }
                assertTrue("$choice: only $widened of ${words.size} words widen with weight: is the axis applied?", widened >= words.size / 2)
            }
            for (word in words) {
                val spacing = weightSpacing(measurer, word, st, fontPx)
                val light = width(word, 300, 0f, st)
                for (k in spacing.indices) {
                    val w = width(word, 300 + k * 20, spacing[k], st)
                    // Within a couple of pixels: the line doesn't visibly move.
                    assertTrue("$choice $word at ${300 + k * 20}: $w px against $light px light", abs(w - light) <= 2)
                }
            }
        }
    }

    @Test
    fun drawSweep() {
        val words = listOf("Every ", "word ", "lights ", "up ", "as ", "it's ", "sung")
        val stages = listOf(0f, 1.5f, 3.2f, 5.6f, 7f)
        val w = 1080; val rowH = 150
        val bmp = Bitmap.createBitmap(w, rowH * stages.size + 40, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(w.toFloat(), bmp.height.toFloat())) {
            drawRect(Color(0xFF060609))
            stages.forEachIndexed { r, at ->
                val text = androidx.compose.ui.text.buildAnnotatedString {
                    words.forEachIndexed { i, word ->
                        val f = (at - i).coerceIn(0f, 1f)
                        val step = Math.round(f * 10)
                        val core = word.trimEnd()
                        val spacing = weightSpacing(measurer, core, style, fontPx)
                        pushStyle(SpanStyle(color = Color.White.copy(alpha = 0.35f + 0.65f * f), fontWeight = FontWeight(300 + step * 20), letterSpacing = spacing[step].em))
                        append(core); pop(); append(word.substring(core.length))
                    }
                }
                drawText(measurer.measure(text, style), topLeft = Offset(40f, 20f + r * rowH))
            }
        }
        File(File("build/type-previews").apply { mkdirs() }, "lyric-sweep.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

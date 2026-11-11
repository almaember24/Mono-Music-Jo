package com.prism.music

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.prism.music.ui.theme.AccentSwatches
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.contrastRatio
import com.prism.music.ui.theme.prismTypography
import com.prism.music.ui.theme.readableOn
import com.prism.music.ui.theme.schemeFromSeed
import com.prism.music.ui.theme.toHsl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The 1.7 redesign's foundations: the type scale keeps thin weights to large sizes, Outfit really loads at each weight
 * from its one variable file, and every scheme Prism builds from a color is readable. Also draws the type scale in
 * Outfit, dark and light, onto app/build/type-previews/type-scale.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SpectrumFoundationTest {
    private val roles = prismTypography().let {
        listOf(
            "displayLarge" to it.displayLarge, "displayMedium" to it.displayMedium, "displaySmall" to it.displaySmall,
            "headlineLarge" to it.headlineLarge, "headlineMedium" to it.headlineMedium, "headlineSmall" to it.headlineSmall,
            "titleLarge" to it.titleLarge, "titleMedium" to it.titleMedium, "titleSmall" to it.titleSmall,
            "bodyLarge" to it.bodyLarge, "bodyMedium" to it.bodyMedium, "bodySmall" to it.bodySmall,
            "labelLarge" to it.labelLarge, "labelMedium" to it.labelMedium, "labelSmall" to it.labelSmall,
        )
    }

    @Test
    fun thinWeightsOnlyAtLargeSizes() {
        roles.forEach { (name, s) ->
            val w = s.fontWeight!!.weight
            val size = s.fontSize.value
            if (w < 300) assertTrue("$name is $w at $size sp: ExtraLight needs 28 sp", size >= 28f)
            if (w < 400) assertTrue("$name is $w at $size sp: Light needs 21 sp", size >= 21f)
            assertTrue("$name's line height is under its size", s.lineHeight.value >= size)
        }
    }

    private val measurer by lazy {
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(2f), LayoutDirection.Ltr)
    }

    private fun width(text: String, family: FontFamily, weight: Int) =
        measurer.measure(text, TextStyle(fontFamily = family, fontSize = 16.sp, fontWeight = FontWeight(weight))).size.width

    @Test
    fun outfitLoadsAtEveryWeight() {
        val title = "Waterfalls Coming Out Your Mouth"
        val widths = listOf(200, 400, 600, 800).map { width(title, AppFonts.prism, it) }
        // Outfit widens as it gets heavier; equal widths would mean the weight axis isn't applied.
        assertTrue("widths by weight: $widths", widths.zipWithNext().all { (a, b) -> b > a })
        assertTrue("Outfit sets like the default font: is it loading?", width(title, AppFonts.prism, 400) != width(title, FontFamily.SansSerif, 400))
    }

    private val hardSeeds = AccentSwatches + listOf(
        Color.White, Color.Black, Color(0xFF39FF14), Color(0xFFFFFF00), Color(0xFFE8DCC0), Color(0xFF00FFFF),
        Color(0xFFFF00FF), Color(0xFF808080), Color(0xFF1B1B3A), Color(0xFFFFE4E1), Color(0xFF8B4513),
    )

    @Test
    fun schemesFromAnyColorAreReadable() {
        for (seed in hardSeeds) for (dark in listOf(true, false)) for (black in listOf(true, false)) {
            if (!dark && black) continue
            val c = schemeFromSeed(seed, dark, black)
            val tag = "seed ${Integer.toHexString(seed.value.shr(32).toInt())} dark=$dark black=$black"
            assertTrue("$tag: body text", contrastRatio(c.onBackground, c.background) >= 7f)
            assertTrue("$tag: secondary text", contrastRatio(c.onSurfaceVariant, c.background) >= 4.5f)
            assertTrue("$tag: accent", contrastRatio(c.primary, c.background) >= 4.5f)
            assertTrue("$tag: text on the accent", contrastRatio(c.onPrimary, c.primary) >= 4.5f)
            assertTrue("$tag: text on the accent's container", contrastRatio(c.onPrimaryContainer, c.primaryContainer) >= 4.5f)
        }
    }

    @Test
    fun readableOnMovesOnlyLightness() {
        val pale = Color(0xFFFFD84D)
        assertEquals(pale, readableOn(pale, Color.Black)) // already readable: untouched
        val fixed = readableOn(pale, Color.White)
        assertTrue(contrastRatio(fixed, Color.White) >= 4.5f)
        assertTrue("barely past 4.5, not pushed to black", contrastRatio(fixed, Color.White) < 5.2f)
        val (h0, s0, _) = pale.toHsl()
        val (h1, s1, _) = fixed.toHsl()
        assertEquals(h0, h1, 1.5f)
        assertEquals(s0, s1, 0.03f)
    }

    @Test
    fun renderTypeScale() {
        val rows = roles.filter { it.first in setOf("displayLarge", "headlineLarge", "headlineMedium", "titleLarge", "titleMedium", "bodyLarge", "bodySmall", "labelLarge", "labelSmall") }
        val samples = mapOf(
            "displayLarge" to "3 h 52 m", "headlineLarge" to "Good evening", "headlineMedium" to "After Hours",
            "titleLarge" to "Made for you", "titleMedium" to "Blinding Lights", "bodyLarge" to "Waterfalls Coming Out Your Mouth",
            "bodySmall" to "The Weeknd · After Hours", "labelLarge" to "Shuffle", "labelSmall" to "MONDAY 19 OCTOBER",
        )
        val typo = prismTypography(AppFonts.prism)
        val styleOf = mapOf(
            "displayLarge" to typo.displayLarge, "headlineLarge" to typo.headlineLarge, "headlineMedium" to typo.headlineMedium,
            "titleLarge" to typo.titleLarge, "titleMedium" to typo.titleMedium, "bodyLarge" to typo.bodyLarge,
            "bodySmall" to typo.bodySmall, "labelLarge" to typo.labelLarge, "labelSmall" to typo.labelSmall,
        )
        val d = 2f
        val colW = (412 * d).toInt()
        val h = (rows.sumOf { (styleOf.getValue(it.first).lineHeight.value + 26).toDouble() } * d + 40 * d).toInt()
        val bmp = Bitmap.createBitmap(colW * 2, h, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(colW * 2f, h.toFloat())) {
            listOf(false, true).forEachIndexed { i, dark ->
                val bg = if (dark) Color(0xFF060609) else Color(0xFFF6F6F8)
                val ink = if (dark) Color(0xFFEEEEF5) else Color(0xFF0A0A10)
                val x = i * colW.toFloat()
                drawRect(bg, Offset(x, 0f), Size(colW.toFloat(), h.toFloat()))
                var y = 20 * d
                rows.forEach { (name, _) ->
                    val st = styleOf.getValue(name)
                    drawText(measurer.measure(name + "  ${st.fontSize.value.toInt()} sp · ${st.fontWeight!!.weight}", TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace)), ink.copy(alpha = 0.4f), Offset(x + 22 * d, y))
                    y += 14 * d
                    drawText(measurer.measure(samples.getValue(name), st), ink, Offset(x + 22 * d, y))
                    y += (st.lineHeight.value + 12) * d
                }
                val line = androidx.compose.ui.graphics.Brush.horizontalGradient(*PrismTokens.spectrumColors.toTypedArray(), startX = x + 22 * d, endX = x + 142 * d)
                drawRect(line, Offset(x + 22 * d, h - 16 * d), Size(120 * d, 2 * d))
            }
        }
        val dir = File("build/type-previews").apply { mkdirs() }
        File(dir, "type-scale.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

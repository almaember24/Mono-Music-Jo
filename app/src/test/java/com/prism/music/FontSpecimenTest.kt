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
import com.prism.music.data.prefs.FontChoice
import com.prism.music.ui.theme.AppFonts
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
 * The fonts Prism ships (1.7): each one loads, its weights really differ, and retired picks move on. Draws a specimen of
 * every font (a title in ExtraLight, a song row, the weight range) onto app/build/type-previews/fonts.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FontSpecimenTest {
    private val d = 2f
    private val measurer = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(d), LayoutDirection.Ltr)
    private val bundled = FontChoice.entries.filter { it != FontChoice.SYSTEM && it != FontChoice.CUSTOM }

    @Test
    fun everyBundledFontHasItsWeights() {
        for (c in bundled) {
            val fam = AppFonts.family(c)
            assertTrue("$c fell back to the default family", fam != FontFamily.Default)
            fun w(weight: Int) = measurer.measure("Blinding Lights", TextStyle(fontFamily = fam, fontSize = 30.sp, fontWeight = FontWeight(weight))).size.width
            // Monospace keeps its width; every other weight axis makes text wider as it gets heavier.
            if (c != FontChoice.MONO) assertTrue("$c: ExtraLight ${w(200)} px vs Bold ${w(700)} px", w(700) > w(200))
            assertTrue("$c has no face name", c.face != null)
        }
    }

    @Test
    fun retiredFontsMoveToClean() {
        assertEquals("CLEAN", FontChoice.upgrade("GOOGLE_SANS"))
        assertEquals("CLEAN", FontChoice.upgrade("CONDENSED"))
        assertEquals("SERIF", FontChoice.upgrade("SERIF"))
        assertEquals("", FontChoice.upgrade(""))
        for (c in FontChoice.entries) assertEquals(c.name, FontChoice.upgrade(c.name))
    }

    @Test
    fun drawSpecimens() {
        val rowH = 196f
        val w = (412 * d).toInt()
        val h = ((bundled.size * rowH + 24) * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color(0xFF07070B))
            val ink = Color(0xFFEEEEF5)
            bundled.forEachIndexed { i, c ->
                val fam = AppFonts.family(c)
                val x = 22 * d
                var y = (16 + i * rowH) * d
                drawText(measurer.measure("${c.label.uppercase()}  ·  ${c.face}", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp)), ink.copy(alpha = 0.45f), Offset(x, y))
                y += 16 * d
                drawText(measurer.measure("Good evening", TextStyle(fontFamily = fam, fontSize = 36.sp, fontWeight = FontWeight(200))), ink, Offset(x, y))
                y += 50 * d
                drawText(measurer.measure("Blinding Lights", TextStyle(fontFamily = fam, fontSize = 16.sp, fontWeight = FontWeight.Medium)), ink, Offset(x, y))
                y += 22 * d
                drawText(measurer.measure("The Weeknd · After Hours · 3:20", TextStyle(fontFamily = fam, fontSize = 13.sp)), ink.copy(alpha = 0.6f), Offset(x, y))
                y += 26 * d
                var wx = x
                for (wt in listOf(200, 300, 400, 500, 600, 700, 800)) {
                    val m = measurer.measure("Aa", TextStyle(fontFamily = fam, fontSize = 26.sp, fontWeight = FontWeight(wt)))
                    drawText(m, ink, Offset(wx, y)); wx += m.size.width + 14 * d
                }
                y += 48 * d
                drawRect(ink.copy(alpha = 0.08f), Offset(x, y), Size(w - 2 * x, 1f))
            }
        }
        File(File("build/type-previews").apply { mkdirs() }, "fonts.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
package com.prism.music.ui.player

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * #232, #338: the player's white type stays readable on the Cover gradient, whatever the cover and however dark its foot is
 * set; and the colors come from the cover. Draws them to app/build/backdrop-previews/cover-gradient.png, to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CoverGradientTest {
    private val w = 360
    private val h = 760

    /** A cover: its main color, and blocks and speckle of others, as a real one has. */
    private fun cover(main: Int, vararg others: Pair<Int, Float>): Bitmap {
        val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(main)
        val rnd = Random(main)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((color, share) in others) {
            paint.color = color
            val side = 120 * kotlin.math.sqrt(share)
            val x = rnd.nextFloat() * (120 - side)
            val y = rnd.nextFloat() * (120 - side)
            c.drawOval(x, y, x + side, y + side, paint)
        }
        return b
    }

    private val covers: List<Pair<String, Bitmap>> by lazy {
        fun i(v: Long) = v.toInt()
        listOf(
            "white sleeve" to cover(i(0xFFF7F5F0), i(0xFF1A1A1A) to 0.08f),
            "pale yellow" to cover(i(0xFFFFE97A), i(0xFFFFFFFF) to 0.2f),
            "pale pink" to cover(i(0xFFFFB6C8), i(0xFFB8F0FF) to 0.3f),
            "pure yellow" to cover(i(0xFFFFFF00), i(0xFF000000) to 0.1f),
            "pure red" to cover(i(0xFFFF0000), i(0xFFFFFFFF) to 0.1f),
            "pure cyan" to cover(i(0xFF00FFFF), i(0xFFFF00FF) to 0.25f),
            "pure magenta" to cover(i(0xFFFF00FF)),
            "pure green" to cover(i(0xFF00FF00), i(0xFF0000FF) to 0.2f),
            "deep blue" to cover(i(0xFF0000FF), i(0xFFFF7A00) to 0.15f),
            "grey" to cover(i(0xFF8A8A8A), i(0xFF3A3A3A) to 0.3f),
            "black" to cover(i(0xFF050505), i(0xFFD02030) to 0.06f),
            "near-black navy" to cover(i(0xFF080B20)),
            "black and white" to cover(i(0xFF0E0E10), i(0xFFF4F4F2) to 0.45f),
            "two colors" to cover(i(0xFF1F6F5C), i(0xFFE8A33D) to 0.35f),
        )
    }

    private fun rendered(g: CoverGradient, depth: Float, width: Int = w, height: Int = h): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1.5f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(width.toFloat(), height.toFloat())) {
            drawCoverGradient(g.stops(depth))
        }
        return bmp
    }

    /** The lowest contrast of white at [alpha] over the gradient from [fromY] (0..1 down the screen) to its foot. */
    private fun worst(page: Bitmap, alpha: Float, fromY: Float): Double {
        var min = 21.0
        for (y in (page.height * fromY).toInt() until page.height step 4) for (x in 0 until page.width step 8) {
            val bg = page.getPixel(x, y)
            val ink = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (alpha * 255).toInt()), bg)
            min = minOf(min, ColorUtils.calculateContrast(ink, bg))
        }
        return min
    }

    @Test
    fun whiteTypeStaysReadable() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((name, bmp) in covers) rows += "%-18s lead %08X second %08X".format(name, coverGradientOf(bmp)!!.lead, coverGradientOf(bmp)!!.second)
        for ((name, bmp) in covers) for (depth in listOf(0f, 0.35f, 0.7f, 1f)) {
            val page = rendered(coverGradientOf(bmp)!!, depth)
            // White: everywhere, the status bar's icons at the very top included.
            val white = worst(page, 1f, 0f)
            // The small type at 70% lies below the cover, in the lower half (the title and artist at about 55-70% down).
            val small = worst(page, 0.7f, GRADIENT_MIDDLE_AT)
            rows += "%-18s depth %.2f  white %.1f  small type %.1f".format(name, depth, white, small)
            if (white < 4.5) failures += "$name depth $depth: white $white"
            if (small < 4.5) failures += "$name depth $depth: small type $small"
        }
        File(File("build/backdrop-previews").apply { mkdirs() }, "cover-gradient.txt").writeText(rows.joinToString("\n"))
        assertTrue("hard to read:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun itsColorsAreTheCovers() {
        // The hue survives being darkened, on a pale cover and on a very saturated one.
        fun hue(c: Int) = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }[0]
        val yellow = coverGradientOf(cover(0xFFFFFF00.toInt()))!!.stops(0.7f)
        assertEquals(60f, hue(yellow.top), 4f)
        val pink = coverGradientOf(cover(0xFFFFB6C8.toInt()))!!.stops(0.7f)
        assertEquals(hue(0xFFFFB6C8.toInt()), hue(pink.top), 8f)
        // A small splash of color on a black cover leads: black has nothing to give.
        val splash = coverGradientOf(listOf(0xFF050505.toInt() to 9000, 0xFFD02030.toInt() to 700))!!
        assertEquals(0xFFD02030.toInt(), splash.lead)
        // A speck doesn't.
        val speck = coverGradientOf(listOf(0xFF3060A0.toInt() to 9900, 0xFFFF0000.toInt() to 100))!!
        assertEquals(0xFF3060A0.toInt(), speck.lead)
        // Two hues: both are used. One hue: the second is the lead again.
        val two = coverGradientOf(listOf(0xFF1F6F5C.toInt() to 6000, 0xFFE8A33D.toInt() to 3000))!!
        assertNotEquals(two.lead, two.second)
        assertEquals(two.lead, coverGradientOf(listOf(0xFF1F6F5C.toInt() to 6000, 0xFF1F7F6C.toInt() to 3000))!!.second)
        assertEquals(null, coverGradientOf(emptyList<Pair<Int, Int>>()))
        // From a picture: an orange is one of its colors (Palette's own filter leaves those out as skin tones).
        val picture = coverGradientOf(cover(0xFF1F6F5C.toInt(), 0xFFE8A33D.toInt() to 0.35f))!!
        assertEquals(36f, hue(picture.second), 12f)
    }

    @Test
    fun theSliderSetsTheFoot() {
        val g = coverGradientOf(cover(0xFF1F6F5C.toInt()))!!
        val lum = { d: Float -> ColorUtils.calculateLuminance(g.stops(d).foot) }
        assertEquals(0.0, lum(1f), 0.0001)
        assertTrue(lum(0f) > lum(0.5f) && lum(0.5f) > lum(1f))
        // The top stays: it's the foot that's being set.
        assertEquals(g.stops(0f).top, g.stops(1f).top)
    }

    /** It really is a gradient and not a blur: from one row to the next it only ever moves a step or two. */
    @Test
    fun itIsSmooth() {
        for ((name, bmp) in covers) {
            val page = rendered(coverGradientOf(bmp)!!, 0.7f)
            for (y in 1 until page.height) {
                val a = page.getPixel(w / 2, y - 1)
                val b = page.getPixel(w / 2, y)
                val step = maxOf(
                    kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)),
                    kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)),
                    kotlin.math.abs((a and 255) - (b and 255)),
                )
                assertTrue("$name jumps $step at row $y", step <= 4)
            }
        }
    }

    /** Every cover on a small player with a cover and lines of type, at three depths. */
    @Test
    fun contactSheet() {
        val tw = 180
        val th = 380
        val depths = listOf(0.7f, 0f, 1f)
        val out = Bitmap.createBitmap(covers.size * (tw + 6), depths.size * (th + 6), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFF505050.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        covers.forEachIndexed { i, (_, bmp) ->
            depths.forEachIndexed { j, depth ->
                val x = i * (tw + 6f)
                val y = j * (th + 6f)
                c.drawBitmap(rendered(coverGradientOf(bmp)!!, depth, tw, th), x, y, null)
                c.drawBitmap(bmp, null, android.graphics.RectF(x + 24, y + 44, x + tw - 24, y + 44 + tw - 48), null)
                paint.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(x + 24, y + 212, x + 130, y + 222, 5f, 5f, paint)
                paint.color = 0xB3FFFFFF.toInt()
                c.drawRoundRect(x + 24, y + 230, x + 100, y + 238, 4f, 4f, paint)
                paint.color = 0x59FFFFFF
                c.drawRoundRect(x + 24, y + 262, x + tw - 24, y + 266, 2f, 2f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(x + tw / 2f, y + 310, 18f, paint)
            }
        }
        File(File("build/backdrop-previews").apply { mkdirs() }, "cover-gradient.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

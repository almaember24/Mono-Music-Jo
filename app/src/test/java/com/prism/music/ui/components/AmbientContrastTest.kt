package com.prism.music.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.prism.music.ui.theme.boxBlur
import com.prism.music.ui.theme.contrastRatio
import com.prism.music.ui.theme.schemeFromSeed
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Text on the Ambient background (the frost of what's playing behind Home, Search, Library and Settings) stays readable
 * whatever the cover: the page's text, its secondary text and text on a frosted pane are measured against the frost at
 * every point a page's text can sit, in dark and light, for covers that are hard on it (bright yellow, near-white,
 * saturated red and cyan, mid grey, near-black, white over black). Draws them to app/build/frost-previews/ambient.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AmbientContrastTest {
    private val w = 360
    private val h = 760
    private val density = 1.5f

    private fun cover(top: Int, bottom: Int = top): Bitmap {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until 96) for (x in 0 until 96) b.setPixel(x, y, if (y < 48) top else bottom)
        return boxBlur(b, 3, 13)
    }

    private val covers = listOf(
        "yellow" to cover(0xFFF2D21B.toInt()), "white" to cover(0xFFF4F4F2.toInt()), "red" to cover(0xFFE0202A.toInt()),
        "cyan" to cover(0xFF22D3EE.toInt()), "grey" to cover(0xFF8A8A8A.toInt()), "black" to cover(0xFF0E0E10.toInt()),
        "white/black" to cover(0xFFF4F4F2.toInt(), 0xFF0E0E10.toInt()),
    )

    private fun render(frost: Bitmap, dark: Boolean): Bitmap {
        val scheme = schemeFromSeed(Color(0xFF3A86FF), dark, pureBlack = false)
        val c = frost.getPixel(48, 20)
        val palette = ArtPalette(Color(c), Color(c))
        val bmp = Bitmap.createBitmap((w * density).toInt(), (h * density).toInt(), Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawFrostedBackdrop(
                frost.asImageBitmap(), 1f, palette, scheme.background, AmbientFrostHeight.value * density, 0f, 0f, dark,
                pureBlack = false, bleed = false, ambient = true,
            )
        }
        return bmp
    }

    /** An artist / album / playlist page's backdrop from [frost], its colors carried down the page (#47), at the top. */
    private fun renderPicturePage(frost: Bitmap, dark: Boolean): Pair<Bitmap, Int> {
        val scheme = schemeFromSeed(Color(0xFF3A86FF), dark, pureBlack = false)
        val c = frost.getPixel(48, 20)
        val palette = ArtPalette(Color(c), Color(c))
        val bmp = Bitmap.createBitmap((w * density).toInt(), (h * density).toInt(), Bitmap.Config.ARGB_8888)
        val hero = minOf(w * 1.1f, h * 0.6f) * density
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawFrostedBackdrop(frost.asImageBitmap(), 1f, palette, scheme.background, hero, 0f, 0.5f, dark, pureBlack = false, bleed = true)
        }
        // The song list starts under the photo (its title and buttons sit on the photo's dissolving foot).
        return bmp to hero.toInt()
    }

    /** Below the photo on artist, album and playlist pages: the song rows. */
    @Test
    fun picturePagesStayReadable() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (dark in listOf(true, false)) {
            val scheme = schemeFromSeed(Color(0xFF3A86FF), dark, pureBlack = false)
            for ((name, frost) in covers) {
                val (page, from) = renderPicturePage(frost, dark)
                val list = Bitmap.createBitmap(page, 0, from, page.width, page.height - from)
                val text = worst(list, scheme.onSurface, top = 0)
                val secondary = worst(list, scheme.onSurfaceVariant, top = 0)
                rows += "%-5s %-12s text %.1f  secondary %.1f".format(if (dark) "dark" else "light", name, text, secondary)
                if (text < 4.5f) failures += "$name ${if (dark) "dark" else "light"}: text $text"
                if (secondary < 4.5f) failures += "$name ${if (dark) "dark" else "light"}: secondary $secondary"
            }
        }
        File(File("build/frost-previews").apply { mkdirs() }, "picture-pages.txt").writeText(rows.joinToString("\n"))
        assertTrue("hard to read below the photo:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * The lowest contrast of [ink] over each sampled point of [page] in the band a page's text uses, with [surface]
     * between them (a frosted pane), or none.
     */
    private fun worst(page: Bitmap, ink: Color, surface: Color? = null, top: Int = (40 * density).toInt()): Float {
        var min = 21f
        val step = 6
        for (y in top until page.height step step) for (x in 0 until page.width step step) {
            val bg = surface?.compositeOver(Color(page.getPixel(x, y))) ?: Color(page.getPixel(x, y))
            min = minOf(min, contrastRatio(ink.compositeOver(bg), bg))
        }
        return min
    }

    @Test
    fun textStaysReadable() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val shots = mutableListOf<Bitmap>()
        for (dark in listOf(true, false)) {
            val scheme = schemeFromSeed(Color(0xFF3A86FF), dark, pureBlack = false)
            val pane = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f)
            for ((name, frost) in covers) {
                val page = render(frost, dark)
                shots += page
                val text = worst(page, scheme.onSurface)
                val secondary = worst(page, scheme.onSurfaceVariant)
                val onPane = worst(page, scheme.onSurfaceVariant, pane)
                rows += "%-5s %-12s text %.1f  secondary %.1f  secondary on pane %.1f".format(if (dark) "dark" else "light", name, text, secondary, onPane)
                if (text < 4.5f) failures += "$name ${if (dark) "dark" else "light"}: text $text"
                if (secondary < 4.5f) failures += "$name ${if (dark) "dark" else "light"}: secondary $secondary"
                if (onPane < 4.5f) failures += "$name ${if (dark) "dark" else "light"}: secondary on pane $onPane"
            }
        }
        rows.forEach(::println)
        val tw = shots[0].width / 3
        val th = shots[0].height / 3
        val out = Bitmap.createBitmap(covers.size * (tw + 6), 2 * (th + 6), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(0xFFFF00FF.toInt())
        shots.forEachIndexed { i, b ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(b, tw, th, true), (i % covers.size) * (tw + 6f), (i / covers.size) * (th + 6f), null)
        }
        File(File("build/frost-previews").apply { mkdirs() }, "ambient.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File("build/frost-previews/ambient.txt").writeText(rows.joinToString("\n"))
        assertTrue("hard to read on Ambient:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}

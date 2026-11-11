package com.prism.music.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.palette.graphics.Palette
import com.prism.music.ui.theme.boxBlur
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the artist page's frosted backdrop as it was and with the picture's colors carried down the page (#47), dark
 * and light, at the top of the page and scrolled, side by side (app/build/frost-previews/), from the README's artist
 * photo. The hero photo itself is left out: this is about what's under and below it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FrostRenderTest {
    private val w = 360
    private val h = 760
    private val density = 1.5f

    private fun photo(): Bitmap {
        val shot = BitmapFactory.decodeFile(File("src/test/resources/frost/artist-dark.jpg").absolutePath)
        // The photo part of the screenshot (above the name).
        return Bitmap.createBitmap(shot, 0, 0, shot.width, (shot.height * 0.38f).toInt())
    }

    private fun render(frost: Bitmap, palette: ArtPalette, dark: Boolean, bleed: Boolean, scroll: Float): Bitmap {
        val bmp = Bitmap.createBitmap((w * density).toInt(), (h * density).toInt(), Bitmap.Config.ARGB_8888)
        val bg = if (dark) Color(0xFF101010) else Color(0xFFF7F7F7)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            val hero = minOf(w * 1.1f, h * 0.6f) * density
            drawFrostedBackdrop(frost.asImageBitmap(), 1f, palette, bg, hero, scroll * hero, 0.5f, dark, pureBlack = false, bleed = bleed)
        }
        return bmp
    }

    @Test
    fun beforeAndAfter() {
        val src = photo()
        val small = Bitmap.createScaledBitmap(src, 96, (96f * src.height / src.width).toInt(), true)
        val frost = boxBlur(small, 3, (minOf(small.width, small.height) / 7).coerceAtLeast(3))
        val p = Palette.from(Bitmap.createScaledBitmap(src, 160, (160f * src.height / src.width).toInt(), true)).maximumColorCount(16).generate()
        val dominant = Color((p.dominantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch)!!.rgb)
        val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch)?.rgb?.let { Color(it) } ?: dominant
        val palette = ArtPalette(vibrant, dominant)
        val shots = listOf(true, false).flatMap { dark ->
            listOf(0f, 0.9f).flatMap { scroll -> listOf(false, true).map { bleed -> render(frost, palette, dark, bleed, scroll) } }
        }
        val tw = shots[0].width
        val th = shots[0].height
        val out = Bitmap.createBitmap(4 * (tw + 12), 2 * (th + 12), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFF808080.toInt())
        shots.forEachIndexed { i, b -> c.drawBitmap(b, (i % 4) * (tw + 12f), (i / 4) * (th + 12f), null) }
        val dir = File("build/frost-previews").apply { mkdirs() }
        val file = File(dir, "artist-frost.png")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.absolutePath} (columns: before/after at the top, before/after scrolled; rows: dark, light)")
    }
}

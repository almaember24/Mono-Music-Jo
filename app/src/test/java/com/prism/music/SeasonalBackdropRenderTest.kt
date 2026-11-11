package com.prism.music

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.prism.music.ui.theme.Backdrop
import com.prism.music.ui.theme.drawDesign
import com.prism.music.ui.theme.schemeFromSeed
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The seasonal backgrounds (1.8.0), Harvest and Holiday, drawn at a phone's size in dark, light and Pure black, with the
 * screens' wash, a title, list rows, the mini player and the tab bar on top (app/build/backdrop-previews/seasonal-*.png),
 * so the drawings and how lists read over them can be looked at without a phone. Also: a frame is quick once built, and
 * the motion loops (the first and last frames are the same picture).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class SeasonalBackdropRenderTest {
    private val density = 2f
    private val w = (412 * density).toInt()
    private val h = (892 * density).toInt()

    private enum class Mode { DARK, LIGHT, BLACK }

    private fun accent(style: Backdrop) = if (style == Backdrop.HARVEST) Color(0xFFD9822B) else Color(0xFFC8283C)

    private fun render(style: Backdrop, mode: Mode, t: Float, mock: Boolean = true, width: Int = w, height: Int = h): Bitmap {
        val dark = mode != Mode.LIGHT
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scheme = schemeFromSeed(accent(style), dark, pureBlack = mode == Mode.BLACK)
        val bg = scheme.background
        // As BackdropLayer: on Pure black the background's own base is black.
        val palette = style.colors(dark).map { Color(it) }.let { if (mode == Mode.BLACK) listOf(Color.Black) + it.drop(1) else it }
        val d = density
        CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(width.toFloat(), height.toFloat())) {
            drawDesign(style.kind, palette, dark, t, d)
            if (!mock) return@draw
            val base = if (dark) 0.35f else 0.35f * 0.85f
            drawRect(Brush.verticalGradient(listOf(bg.copy(alpha = base * 0.7f), bg.copy(alpha = base), bg.copy(alpha = (base + 0.25f).coerceAtMost(0.95f)))))
            drawRect(scheme.onSurface, Offset(16f * d, 52f * d), Size(130f * d, 22f * d))
            for (i in 0 until 10) {
                val y = (112f + i * 60f) * d
                drawRoundRect(scheme.onSurface.copy(alpha = 0.3f), Offset(16f * d, y), Size(48f * d, 48f * d), CornerRadius(6f * d))
                drawRect(scheme.onSurface, Offset(76f * d, y + 10f * d), Size(170f * d, 10f * d))
                drawRect(scheme.onSurfaceVariant, Offset(76f * d, y + 28f * d), Size(110f * d, 8f * d))
            }
            // The mini player and the tab bar, as glass.
            val glass = scheme.surface.copy(alpha = 0.72f)
            drawRoundRect(glass, Offset(12f * d, size.height - 146f * d), Size(size.width - 24f * d, 60f * d), CornerRadius(16f * d))
            drawRoundRect(glass, Offset(12f * d, size.height - 78f * d), Size(size.width - 24f * d, 64f * d), CornerRadius(32f * d))
        }
        return bmp
    }

    private fun save(name: String, tiles: List<Bitmap>, background: Int) {
        val out = Bitmap.createBitmap(tiles.sumOf { it.width + 12 }, tiles.maxOf { it.height }, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(background)
        var x = 0f
        for (t in tiles) { c.drawBitmap(t, x, 0f, null); x += t.width + 12 }
        val dir = File("build/backdrop-previews").apply { mkdirs() }
        val f = File(dir, "$name.png")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${f.absolutePath}")
    }

    @Test fun seasonalSheets() {
        for (style in listOf(Backdrop.HARVEST, Backdrop.HOLIDAY)) {
            val name = style.name.lowercase()
            // Dark, light and Pure black, as a screen with its lists.
            save("seasonal-$name", Mode.entries.map { render(style, it, 0.3f) }, 0xFF808080.toInt())
            // The drawing alone, without the screen on top.
            save("seasonal-$name-bare", Mode.entries.map { render(style, it, 0.3f, mock = false) }, 0xFF808080.toInt())
            // Four moments of its motion.
            save("seasonal-$name-motion", listOf(0f, 0.25f, 0.5f, 0.75f).map { render(style, Mode.DARK, it, mock = false) }, 0xFF808080.toInt())
        }
    }

    @Test fun theMotionLoopsAndFramesAreQuick() {
        for (style in listOf(Backdrop.HARVEST, Backdrop.HOLIDAY)) {
            val first = render(style, Mode.DARK, 0f, mock = false)
            val last = render(style, Mode.DARK, 1f, mock = false)
            var differ = 0
            for (y in 0 until first.height step 4) for (x in 0 until first.width step 4) {
                val p = first.getPixel(x, y); val q = last.getPixel(x, y)
                val dist = maxOf(abs8(p shr 16, q shr 16), abs8(p shr 8, q shr 8), abs8(p, q))
                if (dist > 24) differ++
            }
            assertTrue("${style.name}: the loop's end isn't its start ($differ pixels differ)", differ < 40)
            // Built once (a size no other test drew, so it's built here); then each frame draws the still picture and
            // only what moves, into the same bitmap.
            val fw = w - 2; val fh = h - 2
            val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp.asImageBitmap())
            val scope = CanvasDrawScope()
            fun frame(t: Float) = scope.draw(Density(density), LayoutDirection.Ltr, canvas, Size(fw.toFloat(), fh.toFloat())) {
                drawDesign(style.kind, style.colors(true).map { Color(it) }, true, t, density)
            }
            val built = System.nanoTime().also { frame(0f) }.let { System.nanoTime() - it }
            val still = com.prism.music.ui.theme.seasonalStillNanos
            repeat(5) { frame(it / 5f) } // warm-up
            val start = System.nanoTime()
            repeat(30) { frame(it / 30f) }
            val each = (System.nanoTime() - start) / 30
            println("${style.label} at ${fw}x$fh px: built in ${built / 1_000_000} ms; still parts ${still / 1_000_000.0} ms " +
                "(each frame's cost if they weren't kept as a picture); a frame now (picture + what moves) ${each / 1_000_000.0} ms")
            assertTrue("${style.name}: a frame took ${each / 1_000_000} ms", each < 100_000_000)
        }
    }

    @Test fun theKeptPicturesStaySmall() {
        val held = com.prism.music.ui.theme.SeasonalScenes
        held.release()
        // Three page sizes and six preview sizes: only two pages and four previews are kept, two bytes a pixel.
        for (i in 0 until 3) render(Backdrop.HOLIDAY, Mode.DARK, 0.3f, mock = false, width = w - i * 10, height = h)
        for (i in 0 until 6) render(Backdrop.HARVEST, Mode.LIGHT, 0.3f, mock = false, width = 200 + i * 8, height = 430)
        val worst = 2L * w * h * 2 + 4L * (240 * 430) * 2
        println("kept: ${held.heldBytes()} bytes (at most $worst at ${w}x$h px)")
        assertTrue("kept ${held.heldBytes()} bytes", held.heldBytes() <= worst)
        held.release()
        assertTrue(held.heldBytes() == 0L)
    }

    private fun abs8(a: Int, b: Int) = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
}

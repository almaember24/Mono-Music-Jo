package com.prism.music

import android.graphics.Bitmap
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
import com.prism.music.ui.theme.BackdropGroup
import com.prism.music.ui.theme.BackdropKind
import com.prism.music.ui.theme.drawDesign
import com.prism.music.ui.theme.drawPattern
import com.prism.music.ui.theme.schemeFromSeed
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every Simple and Designs background, in dark and light, onto a contact sheet per mode
 * (app/build/backdrop-previews/), with the screens' wash and a few mock rows on top, so they can be
 * looked at without a phone. Also times a frame of each, as a guard against slow designs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BackdropRenderTest {
    private val w = 360
    private val h = 760
    private val density = 1.5f

    private fun render(style: Backdrop, dark: Boolean, accent: Color, t: Float = 0.3f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scheme = schemeFromSeed(accent, dark, pureBlack = false)
        val bg = scheme.background
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(w.toFloat(), h.toFloat())) {
            if (style.group == BackdropGroup.DESIGNS) drawDesign(style.kind, style.colors(dark).map { Color(it) }, dark, t, density)
            else drawPattern(style.kind, bg, scheme.primary, scheme.tertiary, dark, t, density)
            // Same wash as BackdropLayer at the default softness (0.35).
            val base = if (dark) 0.35f else 0.35f * 0.85f
            val wash = if (style.group == BackdropGroup.SIMPLE) base * 0.5f else base
            drawRect(Brush.verticalGradient(listOf(bg.copy(alpha = wash * 0.7f), bg.copy(alpha = wash), bg.copy(alpha = (wash + 0.25f).coerceAtMost(0.95f)))))
            // A title and a few list rows, to judge how text reads on it.
            drawRect(scheme.onSurface, Offset(16f * density, 40f * density), Size(150f * density, 14f * density))
            for (i in 0 until 9) {
                val y = (90f + i * 52f) * density
                drawRect(scheme.onSurface.copy(alpha = 0.35f), Offset(16f * density, y), Size(34f * density, 34f * density))
                drawRect(scheme.onSurface, Offset(60f * density, y + 6f * density), Size(140f * density, 8f * density))
                drawRect(scheme.onSurfaceVariant, Offset(60f * density, y + 20f * density), Size(90f * density, 6f * density))
            }
        }
        return bmp
    }

    private val styles = Backdrop.entries.filter {
        it.group == BackdropGroup.DESIGNS || (it.group == BackdropGroup.SIMPLE && it.kind != BackdropKind.BLEND)
    }

    private fun sheet(name: String, dark: Boolean, accent: Color) {
        val cols = 6
        val rows = (styles.size + cols - 1) / cols
        val out = Bitmap.createBitmap(cols * (w + 8), rows * (h + 8), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(if (dark) 0xFF303030.toInt() else 0xFFB0B0B0.toInt())
        styles.forEachIndexed { i, s ->
            c.drawBitmap(render(s, dark, accent), (i % cols) * (w + 8f), (i / cols) * (h + 8f), null)
        }
        val dir = File("build/backdrop-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${File(dir, "$name.png").absolutePath}: " + styles.joinToString { it.label })
    }

    @Test
    fun contactSheets() {
        val blue = Color(0xFF3A86FF)
        sheet("dark", dark = true, accent = blue)
        sheet("light", dark = false, accent = blue)
        sheet("light-white-accent", dark = false, accent = Color.White)
    }

    @Test
    fun framesAreQuick() {
        // A warm-up, then the time for a frame of each design at phone size (paths are cached after the first).
        for (s in styles.filter { it.group == BackdropGroup.DESIGNS }) {
            render(s, true, Color(0xFF3A86FF), 0.1f)
            val start = System.nanoTime()
            repeat(5) { render(s, true, Color(0xFF3A86FF), 0.1f + it * 0.1f) }
            println("frame ${s.label}: ${(System.nanoTime() - start) / 5 / 1_000_000} ms")
        }
    }
}

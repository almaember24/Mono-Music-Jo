package com.prism.music

import android.graphics.Bitmap
import android.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.prism.music.ui.theme.PrismAvatar
import com.prism.music.ui.theme.drawPrismAvatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every Prism profile picture as a disc (large and at the size Home shows it) onto one sheet,
 * app/build/avatar-previews/avatars.png, to look at; and checks each draws something of its own, with stable ids.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AvatarRenderTest {
    private fun render(a: PrismAvatar, px: Int): Bitmap {
        val square = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(square.asImageBitmap()), Size(px.toFloat(), px.toFloat())) { drawPrismAvatar(a) }
        // Clipped to a circle, as the app shows it.
        val disc = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(disc)
        c.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
        c.drawBitmap(square, 0f, 0f, null)
        return disc
    }

    /** The Prism picture large and square (no circle), for the showcase film's opening and closing shots. */
    @Test
    fun prismPictureLarge() {
        val px = 1600
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(px.toFloat(), px.toFloat())) { drawPrismAvatar(PrismAvatar.PRISM) }
        val dir = File("build/avatar-previews").apply { mkdirs() }
        File(dir, "prism-1600.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // And as the round picture, clear outside the circle, for light grounds.
        File(dir, "prism-disc-1600.png").outputStream().use { render(PrismAvatar.PRISM, px).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyPictureDrawsAndIdsStay() {
        assertEquals(PrismAvatar.entries.size, PrismAvatar.entries.map { it.id }.toSet().size)
        assertEquals(PrismAvatar.NIGHT, PrismAvatar.of("night"))
        val big = 240
        val small = 84
        val cols = 6
        val rows = (PrismAvatar.entries.size + cols - 1) / cols
        val out = Bitmap.createBitmap(cols * (big + 24), rows * (big + small + 40), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(0xFFE8E8EC.toInt())
        val seen = HashSet<Int>()
        PrismAvatar.entries.forEachIndexed { i, a ->
            val x = (i % cols) * (big + 24f) + 12f
            val y = (i / cols) * (big + small + 40f) + 12f
            val bmp = render(a, big)
            canvas.drawBitmap(bmp, x, y, null)
            canvas.drawBitmap(render(a, small), x + (big - small) / 2f, y + big + 12f, null)
            // Something drawn, and not the same picture as another.
            val pixels = IntArray(big * big).also { bmp.getPixels(it, 0, big, 0, 0, big, big) }
            assertTrue("${a.id} draws colors", pixels.toSet().size > 8)
            seen += pixels.contentHashCode()
        }
        assertEquals("every picture is different", PrismAvatar.entries.size, seen.size)
        val dir = File("build/avatar-previews").apply { mkdirs() }
        File(dir, "avatars.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${File(dir, "avatars.png").absolutePath}")
    }
}

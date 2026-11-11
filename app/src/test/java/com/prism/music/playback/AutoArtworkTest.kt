package com.prism.music.playback

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.prism.music.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pictures for Android Auto ([ArtworkProvider]): everything goes to the car as a JPEG (YouTube's PNG mix covers were
 * left blank), and Prism's own tiles (Downloads had none, and the car drew a warning sign) draw with their corners
 * filled. The tiles are written to app/build/auto-tiles/ to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class AutoArtworkTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun Bitmap.bytes(format: Bitmap.CompressFormat) = ByteArrayOutputStream().also { compress(format, 95, it) }.toByteArray()

    private fun isJpeg(b: ByteArray) = b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    @Test
    fun aJpegIsPassedOnAsItIs() {
        val jpeg = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.bytes(Bitmap.CompressFormat.JPEG)
        assertArrayEquals(jpeg, asJpeg(jpeg))
    }

    @Test
    fun aPngBecomesAJpegOnAnOpaqueGround() {
        // A see-through PNG (as YouTube's "Liked Music" picture can be) goes on near-black, not on black-or-garbage.
        val png = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }.bytes(Bitmap.CompressFormat.PNG)
        val out = asJpeg(png)
        assertNotNull(out)
        assertTrue(isJpeg(out!!))
        val back = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(40, back.width)
        assertTrue(Color.red(back.getPixel(20, 20)) < 40)
    }

    @Test
    fun somethingThatIsntAPictureGivesNothing() {
        assertNull(asJpeg("<html>Bad Request</html>".toByteArray()))
    }

    @Test
    fun prismsTilesDrawWithTheirCornersFilled() {
        val dir = File("build/auto-tiles").apply { mkdirs() }
        val tiles = mapOf(
            "ic_auto_downloads" to R.drawable.ic_auto_downloads,
            "ic_auto_liked" to R.drawable.ic_auto_liked,
            "ic_auto_playlist" to R.drawable.ic_auto_playlist,
            "ic_auto_shuffle" to R.drawable.ic_auto_shuffle,
            "ic_auto_dj" to R.drawable.ic_auto_dj,
        )
        for ((name, id) in tiles) {
            val d = ContextCompat.getDrawable(context, id)!!
            val bitmap = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 288, 288)
            d.draw(Canvas(bitmap))
            val jpeg = asJpeg(bitmap.bytes(Bitmap.CompressFormat.PNG))!!
            assertTrue(isJpeg(jpeg))
            File(dir, "$name.jpg").writeBytes(jpeg)
            val back = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            // The corner (outside the rounded square) takes the tile's color, so no black corners in the car.
            val corner = back.getPixel(1, 1); val edge = back.getPixel(144, 4)
            val channels = listOf<(Int) -> Int>({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
            assertTrue("$name corner", channels.all { f -> kotlin.math.abs(f(corner) - f(edge)) < 24 })
        }
    }
}

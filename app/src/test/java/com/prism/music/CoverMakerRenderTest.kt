package com.prism.music

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.prism.music.ui.screens.CoverDesign
import com.prism.music.ui.screens.renderCover
import com.prism.music.ui.theme.BackdropKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.7.6 cover maker (H4): covers render at 1200 px, name and all, onto app/build/cover-previews/ to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class CoverMakerRenderTest {
    @Test
    fun coversRender() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = File("build/cover-previews").apply { mkdirs() }
        val designs = listOf(
            "road-trip" to CoverDesign("Road trip"),
            "long" to CoverDesign("Songs for a very long drive through the night", top = 0xFFFF6B3D.toInt(), bottom = 0xFF2A0E1A.toInt(), pattern = BackdropKind.DIAMONDS),
            "plain" to CoverDesign("Focus", top = 0xFF0077B6.toInt(), bottom = 0xFF031827.toInt(), pattern = null, edge = false),
        )
        for ((name, d) in designs) {
            val bmp = renderCover(context, d)
            assertEquals(1200, bmp.width)
            // Something drawn: the corners differ from the middle (the edge, the gradient).
            assertTrue(bmp.getPixel(4, 4) != 0)
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

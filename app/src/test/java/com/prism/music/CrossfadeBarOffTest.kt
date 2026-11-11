package com.prism.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.ScrubberThumb
import com.prism.music.ui.player.ThinSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.1's bar shaped by the crossfade, off (the default): the progress bar is exactly 1.8.0's. Every way the bar can be
 * drawn without a shape (each style's height, the knobs, ticks, the loaded part, a fill, the waveform, Pulse, Ribbons)
 * is compared pixel for pixel with what 1.8.0's code drew (`crossfade/bar-off-1.8.0.png`, rendered from v1.8.0's
 * PlayerControls.kt; PRISM_RECORD_GOLDEN=1 writes it anew).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class CrossfadeBarOffTest {
    @get:Rule val compose = createComposeRule()

    /** The bars, only through the parameters 1.8.0's ThinSlider had. */
    @Composable
    private fun Bars() {
        val wave = FloatArray(48) { i -> (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.9f) * kotlin.math.cos(i * 0.37f))) }
        Column(Modifier.width(360.dp).background(Color(0xFF0D0D12)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThinSlider(0.4f, {}, idleHeight = 5.dp, activeHeight = 10.dp, spectrum = true)
            ThinSlider(0.7f, {}, idleHeight = 9.dp, activeHeight = 14.dp, spectrum = true, secondary = 0.85f, marks = listOf(0.3f, 0.8f))
            ThinSlider(0.55f, {}, idleHeight = 4.dp, activeHeight = 6.dp, spectrum = true, thumb = true, thumbShape = ScrubberThumb.DOT)
            ThinSlider(0.25f, {}, idleHeight = 4.dp, activeHeight = 6.dp, spectrum = true, thumb = true, thumbShape = ScrubberThumb.LINE)
            ThinSlider(0.9f, {}, idleHeight = 4.dp, activeHeight = 6.dp, spectrum = true, thumb = true, thumbShape = ScrubberThumb.RING)
            ThinSlider(0.6f, {}, idleHeight = 9.dp, activeHeight = 14.dp, fill = SolidColor(Color(0xFF7C6CFF)))
            ThinSlider(0.5f, {}, idleHeight = 22.dp, activeHeight = 26.dp, spectrum = true, wave = wave, marks = listOf(0.75f))
            ThinSlider(0.45f, {}, idleHeight = 9.dp, activeHeight = 14.dp, spectrum = true, pulse = { 0.5f })
            ThinSlider(0.65f, {}, idleHeight = 6.dp, activeHeight = 9.dp, spectrum = true, thumb = true, ribbons = { 0.35f })
            ThinSlider(0f, {}, idleHeight = 5.dp, activeHeight = 10.dp, spectrum = true)
            ThinSlider(1f, {}, idleHeight = 7.dp, activeHeight = 7.dp)
        }
    }

    @Test fun theBarWithTheOptionOffIs180s() {
        compose.setContent { Bars() }
        compose.waitForIdle()
        val now = compose.onRoot().captureToImage().asAndroidBitmap()
        val golden = File("src/test/resources/crossfade/bar-off-1.8.0.png")
        if (System.getenv("PRISM_RECORD_GOLDEN") == "1") {
            golden.parentFile?.mkdirs()
            golden.outputStream().use { now.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        assertTrue("the 1.8.0 render is there ($golden)", golden.exists())
        val was = BitmapFactory.decodeFile(golden.path)
        assertEquals("same size", was.width to was.height, now.width to now.height)
        val a = IntArray(was.width * was.height).also { was.getPixels(it, 0, was.width, 0, 0, was.width, was.height) }
        val b = IntArray(now.width * now.height).also { now.getPixels(it, 0, now.width, 0, 0, now.width, now.height) }
        val differ = a.indices.count { a[it] != b[it] }
        if (differ > 0) File("build/crossfade-previews").apply { mkdirs() }.let { d -> File(d, "bar-off-now.png").outputStream().use { now.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertEquals("pixels that differ from 1.8.0's bar", 0, differ)
    }
}

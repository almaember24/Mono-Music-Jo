package com.prism.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.ui.player.SweptLine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #303: Karaoke's sung words (a color) were drawn over the whole line in white, so the white words' soft edges showed
 * round the colored letters as a pale fringe. The words still to come are drawn only where nothing's been sung.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class KaraokeFringeTest {
    @get:Rule val compose = createComposeRule()

    private val words = listOf(LyricWord(0, 400, "Sing "), LyricWord(400, 800, "along "), LyricWord(800, 1200, "now"))
    private val line = LyricLine(0, words.joinToString("") { it.text }, 1200, words)

    /** The line drawn red over white-to-come at [now] ms on black: each pixel's (red, green). */
    private fun draw(now: Long): List<Pair<Int, Int>> {
        val clock = mutableLongStateOf(now)
        compose.setContent {
            Box(Modifier.width(360.dp).background(Color.Black).padding(8.dp).testTag("line")) {
                SweptLine(
                    line, clock, TextStyle(fontSize = 40.sp), litBrush = SolidColor(Color.Red), dimColor = Color.White.copy(alpha = 0.92f),
                )
            }
        }
        compose.waitForIdle()
        val bmp = compose.onNodeWithTag("line").captureToImage().asAndroidBitmap()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return px.map { ((it shr 16) and 0xFF) to ((it shr 8) and 0xFF) }
    }

    @Test fun aSungLineHasNoPaleFringe() {
        val sung = draw(5_000)
        assertTrue("the sung words are there, in red", sung.any { (r, _) -> r > 200 })
        // Red over black has no green in it anywhere; white words underneath would add some at every letter's edge.
        val greenest = sung.maxOf { it.second }
        assertTrue("no white round the sung letters (greenest pixel $greenest)", greenest < 8)
    }

    @Test fun wordsStillToComeAreThere() {
        val none = draw(-1)
        assertTrue("the line still to come is drawn in white", none.any { (r, g) -> r > 200 && g > 200 })
    }
}

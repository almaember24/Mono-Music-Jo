package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.LocalMusicPlaying
import com.prism.music.ui.components.PlayingBars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #302: the playing bars beside the current song bounced on while it was paused. They move only while it plays. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PlayingBarsTest {
    @get:Rule val compose = createComposeRule()

    private fun pixels(): IntArray {
        val bmp: Bitmap = compose.onNodeWithTag("bars").captureToImage().asAndroidBitmap()
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    /** The bars drawn now and a quarter of a second later: whether they moved. */
    private fun moves(): Boolean {
        compose.mainClock.advanceTimeBy(50)
        val before = pixels()
        compose.mainClock.advanceTimeBy(250)
        return !before.contentEquals(pixels())
    }

    @Test fun theBarsHoldStillWhilePausedAndMoveWhilePlaying() {
        val playing = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMusicPlaying provides { playing.value }) {
                Box(Modifier.size(40.dp).background(Color.Black).testTag("bars")) { PlayingBars(Modifier.size(26.dp, 28.dp)) }
            }
        }
        assertFalse("paused: the bars stand still", moves())
        playing.value = true
        assertTrue("playing: the bars bounce", moves())
        playing.value = false
        assertFalse("paused again: still once more", moves())
    }
}

package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.prism.music.ui.theme.FadingCover
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Now playing fades between songs" (#283): with nothing playing any more, the last cover doesn't stay behind the screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class FadingCoverTest {
    @get:Rule val compose = createComposeRule()

    private fun middle(): Int {
        val bmp: Bitmap = compose.onNodeWithTag("cover").captureToImage().asAndroidBitmap()
        return bmp.getPixel(bmp.width / 2, bmp.height / 2)
    }

    @Test fun stoppingPlaybackTakesTheCoverAway() {
        // Every cover is plain red here.
        val red = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).components {
            add(Interceptor { chain: Interceptor.Chain -> SuccessResult(red.asImage(), chain.request, DataSource.MEMORY) as ImageResult })
        }.build())
        val url = mutableStateOf<String?>("cover://song")
        compose.setContent {
            Box(Modifier.size(40.dp).background(Color.Black).testTag("cover")) { FadingCover(url.value, 300, Modifier.fillMaxSize()) }
        }
        compose.waitUntil(5_000) { middle() == android.graphics.Color.RED }
        url.value = null
        compose.waitForIdle()
        assertEquals("nothing playing: no cover", android.graphics.Color.BLACK, middle())
    }
}

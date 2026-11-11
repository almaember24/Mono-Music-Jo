package com.prism.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.components.FrostImage
import com.prism.music.ui.components.FrostedBackdrop
import com.prism.music.ui.player.drawStillMesh
import com.prism.music.ui.tv.TvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The TV's frost made once into a picture (1.8.0: drawn live it took about 9 ms of each frame on the owner's TV) looks
 * the same as the frost drawn live: same picture, tone, wash and grain, pixel for pixel within rounding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h270dp-land-mdpi", application = android.app.Application::class)
class BakedFrostTest {
    @get:Rule val compose = createComposeRule()

    private var baked by mutableStateOf(false)

    /** A made-up blurred cover: two colors across it. */
    private val frost = FrostImage(
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { b ->
            Canvas(b).drawRect(0f, 0f, 64f, 64f, Paint().apply { shader = LinearGradient(0f, 0f, 64f, 64f, 0xFF36B8FF.toInt(), 0xFFFF4D5E.toInt(), Shader.TileMode.CLAMP) })
        }.asImageBitmap(),
    )

    private fun shot(): Bitmap {
        repeat(10) { compose.mainClock.advanceTimeBy(120); Thread.sleep(30) }
        compose.waitForIdle()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    /** The mean difference of [a] and [b] per channel (0–255), over a grid of points. */
    private fun diff(a: Bitmap, b: Bitmap): Double {
        var sum = 0.0; var n = 0
        for (y in 4 until a.height step 9) for (x in 4 until a.width step 9) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            sum += (Math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)) + Math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)) +
                Math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q))) / 3.0
            n++
        }
        return sum / n
    }

    /** The player's mesh made once into a picture (the TV's player and lyrics) looks like the mesh drawn live. */
    @Test fun theBakedPlayerMeshLooksLikeTheLiveOne() {
        val mesh = com.prism.music.ui.player.ArtworkMesh(frost.image)
        compose.setContent {
            TvTheme(AppSettings()) {
                if (!baked) com.prism.music.ui.player.ArtworkMeshBackdrop(mesh, Modifier.fillMaxSize(), liveBlur = false)
                else com.prism.music.ui.tv.TvBaked(mesh) { drawStillMesh(mesh) }
            }
        }
        val live = shot()
        baked = true
        var got = shot()
        val end = System.currentTimeMillis() + 10_000
        while (diff(live, got) > 2.0 && System.currentTimeMillis() < end) got = shot()
        val d = diff(live, got)
        assertTrue("baked mesh differs from live by $d per channel", d <= 2.0)
    }

    @Test fun theBakedFrostLooksLikeTheLiveOne() {
        compose.setContent {
            TvTheme(AppSettings()) {
                FrostedBackdrop(frost, null, 270.dp, { 0f }, Modifier.fillMaxSize(), parallax = 0f, ambient = true, baked = baked)
            }
        }
        val live = shot()
        baked = true
        // The picture's made off the main thread; it fades in over the plain background.
        var got = shot()
        val end = System.currentTimeMillis() + 10_000
        while (diff(live, got) > 2.0 && System.currentTimeMillis() < end) got = shot()
        val d = diff(live, got)
        assertTrue("baked differs from live by $d per channel", d <= 2.0)
        // And it isn't just the background: the cover's colors are in it (left blue, right red).
        val left = got.getPixel(got.width / 8, got.height / 4); val right = got.getPixel(got.width * 7 / 8, got.height / 4)
        assertTrue("the frost shows the cover", android.graphics.Color.blue(left) > android.graphics.Color.red(left) && android.graphics.Color.red(right) > android.graphics.Color.blue(right))
    }
}

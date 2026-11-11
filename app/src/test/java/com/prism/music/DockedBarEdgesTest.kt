package com.prism.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.prism.music.ui.theme.GlassSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #290: the docked tab bar, on glass, showed a bright line down each side of the screen: its glass drew the lit edge all
 * round it. A flat bar from side to side keeps only the hairline along its top.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi", application = android.app.Application::class)
class DockedBarEdgesTest {
    @get:Rule val compose = createComposeRule()

    /** A bar of glass, [refract] as the docked bar asks it (false) or as other glass is (true): its pixel rows. */
    private fun bar(refract: Boolean): Array<IntArray> {
        compose.setContent {
            Box(Modifier.testTag("bar").background(Color.Black).padding(4.dp)) {
                GlassSurface(Modifier.size(200.dp, 56.dp), shape = RoundedCornerShape(0.dp), elevation = 0.dp, tint = Color(0xFF202020), refract = refract) {}
            }
        }
        compose.waitForIdle()
        val bmp = compose.onNodeWithTag("bar").captureToImage().asAndroidBitmap()
        return Array(bmp.height) { y -> IntArray(bmp.width) { x -> bmp.getPixel(x, y) and 0xFF } }
    }

    @Test fun theDockedBarHasNoLinesDownItsSides() {
        val rows = bar(refract = false)
        val inset = 12 // the 4 dp of black round the bar, at xxhdpi
        val middle = rows[rows.size / 3]
        val center = middle[middle.size / 2]
        // Its first and last columns are as dark as its middle a third of the way down.
        assertEquals("left edge", center, middle[inset])
        assertEquals("right edge", center, middle[middle.size - 1 - inset])
        // The top keeps its hairline.
        assertTrue("a lit line along the top", rows[inset].drop(inset + 10).take(100).max() > center)
    }

    @Test fun otherGlassKeepsItsEdgeAllRound() {
        val rows = bar(refract = true)
        val inset = 12
        val middle = rows[rows.size / 3]
        assertTrue("a floating piece of glass is lit down its sides", middle[inset] > middle[middle.size / 2])
    }
}

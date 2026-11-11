package com.prism.music

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.prism.music.ui.theme.PrismIcons
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #313: the play button's triangle sat a little right of center. It was centered on its middle of weight, which leaves
 * its point reaching far to the right; it now sits between that and its box's middle, where a triangle looks centered.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PlayIconCenterTest {
    @Test fun thePlayTriangleLooksCentered() {
        val paths = PrismIcons.Play.root.filterIsInstance<VectorPath>() +
            PrismIcons.Play.root.filterIsInstance<VectorGroup>().flatMap { g -> g.filterIsInstance<VectorPath>() }
        val bounds = paths.map { it.pathData.toPath().getBounds() }
        val left = bounds.minOf { it.left }
        val right = bounds.maxOf { it.right }
        val middle = (left + right) / 2
        val width = right - left
        // Its box's middle a little right of the icon's center (about a tenth of its width): not on it (it looks left),
        // nor as far as its middle of weight puts it (about a sixth, which looks right).
        val shift = (middle - PrismIcons.Play.viewportWidth / 2) / width
        assertTrue("the triangle's box sits ${"%.3f".format(shift)} of its width right of center", shift in 0.05f..0.12f)
    }
}

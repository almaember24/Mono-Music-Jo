package com.prism.music.ui.player

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's white type stays readable on the backdrop made from a pale cover (A Night at the Opera, 2026-10-20). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerBackdropTest {
    private fun hsl(c: Int) = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }

    @Test
    fun paleCoversGiveADarkEnoughBackdrop() {
        for (c in listOf(0xFFFFFFFF, 0xFFF4F1EA, 0xFFFFE97A, 0xFFB8F0FF, 0xFFFFB6C8, 0xFF9E9E9E).map { it.toInt() }) {
            val out = ceiled(hsl(c))
            val contrast = ColorUtils.calculateContrast(0xFFFFFFFF.toInt(), out)
            assertTrue("white on ${Integer.toHexString(out)} is $contrast:1", contrast >= 4.5)
            // The hue is kept (greys stay grey).
            if (hsl(c)[1] > 0.2f) assertEquals(hsl(c)[0], hsl(out)[0], 6f)
        }
    }

    @Test
    fun darkCoversAreLeftAlone() {
        for (c in listOf(0xFF1A2B4C, 0xFF5A1010, 0xFF202020).map { it.toInt() }) {
            assertEquals(ColorUtils.calculateLuminance(c), ColorUtils.calculateLuminance(ceiled(hsl(c))), 0.003)
        }
    }
}

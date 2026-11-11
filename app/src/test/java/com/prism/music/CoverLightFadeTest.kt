package com.prism.music

import androidx.compose.ui.graphics.Color
import com.prism.music.ui.theme.SpectrumPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * #254 (1.7.9.2): with the light "From the cover", the next song's light used to replace the last at once (the
 * reporter's progress bar, Play ring and picked cards all cut to the new colors); now it fades over "Between songs".
 */
class CoverLightFadeTest {
    private val gold = listOf(0xFFE0A030.toInt(), 0xFFF2C46B.toInt(), 0xFFB87A1E.toInt())
    private val blues = listOf(0xFF1E3A8A.toInt(), 0xFF2563EB.toInt(), 0xFF60A5FA.toInt(), 0xFF93C5FD.toInt(), 0xFF1D4ED8.toInt())

    private fun dist(a: Int, b: Int) = Color(a).let { x -> Color(b).let { y -> abs(x.red - y.red) + abs(x.green - y.green) + abs(x.blue - y.blue) } }

    @Test fun startsAtTheOldLightAndEndsAtTheNew() {
        val start = SpectrumPresets.fadeStops(gold, blues, 0f)
        val end = SpectrumPresets.fadeStops(gold, blues, 1f)
        assertEquals(5, start.size)
        assertTrue("first stop is the old light's", dist(start.first(), gold.first()) < 0.02f)
        assertTrue("last stop is the old light's", dist(start.last(), gold.last()) < 0.02f)
        end.zip(blues).forEach { (a, b) -> assertTrue(dist(a, b) < 0.02f) }
    }

    @Test fun movesALittleAtEachStep() {
        val steps = (0..40).map { SpectrumPresets.fadeStops(gold, blues, it / 40f) }
        val biggest = steps.zipWithNext().maxOf { (a, b) -> a.zip(b).maxOf { (x, y) -> dist(x, y) } }
        assertTrue("no step jumps ($biggest)", biggest < 0.12f)
    }

    @Test fun nothingToFadeFromTakesTheNewAtOnce() {
        assertEquals(blues, SpectrumPresets.fadeStops(emptyList(), blues, 0.3f))
    }
}

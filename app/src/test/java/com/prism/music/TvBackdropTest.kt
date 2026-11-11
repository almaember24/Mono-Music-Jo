package com.prism.music

import com.prism.music.data.prefs.TvPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reddit (1.8.1): "options on TV to change background opacity, especially in lyrics mode". As it comes unless picked;
 * darker steps lay more black over the cover's backdrop and the video's scrim; lighter lets a video show through more.
 */
class TvBackdropTest {
    @Test fun asItComesUnlessPicked() {
        assertEquals(0, TvPrefs().backdropDim)
        assertEquals(0f, TvPrefs.coverShade(0), 0f)
        assertEquals(1f, TvPrefs.videoShade(0), 0f)
    }

    @Test fun eachStepDarkerThanTheLast() {
        val cover = (TvPrefs.DIM_LIGHTER..TvPrefs.DIM_DARKEST).map { TvPrefs.coverShade(it) }
        val video = (TvPrefs.DIM_LIGHTER..TvPrefs.DIM_DARKEST).map { TvPrefs.videoShade(it) }
        assertTrue(cover.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(video.zipWithNext().all { (a, b) -> b > a })
        // Lighter: the cover's own backdrop can't be lighter; the video shows through more.
        assertEquals(0f, TvPrefs.coverShade(TvPrefs.DIM_LIGHTER), 0f)
        assertTrue(TvPrefs.videoShade(TvPrefs.DIM_LIGHTER) < 1f)
        // Out of range (an old or odd value): held to the ends.
        assertEquals(TvPrefs.coverShade(TvPrefs.DIM_DARKEST), TvPrefs.coverShade(9), 0f)
    }
}

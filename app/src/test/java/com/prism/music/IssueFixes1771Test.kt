package com.prism.music

import com.prism.music.data.ArtistPrefs
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.stream.PlayerJsSolver
import com.prism.music.playback.VideoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

/** 1.7.7.1's fixes (plan section U). */
class IssueFixes1771Test {
    /**
     * #179: the solver's sandbox can come without locale data, so `toLocaleString` threw "Internal error. Icu error."
     * while YouTube's player loaded. With the guard, a throwing locale call answers in its plain form.
     */
    @Test
    fun localeCallsThatThrowFallBack() {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                Number.prototype.toLocaleString = function () { throw new TypeError("Internal error. Icu error."); };
                String.prototype.localeCompare = function () { throw new TypeError("Internal error. Icu error."); };
                """.trimIndent(),
                "broken", 1, null,
            )
            cx.evaluateString(scope, PlayerJsSolver.LOCALE_GUARD, "guard", 1, null)
            assertEquals("1234.5", Context.toString(cx.evaluateString(scope, "(1234.5).toLocaleString()", "t", 1, null)))
            assertEquals(-1.0, Context.toNumber(cx.evaluateString(scope, "'a'.localeCompare('b')", "t", 1, null)), 0.0)
            // A working locale call is left alone.
            assertEquals("ABC", Context.toString(cx.evaluateString(scope, "'abc'.toLocaleUpperCase()", "t", 1, null)))
        } finally {
            Context.exit()
        }
    }

    /** #182: stars that came in a batch (the library brought over) are told apart from stars given by hand. */
    @Test
    fun batchStarsAreToldApart() {
        val t = 1_800_000_000_000L
        val batch = (0 until 40).map { FavoriteArtist("UC$it", "Artist $it", at = t + it * 3) }
        val byHand = listOf(
            FavoriteArtist("UChand1", "Mine", at = t - 86_400_000),
            FavoriteArtist("UChand2", "Mine too", at = t + 3_600_000),
            FavoriteArtist("UCold", "Before stars had a time", at = 0),
        )
        val found = ArtistPrefs.broughtInBatch(batch + byHand).map { it.id }.toSet()
        assertEquals(batch.map { it.id }.toSet(), found)
        // Nine quick stars in a row aren't a batch.
        assertTrue(ArtistPrefs.broughtInBatch((0 until 9).map { FavoriteArtist("UC$it", "A", at = t + it) }).isEmpty())
    }

    /** #180: a video's lag can't be longer than what the video adds to (or cuts from) the song. */
    @Test
    fun videoLagMustFitTheLengths() {
        // A 3:18 song and a 4:05 video with a 40 s intro: fine.
        assertTrue(VideoSync.fits(40_000, 198, 245))
        // The same lag on a video as long as the song: a match on a repeated chorus.
        assertFalse(VideoSync.fits(41_600, 198, 200))
        // A video that starts in a little later (cut intro).
        assertTrue(VideoSync.fits(-6_000, 200, 194))
        assertFalse(VideoSync.fits(-30_000, 200, 198))
        // Lengths unknown: not held against it.
        assertTrue(VideoSync.fits(41_600, 0, 200))
    }
}

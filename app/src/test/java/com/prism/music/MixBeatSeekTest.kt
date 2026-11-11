package com.prism.music

import com.prism.music.playback.MixEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * #210 (1.8.0): a tempo-matched crossfade never plays anything twice. When the next song's beats came in off the ending
 * song's, a big gap was closed by moving the next song back by the gap from its reported position, which replays what
 * was just heard (up to half a beat), and more when that reported position was stale (it lags by up to a few hundred
 * ms while a player is busy). Now it only ever moves on, to the next place where the beats line up.
 */
class MixBeatSeekTest {
    /** How far apart two places in a song are, as beats go (0 when their beats line up). */
    private fun offBeat(a: Long, b: Long, beatMs: Double): Double {
        val d = (a - b) / beatMs
        return abs(d - Math.round(d)) * beatMs
    }

    @Test fun neverBackOverWhatsBeenHeard() {
        val beatMs = 500.0 // 120 BPM
        val heard = 20_000L
        for (err in listOf(-240L, -120L, -61L, 61L, 120L, 240L)) {
            for (stale in listOf(0L, 150L, 400L)) {
                val target = MixEngine.beatSeekMs(heard - stale, heard, err, beatMs)
                assertTrue("err $err, reported $stale ms stale: moved to $target, $heard already heard", target >= heard)
                // Lined up all the same: where a move back by err would have put the beats.
                assertEquals("err $err: in line with the ending song's beats", 0.0, offBeat(target, heard - err, beatMs), 1.0)
                assertTrue("err $err: at most a beat on", target - heard <= beatMs)
            }
        }
    }

    @Test fun aTailBehindIsntCrossedOverToInATempoMatchedBlend() {
        // Crossing over to a tail 45–250 ms behind plays that much of the ending song again (a doubling): with the beats
        // matched, the blend is put off and the tail set right instead (or there's none).
        for (d in listOf(-60L, -120L, -250L)) assertTrue("tail $d ms behind", MixEngine.outOfStep(d, tailPlaying = true, beats = true))
        // Ahead, crossing over skips a moment rather than repeating one; and a little apart either way is fine.
        for (d in listOf(-40L, 0L, 40L, 120L, 250L)) assertTrue("tail $d ms off", !MixEngine.outOfStep(d, tailPlaying = true, beats = true))
        // A plain crossfade (no beats) keeps its slow cross-over, as before.
        assertTrue(!MixEngine.outOfStep(-120L, tailPlaying = true, beats = false))
        assertTrue(MixEngine.outOfStep(null, tailPlaying = true, beats = true))
    }

    @Test fun aSongPlayedThroughABlendPlaysEachMomentOnce() {
        // The next song as it plays (10 ms steps at the blend's tempo), its beats checked every window and set right by a
        // move: every moment of it is heard at most once.
        val beatMs = 476.0
        var pos = 0L
        val heard = ArrayList<LongRange>()
        var from = 0L
        val errs = ArrayDeque(listOf(180L, -90L, 210L, 75L))
        for (step in 1..400) {
            pos += 10
            if (step % 80 == 0 && errs.isNotEmpty()) {
                heard += from until pos
                pos = MixEngine.beatSeekMs(pos - 200, pos, errs.removeFirst(), beatMs)
                from = pos
            }
        }
        heard += from until pos
        for (i in 1 until heard.size) assertTrue("${heard[i]} plays again some of ${heard[i - 1]}", heard[i].first >= heard[i - 1].last + 1)
    }
}

package com.prism.music

import com.prism.music.data.BestPart
import com.prism.music.data.Heatmap
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.playback.Tempo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding a song's best part from YouTube's "Most replayed" curve, or its chorus. */
class BestPartTest {
    private val seg = 2_000L // 100 segments of 2 s: a 200 s song

    /** A curve that's hot at the very start (everyone starts there) and over [hot] segments. */
    private fun curve(hot: IntRange, base: Float = 0.3f): Heatmap =
        Heatmap(seg, List(100) { i -> when { i == 0 -> 1f; i in hot -> 0.9f; else -> base } })

    @Test
    fun peakStartsWhereTheHeatRises() {
        val start = BestPart.peakMs(curve(40..48), 200_000)
        assertEquals(40 * seg, start)
    }

    @Test
    fun theOpeningIsNeverTheBestPart() {
        // Hot only at 0 s (as every curve is): nothing else stands out, so there's no best part.
        assertNull(BestPart.peakMs(Heatmap(seg, List(100) { if (it == 0) 1f else 0.3f }), 200_000))
    }

    @Test
    fun aFlatCurveHasNoBestPart() {
        assertNull(BestPart.peakMs(Heatmap(seg, List(100) { 0.5f + (it % 3) * 0.01f }), 200_000))
    }

    @Test
    fun nothingInTheLastThirtySeconds() {
        // The only hot stretch is in the outro: too late to skip to.
        assertNull(BestPart.peakMs(curve(90..99), 200_000))
    }

    @Test
    fun aWholeHotChorusBeatsOneHotMoment() {
        // A lone spike at 60 (one segment) and a sustained chorus from 30 to 37.
        val values = List(100) { i -> when { i == 0 -> 1f; i == 60 -> 1f; i in 30..37 -> 0.8f; else -> 0.2f } }
        val start = BestPart.peakMs(Heatmap(seg, values), 200_000)!!
        assertTrue("got ${start / seg}", start / seg in 29..31)
    }

    @Test
    fun parsesYouTubesHeatMarkers() {
        val markers = (0 until 100).joinToString(",") { i ->
            """{"startMillis":"${i * 3600}","durationMillis":"3600","intensityScoreNormalized":${if (i == 50) 1 else 0.25}}"""
        }
        val res = Json.parseToJsonElement(
            """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{"macroMarkersListEntity":
               {"markersList":{"markerType":"MARKER_TYPE_HEATMAP","markers":[$markers]}}}}]}}}""",
        ) as JsonObject
        val heat = BestPart.parse(res)!!
        assertEquals(3_600L, heat.segmentMs)
        assertEquals(100, heat.intensity.size)
        assertEquals(1f, heat.intensity[50], 0f)
        // And back out of the database.
        assertEquals(heat.intensity, Heatmap.decode(heat.segmentMs, heat.encode())!!.intensity.map { (it * 1000).toInt() / 1000f })
    }

    @Test
    fun noMarkersNoHeatmap() {
        assertNull(BestPart.parse(Json.parseToJsonElement("""{"contents":{}}""") as JsonObject))
    }

    @Test
    fun chorusIsTheMostRepeatedLyric() {
        fun l(t: Long, text: String) = LyricLine(t, text)
        val lines = listOf(
            l(2_000, "Intro line here"),
            l(10_000, "First verse goes"), l(14_000, "Another verse line"),
            l(30_000, "This is the chorus"), l(34_000, "Sing it all together"),
            l(50_000, "Second verse now"),
            l(70_000, "This is the chorus"), l(74_000, "Sing it all together"),
            l(100_000, "This is the chorus"), l(104_000, "Sing it all together"),
        )
        assertEquals(30_000L, BestPart.chorusMs(lines, 200_000))
    }

    @Test
    fun noRepeatsNoChorus() {
        val lines = (1..8).map { LyricLine(it * 10_000L, "Line number $it is different") }
        assertNull(BestPart.chorusMs(lines, 200_000))
    }

    @Test
    fun snapsToALyricLineNearby() {
        assertEquals(41_750L, BestPart.snap(43_000, listOf(20_000, 42_000, 50_000), null))
        // A line just after the peak is taken too (the chorus often starts a beat late on the curve).
        assertEquals(43_750L, BestPart.snap(43_000, listOf(20_000, 44_000), null))
    }

    @Test
    fun snapsToABarWithoutLyrics() {
        // 120 BPM, first beat at 500 ms: bars every 2 s, so 43 s goes back to 42.5 s.
        val tempo = Tempo(bpm = 120.0, confidence = 5.0, firstSoundMs = 0, lastSoundMs = 200_000, firstBeatMs = 500, outroBeatMs = 0)
        assertEquals(42_500L, BestPart.snap(43_000, emptyList(), tempo))
        assertEquals(42_000L, BestPart.snap(43_000, emptyList(), null))
    }

    @Test
    fun shortSongsHaveNoBestPart() {
        assertNull(BestPart.peakMs(Heatmap(500, List(100) { if (it in 40..50) 1f else 0.2f }), 50_000))
        assertNotNull(BestPart.peakMs(curve(40..48), 200_000))
    }
}

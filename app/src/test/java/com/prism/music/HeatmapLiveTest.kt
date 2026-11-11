package com.prism.music

import com.prism.music.data.BestPart
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** YouTube's "Most replayed" curves, live: songs' own uploads have them, not only music videos. */
@Category(LiveNetwork::class)
class HeatmapLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun songsAndVideosHaveHeatmaps() = runBlocking {
        // Bohemian Rhapsody's video, "Alright" (single and album track), and a song upload.
        for (id in listOf("fJ9rUzIMcZQ", "JocAXINz-YE", "67vr-3kpX3Q", "XXrjqdtHVsI")) {
            val heat = ytm.heatmap(id)
            assertNotNull("no heatmap for $id", heat)
            assertEquals(100, heat!!.intensity.size)
            assertTrue(heat.segmentMs > 1_000)
            val best = BestPart.peakMs(heat)
            println("[live] $id: ${heat.durationMs / 1000} s, best part at ${best?.let { it / 1000 }} s")
        }
    }

    @Test
    fun bestPartsAreWhereListenersReplay() = runBlocking {
        // The curves fall as listeners drift off; the best part is a bump above that, not the hot opening.
        val bohemian = BestPart.peakMs(ytm.heatmap("fJ9rUzIMcZQ")!!)!!
        assertTrue("Bohemian Rhapsody's \"So you think you can stone me\" (~3:04), got ${bohemian / 1000} s", bohemian in 165_000..195_000)
        // "Alright": the single and the album track are separate uploads with their own curves; they agree.
        val single = BestPart.peakMs(ytm.heatmap("JocAXINz-YE")!!)!!
        val album = BestPart.peakMs(ytm.heatmap("67vr-3kpX3Q")!!)!!
        assertTrue("single ${single / 1000} s vs album ${album / 1000} s", kotlin.math.abs(single - album) <= 12_000)
        assertTrue("not the opening", single > 30_000)
    }
}

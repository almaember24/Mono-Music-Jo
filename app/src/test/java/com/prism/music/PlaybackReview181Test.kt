package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.PlayerExtras
import com.prism.music.data.prefs.AppSettings
import com.prism.music.playback.BlendIn
import com.prism.music.playback.MixEngine
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.1's playback code review: a play only noted as Prism's own on YouTube when YouTube took it, and a crossfade's
 * fade-in on the bar let go once the song starts over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackReview181Test {
    /** A YouTube Music whose history pings are answered with [code]; the URLs asked are kept in [pinged]. */
    private class Pings(private val code: Int) {
        val pinged = mutableListOf<String>()
        private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            pinged += req.url.toString()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body("".toResponseBody("text/plain".toMediaType())).build()
        }).build()
        val ytm = YouTubeMusic(InnerTube(http, { AppSettings(cookie = "SAPISID=abc; __Secure-3PAPISID=abc") }))
    }

    private fun extras(url: String?) = PlayerExtras(loudnessDb = null, trackingUrl = url, watchtimeUrl = null)

    @Test fun aPlayCountsAsSentOnlyWhenYouTubeTookIt() = runBlocking {
        val ok = Pings(204)
        assertTrue(ok.ytm.registerPlayback(extras("https://s.youtube.com/api/stats/playback?docid=x")))
        assertEquals(1, ok.pinged.size)
        // Refused: not Prism's own play on the account's history.
        assertFalse(Pings(403).ytm.registerPlayback(extras("https://s.youtube.com/api/stats/playback?docid=x")))
        // No tracking URL: nothing went out at all.
        val none = Pings(204)
        assertFalse(none.ytm.registerPlayback(extras(null)))
        assertEquals(0, none.pinged.size)
    }

    @Test fun aFadeInIsLetGoOnceTheSongStartsOver() {
        // Came in from its start over 6 s.
        val fromStart = BlendIn("a", inMs = 0, lengthMs = 6_000)
        assertFalse("playing on after the blend", MixEngine.wentBack(fromStart, positionMs = 30_000, furthestMs = 30_000))
        assertFalse("readings lag a little", MixEngine.wentBack(fromStart, positionMs = 29_500, furthestMs = 30_000))
        assertTrue("repeat one / Previous: back at the top", MixEngine.wentBack(fromStart, positionMs = 400, furthestMs = 180_000))
        assertTrue("looked at a moment after it started over", MixEngine.wentBack(fromStart, positionMs = 7_500, furthestMs = 180_000))
        assertFalse("taken back, but well after the fade-in", MixEngine.wentBack(fromStart, positionMs = 60_000, furthestMs = 180_000))
        // A blend brought in early (the ending song went quiet) ends short of the fade-in's end, without going back.
        assertFalse(MixEngine.wentBack(fromStart, positionMs = 3_000, furthestMs = 3_000))
        // Came in 8 s in (its silent start skipped): anywhere before that is from before the blend.
        val skipped = BlendIn("b", inMs = 8_000, lengthMs = 6_000)
        assertTrue(MixEngine.wentBack(skipped, positionMs = 0, furthestMs = 0))
        assertFalse(MixEngine.wentBack(skipped, positionMs = 20_000, furthestMs = 20_000))
    }
}

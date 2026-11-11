package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.PlayerExtras
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #377 (1.8.3): plays reached the history of the Google account's own channel but never a brand channel's. The history
 * ping now names the brand channel in use (X-Goog-PageId), and only then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BrandHistoryTest {
    private fun pingHeaders(settings: AppSettings): Headers? {
        var sent: Headers? = null
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            sent = req.headers
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("fake")
                .body("".toResponseBody("text/plain".toMediaType())).build()
        }).build()
        val ytm = YouTubeMusic(InnerTube(http, { settings }))
        runBlocking { ytm.registerPlayback(PlayerExtras(loudnessDb = null, trackingUrl = "https://s.youtube.com/api/stats/playback?docid=x", watchtimeUrl = null)) }
        return sent
    }

    private val cookie = "SAPISID=abc; __Secure-3PAPISID=abc"

    @Test fun aBrandChannelsPlayNamesTheChannel() {
        assertEquals("brand123", pingHeaders(AppSettings(cookie = cookie, dataSyncId = "brand123||user456"))?.get("X-Goog-PageId"))
    }

    @Test fun theAccountsOwnChannelAndSignedOutDont() {
        assertNull(pingHeaders(AppSettings(cookie = cookie, dataSyncId = "user456||"))?.get("X-Goog-PageId"))
        assertNull(pingHeaders(AppSettings(cookie = cookie, dataSyncId = "user456"))?.get("X-Goog-PageId"))
        assertNull(pingHeaders(AppSettings(dataSyncId = "brand123||user456"))?.get("X-Goog-PageId"))
    }
}

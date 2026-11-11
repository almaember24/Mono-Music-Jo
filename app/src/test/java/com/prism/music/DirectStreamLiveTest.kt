package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.stream.YouTubeStreamInterceptor
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * The direct stream lookup ([InnerTube.visionOsPlayer]): one request answers with plain stream URLs (no signature, no
 * "n"), and googlevideo serves them past the first megabyte through Prism's own stream path. Live against YouTube.
 */
@Category(LiveNetwork::class)
class DirectStreamLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val innerTube = InnerTube(http, { AppSettings() })
    private val streamHttp = http.newBuilder().addInterceptor(YouTubeStreamInterceptor(http)).build()

    @Test
    fun songsResolveInOneRequestAndStreamWhole() {
        // Bohemian Rhapsody (Queen), Mr. Brightside (The Killers).
        for (id in listOf("fJ9rUzIMcZQ", "gGdGFtwCNBE")) {
            val started = System.nanoTime()
            val res = innerTube.visionOsPlayer(id)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(res.str("playabilityStatus", "reason"), "OK", res.str("playabilityStatus", "status"))
            val audio = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.str("mimeType")?.startsWith("audio/") == true }
            assertTrue("has audio formats", audio.isNotEmpty())
            val best = audio.maxBy { it.str("bitrate")?.toIntOrNull() ?: 0 }
            val url = best.str("url")
            assertNotNull("plain URL, no signatureCipher", url)
            assertFalse("no throttling parameter to solve", Regex("[?&]n=").containsMatchIn(url!!))
            val clen = best.str("contentLength")!!.toLong()
            println("[direct] $id answered in $ms ms, itag ${best.str("itag")}, $clen bytes")

            // Bytes 3–4 MB, as ExoPlayer asks (a Range header), through the same interceptor playback uses.
            val from = minOf(3L shl 20, clen - (1L shl 20))
            val req = Request.Builder().url(url).header("Range", "bytes=$from-${from + (1L shl 20) - 1}").build()
            streamHttp.newCall(req).execute().use { r ->
                val n = r.body.bytes().size
                println("[direct] $id range from $from: HTTP ${r.code}, $n bytes")
                assertTrue("HTTP ${r.code}", r.code == 200 || r.code == 206)
                assertEquals(1 shl 20, n)
            }
        }
    }
}

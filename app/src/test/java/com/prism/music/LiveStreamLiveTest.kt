package com.prism.music

import com.prism.music.data.stream.DESKTOP_UA
import com.prism.music.data.stream.NewPipeDownloader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Live streams ("Listen together" stations, 24/7 radios) play from their HLS manifest: the manifest,
 * a variant playlist and a segment must all load as plain GETs. Live against YouTube.
 */
@Category(LiveNetwork::class)
class LiveStreamLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    private fun get(url: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()).execute().use { r -> r.code to r.body.string() }

    @Test
    fun liveStreamPlaysFromHls() {
        NewPipe.init(NewPipeDownloader(http), Localization("en", "US"))
        // Whatever 24/7 lofi radio is live right now.
        val search = ServiceList.YouTube.getSearchExtractor("lofi hip hop radio beats to relax study to").also { it.fetchPage() }
        val items = search.initialPage.items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        items.take(8).forEach { println("[live] candidate ${it.streamType} ${it.duration}s ${it.name.take(50)} ${it.url}") }
        val liveUrl = items.first { it.streamType == StreamType.LIVE_STREAM || it.duration <= 0 }.url
        val info = StreamInfo.getInfo(ServiceList.YouTube, liveUrl)
        println("[live] type=${info.streamType} hls=${info.hlsUrl?.take(80)} progressive audio=${info.audioStreams.size}")
        assertTrue(info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM)
        assertTrue("has an HLS manifest", !info.hlsUrl.isNullOrBlank())

        val (code, master) = get(info.hlsUrl)
        assertEquals(200, code)
        // BestLiveAudioParserFactory relies on this: an AAC-LC (mp4a.40.2, 128 kbps) audio rendition beside HE-AAC.
        assertTrue(master.lines().any { it.startsWith("#EXT-X-MEDIA") && "TYPE=AUDIO" in it && "/itag/234/" in it })
        val variant = master.lines().first { it.startsWith("http") }
        val (vcode, playlist) = get(variant)
        assertEquals(200, vcode)
        val segment = playlist.lines().last { it.startsWith("http") }
        val bytes = http.newCall(Request.Builder().url(segment).header("User-Agent", DESKTOP_UA).build()).execute().use { r ->
            println("[live] segment HTTP ${r.code}")
            assertEquals(200, r.code)
            r.body.bytes().size
        }
        println("[live] master ${master.length} chars, variant ${playlist.lines().count { it.startsWith("http") }} segments, last segment $bytes bytes")
        assertTrue(bytes > 10_000)
    }
}

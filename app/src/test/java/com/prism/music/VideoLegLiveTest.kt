package com.prism.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.stream.NewPipeDownloader
import com.prism.music.data.stream.YouTubeStreamInterceptor
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * GitHub #411: "Let's Go" by Stuck in the Sound as its music video (52Gg9CqhbP8) stopped at ~1:08 with "Skipping atom
 * with length > 2147483647", and again at once on every retry with fresh links. Live: its streams as Prism picks them
 * (the direct visionOS answer, then NewPipe's), read through Prism's own stream path and Media3's extractors, from the
 * start past 1:08 and after a seek to 1:08.
 */
@Category(LiveNetwork::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoLegLiveTest {
    private val id = "52Gg9CqhbP8"
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().addInterceptor(YouTubeStreamInterceptor(http)).build()
    private val innerTube = InnerTube(http, { AppSettings() })

    private class F(val itag: Int, val mime: String, val codec: String?, val height: Int, val fps: Int, val clen: Long, val url: String) {
        val lmt get() = Uri.parse(url).getQueryParameter("lmt")
        val xtags get() = Uri.parse(url).getQueryParameter("xtags")
        val client get() = Uri.parse(url).getQueryParameter("c")
        override fun toString() = "itag $itag $mime $codec ${height}p$fps clen $clen lmt $lmt xtags $xtags c $client"
    }

    private fun visionOs(): List<F> {
        val res = innerTube.visionOsPlayer(id)
        println("[411] visionOS: ${res.str("playabilityStatus", "status")}")
        return res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val full = o.str("mimeType") ?: return@mapNotNull null
            F(
                o.str("itag")!!.toInt(), full.substringBefore(";"), Regex("codecs=\"([^\"]+)\"").find(full)?.groupValues?.get(1),
                o.str("height")?.toIntOrNull() ?: 0, o.str("fps")?.toIntOrNull() ?: 0, o.str("contentLength")?.toLongOrNull() ?: -1,
                o.str("url") ?: return@mapNotNull null,
            )
        }
    }

    private fun newPipe(): List<F> {
        NewPipe.init(NewPipeDownloader(http), Localization("en", "US"))
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.map { a ->
            F(a.itag, a.format?.mimeType ?: "audio/*", a.codec, 0, 0, a.itagItem?.contentLength ?: -1, a.content)
        }
        val video = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.map { v ->
            F(v.itag, v.format?.mimeType ?: "video/*", v.codec, v.height, v.fps, v.itagItem?.contentLength ?: -1, v.content)
        }
        return audio + video
    }

    /** StreamResolver.pickVideo at the default 1080p. */
    private fun pickVideo(fs: List<F>): F? {
        val usable = fs.filter { it.mime.startsWith("video/") && it.height in 1..1080 }
        val avc = usable.filter { it.codec?.startsWith("avc") == true || it.mime == "video/mp4" }
        return avc.ifEmpty { usable }.maxWithOrNull(compareBy<F> { it.height }.thenBy { it.fps })
    }

    private class Sink : ExtractorOutput, TrackOutput {
        var lastUs = 0L
        var samples = 0
        var seekMap: SeekMap? = null
        private val scratch = ByteArray(64 * 1024)
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return C.RESULT_END_OF_INPUT; throw java.io.EOFException() }
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            lastUs = maxOf(lastUs, timeUs); samples++
        }
    }

    /**
     * Reads [f] as the player does: from the start to [untilUs], then (when [seekUs] is given) from a seek there for a few
     * seconds. Null when it read fine, else what failed.
     */
    private fun read(f: F, untilUs: Long, seekUs: Long? = null): Throwable? {
        val extractor: Extractor = if (f.mime.endsWith("webm")) MatroskaExtractor() else FragmentedMp4Extractor()
        val sink = Sink()
        extractor.init(sink)
        val src = OkHttpDataSource.Factory(streamHttp).createDataSource()
        var position = 0L
        var stopAt = untilUs
        var seekDone = seekUs == null
        try {
            while (true) {
                val len = src.open(DataSpec.Builder().setUri(Uri.parse(f.url)).setPosition(position).build())
                val input = DefaultExtractorInput(src, position, if (len == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + len)
                val holder = PositionHolder()
                var result = Extractor.RESULT_CONTINUE
                while (result == Extractor.RESULT_CONTINUE && sink.lastUs < stopAt) result = extractor.read(input, holder)
                src.close()
                if (result == Extractor.RESULT_SEEK) { position = holder.position; continue }
                if (result == Extractor.RESULT_END_OF_INPUT) { println("[411]   ${f.itag}: end of input at ${sink.lastUs / 1000} ms"); break }
                if (!seekDone) {
                    // As the player seeks: the seek map's point for the time, and the extractor told so.
                    val sp = sink.seekMap!!.getSeekPoints(seekUs!!).first
                    println("[411]   ${f.itag}: read to ${sink.lastUs / 1000} ms; seeking to ${seekUs / 1000} ms -> byte ${sp.position} (${sp.timeUs / 1000} ms)")
                    extractor.seek(sp.position, sp.timeUs)
                    position = sp.position
                    stopAt = seekUs + 5_000_000
                    sink.lastUs = 0
                    seekDone = true
                    continue
                }
                break
            }
            println("[411]   ${f.itag}: fine to ${sink.lastUs / 1000} ms, ${sink.samples} samples")
            return null
        } catch (e: Throwable) {
            println("[411]   ${f.itag}: FAILED at ${sink.lastUs / 1000} ms: $e")
            runCatching { src.close() }
            return e
        }
    }

    @Test fun itsStreamsReadPastOneMinuteEight() {
        val failures = mutableListOf<String>()
        for ((via, fs) in listOf("visionOS" to visionOs(), "NewPipe" to newPipe())) {
            fs.forEach { println("[411] $via: $it") }
            val video = pickVideo(fs)
            val picks = listOfNotNull(video, fs.firstOrNull { it.itag == 140 }, fs.firstOrNull { it.itag == 251 })
            for (f in picks) {
                println("[411] $via reading $f")
                read(f, untilUs = 75_000_000, seekUs = 68_000_000)?.let { failures += "$via itag ${f.itag}: $it" }
            }
        }
        assertTrue("unreadable: $failures", failures.isEmpty())
    }
}

package com.prism.music

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.prism.music.data.stream.ForgetOnForbidden
import com.prism.music.data.stream.YouTubeStreamInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * GitHub #408 ("Error 403" on many songs, downloads failing too): googlevideo let the first megabyte through and refused a
 * later one. That came out as a plain IOException, so the player saw a dropped connection (no HTTP code) and asked the
 * same client again instead of another one, and a download kept its refused link through all of Media3's retries. A
 * refused later chunk now fails the way a refused first one does: as an HTTP 403.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamChunkRefusedTest {
    private val chunk = 1024L
    private val clen = 4 * chunk
    private val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-test&itag=251&clen=$clen&c=WEB_REMIX"

    /** googlevideo: the first chunk comes, everything from [refuseFrom] on is a 403. */
    private fun googlevideo(refuseFrom: Long) = Interceptor { chain ->
        val req = chain.request()
        val range = req.url.queryParameter("range") ?: "0-"
        val from = range.substringBefore('-').toLong()
        val to = range.substringAfter('-').toLongOrNull() ?: (clen - 1)
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
        if (from >= refuseFrom) b.code(403).message("Forbidden").body(ByteArray(0).toResponseBody(null)).build()
        else b.code(200).message("OK").body(ByteArray((to - from + 1).toInt()) { 7 }.toResponseBody(null)).build()
    }

    private fun source(): OkHttpDataSource {
        val chunkClient = OkHttpClient.Builder().addInterceptor(googlevideo(refuseFrom = chunk)).build()
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(chunkClient, chunk))
            .addInterceptor(googlevideo(refuseFrom = chunk)).build()
        return OkHttpDataSource.Factory(http).createDataSource()
    }

    /** Reads [s] to its end; the failure it ends in. */
    private fun readToFailure(open: () -> Unit, read: (ByteArray) -> Int): IOException {
        open()
        val buf = ByteArray(4096)
        try {
            while (read(buf) != androidx.media3.common.C.RESULT_END_OF_INPUT) Unit
        } catch (e: IOException) {
            return e
        }
        fail("the refused chunk was read as if it came")
        error("unreachable")
    }

    /** The code PlaybackService's retry finds in the failure's causes (its `httpCode`). */
    private fun httpCode(e: Throwable): Int? = generateSequence(e) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    @Test fun aRefusedLaterChunkFailsAsAn403() {
        val s = source()
        val e = readToFailure({ assertEquals(clen, s.open(DataSpec.Builder().setUri(Uri.parse(url)).build())) }) { s.read(it, 0, it.size) }
        s.close()
        assertEquals("the player's retry must see the 403 (refused = true), not a dropped connection: $e", 403, httpCode(e))
    }

    @Test fun aDownloadForgetsTheLinkWhenALaterChunkIsRefused() {
        val forgotten = mutableListOf<Pair<String, Int>>()
        val s = ForgetOnForbidden(source()) { key, code -> forgotten += key to code }
        readToFailure({ s.open(DataSpec.Builder().setUri(Uri.parse(url)).setKey("video:abc").build()) }) { s.read(it, 0, it.size) }
        s.close()
        assertEquals(listOf("video:abc" to 403), forgotten)
    }

    @Test fun aRefusedFirstChunkStillFailsAsAn403FromOpen() {
        val chunkClient = OkHttpClient.Builder().addInterceptor(googlevideo(refuseFrom = 0)).build()
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(chunkClient, chunk))
            .addInterceptor(googlevideo(refuseFrom = 0)).build()
        val forgotten = mutableListOf<Pair<String, Int>>()
        val s = ForgetOnForbidden(OkHttpDataSource.Factory(http).createDataSource()) { key, code -> forgotten += key to code }
        try {
            s.open(DataSpec.Builder().setUri(Uri.parse(url)).setKey("abc").build())
            fail("opened a refused link")
        } catch (e: IOException) {
            assertEquals(403, httpCode(e))
        }
        assertTrue(forgotten == listOf("abc" to 403))
    }
}

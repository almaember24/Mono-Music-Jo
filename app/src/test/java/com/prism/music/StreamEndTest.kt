package com.prism.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.prism.music.data.stream.YouTubeStreamInterceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #206 and Reddit ("Response code: 400" after every song, the next one slow to start): the player asked for a stream
 * from its very end, googlevideo answers that with a 400 (checked live 2026-10-30: `range=<clen>-` → 400), and the
 * player took it as an error. Prism now answers it itself, and the player's own data source reads that as the end.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: Prism's own started its whole container (Room too) for nothing, and Room's late work then failed
// the next Compose test in the same run (SwipeToQueueTest, 1.7.9.3).
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamEndTest {
    private val clen = 3_559_271L
    private val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-test&itag=251&clen=$clen&c=VISIONOS"

    @Test fun readingFromTheStreamsEndIsTheEndNotAnError() {
        val bare = OkHttpClient()
        // Nothing may reach the network for it.
        val http = bare.newBuilder().addInterceptor(YouTubeStreamInterceptor(bare))
            .addInterceptor { error("asked googlevideo for ${it.request().url.queryParameter("range")}") }.build()
        val source = OkHttpDataSource.Factory(http).createDataSource()
        val opened = source.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(clen).build())
        assertEquals(0L, opened)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(16), 0, 16))
        source.close()
    }
}

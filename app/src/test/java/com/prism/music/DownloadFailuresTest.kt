package com.prism.music

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.prism.music.download.DownloadFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException

/** GitHub #98: a failed download says why, in words. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class DownloadFailuresTest {
    private fun http(code: Int) = IOException(
        "wrapped",
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(Uri.parse("https://example.com/a")), ByteArray(0)),
    )

    @Test fun httpCodesAreNamed() {
        assertTrue(DownloadFailures.describe(http(403)).contains("403"))
        assertTrue(DownloadFailures.describe(http(404)).startsWith("YouTube no longer has"))
        assertTrue(DownloadFailures.describe(http(429)).contains("slow down"))
        assertTrue(DownloadFailures.describe(http(503)).contains("trouble"))
    }

    @Test fun offlineAndSpace() {
        assertEquals("The phone was offline", DownloadFailures.describe(IOException(UnknownHostException("rr1.googlevideo.com"))))
        assertEquals("The phone ran out of space", DownloadFailures.describe(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test fun nothingToGoOn() {
        assertEquals("It stopped without saying why", DownloadFailures.describe(null))
        assertEquals("The connection dropped part-way", DownloadFailures.describe(IOException()))
    }
}

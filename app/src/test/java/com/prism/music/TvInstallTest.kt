package com.prism.music

import com.prism.music.connect.TvInstall
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/** Prism on a TV (1.8.0): the address hands over the APK itself, whole or in parts, and nothing else. */
class TvInstallTest {
    private val apk = File.createTempFile("prism", ".apk").apply {
        writeBytes(ByteArray(300_000) { (it * 31 % 251).toByte() })
        deleteOnExit()
    }
    private val share = TvInstall { apk }

    @After fun stop() = share.stop()

    private fun port(): Int { assertTrue(share.start()); return share.port }

    private fun get(port: Int, path: String = "/", range: String? = null): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply { range?.let { setRequestProperty("Range", it) } }

    @Test
    fun theAddressIsTheApk() {
        val c = get(port())
        assertEquals(200, c.responseCode)
        assertEquals(TvInstall.MIME, c.contentType)
        assertTrue(c.getHeaderField("Content-Disposition").contains("Prism.apk"))
        assertArrayEquals(apk.readBytes(), c.inputStream.readBytes())
        assertEquals(1, share.sent.value)
    }

    @Test
    fun aDownloadCanResume() {
        val c = get(port(), "/Prism.apk", "bytes=100000-")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100000-299999/300000", c.getHeaderField("Content-Range"))
        assertArrayEquals(apk.readBytes().copyOfRange(100_000, 300_000), c.inputStream.readBytes())
        assertEquals("the last part finishes a copy", 1, share.sent.value)
    }

    @Test
    fun nothingElseIsServed() {
        val port = port()
        assertEquals(404, get(port, "/settings").responseCode)
        // A page elsewhere that pointed its own name at this phone is refused.
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n".toByteArray())
            assertTrue(s.getInputStream().bufferedReader().readLine().contains("403"))
        }
        assertEquals(0, share.sent.value)
    }

    @Test
    fun aCopyInPartsCantBeShared() {
        val none = TvInstall { null }
        assertFalse(none.start())
        assertFalse(none.running)
    }
}

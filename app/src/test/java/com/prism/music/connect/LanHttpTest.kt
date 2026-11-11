package com.prism.music.connect

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** #239: UPnP's plain HTTP on a socket of Prism's own, because OkHttp on a phone refuses it. Answers as devices write them. */
class LanHttpTest {
    private val client = OkHttpClient.Builder().addInterceptor(LanHttp).callTimeout(3, TimeUnit.SECONDS).build()

    /** One connection: what was asked comes back to [asked], [answer] goes out, and it closes. */
    private fun <T> withDevice(answer: ByteArray, asked: (String) -> Unit = {}, block: (Int) -> T): T =
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val t = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val input = s.getInputStream()
                        val head = ByteArrayOutputStream()
                        while (!head.toString("UTF-8").endsWith("\r\n\r\n")) head.write(input.read())
                        val text = head.toString("UTF-8")
                        val length = Regex("Content-Length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = ByteArray(length).also { b -> var n = 0; while (n < length) n += input.read(b, n, length - n) }
                        asked(text + String(body))
                        s.getOutputStream().write(answer)
                        s.getOutputStream().flush()
                    }
                }
            }
            block(server.localPort).also { t.join(2_000) }
        }

    private fun get(port: Int) = client.newCall(Request.Builder().url("http://127.0.0.1:$port/xml/d.xml?a=1").build()).execute()

    @Test fun aPlainAnswerWithItsLength() = withDevice("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".toByteArray()) { port ->
        get(port).use { r ->
            assertEquals(200, r.code)
            assertEquals("OK", r.message)
            assertEquals(Protocol.HTTP_1_1, r.protocol)
            assertEquals("text/xml", r.body.contentType().toString())
            assertEquals("hello", r.body.string())
        }
    }

    @Test fun aChunkedAnswer() = withDevice("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;x=1\r\nhello\r\n7\r\n, world\r\n0\r\n\r\n".toByteArray()) { port ->
        get(port).use { assertEquals("hello, world", it.body.string()) }
    }

    @Test fun anAnswerThatRunsToTheEndOfTheConnection() = withDevice("HTTP/1.0 200 OK\n\nhello".toByteArray()) { port ->
        get(port).use { assertEquals("hello", it.body.string()) }
    }

    @Test fun continueIsPassedOver() = withDevice("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()) { port ->
        get(port).use { assertEquals(200, it.code); assertEquals("ok", it.body.string()) }
    }

    /** A device's non-ASCII header ("Kök") or odd line doesn't lose its answer. */
    @Test fun oddHeadersAreSurvived() = withDevice("HTTP/1.1 200 OK\r\nX-Room: Kök\r\nno colon here\r\n: nameless\r\nContent-Length: 2\r\n\r\nok".toByteArray(Charsets.ISO_8859_1)) { port ->
        get(port).use { assertEquals("ok", it.body.string()) }
    }

    /** The fault's body is what says which UPnP error it was: a 500 has to arrive whole. */
    @Test fun anErrorKeepsItsBody() = withDevice("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 5\r\n\r\nfault".toByteArray()) { port ->
        get(port).use { r -> assertEquals(500, r.code); assertFalse(r.isSuccessful); assertEquals("fault", r.body.string()) }
    }

    @Test fun theRequestGoesOutWhole() {
        var seen = ""
        withDevice("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(), asked = { seen = it }) { port ->
            val body = "<Kök/>".toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/MediaRenderer/AVTransport/Control").post(body).header("SOAPACTION", "\"x#Play\"").build()).execute().use { }
            assertTrue(seen, seen.startsWith("POST /MediaRenderer/AVTransport/Control HTTP/1.1\r\n"))
            assertTrue(seen, seen.contains("Host: 127.0.0.1:$port\r\n"))
            assertTrue(seen, seen.contains("SOAPACTION: \"x#Play\"\r\n"))
            assertTrue(seen, seen.contains("Content-Type: text/xml"))
            assertTrue(seen, seen.contains("Content-Length: ${"<Kök/>".toByteArray().size}\r\n"))
            assertTrue(seen, seen.contains("Connection: close\r\n"))
            assertTrue(seen, seen.endsWith("<Kök/>"))
        }
    }

    @Test fun aQueryStaysOnTheAddress() {
        var seen = ""
        withDevice("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(), asked = { seen = it }) { port ->
            get(port).use { assertEquals(204, it.code) }
            assertTrue(seen, seen.startsWith("GET /xml/d.xml?a=1 HTTP/1.1\r\n"))
        }
    }

    @Test(expected = IOException::class)
    fun anAnswerCutShortIsAnError() = withDevice("HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\nhello".toByteArray()) { port ->
        get(port).use { it.body.string() }; Unit
    }

    @Test(expected = IOException::class)
    fun somethingElseOnThePortIsAnError() = withDevice("SSH-2.0-OpenSSH\r\n\r\n".toByteArray()) { port ->
        get(port).use { it.body.string() }; Unit
    }

    @Test(expected = IOException::class)
    fun anEndlessAnswerIsRefused() = withDevice("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\nhello".toByteArray()) { port ->
        get(port).use { it.body.string() }; Unit
    }

    /** A device that goes quiet doesn't hold the call past its limit. */
    @Test fun aSilentDeviceTimesOut() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val quick = client.newBuilder().callTimeout(800, TimeUnit.MILLISECONDS).build()
            val started = System.currentTimeMillis()
            val failed = runCatching { quick.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute() }.exceptionOrNull()
            assertTrue("$failed", failed is IOException)
            assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
        }
    }

    /**
     * A byte every so often resets the socket's own timeout each time, so only the call's limit can end this. One that trickles
     * its first line or its headers used to hold the thread for as long as it cared to.
     */
    private fun trickling(first: String, assertInTime: (Int) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val t = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        s.getOutputStream().apply { write(first.toByteArray()); flush() }
                        repeat(100) { write(s) }
                    }
                }
            }
            assertInTime(server.localPort)
            t.interrupt()
        }
    }

    private fun write(s: java.net.Socket) {
        Thread.sleep(100)
        s.getOutputStream().apply { write('x'.code); flush() }
    }

    private fun refusedInTime(port: Int) {
        val quick = client.newBuilder().callTimeout(800, TimeUnit.MILLISECONDS).build()
        val started = System.currentTimeMillis()
        val failed = runCatching { quick.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute() }.exceptionOrNull()
        val took = System.currentTimeMillis() - started
        assertTrue("$failed", failed is IOException)
        assertTrue("held for $took ms past a limit of 800", took < 3_000)
    }

    @Test fun aDeviceTricklingItsFirstLineDoesntHoldTheCall() = trickling("HTTP/1.1 200 ", ::refusedInTime)

    @Test fun aDeviceTricklingItsHeadersDoesntHoldTheCall() = trickling("HTTP/1.1 200 OK\r\nX-Slow: ", ::refusedInTime)

    @Test fun aDeviceTricklingAChunkSizeDoesntHoldTheCall() = trickling("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", ::refusedInTime)

    /** Nothing leaves the local network this way: the rest of Prism keeps OkHttp's own rules (https, and no cleartext). */
    @Test fun onlyLocalAddressesComeThisWay() {
        val passed = ArrayList<String>()
        val rest = Interceptor { chain ->
            passed += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("rest".toResponseBody()).build()
        }
        val c = OkHttpClient.Builder().addInterceptor(LanHttp).addInterceptor(rest).build()
        for (url in listOf("https://music.youtube.com/", "http://example.com/x", "http://8.8.8.8/x", "https://192.168.1.2/x")) {
            c.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(url, "rest", it.body.string()) }
        }
        assertEquals(4, passed.size)
    }
}

package com.prism.music.connect

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * Prism Connect against a pretend renderer on this PC ([FakeRenderer]): found over SSDP, described, sent a song from
 * Prism's media server, played, paused, seeked (it re-fetches from the byte where the new time is, as TVs do) and
 * moved on gaplessly. The bytes it gets must be exactly the song's.
 */
class SimulatedRendererTest {
    private val http = OkHttpClient()

    /** A made-up song: frame i is (i, -i) (mod 2^15), so any byte can be checked against where it should be from. */
    private class Ramp(private var at: Long, private val total: Long) : PcmFrames {
        override fun read(): ShortArray? {
            if (at >= total) return null
            val n = minOf(777L, total - at).toInt()
            val out = ShortArray(n * 2)
            for (i in 0 until n) { val v = ((at + i) % 32768).toInt(); out[2 * i] = v.toShort(); out[2 * i + 1] = (-v).toShort() }
            at += n
            return out
        }
        override fun close() = Unit
    }

    private fun song(rate: Int, seconds: Int, wav: Boolean, shortBy: Long = 0) =
        PcmBody(rate, rate.toLong() * seconds, wav) { start -> Ramp(start, rate.toLong() * seconds - shortBy) }

    private fun expected(body: PcmBody, start: Long, end: Long): ByteArray {
        val all = ByteArrayOutputStream()
        body.write(0, body.length - 1, all)
        return all.toByteArray().copyOfRange(start.toInt(), end.toInt() + 1)
    }

    private fun sampleBytes(frame: Long, wav: Boolean): ByteArray {
        val v = (frame % 32768).toInt()
        val l = v.toShort(); val r = (-v).toShort()
        return if (wav) byteArrayOf(l.toByte(), (l.toInt() shr 8).toByte(), r.toByte(), (r.toInt() shr 8).toByte())
        else byteArrayOf((l.toInt() shr 8).toByte(), l.toByte(), (r.toInt() shr 8).toByte(), r.toByte())
    }

    @Test
    fun pcmRangesLineUp() {
        val wav = song(8000, 3, wav = true)
        assertEquals(44 + 8000L * 3 * 4, wav.length)
        val whole = ByteArrayOutputStream().also { wav.write(0, wav.length - 1, it) }.toByteArray()
        assertEquals(wav.length.toInt(), whole.size)
        assertEquals("RIFF", String(whole, 0, 4))
        assertEquals("WAVE", String(whole, 8, 4))
        assertArrayEquals(sampleBytes(0, true), whole.copyOfRange(44, 48))
        assertArrayEquals(sampleBytes(4321, true), whole.copyOfRange(44 + 4321 * 4, 48 + 4321 * 4))
        // Any range (inside the header, across it, mid-frame) is the same bytes as the whole has there.
        for ((a, b) in listOf(0L to 10L, 20L to 100L, 44L to 47L, 45L to 1000L, 4001L to 4002L, 50_003L to 96_043L, wav.length - 5 to wav.length - 1)) {
            val part = ByteArrayOutputStream().also { wav.write(a, b, it) }.toByteArray()
            assertArrayEquals("bytes $a-$b", whole.copyOfRange(a.toInt(), b.toInt() + 1), part)
        }
        // L16: no header, big-endian.
        val l16 = song(8000, 1, wav = false)
        assertEquals(32_000L, l16.length)
        assertEquals("audio/L16;rate=8000;channels=2", l16.mime)
        val part = ByteArrayOutputStream().also { l16.write(400, 403, it) }.toByteArray()
        assertArrayEquals(sampleBytes(100, false), part)
        // A decoder that runs out early: silence to the promised length.
        val short = song(8000, 1, wav = true, shortBy = 1000)
        val tail = ByteArrayOutputStream().also { short.write(short.length - 8, short.length - 1, it) }.toByteArray()
        assertArrayEquals(ByteArray(8), tail)
        assertEquals(short.length.toInt(), ByteArrayOutputStream().also { short.write(0, short.length - 1, it) }.size())
    }

    @Test
    fun rangeHeaders() {
        assertEquals(ByteRange.Whole, ByteRange.parse(null, 100))
        assertEquals(ByteRange.Part(0, 99), ByteRange.parse("bytes=0-", 100))
        assertEquals(ByteRange.Part(10, 19), ByteRange.parse("bytes=10-19", 100))
        assertEquals(ByteRange.Part(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange.Part(0, 99), ByteRange.parse("bytes=-1000", 100))
        assertEquals(ByteRange.Part(50, 99), ByteRange.parse("bytes=50-5000", 100))
        assertEquals(ByteRange.Part(0, 1), ByteRange.parse("bytes=0-1, 5-6", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=100-", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=20-10", 100))
        assertEquals(ByteRange.Whole, ByteRange.parse("items=0-1", 100))
    }

    @Test
    fun mediaServerAnswersLikeADlnaServer() {
        val server = MediaServer(0)
        assertTrue(server.start())
        try {
            val body = song(8000, 2, wav = true)
            val url = server.publish("song1.wav", body, "127.0.0.1")
            fun open(u: String, method: String = "GET", range: String? = null) = (URL(u).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                if (range != null) setRequestProperty("Range", range)
            }
            open(url, "HEAD").let { c ->
                assertEquals(200, c.responseCode)
                assertEquals(body.length.toString(), c.getHeaderField("Content-Length"))
                assertEquals("audio/wav", c.contentType)
                assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
                assertEquals("Streaming", c.getHeaderField("transferMode.dlna.org"))
                assertTrue(c.getHeaderField("contentFeatures.dlna.org").startsWith("DLNA.ORG_OP=01;DLNA.ORG_CI=1;"))
                assertEquals(-1, c.inputStream.read())
            }
            open(url, range = "bytes=1000-1999").let { c ->
                assertEquals(206, c.responseCode)
                assertEquals("bytes 1000-1999/${body.length}", c.getHeaderField("Content-Range"))
                assertArrayEquals(expected(body, 1000, 1999), c.inputStream.readBytes())
            }
            open(url).let { c -> assertEquals(200, c.responseCode); assertEquals(body.length.toInt(), c.inputStream.readBytes().size) }
            open(url, range = "bytes=${body.length}-").let { c -> assertEquals(416, c.responseCode); assertEquals("bytes */${body.length}", c.getHeaderField("Content-Range")) }
            open(url.replace("song1", "song2")).let { assertEquals(404, it.responseCode) }
            open(url, "POST").let { assertEquals(405, it.responseCode) }
            // A new session: the old link is gone.
            server.newSession()
            open(url).let { assertEquals(404, it.responseCode) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun findsControlsAndFeedsTheRenderer() = runBlocking {
        FakeRenderer().use { fake ->
            // Found by an SSDP search (sent straight to the pretend one: multicast doesn't loop back everywhere).
            val locations = mutableSetOf<String>()
            SsdpSearch.run(1_500, to = InetSocketAddress("127.0.0.1", fake.ssdpPort)) { m, _ -> m.location?.let { locations += it } }
            assertEquals(setOf(fake.location), locations)
            val r = Describe.fetch(http, fake.location)
            assertNotNull(r)
            r!!
            assertEquals("Test Renderer", r.name)
            val ctl = RendererControl(http, r)
            assertEquals(setOf("audio/wav", "audio/l16", "audio/mpeg"), ctl.sinkMimes())
            assertEquals(30, ctl.volume())
            ctl.setVolume(45)
            assertEquals(45, ctl.volume())
            assertEquals("NO_MEDIA_PRESENT", ctl.transport().state)

            val server = MediaServer(0)
            assertTrue(server.start())
            try {
                val (format, mime) = ProtocolInfo.choose(ctl.sinkMimes(), "audio/webm")
                assertEquals(CastFormat.WAV, format)
                val body = song(8000, 4, wav = true)
                val url = server.publish("a.wav", body, "127.0.0.1")
                val meta = Didl.song("a", "Song A", "Artist", "Album", null, url, ProtocolInfo.of(mime, true), 4_000, body.length)
                ctl.setUri(url, meta)
                assertEquals("STOPPED", ctl.transport().state)
                ctl.play()
                assertEquals("PLAYING", ctl.transport().state)
                // It reads the header, then the music from the start.
                waitFor { fake.fetches.size >= 2 }
                assertEquals("bytes=0-43", fake.fetches[0].range)
                assertArrayEquals(expected(body, 0, 43), fake.fetches[0].bytes)
                val fromStart = fake.fetches[1]
                assertEquals(206, fromStart.status)
                assertArrayEquals(expected(body, 44, body.length - 1), fromStart.bytes)

                Thread.sleep(1_200)
                val pos = ctl.position()
                assertEquals(url, pos.trackUri)
                assertEquals(4_000L, pos.durationMs)
                assertTrue("at ${pos.relMs}", pos.relMs!! in 1_000L..2_000L)

                ctl.pause()
                assertEquals("PAUSED_PLAYBACK", ctl.transport().state)
                // A seek to 2.5 s: the renderer asks for the bytes from 2.5 s on, which must be frame 20 000 onwards.
                ctl.seek(2_500)
                ctl.play()
                waitFor { fake.fetches.size >= 4 }
                val seeked = fake.fetches.last()
                assertEquals("bytes=${44 + 20_000 * 4}-", seeked.range)
                assertArrayEquals(sampleBytes(20_000, true), seeked.bytes.copyOfRange(0, 4))
                assertArrayEquals(expected(body, 44 + 20_000L * 4, body.length - 1), seeked.bytes)

                // Gapless: the next song is lined up and the renderer moves on to it by itself.
                val next = song(8000, 2, wav = true)
                val nextUrl = server.publish("b.wav", next, "127.0.0.1")
                ctl.setNextUri(nextUrl, Didl.song("b", "Song B", "Artist", null, null, nextUrl, ProtocolInfo.of(mime, true), 2_000, next.length))
                waitFor(4_000) { runBlocking { ctl.position().trackUri } == nextUrl }
                assertEquals("PLAYING", ctl.transport().state)
                waitFor(4_000) { runBlocking { ctl.transport().state } == "STOPPED" }
                ctl.stop()
                assertTrue(server.log.any { it.startsWith("GET b.wav") })
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun aRefusalComesBackAsItsErrorCode() = runBlocking {
        FakeRenderer(refuse = setOf("audio/wav"), gapless = false).use { fake ->
            val ctl = RendererControl(http, Describe.fetch(http, fake.location)!!)
            val e = runCatching { ctl.setUri("http://127.0.0.1:1/x.wav", Didl.song("x", "X", "", null, null, "http://127.0.0.1:1/x.wav", ProtocolInfo.of("audio/wav", true), 1000, 10)) }
                .exceptionOrNull()
            assertTrue(e is UpnpException && e.code == 714)
            val next = runCatching { ctl.setNextUri("http://127.0.0.1:1/y.wav", "") }.exceptionOrNull()
            assertTrue(next is UpnpException && next.code == 401)
            // L16 goes through.
            ctl.setUri("http://127.0.0.1:1/x.pcm", Didl.song("x", "X", "", null, null, "http://127.0.0.1:1/x.pcm", ProtocolInfo.of("audio/L16;rate=48000;channels=2", true), 1000, 10))
            assertEquals("STOPPED", ctl.transport().state)
        }
        // A renderer that only takes whole seconds: the seek goes again as the nearest second, and stays that way.
        FakeRenderer(preciseSeeks = false).use { fake ->
            val ctl = RendererControl(http, Describe.fetch(http, fake.location)!!)
            ctl.setUri("http://127.0.0.1:1/x.wav", Didl.song("x", "X", "", null, null, "http://127.0.0.1:1/x.wav", ProtocolInfo.of("audio/wav", true), 60_000, 10))
            ctl.play()
            ctl.seek(12_700)
            assertTrue(!ctl.preciseSeeks)
            assertTrue(ctl.position().relMs!! in 13_000L..13_999L)
            ctl.seek(20_200)
            assertTrue(ctl.position().relMs!! in 20_000L..20_999L)
            // The refused one, its retry, and the second seek (straight to whole seconds).
            assertEquals(3, fake.actions.count { it == "Seek" })
        }
    }

    /**
     * Not a test: a renderer on this PC for the phone to cast to, out loud, for [minutes] (env PRISM_FAKE_RENDERER).
     * With the phone on USB: `adb reverse tcp:49152 tcp:49152` and `adb forward tcp:47322 tcp:47322`, then point the
     * debug copy at http://127.0.0.1:49152/description.xml (files/connect_renderer.txt). Its log goes to
     * build/fake-renderer.log. PRISM_FAKE_RENDERER_QUIET set: nothing out loud (a check at night).
     */
    @Test
    fun castFromThePhone() {
        val minutes = System.getenv("PRISM_FAKE_RENDERER")?.toIntOrNull()
        assumeTrue(minutes != null)
        val log = java.io.File("build/fake-renderer.log").apply { writeText("") }
        val t0 = System.currentTimeMillis()
        val audible = System.getenv("PRISM_FAKE_RENDERER_QUIET") == null
        FakeRenderer(bind = "127.0.0.1", port = 49152, audible = audible, name = "PC speakers (test)", log = { line ->
            log.appendText("%7.2f %s\n".format((System.currentTimeMillis() - t0) / 1000.0, line))
        }).use { fake ->
            val until = System.currentTimeMillis() + minutes!! * 60_000L
            var last = ""
            while (System.currentTimeMillis() < until) {
                Thread.sleep(500)
                val now = "${fake.state} ${fake.positionMs / 1000}s vol ${fake.volume} ${fake.uri?.substringAfterLast('/')}"
                if (now != last) { log.appendText("%7.2f %s\n".format((System.currentTimeMillis() - t0) / 1000.0, now)); last = now }
            }
        }
    }

    private fun waitFor(ms: Long = 3_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out")
            Thread.sleep(25)
        }
    }
}

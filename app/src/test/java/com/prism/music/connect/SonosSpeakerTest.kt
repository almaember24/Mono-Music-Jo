package com.prism.music.connect

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * #239, against what a Sonos One SL really sent: the reporter's own device_description.xml (MAC and serial changed,
 * the shared in-app test's copy). There's no Sonos here, so everything past the description is [FakeRenderer] behaving
 * as a Sonos is remembered to, not as one was seen to: only answering a search for its ZonePlayer, refusing seeks with
 * milliseconds (402) and a next song. That a real one plays what it's sent isn't proven by this.
 */
class SonosSpeakerTest {
    private val xml = File("src/androidTest/assets/sonos-one-sl.xml").readText()
    private val http = OkHttpClient()
    private val path = "/xml/device_description.xml"

    /** A guess at what a Sonos lists, from memory: the reporter's file doesn't include this, and none was asked here. */
    private val sink = "http-get:*:audio/mpeg:*,x-file-cifs:*:audio/mpeg:*,http-get:*:audio/mp4:*,x-file-cifs:*:audio/mp4:*," +
        "http-get:*:audio/wav:*,x-file-cifs:*:audio/wav:*,http-get:*:audio/flac:*,x-file-cifs:*:audio/flac:*," +
        "x-rincon-stream:*:*:*,x-rincon-queue:*:*:*,x-sonos-http:*:*:*"

    private fun speaker() = FakeRenderer(
        sink = sink, gapless = false, preciseSeeks = false, seekRefusal = 402,
        descriptionXml = xml, descriptionPath = path, answersTo = setOf(Ssdp.ZONE_PLAYER),
    )

    @Test fun itsDescriptionIsReadAndNamedByItsRoom() {
        val r = Description.parse(xml, "http://192.168.68.61:1400$path")
        assertNotNull(r)
        r!!
        assertEquals("Kök", r.name)
        assertEquals("Sonos, Inc.", r.manufacturer)
        assertEquals("Sonos One SL", r.model)
        assertTrue(r.id, r.id.endsWith("_MR"))
        assertEquals("http://192.168.68.61:1400/MediaRenderer/AVTransport/Control", r.avTransport!!.controlUrl)
        assertEquals("http://192.168.68.61:1400/MediaRenderer/RenderingControl/Control", r.rendering!!.controlUrl)
        assertEquals("http://192.168.68.61:1400/MediaRenderer/ConnectionManager/Control", r.connection!!.controlUrl)
        assertEquals("192.168.68.61", r.host)
        assertTrue(Describe.isLocalLink(r.location))
    }

    @Test fun foundByItsZonePlayerAnswerAndDescribed() = speaker().use { fake ->
        val locations = mutableSetOf<String>()
        SsdpSearch.run(1_500, to = InetSocketAddress("127.0.0.1", fake.ssdpPort)) { m, _ -> m.location?.let { locations += it } }
        assertEquals(setOf(fake.location), locations)
        val r = Describe.fetch(http, fake.location)
        assertEquals("Kök", r?.name)
    }

    /** A song goes to it as WAV (YouTube's own streams are never sent as they are). */
    @Test fun itTakesASongAndItsControls() = runBlocking {
        speaker().use { fake ->
            val r = Describe.fetch(http, fake.location)!!
            val ctl = RendererControl(http, r)
            val mimes = ctl.sinkMimes()
            assertTrue("$mimes", "audio/wav" in mimes)
            val (format, mime) = ProtocolInfo.choose(mimes, "audio/webm")
            assertEquals(CastFormat.WAV, format)
            assertEquals(30, ctl.volume())
            val url = "http://127.0.0.1:1/x.wav"
            ctl.setUri(url, Didl.song("x", "Kök song", "Artist", null, null, url, ProtocolInfo.of(mime, true), 60_000, 10))
            assertEquals("STOPPED", ctl.transport().state)
            ctl.play()
            assertEquals("PLAYING", ctl.transport().state)
            // Its refusal is a code it has for bad arguments, not the "illegal seek target" of other renderers.
            ctl.seek(12_700)
            assertFalse(ctl.preciseSeeks)
            assertTrue(ctl.position().relMs!! in 13_000L..13_999L)
            val next = runCatching { ctl.setNextUri(url, "") }.exceptionOrNull()
            assertTrue("$next", next is UpnpException && next.code == 401)
            ctl.pause()
            assertEquals("PAUSED_PLAYBACK", ctl.transport().state)
        }
    }

    /** Why no speaker is listed reaches diagnostics instead of nowhere. */
    @Test fun whyItWasntListedIsSaid() {
        val why = ArrayList<String>()
        assertEquals(null, Describe.fetch(http, "http://8.8.8.8:1400$path") { why += it })
        assertTrue("$why", why.single().contains("local network"))
        why.clear()
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(null, Describe.fetch(http, "http://127.0.0.1:$closed$path") { why += it })
        assertTrue("$why", why.single().contains("couldn't read"))
        why.clear()
        FakeRenderer(descriptionXml = "<root/>", descriptionPath = path).use { fake ->
            assertEquals(null, Describe.fetch(http, fake.location) { why += it })
            assertTrue("$why", why.single().contains("doesn't describe"))
        }
    }
}

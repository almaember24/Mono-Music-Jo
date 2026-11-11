package com.prism.music.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prism Connect's UPnP pieces: descriptions, SSDP, SOAP, DIDL-Lite, times and format choice. */
class UpnpTest {
    /** Like a TV inside a media server's description (Sonos and some NAS boxes nest them): relative URLs, no URLBase. */
    private val nested = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>Living Room</friendlyName>
    <UDN>uuid:root-1</UDN>
    <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cd</controlURL></service></serviceList>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Living Room TV</friendlyName>
        <manufacturer>Samsung Electronics</manufacturer>
        <modelName>QE55Q80</modelName>
        <UDN>uuid:renderer-1</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
            <SCPDURL>RenderingControl/desc.xml</SCPDURL>
            <controlURL>RenderingControl/control</controlURL>
            <eventSubURL>/RenderingControl/evt</eventSubURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
            <SCPDURL>/AVTransport/desc.xml</SCPDURL>
            <controlURL>/upnp/control/AVTransport1</controlURL>
            <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
            <controlURL>http://192.168.1.50:9197/cm</controlURL>
          </service>
        </serviceList>
      </device>
    </deviceList>
  </device>
</root>"""

    @Test
    fun readsANestedRenderer() {
        val r = Description.parse(nested, "http://192.168.1.50:9197/dmr")
        assertNotNull(r)
        r!!
        assertEquals("uuid:renderer-1", r.id)
        assertEquals("Living Room TV", r.name)
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", r.avTransport!!.controlUrl)
        assertEquals("http://192.168.1.50:9197/RenderingControl/control", r.rendering?.controlUrl)
        assertEquals("http://192.168.1.50:9197/cm", r.connection?.controlUrl)
        assertEquals("192.168.1.50", r.host)
        assertEquals("Samsung Electronics QE55Q80", r.detail)
    }

    @Test
    fun urlBaseAndBareBases() {
        val withBase = nested.replace("<specVersion>", "<URLBase>http://192.168.1.50:8080/base/</URLBase><specVersion>")
        assertEquals("http://192.168.1.50:8080/upnp/control/AVTransport1", Description.parse(withBase, "http://192.168.1.50:9197/dmr")!!.avTransport!!.controlUrl)
        assertEquals("http://192.168.1.50:8080/base/RenderingControl/control", Description.parse(withBase, "http://192.168.1.50:9197/dmr")!!.rendering!!.controlUrl)
        // java.net.URI on its own would make "http://h:1ctl" of these.
        assertEquals("http://192.168.1.5:1400/ctl", Description.resolve("http://192.168.1.5:1400", "ctl"))
        assertEquals("http://192.168.1.5:1400/a/ctl", Description.resolve("http://192.168.1.5:1400/a/desc.xml", "ctl"))
    }

    private val place = "http://192.168.1.50:9197/dmr"

    /** A description says where its actions go, with the phone's stream address in them: only ever the device that described itself. */
    @Test
    fun aDescriptionsActionsStayOnItsOwnDevice() {
        // A device can't send them to anyone else, on the network or off it.
        for (elsewhere in listOf("http://203.0.113.9/steal", "https://203.0.113.9/steal", "http://192.168.1.77:9197/c", "//203.0.113.9/steal", "http://127.0.0.1:47320/x", "http://192.168.1.50@203.0.113.9/steal")) {
            val moved = nested.replace("<controlURL>/upnp/control/AVTransport1</controlURL>", "<controlURL>$elsewhere</controlURL>")
            assertNull(elsewhere, Description.parse(moved, place))
            // A service that isn't the one it can't play without just goes.
            val aside = nested.replace("<controlURL>RenderingControl/control</controlURL>", "<controlURL>$elsewhere</controlURL>")
            assertNull(elsewhere, Description.parse(aside, place)!!.rendering)
        }
        val events = nested.replace("<eventSubURL>/upnp/event/AVTransport1</eventSubURL>", "<eventSubURL>http://203.0.113.9/e</eventSubURL>")
            .replace("<SCPDURL>/AVTransport/desc.xml</SCPDURL>", "<SCPDURL>http://203.0.113.9/s</SCPDURL>")
        val r = Description.parse(events, place)!!
        assertNull(r.avTransport!!.eventUrl)
        assertNull(r.avTransport!!.scpdUrl)
    }

    /** A device whose URLBase is another address of its own: its relative addresses are on the one that was asked. */
    @Test
    fun aUrlBaseElsewhereIsIgnored() {
        val based = nested.replace("<specVersion>", "<URLBase>http://203.0.113.9:8080/base/</URLBase><specVersion>")
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", Description.parse(based, place)!!.avTransport!!.controlUrl)
        assertEquals("http://192.168.1.50:9197/RenderingControl/control", Description.parse(based, place)!!.rendering!!.controlUrl)
    }

    /** The debug hook adding a renderer through adb reverse, and a real device on its own port for its actions. */
    @Test
    fun itsOwnHostOnAnyPortIsFine() {
        val r = Description.parse(nested.replace("<controlURL>/upnp/control/AVTransport1</controlURL>", "<controlURL>http://127.0.0.1:1400/av</controlURL>"), "http://127.0.0.1:49152/description.xml")!!
        assertEquals("http://127.0.0.1:1400/av", r.avTransport!!.controlUrl)
        assertEquals("http://127.0.0.1:49152/RenderingControl/control", r.rendering!!.controlUrl)
    }

    /** Anyone on the Wi-Fi can answer a search: not on behalf of another address, least of all the phone's own. */
    @Test
    fun aSearchAnswerSpeaksOnlyForItsSender() {
        fun answersFor(location: String): List<String> {
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).use { device ->
                val answering = kotlin.concurrent.thread(isDaemon = true) {
                    runCatching {
                        val asked = java.net.DatagramPacket(ByteArray(2048), 2048)
                        device.receive(asked)
                        val b = ("HTTP/1.1 200 OK\r\nLOCATION: $location\r\nST: ${Ssdp.RENDERER}\r\nUSN: uuid:x\r\n\r\n").toByteArray()
                        device.send(java.net.DatagramPacket(b, b.size, asked.socketAddress))
                    }
                }
                SsdpSearch.run(700, to = java.net.InetSocketAddress("127.0.0.1", device.localPort)) { m, _ -> m.location?.let { seen += it } }
                answering.join(1_000)
            }
            return seen
        }
        assertEquals(listOf("http://127.0.0.1:49152/d.xml"), answersFor("http://127.0.0.1:49152/d.xml"))
        assertEquals("another device's address", emptyList<String>(), answersFor("http://192.168.1.77:1400/d.xml"))
        assertEquals("a name, not an address", emptyList<String>(), answersFor("http://speaker.local:1400/d.xml"))
    }

    @Test
    fun refusesWhatIsntARenderer() {
        val serverOnly = nested.substringBefore("<deviceList>") + "</device></root>"
        assertNull(Description.parse(serverOnly, "http://192.168.1.50:9197/dmr"))
        assertNull(Description.parse("<root><device>", "http://x"))
        // Entities are never expanded (no billion laughs, no external files).
        assertNull(Description.parse("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY a \"x\">]><root>&a;</root>", "http://x"))
    }

    @Test
    fun readsSsdpAnswersAndNotifies() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.50:9197/dmr\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:renderer-1::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val m = SsdpMessage.parse(answer)!!
        assertFalse(m.notify)
        assertEquals("http://192.168.1.50:9197/dmr", m.location)
        assertTrue(Ssdp.isRenderer(m))
        val bye = SsdpMessage.parse("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:service:AVTransport:1\r\nNTS: ssdp:byebye\r\nUSN: uuid:renderer-1::urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n")!!
        assertTrue(bye.notify && bye.byebye && Ssdp.isRenderer(bye))
        assertNull(SsdpMessage.parse(Ssdp.search(Ssdp.RENDERER)))
        assertFalse(Ssdp.isRenderer(SsdpMessage.parse("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n")!!))
        assertTrue(Ssdp.search(Ssdp.RENDERER).let { it.startsWith("M-SEARCH * HTTP/1.1\r\n") && it.endsWith("\r\n\r\n") && "ST: ${Ssdp.RENDERER}" in it })
    }

    @Test
    fun soapRoundTrip() {
        val title = "Rock & Roll <Live> \"Ain't\""
        val didl = Didl.song("abc", title, "AC/DC & Co", "Album <1>", "http://192.168.1.2:47322/cast/t/art.jpg", "http://192.168.1.2:47322/cast/t/abc.wav?x=1&y=2",
            ProtocolInfo.of("audio/wav", true), 201_000, 35_000_044)
        val env = Soap.envelope("urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "http://h/a.wav?x=1&y=2", "CurrentURIMetaData" to didl))
        // What the renderer reads back out of the envelope is the DIDL as it was…
        val doc = Xml.parse(env)!!
        val values = with(Xml) { doc.documentElement.all("SetAVTransportURI").first().let { a -> a.kids("CurrentURIMetaData").first().textContent } }
        assertEquals(didl, values)
        // …and the DIDL's own title is the title.
        val item = Xml.parse(didl)!!
        assertEquals(title, with(Xml) { item.documentElement.all("title").first().textContent })
        assertEquals("0:03:21.000", with(Xml) { item.documentElement.all("res").first().getAttribute("duration") })
        assertEquals("http://192.168.1.2:47322/cast/t/abc.wav?x=1&y=2", with(Xml) { item.documentElement.all("res").first().textContent })
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", Soap.actionHeader("urn:schemas-upnp-org:service:AVTransport:1", "Play"))
    }

    @Test
    fun readsSoapAnswersAndFaults() {
        val ok = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><Track>1</Track><TrackDuration>0:03:21</TrackDuration>
            <TrackMetaData>&lt;DIDL-Lite&gt;&lt;/DIDL-Lite&gt;</TrackMetaData><TrackURI>http://x/cast/t/a.wav</TrackURI><RelTime>0:01:02.500</RelTime>
            <AbsTime>NOT_IMPLEMENTED</AbsTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        val r = Soap.parse(ok) as SoapResult.Ok
        assertEquals("http://x/cast/t/a.wav", r.values["TrackURI"])
        assertEquals("<DIDL-Lite></DIDL-Lite>", r.values["TrackMetaData"])
        assertEquals(62_500L, UpnpTime.parse(r.values["RelTime"]))
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>"""
        assertEquals(SoapResult.Fault(714, "Illegal MIME-type"), Soap.parse(fault))
        assertEquals(SoapResult.Ok(emptyMap()), Soap.parse("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:PlayResponse xmlns:u="x"/></s:Body></s:Envelope>"""))
    }

    @Test
    fun times() {
        assertEquals("0:00:00", UpnpTime.format(0))
        assertEquals("0:03:21", UpnpTime.format(201_999))
        assertEquals("1:02:03", UpnpTime.format(3_723_000))
        assertEquals("0:01:02.500", UpnpTime.format(62_500, fraction = true))
        assertEquals(62_500L, UpnpTime.parse(UpnpTime.format(62_500, fraction = true)))
        assertEquals(3_723_000L, UpnpTime.parse("1:02:03"))
        assertEquals(3_723_100L, UpnpTime.parse("01:02:03.1"))
        assertEquals(5_000L, UpnpTime.parse("0:00:05.000/1"))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse(""))
        assertNull(UpnpTime.parse("0:61:00"))
    }

    @Test
    fun picksWhatTheRendererPlays() {
        val tv = ProtocolInfo.sinkMimes("http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/x-wav:*,http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM,http-get:*:video/mp4:*")
        assertEquals(setOf("audio/mpeg", "audio/mp4", "audio/x-wav", "audio/l16", "video/mp4"), tv)
        // YouTube's DASH files are always decoded, even when the type's listed.
        assertEquals(CastFormat.WAV to "audio/x-wav", ProtocolInfo.choose(tv, "audio/webm"))
        assertEquals(CastFormat.WAV to "audio/x-wav", ProtocolInfo.choose(tv, "audio/mp4"))
        // A plain MP3 on the phone goes as it is, under the spelling the renderer used.
        assertEquals(CastFormat.ORIGINAL to "audio/mpeg", ProtocolInfo.choose(tv, "audio/mpeg"))
        assertEquals(CastFormat.ORIGINAL to "audio/mpeg", ProtocolInfo.choose(tv, "audio/mp3"))
        assertEquals(CastFormat.WAV to "audio/x-wav", ProtocolInfo.choose(tv, "audio/flac"))
        assertEquals(CastFormat.WAV to "audio/x-wav", ProtocolInfo.choose(tv, "audio/mpeg", decodedFallback = true))
        // Only LPCM listed: L16. Nothing said: WAV.
        assertEquals(CastFormat.L16 to "audio/L16", ProtocolInfo.choose(setOf("audio/l16"), "audio/webm"))
        assertEquals(CastFormat.WAV to "audio/wav", ProtocolInfo.choose(emptySet(), null))
        assertEquals(CastFormat.ORIGINAL to "audio/flac", ProtocolInfo.choose(setOf("*"), "audio/flac"))
        assertTrue(ProtocolInfo.of("audio/L16;rate=48000;channels=2", true).startsWith("http-get:*:audio/L16;rate=48000;channels=2:DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=1;"))
    }

    @Test
    fun localNetworkOnly() {
        assertTrue(Describe.isLocalLink("http://192.168.1.50:9197/dmr"))
        assertTrue(Describe.isLocalLink("http://10.0.0.2/d.xml"))
        assertTrue(Describe.isLocalLink("http://127.0.0.1:49152/d.xml"))
        assertFalse(Describe.isLocalLink("http://8.8.8.8/d.xml"))
        assertFalse(Describe.isLocalLink("http://tv.example.com/d.xml"))
        assertFalse(Describe.isLocalLink("https://192.168.1.50/d.xml"))
        assertFalse(Describe.isLocalLink("file:///etc/passwd"))
        assertTrue(Lan.hostIsLocalName("192.168.1.40:47320"))
        assertTrue(Lan.hostIsLocalName("localhost:47320"))
        assertTrue(Lan.hostIsLocalName("[fe80::1]:47320"))
        assertTrue(Lan.hostIsLocalName("pixel.local"))
        assertFalse(Lan.hostIsLocalName("evil.example.com"))
        assertFalse(Lan.hostIsLocalName("192.168.1.40.evil.com"))
        assertFalse(Lan.hostIsLocalName(null))
    }
}

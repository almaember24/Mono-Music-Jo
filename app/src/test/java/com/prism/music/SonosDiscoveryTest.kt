package com.prism.music

import com.prism.music.connect.Description
import com.prism.music.connect.Ssdp
import com.prism.music.connect.SsdpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #239 and Reddit (1.7.9.3): Sonos speakers never showed up. They answer a search as a ZonePlayer, with the
 * MediaRenderer (and its AVTransport) inside; Prism only searched for, and only accepted, MediaRenderer / AVTransport.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SonosDiscoveryTest {
    private val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nLOCATION: http://192.168.1.31:1400/xml/device_description.xml\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\nUSN: uuid:RINCON_000E58A0B1C201400::urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

    /** The shape of a Sonos speaker's description (a Sonos One SL in the "Living Room"). */
    private val description = """
        <?xml version="1.0" encoding="utf-8" ?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
            <friendlyName>192.168.1.31 - Sonos One SL - RINCON_000E58A0B1C201400</friendlyName>
            <manufacturer>Sonos, Inc.</manufacturer>
            <modelName>Sonos One SL</modelName>
            <UDN>uuid:RINCON_000E58A0B1C201400</UDN>
            <roomName>Living Room</roomName>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:DeviceProperties:1</serviceType><controlURL>/DeviceProperties/Control</controlURL></service>
            </serviceList>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>192.168.1.31 - Sonos One SL Media Server - RINCON_000E58A0B1C201400</friendlyName>
                <UDN>uuid:RINCON_000E58A0B1C201400_MS</UDN>
                <serviceList>
                  <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/MediaServer/ContentDirectory/Control</controlURL></service>
                </serviceList>
              </device>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Living Room - Sonos One SL Media Renderer - RINCON_000E58A0B1C201400</friendlyName>
                <modelName>Sonos One SL</modelName>
                <UDN>uuid:RINCON_000E58A0B1C201400_MR</UDN>
                <serviceList>
                  <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/MediaRenderer/RenderingControl/Control</controlURL><eventSubURL>/MediaRenderer/RenderingControl/Event</eventSubURL></service>
                  <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/MediaRenderer/ConnectionManager/Control</controlURL></service>
                  <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/MediaRenderer/AVTransport/Control</controlURL><eventSubURL>/MediaRenderer/AVTransport/Event</eventSubURL></service>
                </serviceList>
              </device>
            </deviceList>
          </device>
        </root>
    """.trimIndent()

    @Test fun prismAsksForSonosSpeakersAndTakesTheirAnswer() {
        assertTrue(Ssdp.search(Ssdp.ZONE_PLAYER).contains("ST: urn:schemas-upnp-org:device:ZonePlayer:1"))
        val m = SsdpMessage.parse(answer)!!
        assertTrue("a ZonePlayer answer is a speaker", Ssdp.isRenderer(m))
    }

    @Test fun theRendererInsideIsFoundAndNamedByItsRoom() {
        val r = Description.parse(description, "http://192.168.1.31:1400/xml/device_description.xml")
        assertNotNull(r)
        assertEquals("Living Room", r!!.name)
        assertEquals("http://192.168.1.31:1400/MediaRenderer/AVTransport/Control", r.avTransport!!.controlUrl)
        assertEquals("http://192.168.1.31:1400/MediaRenderer/RenderingControl/Control", r.rendering!!.controlUrl)
        assertEquals("uuid:RINCON_000E58A0B1C201400_MR", r.id)
    }
}

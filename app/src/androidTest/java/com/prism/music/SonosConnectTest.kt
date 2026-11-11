package com.prism.music

import android.os.Build
import android.security.NetworkSecurityPolicy
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.connect.Describe
import com.prism.music.connect.RendererControl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * #239 on a device, where Android's rule about plain HTTP applies (a test on the PC's JVM can't see it): a pretend speaker
 * on this device's own Wi-Fi address serves a real Sonos One SL's description and answers its controls. There's no Sonos
 * here, so what's proven is Prism's half: it can read a local speaker at all. The control shows why it couldn't before.
 */
@RunWith(AndroidJUnit4::class)
class SonosConnectTest {
    private val xml = InstrumentationRegistry.getInstrumentation().context.assets.open("sonos-one-sl.xml").use { it.readBytes() }
    private lateinit var server: ServerSocket
    private val path = "/xml/device_description.xml"
    @Volatile private var chunked = false

    /** The first non-loopback IPv4 address: what a speaker's address looks like to the rule (10.0.2.x on an emulator). */
    private val lanAddress: InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

    @Before fun speaker() {
        server = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { answer(it) } }
            }
        }
    }

    @After fun gone() = server.close()

    private fun answer(s: Socket) {
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return; head.append(b.toChar()) }
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length).also { b -> var n = 0; while (n < length) { val r = input.read(b, n, length - n); if (r < 0) break; n += r } }.toString(Charsets.UTF_8)
        val out = s.getOutputStream()
        val answer = if (head.startsWith("GET $path")) xml else soap(body).toByteArray()
        val top = "HTTP/1.1 200 OK\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nServer: Linux UPnP/1.0 Sonos/97.2-81220 (ZPS38)\r\nConnection: close\r\n"
        if (chunked) {
            out.write((top + "Transfer-Encoding: chunked\r\n\r\n").toByteArray())
            for (part in answer.toList().chunked(1000)) {
                out.write("${Integer.toHexString(part.size)}\r\n".toByteArray())
                out.write(part.toByteArray())
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n\r\n".toByteArray())
        } else {
            out.write((top + "Content-Length: ${answer.size}\r\n\r\n").toByteArray())
            out.write(answer)
        }
        out.flush()
    }

    private fun soap(request: String): String {
        val action = Regex("<u:(\\w+) ").find(request)?.groupValues?.get(1).orEmpty()
        val values = when (action) {
            "GetTransportInfo" -> "<CurrentTransportState>STOPPED</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
            "GetProtocolInfo" -> "<Source></Source><Sink>http-get:*:audio/mpeg:*,http-get:*:audio/wav:*,x-rincon-stream:*:*:*</Sink>"
            "GetVolume" -> "<CurrentVolume>17</CurrentVolume>"
            else -> ""
        }
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$values</u:${action}Response></s:Body></s:Envelope>"
    }

    private fun location(a: InetAddress) = "http://${a.hostAddress}:${server.localPort}$path"

    /** What Prism's search does with the answer from a speaker, over the address a real one would have. */
    @Test fun aLocalSpeakerIsReadAndControlled(): Unit = runBlocking {
        val a = lanAddress
        assumeTrue("needs a network address", a != null)
        val r = Describe.fetch(OkHttpClient(), location(a!!)) { Log.w(TAG, it) }
        assertEquals("Kök", r?.name)
        assertEquals("Sonos One SL", r!!.model)
        val ctl = RendererControl(OkHttpClient(), r)
        assertEquals("STOPPED", ctl.transport().state)
        assertEquals(setOf("audio/mpeg", "audio/wav"), ctl.sinkMimes())
        assertEquals(17, ctl.volume())
        ctl.play()
        Log.i(TAG, "read ${r.name} at ${r.location}")
    }

    /** Sonos's own server may answer chunked; the description and its controls read the same. */
    @Test fun aChunkedAnswerReadsTheSame(): Unit = runBlocking {
        val a = lanAddress
        assumeTrue("needs a network address", a != null)
        chunked = true
        val r = Describe.fetch(OkHttpClient(), location(a!!)) { Log.w(TAG, it) }
        assertEquals("Kök", r?.name)
        assertEquals("STOPPED", RendererControl(OkHttpClient(), r!!).transport().state)
    }

    /** The whole of Prism's side: listed under its room, and connected to (with nothing playing, so no song goes over). */
    @Test fun prismListsItAndConnects() {
        val a = lanAddress
        assumeTrue("needs a network address", a != null)
        Fixture.ready()
        val cast = Fixture.container.cast
        cast.addByLocation(location(a!!))
        fun <T> until(what: String, get: () -> T?): T {
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) { get()?.let { return it }; Thread.sleep(100) }
            error("Never: $what")
        }
        val speaker = until("listed") { cast.renderers.value.firstOrNull { it.name == "Kök" } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { cast.connect(speaker) }
        try {
            until("connected") { (cast.state.value as? com.prism.music.connect.CastState.Casting)?.takeIf { it.renderer.id == speaker.id } }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { cast.disconnect() }
        }
    }

    /** Loopback too (the test copies' debug hook, and FakeRenderer through adb, reach a renderer that way). */
    @Test fun loopbackWorksToo() {
        val r = Describe.fetch(OkHttpClient(), location(InetAddress.getByName("127.0.0.1"))) { Log.w(TAG, it) }
        assertEquals("Kök", r?.name)
    }

    /**
     * The control, and the cause of #239: OkHttp on its own, as Prism used to ask, on a phone that refuses plain HTTP.
     * Logged either way (tag PrismSonosTest), since it's the finding.
     */
    @Test fun whyItFailedBefore() {
        val a = lanAddress
        assumeTrue("needs a network address", a != null)
        val permitted = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("192.168.68.61")
        val thisOne = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(a!!.hostAddress!!)
        val loopback = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1")
        val plain = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
        val outcome = runCatching { plain.newCall(Request.Builder().url(location(a)).build()).execute().use { it.code } }
        val atLoopback = runCatching { plain.newCall(Request.Builder().url(location(InetAddress.getByName("127.0.0.1"))).build()).execute().use { it.code } }
        Log.i(TAG, "Android ${Build.VERSION.SDK_INT}: plain HTTP allowed to 192.168.68.61 = $permitted, to ${a.hostAddress} = $thisOne, to 127.0.0.1 = $loopback; " +
            "OkHttp alone: LAN $outcome, loopback $atLoopback")
        if (!thisOne) {
            val e = outcome.exceptionOrNull()
            assertTrue("$outcome", e is UnknownServiceException && e.message.orEmpty().contains("CLEARTEXT"))
        }
    }

    private companion object { const val TAG = "PrismSonosTest" }
}

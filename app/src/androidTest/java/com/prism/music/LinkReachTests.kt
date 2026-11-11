package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.connect.HLink
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffRequest
import com.prism.music.connect.HandoffResult
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.connect.LinkKeys
import com.prism.music.connect.PrismDevice
import com.prism.music.connect.RemoteNow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Reddit (1.8.1): a phone listed "Dr.'s Google TV Streamer", said "One moment…", then "Couldn't reach … Is Prism open
 * there, on the same Wi-Fi?". A TV answers on several addresses and the phone kept one. Here a TV (the test plays it,
 * over a local connection, as PhoneTvPlayAlongTest does) is reached on the second of its addresses when the first doesn't
 * answer; and when none does, the phone says what went wrong, the TV's row says so, and trying again reaches it once
 * Prism is there.
 */
@RunWith(AndroidJUnit4::class)
class LinkReachTests {
    private class Tv : HandoffLocal {
        override val name = "Test TV"
        override val device = "test-tv-reach"
        override val isTv = true
        val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun pairingCode(from: String): String? = null
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun nowPlaying() = RemoteNow("A tone", "Prism", playing = true, positionMs = 1_000, durationMs = 60_000)
    }

    private val c get() = Fixture.container
    private val servers = ArrayList<ServerSocket>()
    private val tv = Tv()

    @After fun cleanUp() {
        servers.forEach { runCatching { it.close() } }
        c.handoff.forget(tv.device)
    }

    /** The TV's Prism, listening on [port] of this device's loopback (0: any). */
    private fun serve(port: Int = 0): ServerSocket {
        val server = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 10) }
        servers += server
        thread { while (!server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break; thread { HLink(s).use { HandoffPeer(tv).serve(it) } } } }
        return server
    }

    private fun pair() {
        val key = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        tv.keys[c.handoff.device] = key
        c.handoff.savePairing(tv.device, tv.name, key)
    }

    @Test fun aTvIsReachedOnItsSecondAddressWhenTheFirstDoesntAnswer() {
        Fixture.ready()
        pair()
        val server = serve()
        // Its first address answers nothing on the port (another loopback address, which Prism doesn't listen on).
        val device = PrismDevice(tv.name, "127.0.0.2", server.localPort, tv.device, "test:reach", paired = true, tv = true, hosts = listOf("127.0.0.2", "127.0.0.1"))
        val r = runBlocking { c.handoff.ask(device, HandoffRequest.Now) }
        assertTrue("answered: $r", r is HandoffResult.Playing)
        assertEquals("A tone", (r as HandoffResult.Playing).now?.title)
        assertTrue(device.key !in c.handoff.unreachable.value)
    }

    @Test fun noAddressAnsweringSaysWhyAndTryingAgainReachesIt() {
        Fixture.ready()
        pair()
        // A port nothing listens on now: the TV answers (this device), Prism there doesn't.
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val device = PrismDevice(tv.name, "127.0.0.1", port, tv.device, "test:reach", paired = true, tv = true)
        val failed = runBlocking { c.handoff.ask(device, HandoffRequest.Now) }
        assertTrue("failed: $failed", failed is HandoffResult.Failed)
        assertEquals(tr(R.string.link_refused, tv.name), (failed as HandoffResult.Failed).reason)
        // Its row says so (tapped, it tries again).
        assertEquals(tr(R.string.link_refused, tv.name), c.handoff.unreachable.value[device.key])

        // Prism opened there: trying again reaches it, and the row is as before.
        serve(port)
        val again = runBlocking { c.handoff.ask(device, HandoffRequest.Now) }
        assertTrue("answered: $again", again is HandoffResult.Playing)
        assertTrue(device.key !in c.handoff.unreachable.value)
    }
}

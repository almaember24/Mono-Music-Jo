package com.prism.music.connect

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A Prism TV playing along with a phone (1.8.0) over the paired, sealed link: it stays open both ways for as long as it
 * lasts; only a TV plays along; what the TV is sent is cleaned as any song from another device.
 */
class TvFollowLinkTest {
    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist", null)), durationSec = 200, thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
    private val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc")

    private open class Side(override val name: String, override val device: String, override val isTv: Boolean) : HandoffLocal {
        val secrets = ConcurrentHashMap<String, String>()
        override fun secretFor(device: String) = secrets[device]
        override fun savePairing(device: String, name: String, secret: String) { secrets[device] = secret }
        override fun forget(device: String) { secrets.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
    }

    /** The TV: takes the states it's sent, and says something back on the same connection. */
    private class Tv : Side("Living room TV", "tv-1", true) {
        val states = CopyOnWriteArrayList<FollowState>()
        val from = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        @Volatile var back: Boolean? = null
        override fun followStart(from: String): String? = null
        override fun follow(link: HLink, first: FollowState, from: String) {
            this.from += from
            states += first
            link.send(HMsg.Where(TvWhere(first.songs[first.index].id, 12_000, playing = true, volume = 40, seq = first.seq)))
            while (true) {
                when (val m = link.read() ?: break) {
                    is HMsg.FollowNow -> { states += m.state; link.send(HMsg.Act(TvAct(TvAct.PAUSE, n = states.size.toLong() - 1))) }
                    is HMsg.FollowEnd -> { back = m.back; break }
                    else -> Unit
                }
            }
            ended.countDown()
        }
    }

    private fun paired(phone: Side, tv: Side) {
        val key = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        phone.secrets[tv.device] = key
        tv.secrets[phone.device] = key
    }

    @Test fun theLinkStaysOpenBothWaysWhileTheTvPlaysAlong() {
        val phone = Side("Davis's Pixel", "phone-1", false)
        val tv = Tv()
        paired(phone, tv)
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val served = thread { runCatching { server.accept().use { s -> HLink(s).use { HandoffPeer(tv).serve(it) } } } }
        val link = HLink(Socket(InetAddress.getLoopbackAddress(), server.localPort))
        try {
            // A file on the phone in the window is left out; the song playing stays the one at the index.
            val first = FollowState(listOf(song(ids[0]), song("local:7"), song(ids[1]), song(ids[2])), 2, playing = true, seekTo = 42_000, seq = 1)
            val r = HandoffPeer(phone).request(link, HandoffRequest.Follow(first), HandoffExpect(device = tv.device)) { null }
            assertEquals(HandoffResult.Done(), r)
            // The TV says where it is, on the same connection.
            val where = link.read() as HMsg.Where
            assertEquals(ids[1], where.at.songId)
            assertEquals(40, where.at.volume)
            // The phone moves on: the TV has it, and pauses from its own remote, which comes back.
            assertTrue(link.send(HMsg.FollowNow(first.copy(index = 3, seekTo = -1, seq = 2))))
            val act = (link.read() as HMsg.Act).act
            assertEquals(TvAct.PAUSE, act.kind)
            // The act's number, said back with the next state (the TV doesn't take states from before it).
            assertEquals(1L, act.n)
            assertTrue(link.send(HMsg.FollowNow(first.copy(index = 3, seekTo = -1, seq = 3, acts = act.n))))
            link.read()
            // Stopped from the phone: the music comes back to it.
            link.send(HMsg.FollowEnd(back = true))
            assertTrue(tv.ended.await(5, TimeUnit.SECONDS))
        } finally {
            link.close()
            served.join(5_000)
            server.close()
        }
        assertEquals(listOf("Davis's Pixel"), tv.from.toList())
        assertEquals(true, tv.back)
        assertEquals(3, tv.states.size)
        // What the TV was handed is what the phone sent (the TV cleans it again itself, TvSync.cleaned).
        assertEquals(listOf(ids[0], "local:7", ids[1], ids[2]), tv.states[0].songs.map { it.id })
        assertEquals(42_000L, tv.states[0].seekTo)
        assertEquals(3, tv.states[1].index)
        assertEquals(1L, tv.states[2].acts)
    }

    @Test fun onlyATvPlaysAlong() {
        val phone = Side("Davis's Pixel", "phone-1", false)
        val other = Side("Pixel Tablet", "tablet-1", false)
        paired(phone, other)
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val served = thread { runCatching { server.accept().use { s -> HLink(s).use { HandoffPeer(other).serve(it) } } } }
        try {
            val r = HLink(Socket(InetAddress.getLoopbackAddress(), server.localPort)).use { link ->
                HandoffPeer(phone).request(link, HandoffRequest.Follow(FollowState(listOf(song(ids[0])), 0, true)), HandoffExpect(device = other.device)) { null }
            }
            assertTrue("a tablet isn't asked: $r", r is HandoffResult.Failed)
        } finally {
            served.join(5_000)
            server.close()
        }
    }
}

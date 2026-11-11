package com.prism.music.connect

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Prism Connect handoff between two Prisms, over a real connection: pairing, moving, pulling, and being forgotten. */
class HandoffTest {
    private fun song(id: String, title: String = id) = Song(id, title, listOf(ArtistRef("Artist", null)), durationSec = 200, thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg")

    private class Device(override val name: String, override val device: String) : HandoffLocal {
        val secrets = ConcurrentHashMap<String, String>()
        @Volatile var code: String? = null
        @Volatile var playing: HandoffSnapshot? = null
        val took = CopyOnWriteArrayList<Pair<HandoffSnapshot, String>>()
        @Volatile var steppedAside = 0

        override fun secretFor(device: String) = secrets[device]
        override fun savePairing(device: String, name: String, secret: String) { secrets[device] = secret }
        override fun forget(device: String) { secrets.remove(device) }
        override fun showCode(code: String?, from: String) { if (code != null) this.code = code }
        override fun snapshot() = playing
        /** Why it can't play now (Prism closed there, so Android won't let it start), or null. */
        @Volatile var cantPlay: String? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? { cantPlay?.let { return it }; took += s to from; playing = s; return null }
        override fun stepAside() { steppedAside++ }
    }

    /** [receiver] listening (as "available for handoff"); [run] calls it as [caller]. */
    private fun <T> session(receiver: Device, run: (HLink) -> T): T {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val peer = HandoffPeer(receiver)
        val t = thread { runCatching { server.accept().use { s -> HLink(s).use { peer.serve(it) } } } }
        try {
            return HLink(Socket(InetAddress.getLoopbackAddress(), server.localPort)).use(run)
        } finally {
            t.join(5_000)
            server.close()
        }
    }

    private val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd")

    private fun queue(index: Int = 2) = HandoffSnapshot(
        songs = listOf(song(ids[0]), song("local:42", "A file"), song(ids[1]), song(ids[2]), song(ids[3])),
        index = index, positionMs = 83_000, playing = true, shuffle = true, order = listOf(3, 0, 2, 1, 4), repeat = 2, title = "Late night",
    )

    @Test
    fun pairsOnceThenMovesAndPulls() {
        val phone = Device("Davis's Pixel", "phone-1").apply { playing = queue() }
        val tablet = Device("Pixel Tablet", "tablet-1")
        var asked = 0
        val first = session(tablet) { link ->
            HandoffPeer(phone).call(link, HandoffCommand.MOVE) { them -> asked++; assertEquals("Pixel Tablet", them); tablet.code }
        }
        assertEquals(HandoffResult.Done("1 song on this device stayed behind"), first)
        assertEquals(1, asked)
        // Both keep the same key, under each other's id.
        assertNotNull(phone.secrets["tablet-1"])
        assertEquals(phone.secrets["tablet-1"], tablet.secrets["phone-1"])
        // The tablet plays the same queue less the file, from the same song and place, in the same play order.
        val (got, from) = tablet.took.single()
        assertEquals("Davis's Pixel", from)
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), got.songs.map { it.id })
        assertEquals(1, got.index)
        assertEquals(ids[1], got.songs[got.index].id)
        assertEquals(83_000L, got.positionMs)
        // Old order 3,0,2,1,4 → (file left out) 2,0,1,3.
        assertEquals(listOf(2, 0, 1, 3), got.order)
        assertTrue(got.shuffle && got.playing)
        assertEquals(2, got.repeat)
        assertEquals("Late night", got.title)
        // The phone stepped aside only after the tablet said it was playing.
        assertEquals(1, phone.steppedAside)

        // Next time: no code.
        tablet.code = null
        phone.playing = queue(index = 0)
        val again = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { asked++; null } }
        assertEquals(HandoffResult.Done("1 song on this device stayed behind"), again)
        assertEquals(1, asked)
        assertNull(tablet.code)

        // And back: the phone pulls it from the tablet.
        val pulled = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.PULL) { null } }
        assertEquals(HandoffResult.Done(null), pulled)
        assertEquals(ids[0], phone.took.single().first.songs[phone.took.single().first.index].id)
        assertEquals(1, tablet.steppedAside)
    }

    @Test
    fun wrongCodesAndForgottenPairings() {
        val phone = Device("Phone", "phone-1").apply { playing = queue() }
        val tablet = Device("Tablet", "tablet-1")
        val wrong = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { if (tablet.code == "111111") "222222" else "111111" } }
        assertTrue(wrong is HandoffResult.Failed)
        assertTrue(phone.secrets.isEmpty() && tablet.secrets.isEmpty() && tablet.took.isEmpty() && phone.steppedAside == 0)
        // Cancelled at the code box.
        assertEquals(HandoffResult.Cancelled, session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { null } })

        // Paired, then the tablet forgets the phone: the phone's key is refused. It keeps it (that "no" isn't proved)
        // until pairing again with the code replaces it.
        assertTrue(session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { tablet.code } } is HandoffResult.Done)
        tablet.secrets.clear()
        val kept = phone.secrets["tablet-1"]
        assertEquals(HandoffResult.Unpaired, session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { null } })
        assertEquals(kept, phone.secrets["tablet-1"])
        val again = session(tablet) { link -> HandoffPeer(phone).request(link, HandoffRequest.Move, HandoffExpect(repair = true)) { tablet.code } }
        assertTrue("$again", again is HandoffResult.Done)
        assertEquals(tablet.secrets["phone-1"], phone.secrets["tablet-1"])
        assertTrue(kept != phone.secrets["tablet-1"])
        // A made-up key gets nowhere either.
        phone.secrets["tablet-1"] = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        assertEquals(HandoffResult.Unpaired, session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.PULL) { null } })
    }

    /**
     * A whole queue moves (2,000 songs, sealed: far past the old line limit), and only what can play there is sent:
     * files here stay, names and all (here, 3,000 of them with long names: sent, they'd be too much to pass).
     */
    @Test
    fun aWholeQueueMovesAndFilesStayBehind() {
        val yt = (0 until HandoffSnapshot.MAX_SONGS).map { i -> song("v" + i.toString().padStart(10, '0'), "Song $i ".repeat(8)) }
        val files = (0 until 3_000).map { i -> Song("local:$i", "A file on this phone $i ".repeat(80)) }
        val phone = Device("Phone", "phone-1").apply { playing = HandoffSnapshot(songs = yt + files, index = 5, positionMs = 1_000, playing = true) }
        val tablet = Device("Tablet", "tablet-1")
        val r = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { tablet.code } }
        assertTrue("$r", r is HandoffResult.Done && r.note != null)
        val got = tablet.took.single().first
        assertEquals(HandoffSnapshot.MAX_SONGS, got.songs.size)
        assertEquals(yt[5].id, got.songs[got.index].id)
        assertTrue(got.songs.none { it.id.startsWith("local:") })
    }

    /** Pulling from a device playing files too: they stay there (never sent), and how many is said. */
    @Test
    fun aPullLeavesTheOtherDevicesFilesThere() {
        val phone = Device("Phone", "phone-1")
        val tablet = Device("Tablet", "tablet-1").apply { playing = queue() }
        val r = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.PULL) { tablet.code } }
        assertEquals(HandoffResult.Done("1 song stayed on Tablet"), r)
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), phone.took.single().first.songs.map { it.id })
    }

    @Test
    fun aDeviceThatCantPlaySaysSoAndTheMusicStaysPut() {
        val phone = Device("Phone", "phone-1").apply { playing = queue() }
        val tablet = Device("Tablet", "tablet-1").apply { cantPlay = "Open Prism on Tablet first" }
        val r = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { tablet.code } }
        assertEquals(HandoffResult.Failed("Open Prism on Tablet first"), r)
        // Still paired (that part went through), but the phone never stepped aside.
        assertNotNull(phone.secrets["tablet-1"])
        assertEquals(0, phone.steppedAside)
        // Pulling into a device that can't play: the other one keeps playing too.
        phone.cantPlay = "Can't here"
        val tabletNow = Device("Tablet", "tablet-1").apply { playing = queue(); secrets.putAll(tablet.secrets) }
        val pull = session(tabletNow) { link -> HandoffPeer(phone).call(link, HandoffCommand.PULL) { null } }
        assertEquals(HandoffResult.Failed("Can't here"), pull)
        assertEquals(0, tabletNow.steppedAside)
    }

    @Test
    fun nothingThatCantComeIsSent() {
        val phone = Device("Phone", "phone-1").apply { playing = queue(index = 1) }
        val tablet = Device("Tablet", "tablet-1")
        // The song playing is a file on the phone: it can't move (and the tablet isn't even asked to play).
        val r = session(tablet) { link -> HandoffPeer(phone).call(link, HandoffCommand.MOVE) { tablet.code } }
        assertEquals(HandoffResult.Failed("This song is a file on this device: it can't move"), r)
        assertTrue(tablet.took.isEmpty())
        assertEquals(0, phone.steppedAside)
        // A peer's snapshot is cleaned on arrival too: bad ids and pictures from elsewhere don't get through.
        val odd = HandoffSnapshot(
            songs = listOf(song(ids[0]).copy(thumbnail = "http://evil.example/x.png"), Song("../../etc", "x"), song(ids[1])),
            index = 2, positionMs = -5, playing = false, shuffle = true, order = listOf(0, 1), repeat = 9,
        ).cleaned()!!
        assertEquals(listOf(ids[0], ids[1]), odd.songs.map { it.id })
        assertNull(odd.songs[0].thumbnail)
        assertEquals(1, odd.index)
        assertEquals(0L, odd.positionMs)
        // An order that doesn't cover the queue: queue order instead.
        assertTrue(!odd.shuffle && odd.order.isEmpty())
        assertEquals(2, odd.repeat)
    }

    @Test
    fun pairingAsksAreLimited() {
        val tablet = Device("Tablet", "tablet-1")
        var now = 0L
        val peer = HandoffPeer(tablet) { now }
        val answers = (1..HandoffPeer.PAIR_ASKS_PER_MINUTE + 1).map {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            val t = thread { runCatching { server.accept().use { s -> HLink(s).use { peer.serve(it) } } } }
            val r = HLink(Socket(InetAddress.getLoopbackAddress(), server.localPort)).use { link ->
                HandoffPeer(Device("Stranger $it", "s-$it")).call(link, HandoffCommand.MOVE) { null }
            }
            t.join(5_000); server.close()
            r
        }
        assertTrue(answers.dropLast(1).all { it == HandoffResult.Cancelled })
        assertEquals(HandoffResult.Failed("Too many pairing tries. Wait a minute."), answers.last())
        now += 61_000
    }
}

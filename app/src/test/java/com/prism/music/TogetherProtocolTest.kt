package com.prism.music

import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.together.ClockSync
import com.prism.music.playback.together.Link
import com.prism.music.playback.together.Msg
import com.prism.music.playback.together.Wire
import com.prism.music.playback.together.fromPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Listen Together's wire protocol: what phones send each other, over a real local socket. */
class TogetherProtocolTest {
    private val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf(ArtistRef("Rick Astley", "UC1")), AlbumRef("Whenever You Need Somebody", "MPRE1"), 213, "https://i.ytimg.com/x.jpg")

    @Test
    fun everyMessageRoundTrips() {
        val all = listOf(
            Msg.Hello("Sam", "4821"),
            Msg.Welcome("Alex"),
            Msg.Denied("That code doesn't match"),
            Msg.State(listOf(song, song.copy(id = "abc", title = "Next")), positionMs = 61_234, playing = true, buffering = false, sentAt = 99, title = "Party", guests = listOf("Sam"), canControl = true),
            Msg.Ping(5),
            Msg.Pong(5, 1_000),
            Msg.Add(song, next = true),
            Msg.Added(song, "Sam"),
            Msg.Control("seek", 30_000),
            Msg.Bye("bye"),
        )
        for (m in all) {
            val line = Wire.encode(m)
            assertTrue("one line: $line", '\n' !in line)
            assertEquals(m, Wire.decode(line))
        }
    }

    @Test
    fun strangersAreIgnored() {
        assertNull(Wire.decode("""{"t":"teleport","to":"mars"}"""))
        assertNull(Wire.decode("not json"))
        // Fields a newer Prism might add don't stop an older one reading the message.
        assertEquals(Msg.Ping(7), Wire.decode("""{"t":"ping","t0":7,"extra":true}"""))
    }

    @Test
    fun overlongLinesAreSkipped() {
        val huge = "x".repeat(Wire.MAX_LINE + 10)
        val input = (huge + "\n" + Wire.encode(Msg.Ping(1)) + "\n").toByteArray()
        val reader = com.prism.music.playback.together.LineReader(ByteArrayInputStream(input))
        assertEquals(Wire.encode(Msg.Ping(1)), reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun hostAndGuestTalkOverASocket() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val hostSide = CompletableFuture.supplyAsync {
                Link(server.accept()).use { link ->
                    val hello = link.read() as Msg.Hello
                    link.send(Msg.Welcome("Host"))
                    link.send(Msg.State(listOf(song), 10_000, playing = true, sentAt = 50_000))
                    // Answers pings in its own clock, here 5 s ahead of the guest's.
                    val ping = link.read() as Msg.Ping
                    link.send(Msg.Pong(ping.t0, ping.t0 + 5_000))
                    hello
                }
            }
            Link(Socket(InetAddress.getLoopbackAddress(), server.localPort)).use { guest ->
                guest.send(Msg.Hello("Guest", "1234"))
                assertEquals(Msg.Welcome("Host"), guest.read())
                val state = guest.read() as Msg.State
                assertEquals(song, state.songs.first())
                val clock = ClockSync()
                val t0 = 1_000L
                guest.send(Msg.Ping(t0))
                clock.onPong(guest.read() as Msg.Pong, t1 = t0 + 20)
                // Host clock = guest + 5000, give or take half the 20 ms round trip.
                assertTrue("offset ${clock.offset}", clock.offset in 4_980..5_000)
            }
            assertEquals(Msg.Hello("Guest", "1234"), hostSide.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun playheadIsCarriedForwardOnlyWhilePlaying() {
        val clock = ClockSync()
        clock.onPong(Msg.Pong(t0 = 0, hostTime = 1_000), t1 = 0) // host is 1 s ahead, no delay
        val playing = Msg.State(listOf(song), positionMs = 30_000, playing = true, sentAt = 2_000)
        // Guest time 1_500 = host time 2_500: half a second after the state was sent.
        assertEquals(30_500, clock.hostPosition(playing, now = 1_500))
        assertEquals(30_000, clock.hostPosition(playing.copy(playing = false), now = 1_500))
        assertEquals(30_000, clock.hostPosition(playing.copy(buffering = true), now = 1_500))
    }

    @Test
    fun songsFromOtherPhonesAreChecked() {
        assertEquals(song, song.fromPeer())
        assertNull("not a YouTube id", song.copy(id = "../../etc/passwd").fromPeer())
        assertNull("pictures only from YouTube", song.copy(thumbnail = "http://192.168.1.1/admin").fromPeer()?.thumbnail)
        assertNull(song.copy(thumbnail = "https://evil.example/ytimg.com.png").fromPeer()?.thumbnail)
        assertEquals("https://lh3.googleusercontent.com/a=w544", song.copy(thumbnail = "https://lh3.googleusercontent.com/a=w544").fromPeer()?.thumbnail)
        assertEquals(300, song.copy(title = "x".repeat(5_000)).fromPeer()!!.title.length)
    }

    @Test
    fun theQuickestRoundTripWins() {
        val clock = ClockSync()
        clock.onPong(Msg.Pong(t0 = 0, hostTime = 5_100), t1 = 400) // slow: 400 ms round trip
        clock.onPong(Msg.Pong(t0 = 1_000, hostTime = 6_010), t1 = 1_020) // quick: 20 ms
        clock.onPong(Msg.Pong(t0 = 2_000, hostTime = 7_300), t1 = 2_500) // slow again: ignored
        assertEquals(5_000, clock.offset)
    }
}

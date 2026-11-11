package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.connect.CastState
import com.prism.music.connect.FollowState
import com.prism.music.connect.HLink
import com.prism.music.connect.HMsg
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.connect.LinkKeys
import com.prism.music.connect.PrismDevice
import com.prism.music.connect.TvAct
import com.prism.music.connect.TvWhere
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The owner (1.8.0): "When I handoff to the TV it just paused the phone app and doesn't sync the time or new songs with
 * the TV at all." Playing on a Prism TV (this test plays the TV, over a local connection) is like casting: the phone
 * stays connected and muted, a skip on the phone reaches the TV, a pause on the TV shows on the phone, Stop brings the
 * music back here playing, and a lost connection pauses here and says so. The songs are silent tones.
 */
@RunWith(AndroidJUnit4::class)
class PhoneTvPlayAlongTest {
    /** The TV's side, as the test plays it: what it's sent, and its connection, to say things back on. */
    private class Tv : HandoffLocal {
        override val name = "Test TV"
        override val device = "test-tv-along"
        override val isTv = true
        val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
        val states = CopyOnWriteArrayList<FollowState>()
        @Volatile var link: HLink? = null
        @Volatile var endedBack: Boolean? = null
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        /** The code it shows a phone pairing with it; null: not taking pairings. */
        @Volatile var code: String? = null
        override fun pairingCode(from: String): String? = code
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun followStart(from: String): String? = null
        override fun follow(link: HLink, first: FollowState, from: String) {
            endedBack = null
            states += first
            this.link = link
            while (true) {
                when (val m = link.read() ?: break) {
                    is HMsg.FollowNow -> states += m.state
                    is HMsg.FollowEnd -> { endedBack = m.back; break }
                    else -> Unit
                }
            }
        }
        val playing: String? get() = states.lastOrNull()?.let { s -> s.songs.getOrNull(s.index)?.id }
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val tones = ArrayList<android.net.Uri>()

    @After fun cleanUp() {
        TestHooks.prismTvSong = null
        runCatching { instrumentation.runOnMainSync { c.cast.disconnect(); c.player.stop() } }
        runCatching { server.close() }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    private fun waitFor(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $ms ms: $what")
            Thread.sleep(100)
        }
    }

    private fun <T> main(block: () -> T): T { var r: T? = null; instrumentation.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    @Test fun theTvPlaysAlongWithThePhoneBothWaysAndStopBringsItBack() {
        Fixture.ready()
        // Silent, and the phone kept muted.
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        // The tones are files on this phone: here they stand in for YouTube songs, which a TV would get.
        TestHooks.prismTvSong = { it }
        val tv = Tv()
        val key = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        tv.keys[c.handoff.device] = key
        c.handoff.savePairing(tv.device, tv.name, key)
        thread { while (!server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break; thread { HLink(s).use { HandoffPeer(tv).serve(it) } } } }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val titles = listOf("Prism along one", "Prism along two", "Prism along three")
        titles.forEach { tones += Fixture.tone(it, 120) }
        runBlocking { c.localMusic.scan() }
        val songs = titles.map { t -> c.localMusic.tracks.value.first { it.song.title == t }.song }
        instrumentation.runOnMainSync { c.player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Play along test")) }
        waitFor(20_000, "playing") { c.player.isPlaying.value }
        val device = PrismDevice(tv.name, "127.0.0.1", server.localPort, tv.device, "test", paired = true, tv = true)

        // Moved to the TV: the phone shows it as playing there, stays connected, its own sound muted.
        instrumentation.runOnMainSync { c.cast.connectTv(device) }
        waitFor(15_000, "playing on the TV") { c.cast.state.value is CastState.Casting && tv.states.isNotEmpty() }
        assertEquals("Test TV", (c.cast.state.value as CastState.Casting).renderer.name)
        assertEquals(songs[0].id, tv.playing)
        assertTrue("the TV starts where the phone was", tv.states.first().seekTo >= 0)
        // And is told where the phone is once the connection is up (the first state was made before it).
        waitFor(5_000, "the state sent once connected") { tv.states.size >= 2 && tv.states[1].seekTo >= 0 }
        assertEquals(0f, main { c.player.player!!.volume })

        // A skip on the phone reaches the TV.
        instrumentation.runOnMainSync { c.player.next() }
        waitFor(5_000, "the TV on the next song") { tv.playing == songs[1].id }

        // Paused on the TV (its remote): the phone shows it paused; played again, playing.
        tv.link!!.send(HMsg.Act(TvAct(TvAct.PAUSE)))
        waitFor(5_000, "paused on the phone") { !main { c.player.playWhenReady } }
        waitFor(5_000, "the TV told it's paused") { tv.states.last().playing == false }
        tv.link!!.send(HMsg.Act(TvAct(TvAct.PLAY)))
        waitFor(5_000, "playing on the phone") { main { c.player.playWhenReady } }
        // Where the TV is, the phone's playhead goes (it's what's heard), once a moment has passed since the last command.
        Thread.sleep(2_000)
        tv.link!!.send(HMsg.Where(TvWhere(songs[1].id, 50_000, playing = true, volume = 35, seq = tv.states.last().seq)))
        waitFor(6_000, "the phone at the TV's place") { main { c.player.position } in 49_000..58_000 }
        assertEquals(35, c.cast.volume)

        // Stop: the music comes back to the phone, playing, its sound back on.
        instrumentation.runOnMainSync { c.cast.disconnect(keepPlaying = false) }
        waitFor(5_000, "the TV told the music came back") { tv.endedBack == true }
        assertTrue(c.cast.state.value is CastState.Idle)
        assertTrue("still playing here", main { c.player.playWhenReady })
        assertEquals(1f, main { c.player.player!!.volume })

        // Again, then the TV goes (switched off): the phone pauses where it was, and says so.
        instrumentation.runOnMainSync { c.cast.connectTv(device) }
        waitFor(15_000, "playing on the TV again") { c.cast.state.value is CastState.Casting && tv.endedBack == null && tv.link != null }
        Thread.sleep(500)
        tv.link!!.close()
        waitFor(10_000, "the session ended") { c.cast.state.value is CastState.Idle }
        assertTrue("paused here", !main { c.player.playWhenReady })
        c.handoff.forget(tv.device)
    }

    /** The 1.8.0 review (6): stopped while the TV's pairing code is being asked for, the ask goes away and nothing connects. */
    @Test fun stoppingWhileTheCodeIsAskedForLetsItGo() {
        Fixture.ready()
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        TestHooks.prismTvSong = { it }
        // Not paired: it asks for a code.
        val tv = Tv().apply { code = "123456" }
        c.handoff.forget(tv.device)
        thread { while (!server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break; thread { HLink(s).use { HandoffPeer(tv).serve(it) } } } }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism along code", 120)
        runBlocking { c.localMusic.scan() }
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism along code" }.song
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Play along test")) }
        waitFor(20_000, "playing") { c.player.isPlaying.value }
        val device = PrismDevice(tv.name, "127.0.0.1", server.localPort, tv.device, "test", paired = false, tv = true)
        instrumentation.runOnMainSync { c.cast.connectTv(device) }
        waitFor(10_000, "the code asked for") { c.handoff.askingCodeFor.value != null }
        instrumentation.runOnMainSync { c.cast.disconnect() }
        waitFor(5_000, "the ask gone") { c.handoff.askingCodeFor.value == null }
        assertTrue(c.cast.state.value is CastState.Idle)
        Thread.sleep(1_000)
        assertTrue("nothing connected", c.cast.state.value is CastState.Idle && tv.states.isEmpty())
        assertEquals(null, c.handoff.secretFor(tv.device))
    }
}

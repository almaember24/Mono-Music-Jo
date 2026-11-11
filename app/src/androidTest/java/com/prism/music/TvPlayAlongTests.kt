package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.connect.FollowState
import com.prism.music.connect.HLink
import com.prism.music.connect.HMsg
import com.prism.music.connect.HandoffExpect
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffRequest
import com.prism.music.connect.HandoffResult
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.connect.LinkKeys
import com.prism.music.connect.TvAct
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The TV's half of playing along with a phone (1.8.0; PhoneTvPlayAlongTest is the phone's): the real TV app plays what
 * the phone (this test, over a local connection) sends, from where the phone was, moves when the phone moves, says
 * where it is, says what's done on it (a pause, a skip), and pauses when the phone takes the music back. The songs are
 * silent tones.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvPlayAlongTests {
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val tones = ArrayList<android.net.Uri>()
    private val player get() = Fixture.container.player

    /** The phone's side, as the test plays it. */
    private class Phone : HandoffLocal {
        override val name = "Test phone"
        override val device = "test-phone-along"
        val keys = ConcurrentHashMap<String, String>()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
    }

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
    }

    @After fun cleanUp() {
        TestHooks.prismTvSong = null
        runCatching { instrumentation.runOnMainSync { player.stop() } }
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

    @Test fun theTvPlaysAlongWithAPhone() {
        // The tones are files on this TV: here they stand in for YouTube songs, which a phone would send.
        TestHooks.prismTvSong = { it }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val titles = listOf("Prism TV along one", "Prism TV along two", "Prism TV along three")
        titles.forEach { tones += Fixture.tone(it, 120) }
        runBlocking { Fixture.container.localMusic.scan() }
        val songs = titles.map { t -> Fixture.container.localMusic.tracks.value.first { it.song.title == t }.song }
        TvFixture.launch()
        val handoff = Fixture.container.handoff
        waitFor(15_000, "listening") { handoff.port != null }
        // Paired already (as after the code): both keep the same key.
        val phone = Phone()
        val key = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        phone.keys[handoff.device] = key
        handoff.savePairing(phone.device, phone.name, key)

        val link = HLink(Socket(InetAddress.getLoopbackAddress(), handoff.port!!))
        val heard = CopyOnWriteArrayList<HMsg>()
        try {
            val first = FollowState(songs, 0, playing = true, seekTo = 10_000, seq = 1)
            assertEquals(HandoffResult.Done(), HandoffPeer(phone).request(link, HandoffRequest.Follow(first), HandoffExpect(device = handoff.device)) { null })
            thread { while (true) heard += link.read() ?: break }
            // It plays the phone's song from where the phone was, and says where it is.
            waitFor(20_000, "playing the phone's song") { player.currentSong.value?.id == songs[0].id && player.isPlaying.value }
            assertTrue("from the phone's place", main { player.position } >= 9_000)
            waitFor(5_000, "where it is") { heard.any { it is HMsg.Where && (it as HMsg.Where).at.songId == songs[0].id } }
            // The phone moves on: so does the TV.
            link.send(HMsg.FollowNow(first.copy(index = 1, seekTo = -1, seq = 2)))
            waitFor(10_000, "on the phone's next song") { player.currentSong.value?.id == songs[1].id }
            // Paused on the TV (its remote): the phone is told.
            Thread.sleep(500)
            instrumentation.runOnMainSync { player.setPlaying(false) }
            waitFor(5_000, "the pause said") { heard.any { (it as? HMsg.Act)?.act?.kind == TvAct.PAUSE } }
            // A song picked on the TV (Next): the phone is told which.
            instrumentation.runOnMainSync { player.next() }
            waitFor(5_000, "the pick said") { heard.any { (it as? HMsg.Act)?.act?.let { a -> a.kind == TvAct.PICK && a.index == 2 && a.songId == songs[2].id } == true } }
            // The phone takes the music back: the TV pauses.
            instrumentation.runOnMainSync { player.setPlaying(true) }
            waitFor(10_000, "playing again") { player.isPlaying.value }
            link.send(HMsg.FollowEnd(back = true))
            waitFor(10_000, "paused when the phone took it back") { !main { player.playWhenReady } }
            assertTrue("no longer following", !Fixture.container.queue.followingHost)
            val volume = heard.filterIsInstance<HMsg.Where>().last().at.volume
            assertTrue("its volume said: $volume", volume in 0..100)
        } finally {
            link.close()
            handoff.forget(phone.device)
        }
    }
}

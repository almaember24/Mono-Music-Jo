package com.prism.music

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.connect.HLink
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffRequest
import com.prism.music.connect.HandoffResult
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.connect.RemoteKey
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
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

/**
 * Prism TV, slice 6: a phone pairing with this TV by the code on its screen, signing it in, and being its remote, over
 * the encrypted link to the real TV app. (The phone end is this test, over a local connection: this PC runs one
 * emulator at a time, so the two-emulator run is split into this and PhoneTvRemoteTest on a phone.)
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvLinkTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var tone: android.net.Uri? = null

    /** The phone's side, as the test plays it. */
    private class Phone : HandoffLocal {
        override val name = "Test phone"
        override val device = "test-phone-1"
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
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        runBlocking { Fixture.container.settings.fromBackup(emptyMap(), settings = false, accounts = true) }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $timeoutMs ms")
            Thread.sleep(100)
        }
    }

    private fun call(phone: Phone, request: HandoffRequest, code: String? = null): HandoffResult {
        val port = Fixture.container.handoff.port!!
        return HLink(Socket(InetAddress.getLoopbackAddress(), port)).use { HandoffPeer(phone).request(it, request) { code } }
    }

    /**
     * The release check (1.8.0): a song's link opened on the TV showed "One moment" and went back, the song never put
     * on (its play was cancelled halfway). Now the player opens on it.
     */
    @Test fun aSongLinkOpensThePlayerOnIt() {
        val id = "dQw4w9WgXcQ"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://youtu.be/$id"))
            .setClass(Fixture.context, TvActivity::class.java)
        androidx.test.core.app.ActivityScenario.launch<TvActivity>(intent)
        val player = Fixture.container.player
        waitFor(20_000) { player.currentSong.value?.id == id }
    }

    @Test fun aPhonePairsSignsInAndIsTheRemote() {
        TvFixture.launch()
        val handoff = Fixture.container.handoff
        waitFor(15_000) { handoff.port != null }
        val phone = Phone()
        // With the pairing screen closed, a phone can't pair.
        assertTrue(call(phone, HandoffRequest.Pair, "123456") is HandoffResult.Failed)
        assertTrue(phone.keys.isEmpty())
        // Open: a wrong try, and the code on screen is a new one (each failed try tells a caller a little of the code).
        val code = handoff.openPairing()
        assertTrue(call(phone, HandoffRequest.Pair, if (code == "111111") "222222" else "111111") is HandoffResult.Failed)
        val shown = handoff.tvCode.value
        assertTrue("a new code: $code → $shown", shown != null && shown != code)
        // The code on screen pairs, and then the TV takes no more pairings.
        assertTrue(call(phone, HandoffRequest.Pair, shown) is HandoffResult.Done)
        assertEquals(null, handoff.tvCode.value)
        // Paired: the sign-in comes over.
        val r = call(phone, HandoffRequest.SignIn(mapOf("cookie" to "s:SAPISID=test-sapisid; SID=test-sid", "account_name" to "s:Test Listener"), "listener@example.com"))
        assertEquals(HandoffResult.Done(), r)
        handoff.closePairing()
        waitFor(10_000) { Fixture.container.settings.current.isLoggedIn }
        assertEquals("Test Listener", Fixture.container.settings.current.accountName)

        // The remote: a silent song playing on the TV, paused and played from the "phone".
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism TV link tone", 90)
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism TV link tone" }.song
        val player = Fixture.container.player
        instrumentation.runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "TV link test")) }
        waitFor(20_000) { player.isPlaying.value }
        assertEquals(HandoffResult.Done(), call(phone, HandoffRequest.Remote(RemoteKey.PAUSE)))
        waitFor(5_000) { !player.isPlaying.value }
        val now = call(phone, HandoffRequest.Now) as HandoffResult.Playing
        assertEquals("Prism TV link tone", now.now?.title)
        assertEquals(false, now.now?.playing)
    }
}

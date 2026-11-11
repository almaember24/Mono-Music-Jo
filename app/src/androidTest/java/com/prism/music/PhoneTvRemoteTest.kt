package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.connect.HLink
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.connect.LinkKeys
import com.prism.music.connect.PrismDevice
import com.prism.music.connect.RemoteKey
import com.prism.music.connect.RemoteNow
import com.prism.music.ui.components.TvRemoteSheet
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Prism TV, slice 6, the phone's half: a TV (this test, over a local connection) in the phone's TV sheet, the phone as
 * its remote, and the phone's sign-in sent only after the listener says yes. (With TvLinkTests on a TV emulator, this
 * stands in for a two-emulator run: this PC runs one emulator at a time.)
 */
@RunWith(AndroidJUnit4::class)
class PhoneTvRemoteTest {
    @get:Rule val compose = createComposeRule()

    /** The TV's side, as the test plays it. */
    private class Tv : HandoffLocal {
        override val name = "Test TV"
        override val device = "test-tv-1"
        override val isTv = true
        val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pressed = CopyOnWriteArrayList<RemoteKey>()
        val signIns = CopyOnWriteArrayList<Map<String, String>>()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun pairingCode(from: String): String? = null
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun remote(key: RemoteKey, value: Long): String? { pressed += key; return null }
        override fun nowPlaying() = RemoteNow("Glass Horizon", "Mira Vale", null, true, 84_000, 221_000, 30)
        override fun signIn(values: Map<String, String>, account: String, from: String): String? { signIns += values; return null }
    }

    // 127.0.0.1, as the TV's address below (Android's getLoopbackAddress() is IPv6's ::1).
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))

    @After fun cleanUp() {
        runCatching { server.close() }
        runBlocking { Fixture.container.settings.fromBackup(emptyMap(), settings = false, accounts = true) }
    }

    @Test fun thePhoneIsTheTvsRemoteAndSignsItInWhenAsked() {
        Fixture.ready()
        val c = Fixture.container
        val tv = Tv()
        // Already paired (as after the code): both keep the same key.
        val key = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        tv.keys[c.handoff.device] = key
        c.handoff.savePairing(tv.device, tv.name, key)
        // The sheet's first ask (the second connection) is dropped, as when the Wi-Fi blinks: it asks again.
        var connections = 0
        thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                if (++connections == 2) { runCatching { s.close() }; continue }
                thread { HLink(s).use { HandoffPeer(tv).serve(it) } }
            }
        }
        // Signed in on the phone (a made-up account), so "Sign in on this TV" is offered.
        runBlocking { c.settings.fromBackup(mapOf("cookie" to "s:SAPISID=phone-sapisid; SID=phone-sid", "account_name" to "s:Phone Listener"), settings = false, accounts = true) }
        val device = PrismDevice(tv.name, "127.0.0.1", server.localPort, tv.device, "test", paired = true, tv = true)
        // The link itself first, so a failure says why (the sheet only shows it as a toast).
        val first = runBlocking { c.handoff.ask(device, com.prism.music.connect.HandoffRequest.Now) }
        assertTrue("the TV answers what it plays: $first", first is com.prism.music.connect.HandoffResult.Playing)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                PrismTheme(c.settings.current, null) { TvRemoteSheet(device) {} }
            }
        }
        // What the TV plays (after the dropped ask), then play/pause pressed on the phone reaches it.
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Glass Horizon")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the dropped ask happened", connections >= 3)
        compose.onNode(hasTestTag("tvRemote/toggle")).performClick()
        compose.waitUntil(10_000) { tv.pressed.isNotEmpty() }
        assertEquals(RemoteKey.TOGGLE, tv.pressed.first())
        // The sign-in: nothing goes until the listener says yes.
        compose.onNode(hasTestTag("tvRemote/signIn")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("tvSignIn/yes")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        assertTrue(tv.signIns.isEmpty())
        compose.onNode(hasTestTag("tvSignIn/yes")).performClick()
        compose.waitUntil(10_000) { tv.signIns.isNotEmpty() }
        assertEquals("s:SAPISID=phone-sapisid; SID=phone-sid", tv.signIns.single()["cookie"])
        // Only the sign-in's own settings went.
        assertTrue(tv.signIns.single().keys.all { it in com.prism.music.connect.Handoff.SIGN_IN_KEYS })
        c.handoff.forget(tv.device)
    }
}

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
import com.prism.music.connect.Lan
import com.prism.music.connect.PairingCode
import com.prism.music.ui.components.HandoffDialogs
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A pairing link (a TV's QR code, 1.8.0): any page or app can open one, so the phone asks first, naming the TV and
 * its address; only on yes does it pair, and the sign-in (with one here) or bringing the listener's Prism is then offered to the TV that proved itself. A link to
 * this phone itself is ignored.
 */
@RunWith(AndroidJUnit4::class)
class PhoneLinkPairTest {
    @get:Rule val compose = createComposeRule()

    /** The TV's side, as the test plays it, on this phone's own network address. */
    private class Tv : HandoffLocal {
        override val name = "Den TV"
        override val device = "test-tv-link"
        override val isTv = true
        val pairing = PairingCode()
        val keys = ConcurrentHashMap<String, String>()
        val connections = AtomicInteger()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun takesPairing() = pairing.value.value != null
        override fun pairingCode(from: String): String? = pairing.value.value
        override fun pairingEnded(code: String, matched: Boolean) = pairing.ended(code, matched)
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
    }

    private val tv = Tv()
    private var server: ServerSocket? = null

    @After fun cleanUp() {
        runCatching { server?.close() }
        Fixture.container.handoff.answerLinkPair(false)
        Fixture.container.handoff.askSignIn(null)
        Fixture.container.handoff.askBring(null)
        Fixture.container.handoff.forget(tv.device)
    }

    private fun listen(at: InetAddress): Int {
        val ss = ServerSocket(0, 10, at)
        server = ss
        thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                tv.connections.incrementAndGet()
                thread { HLink(s).use { HandoffPeer(tv).serve(it) } }
            }
        }
        return ss.localPort
    }

    private fun link(host: String, port: Int, code: String) =
        android.net.Uri.parse("prism://pair?h=$host&p=$port&n=Den%20TV&c=$code&s=1")

    private fun show() {
        val c = Fixture.container
        compose.setContent { CompositionLocalProvider(LocalContainer provides c) { PrismTheme(c.settings.current, null) { HandoffDialogs() } } }
    }

    @Test fun aScannedLinkAsksFirstThenPairsAndOffersTheNextStep() {
        Fixture.ready()
        val own = Lan.ownAddress()
        assumeTrue("needs a network address", own != null)
        val port = listen(InetAddress.getByName(own))
        val code = tv.pairing.open()
        show()
        val handoff = Fixture.container.handoff

        // Cancel: nothing reaches the TV.
        handoff.pairFromLink(link(own!!, port, code))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("linkPair/yes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(own, substring = true)).assertExists()
        compose.onNode(hasText("Cancel")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("linkPair/yes")).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(500)
        assertEquals(0, tv.connections.get())

        // Yes: paired, then the next step is offered to that TV. This phone isn't signed in (the test's fresh install),
        // so there's no sign-in to give (the link's s=1 notwithstanding): bringing the listener's Prism there instead.
        handoff.pairFromLink(link(own, port, code))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("linkPair/yes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("linkPair/yes")).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("tvBring/yes")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no sign-in offered without one", compose.onAllNodes(hasTestTag("tvSignIn/yes")).fetchSemanticsNodes().isEmpty())
        assertTrue(tv.keys.containsKey(handoff.device))
        assertEquals(tv.keys[handoff.device], handoff.secretFor(tv.device))
        handoff.askBring(null)
    }

    @Test fun aLinkToThisPhoneItselfIsIgnored() {
        Fixture.ready()
        val port = listen(InetAddress.getByName("127.0.0.1"))
        val code = tv.pairing.open()
        show()
        for (host in listOf("127.0.0.1", "localhost", "0.0.0.0", "8.8.8.8")) Fixture.container.handoff.pairFromLink(link(host, port, code))
        Thread.sleep(2_000)
        assertEquals(null, Fixture.container.handoff.linkPair.value)
        assertTrue(compose.onAllNodes(hasTestTag("linkPair/yes")).fetchSemanticsNodes().isEmpty())
        assertEquals(0, tv.connections.get())
    }
}

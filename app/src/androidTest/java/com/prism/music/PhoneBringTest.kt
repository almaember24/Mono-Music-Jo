package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.connect.HLink
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.connect.LinkKeys
import com.prism.music.connect.PrismDevice
import com.prism.music.data.Backup
import com.prism.music.data.BringContent
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
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * "Bring your Prism" (1.8.0), the phone's half: in the phone's TV sheet, "Bring my Prism" asks first, and only after
 * "Bring them" does the phone's listening reach a test TV (over a real local link, sealed): all its likes and plays,
 * none of its files, and never the sign-in. The TV's half (the merge) is BringOverTest's on the PC.
 */
@RunWith(AndroidJUnit4::class)
class PhoneBringTest {
    @get:Rule val compose = createComposeRule()

    /** The TV's side, as the test plays it: what arrives is read back with the real reader and kept. */
    private class Tv : HandoffLocal {
        override val name = "Test TV"
        override val device = "test-tv-bring"
        override val isTv = true
        val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
        val arrived = CopyOnWriteArrayList<BringContent>()
        val raw = CopyOnWriteArrayList<String>()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun pairingCode(from: String): String? = null
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun bringStarting(from: String, size: Long): String? = null
        override fun bringArrived(file: File, from: String): String? {
            raw += java.util.zip.ZipInputStream(file.inputStream()).use { z -> buildString { while (true) { z.nextEntry ?: break; append(z.readBytes().decodeToString()) } } }
            val content = file.inputStream().buffered().use { Backup.readBring(it) } ?: return "not a bundle"
            arrived += content
            return null
        }
    }

    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))

    @After fun cleanUp() {
        runCatching { server.close() }
        runBlocking { Fixture.container.settings.fromBackup(emptyMap(), settings = false, accounts = true) }
    }

    @Test fun thePhonesPrismGoesToTheTvOnlyAfterTheListenerSaysYes() {
        Fixture.ready()
        val c = Fixture.container
        val tv = Tv()
        val key = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        tv.keys[c.handoff.device] = key
        c.handoff.savePairing(tv.device, tv.name, key)
        thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread { HLink(s).use { HandoffPeer(tv).serve(it) } }
            }
        }
        // Signed in on the phone (a made-up account): the sign-in must not travel with the listening.
        runBlocking { c.settings.fromBackup(mapOf("cookie" to "s:SAPISID=phone-sapisid-bring; SID=phone-sid", "account_name" to "s:Phone Listener"), settings = false, accounts = true) }
        val likes = runBlocking { c.db.songs().liked().count { !it.id.startsWith("local:") } }
        val plays = runBlocking { c.db.plays().events(0, Long.MAX_VALUE).count { !it.songId.startsWith("local:") } }
        val device = PrismDevice(tv.name, "127.0.0.1", server.localPort, tv.device, "test-bring", paired = true, tv = true)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                PrismTheme(c.settings.current, null) { TvRemoteSheet(device) {} }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("tvRemote/bring")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("tvRemote/bring")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("tvBring/yes")).fetchSemanticsNodes().isNotEmpty() }
        // Asked, not answered: nothing has gone.
        Thread.sleep(800)
        assertTrue(tv.arrived.isEmpty())
        compose.onNode(hasTestTag("tvBring/yes")).performClick()
        compose.waitUntil(60_000) { tv.arrived.isNotEmpty() }
        compose.waitUntil(10_000) { c.handoff.bringing.value?.done == true }
        val got = tv.arrived.single()
        assertEquals(likes, got.history.songs.count { it.liked })
        assertEquals(plays, got.history.plays.size)
        assertTrue(got.history.songs.none { it.id.startsWith("local:") })
        // The look only when ticked; the sign-in never.
        assertEquals(null, got.look)
        val all = tv.raw.single()
        for (secret in listOf("phone-sapisid-bring", "SAPISID", "cookie", "Phone Listener")) assertTrue("\"$secret\" went to the TV", !all.contains(secret))
        c.handoff.dismissBringing()
        c.handoff.forget(tv.device)
    }
}

package com.prism.music.connect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The Prism link's security (1.8.0, plan §4): X25519 against RFC 7748's own numbers, pairing by code (a wrong code is
 * refused and nothing is kept), every message sealed (a captured session shows nothing readable), and a replayed or
 * altered message refused.
 */
class LinkSecurityTest {
    private fun unhex(s: String) = LinkKeys.unhex(s)!!

    @Test fun x25519MatchesRfc7748() {
        // RFC 7748 §5.2, the first test vector.
        val out = X25519.scalarMult(
            unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
        )
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", LinkKeys.hex(out))
        // RFC 7748 §6.1: Alice and Bob.
        val alice = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", LinkKeys.hex(X25519.publicKey(alice)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", LinkKeys.hex(X25519.publicKey(bob)))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, LinkKeys.hex(X25519.shared(alice, X25519.publicKey(bob))!!))
        assertEquals(shared, LinkKeys.hex(X25519.shared(bob, X25519.publicKey(alice))!!))
        // A key that would make the secret all zeros (a point of small order) is refused.
        assertNull(X25519.shared(alice, ByteArray(32)))
    }

    @Test fun sealedMessagesOpenOnlyOnceAndInOrder() {
        val a = LinkKeys.nonce() + LinkKeys.nonce()
        val b = LinkKeys.nonce() + LinkKeys.nonce()
        val phone = SealedChannel(sendKey = a, receiveKey = b)
        val tv = SealedChannel(sendKey = b, receiveKey = a)
        val one = phone.seal("first".toByteArray())
        val two = phone.seal("second".toByteArray())
        // Out of order: refused.
        assertNull(tv.open(two))
        assertArrayEquals("first".toByteArray(), tv.open(one))
        // Played again: refused.
        assertNull(tv.open(one))
        // Altered: refused (and the real one still opens after).
        assertNull(tv.open(two.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }))
        assertArrayEquals("second".toByteArray(), tv.open(two))
        // The other way uses the other key.
        assertArrayEquals("back".toByteArray(), phone.open(tv.seal("back".toByteArray())))
    }

    @Test fun eachCodeBitIsCommittedBeforeItsShown() {
        val own = X25519.publicKey(X25519.newPrivateKey())
        val other = X25519.publicKey(X25519.newPrivateKey())
        val n = LinkKeys.nonce()
        val c = Passkey.commit(n, "c", own, other, 1)
        assertTrue(Passkey.opens(c, n, "c", own, other, 1))
        // The wrong bit, another number, other keys or the other side's role don't open it.
        assertTrue(!Passkey.opens(c, n, "c", own, other, 0))
        assertTrue(!Passkey.opens(c, LinkKeys.nonce(), "c", own, other, 1))
        assertTrue(!Passkey.opens(c, n, "c", other, own, 1))
        assertTrue(!Passkey.opens(c, n, "s", own, other, 1))
        // Twenty bits cover every six-digit code.
        assertTrue(999_999 < (1 shl Passkey.ROUNDS))
    }

    // ------------------------------------------------------------ Two Prisms over a real connection

    private class Device(override val name: String, override val device: String, override val isTv: Boolean = false) : HandoffLocal {
        val secrets = ConcurrentHashMap<String, String>()
        @Volatile var code: String? = null
        @Volatile var open = true
        /** A TV's real code: a new one after every try, none once paired (when set, [code] and [open] aren't used). */
        var pairing: PairingCode? = null
        val signIns = CopyOnWriteArrayList<Map<String, String>>()
        val keys = CopyOnWriteArrayList<RemoteKey>()
        override fun secretFor(device: String) = secrets[device]
        override fun savePairing(device: String, name: String, secret: String) { secrets[device] = secret }
        override fun forget(device: String) { secrets.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun takesPairing() = pairing?.let { it.value.value != null } ?: open
        override fun pairingCode(from: String): String? = pairing?.value?.value ?: if (open && pairing == null) code else null
        override fun pairingEnded(code: String, matched: Boolean) = pairing?.ended(code, matched) ?: matched
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun remote(key: RemoteKey, value: Long): String? { keys += key; return null }
        override fun nowPlaying() = RemoteNow("Glass Horizon", "Mira Vale", null, true, 84_000, 221_000, 40)
        override fun signIn(values: Map<String, String>, account: String, from: String): String? { signIns += values; return null }
    }

    /** Everything that crossed the wire, both ways. */
    private class Wire {
        val bytes = ByteArrayOutputStream()
        fun tap(s: Socket): Socket = object : Socket() {
            override fun getInputStream(): InputStream = object : FilterInputStream(s.getInputStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) synchronized(bytes) { bytes.write(it) } }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) synchronized(bytes) { bytes.write(b, off, it) } }
            }
            override fun getOutputStream(): OutputStream = object : FilterOutputStream(s.getOutputStream()) {
                override fun write(b: Int) { synchronized(bytes) { bytes.write(b) }; out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { synchronized(bytes) { bytes.write(b, off, len) }; out.write(b, off, len) }
            }
            override fun setSoTimeout(timeout: Int) { s.soTimeout = timeout }
            override fun getInetAddress(): InetAddress? = s.inetAddress
            override fun close() = s.close()
        }
        override fun toString() = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
    }

    /** [receiver] serving one connection ([servedBy]: a peer kept between connections, for its limits). */
    private fun <T> session(receiver: Device, wire: Wire? = null, servedBy: HandoffPeer? = null, run: (HLink) -> T): T {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val peer = servedBy ?: HandoffPeer(receiver)
        val t = thread { runCatching { server.accept().use { s -> HLink(s).use { peer.serve(it) } } } }
        try {
            val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            return HLink(wire?.tap(socket) ?: socket).use(run)
        } finally {
            t.join(10_000)
            server.close()
        }
    }

    @Test fun aPhoneSignsInATvAndNothingIsReadableOnTheWire() {
        val tv = Device("Living room TV", "tv-1", isTv = true).apply { code = "482913" }
        val phone = Device("Alex's Pixel", "phone-1")
        val cookie = "SAPISID=secret-sapisid-value; SID=very-secret-sid"
        val wire = Wire()
        // Pair (the code typed on the phone), then the sign-in (only ever to a TV paired already).
        val paired = session(tv, wire) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { tv.code } }
        assertEquals(HandoffResult.Done(peer = LinkPeer("tv-1", "Living room TV", tv = true)), paired)
        val r = session(tv, wire) { link -> HandoffPeer(phone).request(link, HandoffRequest.SignIn(mapOf("cookie" to "s:$cookie"), "alex@example.com")) { error("no code") } }
        assertEquals(HandoffResult.Done(), r)
        assertEquals("s:$cookie", tv.signIns.single()["cookie"])
        // Both worked out the same key; it never crossed the wire.
        val key = phone.secrets["tv-1"]
        assertNotNull(key)
        assertEquals(key, tv.secrets["phone-1"])
        val seen = wire.toString()
        assertTrue("the key went across", !seen.contains(key!!))
        // Neither the code nor anything of the sign-in is readable.
        for (secret in listOf("482913", "SAPISID", "secret-sapisid-value", "very-secret-sid", "alex@example.com", "sign_in", "cookie")) {
            assertTrue("\"$secret\" readable on the wire", !seen.contains(secret))
        }

        // Later: the phone as the remote, no code, and what's playing comes back.
        val wire2 = Wire()
        assertEquals(HandoffResult.Done(), session(tv, wire2) { link -> HandoffPeer(phone).request(link, HandoffRequest.Remote(RemoteKey.TOGGLE)) { error("no code") } })
        assertEquals(RemoteKey.TOGGLE, tv.keys.single())
        val now = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Now) { error("no code") } } as HandoffResult.Playing
        assertEquals("Glass Horizon", now.now?.title)
        assertTrue(!wire2.toString().contains("TOGGLE"))
    }

    @Test fun aWrongCodeIsRefusedAndNothingIsKept() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "482913" }
        val phone = Device("Phone", "phone-1")
        val r = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { "482914" } }
        assertTrue("$r", r is HandoffResult.Failed)
        assertTrue(phone.secrets.isEmpty() && tv.secrets.isEmpty())
        // A TV whose pairing screen is closed takes no pairing at all.
        tv.open = false
        val closed = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { "482913" } }
        assertTrue("$closed", closed is HandoffResult.Failed)
        assertTrue(tv.secrets.isEmpty())
    }

    @Test fun aKeyFromBeforeOrMadeUpIsRefused() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "111222" }
        val phone = Device("Phone", "phone-1")
        assertTrue(session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
        // The phone's key swapped for another: the TV refuses it. The phone keeps it (anyone can say "not paired")
        // until it pairs again with the code, which replaces it.
        val madeUp = LinkKeys.hex(LinkKeys.nonce() + LinkKeys.nonce())
        phone.secrets["tv-1"] = madeUp
        assertEquals(HandoffResult.Unpaired, session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Remote(RemoteKey.NEXT)) { null } })
        assertEquals(madeUp, phone.secrets["tv-1"])
        assertTrue(tv.keys.isEmpty())
        val again = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Remote(RemoteKey.NEXT), HandoffExpect(repair = true)) { tv.code } }
        assertEquals(HandoffResult.Done(), again)
        assertEquals(tv.secrets["phone-1"], phone.secrets["tv-1"])
        assertEquals(listOf(RemoteKey.NEXT), tv.keys.toList())
        tv.keys.clear()
        // A version 1 Prism (no keys in its hello) is told both need the latest.
        val old = session(tv) { link ->
            link.send(HMsg.Hello("Old phone", "old-1", version = 1))
            link.read()
        }
        assertTrue("$old", old is HMsg.Denied)
    }

    @Test fun aReplayedSessionGetsNowhere() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "123456" }
        val phone = Device("Phone", "phone-1")
        assertTrue(session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
        // Record a whole remote press, then play the caller's lines back to the TV on a new connection.
        val wire = Wire()
        session(tv, wire) { link -> HandoffPeer(phone).request(link, HandoffRequest.Remote(RemoteKey.NEXT)) { null } }
        assertEquals(1, tv.keys.size)
        val callerLines = wire.toString().lines().filter { it.contains("\"t\":\"hello\"") || it.contains("\"t\":\"auth\"") }
        assertEquals(2, callerLines.size)
        // The hello again (same key and number), then the old proof: the TV's fresh challenge makes the old proof
        // wrong, so it never gets as far as a press.
        val answer = session(tv) { link ->
            callerLines.forEach { line -> link.send(HWire.decode(line)!!) }
            link.read(); link.read()
        }
        assertTrue("$answer", answer is HMsg.Denied)
        assertEquals("the replayed press did nothing", 1, tv.keys.size)
    }

    // ------------------------------------------------------------ The 1.8.0 review's findings

    private fun wrongFor(code: String) = if (code == "111111") "222222" else "111111"

    /** Each failed try gives a caller a bit or two of the code: a TV's code is new after every try, and gone once used. */
    @Test fun aTvsCodeChangesAfterEveryTryAndClosesOnceAPhonePairs() {
        val tv = Device("TV", "tv-1", isTv = true).apply { pairing = PairingCode() }
        val first = tv.pairing!!.open()
        val phone = Device("Phone", "phone-1")
        assertTrue(session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { wrongFor(first) } } is HandoffResult.Failed)
        val second = tv.pairing!!.value.value
        assertNotNull(second)
        assertTrue("a new code after a failed try", second != first)
        // A caller that gave up at the code box ends its try too.
        assertEquals(HandoffResult.Cancelled, session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { null } })
        val third = tv.pairing!!.value.value!!
        assertTrue(third != second)
        // The code on screen now pairs; then the TV takes no more pairings, not even with that code.
        val ok = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { third } }
        assertEquals(HandoffResult.Done(peer = LinkPeer("tv-1", "TV", tv = true)), ok)
        assertNull(tv.pairing!!.value.value)
        val other = Device("Other phone", "phone-2")
        assertTrue(session(tv) { link -> HandoffPeer(other).request(link, HandoffRequest.Pair) { third } } is HandoffResult.Failed)
        assertTrue(other.secrets.isEmpty() && tv.secrets.keys == setOf("phone-1"))
    }

    /** Two tries at once: what one learns while the other fails is about a code that's gone by then. */
    @Test fun aTryThatOutlivesItsCodeGetsNowhere() {
        val tv = Device("TV", "tv-1", isTv = true).apply { pairing = PairingCode() }
        val code = tv.pairing!!.open()
        val asked = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        var slow: HandoffResult? = null
        val t = thread {
            slow = session(tv) { link -> HandoffPeer(Device("Slow", "phone-1")).request(link, HandoffRequest.Pair) { asked.countDown(); go.await(); code } }
        }
        assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(session(tv) { link -> HandoffPeer(Device("Quick", "phone-2")).request(link, HandoffRequest.Pair) { wrongFor(code) } } is HandoffResult.Failed)
        assertTrue(tv.pairing!!.value.value != code)
        // The slow try now answers with the code it was shown: right then, not now.
        go.countDown()
        t.join(10_000)
        assertTrue("$slow", slow is HandoffResult.Failed)
        assertTrue(tv.secrets.isEmpty())
    }

    /** Asking while the TV isn't pairing never uses up the tries a real phone needs once it is. */
    @Test fun askingWhilePairingIsClosedUsesUpNoTries() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "112233"; open = false }
        val peer = HandoffPeer(tv)
        repeat(HandoffPeer.PAIR_ASKS_PER_MINUTE * 3) {
            val r = session(tv, servedBy = peer) { link -> HandoffPeer(Device("Stranger", "s-$it")).request(link, HandoffRequest.Pair) { "112233" } }
            assertTrue("$r", r is HandoffResult.Failed && !r.reason.startsWith("Too many"))
        }
        tv.open = true
        assertTrue(session(tv, servedBy = peer) { link -> HandoffPeer(Device("Phone", "phone-1")).request(link, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
    }

    /**
     * A device's id isn't secret (it's in its announcement on the network): something else answering as the TV and
     * saying "not paired" doesn't make the phone drop the real pairing.
     */
    @Test fun aStrangersNoNeverWipesAPairing() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "135790" }
        val phone = Device("Phone", "phone-1")
        assertTrue(session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
        val key = phone.secrets["tv-1"]
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val t = thread {
            runCatching {
                server.accept().use { s ->
                    HLink(s).use { link ->
                        link.read()
                        link.send(HMsg.Challenge("TV", "tv-1", LinkKeys.hex(LinkKeys.nonce()), LinkKeys.hex(X25519.publicKey(X25519.newPrivateKey())), tv = true))
                        link.read()
                        link.send(HMsg.Denied("Not paired", unpaired = true))
                    }
                }
            }
        }
        val r = HLink(Socket(InetAddress.getLoopbackAddress(), server.localPort)).use { link -> HandoffPeer(phone).request(link, HandoffRequest.Remote(RemoteKey.NEXT)) { null } }
        t.join(5_000); server.close()
        assertEquals(HandoffResult.Unpaired, r)
        assertEquals(key, phone.secrets["tv-1"])
        // The real TV still knows the phone.
        assertEquals(HandoffResult.Done(), session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Remote(RemoteKey.NEXT)) { error("no code") } })
    }

    /** The account goes only to the TV expected, paired before (never one paired on the way), and never to a phone. */
    @Test fun theAccountGoesOnlyToAPairedTvItExpects() {
        val values = mapOf("cookie" to "s:SID=secret")
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "246810" }
        val phone = Device("Phone", "phone-1")
        val unpaired = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.SignIn(values, "a")) { error("no code for a sign-in") } }
        assertTrue("$unpaired", unpaired is HandoffResult.Failed)
        assertTrue(tv.signIns.isEmpty() && tv.secrets.isEmpty())
        assertTrue(session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
        // Someone else where another TV was expected.
        val elsewhere = session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.SignIn(values, "a"), HandoffExpect(device = "tv-2")) { null } }
        assertTrue("$elsewhere", elsewhere is HandoffResult.Failed)
        assertTrue(tv.signIns.isEmpty())
        // A phone, paired or not, takes no account.
        val otherPhone = Device("Other phone", "phone-2").apply { code = "975310" }
        assertTrue(session(otherPhone) { link -> HandoffPeer(phone).request(link, HandoffRequest.Pair) { otherPhone.code } } is HandoffResult.Done)
        assertTrue(session(otherPhone) { link -> HandoffPeer(phone).request(link, HandoffRequest.SignIn(values, "a")) { null } } is HandoffResult.Failed)
        assertTrue(otherPhone.signIns.isEmpty())
        // The TV expected, paired: it goes.
        assertEquals(HandoffResult.Done(), session(tv) { link -> HandoffPeer(phone).request(link, HandoffRequest.SignIn(values, "a"), HandoffExpect(device = "tv-1")) { null } })
        assertEquals(1, tv.signIns.size)
    }

    /** A stranger that connects and sends nonsense, or nothing whole, is let go: it never holds a thread for long. */
    @Test fun aStrangerCantHoldTheLinkOpen() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "123123" }
        val peer = HandoffPeer(tv, handshakeMs = 600)
        fun msUntilLetGo(talk: (Socket) -> Unit): Long {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            val t = thread { runCatching { server.accept().use { s -> HLink(s).use { peer.serve(it) } } } }
            val start = System.currentTimeMillis()
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                s.soTimeout = 10_000
                talk(s)
                runCatching { while (s.getInputStream().read() >= 0) Unit }
            }
            val ms = System.currentTimeMillis() - start
            t.join(5_000); server.close()
            return ms
        }
        // Not a message: let go at once.
        assertTrue(msUntilLetGo { s -> s.getOutputStream().apply { write("not a message\n".toByteArray()); flush() } } < 2_000)
        // A line far longer than any handshake message: let go at once.
        assertTrue(msUntilLetGo { s -> s.getOutputStream().apply { write(ByteArray(HLink.PLAIN_MAX_LINE + 10) { 'a'.code.toByte() }); flush() } } < 2_000)
        // A byte now and then, never a whole line (each read alone would never time out): let go at the deadline.
        val trickled = msUntilLetGo { s ->
            thread { runCatching { repeat(60) { s.getOutputStream().apply { write('{'.code); flush() }; Thread.sleep(100) } } }
        }
        assertTrue("let go after $trickled ms", trickled < 3_000)
    }

    /** Connections are served a few at a time, and a few from one address: the rest are closed, the app carries on. */
    @Test fun onlyAFewLinksAreServedAtOnce() {
        val release = java.util.concurrent.CountDownLatch(1)
        val served = java.util.concurrent.atomic.AtomicInteger()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        try {
            fun accepted(n: Int) = (1..n).map { Socket(InetAddress.getLoopbackAddress(), server.localPort) to server.accept() }
            val hold: (Socket) -> Unit = { served.incrementAndGet(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            // From one address (all of these come from this machine): two at a time.
            val perAddress = LinkGate(max = 10, perAddress = 2, serve = hold)
            assertEquals(listOf(true, true, false), accepted(3).map { (_, s) -> perAddress.take(s) })
            // Three at a time in all.
            val all = LinkGate(max = 3, perAddress = 10, serve = hold)
            val pairs = accepted(4)
            assertEquals(listOf(true, true, true, false), pairs.map { (_, s) -> all.take(s) })
            // The one turned away is closed at once.
            pairs.last().first.soTimeout = 2_000
            assertEquals(-1, pairs.last().first.getInputStream().read())
            release.countDown()
            Thread.sleep(300)
            // Once they're done, there's room again.
            assertTrue(all.take(accepted(1).single().second))
        } finally {
            release.countDown()
            server.close()
        }
    }

    @Test fun aTvsCodeKeepsToItsRules() {
        var n = 0
        val codes = PairingCode { "10000${n++}" }
        assertNull(codes.value.value)
        val a = codes.open()
        assertEquals(false, codes.ended(a, matched = false))
        val b = codes.value.value!!
        assertTrue(a != b)
        // A try with a code no longer in force: refused, and the code in force stays.
        assertEquals(false, codes.ended(a, matched = true))
        assertEquals(b, codes.value.value)
        assertEquals(true, codes.ended(b, matched = true))
        assertNull(codes.value.value)
        // Closed: nothing pairs.
        assertEquals(false, codes.ended(b, matched = true))
    }
}

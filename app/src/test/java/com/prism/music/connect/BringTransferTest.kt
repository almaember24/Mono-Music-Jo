package com.prism.music.connect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
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
 * "Bring your Prism" (1.8.0) over the link: a paired phone's bundle reaches a TV whole, in sealed chunks; nothing goes
 * to a device that isn't the paired TV; an altered chunk (its seal or the whole's hash), a bundle over 64 MB and a
 * dropped connection all leave the TV as it was.
 */
class BringTransferTest {
    private class Device(override val name: String, override val device: String, override val isTv: Boolean = false) : HandoffLocal {
        val secrets = ConcurrentHashMap<String, String>()
        @Volatile var code: String? = null
        /** Bundles merged in here (their bytes). */
        val arrived = CopyOnWriteArrayList<ByteArray>()
        val started = CopyOnWriteArrayList<Long>()
        val ended = CopyOnWriteArrayList<Boolean>()
        @Volatile var lastProgress = 0L
        val scratch = CopyOnWriteArrayList<File>()
        override fun secretFor(device: String) = secrets[device]
        override fun savePairing(device: String, name: String, secret: String) { secrets[device] = secret }
        override fun forget(device: String) { secrets.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun pairingCode(from: String): String? = code
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun bringStarting(from: String, size: Long): String? = if (!isTv) "not a TV" else { started += size; null }
        override fun bringProgress(from: String, got: Long, total: Long) { lastProgress = got }
        override fun bringScratch(): File = File.createTempFile("bring-test", ".zip").also { scratch += it }
        override fun bringArrived(file: File, from: String): String? { arrived += file.readBytes(); return null }
        override fun bringEnded(from: String, ok: Boolean) { ended += ok }
    }

    /** Everything the caller wrote; [flipAt]: the byte at that place is changed on its way (an altered message). */
    private class Tap(val flipAt: Long = -1) {
        val bytes = ByteArrayOutputStream()
        @Volatile var written = 0L
        fun wrap(s: Socket): Socket = object : Socket() {
            override fun getInputStream(): InputStream = s.getInputStream()
            override fun getOutputStream(): OutputStream = object : FilterOutputStream(s.getOutputStream()) {
                override fun write(b: Int) {
                    var v = b
                    if (written == flipAt) v = if (b == 'A'.code) 'B'.code else 'A'.code
                    written++
                    synchronized(bytes) { bytes.write(v) }
                    out.write(v)
                }
                override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }
            }
            override fun setSoTimeout(timeout: Int) { s.soTimeout = timeout }
            override fun getInetAddress(): InetAddress? = s.inetAddress
            override fun close() = s.close()
        }
        override fun toString() = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
    }

    private fun <T> session(receiver: Device, tap: Tap? = null, run: (HLink) -> T): T {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val t = thread { runCatching { server.accept().use { s -> HLink(s).use { HandoffPeer(receiver).serve(it) } } } }
        try {
            val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            return HLink(tap?.wrap(socket) ?: socket).use(run)
        } finally {
            t.join(20_000)
            server.close()
        }
    }

    private fun paired(): Pair<Device, Device> {
        val tv = Device("Living room TV", "tv-1", isTv = true).apply { code = "482913" }
        val phone = Device("Alex's Pixel", "phone-1")
        assertTrue(session(tv) { HandoffPeer(phone).request(it, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
        return tv to phone
    }

    /** A bundle-like payload: [marker] repeated among random bytes, [size] bytes long. */
    private fun payload(size: Int, marker: String = "Glass Horizon by Mira Vale"): ByteArray {
        val r = java.util.Random(7)
        val out = ByteArray(size)
        r.nextBytes(out)
        val m = marker.toByteArray()
        var i = 0
        while (i + m.size < size) { System.arraycopy(m, 0, out, i, m.size); i += 4_096 }
        return out
    }

    private fun source(bytes: ByteArray, sha: String = BringTransfer.sha256(bytes)) = BringSource(bytes.size.toLong(), sha) { bytes.inputStream() }

    @Test fun aPairedPhoneBringsItsPrismToATvInSealedChunks() {
        val (tv, phone) = paired()
        val bytes = payload(700_000)
        val tap = Tap()
        val seen = CopyOnWriteArrayList<Long>()
        val r = session(tv, tap) { HandoffPeer(phone).request(it, HandoffRequest.Bring(source(bytes)) { sent, _ -> seen += sent }, HandoffExpect(device = "tv-1")) { error("no code") } }
        assertEquals(HandoffResult.Done(), r)
        assertArrayEquals(bytes, tv.arrived.single())
        assertEquals(listOf(true), tv.ended.toList())
        assertEquals(bytes.size.toLong(), tv.lastProgress)
        // Progress per chunk on the phone: 0, then each of the three chunks.
        assertEquals(listOf(0L, 262_144L, 524_288L, 700_000L), seen.toList())
        // Nothing readable crossed: not the content, nor what the messages are.
        val wire = tap.toString()
        for (secret in listOf("Glass Horizon", "bring_chunk", "bring_begin", "sha256")) assertTrue("\"$secret\" readable on the wire", !wire.contains(secret))
        // The TV's own copy while it came is gone.
        assertTrue(tv.scratch.none { it.exists() })
    }

    /** How long it takes on loopback (no tap): a typical bundle (70 KB), a big one (8 MB). */
    @Test fun howLongItTakesOnLoopback() {
        val (tv, phone) = paired()
        for (size in listOf(70_000, 8 * 1024 * 1024)) {
            val bytes = payload(size)
            val start = System.nanoTime()
            assertEquals(HandoffResult.Done(), session(tv) { HandoffPeer(phone).request(it, HandoffRequest.Bring(source(bytes))) { null } })
            println("bring: $size bytes in ${BringTransfer.chunksFor(size.toLong())} chunks over loopback in ${(System.nanoTime() - start) / 1_000_000} ms (handshake included)")
        }
        assertEquals(2, tv.arrived.size)
    }

    @Test fun nothingGoesToAPeerThatIsntThePairedTv() {
        val bytes = payload(10_000)
        // A TV this phone never paired with: refused before any code is asked or anything is sent.
        val stranger = Device("Some TV", "tv-9", isTv = true).apply { code = "111222" }
        val phone = Device("Phone", "phone-1")
        val unpaired = session(stranger) { HandoffPeer(phone).request(it, HandoffRequest.Bring(source(bytes))) { error("no code for a bring") } }
        assertTrue("$unpaired", unpaired is HandoffResult.Failed)
        assertTrue(stranger.started.isEmpty() && stranger.arrived.isEmpty() && stranger.secrets.isEmpty())
        // Someone else answering where the paired TV was expected.
        val (tv, me) = paired()
        val elsewhere = session(tv) { HandoffPeer(me).request(it, HandoffRequest.Bring(source(bytes)), HandoffExpect(device = "tv-2")) { null } }
        assertTrue("$elsewhere", elsewhere is HandoffResult.Failed)
        assertTrue(tv.arrived.isEmpty())
        // A phone (paired) takes none.
        val otherPhone = Device("Other phone", "phone-2").apply { code = "975310" }
        assertTrue(session(otherPhone) { HandoffPeer(me).request(it, HandoffRequest.Pair) { otherPhone.code } } is HandoffResult.Done)
        assertTrue(session(otherPhone) { HandoffPeer(me).request(it, HandoffRequest.Bring(source(bytes))) { null } } is HandoffResult.Failed)
        assertTrue(otherPhone.arrived.isEmpty() && otherPhone.started.isEmpty())
        // A TV only takes one from a phone: another TV (paired with it) saying it brings is refused.
        val otherTv = Device("Bedroom TV", "tv-3", isTv = true)
        tv.code = "135790"
        assertTrue(session(tv) { HandoffPeer(otherTv).request(it, HandoffRequest.Pair) { tv.code } } is HandoffResult.Done)
        val fromTv = session(tv) { HandoffPeer(otherTv).request(it, HandoffRequest.Bring(source(bytes))) { null } }
        assertTrue("$fromTv", fromTv is HandoffResult.Failed)
        assertTrue(tv.arrived.isEmpty())
    }

    @Test fun aStrangersBringBeforeProvingAnythingChangesNothing() {
        val tv = Device("TV", "tv-1", isTv = true).apply { code = "123456" }
        val answer = session(tv) { link ->
            link.send(HMsg.BringBegin(10, "00".repeat(32), 1))
            link.read()
        }
        assertEquals(null, answer)
        assertTrue(tv.started.isEmpty() && tv.arrived.isEmpty())
    }

    @Test fun aChunkAlteredOnTheWayIsRefusedAndNothingChanges() {
        val (tv, phone) = paired()
        val bytes = payload(600_000)
        // A byte inside the first chunk's sealed line, changed on its way: its seal no longer opens.
        val r = session(tv, Tap(flipAt = 200_000)) { HandoffPeer(phone).request(it, HandoffRequest.Bring(source(bytes))) { null } }
        assertTrue("$r", r is HandoffResult.Failed)
        assertTrue(tv.arrived.isEmpty())
        assertEquals(listOf(false), tv.ended.toList())
        assertTrue(tv.scratch.none { it.exists() })
    }

    @Test fun aWholeThatDoesntMatchItsHashIsRefusedAndNothingChanges() {
        val (tv, phone) = paired()
        val bytes = payload(300_000)
        val wrong = BringTransfer.sha256(bytes.copyOf().also { it[123] = (it[123] + 1).toByte() })
        val r = session(tv) { HandoffPeer(phone).request(it, HandoffRequest.Bring(source(bytes, wrong))) { null } }
        assertTrue("$r", r is HandoffResult.Failed && r.reason.contains("check out"))
        assertTrue(tv.arrived.isEmpty())
        assertEquals(listOf(false), tv.ended.toList())
    }

    @Test fun moreThan64MbIsRefusedBeforeAnythingIsSent() {
        val (tv, phone) = paired()
        var opened = false
        val huge = BringSource(BringTransfer.MAX_BYTES + 1, "ab".repeat(32)) { opened = true; error("never read") }
        val r = session(tv) { HandoffPeer(phone).request(it, HandoffRequest.Bring(huge)) { null } }
        assertTrue("$r", r is HandoffResult.Failed && r.reason.contains("64"))
        assertTrue(!opened)
        assertTrue(tv.started.isEmpty() && tv.arrived.isEmpty())
        // Exactly 64 MB is still taken (the cap is the size, not one byte under).
        assertEquals(BringTransfer.chunksFor(BringTransfer.MAX_BYTES), 256)
    }

    @Test fun aDroppedConnectionLeavesTheTvAsItWasAndTryingAgainWorks() {
        val (tv, phone) = paired()
        val bytes = payload(900_000)
        var link: HLink? = null
        // The Wi-Fi drops after the first chunk: the phone's reading stops dead.
        val dropping = BringSource(bytes.size.toLong(), BringTransfer.sha256(bytes)) {
            object : InputStream() {
                var at = 0
                override fun read(): Int = error("whole reads only")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= BringTransfer.CHUNK) { link?.close(); throw java.io.IOException("Wi-Fi gone") }
                    val n = minOf(len, bytes.size - at)
                    System.arraycopy(bytes, at, b, off, n); at += n
                    return n
                }
            }
        }
        val r = session(tv) { l -> link = l; runCatching { HandoffPeer(phone).request(l, HandoffRequest.Bring(dropping)) { null } }.getOrElse { HandoffResult.Failed("dropped") } }
        assertTrue("$r", r is HandoffResult.Failed)
        assertTrue("nothing merged", tv.arrived.isEmpty())
        assertEquals(listOf(false), tv.ended.toList())
        assertTrue(tv.scratch.none { it.exists() })
        // Again, the whole way: it comes.
        assertEquals(HandoffResult.Done(), session(tv) { HandoffPeer(phone).request(it, HandoffRequest.Bring(source(bytes))) { null } })
        assertArrayEquals(bytes, tv.arrived.single())
    }
}

package com.prism.music.connect

import com.prism.music.data.model.Song
import com.prism.music.playback.together.fromPeer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom

/**
 * What's playing, as it moves from one Prism to another: the whole queue, the song it's on and where, play order
 * (shuffled or not) and repeat. [order] lists queue indices in the order they play (empty when not shuffled).
 */
@Serializable
data class HandoffSnapshot(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val playing: Boolean,
    val shuffle: Boolean = false,
    val order: List<Int> = emptyList(),
    val repeat: Int = 0,
    val title: String = "",
    /** Songs the sender left out before sending (files on it: their names never leave it). */
    val left: Int = 0,
) {
    /**
     * Made safe to play here: only YouTube songs ([fromPeer]; files on the other phone can't come along), with the
     * index and play order moved to match. Null when there's nothing left, or the song playing was one that can't come.
     */
    fun cleaned(): HandoffSnapshot? {
        val keep = songs.mapIndexedNotNull { i, s -> s.fromPeer()?.let { i to it } }.take(MAX_SONGS)
        val newIndex = HashMap<Int, Int>()
        keep.forEachIndexed { n, (old, _) -> newIndex[old] = n }
        val at = newIndex[index] ?: return null
        val playOrder = if (shuffle) order.mapNotNull { newIndex[it] }.distinct() else emptyList()
        // The order has to cover the whole queue exactly once; if it doesn't (an older Prism, a bad message), queue order.
        val orderOk = playOrder.size == keep.size
        return copy(
            songs = keep.map { it.second },
            index = at,
            positionMs = positionMs.coerceAtLeast(0),
            shuffle = shuffle && orderOk,
            order = if (shuffle && orderOk) playOrder else emptyList(),
            repeat = repeat.coerceIn(0, 2),
            title = title.take(200),
        )
    }

    /** How many songs the queue had that couldn't come (files on the other phone). */
    fun leftOut(cleaned: HandoffSnapshot): Int = songs.size - cleaned.songs.size + left.coerceIn(0, 100_000)

    companion object { const val MAX_SONGS = 2_000 }
}

/** What a TV is playing, for a phone being its remote. */
@Serializable
data class RemoteNow(
    val title: String,
    val artist: String,
    val thumbnail: String? = null,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** The TV's volume, 0–100. */
    val volume: Int = 0,
)

/** The phone-as-remote's buttons. */
enum class RemoteKey { TOGGLE, PLAY, PAUSE, NEXT, PREVIOUS, SEEK_TO, SEEK_BY, VOLUME_UP, VOLUME_DOWN }

/**
 * Playing on a Prism TV from the phone (1.8.0), what the phone sends: the songs around the one playing in play order
 * ([songs]: the one before, then [index], the one playing, then what plays next), whether it plays, and a place to go to
 * ([seekTo], -1: none). [index] is -1 when the song playing can't go to the TV (a file on the phone). [seq] counts up.
 */
@Serializable
data class FollowState(
    val songs: List<Song>,
    val index: Int,
    val playing: Boolean,
    val seekTo: Long = -1,
    val seq: Long = 0,
    val repeatOne: Boolean = false,
    val title: String = "",
    /**
     * The last of the TV's own [TvAct]s ([TvAct.n]) the phone had taken in when it sent this: one sent before the
     * TV's latest act is out of date and the TV doesn't take it.
     */
    val acts: Long = 0,
)

/** What the TV says back, about once a second: the song it's on and where, as of when it sent it. */
@Serializable
data class TvWhere(
    val songId: String?,
    val positionMs: Long,
    val playing: Boolean,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    /** The TV's volume, 0–100. */
    val volume: Int = 0,
    /** The last [FollowState.seq] it has taken in. */
    val seq: Long = 0,
)

/**
 * Something done on the TV itself (its remote, its own player, its queue), for the phone to do too: [kind] is one of
 * [PLAY], [PAUSE], [SEEK] ([positionMs]), [PICK] (the song at [index] of the last [FollowState.songs], [songId]) or
 * [REPLACE] (the TV started a queue of its own: [songs] from [index] at [positionMs]).
 */
@Serializable
data class TvAct(
    val kind: String,
    val positionMs: Long = 0,
    val index: Int = -1,
    val songId: String? = null,
    val songs: List<Song> = emptyList(),
    /** Counts the TV's acts up from 1, for the phone to say which it has taken in ([FollowState.acts]). */
    val n: Long = 0,
) {
    companion object {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val SEEK = "seek"
        const val PICK = "pick"
        const val REPLACE = "replace"
    }
}

/**
 * One message per line between two Prisms ([Handoff]). Version 2 (1.8.0): the hello and challenge carry fresh X25519
 * keys; after pairing or proving the pairing, every line is sealed ([SealedChannel]).
 */
@Serializable
sealed class HMsg {
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val device: String, val version: Int = Handoff.VERSION, val key: String = "", val nonce: String = "", val tv: Boolean = false) : HMsg()

    /** The receiving side's answer: who it is, its own fresh key and number. */
    @Serializable @SerialName("challenge")
    data class Challenge(val name: String, val device: String, val nonce: String, val key: String = "", val tv: Boolean = false) : HMsg()

    /** The caller's proof that it holds the pairing's key, for this connection. */
    @Serializable @SerialName("auth")
    data class Auth(val mac: String) : HMsg()

    /** The receiving side's proof back (both prove it: neither can be stood in for). */
    @Serializable @SerialName("auth_ok")
    data class AuthOk(val mac: String) : HMsg()

    @Serializable @SerialName("pair_request")
    data object PairRequest : HMsg()

    /** The receiving side is showing a code: type it on the caller. */
    @Serializable @SerialName("enter_code")
    data object EnterCode : HMsg()

    /** One round of confirming the code: a commitment to a fresh number and one of the code's bits ([Passkey]). */
    @Serializable @SerialName("commit")
    data class Commit(val c: String) : HMsg()

    /** The number behind the last commitment. */
    @Serializable @SerialName("reveal")
    data class Reveal(val n: String) : HMsg()

    /** All twenty rounds held: paired (each side works out the key itself; it's never sent). */
    @Serializable @SerialName("paired")
    data object Paired : HMsg()

    @Serializable @SerialName("ok")
    data object Ok : HMsg()

    @Serializable @SerialName("denied")
    data class Denied(val reason: String, val unpaired: Boolean = false) : HMsg()

    /** Play this here (the caller stops once it's going). */
    @Serializable @SerialName("move")
    data class Move(val snapshot: HandoffSnapshot) : HMsg()

    /** Send me what you're playing (and stop once I've got it). */
    @Serializable @SerialName("pull")
    data object Pull : HMsg()

    @Serializable @SerialName("snapshot")
    data class Snap(val snapshot: HandoffSnapshot) : HMsg()

    /** It's playing on this side now: the other side can stop. */
    @Serializable @SerialName("moved")
    data object Moved : HMsg()

    /** A remote's button ([value]: the place for SEEK_TO, the jump for SEEK_BY, in ms). */
    @Serializable @SerialName("remote")
    data class Remote(val key: RemoteKey, val value: Long = 0) : HMsg()

    /** What are you playing? */
    @Serializable @SerialName("now")
    data object Now : HMsg()

    @Serializable @SerialName("now_is")
    data class NowIs(val now: RemoteNow?) : HMsg()

    /** Songs for the other side's queue: next, or at the end. */
    @Serializable @SerialName("add")
    data class Add(val songs: List<Song>, val next: Boolean) : HMsg()

    /** The phone's YouTube Music sign-in, for the TV (sent only after the listener said so on the phone). */
    @Serializable @SerialName("sign_in")
    data class SignIn(val values: Map<String, String>, val account: String) : HMsg()

    /** A Look, as its code, to use on the other side. */
    @Serializable @SerialName("look")
    data class Look(val code: String) : HMsg()

    /**
     * "Bring your Prism" (1.8.0): a phone's listening, likes and Prism playlists for a TV ([BringTransfer]), [size]
     * bytes in [chunks] chunks, whose SHA-256 is [sha256]. The TV answers Ok (send them) or Denied.
     */
    @Serializable @SerialName("bring_begin")
    data class BringBegin(val size: Long, val sha256: String, val chunks: Int) : HMsg()

    /** Chunk [n] (from 0), in Base64. */
    @Serializable @SerialName("bring_chunk")
    data class BringChunk(val n: Int, val data: String) : HMsg()

    /** The TV has chunk [n]. */
    @Serializable @SerialName("bring_got")
    data class BringGot(val n: Int) : HMsg()

    /** Every chunk sent: the TV checks the whole and merges it in, then answers Ok or Denied. */
    @Serializable @SerialName("bring_end")
    data object BringEnd : HMsg()

    /**
     * Phone → TV (1.8.0): play along with this phone, as a speaker it casts to; the TV answers Ok (or Denied) and the
     * connection stays open for the whole time, both ways ([FollowNow], [Where], [Act], [Volume], [FollowEnd]).
     */
    @Serializable @SerialName("follow_start")
    data class FollowStart(val state: FollowState) : HMsg()

    /** Phone → TV: what plays now. */
    @Serializable @SerialName("follow")
    data class FollowNow(val state: FollowState) : HMsg()

    /** TV → phone: where it is. */
    @Serializable @SerialName("where")
    data class Where(val at: TvWhere) : HMsg()

    /** TV → phone: something done on the TV. */
    @Serializable @SerialName("act")
    data class Act(val act: TvAct) : HMsg()

    /** Phone → TV: its volume, 0–100. */
    @Serializable @SerialName("volume")
    data class Volume(val level: Int) : HMsg()

    /** Either way: playing along is over ([back]: the music goes back to the phone). */
    @Serializable @SerialName("follow_end")
    data class FollowEnd(val back: Boolean = false) : HMsg()
}

object HWire {
    val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(m: HMsg): String = json.encodeToString(HMsg.serializer(), m)
    fun decode(line: String): HMsg? = runCatching { json.decodeFromString(HMsg.serializer(), line) }.getOrNull()
}

/**
 * A connection between two Prisms: [send] and [read] one message per line. Once [channel] is set every line is sealed
 * ("e:" and the sealed message in Base64); after that a line that isn't, or doesn't open, ends the connection. Before
 * it, lines are short handshake messages: a longer one, or one that isn't a message, ends the connection too.
 */
class HLink(private val socket: Socket) : Closeable {
    private val reader = com.prism.music.playback.together.LineReader(socket.getInputStream().buffered(), PLAIN_MAX_LINE).apply { endOnTooLong = true }
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

    /** Sealed lines carry whole queues (2,000 songs, in Base64): longer ones than the handshake's. */
    @Volatile var channel: SealedChannel? = null
        set(value) {
            field = value
            reader.max = if (value != null) SEALED_MAX_LINE else PLAIN_MAX_LINE
        }

    val remote: String get() = socket.inetAddress?.hostAddress ?: "?"

    fun timeout(ms: Int) { runCatching { socket.soTimeout = ms } }

    @Volatile private var closer: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Closes the connection [ms] from now whatever it's doing (a read trickled a byte at a time never times out on its
     * own); replaces the last deadline. 0: none.
     */
    fun deadline(ms: Long) {
        closer?.cancel(false)
        closer = if (ms > 0) DEADLINES.schedule({ close() }, ms, java.util.concurrent.TimeUnit.MILLISECONDS) else null
    }

    fun send(m: HMsg): Boolean = synchronized(writer) {
        val text = HWire.encode(m)
        val line = channel?.let { "e:" + java.util.Base64.getEncoder().encodeToString(it.seal(text.toByteArray())) } ?: text
        runCatching { writer.write(line); writer.write("\n"); writer.flush() }.isSuccess
    }

    /** The next message it understands; null once the connection's gone (or the wait ran out, or a sealed line failed). */
    fun read(): HMsg? {
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return null
            if (line.isBlank()) continue
            val ch = channel ?: return HWire.decode(line)
            if (!line.startsWith("e:")) return null
            val plain = runCatching { java.util.Base64.getDecoder().decode(line.substring(2)) }.getOrNull()?.let(ch::open) ?: return null
            return HWire.decode(plain.decodeToString())
        }
    }

    override fun close() {
        closer?.cancel(false)
        runCatching { socket.close() }
    }

    companion object {
        /** The handshake's lines (keys, numbers, names) are well under this. */
        const val PLAIN_MAX_LINE = 16 * 1024
        const val SEALED_MAX_LINE = 4 * 1024 * 1024
        private val DEADLINES = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "handoff-deadlines").apply { isDaemon = true }
        }
    }
}

object HandoffKeys {
    private val random = SecureRandom()

    fun newCode(): String = (100_000 + random.nextInt(900_000)).toString()
}

/**
 * A TV's pairing code (1.8.0): there only while its pairing screen is open ([open]). Each failed try tells a caller a
 * bit or two of the code, so a new one comes after every try ([ended]); once a phone has paired there is none.
 */
class PairingCode(private val newCode: () -> String = HandoffKeys::newCode) {
    private val _value = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** The code in force (shown on the TV and in its QR code), or null when it isn't taking pairings. */
    val value: kotlinx.coroutines.flow.StateFlow<String?> = _value

    @Synchronized fun open(): String = newCode().also { _value.value = it }
    @Synchronized fun close() { _value.value = null }

    /**
     * A try with [code] is over ([matched]: all its rounds held). True when it may pair: [code] is still in force (a try
     * that outlived its code, while another failed, gets nowhere either).
     */
    @Synchronized fun ended(code: String, matched: Boolean): Boolean {
        val inForce = _value.value == code
        if (inForce) _value.value = if (matched) null else newCode()
        return matched && inForce
    }
}

/**
 * Serves accepted connections ([serve], blocking) on threads of its own, so a slow or junk one never holds up the
 * app's other work: [max] at a time and [perAddress] from one address; more are closed at once.
 */
class LinkGate(private val max: Int, private val perAddress: Int, private val serve: (Socket) -> Unit) {
    private val threads = java.util.concurrent.ThreadPoolExecutor(
        0, max, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    ) { r -> Thread(r, "handoff-link").apply { isDaemon = true } }
    private val from = HashMap<String, Int>()

    /** Serves [s], or closes it (too many already); whether it's served. */
    fun take(s: Socket): Boolean {
        val who = s.inetAddress?.hostAddress ?: "?"
        val ok = synchronized(from) {
            val n = from[who] ?: 0
            (n < perAddress).also { if (it) from[who] = n + 1 }
        }
        if (!ok) { runCatching { s.close() }; return false }
        val done = { synchronized(from) { val n = (from[who] ?: 1) - 1; if (n <= 0) from.remove(who) else from[who] = n } }
        return try {
            threads.execute { try { serve(s) } finally { runCatching { s.close() }; done() } }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            runCatching { s.close() }
            done()
            false
        }
    }
}

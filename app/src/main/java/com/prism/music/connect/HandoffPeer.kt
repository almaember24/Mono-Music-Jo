package com.prism.music.connect

import com.prism.music.R
import com.prism.music.data.model.Song
import com.prism.music.playback.together.fromPeer
import com.prism.music.tr
import com.prism.music.trp

/** This Prism's side of a link: who it is, its pairings, and what it does when asked. */
interface HandoffLocal {
    val name: String
    val device: String
    /** A TV (it says so in its hello: a phone offers it a remote and its sign-in). */
    val isTv: Boolean get() = false
    /** The pairing key shared with [device] (hex), or null. */
    fun secretFor(device: String): String?
    fun savePairing(device: String, name: String, secret: String)
    fun forget(device: String)
    /** Shows [code] on this device for [from] to type in (null: put it away). */
    fun showCode(code: String?, from: String)
    /**
     * The code [from] has to type to pair, shown here; null when this device isn't taking pairings now (a TV only takes
     * them while its pairing screen is open).
     */
    fun pairingCode(from: String): String? = HandoffKeys.newCode().also { showCode(it, from) }
    /** Whether this device takes pairings now (asked before [pairingCode], which shows one). */
    fun takesPairing(): Boolean = true
    /**
     * A pairing attempt with [code] is over ([matched]: all its rounds held). True when it may complete: the code is
     * still the one in force. Each failed try tells a caller a bit or two of the code, so a code that stays on screen
     * (a TV's) is replaced after every attempt, and the TV stops taking pairings once one has paired.
     */
    fun pairingEnded(code: String, matched: Boolean): Boolean = matched
    /** What's playing here, or null. */
    fun snapshot(): HandoffSnapshot?
    /** Plays [s] here; null once it has, else why it couldn't (said to the other device). [from] is the other device's name. */
    fun takeOver(s: HandoffSnapshot, from: String): String?
    /** The other device has it now: fade out and pause. */
    fun stepAside()
    /** A remote's button; null when done, else why not. */
    fun remote(key: RemoteKey, value: Long): String? = tr(R.string.both_devices_need_the_latest_prism)
    /** What's playing here, for a remote. */
    fun nowPlaying(): RemoteNow? = null
    /** Songs from [from] for this queue; null when added, else why not. */
    fun addSongs(songs: List<Song>, next: Boolean, from: String): String? = tr(R.string.both_devices_need_the_latest_prism)
    /** [from]'s YouTube Music sign-in, to use here; null when signed in. */
    fun signIn(values: Map<String, String>, account: String, from: String): String? = tr(R.string.both_devices_need_the_latest_prism)
    /** A Look from [from] to use here; null when it's on. */
    fun useLook(code: String, from: String): String? = tr(R.string.both_devices_need_the_latest_prism)

    /**
     * "Bring your Prism" ([BringTransfer]): [from] (a paired phone) wants to bring [size] bytes here. Null to take it,
     * else why not (only a TV takes one, one at a time).
     */
    fun bringStarting(from: String, size: Long): String? = tr(R.string.both_devices_need_the_latest_prism)
    /** [got] of [total] bytes have come. */
    fun bringProgress(from: String, got: Long, total: Long) = Unit
    /** Where what comes is written while it comes (deleted after, whatever happens). */
    fun bringScratch(): java.io.File = java.io.File.createTempFile("bring", ".zip")
    /** Everything came and checked out: [file] is merged in here. Null once it is, else why not. */
    fun bringArrived(file: java.io.File, from: String): String? = tr(R.string.both_devices_need_the_latest_prism)
    /** The transfer is over: [ok] when it was merged in; otherwise nothing here changed. */
    fun bringEnded(from: String, ok: Boolean) = Unit

    /**
     * Playing along with [from] (a paired phone, 1.8.0): null to start, else why not (only a TV does). Then [follow]
     * runs for as long as the connection lasts.
     */
    fun followStart(from: String): String? = tr(R.string.both_devices_need_the_latest_prism)
    /** Plays along with [from] over [link], from [first] on ([TvFollower]); blocking. */
    fun follow(link: HLink, first: FollowState, from: String) = Unit
}

/** The device at the other end, as it proved itself (a pairing's result). */
data class LinkPeer(val device: String, val name: String, val tv: Boolean)

sealed interface HandoffResult {
    /** [note]: something to say (songs that couldn't come). [peer]: who answered, for a [HandoffRequest.Pair]. */
    data class Done(val note: String? = null, val peer: LinkPeer? = null) : HandoffResult
    data class Failed(val reason: String) : HandoffResult
    /** The key this side had was refused: pair again. */
    data object Unpaired : HandoffResult
    data object Cancelled : HandoffResult
    /** What the other side is playing (for [HandoffRequest.Now]). */
    data class Playing(val now: RemoteNow?) : HandoffResult
}

/** What a caller asks of the other side, once the link is up. */
sealed interface HandoffRequest {
    data object Move : HandoffRequest
    data object Pull : HandoffRequest
    data class Remote(val key: RemoteKey, val value: Long = 0) : HandoffRequest
    data object Now : HandoffRequest
    data class Add(val songs: List<Song>, val next: Boolean) : HandoffRequest
    data class SignIn(val values: Map<String, String>, val account: String) : HandoffRequest
    data class Look(val code: String) : HandoffRequest
    /** "Bring your Prism": [source] for a TV paired already; [progress] = bytes the TV has, of the whole. */
    class Bring(val source: BringSource, val progress: (Long, Long) -> Unit = { _, _ -> }) : HandoffRequest
    /** Only pair (or check the pairing): nothing more. */
    data object Pair : HandoffRequest
    /**
     * A TV playing along with this phone from [first] on (1.8.0): once it's Done, the connection stays open for it, both
     * ways ([TvCast], [TvFollower]), and the caller keeps it.
     */
    data class Follow(val first: FollowState) : HandoffRequest
}

/** What a caller expects of the device it reaches. */
data class HandoffExpect(
    /** Its device id ([PrismDevice.id]): anyone else answering at that address is refused. */
    val device: String? = null,
    /** Pair again even though a key is kept (the other side refused it); the kept key goes only once a new one works. */
    val repair: Boolean = false,
)

/**
 * The two ends of a link over one connection (version 2, 1.8.0). Both say hello with a fresh X25519 key. A caller
 * already paired proves it holds the pairing's key for this connection and the receiver proves it back; a new caller
 * asks to pair: the receiving side shows a six-digit code, the listener types it on the caller, and the code is checked
 * in twenty rounds of commitments ([Passkey]) without ever crossing the wire, the pairing's key worked out on both sides
 * from the key agreement. From then on every message is sealed ([SealedChannel]). Then one request ([HandoffRequest]);
 * for a move, the side giving playback up steps aside only once the other says it's playing.
 */
class HandoffPeer(
    private val local: HandoffLocal,
    /** A stranger's whole handshake ([HANDSHAKE_MS]; shorter in tests). */
    private val handshakeMs: Long = HANDSHAKE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Pairing tries that reached the code, in the last minute, by address. */
    private val pairAsks = HashMap<String, java.util.ArrayDeque<Long>>()

    /**
     * Whether [from] may try a code now: [PAIR_ASKS_PER_MINUTE] tries a minute from one address, [PAIR_ASKS_PER_MINUTE_ALL]
     * from all. Counts the try.
     */
    private fun pairAllowed(from: String): Boolean = synchronized(pairAsks) {
        val now = clock()
        val it = pairAsks.values.iterator()
        while (it.hasNext()) {
            val q = it.next()
            while (q.isNotEmpty() && q.first < now - 60_000) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
        val mine = pairAsks[from]
        if ((mine?.size ?: 0) >= PAIR_ASKS_PER_MINUTE || pairAsks.values.sumOf { it.size } >= PAIR_ASKS_PER_MINUTE_ALL) return false
        pairAsks.getOrPut(from) { java.util.ArrayDeque() }.addLast(now)
        true
    }

    /** Everything both sides said (TV or not too), which every key and proof is bound to. */
    private fun transcript(callerDevice: String, receiverDevice: String, callerKey: String, receiverKey: String, callerNonce: String, receiverNonce: String, callerTv: Boolean, receiverTv: Boolean) =
        "v${Handoff.VERSION}|$callerDevice|$receiverDevice|$callerKey|$receiverKey|$callerNonce|$receiverNonce|${if (callerTv) 1 else 0}${if (receiverTv) 1 else 0}"

    /**
     * The receiving side, for one connection. Blocking. Until the caller has proved its key (or paired) the connection
     * has a deadline as a whole, whatever it sends.
     */
    fun serve(link: HLink) {
        link.deadline(handshakeMs)
        link.timeout(8_000)
        val hello = link.read() as? HMsg.Hello ?: return
        val theirKey = LinkKeys.unhex(hello.key)
        if (hello.version != Handoff.VERSION || theirKey?.size != 32 || hello.nonce.length != 32) {
            link.send(HMsg.Denied(tr(R.string.both_devices_need_the_latest_prism))); return
        }
        val them = hello.name.trim().take(60).ifBlank { tr(R.string.another_device) }
        val mine = X25519.newPrivateKey()
        val myKey = LinkKeys.hex(X25519.publicKey(mine))
        val nonce = LinkKeys.hex(LinkKeys.nonce())
        link.send(HMsg.Challenge(local.name, local.device, nonce, myKey, local.isTv))
        val shared = X25519.shared(mine, theirKey) ?: return
        val t = transcript(hello.device, local.device, hello.key, myKey, hello.nonce, nonce, hello.tv, local.isTv)
        val pairingKey: ByteArray = when (val m = link.read()) {
            is HMsg.Auth -> {
                val key = local.secretFor(hello.device)?.let(LinkKeys::unhex)
                if (key == null || !LinkKeys.same(LinkKeys.proof(key, "c", t).toByteArray(), m.mac.toByteArray())) {
                    Thread.sleep(400)
                    link.send(HMsg.Denied(tr(R.string.isn_t_paired_with_any_more, them, local.name), unpaired = true))
                    return
                }
                key
            }
            HMsg.PairRequest -> {
                val closed = HMsg.Denied(tr(R.string.tv_pairing_closed, local.name))
                // Only tries that get as far as a code count, so asking while pairing is closed never uses them up.
                if (!local.takesPairing()) { link.send(closed); return }
                if (!pairAllowed(link.remote)) { link.send(HMsg.Denied(tr(R.string.too_many_pairing_tries_wait_a))); return }
                val code = local.pairingCode(them) ?: run { link.send(closed); return }
                var ended = false
                try {
                    link.send(HMsg.EnterCode)
                    link.deadline(PAIR_WAIT_MS + handshakeMs)
                    link.timeout(PAIR_WAIT_MS)
                    val matched = confirmCode(link, code, caller = false, own = X25519.publicKey(mine), other = theirKey)
                    ended = true
                    if (!local.pairingEnded(code, matched)) {
                        Thread.sleep(700)
                        link.send(HMsg.Denied(tr(R.string.that_code_didn_t_match_try)))
                        return
                    }
                    val key = LinkKeys.pairingKey(shared, t)
                    local.savePairing(hello.device, them, LinkKeys.hex(key))
                    link.send(HMsg.Paired)
                    link.deadline(handshakeMs)
                    link.timeout(10_000)
                    // The caller now proves the new key, as on any later connection.
                    val proof = link.read() as? HMsg.Auth ?: return
                    if (!LinkKeys.same(LinkKeys.proof(key, "c", t).toByteArray(), proof.mac.toByteArray())) return
                    key
                } finally {
                    if (!ended) local.pairingEnded(code, false)
                    local.showCode(null, them)
                }
            }
            else -> return
        }
        // Proved: the caller is a paired device, no longer a stranger on a deadline.
        link.deadline(0)
        link.send(HMsg.AuthOk(LinkKeys.proof(pairingKey, "s", t)))
        val (toReceiver, toCaller) = LinkKeys.sessionKeys(shared, pairingKey, t)
        link.channel = SealedChannel(sendKey = toCaller, receiveKey = toReceiver)
        link.timeout(20_000)
        when (val cmd = link.read()) {
            is HMsg.Move -> {
                val s = cmd.snapshot.cleaned()
                if (s == null) link.send(HMsg.Denied(tr(R.string.what_s_playing_on_is_only, them, them)))
                else link.send(local.takeOver(s, them)?.let { HMsg.Denied(it) } ?: HMsg.Moved)
            }
            HMsg.Pull -> {
                val s = local.snapshot()
                if (s == null) { link.send(HMsg.Denied(tr(R.string.nothing_s_playing_on, local.name))); return }
                // Only what can play there goes (files here stay, names and all): how many stayed, said with it.
                val clean = s.cleaned() ?: run { link.send(HMsg.Denied(tr(R.string.what_s_playing_on_is_only, local.name, local.name))); return }
                link.send(HMsg.Snap(clean.copy(left = s.leftOut(clean))))
                if (link.read() == HMsg.Moved) local.stepAside()
            }
            is HMsg.Remote -> link.send(local.remote(cmd.key, cmd.value)?.let { HMsg.Denied(it) } ?: HMsg.Ok)
            HMsg.Now -> link.send(HMsg.NowIs(local.nowPlaying()))
            is HMsg.Add -> {
                val songs = cmd.songs.mapNotNull { it.fromPeer() }.take(HandoffSnapshot.MAX_SONGS)
                link.send(if (songs.isEmpty()) HMsg.Denied(tr(R.string.what_s_playing_on_is_only, them, them)) else local.addSongs(songs, cmd.next, them)?.let { HMsg.Denied(it) } ?: HMsg.Ok)
            }
            is HMsg.SignIn -> link.send(local.signIn(cmd.values, cmd.account.take(200), them)?.let { HMsg.Denied(it) } ?: HMsg.Ok)
            is HMsg.Look -> link.send(local.useLook(cmd.code.take(MAX_LOOK_CODE), them)?.let { HMsg.Denied(it) } ?: HMsg.Ok)
            // Only from a paired phone (proved above), and only to a TV ([HandoffLocal.bringStarting]).
            is HMsg.BringBegin -> BringTransfer.receive(link, cmd, local, them, fromTv = hello.tv)
            // Playing along with a paired phone (proved above), for as long as the connection lasts: only a TV does.
            is HMsg.FollowStart -> {
                val why = local.followStart(them)
                if (why != null) { link.send(HMsg.Denied(why)); return }
                link.send(HMsg.Ok)
                local.follow(link, cmd.state, them)
            }
            // Pairing only: done.
            HMsg.Ok -> Unit
            else -> Unit
        }
    }

    /** The calling side of a move or a pull ([command]), as before 1.8.0. */
    fun call(link: HLink, command: HandoffCommand, askCode: (String) -> String?): HandoffResult =
        request(link, if (command == HandoffCommand.MOVE) HandoffRequest.Move else HandoffRequest.Pull, askCode = askCode)

    /**
     * The calling side: [request] of the device at the other end of [link] ([expect]: which device that must be).
     * [askCode] is called (blocking) when they need pairing first, for the code the other device shows; null cancels.
     * A sign-in only goes to a TV this device was paired with before the request. Blocking.
     */
    fun request(link: HLink, request: HandoffRequest, expect: HandoffExpect = HandoffExpect(), askCode: (String) -> String?): HandoffResult {
        // The whole request, the code's wait included (a device that answers a byte at a time can't hold it for ever).
        link.deadline(PAIR_WAIT_MS + REQUEST_MS)
        link.timeout(8_000)
        val mine = X25519.newPrivateKey()
        val myKey = LinkKeys.hex(X25519.publicKey(mine))
        val nonce = LinkKeys.hex(LinkKeys.nonce())
        if (!link.send(HMsg.Hello(local.name, local.device, Handoff.VERSION, myKey, nonce, local.isTv))) return HandoffResult.Failed(tr(R.string.couldn_t_reach_it))
        val ch = when (val m = link.read()) {
            is HMsg.Challenge -> m
            is HMsg.Denied -> return HandoffResult.Failed(m.reason)
            else -> return HandoffResult.Failed(tr(R.string.it_didn_t_answer))
        }
        val them = ch.name.trim().take(60).ifBlank { tr(R.string.the_other_device) }
        val theirKey = LinkKeys.unhex(ch.key)?.takeIf { it.size == 32 } ?: return HandoffResult.Failed(tr(R.string.both_devices_need_the_latest_prism))
        val shared = X25519.shared(mine, theirKey) ?: return HandoffResult.Failed(tr(R.string.it_didn_t_answer))
        // Someone else answering where the expected device was (another device, or one pretending).
        if (expect.device != null && ch.device != expect.device) return HandoffResult.Failed(tr(R.string.didn_t_answer, them))
        val t = transcript(local.device, ch.device, myKey, ch.key, nonce, ch.nonce, local.isTv, ch.tv)
        var pairingKey = if (expect.repair) null else local.secretFor(ch.device)?.let(LinkKeys::unhex)
        // The account goes only to a TV paired already (never to one paired on the way, nor to a phone).
        if (request is HandoffRequest.SignIn) {
            if (!ch.tv) return HandoffResult.Failed(tr(R.string.tv_not_a_tv, them))
            if (pairingKey == null) return HandoffResult.Failed(tr(R.string.tv_sign_in_pair_first, them))
        }
        // So does the listening (1.8.0, "Bring your Prism"): only to a TV this phone was paired with before.
        if (request is HandoffRequest.Bring) {
            if (!ch.tv) return HandoffResult.Failed(tr(R.string.tv_not_a_tv, them))
            if (pairingKey == null) return HandoffResult.Failed(tr(R.string.bring_pair_first, them))
        }
        if (pairingKey == null) {
            link.send(HMsg.PairRequest)
            when (val r = link.read()) {
                HMsg.EnterCode -> Unit
                is HMsg.Denied -> return HandoffResult.Failed(r.reason)
                else -> return HandoffResult.Failed(tr(R.string.didn_t_answer, them))
            }
            link.timeout(PAIR_WAIT_MS)
            val code = askCode(them)?.filter { it.isDigit() }?.takeIf { it.length == 6 } ?: return HandoffResult.Cancelled
            link.timeout(10_000)
            if (!confirmCode(link, code, caller = true, own = X25519.publicKey(mine), other = theirKey)) {
                return HandoffResult.Failed(tr(R.string.that_code_didn_t_match_try))
            }
            when (val r = link.read()) {
                HMsg.Paired -> Unit
                is HMsg.Denied -> return HandoffResult.Failed(r.reason)
                else -> return HandoffResult.Failed(tr(R.string.didn_t_answer, them))
            }
            pairingKey = LinkKeys.pairingKey(shared, t)
            local.savePairing(ch.device, them, LinkKeys.hex(pairingKey))
        }
        link.send(HMsg.Auth(LinkKeys.proof(pairingKey, "c", t)))
        when (val r = link.read()) {
            is HMsg.AuthOk -> if (!LinkKeys.same(LinkKeys.proof(pairingKey, "s", t).toByteArray(), r.mac.toByteArray())) {
                // Whoever answered doesn't hold the pairing's key: not the device it says it is.
                return HandoffResult.Failed(tr(R.string.didn_t_answer, them))
            }
            is HMsg.Denied -> {
                // Not proved by anyone (a stranger can say it): the key stays until a new pairing replaces it.
                if (r.unpaired && !expect.repair) return HandoffResult.Unpaired
                return HandoffResult.Failed(r.reason)
            }
            else -> return HandoffResult.Failed(tr(R.string.didn_t_answer, them))
        }
        val (toReceiver, toCaller) = LinkKeys.sessionKeys(shared, pairingKey, t)
        link.channel = SealedChannel(sendKey = toReceiver, receiveKey = toCaller)
        link.timeout(20_000)
        return when (request) {
            HandoffRequest.Pair -> { link.send(HMsg.Ok); HandoffResult.Done(peer = LinkPeer(ch.device, them, ch.tv)) }
            HandoffRequest.Move -> {
                val s = local.snapshot() ?: return HandoffResult.Failed(tr(R.string.nothing_s_playing_here))
                val clean = s.cleaned() ?: return HandoffResult.Failed(tr(R.string.this_song_is_a_file_on))
                // Only what can play there: files here stay, names and all.
                link.send(HMsg.Move(clean))
                when (val r = link.read()) {
                    HMsg.Moved -> {
                        local.stepAside()
                        HandoffResult.Done(s.leftOut(clean).takeIf { it > 0 }?.let { trp(R.plurals.n_songs_stayed_behind, it) })
                    }
                    is HMsg.Denied -> HandoffResult.Failed(r.reason)
                    else -> HandoffResult.Failed(tr(R.string.didn_t_start_playing, them))
                }
            }
            HandoffRequest.Pull -> {
                link.send(HMsg.Pull)
                when (val r = link.read()) {
                    is HMsg.Snap -> {
                        val clean = r.snapshot.cleaned() ?: return HandoffResult.Failed(tr(R.string.what_s_playing_on_is_only, them, them))
                        local.takeOver(clean, them)?.let { return HandoffResult.Failed(it) }
                        link.send(HMsg.Moved)
                        HandoffResult.Done(r.snapshot.leftOut(clean).takeIf { it > 0 }?.let { trp(R.plurals.n_songs_stayed_on, it, it, them) })
                    }
                    is HMsg.Denied -> HandoffResult.Failed(r.reason)
                    else -> HandoffResult.Failed(tr(R.string.didn_t_answer, them))
                }
            }
            HandoffRequest.Now -> {
                link.send(HMsg.Now)
                (link.read() as? HMsg.NowIs)?.let { HandoffResult.Playing(it.now) } ?: HandoffResult.Failed(tr(R.string.didn_t_answer, them))
            }
            is HandoffRequest.Remote -> answer(link, HMsg.Remote(request.key, request.value), them)
            is HandoffRequest.Add -> answer(link, HMsg.Add(request.songs, request.next), them)
            is HandoffRequest.SignIn -> answer(link, HMsg.SignIn(request.values, request.account), them)
            is HandoffRequest.Look -> answer(link, HMsg.Look(request.code), them)
            is HandoffRequest.Bring -> BringTransfer.send(link, request.source, them, request.progress)
            is HandoffRequest.Follow -> {
                if (!ch.tv) return HandoffResult.Failed(tr(R.string.tv_not_a_tv, them))
                answer(link, HMsg.FollowStart(request.first), them).also {
                    // Open from here on, for as long as the TV plays along (it says where it is every second).
                    if (it is HandoffResult.Done) link.deadline(0)
                }
            }
        }
    }

    /** Sends [m] and reads the other side's Ok (Done) or Denied (Failed). */
    private fun answer(link: HLink, m: HMsg, them: String): HandoffResult {
        link.send(m)
        return when (val r = link.read()) {
            HMsg.Ok -> HandoffResult.Done()
            is HMsg.Denied -> HandoffResult.Failed(r.reason)
            else -> HandoffResult.Failed(tr(R.string.didn_t_answer, them))
        }
    }

    /**
     * The code's twenty rounds ([Passkey]): the caller commits first and reveals first, the receiver checks each
     * reveal against its own bit before revealing its own. Each side's commitment covers both public keys, so a
     * stranger in the middle (with keys of its own on each side) is caught in a round with even odds. False as soon as
     * a round fails (or the other side stops answering).
     */
    private fun confirmCode(link: HLink, code: String, caller: Boolean, own: ByteArray, other: ByteArray): Boolean {
        val me = if (caller) "c" else "s"
        val they = if (caller) "s" else "c"
        for (round in 0 until Passkey.ROUNDS) {
            val bit = Passkey.bit(code, round)
            val n = LinkKeys.nonce()
            val myCommit = Passkey.commit(n, me, own, other, bit)
            val theirCommit: String
            if (caller) {
                link.send(HMsg.Commit(myCommit))
                theirCommit = (link.read() as? HMsg.Commit)?.c ?: return false
                link.send(HMsg.Reveal(LinkKeys.hex(n)))
                val revealed = (link.read() as? HMsg.Reveal)?.n?.let(LinkKeys::unhex) ?: return false
                if (!Passkey.opens(theirCommit, revealed, they, other, own, bit)) return false
            } else {
                theirCommit = (link.read() as? HMsg.Commit)?.c ?: return false
                link.send(HMsg.Commit(myCommit))
                val revealed = (link.read() as? HMsg.Reveal)?.n?.let(LinkKeys::unhex) ?: return false
                if (!Passkey.opens(theirCommit, revealed, they, other, own, bit)) return false
                link.send(HMsg.Reveal(LinkKeys.hex(n)))
            }
        }
        return true
    }

    companion object {
        const val PAIR_WAIT_MS = 180_000
        /** A stranger's whole handshake, up to the code (or the proof of a pairing). */
        const val HANDSHAKE_MS = 15_000L
        /** A request beyond the code's wait (a move waits for the music to start over there). */
        private const val REQUEST_MS = 60_000L
        const val PAIR_ASKS_PER_MINUTE = 5
        const val PAIR_ASKS_PER_MINUTE_ALL = 20
        /** A Look's code is a few kilobytes; anything far longer isn't one. */
        private const val MAX_LOOK_CODE = 64_000
    }
}

enum class HandoffCommand { MOVE, PULL }

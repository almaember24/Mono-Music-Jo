package com.prism.music.playback.together

import com.prism.music.R
import com.prism.music.tr
import android.app.Application
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.math.abs

/** Where Prism is in a Listen Together session. */
sealed interface TogetherState {
    data object Idle : TogetherState

    /** This phone is the session: guests join it with [code]. [address] is for joining by hand. */
    data class Hosting(val code: String, val address: String?, val port: Int, val guests: List<String>, val guestsControl: Boolean) : TogetherState

    data class Joining(val hostName: String) : TogetherState

    /**
     * In someone else's session. [now] is what they're playing. With [listenHere] the same music
     * plays on this phone, kept in step with theirs; [inSync] goes false when the listener pauses
     * or plays something else here, until they resync.
     */
    data class Guest(val hostName: String, val now: Msg.State?, val listenHere: Boolean, val inSync: Boolean) : TogetherState
}

/** A session found on the local network. */
data class NearbySession(val name: String, val host: String, val port: Int, val key: String)

/**
 * Listen Together: phones on the same Wi-Fi (or one's hotspot) listen to the same thing. One phone
 * hosts; it advertises the session on the local network and lets in whoever has its four-digit
 * code. Guests either play the music themselves, kept in step with the host's playhead, or just
 * add songs to the host's queue (party mode). There's no server, account or internet relay:
 * phones talk to each other directly, and only while a session is open.
 */
class Together(private val app: Application, private val c: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle)
    val state: StateFlow<TogetherState> = _state.asStateFlow()
    private val _nearby = MutableStateFlow<List<NearbySession>>(emptyList())
    val nearby: StateFlow<List<NearbySession>> = _nearby.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off news for a toast ("Sam joined", "Alex added Espresso"). */
    val events = _events.asSharedFlow()

    private val nsd: NsdManager? by lazy { app.getSystemService(NsdManager::class.java) }

    /** How this phone introduces itself: the account's first name, or the phone's model. */
    val myName: String get() = c.displayName(first = true).ifBlank { Build.MODEL ?: tr(R.string.app_name) }

    // ------------------------------------------------------------ Hosting

    private class GuestLink(val link: Link, val name: String)

    @Volatile private var server: ServerSocket? = null
    private val guests = CopyOnWriteArrayList<GuestLink>()
    private var registration: NsdManager.RegistrationListener? = null
    private val hostJobs = CopyOnWriteArrayList<Job>()
    @Volatile private var code = ""
    @Volatile private var guestsControl = false
    private var pending: Job? = null
    @Volatile private var starting = false

    fun host() {
        if (_state.value !is TogetherState.Idle || starting) return
        starting = true
        stopDiscovery()
        scope.launch {
            code = (1000..9999).random().toString()
            val ss = runCatching { ServerSocket(PORT) }.getOrNull() ?: runCatching { ServerSocket(0) }.getOrNull()
            starting = false
            if (ss == null) { _events.tryEmit(tr(R.string.couldn_t_start_a_session_on)); return@launch }
            server = ss
            _state.value = TogetherState.Hosting(code, localAddress(), ss.localPort, emptyList(), guestsControl)
            register(ss.localPort)
            hostJobs += scope.launch { acceptLoop(ss) }
            hostJobs += scope.launch { watchPlayer() }
        }
    }

    fun setGuestsControl(on: Boolean) {
        guestsControl = on
        updateHosting()
        broadcastSoon()
    }

    private fun updateHosting() {
        val h = _state.value as? TogetherState.Hosting ?: return
        _state.value = h.copy(guests = guests.map { it.name }, guestsControl = guestsControl)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch { serveGuest(socket) }
        }
    }

    private suspend fun serveGuest(socket: Socket) {
        socket.tcpNoDelay = true
        val link = Link(socket)
        // First thing, within a few seconds: hello, with the right code.
        socket.soTimeout = 8_000
        val hello = runCatching { link.read() }.getOrNull() as? Msg.Hello
        socket.soTimeout = 0
        when {
            hello == null -> { link.close(); return }
            hello.version != Msg.VERSION -> { link.send(Msg.Denied(tr(R.string.both_phones_need_the_latest_prism))); link.close(); return }
            hello.code.trim() != code -> {
                delay(700) // makes guessing codes slow
                link.send(Msg.Denied(tr(R.string.that_code_doesn_t_match_check)))
                link.close()
                return
            }
        }
        val g = GuestLink(link, hello.name.trim().take(32).ifBlank { tr(R.string.a_guest) })
        guests += g
        link.send(Msg.Welcome(myName))
        updateHosting()
        _events.tryEmit(tr(R.string.joined, g.name))
        broadcastSoon()
        try {
            while (true) {
                when (val m = link.read() ?: break) {
                    is Msg.Ping -> link.send(Msg.Pong(m.t0, SystemClock.elapsedRealtime()))
                    is Msg.Add -> onGuestAdd(g, m)
                    is Msg.Control -> if (guestsControl) onGuestControl(m)
                    is Msg.Bye -> break
                    else -> Unit
                }
            }
        } finally {
            guests.remove(g)
            link.close()
            if (_state.value is TogetherState.Hosting) {
                updateHosting()
                _events.tryEmit(tr(R.string.left_2, g.name))
                broadcastSoon()
            }
        }
    }

    private suspend fun onGuestAdd(g: GuestLink, m: Msg.Add) {
        val song = m.song.fromPeer() ?: return
        withContext(Dispatchers.Main) { if (m.next) c.player.playNext(song) else c.player.addToQueue(song) }
        _events.tryEmit(tr(R.string.added_2, g.name, song.title))
        val added = Msg.Added(song, g.name)
        guests.forEach { it.link.send(added) }
        broadcastSoon()
    }

    private suspend fun onGuestControl(m: Msg.Control) = withContext(Dispatchers.Main) {
        val pc = c.player
        when (m.action) {
            "play" -> pc.setPlaying(true)
            "pause" -> pc.setPlaying(false)
            "next" -> pc.next()
            "previous" -> pc.previous()
            "seek" -> pc.seekTo(m.positionMs.coerceAtLeast(0))
        }
    }

    /** Sends the state whenever playback changes, and every few seconds anyway (seeks show up as jumps). */
    private suspend fun watchPlayer() = coroutineScope {
        val pc = c.player
        launch {
            merge(pc.currentSong.map { 0 }, pc.isPlaying.map { 1 }, pc.queue.map { 2 }, pc.upNext.map { 3 }, pc.buffering.map { 4 })
                .collect { broadcastSoon() }
        }
        var last = 0L
        var lastPos = -1L
        var lastAt = 0L
        while (isActive && _state.value is TogetherState.Hosting) {
            delay(500)
            val (pos, playing) = withContext(Dispatchers.Main) { pc.position to pc.playWhenReady }
            val now = SystemClock.elapsedRealtime()
            val expected = if (playing && lastPos >= 0) lastPos + (now - lastAt) else lastPos
            val jumped = lastPos >= 0 && abs(pos - expected) > 1_500
            lastPos = pos; lastAt = now
            if (jumped || now - last > 3_000) { broadcast(); last = now }
        }
    }

    private fun broadcastSoon() {
        if (_state.value !is TogetherState.Hosting) return
        pending?.cancel()
        pending = scope.launch { delay(120); broadcast() }
    }

    private suspend fun broadcast() {
        if (guests.isEmpty()) return
        val s = snapshot()
        guests.forEach { it.link.send(s) }
    }

    private suspend fun snapshot(): Msg.State = withContext(Dispatchers.Main) {
        val pc = c.player
        val queue = pc.queue.value
        val current = queue.getOrNull(pc.currentIndex.value)?.song ?: pc.currentSong.value
        val upcoming = pc.upNext.value.mapNotNull { queue.getOrNull(it)?.song }.take(24)
        Msg.State(
            songs = listOfNotNull(current) + upcoming,
            positionMs = pc.position,
            playing = pc.playWhenReady,
            buffering = pc.buffering.value,
            sentAt = SystemClock.elapsedRealtime(),
            title = c.queue.source.value.title,
            guests = guests.map { it.name },
            canControl = guestsControl,
        )
    }

    private fun register(port: Int) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "$NAME_PREFIX$myName"
            serviceType = SERVICE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "advertising failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { registration = null }
    }

    private fun stopHosting(reason: String) {
        val bye = Msg.Bye(reason)
        val leaving = guests.toList()
        guests.clear()
        scope.launch { leaving.forEach { it.link.send(bye); it.link.close() } }
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
        runCatching { server?.close() }
        server = null
        hostJobs.forEach { it.cancel() }
        hostJobs.clear()
        pending?.cancel()
    }

    // ------------------------------------------------------------ Finding sessions

    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveLock = Mutex()

    /** Starts looking for sessions on this network ([nearby] fills in); stop with [stopDiscovery]. */
    fun startDiscovery() {
        val manager = nsd ?: return
        if (discovery != null) return
        _nearby.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains(SERVICE_LABEL) && !(_state.value is TogetherState.Hosting && info.serviceName.endsWith(myName))) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) { _nearby.value = _nearby.value.filterNot { it.key == info.serviceName } }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
    }

    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
    }

    /** Android resolves one service at a time, so they queue up here. */
    private fun resolve(info: NsdServiceInfo) {
        val manager = nsd ?: return
        scope.launch {
            resolveLock.withLock {
                val resolved = withTimeoutOrNull(6_000) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                        runCatching {
                            @Suppress("DEPRECATION")
                            manager.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, error: Int) { if (cont.isActive) cont.resume(null) }
                                override fun onServiceResolved(i: NsdServiceInfo) { if (cont.isActive) cont.resume(i) }
                            })
                        }.onFailure { if (cont.isActive) cont.resume(null) }
                    }
                } ?: return@withLock
                val host = addressOf(resolved) ?: return@withLock
                val found = NearbySession(resolved.serviceName.removePrefix(NAME_PREFIX), host, resolved.port, resolved.serviceName)
                _nearby.value = _nearby.value.filterNot { it.key == found.key } + found
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addressOf(i: NsdServiceInfo): String? {
        val all = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses else listOfNotNull(i.host)
        return (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
    }

    // ------------------------------------------------------------ Joining

    @Volatile private var hostLink: Link? = null
    private val guestJobs = CopyOnWriteArrayList<Job>()
    private var clock = ClockSync()
    private var leaving = false
    private var listenHere = true
    private var inSync = true
    private var lastState: Msg.State? = null
    private var hostName = ""
    /** Where to reconnect after a dropped connection: host, port, code. */
    private var target: Triple<String, Int, String>? = null

    /** Joins the session at [host]:[port] with its [code]. Returns why not, or null once in. */
    suspend fun join(host: String, port: Int, code: String, label: String): String? = withContext(Dispatchers.IO) {
        if (_state.value !is TogetherState.Idle) return@withContext tr(R.string.leave_this_session_first)
        _state.value = TogetherState.Joining(label)
        val (link, reply) = connect(host, port, code)
        when (reply) {
            is Msg.Welcome -> {
                stopDiscovery()
                target = Triple(host, port, code)
                startGuest(link!!, reply.hostName)
                null
            }
            is Msg.Denied -> { _state.value = TogetherState.Idle; reply.reason }
            null -> { _state.value = TogetherState.Idle; tr(R.string.couldn_t_reach_make_sure_both, label) }
            else -> { _state.value = TogetherState.Idle; tr(R.string.didn_t_answer, label) }
        }
    }

    private fun connect(host: String, port: Int, code: String): Pair<Link?, Msg?> {
        val socket = runCatching { Socket().apply { connect(InetSocketAddress(host, port), 5_000); tcpNoDelay = true } }.getOrNull()
            ?: return null to null
        val link = Link(socket)
        link.send(Msg.Hello(myName, code.trim()))
        socket.soTimeout = 8_000
        val reply = runCatching { link.read() }.getOrNull()
        socket.soTimeout = 0
        if (reply !is Msg.Welcome) link.close()
        return (if (reply is Msg.Welcome) link else null) to reply
    }

    private fun startGuest(link: Link, name: String) {
        hostLink = link
        hostName = name
        leaving = false
        inSync = true
        clock = ClockSync()
        appliedSong = null
        c.queue.followingHost = listenHere
        _state.value = TogetherState.Guest(name, lastState, listenHere, inSync)
        guestJobs += scope.launch { readHost(link) }
        guestJobs += scope.launch {
            while (isActive) {
                val l = hostLink ?: break
                l.send(Msg.Ping(SystemClock.elapsedRealtime()))
                delay(if (clock.samples < 5) 300 else 5_000)
            }
        }
    }

    private suspend fun readHost(link: Link) {
        while (true) {
            when (val m = link.read() ?: break) {
                is Msg.Pong -> clock.onPong(m, SystemClock.elapsedRealtime())
                is Msg.State -> {
                    val s = m.copy(songs = m.songs.mapNotNull { it.fromPeer() }, guests = m.guests.take(50).map { it.take(32) })
                    lastState = s
                    withContext(Dispatchers.Main) { onHostState(s) }
                }
                is Msg.Added -> if (m.by != myName) _events.tryEmit(tr(R.string.added_2, m.by.take(32), m.song.title.take(120)))
                is Msg.Bye -> { endGuest(tr(R.string.ended_the_session, hostName)); return }
                else -> Unit
            }
        }
        if (leaving || hostLink !== link) return
        // Dropped (Wi-Fi blip, the host's screen went off…): try to get back in.
        val t = target
        repeat(3) { attempt ->
            delay(1_500L * (attempt + 1))
            if (leaving || t == null) return
            val (again, reply) = connect(t.first, t.second, t.third)
            if (again != null && reply is Msg.Welcome) {
                guestJobs.forEach { it.cancel() }
                guestJobs.clear()
                startGuest(again, reply.hostName)
                return
            }
        }
        endGuest(tr(R.string.lost_the_connection_to, hostName))
    }

    private fun endGuest(message: String) {
        hostLink?.close()
        hostLink = null
        guestJobs.forEach { it.cancel() }
        guestJobs.clear()
        c.queue.followingHost = false
        target = null
        lastState = null
        _state.value = TogetherState.Idle
        _events.tryEmit(message)
    }

    /** Guest: play along on this phone, or only add songs to the host's queue. */
    fun setListenHere(on: Boolean) {
        listenHere = on
        if (on) resync() else {
            c.queue.followingHost = false
            (_state.value as? TogetherState.Guest)?.let { _state.value = it.copy(listenHere = false) }
        }
    }

    /** Guest: back in step with the host. */
    fun resync() {
        val g = _state.value as? TogetherState.Guest ?: return
        inSync = true
        appliedSong = null
        c.queue.followingHost = listenHere
        _state.value = g.copy(listenHere = listenHere, inSync = true)
        lastState?.let { s -> scope.launch(Dispatchers.Main) { apply(s) } }
    }

    /** Guest: put [song] in the host's queue. */
    fun suggest(song: Song, next: Boolean) {
        val l = hostLink ?: return
        scope.launch { l.send(Msg.Add(song, next)) }
    }

    /** Guest, when the host allows it: play, pause, next, previous or seek on the host. */
    fun control(action: String, positionMs: Long = 0) {
        val l = hostLink ?: return
        scope.launch { l.send(Msg.Control(action, positionMs)) }
    }

    /** Ends hosting, or leaves the session. */
    fun leave() {
        when (_state.value) {
            is TogetherState.Hosting -> {
                stopHosting(tr(R.string.ended_the_session, myName))
                _state.value = TogetherState.Idle
            }
            is TogetherState.Guest, is TogetherState.Joining -> {
                leaving = true
                val l = hostLink
                scope.launch { l?.send(Msg.Bye()); l?.close() }
                hostLink = null
                guestJobs.forEach { it.cancel() }
                guestJobs.clear()
                c.queue.followingHost = false
                target = null
                lastState = null
                _state.value = TogetherState.Idle
            }
            TogetherState.Idle -> Unit
        }
    }

    // ------------------------------------------------------------ Following the host

    private var appliedSong: String? = null
    private var appliedNext: List<String> = emptyList()
    private var appliedPlaying = false
    private var appliedAt = 0L
    private var lastSeekAt = 0L

    private fun onHostState(s: Msg.State) {
        val g = _state.value as? TogetherState.Guest ?: return
        _state.value = g.copy(now = s)
        if (!listenHere || !inSync) return
        // The listener paused, skipped or played something else here: stop steering until they resync.
        if (appliedSong != null && SystemClock.elapsedRealtime() - appliedAt > 2_000 && deviated()) {
            inSync = false
            c.queue.followingHost = false
            _state.value = g.copy(now = s, inSync = false)
            return
        }
        apply(s)
    }

    private fun deviated(): Boolean {
        val p = c.player.player ?: return false
        val cur = c.player.currentSong.value?.id
        val songOk = cur == appliedSong || cur in appliedNext
        val playOk = p.playWhenReady == appliedPlaying || p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE
        return !(songOk && playOk)
    }

    private fun apply(s: Msg.State) {
        val pc = c.player
        val target = s.songs.firstOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        val pos = clock.hostPosition(s, now)
        val p = pc.player
        if (p == null || pc.currentSong.value?.id != target.id) {
            pc.follow(s.songs, pos, tr(R.string.listening_with, hostName), s.playing)
            lastSeekAt = now
            appliedAt = now
        } else {
            pc.replaceUpcoming(s.songs.drop(1))
            if (p.playWhenReady != s.playing) { pc.setPlaying(s.playing); appliedAt = now }
            val drift = p.currentPosition - pos
            val tolerance = if (s.playing) 650 else 1_500
            if (p.playbackState == Player.STATE_READY && now - lastSeekAt > 2_500 && abs(drift) > tolerance) {
                // A little ahead, since the seek itself takes a moment to come back.
                pc.seekTo(pos + if (s.playing) 180 else 0)
                lastSeekAt = now
                appliedAt = now
            }
        }
        appliedSong = target.id
        appliedNext = s.songs.drop(1).take(2).map { it.id }
        appliedPlaying = s.playing
    }

    // ------------------------------------------------------------ Network

    /** This phone's address on the local network (Wi-Fi or its own hotspot), for joining by hand. */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { i -> listOf("wlan", "swlan", "ap", "eth").indexOfFirst { i.name.startsWith(it) }.let { if (it < 0) 9 else it } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    companion object {
        private const val TAG = "PrismTogether"
        const val PORT = 47312
        private const val SERVICE_LABEL = "_prismlisten"
        private const val SERVICE = "$SERVICE_LABEL._tcp"
        private const val NAME_PREFIX = "Prism · "
    }
}

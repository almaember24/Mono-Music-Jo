package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.toSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Where Prism's music is going: the phone (or its headphones), or a speaker / TV on the network. */
sealed interface CastState {
    data object Idle : CastState
    data class Connecting(val renderer: Renderer) : CastState
    /** [volume] 0–100 when the renderer says; [loading] while a song is being sent and it hasn't started yet. */
    data class Casting(val renderer: Renderer, val volume: Int?, val loading: Boolean = false) : CastState
}

/**
 * Prism Connect's speakers and TVs: UPnP / DLNA renderers on the Wi-Fi, found with SSDP and sent songs from the phone
 * ([MediaServer]: decoded to WAV / L16 unless it's a plain file the renderer lists).
 *
 * The phone's own player stays in charge while casting ("shadow playback"): it plays the queue as always, muted, so
 * the queue, autoplay, lyrics, the notification, Android Auto, widgets and play counts all carry on unchanged. Each song
 * it moves to is sent to the renderer; play, pause and seeks go across; and the phone's playhead follows the
 * renderer's, so lyrics stay with what's heard. Pausing, playing or stopping on the TV's own remote comes back the
 * other way. Mix, Sing, videos, the equalizer and normalization are the phone's own sound, so they're off while casting.
 */
@OptIn(UnstableApi::class)
class Cast(private val app: Application, private val c: AppContainer) : Player.Listener {
    /** What the playback service does as casting starts and ends (the phone's own sound effects off, then back). */
    interface Hooks {
        fun beforeCasting()
        fun afterCasting()
        /** The renderer's volume changed (the media session tells Android, so the volume keys follow it). */
        fun remoteChanged()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state.asStateFlow()
    private val _renderers = MutableStateFlow<List<Renderer>>(emptyList())
    /** Renderers on this network (while something is looking: [startDiscovery]). */
    val renderers: StateFlow<List<Renderer>> = _renderers.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off news for a toast ("Lost the connection to Living Room TV"). */
    val events = _events.asSharedFlow()

    /** Casting right now (any thread). */
    @Volatile var casting = false
        private set

    val server = MediaServer()

    // ------------------------------------------------------------ Finding renderers

    private val found = ConcurrentHashMap<String, Pair<Renderer, Long>>()
    private val describing = ConcurrentHashMap.newKeySet<String>()
    private val locationSeen = ConcurrentHashMap<String, Long>()
    private val manual = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var lookers = 0
    private var discovery: Job? = null

    /** Starts looking for renderers (the output picker while it's open); every call needs a [stopDiscovery]. */
    fun startDiscovery() {
        lookers++
        if (discovery?.isActive == true) return
        publishFound()
        discovery = scope.launch(Dispatchers.IO) { discover() }
    }

    fun stopDiscovery() {
        lookers = (lookers - 1).coerceAtLeast(0)
        if (lookers == 0) { discovery?.cancel(); discovery = null }
    }

    /** A renderer by its description's address (one SSDP can't reach, e.g. through adb on a test PC). Debug builds. */
    fun addByLocation(location: String) {
        GoogleCastDevices.byLocation(location)?.let { r ->
            if (location !in manual) manual += location
            found[r.id] = r to SystemClock.elapsedRealtime()
            publishFound()
            return
        }
        if (location !in manual) manual += location
        scope.launch(Dispatchers.IO) { describe(location, force = true) }
    }

    private suspend fun discover() {
        val wifi = app.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("prism-connect")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val notify = scope.launch(Dispatchers.IO) { listenForNotify() }
        val google = withContext(Dispatchers.Main) { startCastDiscovery() }
        try {
            manual.forEach { describe(it, force = true) }
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val job = kotlinx.coroutines.currentCoroutineContext()[Job]
                runCatching {
                    SsdpSearch.run(3_000, stop = { job?.isActive != true }) { m, _ -> m.location?.let { loc -> scope.launch(Dispatchers.IO) { describe(loc) } } }
                }
                prune()
                delay(SEARCH_EVERY_MS)
            }
        } finally {
            notify.cancel()
            google?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
            castListed.clear()
            runCatching { lock?.release() }
        }
    }

    // Google Cast devices (Chromecast, Google TV, speakers with Cast) announce themselves with mDNS as _googlecast._tcp.
    private val nsd: android.net.nsd.NsdManager? by lazy { app.getSystemService(android.net.nsd.NsdManager::class.java) }
    /** Cast devices listed by the network right now (service name → renderer id): kept while they're there. */
    private val castListed = ConcurrentHashMap<String, String>()
    private val resolveLock = Mutex()

    private fun startCastDiscovery(): android.net.nsd.NsdManager.DiscoveryListener? {
        val manager = nsd ?: return null
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                if (info.serviceType.contains("googlecast", ignoreCase = true)) resolveCast(info)
            }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) {
                castListed.remove(info.serviceName)?.let { id ->
                    if (id != (state.value as? CastState.Casting)?.renderer?.id && found.remove(id) != null) publishFound()
                }
            }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "Google Cast discovery didn't start ($error)") }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        return runCatching { manager.discoverServices(GoogleCastDevices.SERVICE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener); listener }.getOrNull()
    }

    private fun resolveCast(info: android.net.nsd.NsdServiceInfo) {
        val manager = nsd ?: return
        scope.launch(Dispatchers.IO) {
            // One resolve at a time: older Androids refuse a second while one is going.
            resolveLock.withLock {
                val r = withTimeoutOrNull(6_000) {
                    kotlinx.coroutines.suspendCancellableCoroutine<android.net.nsd.NsdServiceInfo?> { cont ->
                        runCatching {
                            @Suppress("DEPRECATION")
                            manager.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                                override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, error: Int) { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                                override fun onServiceResolved(i: android.net.nsd.NsdServiceInfo) { if (cont.isActive) cont.resumeWith(Result.success(i)) }
                            })
                        }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                    }
                } ?: return@withLock
                @Suppress("DEPRECATION")
                val all = if (android.os.Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host)
                val host = (all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull())?.hostAddress ?: return@withLock
                val txt = r.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                val device = GoogleCastDevices.renderer(r.serviceName, host, r.port, txt) ?: return@withLock
                castListed[r.serviceName] = device.id
                found[device.id] = device to SystemClock.elapsedRealtime()
                publishFound()
            }
        }
    }

    /** NOTIFY announcements (a TV switched on while the picker's open; "byebye" from one switching off). */
    private suspend fun listenForNotify() {
        val group = InetAddress.getByName(Ssdp.ADDRESS)
        val iface = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { i ->
                i.isUp && !i.isLoopback && i.supportsMulticast() && i.inetAddresses.toList().any { it is java.net.Inet4Address && it.isSiteLocalAddress }
            }
        }.getOrNull() ?: return
        val socket = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORT))
                joinGroup(InetSocketAddress(group, Ssdp.PORT), iface)
                soTimeout = 1_000
            }
        }.getOrNull() ?: return
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            val buf = ByteArray(8 * 1024)
            while (job?.isActive == true) {
                val p = java.net.DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val m = SsdpMessage.parse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                if (!m.notify || !Ssdp.isRenderer(m)) continue
                if (m.byebye) {
                    val udn = m.usn?.substringBefore("::")
                    if (udn != null && found.remove(udn) != null) publishFound()
                } else if (Ssdp.speaksForItself(m, p.address)) m.location?.let { describe(it) }
            }
        } finally {
            runCatching { socket.leaveGroup(InetSocketAddress(group, Ssdp.PORT), iface) }
            runCatching { socket.close() }
        }
    }

    private fun describe(location: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Seen lately: only its last-seen time moves on.
        if (!force && (locationSeen[location] ?: 0L) > now - REDESCRIBE_MS) {
            found.entries.firstOrNull { it.value.first.location == location }?.let { e -> found[e.key] = e.value.first to now; publishFound() }
            return
        }
        if (!describing.add(location)) return
        try {
            val r = Describe.fetch(c.http, location) { why ->
                Log.w(TAG, why)
                // Share diagnostics says why a speaker wasn't listed (#239: nothing did, while another app found them).
                com.prism.music.playback.AudioDiagnostics.log("connect", why)
            } ?: return
            locationSeen[location] = now
            found[r.id] = r to now
            publishFound()
        } finally {
            describing.remove(location)
        }
    }

    private fun prune() {
        val cutoff = SystemClock.elapsedRealtime() - FORGET_MS
        val castingTo = (state.value as? CastState.Casting)?.renderer?.id
        // Cast devices still listed by mDNS stay (they don't answer SSDP searches, so their time never moves on).
        val listed = castListed.values.toSet()
        if (found.entries.removeIf { it.value.second < cutoff && it.key != castingTo && it.key !in listed && it.value.first.location !in manual }) publishFound()
    }

    private fun publishFound() {
        _renderers.value = found.values.map { it.first }.sortedBy { it.name.lowercase() }
    }

    // ------------------------------------------------------------ The phone's player

    private var player: ExoPlayer? = null
    private var hooks: Hooks? = null

    /** The playback service's player; casting drives it from here on. */
    fun attach(p: ExoPlayer, h: Hooks) {
        player = p
        hooks = h
        p.addListener(this)
        if (tv != null) { h.beforeCasting(); applyLocalMode() }
        session?.let { s ->
            h.beforeCasting()
            applyLocalMode()
            p.currentMediaItem?.toSong()?.let { song -> if (!song.isLive) load(s, song, p.currentPosition, p.playWhenReady) }
        }
    }

    fun detach(p: ExoPlayer) {
        if (player !== p) return
        if (session != null || tv != null) disconnect(tr(R.string.casting_stopped), keepPlaying = false)
        p.removeListener(this)
        player = null
        hooks = null
    }

    // ------------------------------------------------------------ Casting

    private class Loaded(
        val song: Song, val key: String, val url: String, val meta: String, val media: CastMedia, val format: CastFormat, val durationMs: Long,
    ) {
        var loadedAt = SystemClock.elapsedRealtime()
        var started = false
        var playRetried = false
        /** Where the phone was when it was sent: mid-song, the renderer goes there once it's playing. */
        var startMs = 0L
    }

    /** A song the phone moved on to while the renderer finishes the last one; sent at [at] at the latest. */
    private class Waiting(val song: Song, val at: Long)

    private class Session(val renderer: Renderer, val ctl: RendererLink, val host: String, val sink: Set<String>) {
        var current: Loaded? = null
        var next: Loaded? = null
        var nextSupported = true
        var waiting: Waiting? = null
        /** What the renderer was last asked to do (or seen doing): a change on the phone that matches it isn't sent back. */
        var wantPlaying = false
        var lastCommandAt = 0L
        var lastSeekAt = 0L
        var lastFollowAt = 0L
        var followTarget: Long? = null
        var lastRel = -1L
        var anchorRel = 0L
        var anchorAt = 0L
        var anchored = false
        var lastTransport: TransportInfo? = null
        val misses = CastMisses(MAX_FAILURES)
        var lastFailure: String? = null
        var foreign = 0
        var volume: Int? = null
        var volumeSetAt = 0L
        var polls = 0
        /** Formats this renderer turned down this session (it then gets the other kind of PCM). */
        val refused = HashSet<CastFormat>()
    }

    private var session: Session? = null
    private val wire = Mutex()
    private var pollJob: Job? = null
    private var loadJob: Job? = null
    private var nextJob: Job? = null
    private var seekJob: Job? = null
    private var volumeJob: Job? = null
    private var singJob: Job? = null
    private var loadGen = 0
    /** The song playing is a live stream: it plays on the phone (renderers can't take an endless stream from it). */
    private var liveLocal = false
    private var muted = false
    private var focusOff = false

    private suspend fun <T> onWire(block: suspend () -> T): T = wire.withLock { block() }

    // #239 (1.8.3): what keeps a renderer session going with Prism off screen. Android may hold a background app's
    // network back ("blocked"); polls failing meanwhile say nothing about the speaker, so they aren't counted. A Wi-Fi lock
    // keeps the phone's Wi-Fi awake for the speaker fetching songs from it.
    @Volatile private var netBlocked = false
    private var netWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun holdNetwork() {
        if (wifiLock == null) wifiLock = runCatching {
            @Suppress("DEPRECATION") // The low-latency mode only holds while the app is on screen.
            app.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "prism-cast")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        if (netWatch != null || android.os.Build.VERSION.SDK_INT < 29) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
                if (blocked == netBlocked) return
                netBlocked = blocked
                com.prism.music.playback.AudioDiagnostics.log("cast", if (blocked) "Android is holding Prism's network back" else "network back")
            }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netWatch = cb
    }

    private fun letGoOfNetwork() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        netWatch?.let { cb -> runCatching { app.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netWatch = null
        netBlocked = false
    }

    /** Sends what's playing to [r] from now on. */
    fun connect(r: Renderer) {
        if (r.kind == RendererKind.PRISM_TV) return
        val now = state.value
        if (now is CastState.Casting && now.renderer.id == r.id) return
        if (session != null || tv != null) disconnect(null, keepPlaying = true)
        _state.value = CastState.Connecting(r)
        scope.launch {
            val ctl: RendererLink = if (r.kind == RendererKind.GOOGLE_CAST) GoogleCastLink(r) else RendererControl(c.http, r)
            val host = withContext(Dispatchers.IO) { r.host?.let { RendererControl.localAddressFor(it) } }
            // A Cast device opens its player first, which takes a few seconds (longer for a TV that was asleep).
            val wait = if (r.kind == RendererKind.GOOGLE_CAST) 25_000L else 8_000L
            val reachable = host != null && runCatching { withTimeoutOrNull(wait) { ctl.transport() } }
                .onFailure { Log.w(TAG, "couldn't reach ${r.name}", it) }.getOrNull() != null
            if (!reachable || !withContext(Dispatchers.IO) { server.start() }) {
                c.scope.launch(NonCancellable) { runCatching { ctl.close() } }
                if (state.value == CastState.Connecting(r)) _state.value = CastState.Idle
                _events.tryEmit(tr(R.string.couldn_t_reach_make_sure_it, r.name))
                return@launch
            }
            if (state.value != CastState.Connecting(r)) { c.scope.launch(NonCancellable) { runCatching { ctl.close() } }; return@launch }
            val sink = ctl.sinkMimes()
            server.newSession()
            val s = Session(r, ctl, host!!, sink)
            // Cast devices get each song as it comes (no "next" to line up), so it isn't got ready twice.
            s.nextSupported = r.kind != RendererKind.GOOGLE_CAST
            s.volume = runCatching { ctl.volume() }.getOrNull()
            session = s
            casting = true
            liveLocal = false
            _state.value = CastState.Casting(r, s.volume)
            Log.i(TAG, "casting to ${r.name} (${r.manufacturer} ${r.model}) from $host:${server.port}, plays ${sink.sorted()}")
            com.prism.music.playback.AudioDiagnostics.log("cast", "casting to ${r.manufacturer} ${r.model} from $host:${server.port}, plays ${sink.sorted()}")
            holdNetwork()
            // Sing is the phone's own sound: switched on while casting, it goes back off.
            singJob = scope.launch {
                c.sing.on.collect { on -> if (on && session === s) { c.sing.setOn(false); _events.tryEmit(tr(R.string.sing_works_on_this_phone_not)) } }
            }
            val p = player
            if (p == null) {
                c.player.connect()
            } else {
                hooks?.beforeCasting()
                applyLocalMode()
                val song = p.currentMediaItem?.toSong()
                if (song != null) {
                    if (song.isLive) goLocalForLive(s) else load(s, song, p.currentPosition, p.playWhenReady)
                }
            }
            hooks?.remoteChanged()
            startPolling(s)
        }
    }

    // ------------------------------------------------------------ A Prism TV (1.8.0)

    /** A TV with Prism on it playing along ([TvCast]), instead of a [session]. */
    private var tv: TvCast? = null

    /** Whether it's a Prism TV that plays now (Stop then brings the music back here, playing). */
    val toPrismTv: Boolean get() = tv != null

    /**
     * What's playing goes to the Prism TV [d] as to a speaker (the owner, 1.8.0: "the phone and TV were supposed to have
     * the music in sync"): the phone stays connected, its player and controls the TV's, and what's done on the TV shows
     * here ([TvCast]). Pairs first if they aren't yet.
     */
    fun connectTv(d: PrismDevice) {
        val r = Renderer("prism:${d.id ?: d.key}", d.name, "Prism", "", "prism://${d.host}:${d.port}", null, kind = RendererKind.PRISM_TV)
        when (val now = state.value) {
            is CastState.Casting -> if (now.renderer.id == r.id) return
            is CastState.Connecting -> if (now.renderer.id == r.id) return
            CastState.Idle -> Unit
        }
        if (session != null || tv != null) disconnect(null, keepPlaying = true)
        val p = player
        if (p == null || p.mediaItemCount == 0) { _events.tryEmit(tr(R.string.nothing_s_playing_here)); return }
        val first = TvCast.build(p, 1, p.currentPosition, c.queue.source.value.title)
        if (first.state.index < 0) { _events.tryEmit(tr(R.string.this_song_is_a_file_on)); return }
        _state.value = CastState.Connecting(r)
        tvConnecting = scope.launch {
            // Not cut off halfway (a connection it made would be left open): stopped meanwhile, it's closed below.
            val (link, why) = withContext(Dispatchers.IO + NonCancellable) { c.handoff.openFollow(d, first.state) }
            if (state.value != CastState.Connecting(r)) { link?.let { l -> c.scope.launch(Dispatchers.IO) { l.close() } }; return@launch }
            if (link == null) {
                _state.value = CastState.Idle
                why?.let { _events.tryEmit(it) }
                return@launch
            }
            val t = TvCast(c, r, link, scope, { player }, lost = { msg -> if (tv != null) disconnect(msg, keepPlaying = false) }, changed = { onTvChanged() })
            t.took(first)
            tv = t
            casting = true
            liveLocal = false
            hooks?.beforeCasting()
            applyLocalMode()
            hooks?.remoteChanged()
            _state.value = CastState.Casting(r, null)
            t.start()
            // What plays now and where: the first state was made before the connection (and any pairing's code).
            t.resync()
            Log.i(TAG, "playing on ${d.name} (Prism TV)")
        }
    }

    /** A Prism TV being connected to (its pairing code may be being asked for): stopped, it's let go. */
    private var tvConnecting: Job? = null

    private fun onTvChanged() {
        val t = tv ?: return
        applyLocalMode()
        _state.value = CastState.Casting(t.renderer, t.volume)
        hooks?.remoteChanged()
    }

    /**
     * The Prism TV stops playing along: the music comes back here ([keepPlaying]: playing, at the TV's place), or, the
     * connection lost, pauses here where the TV was last heard to be.
     */
    private fun endTv(t: TvCast, message: String?, keepPlaying: Boolean) {
        val place = t.tvPlace()
        tv = null
        casting = false
        t.end(back = keepPlaying)
        val p = player
        if (p != null) {
            if (place != null && kotlin.math.abs(p.currentPosition - place) > 300) p.seekTo(place)
            if (!keepPlaying && p.playWhenReady) p.pause()
        }
        applyLocalMode()
        hooks?.afterCasting()
        hooks?.remoteChanged()
        _state.value = CastState.Idle
        if (message != null) _events.tryEmit(message)
        Log.i(TAG, "stopped playing on ${t.renderer.name}${message?.let { ": $it" } ?: ""}")
    }

    /**
     * Stops casting. [keepPlaying]: the phone carries on from where the renderer was (picking the phone in the
     * output picker); otherwise it pauses (stopped, or the connection was lost). A Prism TV's music comes back here
     * playing when it's stopped from the phone ([message] null), as when this phone is picked.
     */
    fun disconnect(message: String? = null, keepPlaying: Boolean = false) {
        tv?.let { t -> endTv(t, message, keepPlaying || message == null); return }
        // Still connecting to a Prism TV: its pairing code isn't asked for any more, and the connection's let go.
        if ((state.value as? CastState.Connecting)?.renderer?.kind == RendererKind.PRISM_TV) {
            tvConnecting?.cancel()
            tvConnecting = null
            c.handoff.submitCode(null)
            _state.value = CastState.Idle
            return
        }
        val s = session
        if (s == null) {
            if (state.value is CastState.Connecting) _state.value = CastState.Idle
            return
        }
        session = null
        casting = false
        letGoOfNetwork()
        com.prism.music.playback.AudioDiagnostics.log("cast", "stopped casting${message?.let { ": $it" } ?: ""}${s.lastFailure?.let { " (last error: $it)" } ?: ""}")
        pollJob?.cancel(); loadJob?.cancel(); nextJob?.cancel(); seekJob?.cancel(); volumeJob?.cancel(); singJob?.cancel()
        loadGen++
        c.scope.launch(NonCancellable) {
            runCatching { withTimeoutOrNull(4_000) { s.ctl.stop() } }
            runCatching { withTimeoutOrNull(4_000) { s.ctl.close() } }
        }
        val p = player
        if (p != null && !keepPlaying && p.playWhenReady) p.pause()
        liveLocal = false
        applyLocalMode()
        hooks?.afterCasting()
        hooks?.remoteChanged()
        // Closing a listening socket is quick; done here so a connect straight after can't have it closed under it.
        server.stop()
        server.newSession()
        _state.value = CastState.Idle
        if (message != null) _events.tryEmit(message)
        Log.i(TAG, "stopped casting to ${s.renderer.name}${message?.let { ": $it" } ?: ""}")
    }

    /** The renderer's volume (0–100), for the player's volume bar and the phone's volume keys. */
    val volume: Int? get() = tv?.volume ?: session?.volume

    fun setVolume(v: Int) {
        tv?.let { t ->
            if (t.volume == v.coerceIn(0, 100)) return
            t.setVolume(v)
            _state.value = CastState.Casting(t.renderer, t.volume)
            hooks?.remoteChanged()
            return
        }
        val s = session ?: return
        val level = v.coerceIn(0, 100)
        if (s.volume == level) return
        s.volume = level
        s.volumeSetAt = SystemClock.elapsedRealtime()
        publish(s)
        hooks?.remoteChanged()
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(60)
            runCatching { onWire { s.ctl.setVolume(level) } }
        }
    }

    fun adjustVolume(delta: Int) = setVolume((volume ?: 50) + delta)

    private fun publish(s: Session, loading: Boolean? = null) {
        if (session !== s) return
        val cur = state.value as? CastState.Casting
        _state.value = CastState.Casting(s.renderer, s.volume, loading ?: cur?.loading ?: false)
    }

    /** The phone's own sound off while the renderer plays (and back after); audio focus isn't the phone's to hold then. */
    private fun applyLocalMode() {
        val p = player ?: return
        // A song a Prism TV can't play (a file on the phone) plays here meanwhile.
        val remote = (session != null && !liveLocal) || tv?.local == false
        if (remote) {
            if (p.volume != 0f) p.volume = 0f
            muted = true
        } else if (muted) {
            p.volume = 1f
            muted = false
        }
        if (remote != focusOff) {
            focusOff = remote
            // A muted player taking audio focus would pause other apps, and lose it to every notification sound.
            p.setAudioAttributes(p.audioAttributes, !remote)
            p.setHandleAudioBecomingNoisy(!remote)
        }
    }

    private fun goLocalForLive(s: Session) {
        if (session !== s) return
        liveLocal = true
        s.current = null
        s.next = null
        s.waiting = null
        loadGen++
        applyLocalMode()
        scope.launch { runCatching { onWire { s.ctl.stop() } } }
        _events.tryEmit(tr(R.string.live_streams_play_on_this_phone, s.renderer.name))
    }

    // ------------------------------------------------------------ Sending songs

    private fun keyFor(song: Song, format: CastFormat) =
        song.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + format.ext

    /** Works out how [song] goes to the renderer, and offers it on the media server. Blocking (it starts decoding). */
    private fun prepare(s: Session, song: Song): Loaded {
        val durationMs = song.durationSec * 1000L
        val refusedPcm = s.refused.contains(CastFormat.WAV) && !s.refused.contains(CastFormat.L16)
        val body: CastBody
        val format: CastFormat
        if (song.isLocal) {
            val uri = c.localMusic.uriFor(song.id) ?: throw IOException(tr(R.string.isn_t_on_the_phone_any, song.title))
            val mime = app.contentResolver.getType(uri)
            val (fmt, announce) = ProtocolInfo.choose(s.sink, mime, decodedFallback = CastFormat.ORIGINAL in s.refused)
            val length = if (fmt == CastFormat.ORIGINAL) runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L else -1L
            if (fmt == CastFormat.ORIGINAL && length > 0) {
                format = fmt
                body = FileBody(announce, length) { pos ->
                    val pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException(tr(R.string.can_t_open, song.title))
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).also { it.channel.position(pos) }
                }
            } else {
                format = pcmFormat(s, refusedPcm)
                body = pcmBody(s, CastDecoder.forUri(app, uri), durationMs, format)
            }
        } else {
            format = pcmFormat(s, refusedPcm)
            body = pcmBody(s, CastDecoder.forSong(c.playbackDataSourceFactory(), song.id), durationMs, format)
        }
        val key = keyFor(song, format)
        val url = server.publish(key, body, s.host)
        val art = runCatching { artFor(song) }.getOrNull()?.let { server.publish("art_" + key.substringBeforeLast('.') + ".jpg", it, s.host) }
        val length = ((body as? PcmBody)?.let { it.frames * 1000 / it.rate }) ?: durationMs
        val artist = song.artistText.takeUnless { song.artists.isEmpty() }.orEmpty()
        val meta = Didl.song(
            id = song.id, title = song.title, artist = artist,
            album = song.album?.title, artUrl = art, url = url, protocolInfo = ProtocolInfo.of(body.mime, body.converted),
            durationMs = length, sizeBytes = body.length,
        )
        val media = CastMedia(song.title, artist, song.album?.title, art, body.mime, length)
        return Loaded(song, key, url, meta, media, format, length)
    }

    private fun pcmFormat(s: Session, wavRefused: Boolean): CastFormat {
        if (wavRefused) return CastFormat.L16
        return ProtocolInfo.choose(s.sink, null).first.takeUnless { it == CastFormat.L16 && CastFormat.L16 in s.refused } ?: CastFormat.WAV
    }

    private fun pcmBody(s: Session, decoder: CastDecoder, durationMs: Long, format: CastFormat): PcmBody {
        val probe = decoder.probe(durationMs)
        val wavMime = s.sink.firstOrNull { it in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave") } ?: "audio/wav"
        return PcmBody(probe.rate, probe.frames, wav = format != CastFormat.L16, wavMime = wavMime) { start -> decoder.from(start, probe.rate) }
    }

    /** The cover as a JPEG of at most 600 px (TVs show JPEG; YouTube's can be WebP), or null. */
    private fun artFor(song: Song): CastBody? {
        val url = song.thumbnail ?: return null
        val bytes = if (url.startsWith("content://")) {
            app.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes() }
        } else {
            val client = c.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(hiRes(url, 600) ?: url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        } ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, 600f / maxOf(bmp.width, bmp.height))
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return BytesBody("image/jpeg", out.toByteArray())
    }

    /** Sends [song] to the renderer and (with [play]) starts it; once it's playing it's moved to [startMs]. */
    private fun load(s: Session, song: Song, startMs: Long, play: Boolean) {
        if (session !== s) return
        val gen = ++loadGen
        s.waiting = null
        s.next = null
        s.wantPlaying = play
        s.lastCommandAt = SystemClock.elapsedRealtime()
        resetAnchor(s)
        nextJob?.cancel()
        loadJob?.cancel()
        publish(s, loading = true)
        loadJob = scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { prepare(s, song) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "couldn't get ${song.id} ready to cast", e)
                if (gen == loadGen && session === s) {
                    publish(s, loading = false)
                    _events.tryEmit(tr(R.string.couldn_t_send_to, song.title, s.renderer.name, e.message ?: tr(R.string.it_didn_t_load)))
                }
                return@launch
            }
            if (gen != loadGen || session !== s) return@launch
            val sent = runCatching {
                onWire {
                    if (gen != loadGen) return@onWire false
                    s.ctl.load(loaded.url, loaded.meta, loaded.media)
                    if (play) s.ctl.play()
                    true
                }
            }
            if (gen != loadGen || session !== s) return@launch
            sent.onFailure { e ->
                Log.w(TAG, "${s.renderer.name} refused ${loaded.format} for ${song.id}: ${e.message}")
                refuse(s, loaded)
                return@launch
            }
            if (sent.getOrNull() != true) return@launch
            loaded.loadedAt = SystemClock.elapsedRealtime()
            loaded.startMs = startMs
            s.current = loaded
            s.lastCommandAt = loaded.loadedAt
            Log.i(TAG, "sent ${song.id} as ${loaded.format} (${loaded.durationMs} ms), play=$play")
            com.prism.music.playback.AudioDiagnostics.log("cast", "sent ${song.id} as ${loaded.format}, play=$play")
            // Paused when it was sent: it starts (and goes to the phone's place) when play is pressed.
            if (!play) publish(s, loading = false)
        }
    }

    /** The renderer wouldn't take (or play) what was sent: the other kind of PCM, then giving up. */
    private fun refuse(s: Session, l: Loaded) {
        if (session !== s) return
        s.refused += l.format
        val left = listOf(CastFormat.WAV, CastFormat.L16).filterNot { it in s.refused }
        if (left.isEmpty()) {
            disconnect(tr(R.string.couldn_t_play_the_music_prism, s.renderer.name), keepPlaying = false)
            return
        }
        val p = player ?: return
        val song = p.currentMediaItem?.toSong()?.takeIf { it.id == l.song.id } ?: l.song
        load(s, song, p.currentPosition, p.playWhenReady)
    }

    /** Lines up the song after this one on renderers that take SetNextAVTransportURI, so songs run into each other. */
    private fun armNext(s: Session) {
        if (!s.nextSupported || session !== s) return
        val p = player ?: return
        val cur = s.current ?: return
        val idx = p.nextMediaItemIndex
        val song = if (idx == C.INDEX_UNSET) null else p.getMediaItemAt(idx).toSong()
        if (song == null || song.isLive || song.id == cur.song.id) {
            // Nothing to line up (a renderer's earlier "next" can't be taken back, so a move onto it is set right when seen).
            return
        }
        if (s.next?.song?.id == song.id) return
        nextJob?.cancel()
        val gen = loadGen
        nextJob = scope.launch {
            delay(1_500)
            val next = runCatching { withContext(Dispatchers.IO) { prepare(s, song) } }.getOrNull() ?: return@launch
            if (session !== s || gen != loadGen) return@launch
            runCatching { onWire { s.ctl.loadNext(next.url, next.meta, next.media) } }
                .onSuccess { s.next = next; Log.i(TAG, "next lined up: ${song.id}") }
                .onFailure { e -> s.nextSupported = false; Log.i(TAG, "${s.renderer.name} has no gapless next (${e.message})") }
        }
    }

    // ------------------------------------------------------------ Following each other

    private fun startPolling(s: Session) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && session === s) {
                val t0 = SystemClock.elapsedRealtime()
                val r = runCatching { onWire { s.ctl.transport() to s.ctl.position() } }
                val rtt = (SystemClock.elapsedRealtime() - t0) / 2
                if (session !== s) break
                r.onSuccess { (ti, pi) ->
                    s.misses.ok()
                    runCatching { onPoll(s, ti, pi, rtt) }.onFailure { Log.w(TAG, "following ${s.renderer.name} failed", it) }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    s.lastFailure = "${e.javaClass.simpleName}: ${e.message?.take(80)}"
                    val lost = s.misses.failed(blocked = netBlocked)
                    com.prism.music.playback.AudioDiagnostics.log("cast", "no answer (${s.misses.count}/$MAX_FAILURES${if (netBlocked) ", network held back: not counted" else ""}) after ${rtt * 2} ms: ${s.lastFailure}")
                    if (lost) {
                        disconnect(tr(R.string.lost_the_connection_to, s.renderer.name), keepPlaying = false)
                        return@launch
                    }
                }
                if (++s.polls % 6 == 0 && SystemClock.elapsedRealtime() - s.volumeSetAt > 3_000) {
                    val v = runCatching { onWire { s.ctl.volume() } }.getOrNull()
                    if (v != null && v != s.volume && session === s) { s.volume = v; publish(s); hooks?.remoteChanged() }
                }
                if (!liveLocal) applyLocalMode()
                delay(POLL_MS)
            }
        }
    }

    private fun keyOf(url: String?): String? {
        val u = url ?: return null
        val marker = "/cast/${server.token}/"
        val i = u.indexOf(marker)
        return if (i < 0) FOREIGN else java.net.URLDecoder.decode(u.substring(i + marker.length), "UTF-8")
    }

    private fun resetAnchor(s: Session) {
        s.anchored = false
        s.lastRel = -1
    }

    private fun onPoll(s: Session, ti: TransportInfo, pi: PositionInfo, halfTrip: Long) {
        val p = player ?: return
        if (liveLocal) return
        val now = SystemClock.elapsedRealtime()
        val prev = s.lastTransport
        s.lastTransport = ti
        val cur = s.current ?: return
        val key = keyOf(pi.trackUri)
        // The renderer moved on to the lined-up song by itself (gapless): the phone goes along if it hasn't yet.
        val next = s.next
        if (next != null && key == next.key && key != cur.key) {
            next.started = true
            next.loadedAt = now
            s.current = next
            s.next = null
            s.waiting = null
            resetAnchor(s)
            if (p.currentMediaItem?.mediaId != next.song.id) {
                val idx = p.nextMediaItemIndex
                if (idx != C.INDEX_UNSET && p.getMediaItemAt(idx).mediaId == next.song.id) p.seekTo(idx, 0)
            }
            armNext(s)
            return
        }
        if (key != null && key != cur.key) {
            // Someone else's music on it now (another app, the TV's own menu): after a while, that's the end of casting.
            if (key == FOREIGN && now - cur.loadedAt > 8_000) {
                if (++s.foreign >= 4) disconnect(tr(R.string.is_playing_something_else_now, s.renderer.name), keepPlaying = false)
            }
            return
        }
        s.foreign = 0
        val onSong = p.currentMediaItem?.mediaId == cur.song.id
        when {
            ti.playing -> {
                if (!cur.started) {
                    cur.started = true
                    publish(s, loading = false)
                    // Sent mid-song (casting started there): the renderer goes to where the phone is. A song sent from its
                    // start has the phone follow the renderer instead (one step back, as the renderer took a moment).
                    if (onSong && cur.startMs > 2_500) seekRenderer(s, p.currentPosition + 200)
                    armNext(s)
                }
                if (onSong && !p.playWhenReady && now - s.lastCommandAt > GRACE_MS) {
                    // Played from the TV's own remote.
                    s.wantPlaying = true
                    p.play()
                }
                if (onSong) follow(s, p, pi, halfTrip, now)
            }
            ti.paused -> {
                resetAnchor(s)
                if (onSong && cur.started && p.playWhenReady && now - s.lastCommandAt > GRACE_MS) {
                    s.wantPlaying = false
                    p.pause()
                }
            }
            ti.stopped -> {
                resetAnchor(s)
                val dur = pi.durationMs?.takeIf { it > 0 } ?: cur.durationMs
                val ended = cur.started && (prev?.playing == true || prev?.transitioning == true || s.waiting != null) &&
                    (!onSong || dur <= 0 || p.currentPosition > dur - END_SLACK_MS)
                when {
                    ended -> s.waiting?.let { w -> s.waiting = null; load(s, w.song, 0, p.playWhenReady) }
                    // Stopped from the TV's own remote.
                    cur.started && onSong && p.playWhenReady && now - s.lastCommandAt > GRACE_MS -> { s.wantPlaying = false; p.pause() }
                    !cur.started && s.wantPlaying && ti.failed -> refuse(s, cur)
                    !cur.started && s.wantPlaying && !cur.playRetried && now - cur.loadedAt > PLAY_AGAIN_MS -> {
                        // Some renderers need Play once loading has finished.
                        cur.playRetried = true
                        scope.launch { runCatching { onWire { s.ctl.play() } } }
                    }
                    !cur.started && s.wantPlaying && now - cur.loadedAt > START_TIMEOUT_MS -> refuse(s, cur)
                }
            }
            else -> if (!cur.started && s.wantPlaying && now - cur.loadedAt > START_TIMEOUT_MS) refuse(s, cur)
        }
        s.waiting?.let { w -> if (now >= w.at) { s.waiting = null; load(s, w.song, 0, p.playWhenReady) } }
    }

    /**
     * Keeps the phone's playhead with the renderer's. Renderers mostly report whole seconds, so a reading is only
     * trusted the moment it changes (the second has just ticked over); between readings it's run on by the clock.
     */
    private fun follow(s: Session, p: ExoPlayer, pi: PositionInfo, halfTrip: Long, now: Long) {
        val rel = pi.relMs ?: return
        if (rel != s.lastRel) {
            val precise = rel % 1000 != 0L
            if (precise || s.lastRel >= 0) {
                s.anchorRel = rel + halfTrip + if (precise) 0 else POLL_MS / 2
                s.anchorAt = now
                s.anchored = true
            }
            s.lastRel = rel
        }
        if (!s.anchored || p.playbackState != Player.STATE_READY || !p.playWhenReady) return
        if (now - s.lastSeekAt < 3_000 || now - s.lastFollowAt < 3_000 || now - s.lastCommandAt < 1_500) return
        val remote = s.anchorRel + (now - s.anchorAt)
        if (abs(p.currentPosition - remote) > FOLLOW_MS) {
            s.lastFollowAt = now
            s.followTarget = remote
            p.seekTo(remote)
        }
    }

    private fun seekRenderer(s: Session, ms: Long) {
        s.lastSeekAt = SystemClock.elapsedRealtime()
        s.lastCommandAt = s.lastSeekAt
        resetAnchor(s)
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(120)
            runCatching { onWire { s.ctl.seek(ms) } }.onFailure { Log.w(TAG, "seek on ${s.renderer.name} failed: ${it.message}") }
            s.lastSeekAt = SystemClock.elapsedRealtime()
        }
    }

    /** How much of the song the renderer has left, by its playhead (a guess of 1.5 s when it hasn't said). */
    private fun remainingOnRenderer(s: Session): Long {
        val cur = s.current ?: return 0
        if (s.lastTransport?.stopped == true) return 0
        if (!s.anchored || cur.durationMs <= 0) return 1_500
        val at = s.anchorRel + (SystemClock.elapsedRealtime() - s.anchorAt)
        return (cur.durationMs - at).coerceIn(0, 30_000)
    }

    // ------------------------------------------------------------ The phone's player changing

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        tv?.let { it.onMediaItemTransition(reason); return }
        val s = session ?: return
        val p = player ?: return
        val song = mediaItem?.toSong() ?: return
        if (song.isLive) { goLocalForLive(s); return }
        if (liveLocal) {
            liveLocal = false
            applyLocalMode()
            load(s, song, p.currentPosition, p.playWhenReady)
            return
        }
        val cur = s.current
        // Already there (the renderer moved on first, or the same song was swapped for its other form).
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && cur?.song?.id == song.id) { s.waiting = null; return }
        val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (natural && cur?.started == true) {
            // The phone reached the end first: the renderer finishes its song (or moves on to the lined-up one by itself).
            val left = remainingOnRenderer(s)
            val lined = s.next?.song?.id == song.id
            s.waiting = Waiting(song, SystemClock.elapsedRealtime() + if (lined) left + 5_000 else left.coerceAtMost(4_000))
            if (left == 0L && !lined) { s.waiting = null; load(s, song, 0, p.playWhenReady) }
            return
        }
        load(s, song, if (natural) 0 else p.currentPosition, p.playWhenReady)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        tv?.let { it.onPlayWhenReadyChanged(); return }
        val s = session ?: return
        val p = player ?: return
        if (liveLocal || playWhenReady == s.wantPlaying) return
        s.wantPlaying = playWhenReady
        s.lastCommandAt = SystemClock.elapsedRealtime()
        resetAnchor(s)
        val cur = s.current
        val song = p.currentMediaItem?.toSong()
        if (cur == null || song == null || cur.song.id != song.id) {
            if (playWhenReady && song != null && !song.isLive) load(s, song, p.currentPosition, true)
            return
        }
        scope.launch {
            runCatching {
                onWire {
                    if (playWhenReady) {
                        s.ctl.play()
                        // Sent while paused: once it's going, it goes to where the phone is.
                        if (!cur.started) cur.loadedAt = SystemClock.elapsedRealtime()
                    } else s.ctl.pause()
                }
            }.onFailure { Log.w(TAG, "play/pause on ${s.renderer.name} failed: ${it.message}") }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        tv?.let { t ->
            if (reason == Player.DISCONTINUITY_REASON_SEEK) t.onSeek(oldPosition.mediaItemIndex == newPosition.mediaItemIndex, newPosition.positionMs)
            return
        }
        val s = session ?: return
        if (reason != Player.DISCONTINUITY_REASON_SEEK || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        val target = s.followTarget
        s.followTarget = null
        // The phone following the renderer: nothing to send back.
        if (target != null && abs(newPosition.positionMs - target) < 400) return
        if (liveLocal) return
        val cur = s.current ?: return
        if (player?.currentMediaItem?.mediaId != cur.song.id) return
        // Not going yet (sent while paused): it starts there instead.
        if (cur.started) seekRenderer(s, newPosition.positionMs) else cur.startMs = newPosition.positionMs
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        tv?.let { it.onQueueChanged(); return }
        val s = session ?: return
        if (s.current?.started == true && s.waiting == null) armNext(s)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        tv?.let { it.onQueueChanged(); return }
        session?.let { if (it.current?.started == true) armNext(it) }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        tv?.let { it.onQueueChanged(); return }
        session?.let { if (it.current?.started == true) armNext(it) }
    }

    companion object {
        private const val TAG = "PrismCast"
        private const val SEARCH_EVERY_MS = 10_000L
        private const val REDESCRIBE_MS = 60_000L
        private const val FORGET_MS = 3 * 60_000L
        private const val POLL_MS = 700L
        private const val MAX_FAILURES = 6
        /** After a command, the renderer gets this long to do it before what it reports is acted on. */
        private const val GRACE_MS = 4_000L
        private const val FOLLOW_MS = 1_000L
        private const val END_SLACK_MS = 5_000L
        private const val PLAY_AGAIN_MS = 4_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val FOREIGN = "\u0000foreign"
    }
}

package com.prism.music.playback.sing

import com.prism.music.R
import com.prism.music.TestHooks
import com.prism.music.tr
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Process
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.ModelStore
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Sing: karaoke with the song's own backing. Each song's vocals are separated on the phone (Spleeter, [Spleeter]) from
 * the very copy the player plays, kept in [store], and Sing's audio processor ([VocalStemProcessor]) takes them out of
 * the music as it plays, as much as [level] says.
 *
 * Songs are separated one at a time, the playing one first, then the next; the vocals are written as they're worked
 * out, so a song can be sung along to a few seconds after Sing starts on it.
 */
class Sing(private val c: AppContainer) : VocalStemProcessor.Source {
    override val store = StemStore(c.app.filesDir)

    private val _on = MutableStateFlow(false)
    /** Sing is on (for this session; it's switched on from the player). */
    val on: StateFlow<Boolean> = _on
    private val _level = MutableStateFlow(0f)
    /** How much of the lead vocals stays in: 0 = none (karaoke), 1 = all (the song as it is). */
    val level: StateFlow<Float> = _level
    private val _semitones = MutableStateFlow(0)
    /** The key, moved up or down (−6…+6 semitones), for singing in your own range. */
    val semitones: StateFlow<Int> = _semitones

    /** Songs being separated: song id → how far, 0..1. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress
    private val _problem = MutableStateFlow<String?>(null)
    /** Why the last song couldn't be separated, if it couldn't. */
    val problem: StateFlow<String?> = _problem

    /** Read by the audio processor on the playback thread. */
    @Volatile override var levelNow = 0f
        private set
    @Volatile override var onNow = false
        private set

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "PrismSing").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val wanted = ArrayDeque<Song>()
    private var job: Job? = null
    // The networks, each loaded when a way needs it (the backing's only for the full way).
    private var vocalsNet: OrtSession? = null
    private var backingNet: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var releaseJob: Job? = null

    /**
     * Sing's engine can run on this processor (otherwise only the quick way, [Separation.QUICK]), and didn't take Prism
     * down as it loaded on this version ([SingWatch.engineBroken]).
     */
    val fullEngine: Boolean get() = (ModelStore.Model.singRuntime() != null || TestHooks.singEngineFails) && !engineFailedNow && !engineBroken

    private val enginePrefs by lazy { c.app.getSharedPreferences("sing_engine", android.content.Context.MODE_PRIVATE) }
    private val version: Int get() = com.prism.music.BuildConfig.VERSION_CODE

    /**
     * The engine's loads that never finished: one is kept as it starts and cleared once it's in, so a load that took
     * Prism down (a crash in the engine itself, which nothing can catch) is found as Prism starts again.
     */
    private val engineBroken: Boolean by lazy {
        val p = enginePrefs
        val strikes = p.getInt("unfinished", 0)
        val broken = SingWatch.engineBroken(strikes, p.getInt("broken_on", 0), version)
        if (broken && p.getInt("broken_on", 0) != version) {
            p.edit().putInt("broken_on", version).putInt("unfinished", 0).apply()
            Log.w("PrismSing", "Sing's engine didn't finish loading $strikes times running: the quick way on this version")
        }
        broken
    }

    init {
        // As Prism starts, on Sing's own thread before any song is worked on: the copies a song in hand was decoded from
        // when Prism last stopped go (nothing half-done is read again), and an engine load that never finished is counted.
        scope.launch {
            withContext(worker) {
                c.app.cacheDir.listFiles { f -> f.name.startsWith("sing_") && f.name.endsWith(".part") }?.forEach { it.delete() }
                engineBroken
            }
        }
    }

    /** The engine as it was before a test's forced failure ([TestHooks.singEngineFails]). */
    internal fun engineAgain() { engineFailedNow = false; _method.value = choose(retry = false) }

    /** Sing can start: its engine is on the phone, or this processor only has the quick way, which needs nothing. */
    fun engineReady(): Boolean {
        // A test's engine: there, and failing as it loads ([networks]).
        if (TestHooks.singEngineFails) return true
        val runtime = ModelStore.Model.singRuntime() ?: return true
        return c.models.ready(runtime) && c.models.ready(ModelStore.Model.SING_MODEL)
    }

    // How fast each network way has gone on this device ([SingPace]), kept between runs.
    private val pacePrefs by lazy { c.app.getSharedPreferences("sing_pace", android.content.Context.MODE_PRIVATE) }
    private fun paceOf(way: Separation): Double? = pacePrefs.getFloat(way.name, 0f).takeIf { it > 0f }?.toDouble()
    private fun keepPace(way: Separation, speed: Double, blend: Boolean = true) {
        pacePrefs.edit().putFloat(way.name, (if (blend) SingPace.blend(paceOf(way), speed) else speed).toFloat()).apply()
    }

    /** The full and light ways' speeds here (seconds of song a second), null till timed. */
    fun pace(): Pair<Double?, Double?> = paceOf(Separation.FULL) to paceOf(Separation.LIGHT)

    /** Once a run, a device that went the quick way for being slow gets another try at the light way (one chunk). */
    @Volatile private var retried = false

    /** The way songs are separated here now, best this device keeps up with. */
    private val _method by lazy { MutableStateFlow(choose(retry = false)) }
    val method: StateFlow<Separation> get() = _method

    /** The way chosen again, after its timings or the engine changed from outside (an in-app test). */
    internal fun rechoose() { _method.value = choose(retry = false) }

    private fun choose(retry: Boolean = true): Separation {
        val way = SingPace.choose(fullEngine && engineReady(), paceOf(Separation.FULL), paceOf(Separation.LIGHT))
        if (way == Separation.QUICK && retry && !retried && fullEngine && engineReady()) { retried = true; return Separation.LIGHT }
        return way
    }

    /** Downloads the engine and the model (about 50 MB, once). */
    suspend fun downloadEngine(): Boolean {
        val runtime = ModelStore.Model.singRuntime() ?: return false
        // Both parts on Sing's own scope, so closing the sheet after the first doesn't leave the second undownloaded.
        return scope.async { c.models.download(runtime) && c.models.download(ModelStore.Model.SING_MODEL) }
            .await().also { _method.value = choose(retry = false) }
    }

    /** Settings → Lyrics → Sing → "Get songs ready while Sing is off": songs are separated ahead with Sing off too. */
    private val alwaysReady: Boolean get() = c.settings.current.singAlwaysReady

    /** Songs are being got ready: Sing is on, or set to get them ready anyway (not after Sing let go of the player). */
    private val working: Boolean get() = onNow || (alwaysReady && !heldBack)

    /** When Sing was last switched off (nanoTime): the song in hand is finished for [GRACE_NS] more, not thrown away. */
    @Volatile private var offAt = 0L

    /** Still at work on the song in hand: Sing is on (or set to get songs ready), or was switched off only just now. */
    private val stillWorking: Boolean get() = !letGo && (working || System.nanoTime() - offAt < GRACE_NS)

    /** Sing let go of the song in hand ([letGoNow]): its pass ends at once, grace or not. Cleared as the next one starts. */
    @Volatile private var letGo = false
    /** Sing stopped itself for the player's sake ([rescue]): nothing is got ready ahead till Sing is switched on again. */
    @Volatile private var heldBack = false
    /** A song is being worked on (fetched, decoded or separated) now. */
    @Volatile private var busy = false
    /** The decoders and the download of the song in hand, so letting go of it stops them at once. */
    private val decoders = java.util.concurrent.CopyOnWriteArraySet<PcmDecoder>()
    @Volatile private var download: androidx.media3.datasource.cache.CacheWriter? = null

    fun setOn(on: Boolean) {
        switch(on)
        // Switched off, the song plays as it is, whatever Sing was doing: a player stuck loading meanwhile (or stopped on
        // an error) has the song in hand let go (its decoder and download with it) and loads again.
        if (!on) main.launch {
            val p = c.player.player ?: return@launch
            val error = p.playbackState == androidx.media3.common.Player.STATE_IDLE && p.playerError != null
            if (SingWatch.stuck(p.playWhenReady, p.playbackState, p.playerError != null) &&
                (error || watching.stuckFor(android.os.SystemClock.elapsedRealtime()) >= SingWatch.OFF_STUCK_MS)
            ) { letGoNow(); unstick(p) }
        }
    }

    private fun switch(on: Boolean) {
        _on.value = on
        onNow = on
        if (on) { heldBack = false; watchPlayer() }
        if (!on) offAt = System.nanoTime()
        if (!on && !alwaysReady) {
            synchronized(wanted) { wanted.clear() }
            scheduleRelease()
        }
    }

    private val main = CoroutineScope(Dispatchers.Main.immediate)
    private var watch: Job? = null
    /** How long the player may sit loading while Sing works before Sing lets go ([SingWatch]); shorter in the tests. */
    @Volatile internal var stuckMs = SingWatch.STUCK_MS
    /** The watchdog's count of how long the player's been stuck (on the main thread). */
    private var watching = SingWatch(SingWatch.STUCK_MS)

    /**
     * The watchdog (1.8.1, Reddit: Sing on a Google TV Streamer stopped the music, which sat "loading"): while Sing is on
     * or a song is being worked on, a player that should be playing but has sat loading while Sing worked (or stopped on
     * an error) for [stuckMs] has Sing let go: the song in hand is dropped, Sing switches off, the song loads again and
     * plays as it is, and the listener is told.
     */
    private fun watchPlayer() {
        main.launch {
            if (watch?.isActive == true) return@launch
            watching = SingWatch(stuckMs)
            watch = main.launch {
                while (onNow || busy) {
                    delay(500)
                    val p = c.player.player ?: continue
                    // Counted while a song is being worked on (Sing's sound processing never holds the player): loading, or
                    // stopped on an error (a decoder it couldn't have, say, while Sing had it). Other errors are the service's.
                    val stuck = SingWatch.counts(busy, p.playWhenReady, p.playbackState, p.playerError != null)
                    if (watching.check(stuck, android.os.SystemClock.elapsedRealtime())) {
                        rescue(p)
                        watching.reset()
                    }
                }
                watching.reset()
            }
        }
    }

    /** Sing lets go for the player's sake: see [watchPlayer]. On the main thread. */
    private fun rescue(p: androidx.media3.exoplayer.ExoPlayer) {
        Log.w("PrismSing", "The player sat loading for $stuckMs ms while Sing worked: Sing let go")
        com.prism.music.playback.AudioDiagnostics.log("background", "Sing let go: the player sat loading for $stuckMs ms")
        heldBack = true
        letGoNow()
        switch(false)
        val say = tr(R.string.sing_stopped_for_the_song)
        _problem.value = say
        c.player.reportError(say)
        unstick(p)
    }

    /** Drops the song in hand at once: its decoders stop, its download stops, and its pass ends. */
    private fun letGoNow() {
        letGo = true
        synchronized(wanted) { wanted.clear() }
        decoders.forEach { it.cancel() }
        download?.cancel()
    }

    /** The player loads the song again where it was and plays (after Sing let go of what held it). */
    private fun unstick(p: androidx.media3.exoplayer.ExoPlayer) {
        val pos = p.currentPosition
        if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) p.prepare()
        p.seekTo(pos)
        p.play()
    }

    /** A decoder for the song in hand, let go of with it ([letGoNow]). */
    private fun <T> decoding(uri: android.net.Uri, block: (PcmDecoder) -> T): T {
        if (TestHooks.singPassHangs) try {
            TestHooks.singHolding = true
            while (!letGo) Thread.sleep(50)
        } finally {
            TestHooks.singHolding = false
        }
        if (letGo) throw Stopped(tr(R.string.sing_was_switched_off))
        val dec = PcmDecoder(c.app, uri)
        decoders += dec
        if (letGo) dec.cancel()
        try {
            return dec.use(block)
        } finally {
            decoders -= dec
        }
    }

    /** The whole song saved for Sing, as the player saves it ([AppContainer.warmUp]); stopped if Sing lets go of it. */
    private fun fetchWhole(id: String) {
        c.warmUp(id, whole = true, cellular = c.settings.current.singOnCellular, forSing = true) { download = it }
        download = null
        if (letGo) throw Stopped(tr(R.string.sing_was_switched_off))
    }

    /** The quick way's stand-ins (1.8.x): done first, in seconds, so Sing takes effect at once. */
    override val quickStore: StemStore get() = store.quick!!

    /** What Sing has for a song now, for the words on screen. */
    sealed interface Status {
        /** The quick way's stand-in is on its way (the song is fetched first): how far, 0..1. */
        data class Starting(val done: Float) : Status
        /** The quick way stands in while the song's own separation goes: how far that is, 0..1. */
        data class QuickWhileOwn(val done: Float) : Status
        /** The quick way, which is all this device does. */
        data object Quick : Status
        /** The song's own vocals are out. */
        data object Out : Status
    }

    fun status(id: String): Status = when {
        store.complete(id) && store.meta(id)?.method != Separation.QUICK.name -> Status.Out
        quickStore.complete(id) || store.complete(id) -> if (needsOwn(id)) Status.QuickWhileOwn(store.progress(id)) else Status.Quick
        else -> Status.Starting(quickStore.progress(id))
    }

    fun setLevel(v: Float) {
        _level.value = v.coerceIn(0f, 1f)
        levelNow = _level.value
    }

    fun setSemitones(v: Int) { _semitones.value = v.coerceIn(-6, 6) }

    /**
     * Separated already, and well enough: a song done the quick way is done again once a network way runs here (the
     * engine downloaded since, or a TV's check found it fast enough).
     */
    private fun done(id: String): Boolean =
        store.complete(id) && (store.meta(id)?.method != Separation.QUICK.name || _method.value == Separation.QUICK)

    /** Where songs were playing when they were asked for (ms, and when: nanoTime). */
    private val playhead = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    /**
     * Where to start separating [id] (µs; 0 = its start): where it's playing, a little ahead, if it's the song playing and
     * well into it (1.8.x, the owner's TV: separated from the start, a song switched to Sing late had its voice out only
     * near its end). The rest of the song is done after, up to there.
     */
    private fun startUs(id: String, quickPass: Boolean, durationSec: Int): Long {
        val (ms, at) = playhead[id] ?: return 0
        if (c.player.currentSong.value?.id != id) return 0
        // Ahead by about what the first chunk takes: a moment the quick way, a few seconds a network way.
        val then = ms + (System.nanoTime() - at) / 1_000_000 + if (quickPass) 1_000 else 8_000
        if (then < FROM_START_MS || (durationSec > 0 && then > durationSec * 1000L - FROM_START_MS)) return 0
        return then * 1000
    }

    /** The quick way's stand-in is there, or isn't needed (the song's own vocals are done). */
    private fun quickDone(id: String): Boolean = done(id) || quickStore.complete(id)

    /** A network way is still to run on [id] (never where only the quick way runs here). */
    private fun needsOwn(id: String): Boolean = _method.value != Separation.QUICK && fullEngine && engineReady() && !done(id)

    /**
     * Separates [songs] (the playing one first, then the ones coming up), skipping what's done, what can't be done,
     * and what's already queued: each song's quick stand-in first, then its own separation ([nextPass]).
     */
    fun prepare(songs: List<Song>, /** Where the first of [songs] is playing (ms; read on the main thread). */ playheadMs: Long = 0) {
        if (!working || !engineReady()) return
        songs.firstOrNull()?.let { if (playheadMs > 0) playhead[it.id] = playheadMs to System.nanoTime() else playhead.remove(it.id) }
        val todo = songs.filter { canSing(it) && (!quickDone(it.id) || needsOwn(it.id)) }
        synchronized(wanted) {
            // The playing song goes to the front; anything no longer wanted is dropped.
            wanted.removeAll { w -> todo.none { it.id == w.id } }
            todo.asReversed().forEach { s -> wanted.removeAll { it.id == s.id }; wanted.addFirst(s) }
        }
        if (job?.isActive != true) job = scope.launch { drain() }
    }

    /** Songs Sing works with: not live streams, not podcast episodes. */
    fun canSing(song: Song) = !song.isLive && !song.isEpisode

    private suspend fun drain() {
        while (true) {
            if (!working) return
            val (next, quickPass) = synchronized(wanted) {
                nextPass(wanted.map { it.id }, ::quickDone, ::needsOwn)?.let { (id, q) -> wanted.first { it.id == id } to q }
                    ?: run { wanted.clear(); null }
            } ?: return
            busy = true
            watchPlayer()
            runCatching { withContext(worker) { separate(next, quickPass) } }
                .onSuccess {
                    _problem.value = it
                    // A pass that left nothing done (it shouldn't): the song isn't tried round and round.
                    if (quickPass && !quickDone(next.id)) synchronized(wanted) { wanted.removeAll { w -> w.id == next.id } }
                }
                .onFailure {
                    synchronized(wanted) { wanted.removeAll { w -> w.id == next.id } }
                    if (it !is Stopped && it !is PcmDecoder.Cancelled) {
                        Log.w("PrismSing", "Couldn't separate ${next.id}: $it")
                        // Whatever went wrong (the download, the decoder, the engine), the song plays on as it is, and
                        // the listener is told so (said on the TV's Sing page, and as a message for the song playing).
                        val why = failure(it)
                        _problem.value = why
                        if (onNow && next.id == c.player.currentSong.value?.id) withContext(Dispatchers.Main) { c.player.reportError(why) }
                    }
                }
            busy = false
            _progress.update { it - next.id }
        }
    }

    /** What the listener's told when a song couldn't be got ready: why, and that it plays on as it is. */
    private fun failure(t: Throwable): String {
        val why = if (t is EngineFailed) tr(R.string.sing_engine_didn_t_start) else t.message?.takeIf { it.isNotBlank() } ?: tr(R.string.couldn_t_get_this_song_ready)
        return why + "\n" + tr(R.string.sing_song_plays_as_it_is)
    }

    /** Sing was switched off a while ago: the song in hand is let go. */
    private class Stopped(message: String) : IOException(message)

    /** Sing's engine failed to load here: the quick way from now on (this run; a crash, this version: [engineBroken]). */
    private class EngineFailed(cause: Throwable) : IOException(cause)

    /** The engine failed to load in this run (an error, not a crash): the quick way stands in till Prism starts again. */
    @Volatile private var engineFailedNow = false

    /** The device fell behind and stepped down to the quick way: the song's own separation stops (its stand-in stays). */
    private class SteppedDown : IOException()

    companion object {
        /** How long the song in hand is still finished after Sing is switched off (leaving the TV's Sing page, say). */
        private const val GRACE_NS = 90_000_000_000L
        /** Less into a song than this (or less left of it), it's simply separated from its start. */
        private const val FROM_START_MS = 15_000L

        /**
         * Which song to work on next, and whether it's its quick pass: the playing song's and the next one's quick
         * stand-ins first (seconds each, so Sing takes effect on both at once, the next song included however slow the
         * device), then each song's own separation in order, any later song's stand-in just before its own. Null when
         * there's nothing left to do.
         */
        internal fun nextPass(ids: List<String>, quickDone: (String) -> Boolean, needsOwn: (String) -> Boolean): Pair<String, Boolean>? {
            ids.take(2).firstOrNull { !quickDone(it) }?.let { return it to true }
            for (id in ids) {
                if (!quickDone(id)) return id to true
                if (needsOwn(id)) return id to false
            }
            return null
        }
    }

    /**
     * How the networks run: a thread per core up to 4; waiting threads asleep rather than spinning, as the player
     * shares the processor (no slower on the A16); memory handed back after each chunk rather than kept for the next
     * (on 32-bit the peak went from 197 to 171 MB).
     */
    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }

    /** The networks [way] needs, loaded (none for the quick way; the backing's for the full way only). */
    private fun networks(way: Separation): Pair<OrtSession?, OrtSession?> {
        if (way == Separation.QUICK) return null to null
        val runtime = ModelStore.Model.singRuntime() ?: if (TestHooks.singEngineFails) ModelStore.Model.SING_MODEL else throw IOException(tr(R.string.sing_doesn_t_run_on_this_2))
        if (vocalsNet != null && (way != Separation.FULL || backingNet != null)) return vocalsNet to backingNet
        // Kept as the engine loads, cleared once it's in (or failed in the usual way): a load that took Prism down with
        // it is found as Prism starts again ([engineBroken]).
        enginePrefs.edit().putInt("unfinished", enginePrefs.getInt("unfinished", 0) + 1).commit()
        try {
            if (TestHooks.singEngineFails) throw IOException("Sing's engine failed (a test)")
            val e = env ?: OrtLoader.environment(c.models.dir(runtime)).also { env = it }
            val dir = c.models.dir(ModelStore.Model.SING_MODEL)
            val vocals = vocalsNet ?: e.createSession(File(dir, "vocals.fp16.onnx").path, options()).also { vocalsNet = it }
            val backing = if (way == Separation.FULL) backingNet ?: e.createSession(File(dir, "accompaniment.fp16.onnx").path, options()).also { backingNet = it }
            else backingNet
            return vocals to backing
        } catch (t: Throwable) {
            // The engine won't load here (a library the processor can't run, a model file cut short): the quick way
            // stands in from now on, rather than failing every song.
            Log.w("PrismSing", "Sing's engine didn't load: $t")
            engineFailedNow = true
            closeNetworks()
            _method.value = choose(retry = false)
            throw EngineFailed(t)
        } finally {
            enginePrefs.edit().putInt("unfinished", 0).apply()
        }
    }

    /** The networks take ~100 MB: let go of them a few minutes after Sing is switched off. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(3 * 60_000L)
            withContext(worker) { if (!working) closeNetworks() }
        }
    }

    private fun closeNetworks() {
        vocalsNet?.let { runCatching { it.close() } }
        backingNet?.let { runCatching { it.close() } }
        vocalsNet = null
        backingNet = null
    }

    /**
     * Times the full and the light way on this device with [song] (a chunk, ~12 s of it, each; nothing is kept): the
     * TV's speed check. Their speeds in seconds of song a second, also kept for choosing ([method]).
     */
    suspend fun timeWays(song: Song): Pair<Double?, Double?> = withContext(worker) {
        letGo = false
        busy = true
        watchPlayer()
        val scratch = File(c.app.cacheDir, "sing_time.part")
        val speeds = HashMap<Separation, Double>()
        try {
            if (!song.isLocal) fetchWhole(song.id)
            val uri = c.audioOnPhone(song.id, scratch) ?: throw IOException(tr(R.string.this_song_isn_t_on_the))
            val (vocals, backing) = networks(Separation.FULL)
            decoding(uri) { dec ->
                val first = dec.read() ?: throw IOException(tr(R.string.the_song_didn_t_decode))
                val reader = PieceReader(dec, first, Resampler(2, dec.sampleRate, Spleeter.RATE))
                val sp = Spleeter(env, vocals, backing)
                // The full way's chunk first, then the light way's.
                sp.onChunk = { _, ms -> speeds[sp.ran] = SingPace.speed(Spleeter.CHUNK_SECONDS, ms); sp.method = Separation.LIGHT }
                try {
                    while (speeds.size < 2) sp.feed(reader.next() ?: break)
                } finally {
                    reader.stop()
                }
                // A song shorter than two chunks: the rest of it.
                if (speeds.size < 2) sp.finish()
            }
        } finally {
            scratch.delete()
            busy = false
        }
        speeds[Separation.FULL]?.let { keepPace(Separation.FULL, it, blend = false) }
        speeds[Separation.LIGHT]?.let { keepPace(Separation.LIGHT, it, blend = false) }
        retried = false
        _method.value = choose(retry = false)
        if (_method.value != Separation.FULL) backingNet?.let { runCatching { it.close() }; backingNet = null }
        speeds[Separation.FULL] to speeds[Separation.LIGHT]
    }

    /**
     * Works out [song]'s vocals and writes them as it goes: with [quickPass], the quick way's stand-in (seconds a song)
     * into the quick store; otherwise its own vocals into [store], each chunk the best way this device keeps up with
     * ([SingPace], timed as it goes), stopping if it falls back to the quick way (the stand-in is there already). What to
     * tell the listener about it, if anything.
     */
    private fun separate(song: Song, quickPass: Boolean): String? {
        val id = song.id
        if (if (quickPass) quickDone(id) else !needsOwn(id)) return null
        val target = if (quickPass) quickStore else store
        val started = System.nanoTime()
        letGo = false
        _progress.update { it + (id to 0f) }
        // The whole song from the player's own copy (cached whole first if need be), so the vocals line up with it.
        if (!song.isLocal) fetchWhole(id)
        val scratch = File(c.app.cacheDir, "sing_${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.part")
        val uri = c.audioOnPhone(id, scratch)
            ?: throw IOException(if (c.network.metered.value && !c.settings.current.singOnCellular) tr(R.string.waiting_for_wi_fi_to_get) else tr(R.string.this_song_isn_t_on_the))
        val way = if (quickPass) Separation.QUICK else choose()
        if (!quickPass) _method.value = way
        if (way == Separation.QUICK && !quickPass) { scratch.delete(); return null }
        val (vocals, accompaniment) = try { networks(way) } catch (t: Throwable) { scratch.delete(); throw t }
        // The way least good of the song's chunks, kept with its vocals.
        var least = way
        var steppedDown = false
        com.prism.music.playback.AudioDiagnostics.log("background", "Sing separation of $id started ($way)")
        try {
            var rate = 0
            var total = 1L
            // The way the next run starts with (it steps down within the song as it's timed).
            var wayNow = way
            var quickChunks = 0
            var monoChunks = 0

            /**
             * One run of the separation over [dec] (its first piece, [first], read already) into [file] from its start,
             * at most [limit] frames; [elsewhere] frames are done in the song's other part. How many it wrote.
             */
            fun run(dec: PcmDecoder, first: FloatArray, file: File, limit: Long, elsewhere: Long): Long {
                val back = Resampler(2, Spleeter.RATE, rate)
                val spleeter = Spleeter(env, vocals, accompaniment)
                spleeter.method = wayNow
                // Each chunk timed, and the next one done the way that keeps up (a slow device moves to a lighter way
                // within its first song; the full way needs its network loaded, so the light way stands in till the
                // next song). Down to the quick way, the song's own separation stops: its stand-in is there.
                if (!quickPass) spleeter.onChunk = { _, ms ->
                    val ran = spleeter.ran
                    if (ran != Separation.QUICK) keepPace(ran, SingPace.speed(Spleeter.CHUNK_SECONDS, ms))
                    if (ran.ordinal > least.ordinal) least = ran
                    // The light way's retry only at a song's start, where its network can be loaded.
                    val next = choose(retry = false)
                    if (next == Separation.QUICK) steppedDown = true else { spleeter.method = next; wayNow = next }
                    _method.value = next
                }
                // Decoding (the phone's decoder answers a buffer at a time) and the step to 44.1 kHz run on their own
                // thread, a few pieces ahead, while the networks work on what's already in.
                val reader = PieceReader(dec, first, Resampler(2, rate, Spleeter.RATE))
                var written = 0L
                BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
                    val bytes = ByteArray(16 * 1024)
                    fun write(v: FloatArray, cap: Long) {
                        var i = 0
                        val frames = minOf(v.size / 2L, cap - written).toInt().coerceAtLeast(0)
                        while (i < frames * 2) {
                            var n = 0
                            while (n < bytes.size && i < frames * 2) {
                                val s = (v[i] * 32767f).toInt().coerceIn(-32768, 32767)
                                bytes[n] = s.toByte(); bytes[n + 1] = (s shr 8).toByte()
                                n += 2; i++
                            }
                            out.write(bytes, 0, n)
                        }
                        written += frames
                    }
                    try {
                        while (written < limit) {
                            // Switched off: the song in hand is still finished for a while (leaving the TV's Sing page
                            // used to throw a nearly done separation away), then let go.
                            if (!stillWorking) throw Stopped(tr(R.string.sing_was_switched_off))
                            if (steppedDown) throw SteppedDown()
                            // Not being sung right now (getting songs ready, or finishing one after Sing's off): this
                            // waits its turn out of sight. Four network threads ran on with nobody listening (#373's log,
                            // Android closed Prism for it). Out here, so the chunks' own timing isn't fooled.
                            if (!onNow || !c.player.isPlaying.value) com.prism.music.playback.BackgroundBudget.pace { stillWorking }
                            val in44 = reader.next() ?: break
                            val vocalsNow = back.push(spleeter.feed(in44))
                            if (vocalsNow.isNotEmpty()) {
                                write(vocalsNow, limit)
                                out.flush()
                                _progress.update { it + (id to ((elsewhere + written).toFloat() / total).coerceAtMost(0.99f)) }
                            }
                        }
                    } finally {
                        reader.stop()
                    }
                    if (written < limit) {
                        val decoded = minOf(reader.decoded, limit)
                        write(back.push(spleeter.finish()), decoded)
                        write(back.finish(), decoded)
                        // Exactly as long as the song: the resampling round trip can be a sample short.
                        if (written < decoded) write(FloatArray(((decoded - written) * 2).toInt()), decoded)
                    }
                }
                quickChunks += spleeter.quickChunks
                monoChunks += spleeter.monoChunks
                return written
            }

            target.delete(id)
            target.pcm(id).createNewFile()
            // Switched on part way through the song: from where it's playing first, the start of it after.
            var split = 0L
            var later = 0L
            val fromUs = startUs(id, quickPass, song.durationSec)
            if (fromUs > 0) decoding(uri) { dec ->
                dec.seekTo(fromUs)
                val first = dec.read() ?: throw IOException(tr(R.string.the_song_didn_t_decode))
                rate = dec.sampleRate
                total = dec.totalFrames.coerceAtLeast(1)
                split = dec.firstFrame
                if (split > 0) {
                    target.writeMeta(id, StemStore.Meta(rate, total, from = split))
                    Log.i("PrismSing", "$id from ${split * 1000 / rate} ms first")
                    later = run(dec, first, target.pcmFrom(id), Long.MAX_VALUE, 0)
                }
            }
            val head = decoding(uri) { dec ->
                val first = dec.read() ?: throw IOException(tr(R.string.the_song_didn_t_decode))
                rate = dec.sampleRate
                if (split <= 0) {
                    split = 0
                    total = dec.totalFrames.coerceAtLeast(1)
                    target.writeMeta(id, StemStore.Meta(rate, total))
                }
                run(dec, first, target.pcm(id), if (split > 0) split else Long.MAX_VALUE, later)
            }
            val written = head + later
            // Marked complete only once every byte is in the file.
            target.writeMeta(id, StemStore.Meta(rate, written, complete = true, method = least.name, from = split))
            // The song's own vocals are done: its stand-in isn't needed any more.
            if (!quickPass) quickStore.delete(id)
            // Stepped down from the full way: the backing's network isn't needed any more (~half the memory).
            if (_method.value != Separation.FULL) backingNet?.let { runCatching { it.close() }; backingNet = null }
            val secs = (System.nanoTime() - started) / 1e9
            Log.i("PrismSing", "separated $id ($least): ${"%.1f".format(written / rate.toDouble())} s of song in ${"%.1f".format(secs)} s")
            // A mono song the quick way, with no network way to come: nothing could be told apart, so it plays as it is.
            val mono = quickChunks > 0 && monoChunks == quickChunks
            return if (mono && !needsOwn(id)) tr(R.string.sing_mono_song) else null
        } catch (_: SteppedDown) {
            target.delete(id)
            return null
        } catch (t: Throwable) {
            target.delete(id)
            throw t
        } finally {
            com.prism.music.playback.AudioDiagnostics.log("background", "Sing separation of $id ended after ${(System.nanoTime() - started) / 1_000_000} ms")
            scratch.delete()
            // "Unlimited" (#238): every sung song stays until the listener clears them.
            val room = c.settings.current.singCacheMb
            if (room < com.prism.music.data.prefs.SING_ROOM_UNLIMITED) store.trim(room * 1_048_576L, setOfNotNull(id, c.player.currentSong.value?.id))
        }
    }
}

/** What Sing has for a song, in words: the TV's Sing page and the phone's Sing sheet. */
fun Sing.Status.words(): String = when (this) {
    is Sing.Status.Starting -> tr(R.string.sing_taking_voice_out, (done * 100).toInt())
    is Sing.Status.QuickWhileOwn -> tr(R.string.sing_quick_then_full, (done * 100).toInt())
    Sing.Status.Quick -> tr(R.string.tv_voice_quick)
    Sing.Status.Out -> tr(R.string.tv_vocals_out)
}

/**
 * Decodes a song and brings it to 44.1 kHz on a thread of its own, up to [AHEAD] pieces ahead of [next]. Ends with
 * the resampler's tail; an error on that thread comes out of [next].
 */
private class PieceReader(private val dec: PcmDecoder, first: FloatArray, private val toModel: Resampler) {
    private val queue = java.util.concurrent.ArrayBlockingQueue<FloatArray>(AHEAD)
    @Volatile private var stopped = false
    @Volatile private var error: Throwable? = null
    @Volatile var decoded = 0L
        private set
    private val thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            var piece: FloatArray? = first
            while (piece != null && !stopped) {
                decoded += piece.size / 2
                put(toModel.push(piece))
                piece = dec.read()
            }
            if (!stopped) put(toModel.finish())
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            error = t
        } finally {
            if (!stopped) runCatching { put(END) }
        }
    }, "PrismSingDecode").apply { start() }

    private fun put(p: FloatArray) {
        while (!stopped) if (queue.offer(p, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return
    }

    /** The next piece at 44.1 kHz, or null once the song is over. */
    fun next(): FloatArray? {
        val p = queue.take()
        error?.let { throw it }
        return if (p === END) null else p
    }

    /** Stops the thread and waits for it (so the decoder can be closed). */
    fun stop() {
        stopped = true
        thread.interrupt()
        thread.join()
    }

    private companion object {
        const val AHEAD = 6
        val END = FloatArray(0)
    }
}

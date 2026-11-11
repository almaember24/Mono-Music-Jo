package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.together.fromPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
private data class RSong(val title: String, val artist: String, val album: String? = null, val art: String? = null, val durationMs: Long, val s: Song)

@Serializable
private data class RItem(val index: Int, val song: RSong, val at: String)

@Serializable
private data class RState(
    val song: RSong?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val shuffle: Boolean,
    val smart: Boolean,
    val repeat: Int,
    /** 0–100; -1 when it can't be set from here. */
    val volume: Int,
    val output: String,
    val source: String,
    val lyricShift: Long,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val queue: List<RItem>,
)

@Serializable
private data class RWord(val t: Long, val e: Long, val w: String)

@Serializable
private data class RLine(val t: Long, val e: Long, val x: String, val w: List<RWord> = emptyList(), val bg: List<RWord> = emptyList(), val gap: Boolean = false, val end: Boolean = false)

@Serializable
private data class RLyrics(val id: String?, val synced: Boolean, val source: String? = null, val lines: List<RLine>)

/**
 * Prism Connect's web remote: Prism played from a browser on the same Wi-Fi (a laptop, a tablet, a TV's browser), with
 * what's playing, the queue, search and word-synced lyrics. Off until switched on in Settings → Prism Connect; only
 * browsers paired with the code shown there get in ([RemoteAuth]). It runs while Prism does (open, or playing).
 */
class WebRemote(private val app: Application, private val c: AppContainer) : RemoteApi {
    private val prefs = app.getSharedPreferences("connect_remote", Context.MODE_PRIVATE)
    val auth = RemoteAuth(object : RemoteAuth.Store {
        override fun all(): Map<String, String> = prefs.all.mapValues { it.value.toString() }
        override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = prefs.edit().remove(key).apply()
    })
    // The page's files; its font is Prism's own Outfit, straight from the app (nothing is fetched from the internet).
    private val server = RemoteServer(auth, this, { name ->
        runCatching {
            if (name == "outfit.ttf") app.resources.openRawResource(com.prism.music.R.font.outfit).use { it.readBytes() }
            else app.assets.open("remote/$name").use { it.readBytes() }
        }.getOrNull()
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var watch: Job? = null

    /** The address to type into a browser, e.g. "http://192.168.1.40:47320" (null off the network). */
    val address: String? get() = if (!server.running) null else Lan.ownAddress()?.let { "http://$it:${server.port}" }

    fun setEnabled(on: Boolean) { if (on) start() else stop() }

    @Synchronized
    private fun start() {
        if (server.running) return
        if (!server.start()) { Log.w(TAG, "couldn't listen"); return }
        _running.value = true
        val pc = c.player
        watch = scope.launch {
            merge(
                pc.currentSong.map { 0 }, pc.isPlaying.map { 1 }, pc.buffering.map { 2 }, pc.queue.map { 3 }, pc.upNext.map { 4 },
                pc.shuffle.map { 5 }, pc.repeatMode.map { 6 }, pc.jumps.map { 7 }, c.queue.smartShuffle.map { 8 }, c.cast.state.map { 9 },
                pc.lyricShift.map { 10 },
            ).collect { bump() }
        }
        Log.i(TAG, "web remote on port ${server.port}")
    }

    @Synchronized
    private fun stop() {
        watch?.cancel()
        watch = null
        server.stop()
        _running.value = false
    }

    // ------------------------------------------------------------ Changes

    private val lock = Object()
    private var version = 0L

    private fun bump() = synchronized(lock) { version++; lock.notifyAll() }

    override fun awaitChange(seen: Long, timeoutMs: Long): Long = synchronized(lock) {
        if (version == seen && timeoutMs > 0) lock.wait(timeoutMs)
        version
    }

    // ------------------------------------------------------------ What the page sees

    override fun phoneName(): String {
        val who = c.displayName(first = true)
        return if (who.isNotBlank()) "$who's ${Build.MODEL}" else Build.MODEL ?: "Prism"
    }

    private fun rsong(s: Song) = RSong(
        title = s.title, artist = s.artistText, album = s.album?.title,
        art = s.thumbnail?.let { t -> if (t.startsWith("content://")) "/api/art?id=" + java.net.URLEncoder.encode(s.id, "UTF-8") else hiRes(t, 544) ?: t },
        durationMs = s.durationSec * 1000L, s = s,
    )

    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    override fun state(): String = onMain {
        val pc = c.player
        val queue = pc.queue.value
        val idx = pc.currentIndex.value
        val current = queue.getOrNull(idx)?.song ?: pc.currentSong.value
        val items = ArrayList<RItem>()
        pc.played.value.takeLast(20).forEach { i -> queue.getOrNull(i)?.let { items += RItem(i, rsong(it.song), "played") } }
        if (current != null && idx in queue.indices) items += RItem(idx, rsong(current), "now")
        pc.upNext.value.take(150).forEach { i -> queue.getOrNull(i)?.let { items += RItem(i, rsong(it.song), "next") } }
        val cast = c.cast.state.value as? CastState.Casting
        json.encodeToString(RState.serializer(), RState(
            song = current?.let(::rsong),
            positionMs = pc.position,
            durationMs = pc.duration,
            playing = pc.playWhenReady,
            buffering = pc.buffering.value,
            shuffle = pc.shuffle.value,
            smart = c.queue.smartShuffle.value,
            repeat = pc.repeatMode.value,
            volume = volume(),
            output = cast?.renderer?.name ?: tr(R.string.this_phone_2),
            source = c.queue.source.value.title,
            lyricShift = pc.lyricShift.value,
            canNext = pc.hasNext.value,
            canPrevious = current != null,
            queue = items,
        ))
    }

    private fun volume(): Int {
        if (c.cast.casting) return c.cast.volume ?: -1
        val am = app.getSystemService(AudioManager::class.java) ?: return -1
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    override fun control(action: String, value: Long?): Boolean {
        val done = onMain {
            val pc = c.player
            when (action) {
                "play" -> pc.setPlaying(true)
                "pause" -> pc.setPlaying(false)
                "toggle" -> pc.togglePlay()
                "next" -> pc.next()
                "previous" -> pc.previous()
                "seek" -> pc.seekTo((value ?: return@onMain false).coerceAtLeast(0))
                "shuffle" -> pc.toggleShuffle()
                "repeat" -> pc.cycleRepeat()
                "volume" -> {
                    val v = (value ?: return@onMain false).toInt().coerceIn(0, 100)
                    if (c.cast.casting) c.cast.setVolume(v) else {
                        val am = app.getSystemService(AudioManager::class.java) ?: return@onMain false
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max / 100f), 0)
                    }
                }
                else -> return@onMain false
            }
            true
        }
        bump()
        return done
    }

    override fun queue(action: String, index: Int, to: Int): Boolean {
        val done = onMain {
            val pc = c.player
            val size = pc.queue.value.size
            if (index !in 0 until size) return@onMain false
            when (action) {
                "jump" -> pc.skipTo(index)
                "remove" -> { if (index == pc.currentIndex.value) return@onMain false; pc.removeAt(index) }
                "move" -> { if (to !in 0 until size) return@onMain false; pc.move(index, to) }
                else -> return@onMain false
            }
            true
        }
        bump()
        return done
    }

    override fun search(query: String): String {
        if (query.isBlank()) return """{"songs":[]}"""
        val songs = runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(15_000) { c.ytm.search(query, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }.take(25) }
            }
        }.getOrNull() ?: return """{"songs":[],"error":"Search didn't answer. Try again."}"""
        return """{"songs":${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RSong.serializer()), songs.map(::rsong))}}"""
    }

    override fun play(song: JsonObject, mode: String): Boolean {
        val raw = song["s"] ?: song
        val s = runCatching { json.decodeFromJsonElement(Song.serializer(), raw) }.getOrNull()?.fromPeer() ?: return false
        onMain {
            when (mode) {
                "next" -> c.player.playNext(s)
                "later" -> c.player.addToQueue(s)
                else -> c.player.playSingle(s)
            }
        }
        bump()
        return true
    }

    private var lyricsFor: String? = null
    private var lyricsJson: String? = null

    override fun lyrics(): String {
        val song = onMain { c.player.currentSong.value } ?: return """{"id":null,"synced":false,"lines":[]}"""
        synchronized(this) { if (lyricsFor == song.id) lyricsJson?.let { return it } }
        val l = if (song.isEpisode || song.isLive) null else runCatching { runBlocking(Dispatchers.IO) { withTimeoutOrNull(20_000) { c.lyrics.auto(song) } } }.getOrNull()
        val out = json.encodeToString(RLyrics.serializer(), RLyrics(
            id = song.id,
            synced = l?.synced == true,
            source = l?.source?.label,
            lines = l?.lines.orEmpty().map { line ->
                RLine(
                    t = line.timeMs, e = line.endMs, x = line.text,
                    w = line.words.map { RWord(it.startMs, it.endMs, it.text) },
                    bg = line.background.map { RWord(it.startMs, it.endMs, it.text) },
                    gap = line.isGap, end = line.alignEnd,
                )
            },
        ))
        // Kept for the song (the page asks again after each reconnect); a miss is asked again next time.
        if (l != null) synchronized(this) { lyricsFor = song.id; lyricsJson = out }
        return out
    }

    override fun art(id: String): Pair<String, ByteArray>? {
        val song = onMain { c.player.queue.value.firstOrNull { it.song.id == id }?.song } ?: return null
        val uri = song.thumbnail?.takeIf { it.startsWith("content://media/external/audio/albumart/") } ?: return null
        val bytes = runCatching { app.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() } }.getOrNull() ?: return null
        return "image/jpeg" to bytes
    }

    private companion object { const val TAG = "PrismRemote" }
}

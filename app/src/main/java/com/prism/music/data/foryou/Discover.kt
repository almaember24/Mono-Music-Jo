package com.prism.music.data.foryou

import android.content.Context
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.ExplicitFilter
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Prism's Discover (1.8.2), all worked out on the phone from what it knows of the listener: New to you and Deep cuts
 * ([Discovery], made once a week, on the Monday), On this day ([onThisDay]), and what tuned radios go by ([radioSignals]).
 *
 * Nothing here runs unless it's asked for: the mixes are only made while their section is on Home (Customize Home adds
 * them), and they aren't part of Made for you, so a Home set up in 1.8.1 looks and costs the same as before.
 */
class Discover(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val offered = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** The mixes made so far, by id. */
    val mixes: StateFlow<Map<String, Mix>> get() = c.forYou.discovered

    /** Makes the mix [id] when this week's isn't made yet. A week it can't be made (no connection, too little to go on) keeps last week's. */
    suspend fun refresh(id: String) = lock.withLock {
        val week = Discovery.weekOf(LocalDate.now())
        if (prefs.getString(WEEK + id, null) == week && c.db.mixes().get(id) != null) return@withLock
        val now = System.currentTimeMillis()
        // Asked each time the section comes into view: it doesn't try again for a while after a try that came to nothing.
        if (now - prefs.getLong(TRIED + id, 0) < RETRY_MS || !c.network.isOnline()) return@withLock
        c.quietNetwork()
        prefs.edit().putLong(TRIED + id, now).apply()
        runCatching { make(id, week) }.onFailure { Log.w(TAG, "$id failed", it) }
    }

    /** The listener's history changed (an import, "Make my mixes again"): the next look makes the mixes again. */
    fun markStale() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(WEEK) || it.startsWith(TRIED) }.forEach { remove(it) } }.apply()
        heardAt = 0
    }

    private suspend fun make(id: String, week: String) {
        val l = withContext(Dispatchers.IO) { listener(id) } ?: return
        val maker = Discovery(Asks(c.forYou.sources()))
        val made = if (id == NEW) maker.newToYou(l, week) else maker.deepCuts(l, week)
        Log.i(TAG, "$id for $week: ${made.songs.size} songs from ${made.seeds}, ${made.songs.map { it.primaryArtist }.distinct().size} artists (${l.heard.size} heard)")
        if (made.songs.size < Discovery.MIN_SONGS) return
        val artists = made.songs.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()
        if (id == NEW) c.forYou.saveMix(NEW, tr(R.string.section_new_to_you), tr(R.string.new_to_you_line), artists.take(4), NEW_COLOR, made.songs)
        else c.forYou.saveMix(DEEP, tr(R.string.section_deep_cuts), tr(R.string.deep_cuts_line), artists.take(4), DEEP_COLOR, made.songs)
        c.recFeedback.served(made.songs.map { it.id })
        prefs.edit().putString(WEEK + id, week).apply()
        remember(id, week, made.songs)
    }

    /** What each mix offered lately (a few weeks), so next Monday's are other songs. */
    private fun offered(id: String): Map<String, List<String>> =
        runCatching { json.decodeFromString(offered, prefs.getString(OFFERED + id, null) ?: "{}") }.getOrDefault(emptyMap())

    private fun remember(id: String, week: String, songs: List<Song>) {
        val kept = (offered(id) + (week to songs.map { LocalRadio.sameSong(it) })).entries.sortedByDescending { it.key }.take(REST_WEEKS).associate { it.key to it.value }
        prefs.edit().putString(OFFERED + id, json.encodeToString(offered, kept)).apply()
    }

    private suspend fun listener(id: String): Discovery.Listener? {
        val now = System.currentTimeMillis()
        val events = c.imports.taste.tasteEventsSince(now - YEAR)
        val liked = c.db.songs().liked().map { it.toSong() }
        val starred = c.artistPrefs.favorites.value.map { Discovery.LovedArtist(it.name, it.id, 0.0) }
        if (events.isEmpty() && liked.isEmpty() && starred.isEmpty()) return null
        val songs = events.map { it.songId }.distinct().chunked(500).flatMap { c.db.songs().getAll(it) }.associate { it.id to it.toSong() }
        val favorites = Discovery.favorites(events, songs, liked, now)
        val fb = c.recFeedback
        val explicit = c.settings.current.behavior.explicitContent
        return Discovery.Listener(
            favorites, Discovery.lovedArtists(favorites, starred), heard(),
            refused = { s -> c.library.isDisliked(s.id) || c.exclusions.blocks(s) || c.exclusions.isSongExcluded(s.id) || fb.turnedDown(s.id, known = false) || !ExplicitFilter.plays(explicit, s) },
            artistDoubt = fb::artistDoubt,
            servedDaysAgo = fb::servedDaysAgo,
            lastWeeks = offered(id).values.flatten().toSet(),
            albumsHeard = favorites.mapNotNull { it.song.album?.id }.toSet(),
        )
    }

    @Volatile private var heardNow: Heard = Heard.NOTHING
    @Volatile private var heardAt = 0L

    /** All the listener has ever heard ([Heard]). Kept a few minutes: a tuned radio asks at each top-up. */
    suspend fun heard(): Heard {
        val now = System.currentTimeMillis()
        if (now - heardAt < HEARD_FOR_MS) return heardNow
        return withContext(Dispatchers.IO) {
            val d = c.db.discover()
            val rows = d.heardSongs()
            val imported = d.importedSongs()
            val own = runCatching { c.downloads.completedSongs() + c.localMusic.tracks.value.map { it.song } }.getOrDefault(emptyList())
            Heard.of(
                d.playedIds() + rows.map { it.id } + imported.mapNotNull { it.matchedId } + own.map { it.id },
                rows.map { it.title to it.primaryArtist.ifBlank { it.artistName } } + imported.map { it.title to it.artist } + own.map { it.title to it.primaryArtist },
            ).also { heardNow = it; heardAt = now }
        }
    }

    private class OnThisDayShown(val day: LocalDate, val songs: List<Song>, val at: Long)
    private val thisDay = MutableStateFlow<OnThisDayShown?>(null)

    /** What was played on this day a year ago, for [today]; empty when nothing was, and the section stays away. */
    fun onThisDaySongs(today: LocalDate): List<Song> = thisDay.value?.takeIf { it.day == today }?.songs.orEmpty()

    /** The flow behind [onThisDaySongs], to watch. */
    val onThisDayChanged: StateFlow<*> get() = thisDay

    /**
     * Reads a year ago today from the history Replay counts ("Count history from": Prism's plays and the sources the
     * listener counts), most heard first. Reads again at most every few minutes, and when the date changes.
     */
    suspend fun loadOnThisDay(today: LocalDate) {
        val now = System.currentTimeMillis()
        thisDay.value?.let { if (it.day == today && now - it.at < HEARD_FOR_MS) return }
        val songs = withContext(Dispatchers.IO) { runCatching { onThisDay(today) }.getOrDefault(emptyList()) }
        thisDay.value = OnThisDayShown(today, songs, now)
    }

    private suspend fun onThisDay(today: LocalDate): List<Song> {
        val day = OnThisDay.window(today, ZoneId.systemDefault())
        val top = c.imports.replay().topSongs(day.first, day.last, OnThisDay.MAX_SONGS * 4)
        val rows = top.map { it.songId }.chunked(500).flatMap { c.db.songs().getAll(it) }.associateBy { it.id }
        return top.mapNotNull { rows[it.songId]?.toSong() }
            .filter { !it.isEpisode && !it.isLive && !c.library.isDisliked(it.id) && !c.exclusions.blocks(it) }
            .distinctBy { LocalRadio.sameSong(it) }.take(OnThisDay.MAX_SONGS)
    }

    /** A few artists' Top songs on YouTube Music at a time, kept while Prism runs: what hits ↔ deep cuts goes by. */
    private val tops = ConcurrentHashMap<String, Set<String>>()

    /** What the phone knows of [songs] for [tune]. Whatever it doesn't know stays null, and doesn't move a song. */
    suspend fun radioSignals(songs: List<Song>, tune: RadioTune): RadioSignals {
        // Asked from the playback service, where a failed read would close Prism. (A mix being made lets it fail, and keeps last week's.)
        val known = if (tune.newness != 0f) com.prism.music.data.readOrElse<Heard?>("history", null) { heard() } else null
        if (tune.depth != 0f) learnTops(songs)
        return RadioSignals(
            heard = { s -> known?.has(s) },
            onTopList = { s -> s.artists.firstOrNull()?.id?.let { tops[it] }?.let { t -> s.id in t || Heard.key(s.title, s.primaryArtist) in t } },
            energy = { s -> if (tune.energy == 0f) null else RadioTuner.energyOf(c.mixTempo.djBpm(s.id), c.mixTempo.djLoudness(s.id)) },
        )
    }

    private suspend fun learnTops(songs: List<Song>) {
        val ask = songs.mapNotNull { it.artists.firstOrNull()?.id }.distinct().filter { !tops.containsKey(it) }.take(TOPS_A_BATCH)
        if (ask.isEmpty()) return
        withTimeoutOrNull(TOPS_WAIT_MS) {
            coroutineScope {
                ask.map { id ->
                    async {
                        runCatching { c.ytm.artistTopSongs(id) }.getOrNull()?.let { list ->
                            tops[id] = list.flatMap { listOfNotNull(it.id, Heard.key(it.title, it.primaryArtist)) }.toSet()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** New to you's and Deep cuts' questions to YouTube, each once per run. */
    private inner class Asks(base: MixMaker.Sources) : Discovery.Sources, MixMaker.Sources by base {
        override suspend fun catalog(artist: Discovery.LovedArtist): Discovery.Catalog? = withContext(Dispatchers.IO) {
            val id = artist.id ?: c.ytm.artistIdByName(artist.name) ?: return@withContext null
            val shelves = c.ytm.artist(id).shelves
            val top = shelves.firstOrNull { s -> s.items.any { it is SongItem && !it.song.isVideo } }?.items.orEmpty().filterIsInstance<SongItem>().map { it.song }
            val all = shelves.flatMap { it.items }.filterIsInstance<AlbumItem>()
            // Albums, where it can tell: a single is mostly its hit.
            val albums = all.filter { it.subtitle.contains("Album") }.ifEmpty { all }
            Discovery.Catalog(top, albums)
        }

        override suspend fun album(item: AlbumItem) = withContext(Dispatchers.IO) { c.ytm.album(item.id) }
    }

    companion object {
        const val NEW = "discover:new"
        const val DEEP = "discover:deep"
        fun isDiscover(id: String) = id.startsWith("discover:")
        const val PREFS = "discover"

        private const val TAG = "Discover"
        private const val WEEK = "week:"
        private const val TRIED = "tried:"
        private const val OFFERED = "offered:"
        private const val YEAR = 365 * 86_400_000L
        private const val RETRY_MS = 6 * 3_600_000L
        private const val HEARD_FOR_MS = 10 * 60_000L
        /** A song a mix offered stays out of it this many weeks (unless there's too little else). */
        private const val REST_WEEKS = 4
        private const val TOPS_A_BATCH = 4
        private const val TOPS_WAIT_MS = 5_000L
        private val NEW_COLOR = 0xFF6B6B6B.toInt()
        private val DEEP_COLOR = 0xFF646464.toInt()
    }
}

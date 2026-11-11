package com.prism.music.data.foryou

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.db.MixEntity
import com.prism.music.data.model.Song
import com.prism.music.download.PlayLikelihood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/** A mix as the screens use it. */
data class Mix(
    val id: String,
    val title: String,
    val subtitle: String,
    val artists: List<String>,
    val color: Int,
    val createdAt: Long,
    val songs: List<Song>,
) {
    /** Up to four different covers for its picture. */
    val covers: List<String> get() = songs.mapNotNull { it.thumbnail }.distinct().take(4)
}

/**
 * Made for you, all on the phone from the listener's own plays (nothing about them leaves it; YouTube is only asked
 * for radios to find new songs):
 * - **Daily Mixes** (up to six): the listener's artists grouped by what goes together ([MadeForYou.clusters]), each a
 *   50-song mix of songs they know by those artists and new ones like them. Made once a day, so they stay the same
 *   all day (and offline).
 * - **The time-of-day mix**: what they play at this time of day, plus more like it ("Your Tuesday evening hip-hop"),
 *   made again when the part of the day changes.
 * Disliked songs and excluded artists never appear; what was heard or served lately rests ([MixMaker], [Variety]).
 */
class ForYou(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences("for_you", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val songList = ListSerializer(Song.serializer())
    private val lock = Mutex()
    private var retitledAt = 0L

    /**
     * The mixes, read once per change and kept (null until the first read). Their songs are stored as JSON; decoding a
     * few hundred songs happened on the main thread every time Made for you scrolled back onto Home (1.7.4).
     */
    val mixes: StateFlow<List<Mix>?> = c.db.mixes().allFlow().map { list -> list.filterNot { Discover.isDiscover(it.id) }.map(::toMix).sortedBy(::order) }
        .flowOn(Dispatchers.Default)
        .stateIn(c.scope, SharingStarted.Eagerly, null)

    /** New to you and Deep cuts ([Discover]): their own sections on Home, not part of Made for you. Read once anything looks. */
    val discovered: StateFlow<Map<String, Mix>> = c.db.mixes().allFlow().map { list -> list.filter { Discover.isDiscover(it.id) }.map(::toMix).associateBy { it.id } }
        .flowOn(Dispatchers.Default)
        .stateIn(c.scope, SharingStarted.Lazily, emptyMap())

    suspend fun mix(id: String): Mix? = c.db.mixes().get(id)?.let(::toMix)

    private fun toMix(e: MixEntity) = Mix(
        e.id, e.title, e.subtitle, e.seedArtists.split('|').filter { it.isNotBlank() }, e.color, e.createdAt,
        runCatching { json.decodeFromString(songList, e.songsJson) }.getOrDefault(emptyList()),
    )

    /** Time-of-day first, then Daily Mixes, then Release Radar. */
    private fun order(m: Mix) = when {
        m.id == MadeForYou.TIME -> 0
        m.id.startsWith("daily:") -> 1 + (m.id.substringAfter(':').toIntOrNull() ?: 9)
        else -> 20
    }

    suspend fun saveMix(id: String, title: String, subtitle: String, artists: List<String>, color: Int, songs: List<Song>) {
        c.db.mixes().put(MixEntity(id, title, subtitle, artists.joinToString("|"), color, System.currentTimeMillis(), json.encodeToString(songList, songs)))
    }

    /** Makes whatever is due: the Daily Mixes once a day, the time-of-day mix when the part of the day changes. */
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        // New history (an import) or "Make my mixes again": New to you and Deep cuts are made again too, if they're on Home.
        if (force) c.discover.markStale()
        val today = LocalDate.now().toString()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val bucket = MadeForYou.TimeBucket.of(hour)
        val dailyDue = force || prefs.getString(DAILY_DAY, null) != today
        val timeDue = force || prefs.getString(TIME_KEY, null) != "$today|${bucket.name}"
        // Made for you asks for this each time it comes into view on Home: the name is looked at again every ten minutes.
        if (!timeDue && System.currentTimeMillis() - retitledAt > 10 * 60_000L) {
            retitledAt = System.currentTimeMillis()
            runCatching { retitleTimeMix() }.onFailure { Log.w(TAG, "time mix title failed", it) }
        }
        if (!dailyDue && !timeDue) return@withLock
        c.quietNetwork()
        val data = withContext(Dispatchers.IO) { Listening.load(c) } ?: return@withLock
        if (dailyDue) runCatching { makeDaily(data) }.onFailure { Log.w(TAG, "daily mixes failed", it) }
            .onSuccess { prefs.edit().putString(DAILY_DAY, today).apply() }
        if (timeDue) runCatching { makeTimeMix(data, bucket) }.onFailure { Log.w(TAG, "time mix failed", it) }
            .onSuccess { prefs.edit().putString(TIME_KEY, "$today|${bucket.name}").apply() }
    }

    /** What the mixes are made from: 90 days of plays that count towards taste, the songs, likes, and a play model. */
    private class Listening(
        val events: List<com.prism.music.data.db.PlayEventEntity>,
        val songs: Map<String, Song>,
        val liked: List<Song>,
        val model: PlayLikelihood,
        val now: Long,
    ) {
        val played90 = events.map { it.songId }.toSet()

        companion object {
            suspend fun load(c: AppContainer): Listening? {
                val now = System.currentTimeMillis()
                val events = c.imports.taste.tasteEventsSince(now - 90 * DAY)
                val liked = c.db.songs().liked().map { it.toSong() }
                if (events.isEmpty() && liked.isEmpty()) return null
                val songs = events.map { it.songId }.distinct().chunked(500).flatMap { c.db.songs().getAll(it) }.associate { it.id to it.toSong() }.toMutableMap()
                liked.forEach { songs.putIfAbsent(it.id, it) }
                return Listening(events, songs, liked, PlayLikelihood(events, songs, liked.map { it.id }.toSet(), now), now)
            }
        }
    }

    private fun ok(s: Song) = !s.isLocal && !s.isEpisode && !s.isLive && !c.library.isDisliked(s.id) && !c.exclusions.blocks(s)

    /**
     * The listener as the mix maker sees them: every song they know scored, with how much it's been heard and served
     * lately, what's been played (never "new"), and what they've turned down.
     */
    private fun taste(data: Listening): MixMaker.Taste {
        val now = data.now
        val lately = data.events.filter { now - it.timestamp < Variety.LATELY_DAYS * DAY }.groupingBy { it.songId }.eachCount()
        val last = HashMap<String, Long>()
        data.events.forEach { e -> if (e.timestamp > (last[e.songId] ?: 0L)) last[e.songId] = e.timestamp }
        val fb = c.recFeedback
        val known = data.songs.values.filter { ok(it) }.map { s ->
            MixMaker.Known(
                s, data.model.score(s), lately[s.id] ?: 0,
                last[s.id]?.let { (now - it) / 3_600_000.0 }, fb.servedDaysAgo(s.id),
            )
        }
        val played = data.played90 + data.played90.mapNotNull { data.songs[it] }.map { LocalRadio.sameSong(it) }
        return MixMaker.Taste(
            known, played,
            refused = { s, knownSong -> !ok(s) || c.exclusions.isSongExcluded(s.id) || fb.turnedDown(s.id, knownSong) },
            artistDoubt = fb::artistDoubt,
            servedDaysAgo = fb::servedDaysAgo,
        )
    }

    /**
     * Where new songs come from: YouTube Music's radio of a song, its Related tab and its similar artists' top songs.
     * Each asked once per run (several mixes can share a seed or an artist).
     */
    internal fun sources() = object : MixMaker.Sources {
        private val radios = java.util.concurrent.ConcurrentHashMap<String, List<Song>>()
        private val related = java.util.concurrent.ConcurrentHashMap<String, com.prism.music.data.model.Related>()
        private val tops = java.util.concurrent.ConcurrentHashMap<String, List<Song>>()
        override suspend fun radio(seed: Song) = radios[seed.id] ?: withContext(Dispatchers.IO) {
            c.radio(seed.id, "RDAMVM${seed.id}").songs
        }.also { radios[seed.id] = it }
        override suspend fun related(seed: Song) = related[seed.id] ?: withContext(Dispatchers.IO) {
            c.ytm.related(seed.id, seed.title)
        }.also { related[seed.id] = it }
        override suspend fun artistTop(artist: com.prism.music.data.model.ArtistItem) = tops[artist.id] ?: withContext(Dispatchers.IO) {
            c.ytm.artistTopSongs(artist.id)
        }.also { tops[artist.id] = it }
    }

    private suspend fun makeDaily(data: Listening) {
        val day = LocalDate.now().toString()
        val plays = data.events.mapNotNull { e -> data.songs[e.songId]?.let { MadeForYou.Play(e.songId, it.primaryArtist, e.timestamp) } } +
            data.liked.map { MadeForYou.Play(it.id, it.primaryArtist, data.now - 7 * DAY, 0.5) }
        val genres = artistGenres(MadeForYou.topArtists(plays.filter { it.artist.isNotBlank() }, data.now), data)
        val clusters = MadeForYou.clusters(plays.filter { it.artist.isNotBlank() }, genres, data.now)
        val taste = taste(data)
        val maker = MixMaker(sources())
        val made = ArrayList<String>()
        val served = ArrayList<String>()
        for (cl in clusters) {
            // Numbered by the mixes actually made: a group without enough songs is skipped without leaving a gap.
            val n = made.size + 1
            val members = cl.artists.map { it.lowercase() }.toSet()
            val recipe = MadeForYou.dailyRecipe(MadeForYou.daily(n), members.size)
            val mix = maker.make(recipe, taste, day) { it.song.primaryArtist.lowercase() in members }
            if (mix.songs.size < 10) continue
            saveMix(MadeForYou.daily(n), tr(R.string.daily_mix, n), MadeForYou.namesLine(cl.artists), cl.artists.take(4), COLORS[(n - 1) % COLORS.size], mix.songs)
            made += MadeForYou.daily(n)
            served += mix.songs.map { it.id }
            Log.i(TAG, "daily mix $n: ${mix.familiar.size} known, ${mix.fresh.size} new, seeds ${mix.seeds.map { it.title }}")
        }
        c.recFeedback.served(served)
        // Mixes from a day with more groups than today's go.
        c.db.mixes().deleteOthers("daily:%", made.ifEmpty { listOf("-") })
        Log.i(TAG, "daily mixes: ${clusters.joinToString(" | ") { "${it.artists} %.0f%%".format(it.weight * 100) }}")
    }

    private suspend fun makeTimeMix(data: Listening, bucket: MadeForYou.TimeBucket) {
        val day = LocalDate.now().toString() + bucket.name
        val cal = Calendar.getInstance()
        val weight = HashMap<String, Double>()
        for (e in data.events) {
            cal.timeInMillis = e.timestamp
            if (cal.get(Calendar.HOUR_OF_DAY) !in bucket) continue
            weight.merge(e.songId, 1.0, Double::plus)
        }
        val top = weight.values.maxOrNull() ?: 0.0
        if (weight.size < 5) { c.db.mixes().deleteOthers(MadeForYou.TIME, listOf("-")); return }
        // What's played at this time of day, in proportion to how often: the time counts as much as the liking.
        val base = taste(data)
        val taste = MixMaker.Taste(
            base.known.map { k -> k.copy(score = k.score * (0.4 + 0.6 * (weight[k.song.id] ?: 0.0) / top)) },
            base.played, base.refused, base.artistDoubt, base.servedDaysAgo,
        )
        val mix = MixMaker(sources()).make(MadeForYou.timeRecipe, taste, day) { it.song.id in weight }
        if (mix.familiar.size < 5) { c.db.mixes().deleteOthers(MadeForYou.TIME, listOf("-")); return }
        val songs = mix.songs
        c.recFeedback.served(songs.map { it.id })
        // Artists whose genre isn't known yet are looked up first (briefly), so the name has the whole mix to go by.
        withTimeoutOrNull(6_000) {
            val known = c.meta.cachedGenres(songs)
            val missing = songs.filter { it.id !in known }.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()
            coroutineScope { missing.map { a -> async { runCatching { c.meta.artistGenre(a) } } }.awaitAll() }
        }
        val weekday = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val artists = mix.familiar.map { it.primaryArtist }.distinct()
        saveMix(MadeForYou.TIME, MadeForYou.timeTitle(weekday, bucket, timeGenre(songs)), MadeForYou.namesLine(artists),
            artists.take(4), TIME_COLORS.getValue(bucket), songs)
    }
    /** The genre word in the time-of-day mix's name ("indie", "hip-hop"), or null for "mix". */
    private suspend fun timeGenre(songs: List<Song>): String? {
        val g = c.meta.cachedGenres(songs)
        return MadeForYou.mainGenre(songs.map { g[it.id] })
    }

    /**
     * The time-of-day mix named again from what's known of its songs' genres now. A new mix is named while most
     * artists only have Deezer's quick genre, and Apple's (looked up in the background) can differ: Deezer files
     * Bring Me The Horizon and A Day To Remember under Alternative, Apple under Rock, so a mix of them was called
     * "indie" while its own page listed it as mostly rock.
     */
    private suspend fun retitleTimeMix() {
        val e = c.db.mixes().get(MadeForYou.TIME) ?: return
        val made = java.time.Instant.ofEpochMilli(e.createdAt).atZone(java.time.ZoneId.systemDefault())
        val bucket = MadeForYou.TimeBucket.of(made.hour)
        val title = MadeForYou.timeTitle(made.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()), bucket, timeGenre(toMix(e).songs))
        if (title != e.title) {
            c.db.mixes().put(e.copy(title = title))
            Log.i(TAG, "time mix renamed: ${e.title} -> $title")
        }
    }

    /** Each artist's genre (Prism's genre buckets), from what's been looked up; the ones not known yet are looked up (briefly). */
    private suspend fun artistGenres(artists: List<String>, data: Listening): Map<String, String?> {
        val bySong = c.meta.cachedGenres(data.songs.values.toList())
        val out = HashMap<String, String?>()
        val votes = HashMap<String, HashMap<String, Int>>()
        for (s in data.songs.values) bySong[s.id]?.let { g -> votes.getOrPut(s.primaryArtist) { HashMap() }.merge(g, 1, Int::plus) }
        for (a in artists) out[a] = votes[a]?.maxByOrNull { it.value }?.key
        // The rest are looked up (Deezer / iTunes, cached for good after), each given its own time so one slow
        // answer doesn't cost the others theirs.
        val missing = artists.filter { out[it] == null }
        if (missing.isNotEmpty()) coroutineScope {
            missing.map { a -> async { a to withTimeoutOrNull(15_000) { runCatching { c.meta.artistGenre(a) }.getOrNull() } } }.awaitAll()
        }.forEach { (a, g) -> if (g != null) out[a] = g }
        Log.i(TAG, "genres for ${artists.size} artists, ${out.values.count { it != null }} known: " + artists.joinToString { "$it=${out[it]}" })
        return out
    }

    companion object {
        /** A queue started from one of Prism's mixes (a Daily Mix, the time mix, Release Radar, a mix from your music). */
        fun isMixId(id: String) = id.startsWith("daily:") || id == MadeForYou.TIME || id == MadeForYou.RADAR || YourMusic.isLibraryMix(id) || Discover.isDiscover(id)

        private const val TAG = "ForYou"
        private const val DAILY_DAY = "daily_day"
        private const val TIME_KEY = "time_key"
        private const val DAY = 86_400_000L
        private val COLORS = listOf(0xFF7B7B7B, 0xFF747474, 0xFF666666, 0xFF7D7D7D, 0xFFA0A0A0, 0xFF6A6A6A).map { it.toInt() }
        private val TIME_COLORS = mapOf(
            MadeForYou.TimeBucket.MORNING to 0xFFB1B1B1.toInt(),
            MadeForYou.TimeBucket.AFTERNOON to 0xFF969696.toInt(),
            MadeForYou.TimeBucket.EVENING to 0xFF8F8F8F.toInt(),
            MadeForYou.TimeBucket.NIGHT to 0xFF707070.toInt(),
            MadeForYou.TimeBucket.LATE to 0xFF434343.toInt(),
        )
    }
}

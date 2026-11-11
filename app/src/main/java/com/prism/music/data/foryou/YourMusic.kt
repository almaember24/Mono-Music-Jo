package com.prism.music.data.foryou

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.AppContainer
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.model.Song
import com.prism.music.download.PlayLikelihood
import com.prism.music.playback.MusicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * What Prism makes from the listener's own music, on the phone, without YouTube: Prism's radio ([radio], what autoplay
 * plays after a song from your own plays, likes, downloads and files), Home's lists ([sections]: On repeat, Repeat
 * rewind, Forgotten favourites) and mixes from your music ([Sections.mixes]: by genre, by artist, and what's ready
 * offline). All from Replay's history, which never leaves the phone.
 */
class YourMusic(private val c: AppContainer) {
    private val lock = Mutex()
    private var snap: Snapshot? = null
    @Volatile private var lastSections: Sections? = null

    /** The phone's music, read once every few minutes (or after new plays). */
    private class Snapshot(
        val at: Long,
        val events: List<PlayEventEntity>,
        val songs: Map<String, Song>,
        val liked: List<Song>,
        val offline: List<Song>,
        val genres: Map<String, String>,
        val model: PlayLikelihood,
        val coListen: Map<String, Map<String, Double>>,
    ) {
        /** Plays in the last [Variety.LATELY_DAYS] days, and each song's last play. */
        val lately: Map<String, Int> = events.filter { at - it.timestamp < Variety.LATELY_DAYS * DAY }.groupingBy { it.songId }.eachCount()
        val last: Map<String, Long> = HashMap<String, Long>().also { m -> events.forEach { e -> if (e.timestamp > (m[e.songId] ?: 0L)) m[e.songId] = e.timestamp } }
    }

    /** How much of its weight [id] keeps today ([Variety.rest]): heard lately, on repeat, served in a recent mix. */
    private fun rest(s: Snapshot, id: String, served: Boolean = true) = Variety.rest(
        s.lately[id] ?: 0, s.last[id]?.let { (System.currentTimeMillis() - it) / 3_600_000.0 }, if (served) c.recFeedback.servedDaysAgo(id) else null,
    )

    private suspend fun snapshot(): Snapshot = lock.withLock {
        val now = System.currentTimeMillis()
        val count = runCatching { c.imports.taste.changeCount(now) }.getOrDefault(0)
        // Kept for a few minutes, and only while no new play has come in.
        snap?.takeIf { now - it.at < 5 * 60_000 && count == total }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val events = c.imports.taste.tasteEventsSince(now - YEAR)
            val liked = c.db.songs().liked().map { it.toSong() }
            val local = c.localMusic.tracks.value.map { it.song }
            val downloaded = c.downloads.completedSongs()
            val songs = HashMap<String, Song>()
            events.map { it.songId }.distinct().chunked(500).flatMap { c.db.songs().getAll(it) }.forEach { songs[it.id] = it.toSong() }
            (liked + downloaded + local).forEach { songs.putIfAbsent(it.id, it) }
            val usable = songs.values.filter { ok(it) }
            val genres = runCatching { c.meta.cachedGenres(usable) }.getOrDefault(emptyMap())
            val model = PlayLikelihood(events, songs, liked.map { it.id }.toSet(), now)
            val co = LocalRadio.coListen(events.map { LocalRadio.Play(it.songId, it.timestamp) })
            Snapshot(now, events, songs, liked, (downloaded + local).distinctBy { it.id }, genres, model, co).also { snap = it; total = count }
        }
    }

    @Volatile private var total = -1

    private fun ok(s: Song) = !s.isEpisode && !s.isLive && !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) && !c.exclusions.isSongExcluded(s.id)

    /**
     * Prism's radio: up to [count] songs from your own music to follow [seedIds] (the newest last), none of [inQueue],
     * nothing played in the last two hours. [offlineOnly]: only songs that play without a connection.
     */
    suspend fun radio(seedIds: List<String>, inQueue: Set<String>, count: Int, offlineOnly: Boolean): List<LocalRadio.Pick> {
        val s = snapshot()
        val seeds = seedIds.mapNotNull { id -> s.songs[id] ?: c.db.songs().get(id)?.toSong() }
        if (seeds.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val recent = s.events.filter { now - it.timestamp < 2 * HOUR }.map { it.songId }.toSet()
        val pool = LocalRadio.Pool(
            // Not clips under a minute (an album's intro, a skit, a sound file on the phone): they'd break the run of songs.
            songs = s.songs.values.filter { ok(it) && !it.isVideo && (it.durationSec == 0 || it.durationSec >= 60) && (!offlineOnly || c.availableOffline(it.id)) },
            coListen = s.coListen,
            affinity = { s.model.score(it) * rest(s, it.id) },
            genreOf = { s.genres[it] },
            keyFit = { a, b -> keyFit(a, b) },
        )
        val day = LocalDate.now().toString()
        return LocalRadio.pick(seeds, pool, inQueue + recent, count) { id -> 0.9 + 0.2 * (((id + day + seedIds.last()).hashCode().toLong() and 0xffff) / 65535.0) }
    }

    private fun keyFit(a: String, b: String): Double {
        val ka = c.mixTempo.key(a) ?: return 0.0
        val kb = c.mixTempo.key(b) ?: return 0.0
        return (1.0 - 2 * MusicKey.clash(ka, kb)).coerceIn(0.0, 1.0)
    }

    data class Sections(
        val onRepeat: List<Song>,
        val repeatRewind: List<Song>,
        val forgotten: List<Song>,
        val mixes: List<Mix>,
    )

    /** Home's lists and mixes from your music; [offlineOnly] keeps to songs that play without a connection. */
    suspend fun sections(offlineOnly: Boolean): Sections = withContext(Dispatchers.Default) {
        val s = snapshot()
        val now = System.currentTimeMillis()
        val play: (Song) -> Boolean = { ok(it) && !it.isVideo && (!offlineOnly || c.availableOffline(it.id)) }
        val byId = s.events.groupBy { it.songId }
        val last = byId.mapValues { (_, e) -> e.maxOf { it.timestamp } }

        fun counted(from: Long, to: Long) = s.events.asSequence().filter { it.timestamp in from until to }.groupingBy { it.songId }.eachCount()

        val month = counted(now - 30 * DAY, now)
        val onRepeat = month.filter { it.value >= 2 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { last[it.key] ?: 0 })
            .mapNotNull { s.songs[it.key] }.filter(play).distinctBy { LocalRadio.sameSong(it) }.take(30).takeIf { it.size >= MIN_LIST }.orEmpty()

        val before = counted(now - YEAR, now - 60 * DAY)
        val repeatRewind = before.filter { it.value >= 3 && (last[it.key] ?: 0) < now - 60 * DAY }.entries
            .sortedByDescending { it.value }
            .mapNotNull { s.songs[it.key] }.filter(play).distinctBy { LocalRadio.sameSong(it) }.take(30).takeIf { it.size >= MIN_LIST }.orEmpty()

        val plays = byId.mapValues { it.value.size }
        val forgotten = (
            s.liked.filter { (last[it.id] ?: 0) < now - 90 * DAY } +
                plays.filter { it.value >= 5 && (last[it.key] ?: 0) < now - 120 * DAY }.keys.mapNotNull { s.songs[it] }
            ).distinctBy { it.id }
            .sortedByDescending { plays[it.id] ?: 0 }
            .filter(play).distinctBy { LocalRadio.sameSong(it) }.take(30).takeIf { it.size >= MIN_LIST }.orEmpty()

        val mixes = libraryMixes(s, play)
        Sections(onRepeat, repeatRewind, forgotten, mixes).also { lastSections = it }
    }

    /** A mix from your music by id (for its page), from the last [sections]. */
    fun mix(id: String): Mix? = lastSections?.mixes?.firstOrNull { it.id == id }

    /**
     * Mixes from your music: one for each of your top genres and artists, made from songs you know (played, liked,
     * downloaded, on the phone), best first with a day's shuffle; and, when there's enough, what's ready offline.
     */
    private fun libraryMixes(s: Snapshot, play: (Song) -> Boolean): List<Mix> {
        val day = LocalDate.now().toString()
        fun j(id: String) = 0.85 + 0.3 * (((id + day).hashCode().toLong() and 0xffff) / 65535.0)
        val candidates = s.songs.values.filter(play)
        val score = candidates.associate { it.id to s.model.score(it) * j(it.id) }
        // One copy of each song (the best liked), however many uploads of it you've played.
        val known = candidates.sortedByDescending { score[it.id] ?: 0.0 }.distinctBy { LocalRadio.sameSong(it) }
        val out = ArrayList<Mix>()

        // Each mix is drawn by weighted chance (the day's own draw), resting what was heard lately, instead of the same top forty.
        fun draw(songs: List<Song>, salt: String) = Variety.sample(songs, MIX_SIZE, Variety.daySeed(day, salt), sharpness = 1.5) { (score[it.id] ?: 0.0) * rest(s, it.id) }
        val byGenre = known.groupBy { s.genres[it.id] }.filterKeys { it != null }
        byGenre.entries.sortedByDescending { (_, songs) -> songs.sumOf { score[it.id] ?: 0.0 } }.take(3).forEach { (genre, songs) ->
            if (songs.size < MIN_MIX) return@forEach
            val list = spread(draw(songs, genre!!))
            out += Mix("lib:genre:$genre", tr(R.string.your_mix, com.prism.music.data.meta.Genres.word(genre)), MadeForYou.namesLine(artists(list)), artists(list).take(4), color(genre), s.at, list)
        }

        val artistPlays = s.events.groupingBy { s.songs[it.songId]?.primaryArtist.orEmpty() }.eachCount().filterKeys { it.isNotBlank() }
        artistPlays.entries.sortedByDescending { it.value }.take(3).forEach { (artist, _) ->
            val theirs = draw(known.filter { it.primaryArtist.equals(artist, ignoreCase = true) }, "artist:$artist")
            if (theirs.size < 3) return@forEach
            // Their songs you know, with the ones you play around them.
            val around = LocalRadio.pick(
                theirs.take(3), LocalRadio.Pool(known, s.coListen, { s.model.score(it) }, { s.genres[it] }),
                theirs.map { it.id }.toSet(), 20,
            ).filter { it.related }.map { it.song }
            val list = MadeForYou.interleave(theirs.take(20), around, MIX_SIZE) { it.primaryArtist.lowercase() }
            if (list.size < MIN_MIX) return@forEach
            out += Mix("lib:artist:$artist", tr(R.string.mix_4, artist), tr(R.string.with_2, MadeForYou.namesLine(artists(list).filterNot { it.equals(artist, true) }.ifEmpty { listOf(artist) })), listOf(artist), color(artist), s.at, list)
        }

        // What plays with no connection at all: downloads, songs kept whole in the cache, files on the phone.
        val ready = known.filter { c.availableOffline(it.id) }.sortedByDescending { score[it.id] ?: 0.0 }
        if (ready.size >= MIN_MIX) out += Mix(OFFLINE_MIX, tr(R.string.ready_offline), tr(R.string.a_mix_of_songs_already_on), artists(ready).take(4), 0xFF434343.toInt(), s.at, spread(draw(ready, OFFLINE_MIX)))
        return out
    }

    /**
     * Quick picks made around the artists the listener plays: favourites by their top artists they haven't played for
     * a few days, new songs from radios of their top three artists ([radioOf], none they've played), and [youtube]'s own
     * picks when they're by artists the listener plays; taken in turn (two favourites, two new, one of YouTube's), no
     * artist twice running and three at most. With too little history to go on, [youtube] as it is.
     *
     * [draw]: which draw of the day (each pull to refresh is another, #371: every draw used to be the day's one, so a
     * refresh gave the same songs back). [shown] is the set being replaced, kept out where there are others to take.
     */
    suspend fun quickPicks(
        youtube: List<Song>, radioOf: suspend (Song) -> List<Song>, count: Int = 20, draw: Int = 0, shown: Set<String> = emptySet(),
    ): List<Song> {
        val s = snapshot()
        val now = System.currentTimeMillis()
        val recent = s.events.filter { now - it.timestamp < 90 * DAY }
        if (recent.size < MIN_PICK_HISTORY) return youtube
        // How much the listener plays each main artist: newer plays count more (halving every 30 days), likes add.
        val aff = HashMap<String, Double>()
        for (e in recent) {
            val a = s.songs[e.songId]?.primaryArtist?.lowercase().orEmpty()
            if (a.isNotBlank()) aff[a] = (aff[a] ?: 0.0) + Math.pow(0.5, (now - e.timestamp) / (30.0 * DAY))
        }
        s.liked.forEach { val a = it.primaryArtist.lowercase(); if (a.isNotBlank()) aff[a] = (aff[a] ?: 0.0) + 0.5 }
        val top = aff.values.maxOrNull()?.takeIf { it > 0 } ?: return youtube
        fun affinity(song: Song) = song.artists.maxOfOrNull { (aff[it.name.lowercase()] ?: 0.0) / top } ?: 0.0
        val heardToday = s.events.filter { now - it.timestamp < DAY }.map { it.songId }.toSet()
        val heardSongs = heardToday.mapNotNull { s.songs[it] }.map { LocalRadio.sameSong(it) }.toSet()
        fun fresh(song: Song) = ok(song) && !song.isVideo && song.id !in heardToday && LocalRadio.sameSong(song) !in heardSongs
        val day = LocalDate.now().toString()
        fun j(id: String) = 0.85 + 0.3 * (((id + day + draw).hashCode().toLong() and 0xffff) / 65535.0)
        fun seed(salt: String) = drawSeed(day, salt, draw)

        val last = s.events.groupBy { it.songId }.mapValues { (_, e) -> e.maxOf { it.timestamp } }
        val favourites = s.songs.values
            .filter { fresh(it) && (last[it.id] ?: 0) < now - 3 * DAY && affinity(it) > 0.15 && (it.durationSec == 0 || it.durationSec >= 60) }
            .distinctBy { LocalRadio.sameSong(it) }
            // Drawn by weighted chance, the day's own draw, so the same favourites don't head Quick picks every day.
            .let { all -> Variety.sample(all, count * if (shown.isEmpty()) 1 else 2, seed("picks"), sharpness = 1.5) { s.model.score(it) * rest(s, it.id) } }
        // New songs from the radios of three of the listener's eight main artists, a different three most days, each from one of their songs drawn by liking.
        val topArtists = Variety.sample(aff.entries.sortedByDescending { it.value }.take(8), 3, seed("pick-artists")) { it.value }.map { it.key }
        val seeds = topArtists.mapNotNull { a ->
            Variety.sample(s.songs.values.filter { it.primaryArtist.equals(a, true) && !it.isVideo && !it.isLocal }, 1, seed("seed:$a"), sharpness = 2.0) { s.model.score(it) }.firstOrNull()
        }
        val played = s.events.map { it.songId }.toSet()
        val playedSongs = played.mapNotNull { s.songs[it] }.map { LocalRadio.sameSong(it) }.toSet()
        // All three at once (#371: one after the other, they were a good part of the wait for the section).
        val radios = kotlinx.coroutines.coroutineScope {
            seeds.map { seed -> async { runCatching { radioOf(seed) }.getOrDefault(emptyList()).filter { it.id != seed.id } } }.awaitAll()
        }
        val discovered = radios.flatten()
            .filter { fresh(it) && it.id !in played && LocalRadio.sameSong(it) !in playedSongs && !c.recFeedback.turnedDown(it.id, known = false) }
            .distinctBy { LocalRadio.sameSong(it) }
            .sortedByDescending { (0.6 + affinity(it)) * j(it.id) }
        val fromYouTube = youtube.filter { fresh(it) && affinity(it) > 0.05 }

        val out = takeTurns(favourites, discovered, fromYouTube, count, shown).toMutableList()
        // Still short (a new listener's few artists): YouTube's own fill the rest.
        val taken = out.map { LocalRadio.sameSong(it) }.toSet()
        youtube.filter { fresh(it) && LocalRadio.sameSong(it) !in taken }.take(count - out.size).forEach { out += it }
        return out
    }

    /** Songs ready offline, most likely first: Quick picks' stand-in with no connection. */
    suspend fun offlinePicks(count: Int = 20): List<Song> {
        val s = snapshot()
        return s.songs.values.filter { ok(it) && !it.isVideo && c.availableOffline(it.id) }
            .sortedByDescending { s.model.score(it) }.distinctBy { LocalRadio.sameSong(it) }.take(count)
    }

    private fun artists(list: List<Song>) = list.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()

    /** The same artist not twice running where it can be helped. */
    private fun spread(list: List<Song>) = MadeForYou.interleave(list, emptyList(), list.size) { it.primaryArtist.lowercase() }

    private fun color(key: String) = COLORS[((key.hashCode() % COLORS.size) + COLORS.size) % COLORS.size]

    companion object {
        /** A draw's seed: the day's own for the first ([draw] 0), another for each pull to refresh (#371). */
        internal fun drawSeed(day: String, salt: String, draw: Int): Long = Variety.daySeed(day, if (draw == 0) salt else "$salt#$draw")

        /**
         * Quick picks' turns: two favourites, two new, one of YouTube's, no artist twice running and three at most. Songs
         * in [shown] (the set a pull to refresh replaces) only come when nothing else fits, so a refresh changes the picks
         * for as far as there are others.
         */
        internal fun takeTurns(favourites: List<Song>, discovered: List<Song>, youtube: List<Song>, count: Int, shown: Set<String> = emptySet()): List<Song> {
            val turns = listOf(0, 1, 0, 1, 2)
            val out = ArrayList<Song>()
            val perArtist = HashMap<String, Int>()
            val taken = HashSet<String>()
            fun fits(song: Song): Boolean {
                val a = song.primaryArtist.lowercase()
                return LocalRadio.sameSong(song) !in taken && (perArtist[a] ?: 0) < 3 && (out.isEmpty() || out.last().primaryArtist.lowercase() != a || a.isBlank())
            }
            fun run(pools: List<MutableList<Song>>) {
                var turn = out.size
                while (out.size < count) {
                    val order = listOf(turns[turn % turns.size]) + (0..2).filter { it != turns[turn % turns.size] }
                    val pool = order.map { pools[it] }.firstOrNull { p -> p.any { fits(it) } } ?: break
                    val song = pool.first { fits(it) }
                    pool.remove(song)
                    out += song
                    taken += LocalRadio.sameSong(song)
                    song.primaryArtist.lowercase().let { perArtist[it] = (perArtist[it] ?: 0) + 1 }
                    turn++
                }
            }
            val pools = listOf(favourites, discovered, youtube)
            run(pools.map { p -> p.filterTo(ArrayList()) { it.id !in shown } })
            if (shown.isNotEmpty()) run(pools.map { p -> p.filterTo(ArrayList()) { it.id in shown } })
            return out
        }

        const val OFFLINE_MIX = "lib:offline"
        fun isLibraryMix(id: String) = id.startsWith("lib:")
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
        private const val YEAR = 365 * DAY
        private const val MIN_LIST = 4
        /** Plays in 90 days before Quick picks are made around the listener's artists. */
        private const val MIN_PICK_HISTORY = 15
        private const val MIN_MIX = 8
        private const val MIX_SIZE = 40
        private val COLORS = listOf(0xFF636363, 0xFF5D5D5D, 0xFF6D6D6D, 0xFF5F5F5F, 0xFF6D6D6D, 0xFF5F5F5F).map { it.toInt() }
    }
}

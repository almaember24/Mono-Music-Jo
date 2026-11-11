package com.prism.music.data.foryou

import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp

/**
 * Everything the listener has heard, ever, as far as the phone knows: Prism's own plays however old (a Daily Mix only
 * looks back 90 days), songs started and skipped, likes, downloads, files on the phone, and every imported history,
 * counted in Replay or not (they did hear them). A song counts by its id and by its name ([ImportKeys.songKey]), so
 * another upload or a remaster of a song they know isn't "new to them".
 */
class Heard(private val ids: Set<String>, private val names: Set<String>) {
    fun has(song: Song): Boolean = song.id in ids || key(song.title, song.primaryArtist).let { it != null && it in names }

    val size: Int get() = ids.size

    companion object {
        val NOTHING = Heard(emptySet(), emptySet())

        /** A song's name as a key, null when its title has nothing to go by (all punctuation): then only its id counts. */
        fun key(title: String, artist: String): String? =
            ImportKeys.songKey(title, artist).takeIf { it.substringBeforeLast('|').isNotBlank() }

        fun of(ids: Collection<String>, names: Collection<Pair<String, String>>) = Heard(ids.toHashSet(), names.mapNotNullTo(HashSet()) { (t, a) -> key(t, a) })
    }
}

/**
 * How New to you and Deep cuts are made (1.8.2), apart from where the data comes from, so a run of Mondays can be tried in
 * a test. They share [MixMaker]'s and [Variety]'s rules (weighted chance, seeds that change, artists spread, turned-down
 * songs out); what's their own is that nothing the listener has ever heard goes in ([Heard]), not just the last 90 days.
 */
class Discovery(private val sources: Sources) {
    /** What it asks of YouTube besides what mixes ask ([MixMaker.Sources]). */
    interface Sources : MixMaker.Sources {
        /** An artist's page: their top songs and their albums. */
        suspend fun catalog(artist: LovedArtist): Catalog?
        suspend fun album(item: AlbumItem): CollectionPage
    }

    class Catalog(val top: List<Song>, val albums: List<AlbumItem>)

    /** A song the listener loves, and how much. */
    data class Favorite(val song: Song, val weight: Double)

    data class LovedArtist(val name: String, val id: String?, val weight: Double)

    /** The listener as these two see them. */
    class Listener(
        val favorites: List<Favorite>,
        val artists: List<LovedArtist>,
        val heard: Heard,
        /** Left out of recommendations (disliked, excluded, turned down). */
        val refused: (Song) -> Boolean = { false },
        val artistDoubt: (String) -> Double = { 0.0 },
        val servedDaysAgo: (String) -> Int? = { null },
        /** Songs this mix offered in the last few weeks: other songs first. */
        val lastWeeks: Set<String> = emptySet(),
        /** Albums they've played something from: their other tracks are the best deep cuts there are. */
        val albumsHeard: Set<String> = emptySet(),
    )

    data class Made(val songs: List<Song>, val seeds: List<String>)

    private val maker = MixMaker(sources)

    /**
     * About [NEW_SIZE] songs never heard, found from the radios, Related tabs and similar artists of a week's draw of
     * favorites ([MixMaker.discover]), an artist or two each. Songs it offered in the last weeks only come back when there's
     * too little else.
     */
    suspend fun newToYou(l: Listener, week: String): Made {
        val seed = Variety.daySeed(week, "new_to_you")
        val seeds = seedSongs(l, seed)
        if (seeds.isEmpty()) return Made(emptyList(), emptyList())
        val artists = l.artists.map { it.name.lowercase() }.toSet()
        val taste = MixMaker.Taste(
            known = emptyList(), played = emptySet(),
            refused = { s, _ -> l.heard.has(s) || l.refused(s) },
            artistDoubt = l.artistDoubt, servedDaysAgo = l.servedDaysAgo,
        )
        val recipe = MixMaker.Recipe("new_to_you", NEW_SIZE, freshShare = 1.0, seeds = seeds.size, perArtist = 2)
        // A wider draw than the mix needs, for [arrange] to spread its artists.
        val want = NEW_SIZE * 3
        var pool = maker.discover(seeds, recipe, taste, artists, seed, exclude = l.lastWeeks, want = want)
        if (pool.size < NEW_SIZE) pool = maker.discover(seeds, recipe, taste, artists, seed, exclude = emptySet(), want = want)
        return Made(spread(pool, NEW_SIZE, 2), seeds.map { it.title })
    }

    /** A week's seeds: favorites drawn by how much they're loved, an artist each; a starred artist's top song if there are too few. */
    private suspend fun seedSongs(l: Listener, seed: Long): List<Song> {
        val can = l.favorites.filter { !it.song.isLocal && !it.song.isEpisode && !it.song.isLive && !it.song.isUpload }
        val drawn = Variety.sample(can, SEEDS * 6, seed) { it.weight }.map { it.song }
        val out = Variety.roundRobin(drawn) { it.primaryArtist }.distinctBy { it.primaryArtist.lowercase() }.take(SEEDS).toMutableList()
        if (out.size < SEEDS) for (a in l.artists) {
            if (out.size >= SEEDS) break
            if (a.id == null || out.any { it.primaryArtist.equals(a.name, ignoreCase = true) }) continue
            runCatching { sources.artistTop(ArtistItem(a.id, a.name, "", null)) }.getOrNull()?.firstOrNull()?.let { out += it }
        }
        return out
    }

    /**
     * About [DEEP_SIZE] songs by artists they love that they've never heard: album tracks that are on neither the artist's
     * top songs nor the album's standouts ([CollectionPage.popularIds]), from a couple of albums each. No live takes,
     * remixes or other versions ([ImportKeys.versionMarks]), and the artist has to be on the track.
     */
    suspend fun deepCuts(l: Listener, week: String): Made {
        val seed = Variety.daySeed(week, "deep_cuts")
        val artists = Variety.sample(l.artists.take(TOP_ARTISTS), ARTISTS_A_WEEK, seed) { it.weight * (1.0 - l.artistDoubt(it.name)) }
        if (artists.isEmpty()) return Made(emptyList(), emptyList())
        val gate = Semaphore(3)
        val found = coroutineScope {
            artists.map { a ->
                async {
                    val catalog = gate.withPermit { runCatching { sources.catalog(a) }.getOrNull() } ?: return@async emptyList()
                    val hits = catalog.top.flatMap { listOfNotNull(it.id, Heard.key(it.title, it.primaryArtist)) }.toSet()
                    // Albums they've started on first, then by chance: a different couple most weeks.
                    val albums = Variety.sample(catalog.albums.distinctBy { it.id }, ALBUMS_EACH, seed + a.name.hashCode()) { if (it.id in l.albumsHeard) 3.0 else 1.0 }
                    albums.flatMap { album ->
                        val page = gate.withPermit { runCatching { sources.album(album) }.getOrNull() } ?: return@flatMap emptyList()
                        val standouts = page.popularIds
                        page.songs.filter { s ->
                            s.id !in standouts && s.id !in hits && Heard.key(s.title, s.primaryArtist) !in hits &&
                                s.artists.any { it.name.equals(a.name, ignoreCase = true) } && deepEnough(s)
                        }.map { s -> Triple(s, a, album.id in l.albumsHeard) }
                    }
                }
            }.awaitAll().flatten()
        }
        val known = found.filter { (s, _, _) -> !l.heard.has(s) && !l.refused(s) }.distinctBy { (s, _, _) -> LocalRadio.sameSong(s) }
        fun weigh(t: Triple<Song, LovedArtist, Boolean>): Double {
            val served = l.servedDaysAgo(t.first.id)?.let { d -> if (d <= 1) 0.25 else if (d <= 3) 0.6 else 1.0 } ?: 1.0
            return (if (t.third) 1.5 else 1.0) * served
        }
        val fresh = known.filter { (s, _, _) -> LocalRadio.sameSong(s) !in l.lastWeeks }
        val pool = (if (fresh.size >= DEEP_SIZE) fresh else known)
        val drawn = Variety.sample(pool, DEEP_SIZE * 3, seed + 1, weight = ::weigh).map { it.first }
        val perArtist = maxOf(3, DEEP_SIZE / maxOf(1, drawn.map { it.primaryArtist.lowercase() }.distinct().size) + 1)
        return Made(spread(drawn, DEEP_SIZE, perArtist), artists.map { it.name })
    }

    /** [Variety.arrange] with its artist limit raised only as far as it takes to fill the mix. */
    private fun spread(pool: List<Song>, size: Int, perArtist: Int): List<Song> {
        var best = emptyList<Song>()
        for (limit in perArtist..perArtist + 2) {
            best = Variety.arrange(emptyList(), pool, size, 1.0, limit) { it.primaryArtist.lowercase() }
            if (best.size >= size) break
        }
        return best
    }

    private fun deepEnough(s: Song) = !s.isVideo && !s.isLive && !s.isEpisode && !s.isLocal && (s.durationSec == 0 || s.durationSec in 60..900) &&
        (ImportKeys.versionMarks(s.title) - SAME_SONG).isEmpty()

    companion object {
        const val NEW_SIZE = 30
        const val DEEP_SIZE = 30
        /** Fewer than this isn't a mix: the section stays away. */
        const val MIN_SONGS = 10
        const val SEEDS = 6
        private const val TOP_ARTISTS = 30
        private const val ARTISTS_A_WEEK = 8
        private const val ALBUMS_EACH = 2
        /** Marks that still leave the song itself: whole albums come remastered. */
        private val SAME_SONG = setOf("remaster", "rerecorded", "mono", "clean")

        /** The week a date belongs to: its Monday. What these mixes are made once for. */
        fun weekOf(date: LocalDate): String = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

        /**
         * Favorites from plays: every play counts for how much of the song was heard, fading slowly (half in about four
         * months: "love" outlasts a Daily Mix's fortnight), and a like adds to it.
         */
        fun favorites(plays: List<com.prism.music.data.db.PlayEventEntity>, songs: Map<String, Song>, liked: List<Song>, now: Long): List<Favorite> {
            val weight = HashMap<String, Double>()
            for (p in plays) {
                val song = songs[p.songId] ?: continue
                val age = (now - p.timestamp) / 86_400_000.0
                val dur = song.durationSec * 1000.0
                val heard = if (dur > 0) (p.playedMs / dur).coerceIn(0.1, 1.0) else 0.7
                weight.merge(song.id, heard * exp(-age / 180.0), Double::plus)
            }
            liked.forEach { weight.merge(it.id, 1.5, Double::plus) }
            val all = songs + liked.associateBy { it.id }
            return weight.mapNotNull { (id, w) -> all[id]?.let { Favorite(it, w) } }.sortedByDescending { it.weight }
        }

        /** The artists behind [favorites] (a song's main artist), most loved first, with the [starred] ones on top of that. */
        fun lovedArtists(favorites: List<Favorite>, starred: List<LovedArtist>): List<LovedArtist> {
            val byName = LinkedHashMap<String, LovedArtist>()
            for (f in favorites) {
                val name = f.song.primaryArtist.takeIf { it.isNotBlank() } ?: continue
                val k = name.lowercase()
                val id = f.song.artists.firstOrNull()?.id
                byName[k] = byName[k]?.let { it.copy(weight = it.weight + f.weight, id = it.id ?: id) } ?: LovedArtist(name, id, f.weight)
            }
            val top = byName.values.maxOfOrNull { it.weight } ?: 1.0
            for (s in starred) {
                val k = s.name.lowercase()
                // A starred artist counts as a good part of the top artist, however little they've been played.
                byName[k] = byName[k]?.let { it.copy(weight = it.weight + top * 0.5, id = s.id ?: it.id) } ?: s.copy(weight = top * 0.5)
            }
            return byName.values.sortedByDescending { it.weight }
        }
    }
}

/** "On this day": the day a year before [today], as times to ask the play history between. */
object OnThisDay {
    /** Local midnight to the last moment of that day ([PlayStats] asks BETWEEN, both ends in). A leap day's year-ago is Feb 28. */
    fun window(today: LocalDate, zone: ZoneId): LongRange {
        val day = today.minusYears(1)
        return day.atStartOfDay(zone).toInstant().toEpochMilli() until day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    const val MAX_SONGS = 30
}

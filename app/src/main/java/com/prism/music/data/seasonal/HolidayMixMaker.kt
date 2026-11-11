package com.prism.music.data.seasonal

import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.foryou.Variety
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How a holiday mix is made (1.8.0): each of its [HolidayMix.seeds] searched on YouTube Music signed out (the Songs
 * results, or YouTube Music's own playlists for it), then [assemble]d: one copy of each song (same title and main
 * artist), no karaoke, covers, sped-up or other knock-offs, an artist three times at most and never twice running, and
 * the week's own draw from what was found, so the mix changes a little each week while it's in season.
 */
class HolidayMixMaker(private val sources: Sources, private val atOnce: Int = AT_ONCE) {
    /** Where songs come from. Never the listener's account: these are anonymous searches. */
    interface Sources {
        /** Songs for [query] (YouTube Music's Songs filter), [pages] pages of them. */
        suspend fun songs(query: String, pages: Int): List<Song>
        /** YouTube Music's own playlists for [query] (Featured playlists). */
        suspend fun playlists(query: String): List<PlaylistItem>
        /** A public playlist's songs (its first page). */
        suspend fun playlistSongs(id: String): List<Song>
    }

    /** What one seed found, in YouTube's order ([lists]: the playlists it took them from, for a test to show). */
    data class Found(val seed: Seed, val songs: List<Song>, val lists: List<String> = emptyList())

    suspend fun build(mix: HolidayMix, week: String): List<Song> = assemble(mix, find(mix), week)

    /**
     * Every seed of [mix], [atOnce] requests at a time; a seed that fails finds nothing (the rest still make the mix). A
     * playlist taken by one seed isn't taken again by the next (two seeds finding "Latin Jazz Christmas" first would
     * otherwise make a third of a jazz mix Latin). The playlist searches go first, so their playlists are read while the
     * song searches are still answering, and a seed's playlists are read side by side; what's found is the same, in
     * the same order, however the answers come in.
     */
    suspend fun find(mix: HolidayMix): List<Found> = coroutineScope {
        // The gate lets waiting requests in in turn, so the playlist searches, asked first, mostly go first.
        val gate = Semaphore(atOnce)
        val lists = mix.seeds.map { seed ->
            async {
                if (!seed.playlist) emptyList<PlaylistItem>()
                else runCatching { gate.withPermit { sources.playlists(seed.query) }.filter { listFits(mix, seed, it.title) } }.getOrDefault(emptyList())
            }
        }
        val songs = mix.seeds.map { seed ->
            async {
                if (seed.playlist) emptyList<Song>()
                else runCatching { gate.withPermit { sources.songs(seed.query, SONG_PAGES) } }.getOrDefault(emptyList())
            }
        }
        // Which playlists each seed takes, in the seeds' order (only the playlist searches are waited for).
        val taken = async {
            val seen = HashSet<String>()
            mix.seeds.indices.map { i ->
                val mine = ArrayList<PlaylistItem>()
                for (p in lists[i].await()) {
                    if (mine.size >= mix.seeds[i].take) break
                    if (seen.add(p.id.removePrefix("VL"))) mine += p
                }
                mine
            }
        }
        mix.seeds.mapIndexed { i, seed ->
            async {
                if (!seed.playlist) Found(seed, songs[i].await())
                else {
                    val mine = taken.await()[i]
                    val read = mine.map { p ->
                        async { gate.withPermit { runCatching { sources.playlistSongs(p.id) }.getOrDefault(emptyList()) }.take(PER_PLAYLIST) }
                    }.awaitAll()
                    Found(seed, read.flatten(), mine.map { it.title })
                }
            }
        }.awaitAll()
    }

    companion object {
        /** About fifty songs a mix. */
        const val SIZE = 50
        const val PER_ARTIST = 3
        /** Requests a mix has out at once (it's about twenty: searches, their second pages, playlists). */
        const val AT_ONCE = 6
        private const val SONG_PAGES = 2
        private const val PER_PLAYLIST = 80

        /**
         * The mix from what its seeds [found]: each song weighed by how high and how often it came up (a playlist's
         * songs count as found there), the week's draw of [SIZE] of them (heavier ones likelier), then arranged so no
         * artist comes more than [PER_ARTIST] times or twice running.
         */
        fun assemble(mix: HolidayMix, found: List<Found>, week: String): List<Song> {
            val weight = HashMap<String, Double>()
            val first = LinkedHashMap<String, Song>()
            for ((seed, songs) in found) {
                songs.forEachIndexed { rank, s ->
                    if (!fits(mix, s, fromPlaylist = seed.playlist)) return@forEachIndexed
                    val key = LocalRadio.sameSong(s)
                    // A music video counts a little less than the song itself, which takes its place when both are found.
                    weight.merge(key, seed.weight / (1.0 + rank / 12.0) * (if (s.isVideo) 0.8 else 1.0), Double::plus)
                    if (first[key]?.isVideo != false) first[key] = s.takeIf { !it.isVideo } ?: first[key] ?: s
                }
            }
            val pool = first.entries.map { it.value to (weight[it.key] ?: 0.0) }
            // More than the mix needs, so the artist limit still leaves it full.
            val drawn = Variety.sample(pool, SIZE * 2, Variety.daySeed(week, mix.id), sharpness = 1.4) { it.second }
                .sortedByDescending { it.second }.map { it.first }
            val arranged = Variety.arrange(drawn, emptyList(), SIZE, 0.0, PER_ARTIST) { it.primaryArtist.lowercase() }
            // Near the end only one artist may be left to take: one song shorter rather than the same artist twice running.
            return arranged.filterIndexed { i, s -> i == 0 || !s.primaryArtist.equals(arranged[i - 1].primaryArtist, ignoreCase = true) }
        }

        /**
         * Whether one of YouTube Music's playlists, by its [title], is one to take a mix's songs from: a Christmas
         * playlist for a Christmas mix (its songs are then taken as Christmas songs, so "80s Pop Party" for "holiday
         * classics" won't do), none for a mix that isn't about Christmas.
         */
        fun listFits(mix: HolidayMix, seed: Seed, title: String): Boolean {
            val t = SearchRank.norm(title.replace('-', ' '))
            if (HALLOWEEN.containsMatchIn(t)) return false
            if (mix.holidayOnly && !HOLIDAY_WORDS.containsMatchIn(t)) return false
            if (mix.noHoliday && CHRISTMAS_WORDS.containsMatchIn(t)) return false
            // About what the seed asked for: one of its words (or [Seed.lists]) in the title, as a word or a word's start
            // ("classic" in "Classical Christmas"; "r" only as "R&B").
            val want = seed.lists?.map { SearchRank.norm(it) } ?: SearchRank.norm(seed.query.replace('-', ' ')).split(" ")
                .filter { it.isNotEmpty() && it !in LIST_STOP && !HOLIDAY_WORDS.matches(it) }
            if (want.isEmpty()) return true
            val words = t.split(" ").filter { it.isNotEmpty() }
            return want.any { w -> if (' ' in w) t.contains(w) else words.any { sameWord(it, w) } }
        }

        private fun sameWord(a: String, b: String) =
            a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))

        private val LIST_STOP = setOf("the", "and", "of", "songs", "song", "music", "hits", "a", "for")

        /** Whether [s] belongs in [mix] at all (before it's weighed). */
        fun fits(mix: HolidayMix, s: Song, fromPlaylist: Boolean): Boolean = whyNot(mix, s, fromPlaylist) == null

        /** Why [s] is left out of [mix] (a word for a test's report), or null when it belongs. */
        fun whyNot(mix: HolidayMix, s: Song, fromPlaylist: Boolean): String? {
            // YouTube Music's own playlists hold many of their songs as the music video: those are welcome (they play as
            // the song's sound, as anywhere else in Prism); a search's videos aren't (lyric videos, live clips, fans').
            if ((s.isVideo && !fromPlaylist) || s.isLive || s.isEpisode || s.isLocal || s.isUpload) return "not a song"
            if (s.durationSec != 0 && s.durationSec !in 60..900) return "length"
            if (s.primaryArtist.isBlank()) return "no artist"
            if (isKnockoff(s, mix.asks)) return "knock-off"
            if (isFiller(mix, s, fromPlaylist)) return "filler"
            val words = SearchRank.norm(s.title + " " + s.album?.title.orEmpty())
            if (HALLOWEEN.containsMatchIn(words)) return "halloween"
            if (mix.noHoliday && CHRISTMAS_WORDS.containsMatchIn(words)) return "christmas"
            // A search for "rock christmas" turns up the band's other songs too; YouTube Music's own Christmas playlists
            // are Christmas songs throughout.
            if (mix.holidayOnly && !fromPlaylist && !HOLIDAY_WORDS.containsMatchIn(words)) return "not the holiday"
            return null
        }

        /**
         * A version that isn't the song: karaoke, a cover, sped up, an instrumental, a tribute act… by the marks in its
         * title's brackets or tail ([SearchRank.knockoffMarks], less what the mix itself [asks] for), or by who made it
         * (karaoke and tribute "artists", "made famous by" uploads).
         */
        fun isKnockoff(s: Song, asks: Set<String> = emptySet()): Boolean {
            val asked = asks.joinToString(" ")
            if (SearchRank.knockoffMarks(s.title, SearchRank.norm(asked)).isNotEmpty()) return true
            val artist = SearchRank.norm(s.artistText)
            if (KNOCKOFF_ARTISTS.containsMatchIn(artist)) return true
            val title = SearchRank.norm(s.title)
            // "Sped Up" and "Karaoke Version" with no brackets round them.
            return KNOCKOFF_TITLES.findAll(title).any { m -> m.value.split(" ").any { it !in asks.flatMap { a -> a.split(" ") } } }
        }

        /**
         * Uploads made to be found rather than heard: "Christmas Jazz" by "Jazzy Christmas", "Lofi Christmas Study Beats",
         * "Classic Country Christmas Songs for You" by "Country Christmas Music All-Stars". By an "artist" named after a
         * genre or a playlist, a title made for searches (a "|" list of keywords, "for studying"), or (from a search) a
         * title of nothing but the search's own words.
         */
        fun isFiller(mix: HolidayMix, s: Song, fromPlaylist: Boolean): Boolean {
            if ('|' in s.title) return true
            val asked = mix.asks.flatMap { it.split(" ") }.toSet()
            if (s.artists.any { a -> FILLER_ARTISTS.findAll(SearchRank.norm(a.name)).any { m -> m.value.split(" ").any { it !in asked } } }) return true
            val title = SearchRank.norm(s.title)
            if (FILLER_TITLES.containsMatchIn(title)) return true
            if (!fromPlaylist) {
                val words = title.split(" ").filter { it.isNotEmpty() }
                val generic = GENERIC + mix.seeds.flatMap { SearchRank.norm(it.query).split(" ") }
                if (words.size >= 2 && words.all { it in generic }) return true
            }
            return false
        }

        private val FILLER_ARTISTS = Regex(
            "\\b(christmas|xmas|santa claus|lofi|lo fi|music|songs|hits|beats|relaxing|all stars|allstars|dj collective|djs collective|party dj|party djs|new years|new year|piano covers|cafe|lounge|studio|sounds|playlist|vibes|chill|instrumentals?)\\b",
        )
        private val FILLER_TITLES = Regex(
            "\\b(for (streaming|study|studying|sleep|sleeping|relaxing|relaxation|focus|work|working|the office|office)|study beats|no lyrics|background music|playlist|compilation|non stop|nonstop|1 hour|one hour|(music|songs|beats|tracks|sounds|vibes) (to|for)|atmosphere|ambience|ambiance)\\b",
        )
        private val GENERIC = setOf(
            "music", "songs", "song", "beats", "mix", "playlist", "vibes", "best", "top", "hits", "the", "for", "of", "and", "a",
            "you", "your", "my", "to", "with", "in", "on", "edition", "version", "vol", "classic", "classics", "party", "dance",
            "holiday", "holidays", "lofi", "lo", "fi", "jazz", "pop", "rock", "country", "hip", "hop", "rap", "rnb", "r", "b",
            "soul", "christmas", "xmas", "cozy", "chill", "acoustic", "fall", "autumn", "kids", "family", "piano", "smooth",
        )
        private val HALLOWEEN = Regex("\\b(halloween|spooky|witch|witches|zombie|haunted)\\b")

        private val KNOCKOFF_ARTISTS = Regex(
            "\\b(karaoke|tribute|made famous|sing king|backing track|cover band|the hit crew|party tyme|ameritz|zoom karaoke|sunfly|8d audio|nightcore)\\b",
        )
        private val KNOCKOFF_TITLES = Regex("\\b(karaoke|sped up|slowed( and| ?\\+ ?)? reverb|nightcore|8d audio|made famous|in the style of|backing track)\\b")

        /** Christmas itself: left out of the mixes that aren't about it (November's, Winter Chill, New Year's Eve). */
        private val CHRISTMAS_WORDS = Regex(
            "\\b(christmas|xmas|navidad|navideno|navidena|noel|santa|sleigh|jingle|mistletoe|rudolph|reindeer|silent night|holy night|nativity|feliz navidad|deck the halls|little drummer|frosty|north pole|elves|nochebuena|villancico)\\b",
        )
        /** The season, for a December mix's search results: Christmas, winter and the new year. */
        private val HOLIDAY_WORDS = Regex(
            "\\b(christmas|xmas|navidad|navideno|navidena|noel|nol|santa|claus|sleigh|jingle|bells?|mistletoe|rudolph|reindeer|snow|snowman|winter|wonderland|holiday|holidays|december|silent night|holy night|carol|carols|nativity|bethlehem|manger|angels?|hark|joy to the world|merry|feliz|nochebuena|villancicos?|aguinaldo|posada|parranda|reyes|nieve|frosty|elf|elves|north pole|chimney|stocking|presents?|gifts?|ornaments?|tree|mele kalikimaka|auld lang syne|new year|ano nuevo|nutcracker|grinch|yule|yuletide|advent|o come|emmanuel|first noel|wise men|kings|peace on earth|cold outside|fireplace|cocoa|eggnog|tinsel|candy cane|gingerbread)\\b",
        )
    }
}

/** [HolidayMixMaker.Sources] from YouTube Music, signed out ([com.prism.music.data.innertube.InnerTube.postAnonymous]). */
class YouTubeHolidaySources(private val ytm: YouTubeMusic) : HolidayMixMaker.Sources {
    override suspend fun songs(query: String, pages: Int): List<Song> {
        val out = ArrayList<Song>()
        var page = ytm.search(query, SearchFilter.SONGS)
        repeat(pages) { i ->
            out += page.items.filterIsInstance<SongItem>().map { it.song }
            val next = page.continuation ?: return out
            if (i < pages - 1) page = runCatching { ytm.search(query, SearchFilter.SONGS, next) }.getOrNull() ?: return out
        }
        return out
    }

    override suspend fun playlists(query: String): List<PlaylistItem> =
        ytm.search(query, SearchFilter.FEATURED_PLAYLISTS).items.filterIsInstance<PlaylistItem>().filter { !it.isMix }

    override suspend fun playlistSongs(id: String): List<Song> = ytm.publicPlaylistSongs(id)
}

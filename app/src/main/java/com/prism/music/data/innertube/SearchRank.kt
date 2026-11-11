package com.prism.music.data.innertube

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import java.text.Normalizer
import kotlin.math.max

/** "Top results": the single best match, then each kind of result, best-matching kind first. */
data class TopSearch(val top: BrowseItem?, val sections: List<Pair<SearchFilter, List<BrowseItem>>>) {
    val isEmpty: Boolean get() = top == null && sections.isEmpty()
}

/**
 * Ranks results from YouTube Music's per-kind searches. Its own mixed search leans on
 * whatever is trending ("AM" turns up podcasts), so Prism asks for songs, albums, artists,
 * playlists and videos separately and judges how well each matches what was typed, and
 * how well it fits what the listener plays ([Taste]).
 */
object SearchRank {
    /** An artist the listener knows, with how much (0..1). */
    data class KnownArtist(val id: String?, val name: String, val thumbnail: String?, val familiarity: Double)

    /**
     * What the listener plays, for leaning results their way: "A Day" should find A Day to
     * Remember for someone who plays them, before an EP of that name. Built on the phone from
     * play history, likes and the library ([com.prism.music.data.TasteRepository.searchTaste]).
     */
    class Taste(artists: List<KnownArtist>, val songs: Map<String, Double>) {
        val artists: Map<String, KnownArtist> = artists.groupBy { norm(it.name) }.mapValues { (_, l) -> l.maxBy { it.familiarity } }
        fun artist(name: String): Double = artists[norm(name)]?.familiarity ?: 0.0

        /** The artists best known first, each name read once: [predict] goes through all of them for every letter typed. */
        internal val names: List<ArtistName> by lazy {
            this.artists.entries.sortedByDescending { it.value.familiarity }
                .map { (n, a) -> ArtistName(a.name, n, n.split(" "), initialsOf(a.name)) }
        }

        companion object { val NONE = Taste(emptyList(), emptyMap()) }
    }

    /** An artist's name as [predict] compares it: normalized, in words, and its initials (null for a single word). */
    internal class ArtistName(val name: String, val norm: String, val words: List<String>, val initials: String?) {
        fun fits(q: String): Boolean = norm != q && (norm.startsWith(q) || (q.length >= 3 && words.any { it.startsWith(q) }) ||
            (q.length >= 2 && !q.contains(' ') && initials?.startsWith(q) == true))
    }

    /** How far a result the listener knows well can climb past a closer match they don't. */
    private const val TASTE_BOOST = 0.45

    /** 0..1: how much the listener plays [item] (or its artist). */
    fun familiarity(item: BrowseItem, taste: Taste): Double = when (item) {
        is ArtistItem -> taste.artist(item.title)
        is SongItem -> maxOf(taste.songs[item.id] ?: 0.0, 0.6 * (item.song.artists.maxOfOrNull { taste.artist(it.name) } ?: 0.0))
        // "Album • A Day to Remember • 2009"
        is AlbumItem -> 0.7 * (item.subtitle.split(" • ").maxOfOrNull { taste.artist(it) } ?: 0.0)
        else -> 0.0
    }

    /** Every typed word is a whole word of the title ("a day" in "A Day to Remember", not "halo" in "Halocene"). */
    private fun wholeWords(query: String, title: String): Boolean {
        val t = norm(title).split(" ").toSet()
        return norm(query).split(" ").all { it in t }
    }

    private val limits = mapOf(
        SearchFilter.SONGS to 6, SearchFilter.ALBUMS to 8, SearchFilter.ARTISTS to 6,
        SearchFilter.PLAYLISTS to 8, SearchFilter.VIDEOS to 6,
    )

    /**
     * Lower case, no accents or punctuation, "&" as "and", single spaces. It walks the string by hand (#355): it used to
     * compile three Regexes per call, and on a Galaxy A16 those compiles were two thirds of the main thread's time while
     * typing, since both search boxes called this hundreds of times for each letter.
     */
    fun norm(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val out = StringBuilder(d.length)
        var space = false
        var i = 0
        while (i < d.length) {
            val cp = d.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == ' '.code -> space = true
                cp == '&'.code -> { if (out.isNotEmpty()) out.append(' '); out.append("and"); space = true }
                isWordPart(cp) -> { if (space && out.isNotEmpty()) out.append(' '); space = false; out.appendCodePoint(cp) }
                // Anything else goes, without leaving a gap: "AC/DC" is "acdc". That includes every combining mark.
            }
        }
        return out.toString()
    }

    /** A letter or a number of any script. */
    private fun isWordPart(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private val bracketed = Regex("\\s*[(\\[].*?[)\\]]")
    private val dashTail = Regex("\\s+-\\s+.*$")

    /** "1989 (Taylor's Version)" and "Song - Remastered 2011" match "1989" and "Song". */
    private fun core(title: String) = norm(title.replace(bracketed, "").replace(dashTail, ""))

    /** 0..1: how well [title] (with [extra], e.g. the artist) matches the query. */
    fun match(query: String, title: String, extra: String = ""): Double {
        val q = norm(query)
        if (q.isEmpty()) return 0.0
        val t = norm(title)
        if (t == q) return 1.0
        // "After Hours (Instrumental)" by a cover act isn't the After Hours that was meant.
        if (core(title) == q) return if (knockoffMarks(title, q).isNotEmpty()) 0.7 else 0.97
        val qWords = q.split(" ")
        val all = norm("$title $extra").split(" ").toSet()
        // Title and artist typed together ("do i wanna know arctic monkeys").
        if (qWords.all { it in all } && core(title).split(" ").all { it in qWords }) return 0.95
        // An abbreviation: "adtr" is A Day To Remember, "rhcp" Red Hot Chili Peppers. Exact titles above still win
        // (an artist really called "NF"); a song by the abbreviated artist counts for something too.
        if (initialsOf(title) == q) return 0.9
        if (t.startsWith(q)) return 0.75
        if (qWords.all { w -> all.any { it.startsWith(w) } }) return 0.6
        if (extra.isNotBlank() && extra.split(" • ", ", ", " & ").any { initialsOf(it) == q }) return 0.55
        if (t.contains(q)) return 0.45
        return 0.2
    }

    /** Words that make a version something other than the song: karaoke, covers, sped-up edits, flips… */
    private val knockoff = Regex(
        "\\b(instrumental|karaoke|cover|tribute|sped up|slowed|nightcore|8d|lofi|lo fi|piano version|made famous|flip|remix|mashup|bootleg)\\b",
    )

    private val titleTails = Regex("[(\\[]([^)\\]]*)[)\\]]|\\s-\\s(.*)$")

    /** Such words in [title]'s brackets or " - …" tail that the query [q] (normalised) doesn't ask for. */
    internal fun knockoffMarks(title: String, q: String): List<String> {
        val tails = titleTails.findAll(title).map { norm(it.value) }.joinToString(" ")
        val asked = q.split(" ").toSet()
        return knockoff.findAll(tails).map { it.value }.filter { m -> m.split(" ").any { it !in asked } }.toList()
    }

    /** "A Day To Remember" -> "adtr"; null for a single word (its initial is no abbreviation). */
    private fun initialsOf(name: String): String? {
        val words = core(name).split(" ").filter { it.isNotEmpty() }
        if (words.size < 2) return null
        return words.joinToString("") { it.take(1) }
    }

    /**
     * How strongly YouTube's own "Top result" card counts: above a near match or a literal title from someone unknown
     * ("Bohemian", an EP, against Queen); a match from an artist the listener plays can still climb past it.
     */
    private const val CARD_PRIOR = 0.95

    /**
     * "444M monthly audience", "3.55K subscribers" -> a number. In other languages too ("월간 시청자 7250만명",
     * "65 Mio. Zuschauer/Monat", "72,5 jt audiens bulanan", "มีผู้ชมรายเดือน 65 ล้าน คน"): the first number in the
     * artist's subtitle with the size word right after it.
     */
    internal fun audience(subtitle: String): Long = count(subtitle.substringAfter(" • ", subtitle))

    /**
     * The first number in [text] with its size word ("189M plays", "189 M reproducciones", "7963만회 재생",
     * "79 Mio. Wiedergaben", "조회수 28만회", "443.157 Aufrufe"); 0 when there's none.
     */
    internal fun count(text: String): Long {
        val m = audienceRegex.find(text) ?: return 0
        val raw = m.groupValues[1]
        // "72,5" / "7.25" are decimals, "789 000" / "1,234" thousands.
        val number = Regex("[.,](\\d{1,2})$").find(raw)?.let { d ->
            (raw.dropLast(d.value.length).replace(Regex("[.,  ]"), "") + "." + d.groupValues[1]).toDoubleOrNull()
        } ?: raw.replace(Regex("[.,  ]"), "").toDoubleOrNull() ?: return 0
        val unit = m.groupValues[2].lowercase().trimEnd('.')
        val mult = audienceUnits.entries.firstOrNull { (k, _) -> unit.startsWith(k) }?.value ?: 1.0
        return (number * mult).toLong()
    }

    // Letters plus combining marks: Thai "ล้าน" and Hindi "लाख" carry vowel signs.
    private val audienceRegex = Regex("(\\d[\\d.,  ]*\\d|\\d)\\s*([\\p{L}\\p{M}.]*)")
    private val audienceUnits = linkedMapOf(
        // Checked in order by prefix: longer words before the shorter ones they start with ("mil" before "mi", "mln" before "m").
        "mrd" to 1e9, "bn" to 1e9, "億" to 1e8, "亿" to 1e8, "억" to 1e8, "crore" to 1e7, "क" to 1e7, "lakh" to 1e5, "लाख" to 1e5,
        "mil" to 1e3, "mio" to 1e6, "mln" to 1e6, "млн" to 1e6, "mn" to 1e6, "mi" to 1e6, "jt" to 1e6, "tr" to 1e6, "ล้าน" to 1e6,
        "مليون" to 1e6, "ألف" to 1e3, "萬" to 1e4, "万" to 1e4, "만" to 1e4, "тыс" to 1e3, "тис" to 1e3, "tys" to 1e3, "tsd" to 1e3,
        "rb" to 1e3, "k" to 1e3, "m" to 1e6, "b" to 1e9,
    )

    /** How strongly each kind of result answers an exact match. */
    private fun weight(item: BrowseItem): Double = when (item) {
        is ArtistItem -> if (audience(item.subtitle) >= 1_000_000) 1.0 else 0.8
        is AlbumItem -> if (item.subtitle.startsWith("Single")) 0.8 else 0.93
        is SongItem -> if (item.song.isVideo) 0.72 else 0.86
        is PlaylistItem -> 0.62
        else -> 0.5
    }

    fun score(query: String, item: BrowseItem, position: Int, taste: Taste = Taste.NONE): Double {
        val extra = when (item) {
            is SongItem -> item.song.artistText
            else -> item.subtitle
        }
        val m = match(query, item.title, extra)
        val fam = if (m >= 0.6) familiarity(item, taste) else 0.0
        // An artist the listener plays counts as a big one, whatever YouTube says of their audience.
        val w = if (item is ArtistItem && fam > 0.0) 1.0 else weight(item)
        // YouTube's own order within each kind stands in for popularity (a cover album ranks below the original).
        val base = m * w - position * 0.08
        // What the listener plays climbs, but only when it really answers what was typed;
        // half a word ("halo" of "Halocene") only nudges it.
        if (fam <= 0.0) return base
        val fit = if (m >= 0.95 || wholeWords(query, item.title)) 1.0 else 0.2
        return base + TASTE_BOOST * fam * fit
    }

    /**
     * Artists the listener plays whose name starts with every word typed ("a day" → A Day to
     * Remember), for when YouTube's artist search leaves them out.
     */
    fun knownArtists(query: String, taste: Taste): List<ArtistItem> {
        val q = norm(query)
        // Too short to mean one artist ("the" starts half the library).
        if (q.length < 4) return emptyList()
        return taste.artists.values
            .filter { a -> a.id != null && a.familiarity >= 0.5 && norm(a.name).let { n -> n == q || n.startsWith("$q ") } }
            .sortedByDescending { it.familiarity }
            .take(2)
            .map { ArtistItem(it.id!!, it.name, "Artist", it.thumbnail) }
    }

    /**
     * Completions from the listener's own world, for the suggestions under the search box while typing: recent searches
     * and known artists that start with what's typed (or whose initials it is, "adt" → A Day To Remember), best known first.
     */
    fun predict(query: String, taste: Taste, recents: List<String>, limit: Int = 3): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val fromRecents = recents.asSequence().map { it to norm(it) }.filter { (_, n) -> n.startsWith(q) && n != q }
        val fromArtists = taste.names.asSequence().filter { it.fits(q) }.map { it.name to it.norm }
        return (fromRecents + fromArtists).distinctBy { it.second }.take(limit).map { it.first }.toList()
    }

    /** [song] is its album's title track and the query names exactly that ("after hours" → After Hours). */
    fun namesItsAlbum(query: String, song: com.prism.music.data.model.Song): Boolean {
        val album = song.album ?: return false
        return core(album.title) == core(song.title) && core(album.title) == norm(query)
    }

    /** [album] becomes the top result; the song that was there leads the Songs section instead. */
    fun withAlbumOnTop(r: TopSearch, album: AlbumItem): TopSearch {
        val song = r.top as? SongItem
        val sections = r.sections.map { (f, items) ->
            val rest = items.filter { it.id != album.id }
            f to if (f == SearchFilter.SONGS && song != null) listOf(song) + rest else rest
        }.filter { it.second.isNotEmpty() }
        val withSong = if (song == null || sections.any { it.first == SearchFilter.SONGS }) sections
            else listOf(SearchFilter.SONGS to listOf<BrowseItem>(song)) + sections
        return TopSearch(album, withSong)
    }

    /**
     * Ranks [results]; live streams ([SearchFilter.LIVE]) always get a row of their own, last, and don't
     * compete for the top spot unless there's nothing else. [pageSongs]: the songs on the page of YouTube's card.
     */
    fun rank(
        query: String, results: List<Pair<SearchFilter, List<BrowseItem>>>, taste: Taste = Taste.NONE, card: BrowseItem? = null,
        pageSongs: List<SongItem> = emptyList(),
    ): TopSearch {
        val live = results.firstOrNull { it.first == SearchFilter.LIVE }?.second.orEmpty().distinctBy { it.id }.take(10)
        val music = withPageSongs(norm(query), results.filter { it.first != SearchFilter.LIVE }, pageSongs)
        val ranked = rankMusic(query, music, taste, card?.takeUnless { it is SongItem && it.song.isLive })
        if (live.isEmpty()) return ranked
        val top = ranked.top ?: live.first()
        val sections = ranked.sections.toMutableList()
        val row = live.filter { it != top }
        if (row.isNotEmpty()) sections.add(SearchFilter.LIVE to row)
        return TopSearch(top, sections)
    }

    /**
     * YouTube's card, signed out, is sometimes a knock-off ("after hours" → an instrumental cover, "blinding lights" →
     * a reggae flip): a version the query didn't ask for gets no head start.
     */
    private fun trustCard(query: String, card: BrowseItem): Boolean = knockoffMarks(card.title, norm(query)).isEmpty()

    /** [item] is a song or video of exactly the title typed, not a karaoke or cover version of it. */
    private fun exactSong(q: String, item: BrowseItem): Boolean =
        item is SongItem && core(item.title) == q && knockoffMarks(item.title, q).isEmpty()

    /**
     * A title several artists share ("upside down": Jack Johnson, Diana Ross, a trending Hazbin Hotel song): those
     * songs most played first, each taking a place one of them held, the rest of the row as YouTube had it. YouTube's
     * own order leans on what's trending; the song most people mean is the one most people play.
     */
    internal fun byPlays(q: String, items: List<BrowseItem>): List<BrowseItem> {
        val slots = items.indices.filter { exactSong(q, items[it]) && (items[it] as SongItem).plays != null }
        if (slots.size < 2) return items
        val ordered = slots.map { items[it] }.sortedByDescending { (it as SongItem).plays }
        return items.toMutableList().also { out -> slots.forEachIndexed { k, i -> out[i] = ordered[k] } }
    }

    /**
     * Songs of exactly the title typed that YouTube's song search left out but its unfiltered page shows join the
     * songs, at the end ([byPlays] then gives the most played the first place one of them holds). "halo": the song
     * search had a Halo game soundtrack and LP's live Halo, the page Beyoncé's Halo (2.7B plays).
     */
    private fun withPageSongs(q: String, results: List<Pair<SearchFilter, List<BrowseItem>>>, page: List<SongItem>): List<Pair<SearchFilter, List<BrowseItem>>> {
        val row = results.firstOrNull { it.first == SearchFilter.SONGS }?.second.orEmpty()
        val extra = page.filter { s -> exactSong(q, s) && (s.plays ?: 0) >= KNOWN_SONG && row.none { it.id == s.id } }.distinctBy { it.id }
        if (extra.isEmpty()) return results
        return if (results.any { it.first == SearchFilter.SONGS }) results.map { (f, items) -> if (f == SearchFilter.SONGS) f to (items + extra) else f to items }
        else results + (SearchFilter.SONGS to extra)
    }

    /** From this many plays, a song is one people mean when they type its title. */
    private const val KNOWN_SONG = 1_000_000L
    /** From this many, a hit: it outranks a card that doesn't even name it. */
    private const val HIT_SONG = 50_000_000L

    /**
     * Whether YouTube's card stands against [hit], the most played song of exactly the title typed. A song card holds
     * when it's that song's artist, or played nearly as often ([cardPlays]: its song's plays, else its video's views);
     * a card of another kind when it names exactly what was typed, or [hit] isn't a hit. "upside down": the trending
     * card (79M plays) gives way to Jack Johnson's song (189M); "dreams": a fan video to Fleetwood Mac's.
     */
    private fun cardHolds(q: String, card: BrowseItem, cardPlays: Long?, hit: SongItem?): Boolean {
        if (hit == null || (hit.plays ?: 0) < KNOWN_SONG) return true
        if (card is SongItem) {
            if (hit.song.primaryArtist.isNotBlank() && card.song.artists.any { norm(it.name) == norm(hit.song.primaryArtist) }) return true
            return (cardPlays ?: 0) * 3 >= (hit.plays ?: 0) * 2
        }
        if (yieldsToHit(card, hit)) return false
        return match(q, card.title) >= 0.97 || (hit.plays ?: 0) < HIT_SONG
    }

    /** From this many plays, a song outranks another artist's soundtrack, cover or tribute collection of its title. */
    private const val COLLECTION_HIT = 20_000_000L
    /**
     * From this many, it outranks another artist's album of its title that came out this year or last ("africa" → a
     * 2026 album by someone new, against Toto's Africa). Not an older one, however big the song: "AM" is Arctic
     * Monkeys' album (2013), not Nio Garcia's AM (491M plays); "lemonade" Beyoncé's (2016).
     */
    private const val BIG_HIT = 100_000_000L

    /** The year in an album's subtitle ("Album • Buddy DML • 2026") is this year or last. */
    private fun isNew(item: BrowseItem): Boolean {
        val year = item.subtitle.split(" • ").lastOrNull { it.trim().matches(Regex("\\d{4}")) }?.trim()?.toInt() ?: return false
        return year >= java.time.Year.now().value - 1
    }

    /** Collections of other people's songs: a film's soundtrack, a cast recording, covers, a tribute, karaoke. */
    private val collection = Regex(
        "\\b(soundtrack|ost|music from|original cast|cast recording|tribute|covers?|karaoke|made famous|instrumental)\\b",
    )

    /**
     * Another artist's album or playlist of the title that gives way to [hit]: a collection of other people's songs
     * ("yesterday" → the 2019 film's soundtrack album, Himesh Patel, against The Beatles' Yesterday), or a new album
     * against a big song. The hit's own album stands ("after hours", "thriller": the title track gives way to it
     * further down).
     */
    private fun yieldsToHit(item: BrowseItem, hit: SongItem): Boolean {
        if (item !is AlbumItem && item !is PlaylistItem) return false
        val plays = hit.plays ?: return false
        val artist = norm(hit.song.primaryArtist)
        if (artist.isNotBlank() && item.subtitle.split(" • ").any { norm(it) == artist }) return false
        return (plays >= BIG_HIT && item is AlbumItem && isNew(item)) ||
            (plays >= COLLECTION_HIT && collection.containsMatchIn(norm("${item.title} ${item.subtitle}")))
    }

    /**
     * An artist only called what was typed doesn't take the top from a song far better known ("roar": the artist ROAR,
     * 12M a month, against Katy Perry's Roar, played billions of times). Monthly listeners against all-time plays,
     * so the song has to be played [SONG_OVER_ARTIST] times the artist's audience.
     */
    private const val SONG_OVER_ARTIST = 30

    /** The section YouTube's card belongs in. */
    private fun kindOf(item: BrowseItem): SearchFilter? = when (item) {
        is ArtistItem -> SearchFilter.ARTISTS
        is AlbumItem -> SearchFilter.ALBUMS
        is PlaylistItem -> SearchFilter.PLAYLISTS
        is SongItem -> if (item.song.isVideo) SearchFilter.VIDEOS else SearchFilter.SONGS
        else -> null
    }

    private fun rankMusic(query: String, results: List<Pair<SearchFilter, List<BrowseItem>>>, taste: Taste, card: BrowseItem? = null): TopSearch {
        // The listener's own artists lead YouTube's artist results when they fit and are missing from them.
        val known = knownArtists(query, taste)
        val withKnown = results.map { (f, items) ->
            if (f != SearchFilter.ARTISTS) f to items
            else f to (known.filter { k -> items.none { it.id == k.id || norm(it.title) == norm(k.title) } } + items)
        }.let { list ->
            if (list.any { it.first == SearchFilter.ARTISTS } || known.isEmpty()) list
            else list + (SearchFilter.ARTISTS to known)
        }
        // YouTube's card also leads its own kind's row (its per-kind search sometimes leaves it out, or low down).
        // A music video as the card ("humble kendrick" → HUMBLE.'s video) gives way to the song itself when the
        // song results have it: Prism opens videos in video mode, and most searches are for the music.
        val q = norm(query)
        val songs = results.firstOrNull { it.first == SearchFilter.SONGS }?.second.orEmpty().filterIsInstance<SongItem>()
        // The video's song: the same title by one of its artists ("Shallow"'s video lists Bradley Cooper; the song
        // Lady Gaga and Bradley Cooper).
        val asSong = (card as? SongItem)?.takeIf { it.song.isVideo }?.let { v ->
            val by = v.song.artists.map { norm(it.name) }.filter { it.isNotBlank() }.toSet()
            songs.firstOrNull { s -> core(s.title) == core(v.title) && s.song.artists.any { norm(it.name) in by } }
        }
        // The most played song of exactly this title, as YouTube's song search shows them.
        val hit = songs.filter { exactSong(q, it) && it.plays != null }.maxByOrNull { it.plays!! }
        val cardPlays = (asSong ?: card as? SongItem)?.let { c -> songs.firstOrNull { it.id == c.id }?.plays ?: c.plays }
        val trusted = (asSong ?: card)?.takeIf { trustCard(query, it) && cardHolds(q, it, cardPlays, hit) }
        val cardKind = trusted?.let(::kindOf)
        val withCard = if (trusted == null || cardKind == null) withKnown else {
            val card: BrowseItem = trusted
            val list = withKnown.map { (f, items) -> if (f == cardKind) f to (listOf(card) + items.filter { it.id != card.id }) else f to items }
            if (list.any { it.first == cardKind }) list else list + (cardKind to listOf(card))
        }
        // Songs and videos of the same title: the most played first (the card, when it holds, stays first).
        val trimmed = withCard.mapNotNull { (f, items) ->
            val distinct = items.distinctBy { it.id }
            val ordered = when {
                f != SearchFilter.SONGS && f != SearchFilter.VIDEOS -> distinct
                trusted != null && distinct.firstOrNull()?.id == trusted.id -> listOf(distinct.first()) + byPlays(q, distinct.drop(1))
                else -> byPlays(q, distinct)
            }
            ordered.take(limits[f] ?: 6).takeIf { it.isNotEmpty() }?.let { f to it }
        }
        val scored = trimmed.map { (f, items) ->
            Triple(f, items, items.mapIndexed { i, it ->
                // The card sits first in its row by Prism's doing, so it's scored as YouTube's first, at least at the prior.
                if (trusted != null && it.id == trusted.id) maxOf(score(query, it, 0, taste), CARD_PRIOR)
                else score(query, it, i, taste)
            })
        }
        var top = scored.flatMap { (_, items, scores) -> items.zip(scores).take(3) }.maxByOrNull { it.second }?.first
        (top as? ArtistItem)?.let { a ->
            val audience = audience(a.subtitle)
            val plays = hit?.plays ?: 0
            if (norm(a.title) == q && familiarity(a, taste) <= 0.0 && audience > 0 && plays >= SONG_OVER_ARTIST * audience) top = hit
        }
        // Nor does another artist's album or playlist of the title, scored on its own, against a far bigger song.
        if (hit != null && top != null && familiarity(top!!, taste) <= 0.0 && yieldsToHit(top!!, hit)) top = hit
        // A title track ("After Hours" by The Weeknd) gives way to the album it names.
        (top as? SongItem)?.song?.let { song ->
            val albums = trimmed.firstOrNull { it.first == SearchFilter.ALBUMS }?.second.orEmpty()
            albums.filterIsInstance<AlbumItem>().firstOrNull { a ->
                !a.subtitle.startsWith("Single") && core(a.title) == core(song.title) && core(a.title) == norm(query) &&
                    song.primaryArtist.isNotBlank() && a.subtitle.contains(song.primaryArtist, ignoreCase = true)
            }?.let { top = it }
        }
        // With an artist on top ("adtr" → A Day To Remember), their songs and albums are what's wanted next.
        val topArtist = (top as? ArtistItem)?.title?.let(::norm)
        fun byTopArtist(item: BrowseItem): Boolean = topArtist != null && when (item) {
            is SongItem -> item.song.artists.any { norm(it.name) == topArtist }
            is AlbumItem -> item.subtitle.split(" • ").any { norm(it) == topArtist }
            else -> false
        }
        val sections = scored
            .sortedByDescending { (f, items, scores) ->
                // Ranked by what's left once the top result is lifted out of its row.
                val best = items.zip(scores).filter { it.first != top }.maxOfOrNull { it.second } ?: 0.0
                val related = if (items.any(::byTopArtist)) 0.8 else 0.0
                // Songs stay near the top: they're what most searches are for.
                maxOf(best, related, if (f == SearchFilter.SONGS) 0.5 else 0.0)
            }
            .map { (f, items, _) -> f to items.filter { it != top } }
            .filter { it.second.isNotEmpty() }
        return TopSearch(top, sections)
    }
}

package com.prism.music.data.innertube

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.model.AccountInfo
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.HomeFeed
import com.prism.music.data.model.NextResult
import com.prism.music.data.model.PlayerExtras
import com.prism.music.data.model.Related
import com.prism.music.data.model.SearchResult
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject

enum class SearchFilter(val label: String, val params: String?) {
    /** Everything, ranked by Prism (see [YouTubeMusic.searchTop]). */
    TOP(tr(R.string.top_results), null),
    SONGS(tr(R.string.songs), "EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS(tr(R.string.videos), "EgWKAQIQAWoMEA4QChADEAQQCRAF"),
    ALBUMS(tr(R.string.albums), "EgWKAQIYAWoMEA4QChADEAQQCRAF"),
    ARTISTS(tr(R.string.artists), "EgWKAQIgAWoMEA4QChADEAQQCRAF"),
    /** Playlists made by YouTube Music itself (#130). */
    FEATURED_PLAYLISTS(tr(R.string.featured_playlists), "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D"),
    /** Playlists made by people. */
    PLAYLISTS(tr(R.string.community_playlists), "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"),
    PODCASTS(tr(R.string.podcasts), "EgWKAQJQAWoMEA4QChADEAQQCRAF"),
    EPISODES(tr(R.string.episodes), "EgWKAQJIAWoMEA4QChADEAQQCRAF"),
    /** Streams on air now (lofi radios and the like), from youtube.com's search: YouTube Music has no such filter. */
    LIVE(tr(R.string.live), null),
}

/** High-level YouTube Music operations built on [InnerTube]. */
class YouTubeMusic(
    private val api: InnerTube,
    private val songsEverywhere: () -> Boolean = { false },
    /** Songs paired with their music videos while reading a page (song id -> video id), for the player's Video button. */
    private val onPairs: (Map<String, String>) -> Unit = {},
) {
    companion object {
        /** An artist page "id" that's looked up by name ([artistIdByName]). */
        const val ARTIST_BY_NAME = "name:"

        /** Words that only describe a video, not the recording ([songForVideo]). */
        internal val VIDEO_WORDS = setOf("official", "music", "lyric", "lyrics", "video", "audio", "hd", "4k", "visualizer", "visualiser", "mv", "clip", "oficial")
        private val SONG_FOR_VIDEO_BRACKETS = Regex("\\(.*?\\)|\\[.*?]")
        /** How far a video's length may be from its song's (an intro or outro on the video). */
        internal const val SONG_FOR_VIDEO_SLACK_SEC = 8
        /**
         * How much longer than its song an artist's own music video may run (intros and endings), 1.7.7; 90 s since
         * 1.7.9 (#202: "Punk Rocky"'s video runs 62 s past the song).
         */
        internal const val LONGER_VIDEO_SEC = 90
        /** A song's name in quotes in a video's title ('Artist - "Song" Label'), any kind of double quotes. */
        private val QUOTED_TITLE = Regex("[\"“”]([^\"“”]+)[\"“”]")

        /** The account's "New episodes" list (podcasts it follows), which YouTube makes itself; "SE" is "Episodes for later". */
        const val NEW_EPISODES = "RDPN"

        /** One of the account's own lists of podcast episodes, which open as a page of episodes (not a radio). */
        fun isEpisodeList(id: String) = id.removePrefix("VL").let { it == NEW_EPISODES || it == "SE" }

        /**
         * A playlist page's rows as songs: song rows, and episode rows (`musicMultiRowListItemRenderer`), which is how
         * "New episodes" lists its episodes (thedarkknight_sl on Reddit, 1.7.9: it showed a count and a length but no
         * episodes, because only song rows were read).
         */
        internal fun playlistRows(node: JsonElement?): List<Song> = (node.arr("contents") ?: emptyList()).mapNotNull { row ->
            row.obj("musicResponsiveListItemRenderer")?.let { r -> (Parser.responsive(r) as? SongItem)?.song }
                ?: row.obj("musicMultiRowListItemRenderer")?.let { r -> Parser.episode(r)?.song }
        }

        /** An album page's track title and its playlist's are the same title (case, punctuation and spacing aside). */
        internal fun sameTrackTitle(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            return norm(a).isNotEmpty() && norm(a) == norm(b)
        }
    }

    private suspend fun browse(browseId: String, params: String? = null): JsonObject {
        val body = buildJsonObject {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }
        return try {
            api.post("browse", body)
        } catch (e: InnerTubeException) {
            // Signed in, a public page can be "not found" for the account (Podcasts outside the countries that have
            // them, #18) while it opens fine signed out. Library pages stay refused that way, so the error stands.
            if (e.code != 404) throw e
            runCatching { api.postAnonymous("browse", body) }.getOrElse { throw e }
        }
    }

    private suspend fun continueBrowse(token: String): JsonObject =
        api.post("browse", buildJsonObject { put("continuation", token) }, "&ctoken=$token&continuation=$token&type=next")

    // ---------------------------------------------------------------- Home & explore

    suspend fun home(continuation: String? = null): HomeFeed {
        val res = if (continuation == null) browse("FEmusic_home") else continueBrowse(continuation)
        // Continuation pages carry a (stale) "contents" block too; the next token lives in "continuationContents".
        return HomeFeed(Parser.shelves(res), Parser.continuation(res.obj("continuationContents") ?: res.obj("contents")))
    }

    /**
     * Loads the home feed plus a few continuation pages so the catalogue has plenty to offer.
     * Keeps paging (up to [maxPages]) while [satisfied] is false, so shelves the user pinned
     * that YouTube happens to serve further down the feed still turn up.
     */
    suspend fun fullHome(pages: Int = 3, maxPages: Int = pages, satisfied: (List<Shelf>) -> Boolean = { true }): List<Shelf> =
        fullHomePages(pages, maxPages, satisfied).first

    /** [fullHome], with the pages as YouTube sent them (Home keeps those to show straight away at the next start, #126). */
    suspend fun fullHomePages(
        pages: Int = 3, maxPages: Int = pages, satisfied: (List<Shelf>) -> Boolean = { true },
        /** Each page's shelves as they come (#371: Quick picks needn't wait for the pages after theirs). */
        onPage: (List<Shelf>) -> Unit = {},
    ): Pair<List<Shelf>, List<JsonObject>> {
        val all = mutableListOf<Shelf>()
        val raw = mutableListOf<JsonObject>()
        var res = browse("FEmusic_home")
        raw += res
        all += Parser.shelves(res)
        onPage(all)
        var next = Parser.continuation(res.obj("continuationContents") ?: res.obj("contents"))
        var n = 1
        while (next != null && (n < pages || (n < maxPages && !satisfied(all)))) {
            res = runCatching { continueBrowse(next!!) }.getOrNull() ?: break
            raw += res
            all += Parser.shelves(res)
            onPage(all)
            next = Parser.continuation(res.obj("continuationContents") ?: res.obj("contents"))
            n++
        }
        return all.distinctBy { it.title } to raw
    }

    /** D40 (1.8.3): kept for a day when the app gives a [moodsCache] (Home, Search and the Moods page all ask). */
    var moodsCache: com.prism.music.data.MoodsCache? = null

    suspend fun moodsAndGenres(): List<Shelf> = moodsCache?.get(api.pageLanguage, { browse("FEmusic_moods_and_genres") }, Parser::shelves)
        ?: Parser.shelves(browse("FEmusic_moods_and_genres"))

    /**
     * Explore's new releases, moods and genres, and trending. Asked without the account: it's the same public page, and
     * what was checked is what everyone gets.
     */
    suspend fun explore(): List<Shelf> =
        Explore.music(Parser.shelves(api.postAnonymous("browse", buildJsonObject { put("browseId", "FEmusic_explore") })))

    /** The charts of [country] (a [ChartCountry.code]), or of the country YouTube picks for the listener when null. */
    suspend fun charts(country: String? = null): ChartsPage = Explore.charts(
        api.postAnonymous("browse", buildJsonObject {
            put("browseId", "FEmusic_charts")
            if (country != null) putJsonObject("formData") { putJsonArray("selectedValues") { add(country) } }
        }),
    )

    suspend fun browseShelves(browseId: String, params: String? = null): List<Shelf> {
        val res = browse(browseId, params)
        val shelves = Parser.shelves(res)
        // A list of songs on its own (an artist's "From your library" opened whole, #69) comes a page at a time.
        val only = shelves.singleOrNull()?.takeIf { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } } ?: return shelves
        var token = Parser.continuation(res.findFirst("musicShelfRenderer") ?: res.findFirst("musicPlaylistShelfRenderer"))
        val items = only.items.toMutableList()
        while (token != null && items.size < 2000) {
            val chunk = runCatching { playlistPage(token!!) }.getOrNull() ?: break
            if (chunk.songs.isEmpty()) break
            items += chunk.songs.map(::SongItem)
            token = chunk.next
        }
        return listOf(only.copy(items = items.distinctBy { it.id }))
    }

    // ---------------------------------------------------------------- Search

    suspend fun searchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val res = api.post("music/get_search_suggestions", buildJsonObject { put("input", query) })
        return res.findAll("searchSuggestionRenderer").mapNotNull { it.obj("suggestion").text() }.distinct()
    }

    /**
     * Typing suggestions (1.7.6 Search): the completions, and the artists, songs and albums YouTube Music offers beside
     * them (with their pictures), in one request. Asked without the account, as searches are (#27).
     */
    suspend fun searchSuggestionsWithItems(query: String): Pair<List<String>, List<BrowseItem>> {
        val res = api.postAnonymous("music/get_search_suggestions", buildJsonObject { put("input", query) })
        val texts = res.findAll("searchSuggestionRenderer").mapNotNull { it.obj("suggestion").text() }.distinct()
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }.distinctBy { it.id }
        return texts to items
    }

    /**
     * Songs, albums, artists, playlists and videos at once, ranked by how well they match and how
     * well they fit the listener's [taste] ([SearchRank]).
     */
    suspend fun searchTop(query: String, taste: SearchRank.Taste = SearchRank.Taste.NONE): TopSearch = kotlinx.coroutines.coroutineScope {
        val kinds = listOf(SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS, SearchFilter.VIDEOS, SearchFilter.LIVE)
        // YouTube's own "Top result" card, from its unfiltered search: it reads abbreviations and typos well.
        val top = async { runCatching { youtubeTop(query) }.getOrNull() }
        val attempts = kinds.map { f -> async { f to runCatching { search(query, f).items } } }.awaitAll()
        val results = attempts.map { (f, r) -> f to r.getOrDefault(emptyList()) }
        // Every search failed: keep why (offline, a timeout…), so the message can say so.
        if (results.all { it.second.isEmpty() }) throw java.io.IOException("Search failed", attempts.firstNotNullOfOrNull { it.second.exceptionOrNull() })
        val (card, pageSongs) = top.await() ?: (null to emptyList())
        val ranked = SearchRank.rank(query, results, taste, card, pageSongs)
        // A title track on top whose album YouTube's album search left out (it varies from one request to the next:
        // "after hours" sometimes has no After Hours by The Weeknd): the song names its album, so look that up.
        val song = (ranked.top as? SongItem)?.song
        val ref = song?.album
        if (song == null || ref?.id == null || !SearchRank.namesItsAlbum(query, song)) return@coroutineScope ranked
        val page = runCatching { album(ref.id) }.getOrNull()
        if (page?.releaseKind != "Album") return@coroutineScope ranked
        SearchRank.withAlbumOnTop(ranked, com.prism.music.data.model.AlbumItem(
            ref.id, page.title, listOfNotNull("Album", page.artists.firstOrNull()?.name ?: song.primaryArtist, page.year?.toString()).joinToString(" • "),
            page.thumbnail, page.playlistId,
        ))
    }

    /** The card at the top of YouTube Music's unfiltered search, if it shows one. */
    suspend fun youtubeTopResult(query: String): BrowseItem? = youtubeTop(query).first

    /**
     * That card, and the songs (not videos) on the same page: they sometimes hold the song a title is known for when
     * the song search leaves it out ("halo": Beyoncé's, under a Halo game soundtrack card).
     */
    suspend fun youtubeTop(query: String): Pair<BrowseItem?, List<SongItem>> {
        fun read(res: JsonObject): Pair<BrowseItem?, List<SongItem>> {
            val card = res.findAll("musicCardShelfRenderer").firstOrNull()?.let { Parser.card(it) }
            val songs = res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) as? SongItem }.filter { !it.song.isVideo }
            return card to songs
        }
        return searchAsked(query, SearchFilter.TOP, buildJsonObject { put("query", query) }, ::read) { it.first == null && it.second.isEmpty() }
    }

    /** #396: searches go with the account for the rest of the session ([SearchAccount]). */
    @Volatile private var searchWithAccount = false

    /**
     * A search request read by [read]: without the account (signed in, YouTube keeps every search in the account's
     * history, #27), unless [SearchAccount] says it goes with it, or asks it again with it when nothing was found.
     */
    private suspend fun <T> searchAsked(query: String, filter: SearchFilter, body: JsonObject, read: (JsonObject) -> T, nextPage: Boolean = false, urlQuery: String = "", empty: (T) -> Boolean): T {
        if (SearchAccount.withAccount(api.signedIn, searchWithAccount)) return read(api.post("search", body, urlQuery))
        val found = read(api.postAnonymous("search", body, urlQuery))
        if (!SearchAccount.askAgain(api.signedIn, query, filter, empty(found), nextPage)) return found
        val again = try { read(api.post("search", body, urlQuery)) } catch (e: java.io.IOException) { return found }
        if (empty(again)) return found
        searchWithAccount = true
        return again
    }

    suspend fun search(query: String, filter: SearchFilter, continuation: String? = null): SearchResult {
        if (filter == SearchFilter.LIVE) return searchLive(query, continuation)
        val body = if (continuation != null) buildJsonObject { put("continuation", continuation) } else buildJsonObject {
            put("query", query)
            filter.params?.let { put("params", it.replace("%3D", "=")) }
        }
        val urlQuery = if (continuation != null) "&ctoken=$continuation&continuation=$continuation&type=next" else ""
        return searchAsked(query, filter, body, { res ->
            val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }
            SearchResult(items, Parser.continuation(res.obj("contents") ?: res.obj("continuationContents")))
        }, nextPage = continuation != null, urlQuery = urlQuery) { it.items.isEmpty() }
    }

    private suspend fun searchLive(query: String, continuation: String?): SearchResult {
        val res = api.webPost("search", buildJsonObject {
            if (continuation != null) put("continuation", continuation)
            else { put("query", query); put("params", LiveStreams.SEARCH_PARAMS) }
        })
        val items = LiveStreams.parse(res).map { SongItem(it) }
        // A page with nothing on air ends the list, rather than paging through past broadcasts.
        return SearchResult(items, LiveStreams.continuation(res)?.takeIf { items.isNotEmpty() })
    }

    /** What an artist's channel is streaming right now (Lofi Girl's radios, say), from its Live tab. */
    suspend fun liveStreams(channelId: String, owner: com.prism.music.data.model.ArtistRef): List<Song> {
        if (!channelId.startsWith("UC")) return emptyList()
        val res = api.webPost("browse", buildJsonObject {
            put("browseId", channelId)
            put("params", LiveStreams.CHANNEL_TAB_PARAMS)
        })
        return LiveStreams.parse(res, owner)
    }

    /**
     * YouTube's "Most replayed" curve for a video (song uploads have one too, once enough people have played them),
     * from youtube.com's own `next` answer; YouTube Music's doesn't carry it. Null when the video has none.
     */
    suspend fun heatmap(videoId: String): com.prism.music.data.Heatmap? =
        com.prism.music.data.BestPart.parse(api.webPost("next", buildJsonObject { put("videoId", videoId) }))

    // ---------------------------------------------------------------- Collections

    private fun songsFrom(res: JsonObject): List<Song> =
        res.findAll("musicResponsiveListItemRenderer").mapNotNull { (Parser.responsive(it) as? SongItem)?.song }

    suspend fun album(browseId: String): CollectionPage {
        if (Parser.isUploadAlbum(browseId)) return uploadAlbum(browseId)
        val res = browse(browseId)
        val header = res.findFirst("musicResponsiveHeaderRenderer") ?: res.findFirst("musicDetailHeaderRenderer")
        val title = header.obj("title").text() ?: "Album"
        val artistLine = header.obj("straplineTextOne").text() ?: ""
        val thumb = header.obj("thumbnail").bestThumbnail()
        val artistRuns = header.obj("straplineTextOne").runs()
        val artists = Parser.artistsFromRuns(artistRuns)
        val albumRef = com.prism.music.data.model.AlbumRef(title, browseId)
        // The album's own playlist (OLAK5uy_…). Its canonical address first: on a long page the first play button found
        // can be another album's ("Imaginal Disk" played someone else's songs from it).
        val playlistId = res.findFirst("microformatDataRenderer").str("urlCanonical")?.substringAfter("list=", "")?.substringBefore('&')?.takeIf { it.isNotBlank() }
            ?: header.findFirst("watchPlaylistEndpoint").str("playlistId")
            ?: res.findFirst("watchPlaylistEndpoint").str("playlistId")
        val listed = songsFrom(res)
        // Signed out, YouTube now lists an album's tracks as their music videos where they have one (Reddit R1, 2026-10:
        // "Death & Romance" played its 8-minute video with another song in it, at the album's 5:15). The album's own
        // playlist still lists the songs, in the same order: one request puts them back.
        val songsOf = if (listed.any { it.isVideo } && playlistId != null) albumSongs(listed, playlistId) else emptyMap()
        if (songsOf.isNotEmpty()) onPairs(songsOf.values.associate { (song, video) -> song.id to video })
        val songs = listed.mapIndexed { i, s ->
            val song = songsOf[i]?.first
            (song ?: s).copy(
                // An album's tracks are its songs. Signed out, YouTube also lists the label's "Official Audio" /
                // "Lyric Video" uploads here, typed as music videos, and they opened in video mode. With "Songs, not
                // videos" everywhere they stay videos, so they're swapped for the song itself (#61).
                isVideo = song == null && s.isVideo && songsEverywhere(),
                artists = (song ?: s).artists.ifEmpty { artists },
                album = s.album ?: albumRef,
                thumbnail = s.thumbnail ?: thumb,
                explicit = s.explicit || song?.explicit == true,
            )
        }
        // "Other versions" sits beside the track list (deluxe, clean, live editions).
        val others = Parser.shelves(res).firstOrNull { it.title.equals("Other versions", true) }?.items.orEmpty()
            .filter { it.id != browseId }
        return CollectionPage(
            id = browseId,
            kind = CollectionKind.ALBUM,
            title = title,
            subtitle = artistLine,
            secondSubtitle = Parser.canonical(header.obj("subtitle").text() ?: ""),
            description = header.obj("description").findFirst("description").text()
                ?: header.obj("description").text(),
            thumbnail = thumb,
            songs = songs,
            playlistId = playlistId,
            artists = artists,
            trackPlays = res.findAll("musicResponsiveListItemRenderer").mapNotNull { r ->
                val id = r.str("playlistItemData", "videoId") ?: return@mapNotNull null
                Parser.playCount(r)?.let { id to it }
            }.toMap().let { plays ->
                // A track played as its song keeps its play count under the song's id.
                val songFor = songsOf.values.associate { (song, video) -> video to song.id }
                if (songFor.isEmpty()) plays else plays.mapKeys { (id, _) -> songFor[id] ?: id }
            },
            artistThumbnail = header.obj("straplineThumbnail").bestThumbnail(),
            savedToLibrary = libraryToggle(header),
            libraryId = libraryTarget(header),
            otherVersions = others,
            explicit = header.arr("subtitleBadge")?.any { it.str("musicInlineBadgeRenderer", "icon", "iconType") == "MUSIC_EXPLICIT_BADGE" } == true,
        )
    }

    /**
     * For an album page that lists music videos ([listed]), the songs its own playlist lists in their place, by track
     * position, each with the video it replaces. Only where both lists agree: the same number of tracks and, at each
     * place, the same title. Empty when the playlist can't be read or doesn't line up (the page then stays as listed).
     */
    private suspend fun albumSongs(listed: List<Song>, playlistId: String): Map<Int, Pair<Song, String>> {
        val own = runCatching { playlist(playlistId).songs }.getOrNull() ?: return emptyMap()
        if (own.size != listed.size) {
            log("album $playlistId: its playlist has ${own.size} tracks, the page ${listed.size}; videos left as listed")
            return emptyMap()
        }
        return listed.indices.mapNotNull { i ->
            val page = listed[i]
            val song = own[i]
            if (!page.isVideo || song.isVideo || song.id == page.id || !sameTrackTitle(page.title, song.title)) null
            else i to (song to page.id)
        }.toMap().also { if (it.isNotEmpty()) log("album $playlistId: ${it.size} tracks listed as videos played as their songs") }
    }

    /** The header's bookmark ("Save to library") state; only meaningful when signed in. */
    private fun libraryToggle(header: JsonElement?): Boolean? {
        if (!api.signedIn) return null
        val toggle = header.arr("buttons")?.firstNotNullOfOrNull { it.obj("toggleButtonRenderer") } ?: return null
        return toggle.str("isToggled") == "true"
    }

    /**
     * The playlist id the header's own library button saves, as YouTube Music's app sends it (its like endpoint's
     * target). Saving by the album's canonical playlist id failed for some albums (Reddit, 1.7.9.1: "Couldn't add to
     * library" on Miki Matsubara's "WINK"); the button's own target is the one YouTube expects.
     */
    private fun libraryTarget(header: JsonElement?): String? {
        val toggle = header.arr("buttons")?.firstNotNullOfOrNull { it.obj("toggleButtonRenderer") } ?: return null
        return listOf("defaultServiceEndpoint", "toggledServiceEndpoint").firstNotNullOfOrNull { e ->
            toggle.obj(e).findFirst("likeEndpoint").str("target", "playlistId")
        }
    }

    /** A playlist's songs' setVideoIds (what removing one from an owned playlist needs), by video id. */
    private fun setVideoIdsIn(node: JsonElement?): Map<String, String> =
        node.findAll("musicResponsiveListItemRenderer").mapNotNull { r ->
            val id = r.str("playlistItemData", "videoId") ?: return@mapNotNull null
            r.str("playlistItemData", "playlistSetVideoId")?.let { id to it }
        }.toMap()

    suspend fun playlist(playlistId: String): CollectionPage {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val res = browse(browseId)
        // The account's own playlists come with an edit header (and their visibility in it).
        val editable = res.findFirst("musicEditablePlaylistDetailHeaderRenderer")
        val header = res.findFirst("musicResponsiveHeaderRenderer")
            ?: editable.findFirst("musicResponsiveHeaderRenderer")
            ?: res.findFirst("musicDetailHeaderRenderer")
        val shelf = res.findFirst("musicPlaylistShelfRenderer") ?: res.findFirst("musicShelfRenderer")
        val songs = playlistRows(shelf)
        val owned = editable != null && api.signedIn
        return CollectionPage(
            id = playlistId.removePrefix("VL"),
            kind = CollectionKind.PLAYLIST,
            title = header.obj("title").text() ?: "Playlist",
            subtitle = header.obj("straplineTextOne").text() ?: header.obj("subtitle").text() ?: "",
            secondSubtitle = header.obj("secondSubtitle").text() ?: "",
            description = header.obj("description").findFirst("description").text(),
            thumbnail = header.obj("thumbnail").bestThumbnail(),
            songs = songs,
            continuation = Parser.continuation(shelf),
            playlistId = playlistId.removePrefix("VL"),
            owned = owned,
            privacy = if (owned) editable.findFirst("musicPlaylistEditHeaderRenderer").str("privacy") else null,
            savedToLibrary = if (owned) null else libraryToggle(header),
            setVideoIds = if (owned) setVideoIdsIn(shelf) else emptyMap(),
        )
    }

    /** One more page of a long playlist. */
    class PlaylistChunk(val songs: List<Song>, val next: String?, val setVideoIds: Map<String, String>)

    suspend fun playlistPage(token: String): PlaylistChunk {
        val res = api.post("browse", buildJsonObject { put("continuation", token) })
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { (Parser.responsive(it) as? SongItem)?.song } +
            res.findAll("musicMultiRowListItemRenderer").mapNotNull { Parser.episode(it)?.song }
        val next = res.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token")
            ?: res.findFirst("musicPlaylistShelfContinuation").let { Parser.continuation(it) }
        return PlaylistChunk(items, next, setVideoIdsIn(res))
    }

    suspend fun playlistContinuation(token: String): Pair<List<Song>, String?> =
        playlistPage(token).let { it.songs to it.next }

    /**
     * A public playlist's songs as anyone sees it, never with the account (1.8.0's holiday mixes: Prism's own
     * lookups, which shouldn't end up in the listener's history): its first page, and more up to [limit].
     */
    suspend fun publicPlaylistSongs(playlistId: String, limit: Int = 100): List<Song> {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val res = api.postAnonymous("browse", buildJsonObject { put("browseId", browseId) })
        val shelf = res.findFirst("musicPlaylistShelfRenderer") ?: res.findFirst("musicShelfRenderer")
        val songs = (shelf.arr("contents") ?: emptyList()).mapNotNull {
            it.obj("musicResponsiveListItemRenderer")?.let { r -> (Parser.responsive(r) as? SongItem)?.song }
        }.toMutableList()
        var token = Parser.continuation(shelf)
            ?: res.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token")
        while (token != null && songs.size < limit) {
            val t: String = token
            val more = runCatching { api.postAnonymous("browse", buildJsonObject { put("continuation", t) }, "&ctoken=$t&continuation=$t&type=next") }.getOrNull() ?: break
            val items = more.findAll("musicResponsiveListItemRenderer").mapNotNull { (Parser.responsive(it) as? SongItem)?.song }
            if (items.isEmpty()) break
            songs += items
            token = more.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token")
                ?: more.findFirst("musicPlaylistShelfContinuation").let { Parser.continuation(it) }
        }
        return songs.distinctBy { it.id }.take(limit)
    }

    /** Loads every song of a playlist, following continuations up to [limit]. */
    suspend fun playlistAll(playlistId: String, limit: Int = 2000, notAll: () -> Unit = {}): CollectionPage {
        val first = playlist(playlistId)
        val songs = first.songs.toMutableList()
        var token = first.continuation
        while (token != null && songs.size < limit) {
            val (more, next) = runCatching { playlistContinuation(token!!) }.getOrNull() ?: break
            if (more.isEmpty()) break
            songs += more
            token = next
        }
        if (token != null) notAll()
        return first.copy(songs = songs.distinctBy { it.id }, continuation = null)
    }

    /**
     * An artist's page id from their name, for artists a song names without a link (#78: "In the City" by Charly García,
     * Sting only links García). Only an exact name match counts.
     */
    suspend fun artistIdByName(name: String): String? {
        val want = SearchRank.norm(name)
        if (want.isBlank()) return null
        artistIds[want]?.let { return it }
        val items = search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>()
        return items.firstOrNull { SearchRank.norm(it.title) == want }?.id?.also { artistIds[want] = it }
    }
    private val artistIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [browseId] can be [ARTIST_BY_NAME] + a name, for an artist a song names without a link. */
    suspend fun artist(browseId: String): ArtistPage {
        if (browseId.startsWith(ARTIST_BY_NAME)) {
            val name = browseId.removePrefix(ARTIST_BY_NAME)
            return artist(artistIdByName(name) ?: throw java.io.IOException("Couldn't find $name in Prism Music"))
        }
        if (Parser.isUploadArtist(browseId)) return uploadArtist(browseId)
        val res = browse(browseId)
        val header = res.findFirst("musicImmersiveHeaderRenderer") ?: res.findFirst("musicVisualHeaderRenderer")
            ?: res.findFirst("musicResponsiveHeaderRenderer")
        val shelves = Parser.shelves(res)
        val mix = (header.findFirst("startRadioButton") ?: res.findFirst("startRadioButton")).findFirst("watchPlaylistEndpoint")
        // No "Mix" button (common when signed out): seed a radio from the artist's top song.
        val topSong = shelves.flatMap { it.items }.filterIsInstance<SongItem>().firstOrNull { !it.song.isVideo }?.song?.id
        // The "About" section has the full biography; the header's text is often just a promo line.
        val about = res.findFirst("musicDescriptionShelfRenderer")
        return ArtistPage(
            id = browseId,
            name = header.obj("title").text() ?: "Artist",
            thumbnail = header.obj("thumbnail").bestThumbnail() ?: header.obj("foregroundThumbnail").bestThumbnail(),
            description = about.obj("description").text()?.takeIf { it.isNotBlank() } ?: header.obj("description").text(),
            views = about.obj("subheader").text()?.takeIf { it.contains("view") },
            subscribers = header.obj("monthlyListenerCount").text()
                ?: header.findFirst("subscriberCountText").text()
                ?: header.obj("subscriptionButton", "subscribeButtonRenderer", "longSubscriberCountText").text(),
            shelves = shelves,
            radioPlaylistId = mix.str("playlistId") ?: topSong?.let { "RDAMVM$it" },
            radioParams = mix.str("params"),
            radioVideoId = if (mix.str("playlistId") == null) topSong else null,
            shufflePlaylistId = header.findFirst("playButton").let { b ->
                b.findFirst("watchEndpoint").str("playlistId") ?: b.findFirst("watchPlaylistEndpoint").str("playlistId")
            },
            shuffleVideoId = header.findFirst("playButton").findFirst("watchEndpoint").str("videoId"),
            shuffleParams = header.findFirst("playButton").let { b ->
                b.findFirst("watchEndpoint").str("params") ?: b.findFirst("watchPlaylistEndpoint").str("params")
            },
            channelId = header.findFirst("subscribeButtonRenderer").str("channelId"),
            subscribed = header.findFirst("subscribeButtonRenderer").str("subscribed") == "true",
        )
    }

    // ---------------------------------------------------------------- Queue / radio

    suspend fun next(
        videoId: String?,
        playlistId: String? = null,
        params: String? = null,
        continuation: String? = null,
        /** False when only the page around the song is wanted (its lyrics tab): the queue isn't fetched. */
        queue: Boolean = true,
    ): NextResult {
        fun body(token: String?) = buildJsonObject {
            if (token == null) {
                videoId?.let { put("videoId", it) }
                playlistId?.let { put("playlistId", it) }
                params?.let { put("params", it) }
            }
            (token ?: continuation)?.let { put("continuation", it) }
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        }
        val res = api.post("next", body(null))
        // Since late 2026 a single song's page comes without its queue, only a token to load it with (radios still
        // carry theirs): asked for once more when the songs are wanted.
        val reload = if (!queue || res.findFirst("playlistPanelRenderer") != null || res.findFirst("playlistPanelContinuation") != null) null
            else res.findFirst("reloadContinuationData").str("continuation")
        val panel = reload?.let { t -> runCatching { api.post("next", body(t)) }.getOrNull() } ?: res
        val counterparts = mutableMapOf<String, String>()
        val counterpartSongs = mutableMapOf<String, Song>()
        val songs = mutableListOf<Song>()
        val panelItems = (panel.findFirst("playlistPanelRenderer") ?: panel.findFirst("playlistPanelContinuation"))
            .arr("contents") ?: emptyList()
        for (item in panelItems) {
            item.obj("playlistPanelVideoRenderer")?.let { Parser.panelVideo(it)?.let(songs::add) }
            item.obj("playlistPanelVideoWrapperRenderer")?.let { w ->
                val primary = w.obj("primaryRenderer", "playlistPanelVideoRenderer")?.let { Parser.panelVideo(it) }
                if (primary != null) {
                    songs += primary
                    val other = w.arr("counterpart")?.firstOrNull()?.obj("counterpartRenderer", "playlistPanelVideoRenderer")
                    other?.str("videoId")?.let { counterparts[primary.id] = it }
                    other?.let { Parser.panelVideo(it) }?.let { counterpartSongs[primary.id] = it }
                }
            }
        }
        val tabs = res.findFirst("watchNextTabbedResultsRenderer").arr("tabs") ?: emptyList()
        val lyricsId = tabs.firstNotNullOfOrNull { t ->
            t.str("tabRenderer", "endpoint", "browseEndpoint", "browseId")?.takeIf { it.startsWith("MPLY") }
        }
        val cont = panel.findFirst("playlistPanelRenderer")?.let { Parser.continuation(it.arr("continuations")) }
            ?: Parser.continuation(panel.obj("continuationContents"))
        val relatedId = tabs.firstNotNullOfOrNull { t ->
            t.str("tabRenderer", "endpoint", "browseEndpoint", "browseId")?.takeIf { it.startsWith("MPTR") }
        }
        return NextResult(songs, cont, lyricsId, counterparts, counterpartSongs, relatedId)
    }

    /**
     * What YouTube Music puts beside [videoId] on its Related tab (1.7.9): "You might also like" songs and "Similar
     * artists", for Prism's mixes and radio to find new music with. The tab also lists other performances of the same
     * song (covers, live takes): those are left out, by title. Found by kind of item, not by the shelves' names.
     */
    suspend fun related(videoId: String, title: String? = null): Related {
        val id = next(videoId, queue = false).relatedBrowseId ?: return Related(emptyList(), emptyList())
        val shelves = Parser.shelves(browse(id))
        fun core(t: String) = t.lowercase().replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim()
        val own = (title ?: shelves.flatMap { it.items }.filterIsInstance<SongItem>().firstOrNull { it.song.id == videoId }?.song?.title)?.let(::core)
        val songs = shelves.filter { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } }
            // "Other performances" is the same song again: a shelf mostly of its title.
            .filterNot { s -> own != null && s.items.count { core(it.title) == own } * 2 >= s.items.size }
            .flatMap { it.items }.filterIsInstance<SongItem>().map { it.song }
            .filter { it.id != videoId && (own == null || core(it.title) != own) }
        val artists = shelves.firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it is ArtistItem } }?.items.orEmpty().filterIsInstance<ArtistItem>()
        return Related(songs.distinctBy { it.id }, artists)
    }

    /** An artist's top songs (the first shelf of songs on their page that aren't videos), for discovery. */
    suspend fun artistTopSongs(browseId: String): List<Song> =
        artist(browseId).shelves.firstOrNull { s -> s.items.any { it is SongItem && !it.song.isVideo } }
            ?.items.orEmpty().filterIsInstance<SongItem>().map { it.song }.filter { !it.isVideo && !it.isEpisode }
    /**
     * YouTube's own song ↔ video pairing for one item, if it still gives one. Signed out it stopped (2026-10-25: no
     * counterparts in `next`, not even in the queue it loads after), so then nothing's asked and Prism matches by search.
     */
    private suspend fun pairing(id: String): NextResult? = if (!api.signedIn) null else runCatching { next(id) }.getOrNull()

    /**
     * The song a music video or live performance belongs to. Official videos are paired with
     * their audio track in the watch queue; anything else (live sets, performances) is matched
     * by a song search on the cleaned-up title and artist, with the duration as a tie-breaker.
     */
    suspend fun songForVideo(video: Song, anyLength: Boolean = false): Song? {
        pairing(video.id)?.let { r ->
            r.counterpartSongs[video.id]?.takeIf { !it.isVideo }?.let { return it }
            r.songs.firstOrNull { !it.isVideo && r.counterparts[it.id] == video.id }?.let { return it }
        }
        // Without YouTube's own pairing it must be the same recording (GitHub #113: a live album's track,
        // "Nos siguen pegando abajo (Pecado mortal)", played as the studio song from another album). So only the video's
        // own words go ("Official Video", "(Lyric Video)", "HD"); anything else in brackets, and live or session takes,
        // tell recordings apart; and the lengths must agree.
        fun norm(s: String) = s.lowercase()
            .replace(SONG_FOR_VIDEO_BRACKETS) { m -> if (m.value.trim('(', ')', '[', ']', ' ').split(Regex("\\s+")).all { it in VIDEO_WORDS }) " " else m.value }
            .replace(Regex("\\b(${VIDEO_WORDS.joinToString("|")})\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        // "Artist - Title (Live at …)" videos put the artist in the title.
        val channel = norm(video.primaryArtist)
        val head = video.title.substringBefore(" - ", "")
        val headIsChannel = head.isNotEmpty() && norm(head).let { a -> channel.isBlank() || a.contains(channel) || channel.contains(a) }
        // A label's or a music channel's upload (#202: 'Meg & Dia - "Monster" Doghouse Records' by BlankTV): the artist
        // before the dash, the song's name in quotes, the rest the label's.
        val quoted = if (head.isNotEmpty() && !headIsChannel) QUOTED_TITLE.find(video.title.substringAfter(" - "))?.groupValues?.get(1)?.trim() else null
        val artistName = if (quoted != null) head.trim() else video.primaryArtist
        val artist = norm(artistName)
        val rawTitle = when {
            quoted != null -> quoted
            headIsChannel -> video.title.substringAfter(" - ")
            else -> video.title
        }
        val title = norm(rawTitle)
        if (title.isBlank()) return null
        val query = "${rawTitle.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()} $artistName".trim()
        // An artist's own music video often runs longer than the song (an intro, a scene after it: "bad guy", 206 s for a
        // 194 s song), so it may be up to a minute and a half longer, never much shorter. A live or acoustic take is told apart by
        // its title, so the lengths only matter beyond that.
        val ownTake = !MusicVideoMatch.otherTake(video.title)
        fun lengthFits(s: Song): Boolean {
            // [anyLength]: only its cover is wanted (Reddit 1.7.9: "Carl Sagan"'s video is a 3:29 edit of the 3:53 song),
            // never its sound, so another length of the same title by the same artist is the same release.
            if (anyLength || video.durationSec <= 0 || s.durationSec <= 0) return true
            val longer = video.durationSec - s.durationSec
            return if (ownTake) longer in -SONG_FOR_VIDEO_SLACK_SEC..LONGER_VIDEO_SEC else kotlin.math.abs(longer) <= SONG_FOR_VIDEO_SLACK_SEC
        }
        // Only an exact title by the same artist counts; a near miss would put the wrong song's lyrics on the video.
        return search(query, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
            .filter { s ->
                !s.isVideo && norm(s.title) == title &&
                    (artist.isBlank() || s.artists.any { a -> norm(a.name).let { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } }) &&
                    lengthFits(s)
            }
            .minByOrNull { s -> if (video.durationSec > 0 && s.durationSec > 0) kotlin.math.abs(s.durationSec - video.durationSec) else 0 }
    }

    /**
     * Finds the official music video for an audio track. Uses the pairing from
     * the watch queue when YouTube provides it, otherwise a filtered video search.
     */
    suspend fun findMusicVideo(song: Song): String? {
        pairing(song.id)?.counterparts?.get(song.id)?.let { log("video for ${song.id} \"${song.title}\": YouTube's own pairing $it"); return it }
        if (song.artists.isEmpty() || song.title.isBlank()) {
            log("video for ${song.id} \"${song.title}\": no artist or title to search with")
            return null
        }
        var videos = search("${song.title} ${song.primaryArtist}", SearchFilter.VIDEOS).items
            .filterIsInstance<SongItem>().map { it.song }
        var picked = MusicVideoMatch.pick(song, videos)
        // Fan uploads can crowd the artist's own video off the first page: ask once more, for the official one.
        if (picked == null) {
            val more = runCatching { search("${song.title} ${song.primaryArtist} official video", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song } }.getOrDefault(emptyList())
            picked = MusicVideoMatch.pick(song, more)
            videos = videos + more
        }
        // What was looked at, so a "no video" can be told apart from a wrong rule (shared in playback diagnostics).
        log(
            "video for ${song.id} \"${song.title}\" by ${song.artistText} (${song.durationSec}s): " +
                (picked?.let { "picked $it" } ?: "none of") + " " +
                videos.take(6).joinToString("; ") { "\"${it.title}\" by ${it.artistText} ${it.durationSec}s ${it.id}" },
        )
        return picked
    }

    private fun log(message: String) { runCatching { android.util.Log.i("PrismVideo", message) } }

    suspend fun lyrics(browseId: String): String? {
        val res = browse(browseId)
        return res.findFirst("musicDescriptionShelfRenderer").obj("description").text()
    }

    /**
     * YouTube Music's time-synced lyrics (LyricFind's, as its app shows them), as LRC; null when the song has only
     * plain text. Only the Android app's lyrics page carries the timings ([InnerTube.androidMusicPost]).
     */
    suspend fun syncedLyrics(browseId: String): String? {
        val res = api.androidMusicPost("browse", buildJsonObject { put("browseId", browseId) })
        val lines = res.findFirst("timedLyricsData") as? kotlinx.serialization.json.JsonArray ?: return null
        val out = StringBuilder()
        for (l in lines) {
            val ms = l.str("cueRange", "startTimeMilliseconds")?.toLongOrNull() ?: continue
            val text = l.str("lyricLine") ?: continue
            out.append("[%02d:%02d.%02d]%s\n".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10, text.trim()))
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** Fetches loudness + playback tracking info for normalization and history sync. */
    /**
     * Fetches loudness + playback tracking info for normalization and history sync. [signatureTimestamp]
     * is the current player JS's: without it the player now answers "Video unavailable" with no
     * tracking URL, and plays never reach the account's history.
     */
    suspend fun playerExtras(videoId: String, signatureTimestamp: Int? = null): PlayerExtras {
        val res = api.post("player", buildJsonObject {
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (signatureTimestamp != null) put("signatureTimestamp", signatureTimestamp)
                }
            }
        })
        return PlayerExtras(
            loudnessDb = res.str("playerConfig", "audioConfig", "loudnessDb")?.toDoubleOrNull(),
            trackingUrl = res.str("playbackTracking", "videostatsPlaybackUrl", "baseUrl"),
            watchtimeUrl = res.str("playbackTracking", "videostatsWatchtimeUrl", "baseUrl"),
            seen = YouTubeSeen.of(res).text,
        ).also {
            if (it.trackingUrl == null) {
                val why = "no tracking URL: ${res.str("playabilityStatus", "status")} ${res.str("playabilityStatus", "reason")} (signature timestamp ${if (signatureTimestamp != null) "known" else "NOT known"}), ${it.seen}"
                android.util.Log.w("PrismHistory", "$videoId: $why")
                com.prism.music.playback.AudioDiagnostics.log("history", "$videoId: not sent: $why")
            }
        }
    }

    /**
     * Registers a play in the user's YouTube Music history so recommendations stay in sync. Whether YouTube took it
     * (a 2xx answer): no tracking URL, no answer or a refusal is false.
     */
    suspend fun registerPlayback(extras: PlayerExtras, songId: String = "?"): Boolean {
        val base = extras.trackingUrl ?: return false
        val cpn = (1..16).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".random() }.joinToString("")
        val sep = if (base.contains("?")) "&" else "?"
        val code = api.ping("$base${sep}ver=2&c=WEB_REMIX&cver=${api.clientVersion}&cpn=$cpn")
        android.util.Log.i("PrismHistory", "play sent to YouTube Music: HTTP $code")
        // #377: a 204 taken for a request that isn't the account's would still not be in its history; so it says both.
        com.prism.music.playback.AudioDiagnostics.log("history", "$songId: sent${if (api.brandChannel) " for the brand channel" else ""}, YouTube answered ${code?.let { "HTTP $it" } ?: "nothing (no connection?)"}; ${extras.seen ?: "not said if signed in"}")
        return code != null && code in 200..299
    }

    // ---------------------------------------------------------------- Account & library

    suspend fun accountInfo(): AccountInfo? {
        val res = api.post("account/account_menu")
        val h = res.findFirst("activeAccountHeaderRenderer") ?: return null
        return AccountInfo(
            name = h.obj("accountName").text() ?: "YouTube Music",
            email = h.obj("email").text() ?: h.obj("channelHandle").text(),
            avatar = h.obj("accountPhoto").bestThumbnail(),
        )
    }

    /**
     * Every channel Prism can switch to: each Google account signed in on this session (up to ten), with its own
     * channel and its brand accounts. [cookie]: a session other than the saved one (just after adding an account).
     */
    suspend fun accounts(cookie: String? = null): List<com.prism.music.data.model.YtAccount> {
        val out = mutableListOf<com.prism.music.data.model.YtAccount>()
        val emails = HashSet<String>()
        for (authUser in 0 until 10) {
            val res = (if (cookie != null) api.accountsList(authUser, cookie) else api.accountsList(authUser)) ?: break
            val email = res.findFirst("googleAccountHeaderRenderer").obj("email").text()
            // Past the last account YouTube answers with the first one again.
            if (email != null && !emails.add(email)) break
            val items = res.findAll("accountItem")
            if (items.isEmpty()) break
            for (item in items) {
                val name = item.obj("accountName").text() ?: continue
                val pageId = item.findFirst("pageIdToken").str("pageId")
                val sync = item.findFirst("datasyncIdToken").str("datasyncIdToken")
                    ?: if (pageId != null) "$pageId||brand" else "||"
                out += com.prism.music.data.model.YtAccount(
                    name = name,
                    byline = item.obj("channelHandle").text() ?: item.obj("accountByline").text(),
                    email = email,
                    avatar = item.obj("accountPhoto").bestThumbnail(),
                    authUser = authUser,
                    dataSyncId = sync,
                )
            }
        }
        return out.distinctBy { it.authUser to it.dataSyncId }
    }

    suspend fun likedSongs(limit: Int = 5000): List<Song> = playlistAll("LM", limit).songs

    /** Every playlist in the library, following its continuations (YouTube sends the first ~25, Reddit R5). */
    suspend fun libraryPlaylists(): List<BrowseItem> = libraryItems("FEmusic_liked_playlists")

    /** A library page plus its continuations (they come 25 at a time). */
    private suspend fun libraryItems(browseId: String, maxPages: Int = 20): List<BrowseItem> {
        val out = mutableListOf<BrowseItem>()
        var res = browse(browseId)
        var pages = 0
        while (true) {
            out += res.findAll("musicTwoRowItemRenderer").mapNotNull { Parser.twoRow(it) }
            out += res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }
            val token = Parser.continuation(res) ?: break
            if (++pages >= maxPages) break
            res = runCatching { continueBrowse(token) }.getOrNull() ?: break
        }
        return out.distinctBy { it.id }
    }

    suspend fun libraryAlbums(): List<BrowseItem> =
        libraryItems("FEmusic_liked_albums").filterIsInstance<com.prism.music.data.model.AlbumItem>()

    /** Artists of songs in the library, then artists the account subscribes to. */
    suspend fun libraryArtists(): List<BrowseItem> {
        val tracks = runCatching { libraryItems("FEmusic_library_corpus_track_artists") }.getOrDefault(emptyList())
        val subs = runCatching { libraryItems("FEmusic_library_corpus_artists") }.getOrDefault(emptyList())
        return (tracks + subs).filterIsInstance<com.prism.music.data.model.ArtistItem>().distinctBy { it.id }
    }

    // ---------------------------------------------------------------- The listener's uploads (#332, #101)
    //
    // Browse ids as ytmusicapi uses them (mixins/uploads.py). Not yet checked against an account with uploads: see Parser.

    /** A page of the account's uploaded songs; [token] from the page before. */
    suspend fun uploadedSongsPage(token: String? = null): Parser.UploadRows<Song> =
        Parser.uploadSongRows(if (token == null) browse("FEmusic_library_privately_owned_tracks") else continueBrowse(token))

    /** Every card of the account's uploaded albums (they come a page at a time). [Parser.UploadRows.next]: a page didn't come. */
    suspend fun uploadedAlbums(maxPages: Int = 40): Parser.UploadRows<com.prism.music.data.model.AlbumItem> =
        allUploadPages(maxPages, browse("FEmusic_library_privately_owned_releases"), Parser::uploadAlbumCards)

    /** Every row of the account's uploaded artists. [Parser.UploadRows.next]: a page didn't come. */
    suspend fun uploadedArtists(maxPages: Int = 40): Parser.UploadRows<ArtistItem> =
        allUploadPages(maxPages, browse("FEmusic_library_privately_owned_artists"), Parser::uploadArtistRows)

    /**
     * Deletes an upload from the account for good (1.8.3, D49), as ytmusicapi's delete_upload_entity: a song's entity id
     * ([Parser.UploadRows.entities]) or an uploaded album's page id. Throws when YouTube refuses.
     */
    suspend fun deleteUpload(entityId: String) {
        val id = entityId.removePrefix("FEmusic_library_privately_owned_release_detail")
        val res = api.post("music/delete_privately_owned_entity", buildJsonObject { put("entityId", id) })
        res["error"]?.let { e -> throw InnerTubeException(e.str("message") ?: "YouTube Music didn't delete it") }
    }

    /** Uploads a music file to the account (1.8.3, D49): see [InnerTube.uploadSong]. */
    suspend fun uploadSong(fileName: String, size: Long, body: okhttp3.RequestBody) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.uploadSong(fileName, size, body) }

    private suspend fun <T : com.prism.music.data.model.BrowseItem> allUploadPages(maxPages: Int, first: JsonObject, read: (JsonElement) -> Parser.UploadRows<T>): Parser.UploadRows<T> {
        var page = read(first)
        val items = page.items.toMutableList()
        var rows = page.rows
        var pages = 1
        var stoppedAt: String? = null
        while (page.next != null && pages++ < maxPages) {
            val token = page.next!!
            page = runCatching { read(continueBrowse(token)) }.getOrNull() ?: run { stoppedAt = token; null } ?: break
            items += page.items
            rows += page.rows
        }
        // Repeated across pages, one would crash a list keyed by id.
        return Parser.UploadRows(items.distinctBy { it.id }, rows, stoppedAt)
    }

    private suspend fun uploadAlbum(browseId: String): CollectionPage {
        val page = Parser.uploadAlbumPage(browseId, browse(browseId))
        val ref = com.prism.music.data.model.AlbumRef(page.title, browseId)
        val songs = page.songs.toMutableList()
        var token = page.continuation
        var pages = 1
        while (token != null && pages++ < 20) {
            val more = runCatching { Parser.uploadSongRows(continueBrowse(token!!), ref) }.getOrNull() ?: break
            songs += more.items.map { s -> s.copy(thumbnail = s.thumbnail ?: page.thumbnail, artists = s.artists.ifEmpty { page.artists }) }
            token = more.next
        }
        return page.copy(songs = songs.distinctBy { it.id }, continuation = null)
    }

    private suspend fun uploadArtist(browseId: String): ArtistPage {
        val res = browse(browseId)
        var page = Parser.uploadSongRows(res)
        val songs = page.items.toMutableList()
        var pages = 1
        while (page.next != null && pages++ < 200) {
            page = runCatching { Parser.uploadSongRows(continueBrowse(page.next!!)) }.getOrNull() ?: break
            songs += page.items
        }
        return Parser.uploadArtistPage(browseId, res, songs.distinctBy { it.id })
    }

    /**
     * A song's credits as YouTube Music's "View song credits" shows them ("Performed by", "Written by", "Produced by",
     * "Music metadata provided by"), each with its names. Empty when there are none (music videos usually have none).
     */
    suspend fun credits(videoId: String): List<Pair<String, List<String>>> {
        val r = browse("MPTC$videoId")
        fun find(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonObject? = when (e) {
            is kotlinx.serialization.json.JsonObject -> (e["dismissableDialogRenderer"] as? kotlinx.serialization.json.JsonObject)
                ?: e.values.firstNotNullOfOrNull { find(it) }
            is kotlinx.serialization.json.JsonArray -> e.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        fun runs(o: kotlinx.serialization.json.JsonObject?): List<String> =
            (o?.get("runs") as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { ((it as? kotlinx.serialization.json.JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val dialog = find(r) ?: return emptyList()
        return (dialog["sections"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { s ->
            val sec = (s as? kotlinx.serialization.json.JsonObject)?.get("dismissableDialogContentSectionRenderer") as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val title = runs(sec["title"] as? kotlinx.serialization.json.JsonObject).joinToString("").trim()
            val names = runs(sec["subtitle"] as? kotlinx.serialization.json.JsonObject).joinToString("").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (title.isEmpty() || names.isEmpty()) null else title to names
        }
    }

    /**
     * A song's or video's plays and dates (#211). Asked without the account: these are public, and only the plain web
     * client's answer carries the description with the release date. YouTube Music's own answer fills in what that one
     * lacks (it can refuse with a bot check). Fails only if neither could be asked.
     */
    suspend fun songFacts(videoId: String): SongFacts {
        val body = buildJsonObject {
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
        }
        val web = runCatching { SongFacts.parse(videoId, api.webPost("player", body)) }
        if (web.getOrNull()?.let { it.count != null && (it.releasedOn ?: it.publishedOn) != null } == true) return web.getOrThrow()
        val music = runCatching { SongFacts.parse(videoId, api.postAnonymous("player", body)) }
        if (web.isFailure && music.isFailure) throw music.exceptionOrNull()!!
        return (web.getOrNull() ?: SongFacts()).orElse(music.getOrNull() ?: SongFacts())
    }

    /** Only the artists the account subscribes to (follows) on YouTube Music. */
    suspend fun librarySubscriptions(): List<com.prism.music.data.model.ArtistItem> =
        libraryItems("FEmusic_library_corpus_artists").filterIsInstance<com.prism.music.data.model.ArtistItem>().distinctBy { it.id }

    suspend fun history(): List<Song> = songsFrom(browse("FEmusic_history"))

    /**
     * The account's history (what was played in the YouTube Music app, on the website and in Prism), as YouTube groups
     * it: "Today", "Yesterday", "This week"… (Library → History → Your account, #118).
     */
    suspend fun historySections(): List<Pair<String, List<Song>>> {
        val res = browse("FEmusic_history")
        val shelves = res.findAll("musicShelfRenderer")
        if (shelves.isEmpty()) return listOf("" to songsFrom(res)).filter { it.second.isNotEmpty() }
        return shelves.map { s -> (s.obj("title").text() ?: "") to songsFrom(s) }.filter { it.second.isNotEmpty() }
    }

    // ---------------------------------------------------------------- Podcasts

    /** YouTube Music's Podcasts page: popular shows and episodes, then shows by category. */
    suspend fun podcastsHome(): List<Shelf> = Parser.shelves(browse("FEmusic_non_music_audio"))

    /** Shows saved to the account's library. */
    suspend fun libraryPodcasts(): List<com.prism.music.data.model.PodcastItem> =
        libraryItems("FEmusic_library_non_music_audio_list", maxPages = 8).filterIsInstance<com.prism.music.data.model.PodcastItem>()

    /** The account's "Episodes for later" (YouTube's "SE" list), newest saved first. */
    suspend fun savedEpisodes(): List<Song> = runCatching { playlist("SE").songs.filter { it.isEpisode } }.getOrDefault(emptyList())

    /** A show's page: its header and the first episodes (newest first); more come from [podcastEpisodes]. */
    suspend fun podcast(browseId: String): com.prism.music.data.model.PodcastPage {
        val id = if (browseId.startsWith("MPSP")) browseId else "MPSP$browseId"
        val res = browse(id)
        val header = res.findFirst("musicResponsiveHeaderRenderer")
        val title = header.obj("title").text() ?: "Podcast"
        val authorRun = header.obj("straplineTextOne").runs().firstOrNull()
        val thumb = header.obj("thumbnail").bestThumbnail()
        val show = com.prism.music.data.model.PodcastItem(id, title, authorRun.str("text") ?: "", thumb)
        val shelf = res.findFirst("musicShelfRenderer")
        return com.prism.music.data.model.PodcastPage(
            id = id,
            title = title,
            author = authorRun?.let {
                com.prism.music.data.model.ArtistRef(it.str("text") ?: "", it.str("navigationEndpoint", "browseEndpoint", "browseId"))
            },
            description = header.obj("description").findFirst("description").text(),
            thumbnail = thumb,
            episodes = episodesIn(shelf, show),
            continuation = Parser.continuation(shelf),
            savedToLibrary = libraryToggle(header),
        )
    }

    /** One more page of a show's episodes. */
    suspend fun podcastEpisodes(token: String, show: com.prism.music.data.model.PodcastItem): Pair<List<com.prism.music.data.model.Episode>, String?> {
        val res = continueBrowse(token)
        val node = res.obj("continuationContents") ?: res
        return episodesIn(node, show) to (Parser.continuation(node) ?: res.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token"))
    }

    private fun episodesIn(node: JsonElement?, show: com.prism.music.data.model.PodcastItem): List<com.prism.music.data.model.Episode> =
        node.findAll("musicMultiRowListItemRenderer").mapNotNull { Parser.episode(it, show) }.distinctBy { it.song.id }

    suspend fun like(videoId: String, liked: Boolean) {
        api.post(if (liked) "like/like" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("videoId", videoId) }
        })
    }

    suspend fun dislike(videoId: String, disliked: Boolean) {
        api.post(if (disliked) "like/dislike" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("videoId", videoId) }
        })
    }

    /**
     * Adds a song to an owned playlist; false when it was already there (#159: it went in a second time; now it's
     * skipped, as YouTube Music's own "Skip duplicates" does, and the listener is told). Throws when YouTube didn't add
     * it (1.8.1: that used to count as added).
     */
    suspend fun addToPlaylist(playlistId: String, videoId: String): Boolean = addSongsToPlaylist(playlistId, listOf(videoId)).added > 0

    /** What adding songs to a playlist did: how many went in, how many were there already, how many YouTube refused. */
    data class PlaylistAdd(val added: Int, val alreadyThere: Int, val refused: Int = 0)

    /**
     * Adds songs to an owned playlist, 50 to a request; ones already in it are skipped. Only what YouTube says went in
     * counts as added (Reddit, 1.7.9: "Added" showed for songs that never got there, because an answer without its
     * per-song results, or with a failed status, was taken as success, and a whole album's add was never checked at
     * all). An answer that doesn't say is checked against the playlist itself. A batch YouTube turns down (one song it
     * won't take spoils all 50) is asked again song by song. Throws when nothing went in and nothing was there already.
     */
    suspend fun addSongsToPlaylist(playlistId: String, videoIds: List<String>): PlaylistAdd {
        val ids = videoIds.distinct()
        var added = 0
        var there = 0
        var refused = 0
        var unclear = emptyList<String>()
        var lastError: Exception? = null
        suspend fun send(chunk: List<String>) {
            val outcome = try {
                PlaylistEdits.read(postAdd(playlistId, chunk), chunk.size)
            } catch (e: InnerTubeException) {
                // A batch turned down whole: song by song, so the ones it takes still go in.
                if (e.code == 400 && chunk.size > 1) { chunk.forEach { send(listOf(it)) }; return }
                lastError = e
                PlaylistEdits.Outcome.Refused(e.message ?: "")
            } catch (e: java.io.IOException) {
                // The connection dropped on a later batch: the ones before it went in, so they still count.
                lastError = e
                PlaylistEdits.Outcome.Refused(e.message ?: "")
            }
            when (outcome) {
                is PlaylistEdits.Outcome.Done -> { added += outcome.added; there += chunk.size - outcome.added }
                is PlaylistEdits.Outcome.Refused -> { refused += chunk.size; if (lastError == null) lastError = java.io.IOException(outcome.message) }
                PlaylistEdits.Outcome.Unclear -> unclear = unclear + chunk
            }
        }
        ids.chunked(50).forEach { send(it) }
        // YouTube's answer didn't say: whether they're in the playlist now is what counts.
        if (unclear.isNotEmpty()) {
            val inIt = runCatching { playlistSlots(playlistId).keys }.getOrNull()
            if (inIt == null) refused += unclear.size
            else unclear.forEach { if (it in inIt) added++ else refused++ }
        }
        if (added == 0 && there == 0) throw (lastError ?: java.io.IOException("YouTube Music didn't add it"))
        return PlaylistAdd(added, there, refused)
    }

    private suspend fun postAdd(playlistId: String, videoIds: List<String>): JsonObject =
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                videoIds.forEach { id ->
                    addJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", id)
                        put("dedupeOption", "DEDUPE_OPTION_SKIP")
                    }
                }
            }
        })

    /** Adds many songs to an owned playlist ([addSongsToPlaylist]); ones already in it are skipped. */
    suspend fun addAllToPlaylist(playlistId: String, videoIds: List<String>): PlaylistAdd = addSongsToPlaylist(playlistId, videoIds)

    /** Takes many songs out of an owned playlist, 50 to a request; each is (videoId, setVideoId). */
    suspend fun removeAllFromPlaylist(playlistId: String, items: List<Pair<String, String>>) {
        items.chunked(50).forEach { chunk ->
            api.post("browse/edit_playlist", buildJsonObject {
                put("playlistId", playlistId.removePrefix("VL"))
                putJsonArray("actions") {
                    chunk.forEach { (videoId, setVideoId) ->
                        addJsonObject {
                            put("action", "ACTION_REMOVE_VIDEO")
                            put("removedVideoId", videoId)
                            put("setVideoId", setVideoId)
                        }
                    }
                }
            })
        }
    }

    /** Everything in an owned playlist, video id -> setVideoId (its slot, for taking it out). */
    suspend fun playlistSlots(playlistId: String, limit: Int = 10_000): Map<String, String> {
        val first = playlist(playlistId)
        if (!first.owned) throw java.io.IOException("Not your playlist")
        val slots = LinkedHashMap(first.setVideoIds)
        var token = first.continuation
        while (token != null && slots.size < limit) {
            val chunk = playlistPage(token)
            if (chunk.songs.isEmpty()) break
            slots.putAll(chunk.setVideoIds)
            token = chunk.next
        }
        return slots
    }

    suspend fun createPlaylist(title: String, videoIds: List<String>, description: String = "", privacy: String = "PRIVATE"): String? {
        val res = api.post("playlist/create", buildJsonObject {
            put("title", title)
            put("description", description)
            put("privacyStatus", privacy)
            putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        })
        return res.str("playlistId")
    }

    /** Renames an owned playlist, rewrites its description and/or changes who can open it ("PRIVATE", "UNLISTED", "PUBLIC"). */
    suspend fun editPlaylist(playlistId: String, title: String? = null, description: String? = null, privacy: String? = null) {
        if (title == null && description == null && privacy == null) return
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                title?.let { addJsonObject { put("action", "ACTION_SET_PLAYLIST_NAME"); put("playlistName", it) } }
                description?.let { addJsonObject { put("action", "ACTION_SET_PLAYLIST_DESCRIPTION"); put("playlistDescription", it) } }
                privacy?.let { addJsonObject { put("action", "ACTION_SET_PLAYLIST_PRIVACY"); put("playlistPrivacy", it) } }
            }
        })
    }

    /**
     * Takes one song out of an owned playlist; [setVideoId] says which copy (a playlist can hold a song twice).
     * YouTube answers "Precondition check failed" (#87) when that slot is out of date (the playlist changed since the
     * page was loaded, elsewhere or on another device): then the playlist's current slots are read and it's asked
     * again with the song's slot now, or it's done if the song isn't in the playlist any more.
     */
    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String) {
        try {
            removeSlot(playlistId, videoId, setVideoId)
        } catch (e: InnerTubeException) {
            if (e.code != 400) throw e
            val fresh = runCatching { playlistSlots(playlistId)[videoId] }.getOrElse { throw e } ?: return
            if (fresh == setVideoId) throw e
            removeSlot(playlistId, videoId, fresh)
        }
    }

    private suspend fun removeSlot(playlistId: String, videoId: String, setVideoId: String) {
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("removedVideoId", videoId)
                    put("setVideoId", setVideoId)
                }
            }
        })
    }

    /**
     * Moves one song in an owned playlist (#140): the song in slot [setVideoId] goes just before the one in [beforeSetVideoId],
     * or to the end when that's null. Slots stay the same, so the page's `setVideoIds` still hold after a move.
     */
    suspend fun movePlaylistItem(playlistId: String, setVideoId: String, beforeSetVideoId: String?) {
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                addJsonObject {
                    put("action", "ACTION_MOVE_VIDEO_BEFORE")
                    put("setVideoId", setVideoId)
                    beforeSetVideoId?.let { put("movedSetVideoIdSuccessor", it) }
                }
            }
        })
    }

    /**
     * Saves an album (by its playlist id) or someone else's playlist to the library, or takes it out. An album's browse
     * id ("MPREb_…", what a card on Home or in Search carries, with no playlist id) is turned into the playlist id its
     * page's library button saves: sent as it was, YouTube refused it ("Couldn't update your library" from a long
     * press on an album's cover).
     */
    suspend fun setLibrarySaved(playlistId: String, saved: Boolean) {
        val id = if (isAlbumBrowseId(playlistId)) album(playlistId).let { it.libraryId ?: it.playlistId }
            ?: throw java.io.IOException("No playlist for album $playlistId") else playlistId
        api.post(if (saved) "like/like" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("playlistId", id.removePrefix("VL")) }
        })
    }

    private fun isAlbumBrowseId(id: String) = id.startsWith("MPRE")

    /** Subscribes to (or unsubscribes from) an artist's channel. */
    suspend fun subscribe(channelId: String, subscribed: Boolean) {
        api.post(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe", buildJsonObject {
            putJsonArray("channelIds") { add(channelId) }
        })
    }

    /** Deletes a playlist the account owns. */
    suspend fun deletePlaylist(playlistId: String) {
        api.post("playlist/delete", buildJsonObject { put("playlistId", playlistId.removePrefix("VL")) })
    }

    /** Takes someone else's playlist out of the library (it isn't deleted). */
    suspend fun unsavePlaylist(playlistId: String) {
        api.post("like/removelike", buildJsonObject {
            putJsonObject("target") { put("playlistId", playlistId.removePrefix("VL")) }
        })
    }
}

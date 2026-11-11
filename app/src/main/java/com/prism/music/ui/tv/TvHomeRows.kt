package com.prism.music.ui.tv

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.quickPicks
import com.prism.music.data.model.speedDial
import com.prism.music.data.prefs.HomeSections

/** One of Home's rows on the TV, with the items behind its cards (what OK opens or plays). */
data class TvHomeRow(val key: String, val title: String, val items: List<BrowseItem>)

/** Most cards a TV row holds (more is a long walk with a remote). */
private const val ROW_SIZE = 20
/** The Speed dial row's size, as on the phone's Speed dial (#267). */
private const val DIAL_SIZE = 18

/**
 * Home's rows on the TV (1.8.0), in the phone's default order: Quick picks, Speed dial (YouTube's tiles, then your most
 * played), Recently played, then YouTube's other shelves as it sends them. Mood and genre tiles come in their own row
 * (Moods & genres, [tvMoodRow]), not in YouTube's shelves; empty rows are left out. The rows Prism makes from your own
 * music are [TvOwnRow]s (TvHomeSources).
 */
fun tvHomeRows(
    shelves: List<Shelf>,
    owner: String?,
    recent: List<Song>,
    mostPlayed: List<BrowseItem>,
    titles: TvHomeTitles,
): List<TvHomeRow> {
    val picks = shelves.quickPicks(owner)
    val dial = shelves.speedDial(owner)
    val rows = ArrayList<TvHomeRow>()
    picks?.items?.filterIsInstance<SongItem>()?.take(ROW_SIZE)?.takeIf { it.isNotEmpty() }?.let { rows += TvHomeRow(KEY_PICKS, titles.quickPicks, it) }
    val dialItems = (dial?.items.orEmpty().filter { it !is MoodItem } + mostPlayed).distinctBy { it.id }.take(DIAL_SIZE)
    if (dialItems.isNotEmpty()) rows += TvHomeRow(KEY_DIAL, titles.speedDial, dialItems)
    recent.distinctBy { it.id }.take(ROW_SIZE).map(::SongItem).takeIf { it.isNotEmpty() }?.let { rows += TvHomeRow(KEY_RECENT, titles.recent, it) }
    for (s in shelves) {
        if (s === picks || s === dial) continue
        val items = s.items.filter { it !is MoodItem }.take(ROW_SIZE)
        if (items.isNotEmpty()) rows += TvHomeRow(HomeSections.shelfKey(s.title), s.title, items)
    }
    return rows
}

/** The row titles Prism writes itself (translated). */
data class TvHomeTitles(val quickPicks: String, val speedDial: String, val recent: String)

/** The rows' keys are the phone's Home sections' (one layout's names on both). */
const val KEY_PICKS = HomeSections.QUICK_PICKS
const val KEY_DIAL = HomeSections.SPEED_DIAL
const val KEY_RECENT = HomeSections.RECENT

/** A card for [this]: an artist's is round, a mix or station is a mix, a music video is a video. */
fun BrowseItem.toTvItem(): TvItem = TvItem(
    id = id, title = title, subtitle = subtitle.takeIf { it.isNotBlank() }, art = thumbnail,
    kind = when (this) {
        is SongItem -> if (song.isVideo) TvItem.Kind.VIDEO else TvItem.Kind.SONG
        is AlbumItem -> TvItem.Kind.ALBUM
        is PlaylistItem -> if (isMix) TvItem.Kind.MIX else TvItem.Kind.PLAYLIST
        is ArtistItem -> TvItem.Kind.ARTIST
        else -> TvItem.Kind.PLAYLIST
    },
)

/**
 * A card's id that plays one of Prism's own lists (a mix, your liked songs, the year's top songs…) rather than opening
 * a YouTube page: [TvOwnRow.plays] holds the list.
 */
const val TV_PLAY = "tvplay:"

/** A list a card plays, under its name (the queue's source). */
data class TvPlayList(val title: String, val songs: List<Song>)

/** A row Prism makes from your own music, with the lists its cards play ([TV_PLAY] ids). */
data class TvOwnRow(val row: TvHomeRow, val plays: Map<String, TvPlayList> = emptyMap())

/** Songs as a row (each once, at most a row's worth); null when there are none. */
fun tvSongRow(key: String, title: String, songs: List<Song>): TvOwnRow? =
    songs.distinctBy { it.id }.take(ROW_SIZE).map(::SongItem).takeIf { it.isNotEmpty() }?.let { TvOwnRow(TvHomeRow(key, title, it)) }

/**
 * Made for you: Prism's own mixes (the time-of-day mix, the Daily Mixes, Release Radar, then the mixes from your music)
 * as cards that play them; a mix with no songs is left out.
 */
fun tvMixRow(title: String, mixes: List<com.prism.music.data.foryou.Mix>): TvOwnRow? {
    val shown = mixes.filter { it.songs.isNotEmpty() }.distinctBy { it.id }.take(ROW_SIZE)
    if (shown.isEmpty()) return null
    val items = shown.map { m -> PlaylistItem(TV_PLAY + "mix:" + m.id, m.title, m.subtitle, m.covers.firstOrNull(), isMix = true) }
    return TvOwnRow(TvHomeRow(HomeSections.MADE_FOR_YOU, title, items), shown.associate { m -> TV_PLAY + "mix:" + m.id to TvPlayList(m.title, m.songs) })
}

/**
 * The season's holiday mixes (1.8.0; "Thanksgiving" through November, "Holiday music" through December): each a card of
 * its own cover ([cover]: the picture HolidayCovers keeps), opening as the phone's do: the mix is made when it's
 * picked, not before (the card's id is [TV_PLAY] + "holiday:" + its id). Nothing outside its season ([mixes] empty).
 */
fun tvHolidayRow(
    title: String, mixes: List<com.prism.music.data.seasonal.HolidayMix>,
    name: (com.prism.music.data.seasonal.HolidayMix) -> String, blurb: (com.prism.music.data.seasonal.HolidayMix) -> String,
    cover: (com.prism.music.data.seasonal.HolidayMix) -> String?,
): TvOwnRow? = mixes.take(ROW_SIZE).map { m -> PlaylistItem(TV_HOLIDAY + m.id, name(m), blurb(m), cover(m), isMix = true) }
    .takeIf { it.isNotEmpty() }?.let { TvOwnRow(TvHomeRow(HomeSections.SEASONAL, title, it)) }

/** A holiday mix's card id: [TV_PLAY] + "holiday:" + the mix's id ("season:<key>"). */
const val TV_HOLIDAY = TV_PLAY + "holiday:"

/** New from your artists: the releases (newest first, as the store has them) as album cards. */
fun tvReleaseRow(title: String, releases: List<com.prism.music.data.db.ReleaseEntity>): TvOwnRow? =
    releases.take(15).map { r -> AlbumItem(r.id, r.title, "${r.kind} · ${r.artistName}", r.thumbnail, r.playlistId) }
        .takeIf { it.isNotEmpty() }?.let { TvOwnRow(TvHomeRow(HomeSections.NEW_RELEASES, title, it)) }

/** Moods & genres: YouTube's tiles that fit you best, each opening its page. */
fun tvMoodRow(title: String, moods: List<MoodItem>): TvOwnRow? =
    moods.take(12).takeIf { it.isNotEmpty() }?.let { TvOwnRow(TvHomeRow(HomeSections.MOODS, title, it)) }

/**
 * The Replay card on the TV: your year's time listened (OK plays the year's top songs) and your top song. Nothing until
 * there's [MIN_REPLAY_MS] of listening, as on the phone.
 */
fun tvReplayRow(title: String, msListened: Long, total: String, listened: String, topSongs: List<Song>, topSongLine: String?): TvOwnRow? {
    if (msListened < MIN_REPLAY_MS || topSongs.isEmpty()) return null
    val top = topSongs.first()
    val items = listOfNotNull(
        PlaylistItem(TV_PLAY + "replay", total, listened, top.thumbnail),
        topSongLine?.let { PlaylistItem(TV_PLAY + "replay:top", top.title, it, top.thumbnail) },
    )
    return TvOwnRow(
        TvHomeRow(HomeSections.REPLAY, title, items),
        mapOf(TV_PLAY + "replay" to TvPlayList(title, topSongs), TV_PLAY + "replay:top" to TvPlayList(top.title, listOf(top))),
    )
}

/** As the phone's Replay card: a year with less listening than this has no card. */
const val MIN_REPLAY_MS = 60_000L

/**
 * The shortcut tiles: your liked songs and downloads (each plays), your pinned playlists, and the albums you played
 * last (each opens). [likedTitle]/[downloadsTitle] with their counts as second lines.
 */
fun tvTilesRow(
    title: String,
    liked: TvPlayList?, likedLine: String,
    downloads: TvPlayList?, downloadsLine: String,
    pinned: List<BrowseItem>, recentAlbums: List<BrowseItem>,
): TvOwnRow? {
    val plays = LinkedHashMap<String, TvPlayList>()
    val items = ArrayList<BrowseItem>()
    liked?.takeIf { it.songs.isNotEmpty() }?.let { items += PlaylistItem(TV_PLAY + "liked", it.title, likedLine, it.songs.firstOrNull()?.thumbnail); plays[TV_PLAY + "liked"] = it }
    downloads?.takeIf { it.songs.isNotEmpty() }?.let { items += PlaylistItem(TV_PLAY + "downloads", it.title, downloadsLine, it.songs.firstOrNull()?.thumbnail); plays[TV_PLAY + "downloads"] = it }
    items += pinned
    items += recentAlbums
    val shown = items.distinctBy { it.id }.take(ROW_SIZE)
    return shown.takeIf { it.isNotEmpty() }?.let { TvOwnRow(TvHomeRow(HomeSections.GREETING, title, it), plays) }
}

/** Your top genres (from your liked songs): a card each, playing that genre's liked songs; [line] is its count. */
fun tvGenreRow(title: String, genres: List<Pair<String, List<Song>>>, line: (Int) -> String): TvOwnRow? {
    val shown = genres.filter { it.second.isNotEmpty() }.take(8)
    if (shown.isEmpty()) return null
    val items = shown.map { (g, songs) -> PlaylistItem(TV_PLAY + "genre:" + g, g, line(songs.size), songs.first().thumbnail) }
    return TvOwnRow(TvHomeRow(HomeSections.GENRES, title, items), shown.associate { (g, songs) -> TV_PLAY + "genre:" + g to TvPlayList(g, songs) })
}

/** [liked]'s songs by genre ([genreOf]: song id → genre), most songs first. */
fun tvTopGenres(liked: List<Song>, genreOf: Map<String, String>): List<Pair<String, List<Song>>> =
    liked.mapNotNull { s -> genreOf[s.id]?.let { it to s } }.groupBy({ it.first }, { it.second })
        .entries.sortedByDescending { it.value.size }.map { it.key to it.value }

/**
 * Every row Home has now, in Home's own order before any layout: [TvHomeLayout.CATALOG]'s, then your own sections
 * (pins and rules), then YouTube's shelves as it sent them.
 */
fun tvAllRows(rows: List<TvHomeRow>): List<TvHomeRow> = rows.sortedBy { r ->
    TvHomeLayout.CATALOG.indexOf(r.key).takeIf { it >= 0 } ?: if (TvHomeLayout.isOwn(r.key)) TvHomeLayout.CATALOG.size else TvHomeLayout.CATALOG.size + 1
}
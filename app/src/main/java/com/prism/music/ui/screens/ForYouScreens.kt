package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.components.pane
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismTextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.db.ReleaseEntity
import com.prism.music.data.foryou.Excluded
import com.prism.music.data.foryou.Mix
import com.prism.music.data.foryou.ReleaseRadar
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.hiRes
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// ---------------------------------------------------------------------------- Mixes

/** A mix's picture: up to four of its covers in a square, washed in its color, with its name. */
@Composable
fun MixCover(mix: Mix, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val ui = LocalUi.current
    val tint = Color(mix.color)
    val covers = mix.covers
    Box(modifier.clip(ui.art).background(tint)) {
        if (covers.size >= 4) Column(Modifier.fillMaxSize()) {
            for (row in 0 until 2) Row(Modifier.weight(1f)) {
                for (col in 0 until 2) AsyncImage(hiRes(covers[row * 2 + col], 226), null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
            }
        } else if (covers.isNotEmpty()) AsyncImage(hiRes(covers[0], 544), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to tint.copy(alpha = 0.95f))))
        // Made by Prism: the split light along the bottom edge (Aura × Spectrum), as Prism signs its own covers.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(com.prism.music.ui.theme.PrismTokens.spectrum))
        if (showTitle) Text(
            mix.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        )
    }
}

/**
 * Home's "Made for you", all made on the phone: the time-of-day mix, the Daily Mixes and Release Radar, then [yours],
 * the mixes from your music (by genre, by artist, and what's ready offline, from songs you know).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MadeForYouSection(cfg: HomeSectionConfig, offline: Boolean = false, yours: List<Mix> = emptyList()) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val mixes by c.forYou.mixes.collectAsState()
    val favorites by c.artistPrefs.favorites.collectAsState()
    val recent by c.library.recent.collectAsState()
    // Made when due (once a day; the time-of-day one as the day moves on), in the app's scope so leaving Home
    // doesn't cut it short; Release Radar looks round when it's been a few days.
    LaunchedEffect(Unit) {
        c.scope.launch { runCatching { c.forYou.refresh() } }
        if (c.releaseRadar.due()) ReleaseRadar.runSoon(c.app)
    }
    val list = mixes ?: return
    // Offline, each mix keeps to its songs that play without a connection (and one with too few of them waits).
    val shown = remember(list, offline, yours) {
        val made = if (!offline) list.filter { it.songs.isNotEmpty() }
        else list.map { m -> m.copy(songs = m.songs.filter { c.availableOffline(it.id) }) }.filter { it.songs.size >= 5 }
        // Then the mixes from your music (already kept to what plays offline when offline).
        (made + yours).distinctBy { it.id }
    }
    // Blends live in Library → Blend (and their own pages), not here: Made for you is Prism's own mixes.
    if (shown.isEmpty()) {
        // Nothing to make them from yet: a way to start.
        if (favorites.isEmpty() && recent.isEmpty()) {
            SectionHeader(cfg.title)
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().pane(LocalUi.current.card)
                    .border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, LocalUi.current.card).clickable { nav.go(Routes.PICK_ARTISTS) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PrismIcons.StarFilled, null, tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.pick_a_few_artists_you_like), style = MaterialTheme.typography.titleMedium)
                    Text(tr(R.string.your_mixes_and_new_releases_start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    SectionHeader(cfg.title, strapline = tr(R.string.made_on_your_phone_from_what))
    // Carousel (the default) is the row of covers below; the other styles show the same mixes their way.
    if (cfg.style != com.prism.music.data.prefs.SectionStyle.CAROUSEL) {
        val playMix: (Mix) -> Unit = { mix ->
            c.player.playQueue(mix.songs, 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.PLAYLIST, mix.title, originId = mix.id))
        }
        StyledEntries(cfg.style, shown.map { mix ->
            HomeEntry(
                mix.id, mix.title, mix.subtitle,
                art = { m -> MixCover(mix, m, showTitle = false) },
                onLongClick = { playMix(mix) },
            ) { nav.go(Routes.mix(mix.id)) }
        })
        return
    }
    val w = LocalUi.current.cardWidth
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown, key = { it.id }) { mix ->
            Column(
                Modifier.width(w).clip(LocalUi.current.tile)
                    .combinedClickable(
                        onClick = { nav.go(Routes.mix(mix.id)) },
                        onLongClick = { c.player.playQueue(mix.songs, 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.PLAYLIST, mix.title, originId = mix.id)) },
                    ).padding(4.dp),
            ) {
                MixCover(mix, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------- New releases

private fun ReleaseEntity.asItem() = AlbumItem(id, title, "$kind · $artistName", thumbnail, playlistId)

private fun ReleaseEntity.dateText(today: LocalDate): String {
    val date = releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return year.toString()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days < 0 -> tr(R.string.out, date.format(com.prism.music.localDates("EEEMMMd")))
        days == 0L -> tr(R.string.today)
        days == 1L -> tr(R.string.yesterday)
        days < 7 -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        else -> date.format(com.prism.music.localDates("MMMd"))
    }
}

/** Home's "New releases": the last month's, newest first. */
@Composable
fun NewReleasesSection(cfg: HomeSectionConfig) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val today = remember { LocalDate.now() }
    val recent by remember { c.db.releases().freshFlow(today.year - 1).map { list -> list.filter { ReleaseRadar.isRecent(it, today, 30) } } }
        .collectAsState(initial = emptyList())
    // #311: with the page's "Favorite artists only" on, Home's section keeps to them too.
    val favorites by c.artistPrefs.favorites.collectAsState()
    val starredOnly = remember { c.library.sortOf(ReleaseRadar.STARRED_ONLY) != null }
    val releases = if (starredOnly) ReleaseRadar.fromFavorites(recent, favorites.map { it.id }.toSet()) else recent
    if (releases.isEmpty()) return
    SectionHeader(cfg.title, strapline = tr(R.string.from_artists_you_listen_to), onMore = { nav.go(Routes.NEW_RELEASES) })
    StyledItems(cfg.style, releases.take(15).map { it.asItem() }) { nav.go(Routes.album(it.id)) }
}

/** Release Radar's page: what's come out lately from the listener's artists. */
@Composable
fun NewReleasesScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    val running by c.releaseRadar.running.collectAsState()
    val releases by remember { c.db.releases().freshFlow(today.year - 1).map { list -> list.filter { ReleaseRadar.isRecent(it, today, 90) } } }
        .collectAsState(initial = null)
    fun age(r: ReleaseEntity): Long {
        val d = r.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: java.time.Instant.ofEpochMilli(r.firstSeenAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(d, today)
    }
    // Filters and order (Reddit 1.7: "just the albums out this week that I haven't saved"), kept between visits.
    var within by remember { mutableStateOf(ReleaseWithin.entries.firstOrNull { it.name == c.library.sortOf("releases:within") } ?: ReleaseWithin.QUARTER) }
    var kind by remember { mutableStateOf(ReleaseKind.entries.firstOrNull { it.name == c.library.sortOf("releases:kind") } ?: ReleaseKind.ALL) }
    var order by remember { mutableStateOf(ReleaseOrder.entries.firstOrNull { it.name == c.library.sortOf("releases:order") } ?: ReleaseOrder.NEWEST) }
    var hideSaved by remember { mutableStateOf(c.library.sortOf("releases:hide_saved") != null) }
    // #311 (1.8.1): only your favorite (starred) artists' releases, kept like the other filters; and one artist at a
    // time, picked from the artists you play most (for this visit only).
    var starredOnly by remember { mutableStateOf(c.library.sortOf(ReleaseRadar.STARRED_ONLY) != null) }
    var oneArtist by remember { mutableStateOf<String?>(null) }
    val favorites by c.artistPrefs.favorites.collectAsState()
    val starredIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val mostPlayed by androidx.compose.runtime.produceState(emptyList<String>()) {
        val now = System.currentTimeMillis()
        value = runCatching { c.imports.taste.tasteTopArtists(now - 90L * 86_400_000, now, 60).mapNotNull { it.artistId } }.getOrDefault(emptyList())
    }
    val libraryAlbums by c.library.albums.collectAsState()
    val savedHere by c.prismLibrary.albums.collectAsState()
    val savedIds = remember(libraryAlbums, savedHere) {
        (libraryAlbums.map { it.id } + libraryAlbums.mapNotNull { (it as? com.prism.music.data.model.AlbumItem)?.playlistId } + savedHere.map { it.id }).toSet()
    }
    SubPage(
        tr(R.string.section_new_from_your_artists), bottomPadding, subtitle = tr(R.string.new_albums_singles_and_eps_from),
        actions = { RoundAction(PrismIcons.Refresh, tr(R.string.look_now), glass = true, enabled = !running) { scope.launch { c.releaseRadar.refresh() } } },
    ) {
        if (running) item("running") {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.prism.music.ui.components.PrismSpinner(Modifier.size(18.dp), stroke = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr(R.string.looking_round_your_artists), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val all = releases ?: return@SubPage
        val beforeArtist = all
            .filter { age(it) <= within.days }
            .filter { kind.accepts(it.kind) }
            .filter { !hideSaved || (it.id !in savedIds && (it.playlistId == null || it.playlistId !in savedIds)) }
            .let { if (starredOnly) ReleaseRadar.fromFavorites(it, starredIds) else it }
        val artistChoices = ReleaseRadar.artistsToPick(beforeArtist, mostPlayed)
        item("filters") {
            ReleaseFilters(
                within, kind, order, hideSaved,
                onWithin = { within = it; c.library.setSort("releases:within", it.name) },
                onKind = { kind = it; c.library.setSort("releases:kind", it.name) },
                onOrder = { order = it; c.library.setSort("releases:order", it.name) },
                onHideSaved = { hideSaved = it; c.library.setSort("releases:hide_saved", if (it) "on" else null) },
                starredOnly = starredOnly,
                onStarredOnly = { starredOnly = it; oneArtist = null; c.library.setSort(ReleaseRadar.STARRED_ONLY, if (it) "on" else null) },
                artists = artistChoices, oneArtist = oneArtist, onArtist = { oneArtist = it },
            )
        }
        val list = beforeArtist.filter { oneArtist == null || it.artistId == oneArtist }
        if (starredOnly && starredIds.isEmpty()) item("no-favorites") {
            Text(
                tr(R.string.no_favorite_artists_yet),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        if (all.isNotEmpty() && list.isEmpty()) item("filtered-empty") {
            Text(
                tr(R.string.nothing_matches_these_filters_try_a),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        // Sorted by artist or title: one list; newest first: grouped by week, month and earlier.
        if (order != ReleaseOrder.NEWEST) {
            val sorted = if (order == ReleaseOrder.ARTIST) list.sortedWith(compareBy<ReleaseEntity> { it.artistName.lowercase() }.thenBy { age(it) })
                else list.sortedBy { it.title.lowercase() }
            items(sorted, key = { it.id }) { r -> com.prism.music.ui.components.SwipeToQueue(r.asItem()) { ReleaseRow(r, today) { nav.go(Routes.album(r.id)) } } }
            return@SubPage
        }
        if (all.isEmpty() && !running) item("empty") {
            Text(
                tr(R.string.nothing_new_from_your_artists_in_all),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        listOf(tr(R.string.this_week) to (Long.MIN_VALUE..7L), tr(R.string.this_month) to (8L..30L), tr(R.string.earlier) to (31L..Long.MAX_VALUE)).forEach { (title, range) ->
            val part = list.filter { age(it) in range }
            if (part.isEmpty()) return@forEach
            item("h:$title") {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
            }
            items(part, key = { it.id }) { r -> com.prism.music.ui.components.SwipeToQueue(r.asItem()) { ReleaseRow(r, today) { nav.go(Routes.album(r.id)) } } }
        }
    }
}

private enum class ReleaseWithin(val label: String, val days: Long) { WEEK(tr(R.string.this_week), 7), MONTH(tr(R.string.this_month), 30), QUARTER(tr(R.string.t_3_months), 90) }
private enum class ReleaseKind(val label: String) {
    ALL(tr(R.string.all)), ALBUMS(tr(R.string.albums)), SINGLES(tr(R.string.singles_eps));
    fun accepts(kind: String) = this == ALL || (this == ALBUMS) == (kind == "Album")
}
private enum class ReleaseOrder(val label: String) { NEWEST(tr(R.string.newest)), ARTIST(tr(R.string.artist)), TITLE(tr(R.string.title)) }

/** The New releases page's filters: how far back, which kind, hide what's already in the library, and the order. */
@Composable
private fun ReleaseFilters(
    within: ReleaseWithin, kind: ReleaseKind, order: ReleaseOrder, hideSaved: Boolean,
    onWithin: (ReleaseWithin) -> Unit, onKind: (ReleaseKind) -> Unit, onOrder: (ReleaseOrder) -> Unit, onHideSaved: (Boolean) -> Unit,
    starredOnly: Boolean = false, onStarredOnly: (Boolean) -> Unit = {},
    artists: List<Pair<String, String>> = emptyList(), oneArtist: String? = null, onArtist: (String?) -> Unit = {},
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.prism.music.ui.components.Segmented(ReleaseWithin.entries, within, { it.label }, Modifier.fillMaxWidth(), onWithin)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReleaseKind.entries.forEach { k -> com.prism.music.ui.components.PrismChip(k == kind, { onKind(k) }, k.label) }
            com.prism.music.ui.components.PrismChip(hideSaved, { onHideSaved(!hideSaved) }, tr(R.string.not_in_my_library), icon = if (hideSaved) PrismIcons.Check else null)
            // #311: just the artists you've starred.
            com.prism.music.ui.components.PrismChip(
                starredOnly, { onStarredOnly(!starredOnly) }, tr(R.string.favorite_artists_only),
                icon = if (starredOnly) PrismIcons.Check else PrismIcons.Star,
            )
        }
        // #311: one artist at a time, the ones you play most first.
        if (artists.size > 1) Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.prism.music.ui.components.PrismChip(oneArtist == null, { onArtist(null) }, tr(R.string.every_artist))
            artists.forEach { (id, name) -> com.prism.music.ui.components.PrismChip(oneArtist == id, { onArtist(if (oneArtist == id) null else id) }, name) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.sort_by), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReleaseOrder.entries.forEach { o -> com.prism.music.ui.components.PrismChip(o == order, { onOrder(o) }, o.label) }
        }
    }
}

@Composable
private fun ReleaseRow(r: ReleaseEntity, today: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(r.thumbnail, Modifier.size(60.dp), LocalUi.current.albumArt, placeholderIcon = PrismIcons.Album)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${r.kind} · ${r.artistName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(r.dateText(today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------- Your artists

/** An artist on the picker: YouTube id, name, and a picture once one's found. */
private data class Pickable(val id: String, val name: String, val thumbnail: String?)

/**
 * Artist photos for the picker (a genre page only names its artists), found by an artist search and kept for the
 * session. A few searches at a time: a grid of 36 asked all at once, and the ones YouTube turned away were kept as
 * "no photo" (Drake and YoungBoy Never Broke Again showed blank). A failed search isn't remembered, only a real miss.
 */
private object ArtistPhotos {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val gate = kotlinx.coroutines.sync.Semaphore(4)
    suspend fun of(c: com.prism.music.AppContainer, a: Pickable): String? {
        a.thumbnail?.let { return it }
        cache[a.id]?.let { return it.ifEmpty { null } }
        c.library.artistThumbs.value[a.id]?.let { return it }
        val list = gate.withPermit {
            runCatching { withContext(Dispatchers.IO) { c.ytm.search(a.name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>() } }
        }.getOrElse { return null }
        val want = com.prism.music.data.innertube.SearchRank.norm(a.name)
        val found = (list.firstOrNull { it.id == a.id } ?: list.firstOrNull { com.prism.music.data.innertube.SearchRank.norm(it.title) == want })?.thumbnail
        cache[a.id] = found ?: ""
        return found
    }

    /** An artist named on a genre page without a link: their page and photo, by an exact name match. */
    suspend fun byName(c: com.prism.music.AppContainer, name: String): Pickable? {
        val want = com.prism.music.data.innertube.SearchRank.norm(name)
        if (want.isBlank()) return null
        val list = gate.withPermit {
            runCatching { withContext(Dispatchers.IO) { c.ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>() } }
        }.getOrNull() ?: return null
        val a = list.firstOrNull { com.prism.music.data.innertube.SearchRank.norm(it.title) == want } ?: return null
        a.thumbnail?.let { cache[a.id] = it }
        return Pickable(a.id, a.title, a.thumbnail)
    }
}

/**
 * "Pick artists you like": genres from YouTube Music's own Genres (each genre page's songs and albums give its artists),
 * or a search. Picked artists are starred (on this phone only: nothing is followed on YouTube), and shape the mixes and
 * Release Radar from the first day. [onboarding]: shown after the welcome screen, with a Skip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickArtistsScreen(onboarding: Boolean, bottomPadding: Dp, onDone: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val favorites by c.artistPrefs.favorites.collectAsState()
    val startedWith = remember { favorites.map { it.id }.toSet() }
    val genres by produceState<List<MoodItem>?>(null) {
        value = runCatching { withContext(Dispatchers.IO) { c.ytm.moodsAndGenres() } }.getOrNull()
            // The genres shelf (the other is moods & moments): the longer one.
            ?.maxByOrNull { s -> s.items.count { it is MoodItem } }?.items?.filterIsInstance<MoodItem>()
            ?: emptyList()
    }
    val picked = remember { mutableStateListOf<MoodItem>() }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf<String?>(null) }
    val forGenres = remember { HashMap<String, List<Pickable>>() }
    val artists by produceState<List<Pickable>?>(null, picked.toList(), searched, genres) {
        val q = searched
        value = null
        value = if (!q.isNullOrBlank()) {
            runCatching { withContext(Dispatchers.IO) { c.ytm.search(q, SearchFilter.ARTISTS) } }.getOrNull()?.items
                ?.filterIsInstance<ArtistItem>()?.map { Pickable(it.id, it.title, it.thumbnail) } ?: emptyList()
        } else {
            val g = genres ?: return@produceState
            // Before a genre is picked: the most popular ones to start from.
            val chosen = picked.toList().ifEmpty { g.filter { it.title.lowercase() in DEFAULT_GENRES } }
            // Keyed by id and params: every YouTube genre has the same browse id (they differ only in params), so keying by
            // the id alone gave every chip the first genre's artists.
            val lists = chosen.map { genre ->
                val key = genre.id + "|" + genre.params
                forGenres[key] ?: runCatching { withContext(Dispatchers.IO) { genreArtists(c, genre) } }.getOrDefault(emptyList())
                    .also { if (it.isNotEmpty()) forGenres[key] = it }
            }
            // Taken in turn from each genre, so one doesn't crowd out the rest.
            val out = LinkedHashMap<String, Pickable>()
            for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) l.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
            out.values.toList()
        }
    }
    fun finish() {
        if (favorites.map { it.id }.toSet() != startedWith) {
            ReleaseRadar.runSoon(c.app)
            c.scope.launch { runCatching { c.forYou.refresh(force = true) } }
        }
        onDone()
    }

    // Clear of the mini player and tab bar (none during onboarding, then just the gesture bar).
    Column(Modifier.fillMaxSize().statusBarsPadding().then(if (bottomPadding > 0.dp) Modifier.padding(bottom = bottomPadding) else Modifier.navigationBarsPadding())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!onboarding) RoundAction(PrismIcons.ArrowBack, tr(R.string.back), glass = true) { nav.back() }
            Spacer(Modifier.weight(1f))
            if (onboarding) PrismTextButton({ finish() }) { Text(tr(R.string.skip)) }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(tr(R.string.pick_artists_you_like), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Text(
                tr(R.string.they_shape_your_daily_mixes_and),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            com.prism.music.ui.components.PrismTextField(
                query, { query = it; if (it.isBlank()) searched = null }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(tr(R.string.search_for_an_artist)) }, leadingIcon = { Icon(PrismIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searched = query.trim() }),
            )
        }
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searched.isNullOrBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.orEmpty().forEach { g ->
                        PrismChip(g in picked, { if (g in picked) picked.remove(g) else picked.add(g) }, g.title)
                    }
                }
            }
            val list = artists
            if (list == null) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { com.prism.music.ui.components.PrismSpinner(Modifier.size(30.dp)) }
            } else items(list, key = { it.id }) { a ->
                val on = favorites.any { it.id == a.id }
                val photo by produceState(a.thumbnail, a.id) { value = ArtistPhotos.of(c, a) }
                Column(
                    Modifier.clip(LocalUi.current.tile).clickable {
                        c.artistPrefs.setFavorite(FavoriteArtist(a.id, a.name, photo), !on)
                    }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Artwork(photo, Modifier.size(92.dp), CircleShape, placeholderIcon = PrismIcons.Person)
                        if (on) Box(
                            Modifier.size(92.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).border(3.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(PrismIcons.Check, tr(R.string.picked), tint = Color.White, modifier = Modifier.size(36.dp)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        val count = favorites.size
        PrismButton({ finish() }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(if (count == 0) tr(R.string.done) else trp(R.plurals.done_n_artists, count))
        }
    }
}

/**
 * A genre's artists, from its YouTube Music page: the songs' main artists, most frequent first. Many genre pages (rock,
 * pop) list only albums and playlists, which name their artists without a link ("Album • Led Zeppelin", "Led Zeppelin,
 * AC/DC, Queen…"): those are looked up by name, so those genres aren't empty.
 */
private suspend fun genreArtists(c: com.prism.music.AppContainer, genre: MoodItem): List<Pickable> {
    val shelves = c.ytm.browseShelves(genre.id, genre.params)
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    val named = LinkedHashMap<String, Int>()
    for (s in shelves) for (item in s.items) when (item) {
        is SongItem -> item.song.artists.firstOrNull()?.let { ref ->
            val id = ref.id
            if (id != null) counts[id] = ref.name to ((counts[id]?.second ?: 0) + 1) else named.merge(ref.name, 1, Int::plus)
        }
        // "Album • Artist" / "Single • Artist" / "EP • Artist, Other".
        is AlbumItem -> item.subtitle.split(" • ").getOrNull(1)?.split(", ", " & ")?.firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && !it.first().isDigit() }?.let { named.merge(it, 1, Int::plus) }
        // "Led Zeppelin, Creedence Clearwater Revival, Fleetwood Mac…": the first few.
        is PlaylistItem -> item.subtitle.split(" • ").first().split(", ").take(3).map { it.trim().removeSuffix("…").trim() }
            .filter { it.isNotBlank() && it.length < 40 && !it.contains("views", true) }.forEach { named.merge(it, 1, Int::plus) }
        else -> {}
    }
    val linked = counts.entries.sortedByDescending { it.value.second }.take(36).map { Pickable(it.key, it.value.first, null) }
    if (linked.size >= 24) return linked
    val known = linked.map { com.prism.music.data.innertube.SearchRank.norm(it.name) }.toSet()
    val more = named.entries.sortedByDescending { it.value }.map { it.key }
        .filter { com.prism.music.data.innertube.SearchRank.norm(it) !in known }.take(36 - linked.size)
    val found = kotlinx.coroutines.coroutineScope { more.map { n -> async { ArtistPhotos.byName(c, n) } }.awaitAll() }.filterNotNull()
    return (linked + found).distinctBy { it.id }
}

private val DEFAULT_GENRES = setOf("pop", "hip-hop", "rock")

// ---------------------------------------------------------------------------- Exclusions

/** Settings → Library & Replay → Exclusions: what's left out, each with a way to bring it back. */
@Composable
fun ExclusionsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val artists by c.exclusions.artists.collectAsState()
    val playlists by c.exclusions.playlists.collectAsState()
    val songs by c.exclusions.songs.collectAsState()
    SubPage(tr(R.string.exclusions), bottomPadding, subtitle = tr(R.string.what_prism_leaves_out_of_your)) {
        fun section(title: String, note: String, list: List<Excluded>, artists: Boolean = false, bring: (Excluded) -> Unit) {
            item("h:$title") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (list.isEmpty()) item("e:$title") {
                Text(tr(R.string.none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            items(list, key = { "$title:${it.id}" }) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(e.thumbnail, Modifier.size(44.dp), if (artists) LocalUi.current.artistArt else LocalUi.current.songArt, placeholderIcon = PrismIcons.Song)
                    Spacer(Modifier.width(12.dp))
                    Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PrismTextButton({ bring(e) }) { Text(tr(R.string.bring_back)) }
                }
            }
        }
        section(tr(R.string.artists_not_to_play), tr(R.string.never_in_radios_autoplay_smart_shuffle), artists, artists = true) { c.exclusions.setArtist(it, false) }
        section(tr(R.string.playlists_left_out_of_your_taste), tr(R.string.they_play_as_usual_but_don), playlists) { c.exclusions.setPlaylist(it, false) }
        section(tr(R.string.songs_left_out_of_your_taste), tr(R.string.the_same_for_single_songs), songs) { c.exclusions.setSong(it, false) }
    }
}

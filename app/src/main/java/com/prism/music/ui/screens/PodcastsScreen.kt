package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.prism.music.ui.components.shareTarget
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Episode
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.PodcastPage
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.podcast.song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.PlayingBars
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongActionsSheet
import androidx.compose.ui.text.withLink
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.quietFill
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch

/** A search on the Podcasts page: null lists while it's asking. */
private class PodcastSearch(val query: String, val shows: List<PodcastItem>?, val episodes: List<Song>?)

/** What the Podcasts page shows: the account's shows and saved episodes (signed in), then YouTube Music's Podcasts page. */
private class PodcastsHome(val yourShows: List<PodcastItem>, val saved: List<Song>, val shelves: List<Shelf>)

/**
 * Podcasts, opened from the round button at the top of Home (as on Spotify's home page): episodes to finish,
 * the shows saved to the account, its "Episodes for later", then popular shows and episodes by category.
 */
@Composable
fun PodcastsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val signedIn = s.isLoggedIn
    val load = rememberLoad("podcasts:$signedIn") {
        coroutineScope {
            val shows = async { if (signedIn) runCatching { c.ytm.libraryPodcasts() }.getOrDefault(emptyList()) else emptyList() }
            val saved = async { if (signedIn) c.ytm.savedEpisodes() else emptyList() }
            // YouTube decides by the network's country whether there's a Podcasts page at all: where there isn't
            // (Egypt, #18) it answers "Requested entity was not found", signed in or not. The rest of the page still shows.
            val home = async { runCatching { c.ytm.podcastsHome() } }
            val h = home.await()
            val yours = shows.await()
            val later = saved.await()
            if (h.isFailure && yours.isEmpty() && later.isEmpty() && h.exceptionOrNull() !is com.prism.music.data.innertube.InnerTubeException) throw h.exceptionOrNull()!!
            PodcastsHome(yours, later, h.getOrDefault(emptyList()))
        }
    }
    val resume by c.episodes.inProgress.collectAsState()
    val rssShows by remember { rssShowsFlow(c) }.collectAsState(null)
    var addingRss by remember { mutableStateOf(false) }
    val onSong: (SongItem) -> Unit = { playEpisode(c, it.song, tr(R.string.podcasts)) }
    // Search right here: shows and episodes only, as you type (a moment after the last key).
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val found by androidx.compose.runtime.produceState<PodcastSearch?>(null, query) {
        val q = query.trim()
        if (q.length < 2) { value = null; return@produceState }
        value = PodcastSearch(q, null, null)
        kotlinx.coroutines.delay(350)
        coroutineScope {
            val shows = async { runCatching { c.ytm.search(q, com.prism.music.data.innertube.SearchFilter.PODCASTS).items.filterIsInstance<PodcastItem>() }.getOrNull() }
            val eps = async { runCatching { c.ytm.search(q, com.prism.music.data.innertube.SearchFilter.EPISODES).items.filterIsInstance<SongItem>().map { it.song } }.getOrNull() }
            value = PodcastSearch(q, shows.await() ?: emptyList(), eps.await() ?: emptyList())
        }
    }

    SubPage(tr(R.string.podcasts), bottomPadding) {
        item(key = "search") {
            com.prism.music.ui.components.SearchField(query, tr(R.string.search_shows_and_episodes), { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        found?.let { f ->
            when {
                f.shows == null || f.episodes == null -> item(key = "searching") { LoadingState() }
                f.shows.isEmpty() && f.episodes.isEmpty() -> item(key = "none") {
                    EmptyState(PrismIcons.Podcasts, tr(R.string.nothing_found), tr(R.string.no_shows_or_episodes_for_try, f.query))
                }
                else -> {
                    if (f.shows.isNotEmpty()) item(key = "found-shows") {
                        SectionHeader(tr(R.string.shows))
                        ItemCarousel(f.shows, { nav.open(it, onSong) })
                    }
                    if (f.episodes.isNotEmpty()) {
                        item(key = "found-eps-h") { SectionHeader(tr(R.string.episodes)) }
                        items(f.episodes.take(30), key = { "fe:" + it.id }) { song ->
                            EpisodeRow(Episode(song, date = null, description = null), showShow = true) { playEpisode(c, song, tr(R.string.podcast_search)) }
                        }
                    }
                }
            }
            return@SubPage
        }
        if (resume.isNotEmpty()) {
            item(key = "resume-h") { SectionHeader(tr(R.string.continue_listening)) }
            items(resume.take(5), key = { "r:" + it.song.id }) { e ->
                EpisodeRow(
                    Episode(e.song, date = null, description = null, progress = e.fraction),
                    showShow = true,
                    leftMs = e.leftMs,
                ) { playEpisode(c, e.song, tr(R.string.continue_listening)) }
            }
        }
        // On the phone, so here whatever YouTube's page below does.
        item(key = "rss") { RssShowsSection(rssShows.orEmpty()) { addingRss = true } }
        when (val st = load.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(st.message) { load.reload(false) } }
            is Load.Ok -> {
                val home = st.value
                if (home.yourShows.isNotEmpty()) item(key = "yours") {
                    SectionHeader(tr(R.string.your_shows))
                    ItemCarousel(home.yourShows, { nav.open(it, onSong) })
                }
                if (home.saved.isNotEmpty()) {
                    item(key = "saved-h") { SectionHeader(tr(R.string.episodes_for_later), onMore = { nav.go(Routes.playlist("SE")) }) }
                    items(home.saved.take(5), key = { "sv:" + it.id }) { song ->
                        EpisodeRow(Episode(song, date = null, description = null), showShow = true) { playEpisode(c, song, tr(R.string.episodes_for_later)) }
                    }
                }
                if (!signedIn && resume.isEmpty()) item(key = "hint") {
                    Text(
                        tr(R.string.sign_in_to_see_the_shows),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                home.shelves.forEachIndexed { place, shelf -> shelfItems(shelf, place = place) { nav.open(it, onSong) } }
                if (home.shelves.isEmpty() && home.yourShows.isEmpty()) item {
                    EmptyState(PrismIcons.Podcasts, tr(R.string.no_podcasts_page_here), tr(R.string.prism_music_s_podcasts_page_isn))
                }
            }
        }
    }
    if (addingRss) AddRssShowDialog(onDismiss = { addingRss = false }) { show ->
        addingRss = false
        nav.go(Routes.podcast(show.id))
    }
}

/** Plays one episode on its own (no music radio follows); it resumes where it was left. */
fun playEpisode(c: com.prism.music.AppContainer, song: Song, from: String) {
    c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, from))
}

/** A show's page: its cover, host and description, then its episodes, newest first, loading more as you go. */
@Composable
fun PodcastScreen(id: String, bottomPadding: Dp) {
    // A show added by its feed has nothing on YouTube to ask for.
    if (id.startsWith(Song.RSS_PREFIX)) { RssShowScreen(id, bottomPadding); return }
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad("podcast:$id") { c.ytm.podcast(id) }
    val current by c.player.currentSong.collectAsState()
    val played by c.episodes.played.collectAsState()
    val resume by c.episodes.inProgress.collectAsState()
    // Pages loaded past the first, and the token for the next.
    val more = remember(id) { mutableStateListOf<Episode>() }
    var next by remember(id) { mutableStateOf<String?>(null) }
    var nextReady by remember(id) { mutableStateOf(false) }
    var loadingMore by remember(id) { mutableStateOf(false) }
    var saved by remember(id) { mutableStateOf<Boolean?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val page = (load.state as? Load.Ok<PodcastPage>)?.value
    if (page != null && !nextReady) { next = page.continuation; saved = page.savedToLibrary; nextReady = true }
    val show = page?.let { PodcastItem(it.id, it.title, it.author?.name ?: "", it.thumbnail) }

    SubPage(page?.title ?: tr(R.string.podcast), bottomPadding, actions = {
        if (show != null) RoundAction(PrismIcons.Share, tr(R.string.share)) { sharing = true }
    }) {
        when (val st = load.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(st.message) { load.reload(false) } }
            is Load.Ok -> {
                val p = st.value
                val episodes = (p.episodes + more).distinctBy { it.song.id }
                item(key = "header") {
                    val ui = LocalUi.current
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(p.thumbnail, Modifier.size(132.dp), ui.art, size = 544, placeholderIcon = PrismIcons.Podcasts)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                p.author?.let { a ->
                                    Text(
                                        a.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp).then(if (a.id != null) Modifier.clickable { nav.go(Routes.artist(a.id)) } else Modifier),
                                    )
                                }
                                Text(tr(R.string.podcast), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val latest = episodes.firstOrNull { it.song.id !in played } ?: episodes.firstOrNull()
                            Row(
                                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                                    .clickable(enabled = latest != null) { latest?.let { playEpisode(c, it.song, p.title) } }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(PrismIcons.Play, null, tint = MaterialTheme.colorScheme.surface)
                                Spacer(Modifier.width(6.dp))
                                Text(tr(R.string.latest_episode), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                            if (s.isLoggedIn && saved != null) {
                                val on = saved == true
                                RoundAction(if (on) PrismIcons.BookmarkFilled else PrismIcons.Bookmark, if (on) tr(R.string.remove_from_library) else tr(R.string.save_to_library)) {
                                    saved = !on
                                    scope.launch {
                                        runCatching { c.ytm.setLibrarySaved(p.playlistId, !on) }.onFailure {
                                            saved = on
                                            c.player.reportError(tr(R.string.couldn_t_update_your_library_check))
                                        }
                                    }
                                }
                            }
                        }
                        // Some shows have none on YouTube (JRE's page showed nothing at all): said plainly.
                        Text(
                            p.description ?: tr(R.string.youtube_has_no_description_for_this),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 14.dp).then(if (p.description != null) Modifier.clickable { expanded = !expanded } else Modifier),
                        )
                    }
                }
                item(key = "eps-h") { SectionHeader(tr(R.string.episodes)) }
                items(episodes, key = { "e:" + it.song.id }) { e ->
                    val local = resume.firstOrNull { it.song.id == e.song.id }
                    EpisodeRow(
                        e.copy(
                            progress = local?.fraction ?: e.progress,
                            played = e.played || e.song.id in played,
                        ),
                        playing = current?.id == e.song.id,
                        leftMs = local?.leftMs,
                        about = ShowAbout(p.title, p.description),
                    ) { playEpisode(c, e.song, p.title) }
                }
                if (next != null && show != null) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) LoadingState()
                        else PrismTextButton(onClick = {
                            val token = next ?: return@PrismTextButton
                            loadingMore = true
                            scope.launch {
                                runCatching { c.ytm.podcastEpisodes(token, show) }
                                    .onSuccess { (eps, nxt) -> more += eps; next = nxt?.takeIf { eps.isNotEmpty() } }
                                    .onFailure { c.player.reportError(tr(R.string.couldn_t_load_more_episodes)) }
                                loadingMore = false
                            }
                        }) { Text(tr(R.string.more_episodes)) }
                    }
                }
            }
        }
    }
    if (sharing && show != null) com.prism.music.ui.components.ShareSheet(show.shareTarget()) { sharing = false }
}

/** The shows added by their feeds, and the way to add one. */
@Composable
private fun RssShowsSection(shows: List<com.prism.music.data.podcast.RssShow>, onAdd: () -> Unit) {
    val nav = LocalNavigator.current
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth()) {
        if (shows.isNotEmpty()) {
            SectionHeader(tr(R.string.rss_your_shows))
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shows, key = { it.id }) { show ->
                    Column(Modifier.width(ui.cardWidth).clip(ui.shape(8.dp)).clickable { nav.go(Routes.podcast(show.id)) }.padding(6.dp)) {
                        Artwork(show.image, Modifier.fillMaxWidth().aspectRatio(1f), ui.art, size = 544, placeholderIcon = PrismIcons.Podcasts)
                        Text(
                            show.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PrismIcons.Add, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(tr(R.string.rss_add_a_podcast), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Paste a feed's address: the show is read and kept, or the dialog says why it can't be. */
@Composable
private fun AddRssShowDialog(onDismiss: () -> Unit, onAdded: (com.prism.music.data.podcast.RssShow) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var address by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val add = {
        if (address.isNotBlank() && !busy) {
            busy = true
            problem = null
            scope.launch {
                try {
                    val show = c.rssPodcastsReady().add(address)
                    // onAdded opens the show's page, and navigating has to happen on the main thread.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { onAdded(show) }
                } catch (e: com.prism.music.data.podcast.RssException) {
                    problem = e.message
                }
                busy = false
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.rss_add_a_podcast), Modifier.weight(1f, fill = false))
                InfoMark(info, tr(R.string.rss_add_a_podcast)) { info = !info }
            }
        },
        text = {
            Column {
                InfoText(info, tr(R.string.rss_add_info), Modifier.padding(bottom = 10.dp))
                com.prism.music.ui.components.PrismTextField(
                    address, { address = it; problem = null },
                    Modifier.fillMaxWidth().testTag("rssAddress"), enabled = !busy, singleLine = true,
                    placeholder = { Text(tr(R.string.rss_feed_address)) },
                    supportingText = if (problem != null) ({ Text(problem.orEmpty()) }) else null,
                    isError = problem != null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                )
                if (busy) com.prism.music.ui.components.PrismProgressBar(null, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
        confirmButton = { PrismTextButton(onClick = { add() }, enabled = address.isNotBlank() && !busy) { Text(tr(R.string.add)) } },
        dismissButton = { PrismTextButton(onClick = onDismiss, enabled = !busy) { Text(tr(R.string.cancel)) } },
    )
}

/** The RSS shows' store, first made off the main thread: making it reads their one file, however many shows are in it. */
private suspend fun com.prism.music.AppContainer.rssPodcastsReady() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rssPodcasts }

/** The shows, null until that first read is done. */
private fun rssShowsFlow(c: com.prism.music.AppContainer) = kotlinx.coroutines.flow.flow<List<com.prism.music.data.podcast.RssShow>?> {
    emitAll(c.rssPodcastsReady().shows)
}

/**
 * The page of a show added by its feed: as a YouTube show's, from what's kept on the phone, so it opens at once and works
 * offline. The feed is asked for what's new on opening; if it can't answer, the page says so and shows what it has.
 */
@Composable
private fun RssShowScreen(id: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val ui = LocalUi.current
    val shows by remember { rssShowsFlow(c) }.collectAsState(null)
    val show = shows?.firstOrNull { it.id == id }
    val current by c.player.currentSong.collectAsState()
    val played by c.episodes.played.collectAsState()
    val resume by c.episodes.inProgress.collectAsState()
    var refreshProblem by remember(id) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(id) {
        try {
            c.rssPodcastsReady().refresh(id) { ep -> c.episodes.positionOf(ep) > 0 || c.player.currentSong.value?.id == ep }
        } catch (e: com.prism.music.data.podcast.RssException) {
            refreshProblem = e.message
        }
    }

    SubPage(show?.title ?: tr(R.string.podcast), bottomPadding, actions = {
        if (show != null) RoundAction(PrismIcons.Delete, tr(R.string.rss_remove_show)) { removing = true }
    }) {
        // Still being read: not "gone" yet.
        if (shows == null) return@SubPage
        if (show == null) {
            item { EmptyState(PrismIcons.Podcasts, tr(R.string.rss_show_gone), tr(R.string.rss_show_gone_detail)) }
            return@SubPage
        }
        val dates = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        val episodes = show.episodes.map { e ->
            Episode(e.song(show), date = e.publishedAt?.let { dates.format(java.util.Date(it)) }, description = e.description)
        }
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(show.image, Modifier.size(132.dp), ui.art, size = 544, placeholderIcon = PrismIcons.Podcasts)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(show.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        show.author?.let { a ->
                            Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text(tr(R.string.podcast), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                val latest = episodes.firstOrNull { it.song.id !in played } ?: episodes.firstOrNull()
                Row(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                        .clickable(enabled = latest != null) { latest?.let { playEpisode(c, it.song, show.title) } }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PrismIcons.Play, null, tint = MaterialTheme.colorScheme.surface)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(R.string.latest_episode), color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.labelLarge)
                }
                show.description?.let { d ->
                    Text(
                        d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 14.dp).clickable { expanded = !expanded },
                    )
                }
                refreshProblem?.let { p ->
                    Text(tr(R.string.rss_couldnt_refresh, p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item(key = "eps-h") { SectionHeader(tr(R.string.episodes)) }
        items(episodes, key = { "e:" + it.song.id }) { e ->
            val local = resume.firstOrNull { it.song.id == e.song.id }
            EpisodeRow(
                e.copy(progress = local?.fraction ?: 0f, played = e.song.id in played),
                playing = current?.id == e.song.id,
                leftMs = local?.leftMs,
                about = ShowAbout(show.title, show.description),
            ) { playEpisode(c, e.song, show.title) }
        }
    }
    if (removing && show != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(tr(R.string.rss_remove_show_title, show.title)) },
        text = { Text(tr(R.string.rss_remove_show_detail)) },
        confirmButton = {
            PrismTextButton(onClick = {
                removing = false
                // Your place in its episodes goes with it (a place in an episode that can't be played would stay on the list).
                // Written whole off the main thread, and on c.scope: leaving the page doesn't stop it.
                c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    c.rssPodcastsReady().remove(id).forEach { ep -> c.episodes.forget(ep); c.episodes.unmarkPlayed(ep) }
                }
                nav.back()
            }) { Text(tr(R.string.remove)) }
        },
        dismissButton = { PrismTextButton(onClick = { removing = false }) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * One episode: its picture, title, the date and length (or what's left), a bar for how far in it is, and the start
 * of its description. A played one is dimmed with a tick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    e: Episode,
    showShow: Boolean = false,
    playing: Boolean = false,
    leftMs: Long? = null,
    /** The show it's from (its name and description), for the details sheet. */
    about: ShowAbout? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val song = e.song
    val dim = e.played && !playing
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            // YouTube's episode pictures are wide, a feed's are square covers.
            val picture = if (song.isRss) Modifier.size(58.dp) else Modifier.size(width = 104.dp, height = 58.dp)
            Artwork(song.thumbnail, picture, ui.shape(8.dp), size = 480, placeholderIcon = PrismIcons.Podcasts)
            if (playing) Box(picture.clip(ui.shape(8.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                PlayingBars(Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = when { playing -> scheme.primary; dim -> scheme.onSurfaceVariant; else -> scheme.onSurface },
            )
            val length = when {
                e.played -> tr(R.string.played)
                leftMs != null && leftMs > 0 -> tr(R.string.left_2, formatLength(leftMs / 1000))
                song.durationSec > 0 -> formatLength(song.durationSec.toLong())
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (e.played) Icon(PrismIcons.CheckCircle, null, Modifier.size(14.dp).padding(end = 3.dp), tint = scheme.primary)
                Text(
                    listOfNotNull(if (showShow) song.artistText.takeIf { song.artists.isNotEmpty() } else null, e.date, length).joinToString(" • "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            if (e.progress > 0.01f && !e.played) com.prism.music.ui.components.PrismProgressBar({ e.progress }, Modifier.padding(top = 6.dp).fillMaxWidth(0.6f))
            e.description?.let {
                // Tapping the start of the description opens all of it.
                Text(
                    it.replace(Regex("\\s+"), " "), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp).clickable(onClickLabel = tr(R.string.episode_details)) { details = true },
                )
            }
        }
        IconButton(onClick = { menu = true }) { Icon(PrismIcons.More, tr(R.string.more), tint = scheme.onSurfaceVariant.copy(alpha = 0.8f)) }
    }
    if (menu) SongActionsSheet(song, extra = listOf(com.prism.music.ui.components.SheetItem(PrismIcons.Info, tr(R.string.episode_details)) { details = true })) { menu = false }
    if (details) EpisodeDetailsSheet(e, about, leftMs) { details = false }
}

/** A show's name and description, as its page has them. */
data class ShowAbout(val title: String, val description: String?)

/**
 * Everything about an episode (H6): its whole description (links open in the browser), date and length, then the show
 * it's from. YouTube gives some shows no description at all; that's said rather than left blank.
 */
@Composable
private fun EpisodeDetailsSheet(e: Episode, about: ShowAbout?, leftMs: Long?, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val song = e.song
    com.prism.music.ui.components.ActionSheet(song.thumbnail, onDismiss) { _ ->
        com.prism.music.ui.components.ActionHeader(
            song.thumbnail, song.title,
            listOfNotNull(
                e.date,
                song.durationSec.takeIf { it > 0 }?.let { formatLength(it.toLong()) },
                leftMs?.takeIf { it > 0 && !e.played }?.let { tr(R.string.left_2, formatLength(it / 1000)) },
                tr(R.string.played).takeIf { e.played },
            ).joinToString(" • ").ifEmpty { null },
            shape = LocalUi.current.shape(8.dp), placeholderIcon = PrismIcons.Podcasts,
        )
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                e.description?.let { withLinks(it, scheme.primary) } ?: androidx.compose.ui.text.AnnotatedString(if (song.isRss) tr(R.string.rss_no_description) else tr(R.string.youtube_has_no_description_for_this_2)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.description == null) scheme.onSurfaceVariant else scheme.onSurface,
            )
            val showName = about?.title ?: song.artistText.takeIf { song.artists.isNotEmpty() }
            if (showName != null) {
                Text(
                    tr(R.string.about_the_show), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                )
                Text(showName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                // Only on a show's own page do we have its description; elsewhere the show's name is enough.
                if (about != null) Text(
                    about.description?.let { withLinks(it, scheme.primary) } ?: androidx.compose.ui.text.AnnotatedString(if (song.isRss) tr(R.string.rss_no_description) else tr(R.string.youtube_has_no_description_for_this)),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val URL_IN_TEXT = Regex("""https?://[^\s<>"')\]]+""")

/** [text] with every web address in it tappable (opened in the browser). */
private fun withLinks(text: String, color: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var at = 0
        for (m in URL_IN_TEXT.findAll(text)) {
            append(text.substring(at, m.range.first))
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Url(
                    url, androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = color)),
                ),
            ) { append(url) }
            at = m.range.first + url.length
        }
        append(text.substring(at))
    }

/** "1 hr 12 min", "47 min", "40 sec". */
private fun formatLength(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 && m > 0 -> tr(R.string.hr_min, h, m)
        h > 0 -> tr(R.string.hr, h)
        m > 0 -> tr(R.string.min, m)
        else -> tr(R.string.sec, sec)
    }
}

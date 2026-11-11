package com.prism.music.ui.tv

import com.prism.music.ui.theme.Mono
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.prism.music.data.model.quickPicks
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.hiRes
import com.prism.music.playback.CarLyricsCard
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.player.MusicVideoSurface
import com.prism.music.ui.player.rememberLyricClock
import com.prism.music.ui.player.rememberLyricsState
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.pickSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/*
 * Prism TV's pages (1.8.0): the designed screens (TvHome, TvPlayer, TvBrowse…) fed from the shared engine. The
 * screens only draw; what they show and what OK does is worked out here.
 */

/**
 * Home's shelves for this run of Prism: coming back to Home shows them at once, and they refresh in the background.
 * They're the account's ([owner]): another account (or none) never sees them.
 */
internal object TvHomeShelves {
    private var shelves: List<Shelf> = emptyList()
    private var owner: String? = null
    private var loadedAt = 0L

    /** [account]'s shelves (empty if they're someone else's), and whether they're still fresh at [now]. */
    @Synchronized fun of(account: String?, now: Long): Pair<List<Shelf>, Boolean> =
        if (account != owner) emptyList<Shelf>() to false else shelves to (shelves.isNotEmpty() && now - loadedAt < HOME_FRESH_MS)

    @Synchronized fun keep(account: String?, s: List<Shelf>, now: Long) { shelves = s; owner = account; loadedAt = now }

    @Synchronized fun clear() { shelves = emptyList(); owner = null; loadedAt = 0L }
}

/**
 * Signing out on the TV, as on the phone: the account's library, offline copies and personal Home go with it (a TV is
 * often shared), and its sign-in cookies.
 */
internal fun tvSignOut(c: AppContainer) {
    c.settings.logout()
    c.library.forgetAccount()
    TvHomeShelves.clear()
    TvHomeOwn.clear()
    runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
}

/** How long Home's shelves count as fresh (YouTube's Home changes through the day, not by the minute). */
private const val HOME_FRESH_MS = 30 * 60_000L

@Composable
fun TvHomePlace(nav: TvNav, onSearch: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val owner = settings.accountName.takeIf { it.isNotBlank() }
    // The account's own (signing in or out loads Home again; the disk's copy goes with the account, forgetAccount).
    val account = settings.accountEmail.ifBlank { settings.accountName }.takeIf { settings.isLoggedIn }
    val shelves by produceState(TvHomeShelves.of(account, System.currentTimeMillis()).first, account) {
        val (kept, fresh) = TvHomeShelves.of(account, System.currentTimeMillis())
        value = kept
        if (value.isEmpty()) c.homeCache.read()?.let { value = it }
        if (fresh) return@produceState
        val got = runCatching {
            withContext(Dispatchers.IO) {
                c.ytm.fullHomePages(pages = 3, maxPages = 8) { got -> owner == null || got.quickPicks(owner) != null }
            }
        }.getOrNull()
        if (got != null && got.first.isNotEmpty()) {
            c.scope.launch { c.homeCache.write(got.second) }
            TvHomeShelves.keep(account, got.first, System.currentTimeMillis())
            value = got.first
        }
    }
    val recent by c.library.recent.collectAsState()
    val mostPlayed by produceState(emptyList<BrowseItem>(), recent.firstOrNull()?.id) {
        value = withContext(Dispatchers.IO) { runCatching { com.prism.music.ui.screens.speedDialFromHistory(c) }.getOrDefault(emptyList()) }
    }
    val titles = TvHomeTitles(tr(R.string.quick_picks), tr(R.string.section_speed_dial), tr(R.string.recently_played))
    // Laid out as Customize Home has them: shown or hidden as the phone's Home layout has the rows both have (so phone
    // and TV agree), the hero and the order as the TV's own layout keeps them (none kept: the phone's order, else Home
    // as it comes).
    val phoneLayout = settings.homeLayout
    // Every phone Home section the TV can show, from the same places the phone reads them (TvHomeSources): Prism's
    // mixes, your own lists, the Replay card, moods, tiles, pins… Only the rows Home shows are worked out.
    val online by c.network.online.collectAsState()
    val own = rememberTvOwnRows(c, settings, online, recent, shelves, owner, account)
    val plays = remember(own) { own.flatMap { it.plays.entries }.associate { it.key to it.value } }
    val arranged = remember(shelves, recent, mostPlayed, owner, settings.tv.homeLayout, phoneLayout, own, settings.autoAddSections) {
        // A row Prism makes itself takes the place of YouTube's of the same kind (its Quick picks, made as the phone's).
        val youtube = tvHomeRows(shelves, owner, recent, mostPlayed, titles).filterNot { y -> own.any { it.row.key == y.key } }
            .map { r -> r.copy(title = phoneLayout.firstOrNull { it.key == r.key }?.name ?: r.title) }
        TvHomeLayout.arrange(settings.tv.homeLayout, tvAllRows(own.map { it.row } + youtube), phoneLayout, settings.autoAddSections)
    }
    val rows = arranged.rows
    // The song only, not its progress (which moves twice a second): Home's rows aren't worked out again as it plays;
    // only the hero, which shows the progress, follows it (TvHomeNow below).
    val playingSong by c.player.currentSong.collectAsState()
    // What Home shows, for the editor's Up and Down.
    val heroOn = arranged.heroAt >= 0 && playingSong != null
    LaunchedEffect(arranged, heroOn) { TvHomeOnHome.keys = arranged.rows.map { it.key }.toSet() + if (heroOn) setOf(TvHomeLayout.HERO) else emptySet() }
    val accent = rememberTvAccent(playingSong?.thumbnail)
    // Updates on the TV (plan §2): the same banner and installer as the phone (Android asks once to allow installs).
    val update by c.updates.available.collectAsState()
    val progress by c.updates.progress.collectAsState()
    LaunchedEffect(Unit) { c.updates.check() }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = settings.greetingText.trim().ifBlank {
        when (hour) { in 5..11 -> tr(R.string.good_morning); in 12..17 -> tr(R.string.good_afternoon); else -> tr(R.string.good_evening) }
    }
    // Made once per change of rows, not on each tick of the progress (or every row would redraw twice a second).
    val tvShelves = remember(rows) { rows.map { r -> TvShelf(r.title, r.items.map { it.toTvItem() }) } }
    TvHomeNow { now -> TvHomeScreen(
        greeting, now, tvShelves,
        onPlayPause = { c.player.togglePlay() },
        onOpenPlayer = { nav.go(TvRoute.Player) },
        onOpenLyrics = { nav.go(TvRoute.Lyrics) },
        onItem = { r, i ->
            val row = rows.getOrNull(r) ?: return@TvHomeScreen
            if (openOwn(c, nav, row.items.getOrNull(i) ?: return@TvHomeScreen, plays) { scope.launch { it() } }) return@TvHomeScreen
            open(c, nav, row.items[i], row.items, i, row.title, radio = row.key == KEY_PICKS && settings.quickPicksRadio) {
                scope.launch { it() }
            }.let { ok -> if (!ok) Toast.makeText(context, tr(R.string.couldn_t_start, row.items[i].title), Toast.LENGTH_SHORT).show() }
        },
        accent = accent,
        banner = update?.let { u ->
            {
                TvUpdateBanner(
                    u.version,
                    onUpdate = { if (!c.updates.canInstall()) runCatching { context.startActivity(c.updates.permissionIntent()) } else c.updates.install(u) },
                    note = when (val p = progress) {
                        is com.prism.music.data.UpdateProgress.Downloading -> tr(R.string.downloading_4, u.version)
                        com.prism.music.data.UpdateProgress.NeedsPermission -> tr(R.string.allow_prism_to_install_updates)
                        is com.prism.music.data.UpdateProgress.Failed -> p.message
                        else -> null
                    },
                )
            }
        },
        onSearch = onSearch,
        heroAt = arranged.heroAt,
        onCustomize = { nav.go(TvRoute.HomeEditor) },
    ) }
}

/**
 * What's playing, with its progress, for [content] alone: the progress moves twice a second, and only what's drawn
 * here is drawn again for it (the release check: Home's rows, with their offline checks, were worked out each time).
 */
@Composable
private fun TvHomeNow(content: @Composable (TvNow?) -> Unit) {
    content(rememberTvNow(LocalContainer.current))
}

/**
 * What OK on a card does: a song plays (with its row after it) and the player opens; a mix or station starts; an
 * album, playlist or artist opens its page. [launch] runs the slow starts (a mix's queue comes from YouTube). False
 * when there's nothing to do.
 */
internal fun open(
    c: AppContainer, nav: TvNav, item: BrowseItem, row: List<BrowseItem>, index: Int, rowTitle: String, radio: Boolean,
    launch: (suspend () -> Unit) -> Unit,
): Boolean {
    when (item) {
        is SongItem -> { TvPlay.song(c, row, index, rowTitle, radio); nav.go(TvRoute.Player) }
        is PlaylistItem -> if (item.isMix) launch { if (TvPlay.mix(c, item)) nav.go(TvRoute.Player) } else nav.go(TvRoute.Collection(item))
        is AlbumItem -> nav.go(TvRoute.Collection(item))
        is ArtistItem -> nav.go(TvRoute.Artist(item.id))
        is com.prism.music.data.model.MoodItem -> nav.go(TvRoute.Mood(item))
        else -> return false
    }
    return true
}

/** The cover's color for a page's light ([tvBackdrop]), as the phone picks it (Settings' swatch and vividness). */
@Composable
fun rememberTvAccent(art: String?): Color? {
    val c = LocalContainer.current
    val context = LocalContext.current
    val settings by c.settings.flow.collectAsState()
    val color by produceState<Color?>(null, art, settings.artworkSwatch, settings.artworkVivid) {
        val url = hiRes(art, 160) ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                pickSwatch(Palette.from(bmp).generate(), settings.artworkSwatch, settings.artworkVivid)?.let { Color(Mono.gray(it)) }
            }.getOrNull()
        }
    }
    return color
}

/** How long a page opened for a song waits for it to start (a music video's stream takes a few seconds to find). */
internal const val TV_START_WAIT_MS = 6_000L

/**
 * A page opened for a song that isn't playing yet: "One moment" (focused, so focus is never nowhere) while it starts;
 * if nothing has started after [TV_START_WAIT_MS] (the queue ended or was cleared), [onGiveUp].
 */
@Composable
fun TvStarting(onGiveUp: () -> Unit) {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(TV_START_WAIT_MS); onGiveUp() }
    val scheme = androidx.tv.material3.MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.tv.material3.Text(
            tr(R.string.one_moment),
            Modifier.tvItem(TAG_STARTING, entry = true).focusable(),
            style = androidx.tv.material3.MaterialTheme.typography.headlineMedium, color = scheme.onSurfaceVariant,
        )
    }
}

const val TAG_STARTING = "tv_starting"

/** Now playing: the player's controls on the engine. With nothing lined up (the queue ended, cleared), it closes. */
@Composable
fun TvPlayerPage(nav: TvNav) {
    val c = LocalContainer.current
    val now = rememberTvNow(c)
    if (now == null) { TvStarting { nav.back() }; return }
    val accent = rememberTvAccent(now.art)
    val song by c.player.currentSong.collectAsState()
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { TvSing.load(context) }
    val singReady = TvSing.state.collectAsState().value.sings
    // Each time a panel closes the controls are drawn anew, so the button that opened it takes focus back.
    var returns by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val screen = LocalTvScreen.current
    val memory = LocalTvFocusMemory.current
    val close: () -> Unit = { panel = Panel.NONE; memory.opening(screen); returns++ }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(returns) {
            TvNowPlayingScreen(
                now,
                onPlayPause = { c.player.togglePlay() },
                onNext = { c.player.next() },
                onPrevious = { c.player.previous() },
                onShuffle = { c.player.toggleShuffle() },
                onRepeat = { c.player.cycleRepeat() },
                onLyrics = { nav.go(TvRoute.Lyrics) },
                onQueue = { panel = Panel.QUEUE },
                // The video page turns video mode on (and off as it's left).
                onVideo = { nav.go(TvRoute.Video) },
                onLike = { c.player.currentSong.value?.let { c.library.toggleLike(it) } },
                onMore = { panel = Panel.MORE },
                accent = accent,
                onSeek = { ms -> c.player.seekBy(ms) },
            )
        }
        when (panel) {
            Panel.NONE -> Unit
            Panel.QUEUE -> TvPanel("queue", close) { TvQueuePlace() }
            Panel.MORE -> TvPanel("more", close) { TvActionPanel(song?.title ?: "", songActions(c, nav, song, singReady, close)) }
        }
    }
}

/** The panels over the player. */
private enum class Panel { NONE, QUEUE, MORE }

/**
 * A side panel over a page ([content] draws it): Back or Left closes it, and it keeps its own focus memory (a screen
 * key of its own under the page's).
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.TvPanel(name: String, onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val screen = LocalTvScreen.current + "/" + name
    val memory = LocalTvFocusMemory.current
    remember(screen) { memory.opening(screen); true }
    androidx.activity.compose.BackHandler { onClose() }
    androidx.compose.runtime.CompositionLocalProvider(LocalTvScreen provides screen) {
        Box(
            Modifier.matchParentSize().onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onClose(); true } else false
            }
                // Up or Down past its ends stays in it: the player's controls behind it are out of reach.
                .tvHoldsFocus(),
        ) { content() }
    }
}

/** The queue: the playing song and what comes next; OK plays one ([onPicked] then: the video screen closes it). */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.TvQueuePlace(onPicked: () -> Unit = {}) {
    val c = LocalContainer.current
    val queue by c.player.queue.collectAsState()
    val index by c.player.currentIndex.collectAsState()
    val source by c.queue.source.collectAsState()
    // Rows are the queue's entries; the player counts by each entry's own index (a row isn't always the same number).
    TvQueuePanel(
        source.title.takeIf { it.isNotBlank() },
        queue.map { e -> TvSong(e.uid, e.song.title, e.song.artistText, if (e.song.isLive || e.song.durationSec <= 0) null else tvTime(e.song.durationSec * 1000L), e.song.thumbnail) },
        queue.indexOfFirst { it.index == index },
        // As the phone's queue plays one: prepared and playing, and shuffled, Previous comes back to the song before.
        onSong = { i -> queue.getOrNull(i)?.let { c.player.skipTo(it.index); onPicked() } },
    )
}

/** Lyrics, large: the song's lyrics from the shared lyrics engine, in the listener's lyrics style. */
@Composable
fun TvLyricsPage(nav: TvNav) = TvSongLyrics({ nav.back() })

/**
 * The ⋯ of a song (the player's and the video's): its album, its artists, and Sing once this TV passed Sing's speed
 * check. [close] puts the panel away first.
 */
internal fun songActions(c: AppContainer, nav: TvNav, s: com.prism.music.data.model.Song?, singReady: Boolean, close: () -> Unit): List<TvAction> = buildList {
    s?.album?.id?.let { id -> add(TvAction(tr(R.string.go_to_album), com.prism.music.ui.theme.PrismIcons.Album) { close(); nav.go(TvRoute.Collection(AlbumItem(id, s.album.title, s.primaryArtist, s.thumbnail))) }) }
    s?.artists?.filter { it.id != null }?.forEach { a -> add(TvAction(a.name, com.prism.music.ui.theme.PrismIcons.Person) { close(); nav.go(TvRoute.Artist(a.id!!)) }) }
    // Sing, once this TV passed its speed check (Settings → Sing).
    if (singReady && s != null && c.sing.canSing(s)) add(TvAction(tr(R.string.sing), com.prism.music.ui.theme.PrismIcons.Mic) { close(); nav.go(TvRoute.Sing) })
}

/** An album or playlist: its songs from YouTube (or Prism's own list), Play and Shuffle, a song plays from there. */
@Composable
fun TvCollectionPage(item: BrowseItem, nav: TvNav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page by produceState<com.prism.music.data.model.CollectionPage?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    item is AlbumItem -> c.ytm.album(item.id)
                    com.prism.music.data.PrismLibrary.isPrismList(item.id) -> c.prismLibrary.list(item.id)?.let { l ->
                        com.prism.music.data.model.CollectionPage(l.id, com.prism.music.data.model.CollectionKind.PLAYLIST, l.title, "", thumbnail = l.songs.firstOrNull()?.thumbnail, songs = l.songs)
                    }
                    else -> c.ytm.playlist(item.id)
                }
            }.getOrNull()
        }
    }
    val current by c.player.currentSong.collectAsState()
    val p = page
    val songs = p?.songs.orEmpty()
    val kind = if (item is AlbumItem) QueueKind.ALBUM else QueueKind.PLAYLIST
    val play: (Int, Boolean) -> Unit = { from, shuffle ->
        if (songs.isEmpty()) Toast.makeText(context, tr(R.string.couldn_t_start, item.title), Toast.LENGTH_SHORT).show()
        else { c.player.playQueue(songs, from, QueueSource(kind, p?.title ?: item.title, originId = item.id, page = item), shuffle = shuffle); nav.go(TvRoute.Player) }
    }
    val details = listOfNotNull(p?.subtitle?.takeIf { it.isNotBlank() }, p?.secondSubtitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { item.subtitle }
    val accent = rememberTvAccent(p?.thumbnail ?: item.thumbnail)
    TvCollectionScreen(
        p?.title ?: item.title.ifBlank { tr(R.string.one_moment) }, details, p?.thumbnail ?: item.thumbnail,
        songs.map { s -> TvSong(s.id, s.title, s.artistText, if (s.isLive || s.durationSec <= 0) null else tvTime(s.durationSec * 1000L)) },
        onPlay = { play(0, false) }, onShuffle = { play(0, true) }, onSong = { i -> play(i, false) },
        playing = songs.indexOfFirst { it.id == current?.id }, accent = accent,
    )
}

/** An artist: their page from YouTube; Play plays their popular songs, Shuffle and Radio start YouTube's lists. */
@Composable
fun TvArtistPage(id: String, nav: TvNav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page by produceState<com.prism.music.data.model.ArtistPage?>(null, id) {
        value = withContext(Dispatchers.IO) { runCatching { c.ytm.artist(id) }.getOrNull() }
    }
    val p = page
    val shelves = p?.shelves.orEmpty().map { s -> s to s.items.filter { it !is com.prism.music.data.model.MoodItem } }.filter { it.second.isNotEmpty() }
    val fail = { Toast.makeText(context, tr(R.string.couldn_t_start, p?.name ?: ""), Toast.LENGTH_SHORT).show() }
    val accent = rememberTvAccent(p?.thumbnail)
    TvArtistScreen(
        p?.name ?: tr(R.string.one_moment), p?.subscribers, p?.thumbnail,
        shelves.map { (s, items) -> TvShelf(s.title, items.map { it.toTvItem() }) },
        onPlay = {
            val top = shelves.firstOrNull { (_, items) -> items.any { it is SongItem } }
            if (top == null) scope.launch { if (TvPlay.artist(c, id)) nav.go(TvRoute.Player) else fail() }
            else { TvPlay.song(c, top.second, top.second.indexOfFirst { it is SongItem }, top.first.title, radio = false); nav.go(TvRoute.Player) }
        },
        onShuffle = { scope.launch { if (TvPlay.artist(c, id)) nav.go(TvRoute.Player) else fail() } },
        onRadio = { scope.launch { if (TvPlay.artist(c, id, radio = true)) nav.go(TvRoute.Player) else fail() } },
        onItem = { r, i ->
            val (shelf, items) = shelves.getOrNull(r) ?: return@TvArtistScreen
            open(c, nav, items.getOrNull(i) ?: return@TvArtistScreen, items, i, shelf.title, radio = false) { scope.launch { it() } }
        },
        accent = accent,
    )
}

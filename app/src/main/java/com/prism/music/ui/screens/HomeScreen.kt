package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.db.mergeUploads
import com.prism.music.ui.theme.lit
import androidx.compose.foundation.border
import com.prism.music.data.model.quickPicks
import com.prism.music.data.model.speedDial
import com.prism.music.data.model.speedDialOrderLine
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import com.prism.music.ui.components.customizable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.prism.music.data.prefs.HomeShortcut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.prism.music.ui.components.tickWhileShown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import coil3.compose.AsyncImage
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LocalFrosted
import com.prism.music.ui.components.TopScrollEdge
import com.prism.music.ui.components.pane
import com.prism.music.ui.components.quietFill
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.Playable
import com.prism.music.ui.components.playable
import com.prism.music.ui.components.rememberPlayMenu
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar

/** Last fetched YouTube home shelves, shared with the Catalogue editor. */
val homeShelfCache = MutableStateFlow<List<Shelf>>(emptyList())

/** The last Quick picks YouTube served, the radio standing in when it served none, and Speed dial's own picks (per session). */
private val lastQuickPicks = MutableStateFlow<Shelf?>(null)
private val standInPicks = MutableStateFlow<List<Song>>(emptyList())
/** Quick picks made around the listener's artists, kept for the session (and which pull to refresh made them). */
private val curatedPicks = MutableStateFlow<List<Song>>(emptyList())
private val curatedAt = MutableStateFlow(-1)
/** Which pull to refresh Quick picks were last tried for, and the set shown for it (kept: #297). */
private val picksTried = MutableStateFlow(-1)
private val shownPicks = MutableStateFlow<Pair<Int, List<Song>>?>(null)
/** Whose the set shown is (#349): switching in Customize Home shows the other set at once. */
private val shownPicksYouTube = MutableStateFlow(false)
/** Quick picks from this load's first pages, as soon as they're in (#371): the rest of Home needn't be waited for. */
private val earlyPicks = MutableStateFlow<Shelf?>(null)
/** How long Quick picks wait for the set made around the listener's artists before YouTube's own show. */
private const val QUICK_PICKS_WAIT_MS = 6_000L

/** The sign-in Home's lists above were made for. */
private var homeSignIn: String? = null

/** Another sign-in: nothing kept for this session was made for it. */
private fun forgetHomeLists() {
    homeShelfCache.value = emptyList()
    lastQuickPicks.value = null
    standInPicks.value = emptyList()
    curatedPicks.value = emptyList()
    curatedAt.value = -1
    picksTried.value = -1
    shownPicks.value = null
    earlyPicks.value = null
}

/**
 * The YouTube shelves Home adds as sections of their own (Customize Home's "add new sections"). Neither Quick picks is one:
 * [picks] may be this load's early copy or the last one kept, and then isn't the feed's own, [feedPicks].
 */
internal fun extraShelves(shelves: List<Shelf>, known: Set<String>, placed: Collection<Shelf>, picks: Shelf?, feedPicks: Shelf?, dial: Shelf?, hiddenKinds: Set<String>): List<Shelf> =
    shelves.filter { s ->
        shelfKey(s.title) !in known && placed.none { it === s } && s !== picks && s !== feedPicks && s !== dial && shelfKind(s) !in hiddenKinds
    }

/**
 * What YouTube's own Quick picks may offer while the set made around your artists is made: nothing on a pull to refresh
 * with a set on show. It stays until the new set is in, or if making one fails, rather than swapping to YouTube's after
 * the wait and staying there (the new set would never have shown).
 */
internal fun youtubePicksNow(youtube: List<Song>, picksFromYouTube: Boolean, shown: Pair<Int, List<Song>>?, refresh: Int, sameSource: Boolean): List<Song> =
    if (!picksFromYouTube && shown != null && shown.first < refresh && sameSource) emptyList() else youtube

/**
 * The Quick picks to show (#297): the set already shown ([locked]) stays; else the one made around your artists
 * ([curated]); else, once that's been tried or waited for ([ready]), YouTube's own. Nothing before that, so the section
 * comes in once rather than changing twice. [keep] leaves out songs shown elsewhere (Speed dial's).
 */
internal fun quickPicksToShow(locked: List<Song>?, curated: List<Song>, youtube: List<Song>, ready: Boolean, keep: (Song) -> Boolean = { true }): List<Song> = when {
    locked != null -> locked
    curated.isNotEmpty() -> curated.filter(keep)
    ready -> youtube.filter(keep)
    else -> emptyList()
}

/**
 * Quick picks while a pull to refresh makes the next set (#371: the section went away, pushing Home up, and came back
 * seconds later): the set on show stays until the new one is in. Only that: at the start, and when the source was
 * switched, there's nothing to keep.
 */
internal fun picksDuringRefresh(fresh: List<Song>, shown: Pair<Int, List<Song>>?, refresh: Int, sameSource: Boolean): List<Song> =
    fresh.ifEmpty { if (shown != null && shown.first < refresh && sameSource) shown.second else emptyList() }

/** The set made around your artists, only when it was made for this pull to refresh ([madeFor]): an older one isn't kept. */
internal fun curatedFor(curated: List<Song>, madeFor: Int, refresh: Int): List<Song> = if (madeFor == refresh) curated else emptyList()
private val speedDialMine = MutableStateFlow<List<BrowseItem>>(emptyList())
/** The last lists and mixes made from your music, shown while the next are made. */
private val lastYours = MutableStateFlow<com.prism.music.data.foryou.YourMusic.Sections?>(null)
private val YOURS_KEYS = setOf(HomeSections.ON_REPEAT, HomeSections.REPEAT_REWIND, HomeSections.FORGOTTEN, HomeSections.MADE_FOR_YOU)

fun shelfKey(title: String) = HomeSections.shelfKey(title)

/** A YouTube shelf's identity beyond its title, when it has one: where its "More" button goes. */
internal fun shelfIdentity(s: Shelf): String? = s.moreBrowseId?.let { it + "|" + s.moreParams.orEmpty() }

/**
 * The shelf for each of [keys] (YouTube's sections on Home, by title): by its title, else by where its More button goes,
 * as last seen under that key ([known]: key -> [shelfIdentity]). #277 (1.7.9.2, a Spanish account): sections such as
 * "From the community" vanished when YouTube's language changed (the app's language, or a request answered in English
 * after a retry), because they're kept by title. Only a shelf no other section has by its title, and only one with
 * that identity, stands in.
 */
internal fun matchShelves(keys: Collection<String>, shelves: List<Shelf>, known: Map<String, String>): Map<String, Shelf> {
    val byTitle = shelves.associateBy { shelfKey(it.title) }
    val out = LinkedHashMap<String, Shelf>()
    keys.forEach { k -> byTitle[k]?.let { out[k] = it } }
    keys.filter { it !in out }.forEach { k ->
        val id = known[k] ?: return@forEach
        val same = shelves.filter { shelfIdentity(it) == id }
        val s = same.singleOrNull() ?: return@forEach
        if (shelfKey(s.title) in keys || out.values.any { it === s }) return@forEach
        out[k] = s
    }
    return out
}

/** What kind of section a YouTube shelf is, for "Leave off Home" (1.7.6): mostly music videos, or podcasts. */
internal fun shelfKind(shelf: Shelf): String? {
    val n = shelf.items.size.takeIf { it > 0 } ?: return null
    val podcasts = shelf.items.count { it is com.prism.music.data.model.PodcastItem || (it is SongItem && it.song.isEpisode) }
    if (podcasts * 2 > n) return HomeSections.KIND_PODCASTS
    val videos = shelf.items.count { it is SongItem && it.song.isVideo }
    return if (videos * 2 > n) HomeSections.KIND_VIDEOS else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(bottomPadding: androidx.compose.ui.unit.Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    // Signed out, signed in or switched while Home was kept: another account's lists don't stay on show.
    val signIn = "${settings.cookie.hashCode()}/${settings.authUser}"
    val signInChanged = remember(signIn) {
        (homeSignIn != null && homeSignIn != signIn).also { if (it) forgetHomeLists(); homeSignIn = signIn }
    }
    val layoutNow by rememberUpdatedState(settings.homeLayout)
    // Where each YouTube section's More button goes, by its key: the section is found again under another language's title.
    val shelfIds = remember { c.app.getSharedPreferences("home_shelf_ids", android.content.Context.MODE_PRIVATE) }
    fun knownShelves(): Map<String, String> = shelfIds.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    val home = rememberLoad("home") {
        // YouTube's personalised feed reshuffles per load, so pinned shelves (e.g. "Mixed for you")
        // can land on a later page or drop out entirely. Page further for them, and keep the last
        // copy we saw rather than silently hiding a section the user turned on.
        val wanted = layoutNow.filter { it.visible && it.key.startsWith(HomeSections.YT_PREFIX) }.map { it.key }.toSet()
        // Quick picks and Speed dial too (#162): YouTube puts Quick picks anywhere from the first page to the fourth, and
        // stopping at three left the section out of a load now and then ("refresh until it comes back").
        // Signed in only: signed out, YouTube may send no Quick picks at all (es-MX) and has no Speed dial, and every
        // load would then read all eight pages.
        val owner = c.settings.current.accountName
        val signedIn = owner.isNotBlank()
        val picksOn = signedIn && layoutNow.any { it.visible && it.key == HomeSections.QUICK_PICKS }
        val dialWanted = signedIn && layoutNow.any { it.visible && it.key == HomeSections.SPEED_DIAL }
        val known = knownShelves()
        earlyPicks.value = null
        val (fresh, pages) = c.ytm.fullHomePages(
            pages = 3, maxPages = 8,
            satisfied = { got -> matchShelves(wanted, got, known).size == wanted.size && (!picksOn || got.quickPicks(owner) != null) && (!dialWanted || got.speedDial(owner) != null) },
            // By its title only: on a page of its own, another shelf of song rows could pass for it by its shape.
            onPage = { got -> if (earlyPicks.value == null) got.firstOrNull { it.title.equals("Quick picks", true) }?.let { earlyPicks.value = it } },
        )
        // Kept for the next start, to show while this loads again (#126).
        c.scope.launch { c.homeCache.write(pages) }
        // Each section's identity, under its title now (#277).
        shelfIds.edit().apply { fresh.forEach { s -> shelfIdentity(s)?.let { putString(shelfKey(s.title), it) } } }.apply()
        val freshKeys = matchShelves(wanted, fresh, known).keys
        val kept = homeShelfCache.value.filter { shelfKey(it.title).let { k -> k in wanted && k !in freshKeys } }
        // What YouTube's Home held, and which shelves Prism took for Quick picks and Speed dial (#162, #163: an account
        // in Spanish found neither), for a diagnostics log.
        com.prism.music.playback.AudioDiagnostics.log(
            "home",
            tr(R.string.pages_2, pages.size) + fresh.joinToString { s ->
                // What's in each by kind ("song=3 album=4 playlist=2"): #266's Speed dial had no songs.
                s.title + " (" + listOfNotNull("rows".takeIf { s.rows }, "stacked".takeIf { s.stacked }, shelfKinds(s),
                    s.strapline?.let { "strapline=" + if (it.equals(settings.accountName, true)) "owner" else it }).joinToString(" ") + ")"
            } + tr(R.string.quick_picks_speed_dial, fresh.quickPicks(owner)?.title ?: "none", fresh.speedDial(owner)?.title ?: "none") +
                (if (kept.isNotEmpty()) tr(R.string.kept_from_before, kept.joinToString { it.title }) else "") +
                // #372: the tiles in YouTube's own order, by kind: is a first page of playlists how YouTube sent it?
                fresh.speedDialOrderLine(owner),
        )
        (fresh + kept).also { homeShelfCache.value = it }
    }
    // The last Home (from this session, else kept on the phone) shows until the new one is in: no spinner at start.
    val lastHome by produceState(homeShelfCache.value.ifEmpty { null }) {
        if (value == null) value = c.homeCache.read()?.also { if (homeShelfCache.value.isEmpty()) homeShelfCache.value = it }
    }
    val moods = rememberLoad("moods") { c.taste.rank(runCatching { c.ytm.moodsAndGenres() }.getOrDefault(emptyList())) }
    LaunchedEffect(signIn) { if (signInChanged) home.reload(false) }
    val recent by c.library.recent.collectAsState()
    val liked by c.library.likedShown.collectAsState()

    val shelves = (home.state as? Load.Ok)?.value ?: lastHome ?: emptyList()
    // A reload whose feed has no Quick picks keeps the last ones, rather than the section vanishing or swapping.
    val early by earlyPicks.collectAsState()
    val picks = remember(shelves, settings.accountName, early) { (early ?: shelves.quickPicks(settings.accountName))?.also { lastQuickPicks.value = it } ?: lastQuickPicks.value }
    val dialOn = settings.homeLayout.any { it.key == HomeSections.SPEED_DIAL && it.visible }
    // The Speed dial section shows YouTube's own, so it isn't added again as a shelf of its own.
    val dial = shelves.speedDial(settings.accountName)?.takeIf { dialOn }
    // The holiday section and holiday pins only in their season (1.8.0), read from the phone's own date.
    val today = c.seasonal.today()
    // YouTube's sections on Home, found by title or, in another language, by where they lead (#277).
    val ytShelves = remember(settings.homeLayout, shelves) {
        matchShelves(settings.homeLayout.map { it.key }.filter { it.startsWith(HomeSections.YT_PREFIX) }, shelves, knownShelves())
    }
    val layout = remember(settings.homeLayout, shelves, settings.autoAddSections, picks, settings.homeHideKinds, today, ytShelves) {
        val known = settings.homeLayout.map { it.key }.toSet()
        val placed = ytShelves.values
        val extra = if (settings.autoAddSections) extraShelves(shelves, known, placed, picks, shelves.quickPicks(settings.accountName), dial, settings.homeHideKinds)
            .map { HomeSectionConfig(shelfKey(it.title), it.title) } else emptyList()
        HomeSections.inSeason(settings.homeLayout, today).filter { it.visible } + extra
    }

    // Worked out here rather than inside their rows: a LazyColumn row forgets its state when scrolled off, so these were
    // fetched again on the way back and the sections popped out and in, pushing everything under them.
    // Kept across tab switches, so the session's Quick picks aren't made again on the way back to Home.
    var refreshes by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val seed = recent.firstOrNull()?.takeIf { picks == null && home.state is Load.Ok }
    val standIn by produceState(standInPicks.value, seed?.id, refreshes) {
        // One stand-in per session (a pull to refresh asks again): each new play swapped the whole section before.
        if (seed == null || standInPicks.value.isNotEmpty()) return@produceState
        val heard = recent.take(50).map { it.id }.toSet()
        value = runCatching { c.ytm.next(seed.id, playlistId = "RDAMVM${seed.id}").songs }.getOrDefault(emptyList())
            .filter { it.id !in heard && !it.isVideo }.distinctBy { it.id }.take(20)
            .also { standInPicks.value = it }
    }
    val lastPlayed = recent.firstOrNull()?.id
    val dialMine by produceState(speedDialMine.value, lastPlayed, dialOn) {
        if (!dialOn) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { speedDialFromHistory(c) }.getOrDefault(emptyList()) }
            .also { speedDialMine.value = it }
    }
    // YouTube's tiles lead; yours fill the rest, unless Customize Home says YouTube's only (#131). With none from
    // YouTube (signed out, offline), yours stand in either way.
    val youtubeDialOnly = settings.speedDialYouTubeOnly && (dial?.items?.size ?: 0) >= 3
    val yoursFirst = settings.behavior.speedDialYoursFirst
    val dialItems = remember(dial, dialMine, youtubeDialOnly, recent, liked, yoursFirst) {
        speedDialTiles(dial?.items.orEmpty(), dialMine, (recent + liked).filter { !it.isEpisode && !it.isLive }.map(::SongItem), youtubeDialOnly, yoursFirst)
    }
    // Quick picks made around the artists the listener plays (their favourites, new songs from radios of their top
    // artists, YouTube's own that fit): worked out once a session, again on pull to refresh.
    val youtubePicks = picks?.items?.filterIsInstance<SongItem>()?.map { it.song } ?: standIn
    val onlineNow by c.network.online.collectAsState()
    // #297 / #277 (1.7.9.3): the section showed the kept Home's picks, then the fresh feed's, then these. Now they're
    // made once, from the fresh feed (not the copy kept from last time), and the section waits for them.
    val homeSettled = home.state !is Load.Loading
    // #349: YouTube Music's own, as they come (Customize Home): then Prism makes none of its own, and nothing is waited for.
    val picksFromYouTube = settings.behavior.quickPicksSource == com.prism.music.data.prefs.QuickPicksSource.YOUTUBE
    // #371: not waiting for all of Home when YouTube's picks are in already (kept from last time, or this load's first
    // page): they're one in five of the set, and all of Home first was most of the 5 s the section took to come.
    val canMakePicks = homeSettled || youtubePicks.isNotEmpty()
    val curated by produceState(curatedPicks.value, canMakePicks, refreshes, onlineNow, picksFromYouTube, signIn) {
        if (picksFromYouTube || !onlineNow || (curatedPicks.value.isNotEmpty() && curatedAt.value == refreshes)) return@produceState
        val day = java.time.LocalDate.now().toString()
        val whose = c.settings.current.let { "${it.authUser}|${it.dataSyncId}|${it.accountName}" }
        // #371: today's set, if it's been made: at once at the start, and it's what the listener last saw.
        if (refreshes == 0 && curatedPicks.value.isEmpty()) {
            c.homeCache.readPicks(day, whose)?.let { kept ->
                value = kept; curatedPicks.value = kept; curatedAt.value = refreshes; picksTried.value = refreshes
                return@produceState
            }
        }
        if (!canMakePicks) return@produceState
        // #371: each pull to refresh is a new draw, without what's on show, where there's enough else.
        val onShow = if (refreshes > 0) shownPicks.value?.second.orEmpty().map { it.id }.toSet() else emptySet()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val made = if (youtubePicks.isEmpty() && recent.isEmpty()) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                c.yourMusic.quickPicks(youtubePicks, radioOf = { seed: Song ->
                    asked.incrementAndGet()
                    try { c.ytm.next(seed.id, playlistId = "RDAMVM${seed.id}").songs }
                    catch (e: Exception) { failed.incrementAndGet(); throw e }
                }, draw = refreshes, shown = onShow)
            }.getOrDefault(youtubePicks)
        }
        // No radio answered (a bad connection): this set is only part of one, so it doesn't replace the one on show or
        // stand for the rest of today.
        val partial = asked.get() > 0 && failed.get() == asked.get()
        if (made.isNotEmpty() && !(partial && refreshes > 0)) {
            value = made; curatedPicks.value = made; curatedAt.value = refreshes
            if (!partial) c.scope.launch { c.homeCache.writePicks(day, whose, made) }
        }
        picksTried.value = refreshes
    }
    // Never waiting for long: after a few seconds YouTube's own show. Kept as the pull to refresh it waited for:
    // produceState keeps its value when the keys change, so a plain "waited" stayed true after a refresh.
    val waitedFor by produceState(if (picksTried.value == refreshes) refreshes else -1, refreshes) {
        if (picksTried.value != refreshes) kotlinx.coroutines.delay(QUICK_PICKS_WAIT_MS)
        value = refreshes
    }
    val tried by picksTried.collectAsState()
    val madeFor by curatedAt.collectAsState()
    val picksReady = waitedFor == refreshes || tried == refreshes
    // Songs already on a Speed dial tile aren't repeated in Quick picks. Once shown, the set stays for the session
    // (until pull to refresh), whatever loads after it.
    // YouTube's own come from the fresh feed, not the Home kept from last time, so they come in once too.
    // Once this load's own picks are in, they needn't wait for the pages after them (#371).
    val youtubeReady = (homeSettled && !home.refreshing) || early != null
    val pickSongs = remember(curated, madeFor, youtubePicks, dialItems, picksReady, refreshes, picksFromYouTube, youtubeReady) {
        val onDial = if (dialItems.size >= 3) dialItems.map { it.id }.toSet() else emptySet()
        val before = shownPicks.value
        val sameSource = shownPicksYouTube.value == picksFromYouTube
        val fresh = quickPicksToShow(
            before?.takeIf { it.first == refreshes && sameSource }?.second,
            if (picksFromYouTube) emptyList() else curatedFor(curated, madeFor, refreshes),
            youtubePicksNow(youtubePicks, picksFromYouTube, before, refreshes, sameSource), if (picksFromYouTube) youtubeReady else picksReady,
        ) { it.id !in onDial }
            .also { if (it.isNotEmpty()) { shownPicks.value = refreshes to it; shownPicksYouTube.value = picksFromYouTube } }
        picksDuringRefresh(fresh, before, refreshes, sameSource)
    }

    // Offline, Home is what's on the phone: no YouTube shelves, and lists keep to songs that play without a connection.
    val online by c.network.online.collectAsState()
    LaunchedEffect(online) { if (online && home.state is Load.Err) { home.reload(false); moods.reload(false) } }
    val offline: (Song) -> Boolean = { online || c.availableOffline(it.id) }
    val wantsYours = !online || layout.any { it.key in YOURS_KEYS }
    val yours by produceState(lastYours.value, recent.firstOrNull()?.id, online, refreshes, wantsYours) {
        if (!wantsYours) return@produceState
        value = runCatching { c.yourMusic.sections(offlineOnly = !online) }.getOrNull()?.also { lastYours.value = it }
    }
    val offlinePicks by produceState(emptyList<Song>(), online, recent.firstOrNull()?.id) {
        if (!online) value = runCatching { c.yourMusic.offlinePicks() }.getOrDefault(emptyList())
    }

    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    val openItem: (BrowseItem) -> Unit = { nav.open(it, onSong) }

    val list = rememberLazyListState()
    // Content melts away under the status bar once the greeting has scrolled off.
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 24 } }
    val edge by animateFloatAsState(if (scrolled) 1f else 0f, tween(220), label = "edge")
    Box(Modifier.fillMaxSize()) {
    PullToRefreshBox(
        isRefreshing = home.refreshing,
        onRefresh = { standInPicks.value = emptyList(); earlyPicks.value = null; refreshes++; home.reload(true); moods.reload(true) },
        // (Your own sections load again too: they're keyed on [refreshes].)
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item { HomeTopBar() }
            item(key = "update") { UpdateBanner() }
            item(key = "performance") { PerformanceOffer() }
            item(key = "smartDownloads") { SmartDownloadsOffer() }
            if (!online) item(key = "offline") { OfflineNote() }
            if (online && home.state is Load.Loading && shelves.isEmpty()) item { LoadingState() }
            // With the last Home on show, a failed refresh leaves it there (pull to refresh tries again).
            (home.state as? Load.Err)?.takeIf { online && shelves.isEmpty() }?.let { e -> item { ErrorState(e.message) { home.reload(false) } } }

            layout.forEach { raw ->
                // A section renamed in Customize Home (1.7.6) goes by its new name, also where it reads its own title.
                val cfg = if (raw.name != null) raw.copy(title = raw.name) else raw
                item(key = cfg.key) {
                    // "Customize this" (1.7.3): a section opens its own style and Home's look.
                    Column(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.HOME_SECTION, cfg.key)) {
                    when (cfg.key) {
                        HomeSections.GREETING -> GreetingSection(cfg, recent)
                        HomeSections.QUICK_PICKS -> {
                            // When YouTube serves no picks, songs like your latest play stand in (from its radio, less
                            // what you've just heard). Your recent plays themselves never do: that's Recently played.
                            // A pick starts a radio from it (#38), as on YouTube Music, unless Customize Home says to play them in order.
                            // Offline: the songs on the phone you're most likely to want, played in order.
                            if (!online) { if (offlinePicks.isNotEmpty()) SongSection(cfg, cfg.titled(tr(R.string.quick_picks)), tr(R.string.ready_offline), offlinePicks, snap = true) }
                            else if (pickSongs.isNotEmpty()) SongSection(
                                cfg, cfg.titled(tr(R.string.quick_picks)), picks?.strapline, pickSongs, snap = true,
                                play = if (settings.quickPicksRadio) { list, i -> c.player.playRadio(list[i]) } else null,
                            )
                        }
                        HomeSections.RECENT -> {
                            // "Albums & playlists" (#196): what the songs were played from, as cards; songs until there are any.
                            val played by c.recentCollections.items.collectAsState()
                            if (settings.behavior.recentShows == com.prism.music.data.prefs.RecentShows.COLLECTIONS && played.isNotEmpty()) {
                                SectionHeader(cfg.titled())
                                StyledItems(cfg.style, played, openItem)
                            } else recent.filter(offline).take(20).let { if (it.isNotEmpty()) SongSection(cfg, cfg.titled(), null, it) }
                        }
                        HomeSections.LIKED -> liked.filter(offline).take(20).let { if (it.isNotEmpty()) SongSection(cfg, cfg.titled(tr(R.string.liked_songs)), null, it) { nav.go(Routes.liked()) } }
                        HomeSections.ON_REPEAT -> yours?.onRepeat?.takeIf { it.isNotEmpty() }?.let { SongSection(cfg, cfg.titled(), tr(R.string.your_most_played_this_month), it) }
                        HomeSections.REPEAT_REWIND -> yours?.repeatRewind?.takeIf { it.isNotEmpty() }?.let { SongSection(cfg, cfg.titled(), tr(R.string.what_you_used_to_play_on), it) }
                        HomeSections.FORGOTTEN -> yours?.forgotten?.takeIf { it.isNotEmpty() }?.let { SongSection(cfg, cfg.titled(), tr(R.string.favorites_you_haven_t_played_in), it) }
                        HomeSections.DOWNLOADS -> {
                            val dls by c.downloadState.collectAsState()
                            // Nothing until the downloads have started (then they're up already, so this doesn't hold up the first frame).
                            val songs = remember(dls) { if (dls.isEmpty()) emptyList() else c.downloads.completedSongs() }
                            if (songs.isNotEmpty()) SongSection(cfg, cfg.titled(tr(R.string.downloads)), null, songs.take(20)) { nav.go(Routes.DOWNLOADS) }
                        }
                        HomeSections.SPEED_DIAL -> SpeedDialSection(cfg, if (online) dialItems else dialItems.filter { it is SongItem && c.availableOffline(it.id) }, youtubeDialOnly, openItem)
                        HomeSections.MADE_FOR_YOU -> MadeForYouSection(cfg, offline = !online, yours = yours?.mixes.orEmpty())
                        HomeSections.NEW_RELEASES -> if (online) NewReleasesSection(cfg)
                        HomeSections.SEASONAL -> if (online) HolidaySection(cfg)
                        HomeSections.NEW_TO_YOU, HomeSections.DEEP_CUTS -> DiscoverMixSection(cfg, online)
                        HomeSections.ON_THIS_DAY -> OnThisDaySection(cfg, online)
                        HomeSections.REPLAY -> ReplayTeaser(cfg)
                        HomeSections.GENRES -> TopGenresSection(cfg)
                        HomeSections.MOODS -> if (online) {
                            val m = (moods.state as? Load.Ok)?.value?.picks?.take(12)
                            if (!m.isNullOrEmpty()) {
                                SectionHeader(cfg.titled(), strapline = tr(R.string.picked_for_you), onMore = { nav.go(Routes.MOODS) })
                                if (cfg.style != SectionStyle.GRID) StyledEntries(cfg.style, m.map { mood ->
                                    val col = Color(mood.color or 0xFF000000).lit()
                                    HomeEntry(mood.id + mood.params, mood.title, fill = Brush.linearGradient(listOf(col, col.copy(alpha = 0.6f).compositeOver(Color.Black))), onFill = true, icon = PrismIcons.Mood) { openItem(mood) }
                                }) else LazyHorizontalGrid(
                                    GridCells.Fixed(2), Modifier.height(150.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(m) { mood -> MoodTile(mood, Modifier.width(150.dp).height(68.dp)) { openItem(mood) } }
                                }
                            }
                        }
                        // Your own (1.7.6): a pinned album, playlist, artist or mix, or a rule over your listening.
                        else -> if (cfg.tile && OwnSections.canTile(cfg.key)) {
                            // A pin as one tile (1.8.3, D48): opens its page; Play loads and plays it.
                            val scope = androidx.compose.runtime.rememberCoroutineScope()
                            val context = androidx.compose.ui.platform.LocalContext.current
                            PinnedTile(
                                cfg.titled(), OwnSections.kindOf(cfg.key), cfg.art,
                                onOpen = { OwnSections.pageOf(cfg.key)?.let { nav.go(it) } },
                                onPlay = {
                                    scope.launch {
                                        val songs = OwnSections.songs(c, cfg.key).filter(offline)
                                        if (songs.isEmpty()) android.widget.Toast.makeText(context, tr(R.string.couldn_t_load, cfg.titled()), android.widget.Toast.LENGTH_SHORT).show()
                                        else c.player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, cfg.titled()))
                                    }
                                },
                                modifier = Modifier.testTag("pinTile:${cfg.key}"),
                            )
                        } else if (OwnSections.isOwn(cfg.key)) {
                            val songs by OwnSections.rememberSongs(cfg.key, refreshes)
                            val shownSongs = songs.filter(offline).take(if (cfg.style == SectionStyle.LIST) 20 else 30)
                            if (shownSongs.isNotEmpty()) SongSection(
                                cfg, cfg.titled(), OwnSections.strapline(cfg.key), shownSongs,
                                onMore = OwnSections.pageOf(cfg.key)?.let { r -> { nav.go(r) } },
                            )
                        } else if (online) {
                            // Not a shelf that already shows as Quick picks or Speed dial (it was on Home twice), nor of a
                            // kind left off Home (Customize Home → Leave off Home).
                            val shelf = (ytShelves[cfg.key] ?: shelves.firstOrNull { shelfKey(it.title) == cfg.key })
                                ?.takeIf { it !== picks && it !== dial && shelfKind(it) !in settings.homeHideKinds }
                            if (shelf != null) ShelfSection(cfg, shelf, openItem)
                        }
                    }
                    }
                }
            }
            if (settings.homeCustomizeButton) item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.pane(CircleShape)
                            .clickable { nav.go(Routes.CATALOGUE) }.padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PrismIcons.Customize, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(tr(R.string.customize_home), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    TopScrollEdge({ edge })
    }
}

/**
 * Once, on a phone that may find 1.7's effects heavy (Android 9 or older, or 4 GB of memory or less): Performance mode,
 * offered where it's seen. Either answer puts the card away for good; Settings has the switch.
 */
@Composable
private fun PerformanceOffer() {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val slow = remember { com.prism.music.data.DevicePower.mayBeSlow(context) }
    if (!slow || settings.performanceOffered || settings.performanceMode) return
    val ui = com.prism.music.ui.theme.LocalUi.current
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = ui.gap(14.dp)).fillMaxWidth().pane(ui.shape(16.dp)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PrismIcons.Speed, null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.make_prism_lighter_on_this_phone), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(R.string.performance_mode_pauses_blur_moving_backgrounds),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.align(Alignment.End)) {
            com.prism.music.ui.components.PrismTextButton(onClick = { c.settings.setPerformanceOffered() }) { Text(tr(R.string.no_thanks)) }
            com.prism.music.ui.components.PrismTextButton(onClick = { c.settings.setPerformanceMode(true) }) { Text(tr(R.string.turn_on)) }
        }
    }
}

/**
 * Once, after setting up (#339, 1.8.1.1): Smart Downloads is off on a new install, so Home offers it. Either answer puts
 * the card away for good (Downloads, storage & data has the switch); it never shows to anyone who had it before.
 */
@Composable
private fun SmartDownloadsOffer() {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    var answered by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { answered = c.settings.smartDownloadsChosen() }
    if (answered != false || !settings.onboarded) return
    val ui = com.prism.music.ui.theme.LocalUi.current
    val answer = { on: Boolean -> c.settings.setSmartDownloads(on); answered = true }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = ui.gap(14.dp)).fillMaxWidth().pane(ui.shape(16.dp)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PrismIcons.Download, null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.smart_downloads_offer_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(R.string.smart_downloads_offer_body),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.align(Alignment.End)) {
            com.prism.music.ui.components.PrismTextButton(onClick = { answer(false) }) { Text(tr(R.string.not_now)) }
            com.prism.music.ui.components.PrismTextButton(onClick = { answer(true) }) { Text(tr(R.string.turn_on)) }
        }
    }
}

/** Offline: Home says so, and that what it shows plays without a connection. */
@Composable
private fun OfflineNote() {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = ui.gap(14.dp)).fillMaxWidth().pane(ui.shape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PrismIcons.CloudOff, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(tr(R.string.you_re_offline), style = MaterialTheme.typography.titleSmall)
            Text(tr(R.string.home_shows_the_music_on_this), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeTopBar() {
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    // The hour and the day, looked at again when Prism comes back on screen and each minute while it's shown (1.7.9.3:
    // left open overnight, Home still said "Friday … Good evening" on Saturday morning). Only a change redraws.
    var hour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var day by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.tickWhileShown(60_000) {
            val now = Calendar.getInstance()
            hour = now.get(Calendar.HOUR_OF_DAY)
            day = now.get(Calendar.DAY_OF_YEAR)
        }
    }
    val greeting = settings.greetingText.trim().ifBlank {
        when (hour) { in 5..11 -> tr(R.string.good_morning); in 12..17 -> tr(R.string.good_afternoon); else -> tr(R.string.good_evening) }
    }
    // In the language's own order ("Thursday 15 October", "10월 15일 목요일"), not English's with translated words.
    val today = remember(day) {
        val locale = java.util.Locale.getDefault()
        java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, tr(R.string.eeeedmmmm)), locale).format(java.util.Date())
    }
    val ui = com.prism.music.ui.theme.LocalUi.current
    val header = settings.homeHeader
    // A picture behind the greeting (1.7.6): it runs up under the status bar and fades into the page at its foot.
    if (header == com.prism.music.data.prefs.HomeHeader.PICTURE || header == com.prism.music.data.prefs.HomeHeader.NOW_PLAYING) {
        val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(Modifier.fillMaxWidth().heightIn(min = statusTop + settings.homeHeaderHeight.dp.dp)) {
            HeaderPicture(
                header, settings.homeHeaderPicture,
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
            )
            Box(Modifier.align(Alignment.BottomStart)) { HomeTopRow(greeting, today, showGreeting = true) }
        }
        return
    }
    HomeTopRow(greeting, today, showGreeting = header != com.prism.music.data.prefs.HomeHeader.NONE)
}

/** What's behind a picture header: a background design or your photo, or the playing cover frosted. */
@Composable
private fun HeaderPicture(header: com.prism.music.data.prefs.HomeHeader, picture: String, modifier: Modifier) {
    val c = LocalContainer.current
    if (header == com.prism.music.data.prefs.HomeHeader.NOW_PLAYING) {
        val current by c.player.currentSong.collectAsState()
        val recent by c.library.recent.collectAsState()
        val frost = com.prism.music.ui.components.rememberFrost((current ?: recent.firstOrNull())?.thumbnail)
        Box(modifier) {
            frost?.let { androidx.compose.foundation.Image(it.image, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        return
    }
    val (style, photo) = com.prism.music.ui.theme.Backdrop.parse(picture)
    val s = LocalAppSettings.current
    com.prism.music.ui.theme.BackdropLayer(style, photo, 0f, s.backdropMotion && !s.reduceMotion, modifier)
}

/** The greeting (unless [showGreeting] is off: just the buttons) and Home's round buttons. */
@Composable
private fun HomeTopRow(greeting: String, today: String, showGreeting: Boolean) {
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = ui.gap(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showGreeting) Column(Modifier.weight(1f)) {
            com.prism.music.ui.components.Eyebrow(today)
            Text(greeting, style = com.prism.music.ui.components.pageTitleStyle(), maxLines = 2)
            val profile by LocalContainer.current.account.profile.collectAsState()
            val name = profile?.name ?: settings.accountName
            if (settings.greetingShowName && name.isNotBlank()) Text(
                name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Settings, Home screen, Continue line hides it.
            if (settings.homeContinue) ContextLine()
        } else Spacer(Modifier.weight(1f))
        // Podcasts live a tap away from Home (as on Spotify's), in the same quiet round button as Customize.
        // With the top Customize button off, Podcasts moves over into its place, beside the account.
        // Settings → Home screen → "Podcasts button" off (#28): Podcasts opens from Library instead.
        if (settings.homePodcastsButton) com.prism.music.ui.components.RoundAction(PrismIcons.Podcasts, tr(R.string.podcasts)) { nav.go(Routes.PODCASTS) }
        if (settings.homeCustomizeTopButton) {
            if (settings.homePodcastsButton) Spacer(Modifier.width(8.dp))
            com.prism.music.ui.components.RoundAction(PrismIcons.Customize, tr(R.string.customize_home_2)) { nav.go(Routes.CATALOGUE) }
        }
        Spacer(Modifier.width(8.dp))
        com.prism.music.ui.screens.ProfilePicture(
            Modifier.size(42.dp).clickable(onClickLabel = tr(R.string.settings)) { nav.go(Routes.SETTINGS) }, description = tr(R.string.settings),
        )
    }
}

/**
 * The line under the greeting: what to pick up, in a pill of glass after a short line of split light. Rewind through
 * December once it's open, otherwise the album of the song played last ("Continue After Hours"), which it opens.
 */
@Composable
private fun ContextLine() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val recent by c.library.recent.collectAsState()
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val rewind = remember { Calendar.getInstance().get(Calendar.MONTH) == Calendar.DECEMBER && c.rewind.isOpen(year) }
    val album = recent.firstNotNullOfOrNull { s -> s.album?.takeIf { it.id != null && it.title.isNotBlank() } }
    val (label, open) = when {
        rewind -> tr(R.string.your_rewind_is_ready, year) to { c.rewindRequest.value = year; nav.go(Routes.REPLAY) }
        album != null -> tr(R.string.continue_text, album.title) to { nav.go(Routes.album(album.id!!)) }
        else -> return
    }
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    Row(
        Modifier.padding(top = 10.dp).clip(CircleShape).background(com.prism.music.ui.components.glassFill(1.3f))
            .border(1.dp, com.prism.music.ui.components.glassEdge(), CircleShape)
            .clickable(onClickLabel = label, onClick = open).padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 20.dp, height = 3.dp).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrum(dark)))
        Spacer(Modifier.width(9.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        Icon(PrismIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "A new Prism is out" under the greeting, when there is one. Tapping it downloads the update and
 * opens Android's installer (asking once for permission to install); × puts it off until the next.
 * Settings → Backup & about shows it too, [inSettings] (no ×: it's there because the listener asked), so an update
 * installs without Home (#201: with the Home tab turned off, About pointed at a banner that wasn't there).
 */
@Composable
internal fun UpdateBanner(inSettings: Boolean = false) {
    val c = LocalContainer.current
    val update by c.updates.available.collectAsState()
    val progress by c.updates.progress.collectAsState()
    LaunchedEffect(Unit) { c.updates.check() }
    val u = update ?: return
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    // Back from "Install unknown apps": carry on straight away if it was allowed.
    val allow = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (c.updates.canInstall()) c.updates.install(u)
    }
    LaunchedEffect(progress) {
        if (progress is com.prism.music.data.UpdateProgress.NeedsPermission) runCatching { allow.launch(c.updates.permissionIntent()) }
    }
    var moving by remember { mutableStateOf(false) }
    (progress as? com.prism.music.data.UpdateProgress.NewSignature)?.let { p ->
        LaunchedEffect(p) { moving = true }
        if (moving) com.prism.music.ui.screens.NewSignatureSheet(p.version, p.apk) { moving = false }
    }
    val busy = progress is com.prism.music.data.UpdateProgress.Downloading || progress is com.prism.music.data.UpdateProgress.Installing
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    // Glass ringed in split light: news from Prism itself.
    val ink = scheme.onSurface
    val (title, detail) = when (val p = progress) {
        is com.prism.music.data.UpdateProgress.Downloading ->
            tr(R.string.downloading_prism, u.version) to (p.fraction?.let { "${(it * 100).toInt()}%" } ?: tr(R.string.starting_2))
        com.prism.music.data.UpdateProgress.Installing -> tr(R.string.installing_prism, u.version) to tr(R.string.confirm_in_android_s_installer)
        is com.prism.music.data.UpdateProgress.Failed -> tr(R.string.update_didn_t_finish) to tr(R.string.tap_to_try_again_2, p.message)
        com.prism.music.data.UpdateProgress.NeedsPermission -> tr(R.string.allow_prism_to_install_updates) to tr(R.string.turn_on_allow_from_this_source)
        com.prism.music.data.UpdateProgress.Idle -> tr(R.string.prism_is_available, u.version) to tr(R.string.tap_to_update)
        is com.prism.music.data.UpdateProgress.NewSignature -> tr(R.string.prism_needs_a_reinstall, u.version) to tr(R.string.tap_to_move_over_your_things)
    }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = if (inSettings) 4.dp else ui.gap(14.dp), bottom = if (inSettings) 8.dp else 0.dp)
            .fillMaxWidth().pane(ui.shape(16.dp)).border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, ui.shape(16.dp))
            // While Android's installer waits, a tap brings its screen back (#278); nothing starts a second update.
            .clickable(enabled = progress !is com.prism.music.data.UpdateProgress.Downloading) {
                when {
                    !u.isApk -> runCatching { uri.openUri(u.url) }
                    progress is com.prism.music.data.UpdateProgress.NeedsPermission -> runCatching { allow.launch(c.updates.permissionIntent()) }
                    progress is com.prism.music.data.UpdateProgress.NewSignature -> moving = true
                    else -> c.updates.install(u)
                }
            },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PrismIcons.Update, null, tint = ink)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(vertical = if (busy) 10.dp else 0.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.8f))
            }
            if (!busy && !inSettings) IconButton(onClick = { c.updates.dismiss() }) { Icon(PrismIcons.Close, tr(R.string.dismiss), tint = ink) }
        }
        (progress as? com.prism.music.data.UpdateProgress.Downloading)?.let { p ->
            com.prism.music.ui.components.PrismProgressBar(p.fraction?.let { f -> { f } }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun GreetingSection(cfg: HomeSectionConfig, recent: List<Song>) {
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    /** [playable]: what holding the tile offers to play (null for pages like Replay or Search). */
    data class Tile(val title: String, val icon: ImageVector?, val image: String?, val playable: Playable?, val onClick: () -> Unit)
    val prismLists by c.prismLibrary.lists.collectAsState()
    val tiles = buildList {
        settings.homeShortcuts.forEach { sc ->
            val (icon, route) = when (sc) {
                HomeShortcut.LIKED -> PrismIcons.HeartFilled to Routes.liked()
                HomeShortcut.DOWNLOADS -> PrismIcons.DownloadDone to Routes.DOWNLOADS
                HomeShortcut.REPLAY -> PrismIcons.History to Routes.REPLAY
                HomeShortcut.LIBRARY -> PrismIcons.LibraryFilled to Routes.LIBRARY
                HomeShortcut.MOODS -> PrismIcons.Mood to Routes.MOODS
                HomeShortcut.SEARCH -> PrismIcons.Search to Routes.SEARCH
                HomeShortcut.EQUALIZER -> PrismIcons.Equalizer to Routes.EQ
                HomeShortcut.TOGETHER -> PrismIcons.Groups to Routes.TOGETHER
            }
            val playable = when (sc) {
                HomeShortcut.LIKED -> Playable.Liked
                HomeShortcut.DOWNLOADS -> Playable.Downloads
                else -> null
            }
            add(Tile(sc.label, icon, null, playable) { nav.go(route) })
        }
        settings.homePlaylists.forEach { pin ->
            val prism = pin.id.startsWith(com.prism.music.data.PrismLibrary.PREFIX)
            // A deleted Prism playlist's pin has nothing to open.
            if (prism && prismLists.none { it.id == pin.id }) return@forEach
            // A Prism playlist shows its current name and cover (it changes on the phone; the pin keeps a copy).
            val pl = prismLists.firstOrNull { it.id == pin.id }?.item()?.let { pin.copy(title = it.title, thumbnail = it.thumbnail ?: pin.thumbnail) } ?: pin
            val art = c.covers.art(pl.id, pl.thumbnail)
            add(Tile(pl.title, null, art, Playable.Of(PlaylistItem(pl.id, pl.title, tr(R.string.playlist), art))) { nav.go(Routes.playlist(pl.id)) })
        }
        // One tile per album: by its id, or (music files on the phone have no album id) by its title and artist.
        recent.distinctBy { s -> s.album?.id ?: s.album?.title?.takeIf { it.isNotBlank() }?.let { "t:${it.lowercase()}|${s.primaryArtist.lowercase()}" } ?: s.id }
            .take(settings.homeRecentTiles).forEach { s ->
                val album = s.album?.id?.let { AlbumItem(it, s.album.title, s.primaryArtist, s.thumbnail) }
                add(Tile(s.album?.title ?: s.title, null, s.thumbnail, Playable.Of(album ?: SongItem(s))) {
                    when {
                        album != null -> nav.go(Routes.album(album.id))
                        // A file's album: its songs on the phone, in order.
                        s.isLocal && s.album?.title != null -> {
                            val songs = c.localMusic.tracks.value.map { it.song }.filter { it.album?.title == s.album.title && it.primaryArtist == s.primaryArtist }
                            if (songs.size > 1) c.player.playQueue(songs, songs.indexOfFirst { it.id == s.id }.coerceAtLeast(0), com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.ALBUM, s.album.title))
                            else c.player.playSingle(s)
                        }
                        else -> c.player.playSingle(s)
                    }
                })
            }
    }
    val menu = rememberPlayMenu()
    if (tiles.isEmpty()) return
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    // Spotlight (the default) is the two-column tiles below; the other styles show the same shortcuts their way.
    if (cfg.style != SectionStyle.HERO) {
        Spacer(Modifier.height(ui.gap(12.dp)))
        val glass = androidx.compose.ui.graphics.SolidColor(com.prism.music.ui.components.glassFill(2f))
        StyledEntries(cfg.style, tiles.mapIndexed { i, t ->
            HomeEntry(
                "tile:$i:${t.title}", t.title, image = t.image, icon = t.icon,
                fill = if (t.playable == Playable.Liked) com.prism.music.ui.theme.PrismTokens.spectrumRing() else glass,
                onFill = t.playable == Playable.Liked,
                onLongClick = t.playable?.let { p -> { menu(p) } }, onClick = t.onClick,
            )
        })
        Spacer(Modifier.height(ui.gap(8.dp)))
        return
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = ui.gap(16.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Row(
                        Modifier.weight(1f).height(58.dp).pane(ui.tile, scheme.surfaceContainerHigh.copy(alpha = 0.9f))
                            .combinedClickable(onClick = t.onClick, onLongClick = t.playable?.let { p -> { menu(p) } }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Liked songs wears the prism's colors; the other pages a tile of stronger glass.
                        if (t.icon != null) Box(
                            Modifier.size(58.dp).background(
                                if (t.playable == Playable.Liked) com.prism.music.ui.theme.PrismTokens.spectrumRing()
                                else androidx.compose.ui.graphics.SolidColor(com.prism.music.ui.components.glassFill(2f)),
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(t.icon, null, Modifier.size(22.dp), tint = if (t.playable == Playable.Liked) Color.White else scheme.onSurface) }
                        else Artwork(t.image, Modifier.size(58.dp), androidx.compose.ui.graphics.RectangleShape, size = 226)
                        Text(
                            t.title, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SongSection(
    cfg: HomeSectionConfig, title: String, strapline: String?, listed: List<Song>,
    /** Swipes stop on each column of rows, as YouTube Music's Quick picks do. */
    snap: Boolean = false,
    /** What tapping a song does; null plays the section from it. */
    play: ((List<Song>, Int) -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    val c = LocalContainer.current
    // #216: "Hide them" leaves explicit songs off Home's rows too (a row of nothing else goes).
    val songs = com.prism.music.data.ExplicitFilter.shown(com.prism.music.ui.theme.LocalAppSettings.current.behavior.explicitContent, listed)
    if (songs.isEmpty()) return
    val current by c.player.currentSong.collectAsState()
    val start: (Int) -> Unit = { i -> play?.invoke(songs, i) ?: c.player.playQueue(songs, i, QueueSource(QueueKind.OTHER, title)) }
    val indexOf: (BrowseItem) -> Int = { item -> songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0) }
    SectionHeader(title, strapline = strapline, onMore = onMore)
    when (cfg.style) {
        // Grid (#44): two rows of covers, where List is four rows of songs.
        // One or two songs sit in a single row rather than a column of two.
        SectionStyle.GRID -> LazyHorizontalGrid(
            GridCells.Fixed(if (songs.size > 2) 2 else 1), Modifier.height(if (songs.size > 2) 2 * 212.dp else 212.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(songs.size) { i -> ItemCard(SongItem(songs[i]), width = 140.dp) { start(i) } }
        }
        SectionStyle.LIST -> {
            val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val snapping = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(
                remember(grid) {
                    androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(
                        grid, androidx.compose.foundation.gestures.snapping.SnapPosition.Start,
                    )
                },
            )
            LazyHorizontalGrid(
                GridCells.Fixed(songs.size.coerceIn(1, 4)), Modifier.height(64.dp * songs.size.coerceIn(1, 4)),
                state = grid,
                contentPadding = PaddingValues(horizontal = 4.dp),
                flingBehavior = if (snap) snapping else androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior(),
            ) {
                items(songs.size) { i ->
                    // The song playing is marked, as in a playlist (#242: Quick picks never showed it).
                    Box(Modifier.width(330.dp)) { SongRow(songs[i], playing = current?.id == songs[i].id) { start(i) } }
                }
            }
        }
        SectionStyle.HERO -> HeroRow(songs.map { SongItem(it) }) { item -> start(indexOf(item)) }
        SectionStyle.CAROUSEL -> ItemCarousel(songs.map { SongItem(it) }, { item -> start(indexOf(item)) })
        SectionStyle.DIAL -> SpeedDialPages(songs.map { SongItem(it) }, open = { item -> start(indexOf(item)) })
    }
}

@Composable
private fun ShelfSection(cfg: HomeSectionConfig, shelf: Shelf, open: (BrowseItem) -> Unit) {
    val nav = LocalNavigator.current
    val more = shelf.moreBrowseId?.let { id -> { ShelfPages.opened[id] = shelf; nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) } }
    val songs = shelf.items.filterIsInstance<SongItem>().map { it.song }
    // A shelf of song rows (YouTube's own Quick picks in another language, #131) stops on each column as Prism's does.
    if (cfg.style == SectionStyle.LIST && songs.size == shelf.items.size) {
        SongSection(cfg, cfg.titled(shelf.title), shelf.strapline, songs, snap = shelf.rows, onMore = more)
        return
    }
    SectionHeader(cfg.titled(shelf.title), strapline = shelf.strapline, onMore = more)
    // Every style, for every kind of item (List used to fall back to a carousel unless they were all songs).
    StyledItems(cfg.style, shelf.items, open)
}

/**
 * Speed dial (#53), as on YouTube Music's app: the music you go back to, as big tiles three by three, a page at a time.
 * YouTube's own Speed dial (or "Listen again") leads; your most played albums and songs on this phone fill the rest
 * (and stand in on their own when YouTube has none, signed out or offline).
 */
@Composable
private fun SpeedDialSection(cfg: HomeSectionConfig, items: List<BrowseItem>, youtubeOnly: Boolean, open: (BrowseItem) -> Unit) {
    if (items.size < 3) return
    SectionHeader(cfg.title, strapline = tr(R.string.your_go_to_music))
    when (cfg.style) {
        SectionStyle.CAROUSEL -> ItemCarousel(items, open)
        SectionStyle.HERO -> HeroRow(items, open)
        SectionStyle.LIST -> StyledItems(SectionStyle.LIST, items, open)
        SectionStyle.GRID, SectionStyle.DIAL -> SpeedDialPages(items, open, keepShortPage = youtubeOnly)
    }
}

private const val SPEED_DIAL_SIZE = 27

/**
 * Speed dial's tiles: YouTube's first, then the listener's own most played ([mine]) and, while the last page still has
 * gaps, their recent plays and likes ([more]). #295 (1.7.9.3): with only the most played to fill in from, 18–26 tiles
 * were cut to whole pages, two of them; now the last page is filled. With "YouTube Music's only" ([youtubeOnly]) only
 * YouTube's own tiles show, all of them ([speedDialPages]). With [yoursFirst] (#372: YouTube's answer to Prism isn't
 * what its app shows, and doesn't follow what you play), your own lead, and YouTube's fill in after them.
 */
internal fun speedDialTiles(youtube: List<BrowseItem>, mine: List<BrowseItem>, more: List<BrowseItem>, youtubeOnly: Boolean, yoursFirst: Boolean = false): List<BrowseItem> {
    val theirs = youtube.filter { it !is MoodItem }
    if (youtubeOnly) return theirs.distinctBy { it.id }.take(SPEED_DIAL_SIZE)
    val first = (if (yoursFirst) mine + theirs else theirs + mine).distinctBy { it.id }.take(SPEED_DIAL_SIZE)
    // Whole pages: as many as there are tiles for, and a page begun is filled up from the rest.
    val pages = minOf(SPEED_DIAL_SIZE / 9, (first.size + 8) / 9)
    if (first.size >= pages * 9) return first
    // Not a song whose album is a tile already.
    val shown = first.map { it.id }.toSet()
    return (first + more.filter { (it as? SongItem)?.song?.album?.id !in shown }).distinctBy { it.id }.take(pages * 9)
}

/**
 * Speed dial's pages of 3 × 3: whole pages only (#267, 1.7.9.3: 19 tiles left a last page with one), unless there's
 * less than a page, which shows as it is. With "YouTube Music's only" ([keepShortPage], #349) nothing can fill the last
 * page without going against the setting, and cutting it hid YouTube's own tiles (20 of them gave 2 pages, where they
 * make 3): it stays, short.
 */
internal fun <T> speedDialPages(items: List<T>, keepShortPage: Boolean = false): List<List<T>> {
    val whole = if (items.size > 9 && !keepShortPage) items.size - items.size % 9 else items.size
    return items.take(whole).chunked(9)
}

/** A shelf's items counted by kind, for the diagnostics log's Home line ("song=3 album=4 playlist=2"). */
internal fun shelfKinds(s: com.prism.music.data.model.Shelf): String =
    s.items.groupingBy { it.javaClass.simpleName.removeSuffix("Item").lowercase() }.eachCount().entries.joinToString(" ") { "${it.key}=${it.value}" }

/** The last three months' most played: albums you've played two or more songs from, and the songs otherwise. */
internal suspend fun speedDialFromHistory(c: com.prism.music.AppContainer): List<BrowseItem> {
    val since = System.currentTimeMillis() - 90L * 86_400_000L
    val ids = c.db.plays().mostPlayedIds(since, 80)
    if (ids.isEmpty()) return emptyList()
    val byId = c.db.songs().getAll(ids).associateBy { it.id }
    val songs = ids.mapNotNull { byId[it]?.toSong() }.filter { !it.isEpisode && !it.isLive }
    val perAlbum = songs.filter { it.album?.id != null }.groupingBy { it.album!!.id!! }.eachCount()
    return songs.map { s ->
        val album = s.album?.id?.takeIf { (perAlbum[it] ?: 0) >= 2 }
        if (album != null) AlbumItem(album, s.album.title, s.primaryArtist, s.thumbnail) else SongItem(s)
    }.distinctBy { it.id }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SpeedDialPages(items: List<BrowseItem>, open: (BrowseItem) -> Unit, keepShortPage: Boolean = false) {
    val pages = remember(items, keepShortPage) { speedDialPages(items, keepShortPage) }
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    val ui = com.prism.music.ui.theme.LocalUi.current
    val menu = rememberPlayMenu()
    Column(Modifier.testTag("dialPages")) {
        androidx.compose.foundation.pager.HorizontalPager(
            pager,
            // Even margins on both sides (#67 again on 1.7.1: the next page peeking in at the right read as the grid
            // being off-centre); the dots under it say there's more.
            contentPadding = PaddingValues(horizontal = 16.dp),
            // As wide as the start padding, so the page before doesn't show as a sliver at the left edge (#67).
            pageSpacing = 16.dp,
            verticalAlignment = Alignment.Top,
        ) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pages[p].chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            SpeedDialTile(item, Modifier.weight(1f), ui.shape(14.dp), { open(item) }, item.playable()?.let { pl -> { menu(pl) } })
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                // A short last page (#349) takes a full page's height, so what's under it stays put while swiping.
                if (pages.size > 1) repeat(3 - (pages[p].size + 2) / 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { Spacer(Modifier.weight(1f).aspectRatio(1f)) } }
                }
            }
        }
        if (pages.size > 1) Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.size(if (i == pager.currentPage) 7.dp else 6.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (i == pager.currentPage) 0.8f else 0.25f)),
                )
            }
        }
    }
}

/** One Speed dial tile: the cover filling it (an artist's photo round), its name over the bottom. */
@Composable
private fun SpeedDialTile(item: BrowseItem, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val round = item is com.prism.music.data.model.ArtistItem
    Box(
        modifier.aspectRatio(1f).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (round) Artwork(
            LocalContainer.current.covers.art(item), Modifier.fillMaxSize().padding(10.dp), CircleShape, size = 400,
            placeholderIcon = PrismIcons.Person,
        ) else Artwork(LocalContainer.current.covers.art(item), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), size = 400)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Text(
            item.title, Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp),
            color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HeroRow(items: List<BrowseItem>, open: (BrowseItem) -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    val menu = rememberPlayMenu()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.take(10)) { item ->
            Box(
                Modifier.width(300.dp).aspectRatio(1.25f).clip(ui.shape(24.dp))
                    .combinedClickable(onClick = { open(item) }, onLongClick = item.playable()?.let { p -> { menu(p) } }),
            ) {
                Artwork(LocalContainer.current.covers.art(item), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), size = 900)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                Row(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(com.prism.music.data.model.shownKinds(item.subtitle), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(PrismIcons.Play, null, tint = Color.Black)
                    }
                }
            }
        }
    }
}

/**
 * The Replay card's year so far, kept for the session: the card is a list item, so it read the year from the database
 * again (and popped back in empty, moving the list) every time it scrolled back into view (1.7.4). Read again after
 * five minutes.
 */
private class YearSoFar(
    val at: Long, val ms: Long, val months: List<Long>,
    val topSong: com.prism.music.data.db.SongPlays?, val topArtist: com.prism.music.data.db.ArtistPlays?,
    /** What the bars are (#286) and the imported sources counted (1.8.1): read again when either changes. */
    val graph: com.prism.music.data.prefs.ReplayGraph = com.prism.music.data.prefs.ReplayGraph.MONTHS,
    val counted: Set<com.prism.music.data.imports.ImportSource> = emptySet(),
)
private val yearSoFar = MutableStateFlow<YearSoFar?>(null)

private suspend fun loadYearSoFar(
    c: com.prism.music.AppContainer, graph: com.prism.music.data.prefs.ReplayGraph, counted: Set<com.prism.music.data.imports.ImportSource>,
): YearSoFar = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val start = Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
    // 1.8.1: the sources Replay counts (Prism's own plays unless the listener counts an imported history too).
    val plays = c.imports.stats(counted)
    // This year's months, or (#286) the last weeks or days.
    val bars = replayCardBars(plays, graph, now)
    val top = runCatching { plays.topSongs(start, now, 20).mergeUploads().firstOrNull() to plays.topArtists(start, now, 1).firstOrNull() }
        .getOrDefault(null to null)
    YearSoFar(now, plays.totalMs(start, now), bars, top.first, top.second, graph, counted)
}

@Composable
private fun ReplayTeaser(cfg: HomeSectionConfig) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val graph = LocalAppSettings.current.view.replayGraph
    val counted by c.imports.counted.collectAsState()
    val kept by produceState(yearSoFar.value?.takeIf { it.graph == graph && it.counted == counted }, graph, counted) {
        val have = yearSoFar.value?.takeIf { it.graph == graph && it.counted == counted }
        if (have != null && System.currentTimeMillis() - have.at < 5 * 60_000L) { value = have; return@produceState }
        value = runCatching { loadYearSoFar(c, graph, counted) }.getOrNull()?.also { yearSoFar.value = it } ?: have
    }
    val soFar = kept ?: return
    val ms = soFar.ms
    if (ms < 60_000) return
    val total = listenTotal(ms)
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    // Through December (once this year's Rewind has opened), the teaser is the Rewind's own card and opens its story.
    val rewind = remember { Calendar.getInstance().get(Calendar.MONTH) == Calendar.DECEMBER && c.rewind.isOpen(year) }
    val ui = com.prism.music.ui.theme.LocalUi.current
    // Spotlight (the default) is the big card below; the other styles show the year so far as a few cards or rows:
    // the time listened, the top song and the top artist, each opening Replay.
    val top = soFar.topSong to soFar.topArtist
    // The card counts imported histories too when they're switched on, but doesn't say "Includes Spotify" (owner,
    // 1.8.1): Replay itself and its story still do.
    if (cfg.style != SectionStyle.HERO) {
        val open: () -> Unit = { if (rewind) c.rewindRequest.value = year; nav.go(Routes.REPLAY) }
        val dark = com.prism.music.ui.theme.LocalIsDark.current
        SectionHeader(if (rewind) tr(R.string.rewind, year) else tr(R.string.replay, year), strapline = tr(R.string.your_year_so_far), onMore = open)
        StyledEntries(cfg.style, listOfNotNull(
            HomeEntry("replay:time", total.full, listOfNotNull(if (rewind) tr(R.string.your_year_in_music) else tr(R.string.listened_this_year)).joinToString(" · "), icon = PrismIcons.ReplayFilled,
                fill = com.prism.music.ui.theme.PrismTokens.spectrum(dark), onFill = true, onClick = open),
            top.first?.let { s -> HomeEntry("replay:song", s.title, tr(R.string.your_top_song, s.artistName), image = s.thumbnail, onClick = open) },
            top.second?.let { a -> HomeEntry("replay:artist", a.artistName, tr(R.string.your_top_artist), image = a.thumbnail, icon = PrismIcons.Person, onClick = open) },
        ))
        return
    }
    // Quiet glass with a hairline edge; the color is a faint wash of split light from one corner, a short spectrum
    // dash under the label, and the year's months as small bars in hue order (this month brightest). The time listened
    // is set large and light in plain ink.
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    val months = soFar.months
    // The listener's pick of looks (Settings → Home screen → Replay card); December's Rewind card keeps its own below.
    if (!rewind) {
        val look = ReplayCardStyle.entries.firstOrNull { it.name == LocalAppSettings.current.replayCard } ?: ReplayCardStyle.WASH
        val coverUrl = top.first?.thumbnail?.takeIf { look == ReplayCardStyle.COVER }
        val art = if (coverUrl != null) coil3.compose.rememberAsyncImagePainter(coverUrl) else null
        // How far through the year, to the minute; looked at again every 10 minutes while Home is open (the Ring shows
        // tenths of a percent, which step up about every nine hours, not only when the page is rebuilt).
        val yearDone by produceState(com.prism.music.ui.screens.yearFraction()) {
            while (true) { kotlinx.coroutines.delay(600_000); value = com.prism.music.ui.screens.yearFraction() }
        }
        ReplayCard(
            look,
            ReplayCardData(year, total.full, months, top.first?.title, top.first?.artistName, top.second?.artistName, yearDone, 0, soFar.graph),
            art, Modifier.padding(vertical = ui.gap(16.dp)),
        ) { nav.go(Routes.REPLAY) }
        return
    }
    val hues = com.prism.music.ui.theme.PrismTokens.spectrumColors(dark).map { it.second }
    val wash = if (dark) 0.16f else 0.10f
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = ui.gap(16.dp)).fillMaxWidth().height(156.dp)
            .pane(ui.card).border(0.7.dp, com.prism.music.ui.components.glassEdge(), ui.card)
            .drawBehind {
                // Light through a prism landing on the card: three soft pools of color fanning from the top-right corner.
                listOf(0.0f to 0.62f, 0.5f to 0.48f, 1.0f to 0.34f).forEach { (t, r) ->
                    val col = hues[(t * (hues.size - 1)).toInt()]
                    drawCircle(
                        Brush.radialGradient(listOf(col.copy(alpha = wash), Color.Transparent), center = Offset(size.width * (0.98f - t * 0.16f), size.height * (0.04f + t * 0.22f)), radius = size.width * r),
                        radius = size.width * r, center = Offset(size.width * (0.98f - t * 0.16f), size.height * (0.04f + t * 0.22f)),
                    )
                }
            }
            .clickable {
                if (rewind) c.rewindRequest.value = year
                nav.go(Routes.REPLAY)
            }
            .padding(22.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.68f)) {
            com.prism.music.ui.components.Eyebrow(if (rewind) tr(R.string.rewind, year) else tr(R.string.replay, year))
            Box(Modifier.padding(top = 6.dp, bottom = 8.dp).size(28.dp, 2.dp).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrum(dark)))
            val big = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraLight)
            if (rewind) Text(tr(R.string.your_year_in_music), style = big, color = scheme.onSurface, maxLines = 1)
            else Text(total.full, Modifier.toggleListenUnit(), style = big, color = scheme.onSurface, maxLines = 1)
            Text(
                (if (rewind) tr(R.string.your_top_songs_and_artists_your, total.full) else tr(R.string.your_top_songs_artists_and_genres)),
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            )
        }
        // The year by month: one slim bar for each month so far, in hue order, the current one fully lit.
        if (months.size >= 2 && !rewind) {
            val most = months.max().coerceAtLeast(1L)
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomEnd).size(width = (months.size * 7).dp, height = 54.dp)) {
                val w = 3.dp.toPx()
                val step = size.width / months.size
                months.forEachIndexed { i, ms ->
                    val h = (size.height * (ms.toFloat() / most)).coerceAtLeast(w)
                    val col = hues[(i * (hues.size - 1)) / 11]
                    val x = step * i + (step - w) / 2
                    drawRoundRect(
                        col.copy(alpha = if (i == months.lastIndex) 0.95f else 0.5f),
                        topLeft = Offset(x, size.height - h), size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                    )
                }
            }
        } else Icon(PrismIcons.Play, null, tint = scheme.onSurface, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun TopGenresSection(cfg: HomeSectionConfig) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val liked by c.library.likedShown.collectAsState()
    var genres by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(liked.size) {
        val sample = liked.take(150)
        val map = c.meta.cachedGenres(sample)
        genres = map.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(8)
    }
    if (genres.isEmpty()) return
    SectionHeader(tr(R.string.your_top_genres), strapline = tr(R.string.from_your_liked_songs))
    val palette = listOf(0xFF787878, 0xFF797979, 0xFF828282, 0xFF8E8E8E, 0xFF919191, 0xFF929292, 0xFFBCBCBC, 0xFF7D7D7D)
    val ui = com.prism.music.ui.theme.LocalUi.current
    // Grid (the default) is the colored tiles below; the other styles show the same genres their way.
    if (cfg.style != SectionStyle.GRID) {
        StyledEntries(cfg.style, genres.mapIndexed { i, (g, n) ->
            val col = Color(palette[i % palette.size]).lit()
            HomeEntry("genre:$g", g, com.prism.music.data.model.songCount(n), icon = PrismIcons.Category,
                fill = Brush.linearGradient(listOf(col, col.copy(alpha = 0.6f).compositeOver(Color.Black))), onFill = true) { nav.go(Routes.liked(g)) }
        })
        return
    }
    LazyHorizontalGrid(
        GridCells.Fixed(2), Modifier.height(140.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(genres.size) { i ->
            val (g, n) = genres[i]
            val col = Color(palette[i % palette.size]).lit()
            Box(
                Modifier.width(160.dp).height(64.dp).clip(ui.tile)
                    .background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.6f))))
                    .clickable { nav.go(Routes.liked(g)) }.padding(12.dp),
            ) {
                Text(g, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopStart))
                Text(com.prism.music.data.model.songCount(n), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

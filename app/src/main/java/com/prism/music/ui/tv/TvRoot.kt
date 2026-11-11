package com.prism.music.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.data.model.BrowseItem
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/** Prism TV's top-level places, in the side navigation's order. */
enum class TvSection(val label: () -> String, val icon: () -> ImageVector) {
    HOME({ tr(R.string.home) }, { PrismIcons.Home }),
    SEARCH({ tr(R.string.search) }, { PrismIcons.Search }),
    LIBRARY({ tr(R.string.library) }, { PrismIcons.Library }),
    SETTINGS({ tr(R.string.settings) }, { PrismIcons.Settings }),
}

/** A page opened over the top-level places (Back closes it). [key] files its scroll and focus. */
sealed interface TvRoute {
    val key: String

    data object Player : TvRoute { override val key = "player" }
    data object Lyrics : TvRoute { override val key = "lyrics" }
    data object Video : TvRoute { override val key = "video" }
    /** An album or a playlist. */
    data class Collection(val item: BrowseItem) : TvRoute { override val key = "collection:${item.id}" }
    data class Artist(val id: String) : TvRoute { override val key = "artist:$id" }
    /** Pairing a phone: to sign in with it ([signIn]), or as a remote. */
    data class Pair(val signIn: Boolean, val firstRun: Boolean = false) : TvRoute { override val key = "pair:$signIn" }
    data object Sing : TvRoute { override val key = "sing" }
    /** The first run's welcome. */
    data object Welcome : TvRoute { override val key = "welcome" }
    /** Customize Home: which rows show, in what order. */
    data object HomeEditor : TvRoute { override val key = "home_editor" }
    /** A mood or genre's page (Home's Moods & genres tiles). */
    data class Mood(val item: com.prism.music.data.model.MoodItem) : TvRoute { override val key = "mood:${item.id}:${item.params}" }
}

/** Moving between Prism TV's pages: [go] opens one over the others, [back] closes the top one. */
class TvNav(private val stack: SnapshotStateList<TvRoute>, private val memory: TvFocusMemory, private val sectionKey: () -> String) {
    val top: TvRoute? get() = stack.lastOrNull()

    fun go(route: TvRoute) {
        if (stack.lastOrNull() == route) return
        memory.opening(route.key)
        stack += route
    }

    /** Closes the top page; false when none is open (Back then belongs to the places). */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        memory.opening(stack.lastOrNull()?.key ?: sectionKey())
        return true
    }

    /** Closes every page: back to the places. */
    fun clear() {
        while (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        memory.opening(sectionKey())
    }

    /** The first run is over (signed in with the phone, or not): its pages go, Home shows. */
    var firstRunDone: () -> Unit = {}
}

/**
 * Prism TV's shell (1.8.0): the side navigation (it opens wide while it has focus) and the place picked in it, with
 * pages (the player, an album…) opened over them. Back closes a page, then goes to Home from another place; Back on
 * Home leaves Prism, and the music plays on. Each page keeps its scroll and the item that had focus.
 */
@Composable
fun TvRoot(link: kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?> = remember { kotlinx.coroutines.flow.MutableStateFlow(null) }) {
    TvTheme {
        var section by rememberSaveable { mutableStateOf(TvSection.HOME) }
        val stack = remember { mutableStateListOf<TvRoute>() }
        val memory = remember { TvFocusMemory() }
        val sectionKey = "section:${section.name}"
        val nav = remember(stack, memory) { TvNav(stack, memory) { "section:${section.name}" } }
        // First run (plan §2): welcome, then sign in with the phone or carry on without an account.
        val c = com.prism.music.ui.theme.LocalContainer.current
        nav.firstRunDone = { c.settings.setOnboarded(true); c.settings.setTvWelcomed(); nav.clear() }
        // Once per TV, signed out: also a TV that had Prism's phone app before (onboarded there, never offered the
        // sign-in with the phone).
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val s = c.settings.current
            if (tvWelcomeDue(s.tv.welcomed, s.isLoggedIn)) nav.go(TvRoute.Welcome)
        }
        // What the player has to say (a song that wouldn't play, Sing letting go so the song plays): the phone showed it,
        // the TV said nothing, and a song that failed looked as if it were still loading (1.8.1, Reddit).
        androidx.compose.runtime.LaunchedEffect(Unit) {
            c.player.errors.collect { android.widget.Toast.makeText(c.app, it, android.widget.Toast.LENGTH_LONG).show() }
        }
        BackHandler(enabled = stack.isNotEmpty() || section != TvSection.HOME) {
            if (!nav.back()) { section = TvSection.HOME; memory.opening("section:${TvSection.HOME.name}") }
        }
        val holder = rememberSaveableStateHolder()
        val top = stack.lastOrNull()
        val pick: (TvSection) -> Unit = { section = it; memory.opening("section:${it.name}") }
        // The song only (not its progress, which moves twice a second): the shell redraws when it changes, not as it plays.
        val playingNow by c.player.currentSong.collectAsState()
        // A link the TV was opened with (a YouTube Music link, the media notification): its song plays, or its page opens.
        val pending by link.collectAsState()
        androidx.compose.runtime.LaunchedEffect(pending) {
            val uri = pending ?: return@LaunchedEffect
            link.value = null
            when (val l = tvLinkFor(uri)) {
                // On the app's scope: clearing the link above starts this effect again, which cancelled the song's play
                // halfway (the player said "One moment", then went back).
                is TvLink.Song -> { nav.go(TvRoute.Player); c.scope.launch(kotlinx.coroutines.Dispatchers.Main) { TvPlay.single(c, l.id) } }
                is TvLink.Page -> nav.go(l.route)
                null -> Unit
            }
        }
        // Over everything, when the room has gone quiet while music plays; nothing is drawn under it meanwhile (no
        // lyrics or progress moving unseen), and each page comes back as it was.
        CompositionLocalProvider(LocalTvFocusMemory provides memory) { AmbientHost(onTv = true) { Box(Modifier.fillMaxSize()) {
            // Prism's Aura behind every place and page: the frost of what's playing (or played last).
            TvAura()
            // The side navigation stays put while the places change beside it (focus moves from it into the new place);
            // each place keeps its own scroll and focus.
            if (top == null) TvNavFrame(section, pick, focusNav = false, now = playingNow?.let { it.title to it.thumbnail }, onNowPlaying = { nav.go(TvRoute.Player) }) {
                holder.SaveableStateProvider(sectionKey) {
                    CompositionLocalProvider(LocalTvScreen provides sectionKey) { TvPlace(section, nav, pick) }
                }
            }
            else holder.SaveableStateProvider(top.key) {
                CompositionLocalProvider(LocalTvScreen provides top.key) {
                    when (top) {
                        TvRoute.Player -> TvPlayerPage(nav)
                        TvRoute.Lyrics -> TvLyricsPage(nav)
                        TvRoute.Video -> TvVideoPage(nav)
                        is TvRoute.Collection -> TvCollectionPage(top.item, nav)
                        is TvRoute.Artist -> TvArtistPage(top.id, nav)
                        is TvRoute.Pair -> if (top.firstRun) TvPairPage(top.signIn, nav) { nav.firstRunDone() } else TvPairPage(top.signIn, nav)
                        TvRoute.Sing -> TvSingPage(nav)
                        TvRoute.Welcome -> TvWelcomePage(nav)
                        TvRoute.HomeEditor -> TvHomeEditorPage(nav)
                        is TvRoute.Mood -> TvMoodPage(top.item, nav)
                    }
                }
            }
            // A phone bringing its Prism here: a card in the corner over any screen (never takes focus).
            TvBringBanner()
        } } }
    }
}

/**
 * The side navigation beside a top-level place's [content]. [focusNav]: focus opens on the picked place's item (while
 * a place has nothing to focus yet); otherwise the place's own first item takes it. While something plays ([now]) it
 * ends with "Now playing" (its cover, and its title when open), the way back to the player from any place.
 */
@Composable
fun TvNavFrame(
    section: TvSection,
    onSection: (TvSection) -> Unit,
    focusNav: Boolean,
    /** What's playing (its title and cover), for the navigation's "Now playing"; null with nothing lined up. */
    now: Pair<String, String?>? = null,
    onNowPlaying: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // Entering the navigation (Left from a place) lands on the place being shown.
    val current = remember { androidx.compose.ui.focus.FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    val light = tvLight()
    val itemColors = androidx.tv.material3.NavigationDrawerItemDefaults.colors(
        containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = scheme.onSurfaceVariant,
        selectedContainerColor = TvGlassFill, selectedContentColor = scheme.onSurface,
        focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface,
        focusedSelectedContainerColor = scheme.onSurface, focusedSelectedContentColor = scheme.surface,
    )
    val itemBorder = androidx.tv.material3.NavigationDrawerItemDefaults.border(focusedBorder = tvFocusBorder(androidx.compose.foundation.shape.CircleShape, TV_RING_THIN))
    Box(Modifier.fillMaxSize()) {
        // Over the place (a scrim behind it while open), not beside it: the place isn't laid out again on every frame of
        // the navigation opening and closing.
        androidx.tv.material3.ModalNavigationDrawer(
            scrimBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
            drawerContent = {
                // Its own focus key: moving through it never counts as focus inside a place.
                CompositionLocalProvider(LocalTvScreen provides NAV_SCREEN) {
                    Column(
                        // Dark glass all the way across (open, it lies over the place: the place mustn't show through its names).
                        Modifier.fillMaxHeight()
                            .background(TvPanelFill.copy(alpha = 1f))
                            .padding(vertical = TV_SAFE_V, horizontal = 12.dp).selectableGroup()
                            .focusRestorerTo(current).testTag(TAG_NAV),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        for (s in TvSection.entries) {
                            NavigationDrawerItem(
                                selected = section == s,
                                onClick = { onSection(s) },
                                leadingContent = { Icon(s.icon(), contentDescription = null) },
                                colors = itemColors,
                                border = itemBorder,
                                // Where you are: the split light's mark at its edge (as the phone's tab bar).
                                modifier = (if (s == section) Modifier.focusRequester(current) else Modifier)
                                    .tvCurrentMark(s == section, light)
                                    .tvItem("$TAG_NAV/${s.name}", entry = focusNav && s == section),
                            ) { Text(s.label()) }
                        }
                        if (now != null) {
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
                            // Reddit (1.8.1): the song's title was cut off at the bottom here. The item leaves its two
                            // lines 32 dp (its height less its padding), so they're sized to that, whatever the text size.
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val body = MaterialTheme.typography.bodyMedium
                            fun line(size: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) = with(density) {
                                body.copy(fontSize = size.toSp(), lineHeight = height.toSp(), platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                            }
                            NavigationDrawerItem(
                                selected = false,
                                onClick = onNowPlaying,
                                colors = itemColors,
                                border = itemBorder,
                                leadingContent = {
                                    com.prism.music.ui.components.Artwork(
                                        now.second, Modifier.size(28.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), size = 96,
                                    )
                                },
                                supportingContent = { Text(now.first, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = line(11.dp, 14.dp)) },
                                modifier = Modifier.tvItem(TAG_NAV_NOW),
                            ) { Text(tr(R.string.now_playing), maxLines = 1, style = line(14.dp, 16.dp)) }
                        }
                    }
                }
            },
            // The place starts beside the closed navigation's icons.
            content = { Box(Modifier.fillMaxSize().padding(start = TV_RAIL)) { CompositionLocalProvider(LocalTvNavFocus provides current) { content() } } },
        )
    }
}

/** The side navigation's place being shown, for a screen whose Left should open the navigation (Home's hero). */
val LocalTvNavFocus = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.focus.FocusRequester?> { null }

/** Test tag: the side navigation's "Now playing". */
const val TAG_NAV_NOW = "tv_nav/now"

/** One top-level place. */
@Composable
private fun TvPlace(section: TvSection, nav: TvNav, onSection: (TvSection) -> Unit) {
    Box(Modifier.fillMaxSize().testTag("$TAG_PLACE/${section.name}")) {
        when (section) {
            TvSection.HOME -> TvHomePlace(nav) { onSection(TvSection.SEARCH) }
            TvSection.SEARCH -> TvSearchPlace(nav)
            TvSection.LIBRARY -> TvLibraryPlace(nav)

            TvSection.SETTINGS -> TvSettingsPlace(nav)
        }
    }
}

private val TV_SAFE_V = 27.dp
/** The closed navigation's width (its icons and their margins): where the places start. */
private val TV_RAIL = 80.dp
/** Whether the TV's welcome (sign in with the phone) shows now: once per TV, and only signed out. */
fun tvWelcomeDue(welcomed: Boolean, signedIn: Boolean): Boolean = !welcomed && !signedIn

/** The side navigation's own focus key. */
private const val NAV_SCREEN = "nav"

/** Focus coming into this group goes to [target] (the navigation's current place), whatever was focused in it before. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.focusRestorerTo(target: androidx.compose.ui.focus.FocusRequester): Modifier =
    this.focusProperties { onEnter = { target.requestFocus() } }.focusGroup()
/** Test tags (TV in-app tests): the side navigation, its items ("tv_nav/HOME"), and the shown place ("tv_place/HOME"). */
const val TAG_NAV = "tv_nav"
const val TAG_PLACE = "tv_place"

package com.prism.music.ui.player

import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.shows
import com.prism.music.data.prefs.showsMenuButton
import com.prism.music.data.prefs.drawsCover
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset as offsetConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.prism.music.R
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.player.PlayerInk.inked
import com.prism.music.ui.Routes
import com.prism.music.ui.components.shareTarget
import com.prism.music.ui.components.tickWhileShown
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
private val DISMISS_STRIP = 32.dp
/** How far a swipe on the controls goes before it opens or closes the queue or the lyrics (#200), in pixels. */
private const val PANEL_SWIPE_PX = 90f
private val ART_BOX_TOP_PAD = 8.dp
/** How far the title may drop below the cover toward the scrubber, when the screen has room to spare. */
private val TITLE_DROP_MAX = 24.dp
/** What the like and ⋯ buttons beside the title take up (a centred title is balanced against it). */
private val TITLE_BUTTONS_WIDTH = 98.dp
/** The heart beside the title and the space after it, left out when the buttons row has Like. */
private val LIKE_GLYPH_ROOM = 48.dp
/** The space under each part of the deck when another follows it (as the deck was laid out before it could be arranged). */
private val DECK_GAP_AFTER = mapOf(
    com.prism.music.data.prefs.DeckPart.STRIP to 0.dp, com.prism.music.data.prefs.DeckPart.SCRUBBER to 8.dp,
    com.prism.music.data.prefs.DeckPart.TRANSPORT to 12.dp, com.prism.music.data.prefs.DeckPart.VOLUME to 6.dp,
    com.prism.music.data.prefs.DeckPart.BUTTONS to 10.dp,
)
private const val HERO_FADE = 0.42f
/** #293: "More room for the picture": the last quarter fades, not the last ~two fifths. */
private const val HERO_FADE_ROOMY = 0.24f

/** The deck's parts as they show, the gap after each one but the last, and room added under the last one. */
internal class DeckSpacing(val parts: List<com.prism.music.data.prefs.DeckPart>, val gaps: List<Dp>, val below: Dp)

/**
 * #329: the deck laid out from [shown] (its parts in the listener's order, the ones hidden from the player left out
 * already). A hidden volume bar leaves no hole of its own any more (its blank room sat between the controls and the
 * buttons row, so the gaps looked uneven): its room, kept so the controls and the cover above stay where they were
 * (1.6.5), is shared evenly between the gaps. [small]: Small over the lyrics or the queue, tight as before.
 * [tight] (#293): over a full-bleed picture that wants the room: a hidden volume bar keeps none, and the gaps are halved.
 * #361: the current lyric moved anywhere but over the progress bar sits midway between its neighbours.
 */
internal fun deckSpacing(shown: List<com.prism.music.data.prefs.DeckPart>, volumeShown: Boolean, small: Boolean, tight: Boolean = false): DeckSpacing {
    val volume = com.prism.music.data.prefs.DeckPart.VOLUME
    if (small) return DeckSpacing(shown, List((shown.size - 1).coerceAtLeast(0)) { 2.dp }, 0.dp)
    val hiddenVolume = !volumeShown && volume in shown
    val parts = if (hiddenVolume) shown - volume else shown
    if (tight) return DeckSpacing(parts, stripBalanced(parts, parts.dropLast(1).map { (DECK_GAP_AFTER[it] ?: 8.dp) / 2 }), 0.dp)
    // The room it had: the bar's own, and the gap under it when something followed.
    val spare = if (!hiddenVolume) 0.dp else VOLUME_ROW_HEIGHT + if (shown.last() != volume) DECK_GAP_AFTER.getValue(volume) else 0.dp
    val gaps = parts.dropLast(1).map { DECK_GAP_AFTER[it] ?: 8.dp }
    if (gaps.isEmpty()) return DeckSpacing(parts, gaps, spare)
    return DeckSpacing(parts, stripBalanced(parts, gaps.map { it + spare / gaps.size }), 0.dp)
}

/** Whether the current lyric at [index] of [parts] is in its first place, right over the progress bar, where it hugs the bar. */
internal fun stripOverScrubber(parts: List<com.prism.music.data.prefs.DeckPart>, index: Int): Boolean =
    parts.getOrNull(index + 1) == com.prism.music.data.prefs.DeckPart.SCRUBBER

/**
 * #361: [gaps] with the current lyric's two shared out evenly. Its own gap is none, to hug the progress bar beneath it, so
 * moved above anything else it stayed against that (1.8.1.1's shared-out room moved it further down still). The deck's
 * height stays the same, so nothing else moves.
 */
private fun stripBalanced(parts: List<com.prism.music.data.prefs.DeckPart>, gaps: List<Dp>): List<Dp> {
    val at = parts.indexOf(com.prism.music.data.prefs.DeckPart.STRIP)
    if (at !in 1 until parts.lastIndex || stripOverScrubber(parts, at)) return gaps
    val even = (gaps[at - 1] + gaps[at]) / 2
    return gaps.toMutableList().also { it[at - 1] = even; it[at] = even }
}

/**
 * Whether the full-bleed cover is up: in full-bleed mode, once there's a picture to show, this song's or (#244) the
 * last one's while this one loads, or an animated cover.
 */
internal fun heroTarget(heroMode: Boolean, picture: Boolean, canvas: Boolean): Boolean = heroMode && (picture || canvas)

/**
 * #250: the player draws the band behind the navigation buttons itself on Android 15 and later ([sdk] 35+), where a
 * window's navigation bar color is ignored for apps targeting 35+; below that the window's color is the band.
 */
internal fun drawsNavBand(sdk: Int, on: Boolean): Boolean = on && sdk >= 35

/** The band's color: dark under the player, light over its White background (as the window's color below Android 15). */
internal fun navBandColor(light: Boolean): Color = if (light) Color(0x80F2F2F2) else Color(0x801B1B1B)

/**
 * The band behind three-button navigation (#250, Android 15+): as tall as the buttons' bar, from the tappable-element
 * insets, which are zero with gesture navigation (its handle isn't tappable), so there's no band there.
 */
@Composable
private fun PlayerNavBand(light: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val buttons = WindowInsets.tappableElement.getBottom(density)
    if (buttons <= 0) return
    Box(modifier.fillMaxWidth().height(with(density) { buttons.toDp() }).background(navBandColor(light)).testTag("playerNavBand"))
}

/**
 * The cover's size: a little smaller while paused when asked, unless the listener chose Reduce motion themselves. A
 * Full width cover ([fullWidth]) stays edge to edge (#276: it shrank away from the screen's sides).
 */
internal fun pausedCoverScale(playing: Boolean, shrinkWhenPaused: Boolean, ownReduceMotion: Boolean, fullWidth: Boolean = false): Float =
    if (playing || !shrinkWhenPaused || ownReduceMotion || fullWidth) 1f else 0.86f

/**
 * The player's cover, [width] by [height], in a box [maxWidth] by [maxHeight] (the player's side margins [extra] wide
 * each, which Large and Full width reach into). It's square as big as the box lets it be; a Full width cover ([full])
 * always meets the screen's sides (#276: on most phones the player is too short for a square that wide, so Full kept the
 * margins in the Standard layout, looking just like Large), its top and bottom then cropped a little instead.
 */
internal fun coverBox(maxWidth: Dp, maxHeight: Dp, extra: Dp, full: Boolean, titleRoom: Dp = ART_TITLE_GAP + HEADER_HEIGHT, least: Dp = THUMB_SIZE): Pair<Dp, Dp> {
    val across = maxWidth + extra * 2
    val side = minOf(across, maxHeight - titleRoom).coerceAtLeast(least)
    return (if (full) maxOf(side, across) else side) to side
}

/**
 * [width] by [height] (a square when they're the same); it may be wider than the slot (a Large / Full width cover
 * reaches into the side margins). Taller than the slot, both sides give up the same.
 */
private fun Modifier.measuredBox(width: () -> Dp, height: () -> Dp): Modifier = layout { measurable, constraints ->
    val wanted = height().roundToPx()
    val h = wanted.coerceAtMost(constraints.maxHeight)
    val w = (width().roundToPx() - (wanted - h)).coerceAtLeast(0)
    val p = measurable.measure(Constraints.fixed(w, h))
    layout(minOf(p.width, constraints.maxWidth), p.height) { p.placeRelative(0, 0) }
}

@Composable
fun rememberPosition(fast: Boolean, hold: Boolean = false): Long = rememberPositionState(fast, hold).value

/**
 * The playhead as state, refreshed four times a second ([fast]: about 16). Read it where it's drawn (a draw block,
 * `graphicsLayer`) and only that redraws as the song plays, rather than everything around it recomposing (1.7.4: the
 * mini player, glass and all, was rebuilt four times a second over every screen).
 */
@Composable
fun rememberPositionState(fast: Boolean, hold: Boolean = false): androidx.compose.runtime.State<Long> {
    val pc = LocalContainer.current.player
    val pos = remember { mutableLongStateOf(pc.position) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // [hold]: hidden under a sheet, the player isn't recomposed four times a second for a clock nobody sees; nor while
    // Prism is out of sight (the screen off, another app in front).
    LaunchedEffect(fast, hold, lifecycle) {
        pos.longValue = pc.position
        if (!hold) lifecycle.tickWhileShown(if (fast) 60 else 250) { pos.longValue = pc.position }
    }
    return pos
}


/**
 * Now Playing, modelled on BitChord v1.7 (itself after Apple Music): artwork
 * that shrinks while paused, a hairline scrubber, oversized transport glyphs, a
 * volume capsule, and lyrics / Song·Video / queue along the bottom. Lyrics and
 * the queue live inside the player; the sleeve collapses into a header for them.
 */
@Composable
fun NowPlayingScreen(onCollapse: () -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val view = LocalView.current
    val settings by c.settings.flow.collectAsState()
    val song = pc.currentSong.collectAsState().value ?: return
    val isPlaying by pc.isPlaying.collectAsState()
    val buffering by pc.buffering.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    val videoAvailable by pc.videoAvailable.collectAsState()
    val hasNext by pc.hasNext.collectAsState()
    val hasPrevious by pc.hasPrevious.collectAsState()
    val liked by c.library.likedIds.collectAsState()

    // #280: "Reopen on the last page". Nothing's read or written with it off.
    val pageMemory = remember(settings.behavior.playerRemembersPage) { if (settings.behavior.playerRemembersPage) PlayerPageMemory(view.context) else null }
    val opensOn = remember { pageMemory?.opensOn(song.isEpisode) ?: PlayerPage.COVER }
    // Opened on a page, its controls start as opening that page would start them ("Full screen" hides them).
    val openPanelControls = opensOn == PlayerPage.COVER || settings.view.panelPlayer != com.prism.music.data.prefs.PanelPlayer.HIDDEN
    var lyricsOpen by rememberSaveable { mutableStateOf(opensOn == PlayerPage.LYRICS) }
    var queueOpen by rememberSaveable { mutableStateOf(opensOn == PlayerPage.QUEUE) }
    var lyricsControlsOpen by remember { mutableStateOf(openPanelControls) }
    var queueControlsOpen by remember { mutableStateOf(openPanelControls) }
    var showProviders by remember { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showTiming by remember { mutableStateOf(false) }
    var showSing by remember { mutableStateOf(false) }
    // Opened from the player's own buttons (1.7.4): where the sound goes, a playlist to add to, sharing.
    var showOutputs by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var fullscreenVideo by rememberSaveable { mutableStateOf(false) }
    var videoFill by rememberSaveable { mutableStateOf(true) }

    // A sheet over the player (⋯, lyrics source, sleep timer): what moves underneath it every frame (the animated
    // cover, the word sweep, the title marquee) holds still, since both windows draw on the same thread and dragging
    // the sheet stuttered. The timing sheet isn't counted: the lyrics are what's being lined up there.
    val covered = showActions || showProviders || showSleep || showSing || showOutputs || showPlaylists || showShare || showSpeed
    // Where the lyrics are on show (the lyrics view, immersive mode, the source sheet): with "Lyrics on mobile data"
    // off, that's when they're looked up on mobile data (#95).
    // Settings → Player → Layout (1.7.4); a wide player (tablet, landscape) keeps its own.
    val wideNow = com.prism.music.ui.theme.LocalWindowLayout.current.let { it.twoPanePlayer || it.shortLandscape || it.tabletop }
    val layout = if (wideNow) com.prism.music.data.prefs.PlayerLayout.STANDARD else settings.playerLayout
    val lyricsState = rememberLyricsState(song, opened = lyricsOpen || settings.immersivePlayer || showProviders || layout == com.prism.music.data.prefs.PlayerLayout.LYRICS)
    // With the lyrics in, the best part can start where a line does (or come from the chorus, without a heat curve).
    LaunchedEffect(song.id, lyricsState.lyrics) { if (lyricsState.lyrics?.synced == true) c.bestParts.refine(song) }
    // The playhead is read only where it's shown (the scrubber), so the player isn't recomposed as the song plays.
    val position = rememberPositionState(fast = false, hold = covered)
    val pastStart by remember { derivedStateOf { position.value > 3000 } }
    val volume = rememberPlayerVolume()
    val scope = rememberCoroutineScope()
    // A wide window (tablet, foldable, phone on its side) shows the cover and controls beside lyrics / the queue.
    val window = com.prism.music.ui.theme.LocalWindowLayout.current
    val wide = window.twoPanePlayer || window.shortLandscape || window.tabletop
    // A wide player has its own panes, always showing one of them: only a phone's page is remembered.
    LaunchedEffect(lyricsOpen, queueOpen, pageMemory, wide) { if (!wide) pageMemory?.keep(PlayerPage.of(lyricsOpen, queueOpen)) }

    // The player is always dark, so it owns light system-bar icons, and the navigation bar's scrim (three-button
    // navigation) is a dark one: in light mode a light grey strip sat under the player.
    // Player → Show on the player → "Band behind the navigation buttons" (#250, 1.7.9.1): off, the player shows through.
    val navBand = settings.playerNavBand
    DisposableEffect(navBand) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = ctl?.isAppearanceLightStatusBars
        val previousNav = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false
        ctl?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        val previousNavColor = window?.navigationBarColor
        @Suppress("DEPRECATION")
        window?.navigationBarColor = if (navBand) android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b) else android.graphics.Color.TRANSPARENT
        // Android's own scrim behind three buttons (10+) would put a band back. On 15+ the color above isn't drawn (edge to
        // edge is enforced) and the player draws its band itself ([PlayerNavBand]), so the system's scrim stays off there.
        val previousEnforced = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        if (android.os.Build.VERSION.SDK_INT >= 29 && (!navBand || android.os.Build.VERSION.SDK_INT >= 35)) window?.isNavigationBarContrastEnforced = false
        onDispose {
            if (previous != null) ctl.isAppearanceLightStatusBars = previous
            if (previousNav != null) ctl.isAppearanceLightNavigationBars = previousNav
            @Suppress("DEPRECATION")
            if (previousNavColor != null) window.navigationBarColor = previousNavColor
            if (android.os.Build.VERSION.SDK_INT >= 29 && previousEnforced != null) window?.isNavigationBarContrastEnforced = previousEnforced
        }
    }
    // Lyrics on screen keep it awake (a wide player shows them unless its other pane is picked), and so does a music
    // video while it plays (GitHub #169: the screen went off mid-video).
    val lyricsInView = lyricsOpen || (wide && !queueOpen && !statsOpen)
    val awake = lyricsInView || (videoMode && isPlaying)
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    // Settings → Player → "Lyrics and the queue" (#193, #197): the controls hide after a moment (as before), always stay,
    // or start hidden so the lyrics and the queue get the whole screen (a tap or a swipe up brings them).
    val panelPlayer = settings.view.panelPlayer
    // Small (#230): they stay too, in less room.
    val controlsStay = panelPlayer == com.prism.music.data.prefs.PanelPlayer.ALWAYS || panelPlayer == com.prism.music.data.prefs.PanelPlayer.SMALL
    val controlsAtOpen = panelPlayer != com.prism.music.data.prefs.PanelPlayer.HIDDEN
    val openLyrics = { lyricsControlsOpen = controlsAtOpen; lyricsOpen = true; queueOpen = false; statsOpen = false }
    val closeLyrics = { lyricsControlsOpen = false; lyricsOpen = false }
    val toggleQueue = {
        val opening = !queueOpen
        queueOpen = opening
        if (opening) { closeLyrics(); statsOpen = false; queueControlsOpen = controlsAtOpen }
    }
    val toggleStats = {
        val opening = !statsOpen
        statsOpen = opening
        if (opening) { closeLyrics(); queueOpen = false }
    }
    // Sing's controls up over the lyrics: the deck stays (it holds them), and comes back when they're opened.
    val singing by c.sing.on.collectAsState()
    // Sing's button held as a slider (1.7.8, V): the deck stays up while it's in use.
    val singControls = singing && SingUi.holding
    LaunchedEffect(singControls) { if (singControls) lyricsControlsOpen = true }
    LaunchedEffect(lyricsOpen, lyricsControlsOpen, volume.dragging, singControls, controlsStay) {
        if (lyricsOpen && lyricsControlsOpen && !volume.dragging && !singControls && !controlsStay) { delay(5000); lyricsControlsOpen = false }
    }
    // Cover layout: a tap on the cover brings the controls up; they go again after a few seconds of music.
    var coverDeck by remember { mutableStateOf(false) }
    // A playing music video does the same when "Controls hide over videos" is on (#166): they show as the video starts,
    // go after a few seconds, and a tap on the video brings them; they stay away from song to song.
    val videoHidesDeck = settings.view.videoControlsHide && videoMode
    val deckOnTap = layout == com.prism.music.data.prefs.PlayerLayout.COVER || videoHidesDeck
    LaunchedEffect(videoHidesDeck) { if (videoHidesDeck) coverDeck = true }
    LaunchedEffect(coverDeck, isPlaying, volume.dragging, deckOnTap) {
        if (coverDeck && isPlaying && !volume.dragging && deckOnTap) { delay(5000); coverDeck = false }
    }
    // #293: "Video buttons wait for a tap": shown by a tap on the video, gone again after a moment.
    var videoChips by remember { mutableStateOf(false) }
    LaunchedEffect(videoChips) { if (videoChips) { delay(5000); videoChips = false } }
    // Lyrics layout: each song opens on its lyrics once they're in (a song without any stays on the cover).
    val hasLyrics = lyricsState.lyrics != null
    LaunchedEffect(song.id, hasLyrics, layout) {
        if (layout == com.prism.music.data.prefs.PlayerLayout.LYRICS && hasLyrics && !queueOpen && !statsOpen && !lyricsOpen) openLyrics()
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Sideways, a video fills the screen; its exit (or Back) keeps it in the player instead, until the phone turns or the
    // video's switched off (#245: on a Fold held sideways it went straight back to full screen, no way out but turning it).
    var sidewaysInPlayer by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(landscape, videoMode) { if (!landscape || !videoMode) sidewaysInPlayer = false }
    val sidewaysFull = landscape && !sidewaysInPlayer
    fun leaveFullscreenVideo() {
        fullscreenVideo = false
        if (landscape) sidewaysInPlayer = true
    }
    // How far the player has been pulled down (px). Closing slides it the rest of the way, from wherever it is, the same
    // way for Back and for a swipe (#26: a swipe used to hand over to a different exit animation part-way down).
    val dismiss = remember { Animatable(0f) }
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var closing by remember { mutableStateOf(false) }
    fun slideClosed() {
        if (closing) return
        closing = true
        // #223: the cover flies back from where it is, and the player fades around it, instead of sliding away.
        if (CoverFlight.returnsWith(settings.view.coverFlight, settings.view.coverFlightBack, settings.reduceMotion) && CoverFlight.canReturn()) {
            onCollapse()
            // Opened again while it fades, this is the same player still: it has to be able to close again.
            scope.launch { delay(CoverFlight.FLIGHT_MS + 100L); closing = false }
            return
        }
        scope.launch {
            dismiss.animateTo(screenPx, tween(if (settings.reduceMotion) 240 else 340, easing = FastOutSlowInEasing))
            onCollapse()
        }
    }
    // Pulled down (or sliding closed), the page under the player shows again: its backgrounds move again.
    val cover = com.prism.music.ui.components.LocalPlayerCover.current
    LaunchedEffect(cover) { snapshotFlow { dismiss.value > 0f || closing }.collect { cover.pulled = it } }
    DisposableEffect(cover) { onDispose { cover.pulled = false } }
    // Opened again before it had gone, this is the same player still, pulled down or slid away: it comes back to its place,
    // and can close again. (While it leaves it stays where it is: the cover flies back from there.)
    LaunchedEffect(cover) {
        snapshotFlow { cover.leaving }.collect { leaving ->
            if (!leaving && (closing || dismiss.value != 0f)) {
                closing = false
                dismiss.animateTo(0f, spring())
            }
        }
    }
    BackHandler {
        when {
            fullscreenVideo || (videoMode && sidewaysFull) -> leaveFullscreenVideo()
            // A wide player always shows its lyrics pane: "open" (left from portrait) isn't something Back undoes there.
            // #280: with "Reopen on the last page", Back leaves the player on that page: stepping back to the cover first
            // would make the cover the last page every time.
            lyricsOpen && !wide -> if (pageMemory != null) slideClosed() else closeLyrics()
            queueOpen -> if (pageMemory != null && !wide) slideClosed() else queueOpen = false
            statsOpen -> statsOpen = false
            else -> slideClosed()
        }
    }
    // Picture-in-picture: a music video playing here floats over other apps when the listener
    // leaves Prism (or taps the PiP button); the window then shows just the video.
    val inPip = LocalPip.current
    val activity = view.context as? com.prism.music.MainActivity
    DisposableEffect(videoMode, isPlaying, activity) {
        val size = pc.player?.videoSize
        activity?.setPipEligible(
            videoMode && isPlaying,
            ((size?.width ?: 0) * (size?.pixelWidthHeightRatio ?: 1f)).toInt(), size?.height ?: 0,
        )
        onDispose { activity?.setPipEligible(false) }
    }
    if (videoMode && (fullscreenVideo || sidewaysFull || inPip)) {
        FullscreenVideo(song, forceLandscape = fullscreenVideo && !inPip, pip = inPip, onExit = { leaveFullscreenVideo() })
        return
    }

    // ---------------------------------------------------------------- Motion
    val collapse = animateFloatAsState(if (lyricsOpen || queueOpen || statsOpen) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "collapse")
    val p: () -> Float = { collapse.value }
    val collapsePastHalf = collapse.value >= 0.5f
    // Settings → Player → What's on the player (1.8.0): a hidden part leaves the player; the cover, title and buttons come
    // back in the header over the lyrics and the queue, where they say which song it is.
    val inHeader = { part: PlayerPart -> settings.shows(part) || collapsePastHalf }
    val panelsSettled = collapse.value >= 1f
    val panelFade by animateFloatAsState(if (panelsSettled) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "panelFade")
    // The listener's own Reduce motion, not Performance mode's (Reddit, 1.7.9.3: Performance mode turns Reduce motion on,
    // which took the shrink away too; it's one scale of one layer, nothing to save).
    val ownReduceMotion = c.settings.stored.collectAsState().value.reduceMotion
    val artScale by animateFloatAsState(
        pausedCoverScale(isPlaying, settings.artShrinkOnPause, ownReduceMotion, fullWidth = settings.coverSize == com.prism.music.data.prefs.CoverSize.FULL),
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale",
    )
    val swipe = remember { Animatable(0f) }
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    var seekFlash by remember { mutableStateOf<SeekFlash?>(null) }
    LaunchedEffect(seekFlash?.at) { if (seekFlash != null) { delay(750); seekFlash = null } }

    // ---------------------------------------------------------------- Canvas
    val metered = remember { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }
    val savedCanvases by c.canvas.saved.collectAsState()
    val canvasSaved = savedCanvases.has(song.id, com.prism.music.data.canvas.CanvasRepository.albumKey(song))
    val songCanvas by produceState<CanvasArtwork?>(c.canvas.cached(song), song.id, song.album?.title, settings.canvasEnabled) {
        // Saved clips play from the phone, so mobile data doesn't matter for them.
        if (!settings.canvasEnabled || (metered && (!settings.canvasOnCellular || settings.dataSaver) && !c.canvas.isSaved(song))) { value = null; return@produceState }
        val known = c.canvas.cached(song)
        value = known
        if (known == null) delay(500)
        value = c.canvas.canvasFor(song)
    }
    // Animated artwork can be switched off per song from the song options.
    val canvasHidden by c.canvas.hidden.collectAsState()
    val canvas = songCanvas.takeIf { !videoMode && song.id !in canvasHidden }
    var canvasCover by remember(canvas) { mutableFloatStateOf(0f) }

    // ---------------------------------------------------------------- Artwork
    // The cover picked for the song or its album (#305), else its own.
    val songArt = c.covers.art(song)
    val artUrl = hiRes(songArt, 1200)
    // Decoded at full size whatever size the cover is first laid out at (#86): opened mid-animation or with lyrics
    // open it starts out small, and the picture decoded for that size was then stretched over the whole cover.
    val artRequest = remember(artUrl, settings.fullBleedArtwork) { playerCoverRequest(context, artUrl, settings.fullBleedArtwork) }
    var artLoaded by remember(artUrl) { mutableStateOf(false) }
    // Vinyl: the cover is a record (its own shape, no edge-to-edge banner).
    val vinyl = layout == com.prism.music.data.prefs.PlayerLayout.VINYL && !videoMode
    // Cassette (1.7.4): the cover on a tape, its reels turning.
    val cassette = layout == com.prism.music.data.prefs.PlayerLayout.CASSETTE && !videoMode
    val heroMode = settings.fullBleedArtwork && !videoMode && !wide && !vinyl && !cassette
    // The animated cover plays behind the whole player instead of in the cover (not with full-bleed artwork, which
    // already shows it edge to edge).
    val canvasBehind = settings.canvasFillsBackground && !heroMode && !videoMode && !wide
    // Full-bleed video (#120): the video runs edge to edge from the top of the screen and fades into the background,
    // in the full-bleed cover's place; the box it would sit in keeps only its buttons.
    val videoBehind = settings.videoFullBleed && videoMode && !wide
    // #293: "More room for the picture": over a full-bleed picture the controls close up, the picture takes what they
    // give back, and it fades later.
    val bleedRoomy = settings.view.bleedRoomy && (heroMode || videoBehind)
    val heroFade = if (bleedRoomy) HERO_FADE_ROOMY else HERO_FADE
    var heroHeight by remember { mutableStateOf(0.dp) }
    // #244 (1.7.9.3): once the full-bleed cover has shown it stays up while the next song's picture loads, over the last
    // one (it used to drop to the square cover for every new song, then grow back, a jump on each skip).
    var heroPicture by remember { mutableStateOf<coil3.request.ImageRequest?>(null) }
    LaunchedEffect(heroMode) { if (!heroMode) heroPicture = null }
    val heroT = animateFloatAsState(if (heroTarget(heroMode, artLoaded || heroPicture != null, canvasCover > 0f)) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "hero")
    val heroVisible: () -> Float = { heroT.value * (1f - p()) }
    // #232, #338: only one of the two is worked out, for the background that's up.
    val onGradient = settings.playerBackground == com.prism.music.data.prefs.PlayerBackground.GRADIENT
    val mesh = rememberArtworkMesh(songArt.takeUnless { onGradient })
    val coverGradient = rememberCoverGradient(songArt.takeIf { onGradient })
    val blurImage = rememberFullArtworkBlur(songArt, settings.playerBlur)
    val fullBlur by animateFloatAsState(if (lyricsOpen || queueOpen || statsOpen) 1f else 0f, tween(360, easing = FastOutSlowInEasing), label = "fullBlur")
    // #320: over the White background the type and controls are dark ([PlayerInk]); over the lyrics and the queue (their
    // dark blurred cover), a canvas or a video behind, a wide player or immersive mode they're white as ever.
    val inkLight = settings.playerBackground == com.prism.music.data.prefs.PlayerBackground.WHITE && !wide && !canvasBehind && !videoBehind &&
        fullBlur < 0.5f && !(settings.immersivePlayer && (!videoMode || settings.view.immersiveVideo))
    PlayerInk.light = inkLight
    DisposableEffect(Unit) { onDispose { PlayerInk.light = false } }
    // The status bar's and the navigation bar's icons dark over it too.
    DisposableEffect(inkLight, settings.playerNavBand) {
        if (!inkLight) return@DisposableEffect onDispose {}
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.isAppearanceLightStatusBars = true
        ctl?.isAppearanceLightNavigationBars = true
        @Suppress("DEPRECATION")
        val previousNavColor = window?.navigationBarColor
        @Suppress("DEPRECATION")
        if (settings.playerNavBand) window?.navigationBarColor = android.graphics.Color.argb(0x80, 0xf2, 0xf2, 0xf4)
        onDispose {
            ctl?.isAppearanceLightStatusBars = false
            ctl?.isAppearanceLightNavigationBars = false
            @Suppress("DEPRECATION")
            if (previousNavColor != null) window.navigationBarColor = previousNavColor
        }
    }
    val statusTop = statusBarRoom().asPaddingValues().calculateTopPadding()
    // Collapsed into the header thumbnail, the sleeve keeps gentle corners whatever the setting.
    // A Full width cover meets the screen's edges, so it has square corners until it shrinks into the header.
    val artCorners = if (settings.coverSize == com.prism.music.data.prefs.CoverSize.FULL) 0.dp else settings.playerArtCorners.dp
    // Another shape picked for the player's cover (1.7.7) keeps it all the way into the header.
    val artShape = when {
        vinyl -> CircleShape
        settings.shapes.player != com.prism.music.data.prefs.CoverShape.ROUNDED && !cassette ->
            com.prism.music.ui.theme.CoverShapes.of(settings.shapes.player, artCorners)
        else -> RoundedCornerShape(lerp(artCorners, 8.dp, collapse.value))
    }
    val roomyCover = settings.coverSize != com.prism.music.data.prefs.CoverSize.STANDARD

    // ---------------------------------------------------------------- Visualizer (1.7.4, off by default)
    // Runs only while it's picked, the song plays and the player is in view (not under a sheet, not a video, not with
    // Reduce motion): otherwise nothing listens and nothing redraws.
    val vizStyle = settings.visualizer
    val viz = rememberVisualizer(
        vizStyle != com.prism.music.data.prefs.VisualizerStyle.OFF && !settings.reduceMotion && !covered && !videoMode && isPlaying,
    )
    val vizBrush: Brush = if (viz == null) androidx.compose.ui.graphics.SolidColor(Color.White) else
        scrubberFill(settings.visualizerColor, songArt)
            ?: Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray())
    val vizColor: Color = (vizBrush as? androidx.compose.ui.graphics.SolidColor)?.value
        ?: com.prism.music.ui.theme.PrismTokens.spectrumColors(true).let { it[it.size / 2].second }

    // Only ever the song's album (tapping it opens the album); nothing for songs without one.
    val originAlbum = song.album?.takeIf { it.id != null && it.title.isNotBlank() }

    // The bottom row's buttons that open something over the player (Settings → Player → Buttons).
    val onButton: (com.prism.music.data.prefs.PlayerButton) -> Unit = { b ->
        when (b) {
            com.prism.music.data.prefs.PlayerButton.SLEEP -> showSleep = true
            com.prism.music.data.prefs.PlayerButton.OUTPUT -> showOutputs = true
            com.prism.music.data.prefs.PlayerButton.ADD_TO_PLAYLIST -> showPlaylists = true
            com.prism.music.data.prefs.PlayerButton.SHARE -> showShare = true
            com.prism.music.data.prefs.PlayerButton.SPEED -> showSpeed = true
            // Sing on (with the lyrics opening) or off; the download sheet the first time.
            com.prism.music.data.prefs.PlayerButton.SING -> {
                if (c.sing.engineReady() && !c.sing.on.value) openLyrics()
                toggleSing(c) { showSing = true }
            }
            com.prism.music.data.prefs.PlayerButton.EQUALIZER -> { onCollapse(); nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.SOUND.key)) }
            else -> {}
        }
    }

    // The sheets that open over the player (in immersive mode too).
    val playerSheets = @Composable {
        if (showProviders) LyricsProviderSheet(lyricsState) { showProviders = false }
        if (showActions) PlayerOptionsSheet(
            song,
            hasCanvas = songCanvas != null,
            statsShown = statsOpen,
            hasNext = hasNext,
            // What the player's hidden parts did (1.8.0): open the lyrics or the queue, the music video, the buttons.
            onOpenLyrics = { openLyrics() },
            onOpenQueue = { if (!queueOpen) toggleQueue() },
            videoMode = videoMode,
            videoAvailable = videoAvailable,
            onVideoMode = { pc.setVideoMode(it) },
            onButton = { onButton(it) },
            onLyricsSource = { showProviders = true },
            onLyricsTiming = { showTiming = true },
            onRedownloadLyrics = { lyricsState.redownload() },
            onToggleStats = { toggleStats() },
            onSleepTimer = { showSleep = true },
            onSpeedPitch = { showSpeed = true },
            onCollapse = onCollapse,
            romanizable = hasForeignScript(lyricsState),
            // Sing from the menu: the lyrics open with Sing on and its controls up (the download sheet the first time).
            // ⋯ → Sing: its sheet (Sing on or off, the voice, the key; the one-time download first).
            onSing = { showSing = true },
        ) { showActions = false }
        if (showSleep) SleepTimerDialog { showSleep = false }
        if (showTiming) LyricsTimingSheet { showTiming = false }
        if (showSing) SingSheet(song) { showSing = false }
        // Held Best part button, or ⋯ → Your best part.
        if (BestPartEditor.open) BestPartSheet { BestPartEditor.open = false }
        if (showOutputs) {
            val profile by c.account.profile.collectAsState()
            val name = (profile?.name ?: settings.accountName).trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { tr(R.string.s_phone, it) } ?: tr(R.string.this_phone)
            OutputPickerSheet(name) { showOutputs = false }
        }
        if (showPlaylists) com.prism.music.ui.components.PlaylistPicker(song) { showPlaylists = false }
        if (showShare) com.prism.music.ui.components.ShareSheet(song.shareTarget()) { showShare = false }
        if (showSpeed) SpeedPitchSheet(song) { showSpeed = false }
    }

    // ------------------------------------------------ Immersive mode (⋯ menu): the cover fills the screen, lyrics over it
    // A music video playing leaves it for the normal player, unless immersive's "Show the video" is on (#170).
    if (settings.immersivePlayer && (!videoMode || settings.view.immersiveVideo)) {
        // Back leaves immersive mode for the normal player, as it leaves a full-screen video; Back again closes it.
        BackHandler { c.settings.setImmersivePlayer(false) }
        ImmersivePlayer(
            song, canvas, canvasSaved, isPlaying, lyricsState, covered, video = videoMode,
            onExit = { c.settings.setImmersivePlayer(false) },
            onLyricsTiming = { showTiming = true },
            onLyricsSource = { showProviders = true },
            onSongOptions = { showActions = true },
            onCollapse = onCollapse,
        )
        playerSheets()
        return
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalVisualizer provides viz) {
    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dismiss.value.roundToInt()) }
            .graphicsLayer {
                val f = (dismiss.value / 1800f).coerceIn(0f, 1f)
                scaleX = 1f - f * 0.06f; scaleY = 1f - f * 0.06f
                shape = RoundedCornerShape((f * 140).dp); clip = f > 0f
            }
            .background(PlayerFallbackBackdrop),
    ) {
        // ------------------------------------------------ Backdrop
        when (settings.playerBackground) {
            com.prism.music.data.prefs.PlayerBackground.MESH -> {
                if (fullBlur < 1f) ArtworkMeshBackdrop(
                    mesh, Modifier.graphicsLayer { alpha = 1f - fullBlur }, seam = if (heroMode) heroHeight else 0.dp,
                    // Held still under a sheet, like everything else that moves here.
                    speed = if (covered) 0f else settings.meshSpeed, intensity = settings.meshIntensity, blur = settings.playerBlur,
                )
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            // "Show background" lifts the darkening: about as before at its default, down to a light veil at full.
            com.prism.music.data.prefs.PlayerBackground.BLUR -> {
                FullArtworkBlurBackdrop(blurImage, scrim = 1.3f - 0.95f * settings.playerBackdropShow.coerceAtLeast(0f))
                // #309: below 0 the slider darkens on, to black at its start.
                backdropBlackout(settings.playerBackdropShow).takeIf { it > 0f }?.let { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = it))) }
            }
            com.prism.music.data.prefs.PlayerBackground.APPEARANCE -> {
                AppearanceBackdrop(settings.playerBackdropShow.coerceAtLeast(0f))
                backdropBlackout(settings.playerBackdropShow).takeIf { it > 0f }?.let { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = it))) }
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            com.prism.music.data.prefs.PlayerBackground.THEME -> ThemeBackdrop()
            com.prism.music.data.prefs.PlayerBackground.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
            // #320: white, the type dark ([PlayerInk]); the lyrics and the queue fade to the dark blurred cover as the
            // mesh does. A wide player keeps the mesh (its two panes are drawn for a dark ground).
            com.prism.music.data.prefs.PlayerBackground.WHITE -> if (wide) ArtworkMeshBackdrop(mesh, speed = 0f, intensity = settings.meshIntensity, blur = settings.playerBlur) else {
                Box(Modifier.fillMaxSize().background(PlayerWhiteBackdrop))
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            // #232 ("not blurry at all"): the lyrics and the queue stay on the gradient, a little darker under their lines.
            com.prism.music.data.prefs.PlayerBackground.GRADIENT -> {
                CoverGradientBackdrop(coverGradient, settings.view.gradientDepth)
                if (fullBlur > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * fullBlur)))
            }
        }
        // Settings → Lyrics → Background (1.7.5): while the lyrics are open, their own background over the player's.
        val lyricsGround = settings.lyricsPageBackground()
        val lyricsGroundT by animateFloatAsState(
            if (lyricsOpen && lyricsGround != com.prism.music.data.prefs.LyricsBackground.PLAYER) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "lyricsGround",
        )
        if (lyricsGroundT > 0f) LyricsBackdrop(
            lyricsGround, settings, songArt, blurImage, canvas, canvasSaved, isPlaying,
            Modifier.fillMaxSize().graphicsLayer { alpha = lyricsGroundT }, hold = covered,
        )
        // The visualizer's Glow behind everything, and its Bars along the bottom, under the controls.
        if (viz != null) when (vizStyle) {
            com.prism.music.data.prefs.VisualizerStyle.GLOW -> VisualizerGlow(viz, vizColor, Modifier.fillMaxSize())
            com.prism.music.data.prefs.VisualizerStyle.BARS -> VisualizerBars(
                viz, vizBrush,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.2f).padding(horizontal = 12.dp).navigationBarsPadding().inked(),
            )
            else -> {}
        }
        // Settings → Player → Background → "Animated cover fills the player" (1.7.4): the clip plays behind everything,
        // darkened under the type, and the cover itself shows the still picture (one clip playing, not two).
        if (canvasBehind) canvas?.takeIf { !collapsePastHalf }?.let { clip ->
            CanvasPlayer(
                clip, canvasSaved, isPlaying, Modifier.fillMaxSize(), hold = covered,
                presentationAlpha = { (1f - 2f * p()).coerceIn(0f, 1f) },
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.5f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.75f))))
        }
        if (videoBehind && heroHeight > 0.dp && !collapsePastHalf) {
            MusicVideoSurface(
                Modifier.fillMaxWidth().height(heroHeight), fill = true, bottomFade = heroFade,
                alpha = { (1f - 2f * p()).coerceIn(0f, 1f) },
            )
        }

        // ------------------------------------------------ Full-bleed banner (+ canvas)
        if (heroMode && heroHeight > 0.dp && !(collapsePastHalf && heroVisible() < 0.001f)) {
            // "Whole cover" (#175): the square cover isn't cropped to the banner's shape; it sits whole on a blurred,
            // cropped copy of itself that fills the rest.
            val fit = settings.view.bleedFit == com.prism.music.data.prefs.BleedFit.FIT
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .graphicsLayer {
                        alpha = heroVisible() * (1f - canvasCover)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height * (1f - heroFade), endY = size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // Prism's own software frost of the cover, so it's soft below Android 12 too.
                val frost = if (fit) com.prism.music.ui.components.rememberFrost(artUrl) else null
                if (frost != null) androidx.compose.foundation.Image(
                    frost.image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.8f },
                )
                // The last song's picture under the new one until it's in (#244).
                heroPicture?.takeIf { it != artRequest }?.let { last ->
                    AsyncImage(last, null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                AsyncImage(
                    artRequest, null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                    onState = { if (it is AsyncImagePainter.State.Success) { artLoaded = true; heroPicture = artRequest } },
                    modifier = Modifier.matchParentSize(),
                )
            }
            canvas?.takeIf { !collapsePastHalf }?.let { clip ->
                CanvasPlayer(
                    clip, canvasSaved, isPlaying,
                    Modifier.fillMaxWidth().height(heroHeight),
                    bottomFade = heroFade,
                    presentationAlpha = { heroT.value * (1f - 2f * p()).coerceIn(0f, 1f) },
                    onCoverChanged = { canvasCover = it },
                )
            }
        }
        // Status-bar scrim (none on the White background, whose status bar has dark icons)
        if (!inkLight) Box(Modifier.fillMaxWidth().height(statusTop + DISMISS_STRIP).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))

        val dismissGesture = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { if (dismiss.value > 220f) slideClosed() else scope.launch { dismiss.animateTo(0f, spring()) } },
                onDragCancel = { if (!closing) scope.launch { dismiss.animateTo(0f) } },
            ) { change, amount ->
                change.consume()
                if (!closing) scope.launch { dismiss.snapTo((dismiss.value + amount).coerceAtLeast(0f)) }
            }
        }
        // Settings → Player → Gestures (1.7.4): what a swipe, double tap or long press on the cover does. Read from the
        // settings as each gesture happens (the gesture handlers live as long as the player).
        fun runGesture(g: com.prism.music.data.prefs.PlayerGesture) {
            when (g) {
                com.prism.music.data.prefs.PlayerGesture.NEXT -> pc.next()
                com.prism.music.data.prefs.PlayerGesture.PREVIOUS -> pc.previous()
                com.prism.music.data.prefs.PlayerGesture.SEEK_BACK, com.prism.music.data.prefs.PlayerGesture.SEEK_AHEAD -> {
                    val ahead = g == com.prism.music.data.prefs.PlayerGesture.SEEK_AHEAD
                    pc.seekBy(if (ahead) 10_000L else -10_000L)
                    seekFlash = SeekFlash(ahead, if (seekFlash?.ahead == ahead) seekFlash!!.steps + 1 else 1, System.nanoTime(), 10)
                }
                com.prism.music.data.prefs.PlayerGesture.PLAY_PAUSE -> pc.togglePlay()
                com.prism.music.data.prefs.PlayerGesture.LIKE -> c.library.toggleLike(song)
                com.prism.music.data.prefs.PlayerGesture.QUEUE -> toggleQueue()
                com.prism.music.data.prefs.PlayerGesture.LYRICS -> if (lyricsOpen) closeLyrics() else openLyrics()
                com.prism.music.data.prefs.PlayerGesture.SLEEP -> showSleep = true
                com.prism.music.data.prefs.PlayerGesture.VIDEO -> if (videoAvailable || videoMode) pc.setVideoMode(!videoMode)
                com.prism.music.data.prefs.PlayerGesture.SING -> onButton(com.prism.music.data.prefs.PlayerButton.SING)
                com.prism.music.data.prefs.PlayerGesture.MENU -> showActions = true
                com.prism.music.data.prefs.PlayerGesture.STOP -> { pc.stop(); slideClosed() }
                // OPEN is the mini player's; the player is open already.
                com.prism.music.data.prefs.PlayerGesture.SEEK_SIDES, com.prism.music.data.prefs.PlayerGesture.OPEN,
                com.prism.music.data.prefs.PlayerGesture.NONE -> {}
            }
        }
        // Drag on the sleeve: down closes the player, up does what's set (the queue comes in, by default).
        fun sleeveDrag(lyrics: Boolean, queue: Boolean, stats: Boolean) = Modifier.pointerInput(lyrics, queue, stats) {
            if (lyrics || stats) return@pointerInput
            var total = 0f
            // With the queue open, pulling the header down closes the queue (#149), as swiping up brought it.
            if (queue) {
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 90f) { haptics.gestureEnd(); toggleQueue() } },
                ) { change, amount -> change.consume(); total += amount }
                return@pointerInput
            }
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (dismiss.value > 220f) slideClosed()
                    else scope.launch {
                        val up = c.settings.current.coverSwipeUp
                        if (total < -90f && up != com.prism.music.data.prefs.PlayerGesture.NONE) { dismiss.snapTo(0f); haptics.gestureEnd(); runGesture(up) }
                        else dismiss.animateTo(0f, spring())
                    }
                },
                // An interrupted drag (a system gesture, a sheet opening) used to leave the player part-way down.
                onDragCancel = { if (!closing) scope.launch { dismiss.animateTo(0f, spring()) } },
            ) { change, amount ->
                change.consume()
                total += amount
                // Back above where it started: follow the finger back to the top rather than stopping short of it.
                if (!closing) scope.launch { dismiss.snapTo(total.coerceAtLeast(0f)) }
            }
        }
        val sleeveGesture = sleeveDrag(lyricsOpen, queueOpen, statsOpen)
        // Double-tap the cover: by default its left or right half seeks back or ahead (#79, as on YouTube Music); long
        // press: nothing unless one's set. A double tap set to nothing leaves single taps instant.
        val seekStep = settings.doubleTapSeekSeconds
        val doubleTap = settings.coverDoubleTap.takeUnless { it == com.prism.music.data.prefs.PlayerGesture.SEEK_SIDES && seekStep <= 0 }
            ?: com.prism.music.data.prefs.PlayerGesture.NONE
        val longPress = settings.coverLongPress
        val tapForDeck = deckOnTap
        fun tapsAndHolds(panelOpen: Boolean, tapShowsDeck: Boolean) = Modifier.pointerInput(panelOpen, seekStep, doubleTap, longPress, tapShowsDeck) {
            if (panelOpen) return@pointerInput
            if (doubleTap == com.prism.music.data.prefs.PlayerGesture.NONE && longPress == com.prism.music.data.prefs.PlayerGesture.NONE && !tapShowsDeck) return@pointerInput
            detectTapGestures(
                onTap = if (!tapShowsDeck) null else { _ -> coverDeck = !coverDeck },
                onDoubleTap = if (doubleTap == com.prism.music.data.prefs.PlayerGesture.NONE) null else { at ->
                    haptics.gestureEnd()
                    if (doubleTap == com.prism.music.data.prefs.PlayerGesture.SEEK_SIDES) {
                        val ahead = at.x > size.width / 2
                        pc.seekBy(if (ahead) seekStep * 1000L else -seekStep * 1000L)
                        // Taps in a row add up on the label, as YouTube's does.
                        seekFlash = SeekFlash(ahead, if (seekFlash?.ahead == ahead) seekFlash!!.steps + 1 else 1, System.nanoTime(), seekStep)
                    } else runGesture(doubleTap)
                },
                onLongPress = if (longPress == com.prism.music.data.prefs.PlayerGesture.NONE) null else { _ -> haptics.click(); runGesture(longPress) },
            )
        }
        val doubleTapSeek = tapsAndHolds(lyricsOpen || queueOpen || statsOpen, tapForDeck)
        fun sideSwipe(panelOpen: Boolean) = Modifier.pointerInput(panelOpen) {
            if (panelOpen) return@pointerInput
            var total = 0f
            val threshold = 72.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                onDragEnd = {
                    val now = c.settings.current
                    val g = when {
                        total <= -threshold -> now.coverSwipeLeft
                        total >= threshold -> now.coverSwipeRight
                        else -> com.prism.music.data.prefs.PlayerGesture.NONE
                    }
                    if (g != com.prism.music.data.prefs.PlayerGesture.NONE) { haptics.gestureEnd(); runGesture(g) }
                    scope.launch { swipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                },
            ) { _, d ->
                val now = c.settings.current
                // #222: toward a swipe set to Nothing the cover stays still, and gives no tick.
                val off = (if (total + d < 0f) now.coverSwipeLeft else now.coverSwipeRight) == com.prism.music.data.prefs.PlayerGesture.NONE
                if (!off && (kotlin.math.abs(total) < threshold) != (kotlin.math.abs(total + d) < threshold)) haptics.threshold()
                total += d
                scope.launch { swipe.snapTo(if (off) 0f else total * 0.35f) }
            }
        }
        val skipGesture = sideSwipe(lyricsOpen || queueOpen || statsOpen)

        if (wide) WidePlayerContent(
            song, isPlaying, buffering, position, hasNext, hasPrevious, videoMode, videoAvailable, lyricsState, covered,
            canvas, canvasSaved, artRequest, volume,
            pane = when { queueOpen -> PlayerPane.QUEUE; statsOpen -> PlayerPane.STATS; else -> PlayerPane.LYRICS },
            onPane = { p ->
                // The lyrics are the wide player's resting pane; the flags only say when another one is picked.
                lyricsOpen = false
                queueOpen = p == PlayerPane.QUEUE
                statsOpen = p == PlayerPane.STATS
            },
            dismissGesture = dismissGesture,
            onMore = { showActions = true },
            onLyricsSource = { showProviders = true },
            onCollapse = onCollapse,
            onSing = { showSing = true },
            onSingNeedsDownload = { showSing = true },
            onButton = onButton,
        ) else
        Column(Modifier.fillMaxSize().windowInsetsPadding(statusBarRoom()).navigationBarsPadding().then(skipGesture), horizontalAlignment = Alignment.CenterHorizontally) {
            // ------------------------------------------------ Handle + caption
            Box(Modifier.fillMaxWidth().height(DISMISS_STRIP).then(dismissGesture).inked()) {
                // Settings → Player → Show on the player → Handle at the top (Reddit, 1.7.9.1): off, the strip still
                // takes the swipe down.
                if (settings.playerHandle) Box(
                    Modifier.align(Alignment.TopCenter).testTag("playerHandle")
                        .offset { IntOffset(0, lerp(6.dp, (DISMISS_STRIP - 5.dp) / 2, p()).roundToPx()) }
                        .size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.6f)),
                )
                if (collapse.value < 0.999f && settings.showPlayingFrom && originAlbum != null) PlaybackOriginCaption(
                    // The song's own album, which needn't be what's playing (a playlist, a radio): not "Playing from".
                    tr(R.string.from_the_album_2, originAlbum.title),
                    Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - p() },
                ) {
                    onCollapse(); nav.go(Routes.album(originAlbum.id!!))
                }
            }

            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = PLAYER_GUTTER), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth()
                        // #276: a swipe down anywhere round the cover closes the player, not only on the cover and the strip
                        // at the top (the Cover and Minimal layouts leave room above the cover that took nothing). What's
                        // on it (the cover, the title's buttons) takes its own drags first.
                        .then(if (lyricsOpen || queueOpen || statsOpen) Modifier else dismissGesture)
                        .testTag("playerArtBox")
                        .padding(top = ART_BOX_TOP_PAD, bottom = if (roomyCover) 12.dp else 18.dp),
                ) {
                    // Settings → Player → Cover size: Large and Full width reach into the side margins (still square).
                    // The Cover layout's cover is at least Large (its controls are out of the way).
                    val extra = if (layout == com.prism.music.data.prefs.PlayerLayout.COVER) maxOf(settings.coverSize.extra, com.prism.music.data.prefs.CoverSize.LARGE.extra) else settings.coverSize.extra
                    // Full width meets the screen's sides whatever the player's height (#276); a record, a tape and a
                    // shaped cover stay whole.
                    val fullWidth = settings.coverSize == com.prism.music.data.prefs.CoverSize.FULL && !vinyl && !cassette && settings.shapes.player == com.prism.music.data.prefs.CoverShape.ROUNDED
                    val (artWidth, fullArt) = coverBox(maxWidth, maxHeight, extra.dp, fullWidth)
                    // Room the cover can't use (a tall screen) is shared above and below it, as before 1.6.4 (all of it
                    // above pushed the whole player down). The title drops into part of the room below, so no blank
                    // band opens up between the artist and the scrubber (issue #9).
                    val slack = (maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT).coerceAtLeast(0.dp)
                    val groupTop = slack / 2
                    // #293: the title sits right above the controls, and the picture runs down to it.
                    val titleDrop = if (bleedRoomy) slack - groupTop else minOf(slack / 2, TITLE_DROP_MAX)
                    val bannerBottom = statusTop + DISMISS_STRIP + ART_BOX_TOP_PAD + groupTop + fullArt + ART_TITLE_GAP / 2 +
                        if (bleedRoomy) titleDrop + ART_TITLE_GAP / 2 else 0.dp
                    if (!lyricsOpen && !queueOpen && !statsOpen && bannerBottom != heroHeight) SideEffect { heroHeight = bannerBottom }
                    val sleeveMaxWidth = maxWidth
                    fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                    fun artWidthNow(): Dp = lerp(artWidth, THUMB_SIZE, p())
                    fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                    fun artStart(): Dp = lerp((maxWidth - artWidth) / 2, 0.dp, p())
                    fun titleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP + titleDrop, 0.dp, p())
                    fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())

                    // ---- Music video: spans the full screen width, filling the artwork slot.
                    if (videoMode && !collapsePastHalf) {
                        Box(
                            Modifier
                                // requiredWidth wider than the column overflows equally on
                                // both sides, so this already reaches both screen edges.
                                .offset { IntOffset(0, artTop().roundToPx()) }
                                .requiredWidth(sleeveMaxWidth + PLAYER_GUTTER * 2)
                                .height(fullArt + ART_TITLE_GAP / 2)
                                .graphicsLayer { alpha = 1f - 2f * p() }
                                .then(sleeveGesture)
                                // Controls hidden over the video (#166): a tap on it brings them, another sends them away.
                                // #293: the video's own buttons wait for a tap too; with the controls hiding as well, they go together.
                                .then(
                                    if (videoHidesDeck || settings.view.videoButtonsHide) Modifier.pointerInput(videoHidesDeck) {
                                        detectTapGestures(onTap = { if (videoHidesDeck) coverDeck = !coverDeck else videoChips = !videoChips })
                                    } else Modifier
                                ),
                        ) {
                            // One video view at a time: full-bleed, it's drawn behind the player (see videoBehind).
                            if (!videoBehind) MusicVideoSurface(Modifier.fillMaxSize(), fill = videoFill)
                            val chipsShown = !settings.view.videoButtonsHide || (if (videoHidesDeck) coverDeck else videoChips)
                            val chipsAlpha by animateFloatAsState(if (chipsShown) 1f else 0f, tween(180), label = "videoChips")
                            if (chipsShown || chipsAlpha > 0f) Row(Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = chipsAlpha }.padding(10.dp).testTag("videoChips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!videoBehind) VideoChip(PrismIcons.AspectRatio, if (videoFill) tr(R.string.fit_video) else tr(R.string.fill)) { videoFill = !videoFill }
                                if (!wide) VideoChip(PrismIcons.FillIn, if (videoBehind) tr(R.string.video_in_a_box) else tr(R.string.full_bleed_video)) {
                                    c.settings.setVideoFullBleed(!settings.videoFullBleed)
                                }
                                if (activity != null) VideoChip(PrismIcons.PictureInPicture, tr(R.string.picture_in_picture)) {
                                    if (!activity.enterPip()) android.widget.Toast.makeText(
                                        context, tr(R.string.picture_in_picture_is_off_for), android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                VideoChip(PrismIcons.Fullscreen, tr(R.string.full_screen)) { fullscreenVideo = true }
                            }
                        }
                    }

                    // ---- The sleeve (collapses to a header thumbnail)
                    Box(
                        Modifier
                            .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                            .measuredBox(::artWidthNow, ::artSize)
                            .then(if (!videoMode) sleeveGesture.then(doubleTapSeek) else Modifier)
                            .graphicsLayer {
                                val idle = artScale + (1f - artScale) * p()
                                scaleX = idle; scaleY = idle
                                translationX = swipe.value * (1f - p())
                            }
                            .then(
                                if (lyricsOpen || queueOpen || statsOpen) Modifier.clickable { queueOpen = false; statsOpen = false; closeLyrics() } else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A hidden cover (What's on the player, 1.8.0) isn't drawn at all until it shrinks into the header:
                        // no animated cover decoding, no record or tape turning, no blend or ring around nothing.
                        val coverAway = !settings.drawsCover(collapsePastHalf)
                        // The visualizer's Ring, behind the cover (it reaches out past the edge).
                        if (viz != null && vizStyle == com.prism.music.data.prefs.VisualizerStyle.RING && !collapsePastHalf && !heroMode && !coverAway) {
                            // A tape is wider than tall, with its own corners (Cassette.kt).
                            val cornerPx = with(LocalDensity.current) { if (cassette) 0f else artCorners.toPx() }
                            VisualizerRing(
                                viz, vizBrush, round = vinyl, cornerPx = cornerPx, modifier = Modifier.matchParentSize(),
                                aspect = if (cassette) CASSETTE_ASPECT else 1f,
                            )
                        }
                        // The cover flying in from the mini player lands here (#199): where it is, and unseen until then.
                        val flightTarget = !vinyl && !cassette && !heroMode && !videoMode
                        // #223: nothing to fly back from when it isn't this cover that's showing.
                        LaunchedEffect(flightTarget) { if (!flightTarget) CoverFlight.player = null }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(if (flightTarget) with(CoverFlight) { Modifier.playerCover() } else Modifier)
                                .graphicsLayer {
                                    alpha = when {
                                        CoverFlight.flying -> 0f
                                        videoMode -> (2f * p() - 1f).coerceIn(0f, 1f)
                                        heroMode && (artLoaded || heroPicture != null) -> 1f - heroVisible()
                                        // Hidden (1.8.0): it shows only as it shrinks into the header.
                                        !settings.shows(PlayerPart.COVER) -> p()
                                        else -> 1f
                                    }
                                }
                                // A record keeps its own round shadow and clip (the tonearm reaches past its edge); a
                                // tape draws its own shell.
                                .then(if (vinyl || cassette) Modifier else Modifier.shadow(if (artLoaded) 10.dp else 0.dp, com.prism.music.ui.theme.CoverShapes.shadowOf(artShape)).clip(artShape).background(Color.Black.copy(alpha = 0.18f))),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Vinyl: the cover as a record's label, turning while it plays (the animated cover isn't used).
                            if (vinyl) VinylRecord(
                                artRequest, isPlaying, settings.vinylTonearm, Modifier.fillMaxSize(), hold = covered || coverAway,
                                collapsed = p, onArtLoaded = { artLoaded = true },
                            ) else if (cassette) CassetteTape(
                                artRequest, isPlaying, Modifier.fillMaxSize(), hold = covered || coverAway,
                                collapsed = p, onArtLoaded = { artLoaded = true },
                            ) else {
                            // A file on the phone with no artwork gets a cover of its own rather than a bare note.
                            if (!artLoaded) {
                                if (song.isLocal) com.prism.music.ui.components.LocalCoverFill(song)
                                else Icon(PrismIcons.Song, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
                            }
                            AsyncImage(
                                artRequest, null, contentScale = ContentScale.Crop,
                                onState = { if (it is AsyncImagePainter.State.Success) artLoaded = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (!videoMode && !coverAway) BlendingCover(song.id, Modifier.fillMaxSize())
                            }
                            if (!heroMode && !videoMode && !canvasBehind && !vinyl && !cassette && !coverAway) canvas?.takeIf { !collapsePastHalf }?.let { clip ->
                                CanvasPlayer(clip, canvasSaved, isPlaying, Modifier.fillMaxSize(), onCoverChanged = { canvasCover = it })
                            }
                        }
                        seekFlash?.takeIf { !collapsePastHalf }?.let { f ->
                            SeekLabel(f, Modifier.align(if (f.ahead) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 18.dp))
                        }
                    }

                    // The visualizer's Wave, behind the title.
                    if (viz != null && vizStyle == com.prism.music.data.prefs.VisualizerStyle.WAVE && !collapsePastHalf) VisualizerWave(
                        viz, vizBrush,
                        Modifier.fillMaxWidth().height(HEADER_HEIGHT).offset { IntOffset(0, titleTop().roundToPx()) }.graphicsLayer { alpha = 1f - 2f * p() }.inked(),
                    )
                    // ---- Credits: title, artist, like, menu
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, (titleTop() - lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())).roundToPx()) }
                            .layout { measurable, constraints ->
                                val start = titleStart().roundToPx()
                                val placeable = measurable.measure(constraints.offsetConstraints(horizontal = -start))
                                layout(constraints.constrainWidth(placeable.width + start), constraints.constrainHeight(placeable.height)) {
                                    placeable.placeRelative(start, 0)
                                }
                            }
                            .height(HEADER_HEIGHT)
                            .then(sleeveGesture)
                            // #320: dark over the White background.
                            .inked(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Settings → Player → Title (1.7.4): centred titles sit in the middle of the screen (a space as wide
                        // as the buttons on the right balances them), until the cover shrinks into the header.
                        val centred = settings.playerTitleAlign == com.prism.music.data.prefs.PlayerTitleAlign.CENTER && !collapsePastHalf
                        // Like picked for the buttons row: one heart is enough while that row is on screen (the
                        // header keeps its own over the lyrics and the queue, and with Cover / Minimal, which hide the row).
                        val heartBelow = com.prism.music.data.prefs.PlayerButton.LIKE in settings.playerButtons &&
                            settings.shows(PlayerPart.BUTTONS) &&
                            layout != com.prism.music.data.prefs.PlayerLayout.MINIMAL && layout != com.prism.music.data.prefs.PlayerLayout.COVER &&
                            !lyricsOpen && !queueOpen && !statsOpen
                        if (centred) Spacer(Modifier.width(if (heartBelow) TITLE_BUTTONS_WIDTH - LIKE_GLYPH_ROOM else TITLE_BUTTONS_WIDTH))
                        Column(
                            Modifier.weight(1f).graphicsLayer {
                                val s = 1f - 0.2f * p()
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(if (centred) 0.5f else 0f, 0.5f)
                            },
                            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
                        ) {
                            val scrolls = collapse.value < 0.01f && !covered && settings.playerTitleScroll
                            val titleScale = settings.playerTitleSize.scale
                            // With the ⋯ button hidden (1.8.0), a long press on the title or the artist opens the menu.
                            val menuHeld: (() -> Unit)? = if (settings.shows(PlayerPart.MENU)) null else ({ showActions = true })
                            // #328: the title, artists and album of the version picked to play in its place, if any.
                            @Suppress("NAME_SHADOWING") val song = com.prism.music.data.SongVersions.shownOf(song)
                            if (inHeader(PlayerPart.TITLE)) PlayerMarquee(
                                // The title leads, the artist under it a size smaller (#41). Spectrum: large and light
                                // (a tenth under headlineMedium, so longer titles fit before they scroll).
                                song.title, MaterialTheme.typography.headlineMedium.let { it.copy(fontSize = it.fontSize * 0.9f * titleScale, lineHeight = it.lineHeight * 0.9f * titleScale, fontWeight = settings.type.playerTitleWeight.title ?: it.fontWeight) }, Color.White, scrolls,
                                Modifier.testTag("playerTitle").combinedClickable(enabled = song.album?.id != null || menuHeld != null, onLongClick = menuHeld) {
                                    song.album?.id?.let { onCollapse(); nav.go(Routes.album(it)) }
                                },
                            )
                            // With more than one artist, the name asks which one to open (#42).
                            // Artists YouTube names without a link open by name (#78).
                            val linked = com.prism.music.ui.components.openableArtists(song)
                            var pickArtist by remember(song.id) { mutableStateOf(false) }
                            if (inHeader(PlayerPart.ARTIST)) Box {
                                // #289 (1.8.1): each name its own link, if asked for; a tap between them still asks.
                                val artistLine = artistLinkText(song.artistText, linked, settings.view.artistLinks) { a -> onCollapse(); nav.go(Routes.artist(a)) }
                                PlayerMarquee(
                                    artistLine, MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = settings.type.playerTitleWeight.artist ?: it.fontWeight) }, Color.White.copy(alpha = 0.6f), scrolls,
                                    Modifier.testTag("playerArtist").combinedClickable(enabled = linked.isNotEmpty() || menuHeld != null, onLongClick = menuHeld) {
                                        if (linked.size > 1) pickArtist = true else linked.firstOrNull()?.let { onCollapse(); nav.go(Routes.artist(it)) }
                                    },
                                    delayMs = 5500,
                                )
                                androidx.compose.material3.DropdownMenu(pickArtist, { pickArtist = false }) {
                                    linked.forEach { a ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            { Text(a.name) }, { pickArtist = false; onCollapse(); nav.go(Routes.artist(a)) },
                                            leadingIcon = { androidx.compose.material3.Icon(PrismIcons.Person, null) },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        // A Smart Shuffle pick: keep it in the playlist, or turn it down.
                        val smartPicks by c.queue.smartPicks.collectAsState()
                        if (song.id in smartPicks) {
                            SmartPickButton(song)
                            Spacer(Modifier.width(8.dp))
                        }
                        val isLiked = song.id in liked
                        if (!heartBelow && inHeader(PlayerPart.LIKE) && !song.isRss) {
                            CircleGlyph(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, if (isLiked) tr(R.string.remove_from_liked) else tr(R.string.widget_like), isLiked) {
                                c.library.toggleLike(song)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (settings.showsMenuButton() || collapsePastHalf) CircleGlyph(PrismIcons.More, tr(R.string.song_options)) { showActions = true }
                    }

                    // ---- Lyrics and queue panels, under the collapsed header
                    if (lyricsOpen && panelsSettled) {
                        LyricsPanel(
                            lyricsState, isPlaying && !covered, lyricsControlsOpen,
                            onRevealControls = { lyricsControlsOpen = true },
                            onHideControls = { if (!controlsStay) lyricsControlsOpen = false },
                            modifier = Modifier.fillMaxSize().padding(top = HEADER_HEIGHT).graphicsLayer { alpha = panelFade },
                            onChangeSource = { showProviders = true },
                        )
                    }
                    if (statsOpen && panelsSettled) {
                        NerdStatsPanel(
                            Modifier.fillMaxSize().padding(top = HEADER_HEIGHT + 8.dp).graphicsLayer {
                                alpha = panelFade
                                translationY = (1f - panelFade) * 26.dp.toPx()
                            },
                        )
                    }
                    if (queueOpen && panelsSettled) {
                        InlineQueue(
                            onRevealPlayer = { queueControlsOpen = true },
                            onHidePlayer = { if (!controlsStay) queueControlsOpen = false },
                            onClose = { haptics.gestureEnd(); toggleQueue() },
                            modifier = Modifier.fillMaxSize().padding(top = HEADER_HEIGHT + 8.dp).graphicsLayer {
                                alpha = panelFade
                                translationY = (1f - panelFade) * 26.dp.toPx()
                            },
                        )
                    }
                }

                // ------------------------------------------------ The deck
                // Cover layout: the deck stays away until the cover's tapped (the lyrics and queue bring it as before).
                val deckShown = (!lyricsOpen || lyricsControlsOpen || controlsStay) && (!queueOpen || queueControlsOpen || controlsStay) &&
                    (!deckOnTap || coverDeck || lyricsOpen || queueOpen || statsOpen || (videoHidesDeck && !isPlaying))
                AnimatedVisibility(
                    deckShown,
                    enter = expandVertically(tween(420, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(420)),
                    exit = shrinkVertically(tween(420, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(300)),
                ) {
                    // Settings → Controls & gestures → "Swipe up for the queue" (#200, #197): up on the controls opens the
                    // queue (from their right half) or the lyrics (left half); down closes whichever is open. One-handed,
                    // instead of reaching for the buttons or the top of the screen.
                    val swipes = if (!settings.behavior.panelSwipes) Modifier else Modifier.pointerInput(panelPlayer) {
                        var total = 0f
                        var fromRight = true
                        detectVerticalDragGestures(
                            onDragStart = { at -> total = 0f; fromRight = at.x > size.width / 2 },
                            onDragEnd = {
                                when {
                                    total < -PANEL_SWIPE_PX && !lyricsOpen && !queueOpen -> { haptics.gestureEnd(); if (fromRight) toggleQueue() else openLyrics() }
                                    total > PANEL_SWIPE_PX && queueOpen -> { haptics.gestureEnd(); toggleQueue() }
                                    total > PANEL_SWIPE_PX && lyricsOpen -> { haptics.gestureEnd(); closeLyrics() }
                                }
                            },
                        ) { change, amount -> change.consume(); total += amount }
                    }
                    // #259 (1.7.9.2): "Gestures anywhere on the player": the cover's swipes, double tap and long press work
                    // around the controls too (the buttons and bars keep their own taps and drags).
                    val coverGestures = when {
                        settings.behavior.panelSwipes -> Modifier
                        // #282: below the lyrics, the deck takes the cover's gestures. The lyrics are their own list above it,
                        // so scrolling them never reaches this.
                        lyricsOpen && !queueOpen && !statsOpen && settings.behavior.gesturesUnderLyrics ->
                            sleeveDrag(lyrics = false, queue = false, stats = false)
                                .then(tapsAndHolds(panelOpen = false, tapShowsDeck = false))
                                .then(sideSwipe(panelOpen = false))
                        !settings.behavior.gesturesEverywhere || lyricsOpen || queueOpen || statsOpen -> Modifier
                        else -> sleeveGesture.then(doubleTapSeek)
                    }
                    Column(swipes.then(coverGestures).testTag("playerDeck").inked(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // #293: closer together over a full-bleed picture ("More room for the picture").
                        val tightDeck = bleedRoomy && !lyricsOpen && !queueOpen && !statsOpen
                        val deckGap = if (tightDeck) 8.dp else if (roomyCover) 12.dp else 18.dp
                        // With the lyrics open: Sing's one button at their lower right (1.7.8, V; their source is a line
                        // at their end), and the source as a button at the lower left when asked for (1.7.9).
                        if (lyricsOpen) LyricsButtonsRow(song, lyricsState, onChangeSource = { showProviders = true }, onNeedsDownload = { showSing = true }, modifier = Modifier.padding(bottom = 6.dp))
                        // Minimal: play, and gestures for the rest (the lyrics and queue still have the whole deck).
                        if (layout == com.prism.music.data.prefs.PlayerLayout.MINIMAL && !lyricsOpen && !queueOpen && !statsOpen) {
                            MinimalPlay(isPlaying, buffering)
                            Spacer(Modifier.height(deckGap))
                        } else {
                            // The parts in the listener's order (Settings → Player → Arrange, 1.7.4); what's switched off
                            // elsewhere stays off.
                            // Small (#230): over the lyrics or the queue, only the progress, a smaller transport and the
                            // buttons (they switch between lyrics and queue), so the list keeps most of the screen.
                            val small = (lyricsOpen || queueOpen) && panelPlayer == com.prism.music.data.prefs.PanelPlayer.SMALL
                            val parts = settings.playerDeck.filter { it != com.prism.music.data.prefs.DeckPart.STRIP || (!lyricsOpen && settings.lyricsOnPlayer) }
                                .filter { !small || it == com.prism.music.data.prefs.DeckPart.SCRUBBER || it == com.prism.music.data.prefs.DeckPart.TRANSPORT || it == com.prism.music.data.prefs.DeckPart.BUTTONS }
                                // What's on the player (1.8.0): the progress bar and the buttons row can go too.
                                .filter { it != com.prism.music.data.prefs.DeckPart.SCRUBBER || settings.shows(PlayerPart.PROGRESS) }
                                .filter { it != com.prism.music.data.prefs.DeckPart.BUTTONS || settings.shows(PlayerPart.BUTTONS) }
                            // #329: what's hidden leaves no hole; the hidden volume bar's room is shared between the gaps.
                            val spacing = deckSpacing(parts, settings.shows(PlayerPart.VOLUME), small, tight = tightDeck)
                            spacing.parts.forEachIndexed { i, part ->
                                when (part) {
                                    com.prism.music.data.prefs.DeckPart.STRIP -> CurrentLyricStrip(lyricsState, isPlaying && !covered, overScrubber = stripOverScrubber(spacing.parts, i), onClick = openLyrics)
                                    com.prism.music.data.prefs.DeckPart.SCRUBBER -> PlayheadScrubber(position, pc.duration) {
                                        // Settings → Player → "Stream quality" hides it (the full stats stay in the ⋯ menu).
                                        // In a song's opening seconds the middle offers its best part instead, while it's still ahead.
                                        if (!SingWord() && !BestPartChip() && settings.showQuality) QualityLabel(Modifier.align(Alignment.Center), selected = statsOpen) { toggleStats() }
                                    }
                                    com.prism.music.data.prefs.DeckPart.TRANSPORT ->
                                        TransportRow(
                                            isPlaying, buffering, previousEnabled = hasPrevious || pastStart, nextEnabled = hasNext, compact = small || tightDeck, episode = song.isEpisode,
                                            skips = settings.shows(PlayerPart.PREV_NEXT),
                                        )
                                    com.prism.music.data.prefs.DeckPart.VOLUME -> VolumeRow(volume)
                                    com.prism.music.data.prefs.DeckPart.BUTTONS -> PlayerActionRow(
                                        lyricsOpen, queueOpen, videoMode, videoAvailable,
                                        onToggleLyrics = { if (lyricsOpen) closeLyrics() else openLyrics() },
                                        onToggleQueue = toggleQueue,
                                        onVideoMode = { pc.setVideoMode(it) },
                                        episode = song.isEpisode,
                                        onButton = onButton,
                                    )
                                }
                                // The gaps the deck always had after each part (none after the last); Small keeps them tight.
                                spacing.gaps.getOrNull(i)?.let { Spacer(Modifier.height(it)) }
                            }
                            Spacer(Modifier.height((if (small) 6.dp else deckGap) + spacing.below))
                        }
                        val smallDeck = (lyricsOpen || queueOpen) && panelPlayer == com.prism.music.data.prefs.PanelPlayer.SMALL
                        // The device line, with the sleep timer beside it while one is set (on its own when the line is hidden).
                        if (smallDeck) SleepBadge()
                        else if (settings.showOutputDevice) Row(verticalAlignment = Alignment.CenterVertically) {
                            OutputCaption()
                            SleepBadge(Modifier.padding(start = 8.dp))
                        } else {
                            SleepBadge()
                            if (!roomyCover && !tightDeck) Spacer(Modifier.height(14.dp))
                        }
                        Spacer(Modifier.height(if (smallDeck) 6.dp else deckGap))
                    }
                }
                // The controls away over the lyrics or the queue: a swipe up from the bottom edge brings them back (a tap
                // on the lyrics or the queue does too).
                if (!deckShown && (lyricsOpen || queueOpen)) Box(
                    Modifier.fillMaxWidth().height(40.dp).pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -40f) { lyricsControlsOpen = true; queueControlsOpen = true } },
                        ) { change, amount -> change.consume(); total += amount }
                    },
                )
            }
        }
        // #250 on Android 15 and later, where a window's navigation bar color isn't drawn: the band is the player's own.
        if (drawsNavBand(android.os.Build.VERSION.SDK_INT, navBand)) PlayerNavBand(inkLight, Modifier.align(Alignment.BottomCenter))
    }
    }

    playerSheets()
}

/** The "Theme" player background: a deep wash of the accent color, lighter at the top. */
@Composable
private fun ThemeBackdrop() {
    val scheme = MaterialTheme.colorScheme
    fun Color.deep(f: Float) = Color(red * f, green * f, blue * f, 1f)
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(scheme.primary.deep(0.55f), scheme.tertiary.deep(0.28f), Color.Black.copy(alpha = 0.92f).compositeOver(scheme.primary.deep(0.1f))))
        ),
    )
}

/**
 * The background picked for Home in Appearance → Backgrounds. The player is always dark (white text and
 * controls), so the background is drawn in its dark version whatever the app's theme, under a veil whose
 * strength [show] sets, heavier toward the controls at the bottom.
 */
@Composable
private fun AppearanceBackdrop(show: Float) {
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    // The player's own pick (1.7.4), else Home's.
    val (style, photo) = com.prism.music.ui.theme.Backdrop.parse(
        s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP] ?: s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name],
    )
    val primary = MaterialTheme.colorScheme.primary
    val darkScheme = remember(primary) { com.prism.music.ui.theme.schemeFromSeed(primary, dark = true, pureBlack = false) }
    val veil = 1f - show
    MaterialTheme(colorScheme = darkScheme) {
        androidx.compose.runtime.CompositionLocalProvider(com.prism.music.ui.theme.LocalIsDark provides true) {
            Box(Modifier.fillMaxSize().background(darkScheme.background)) {
                com.prism.music.ui.theme.BackdropLayer(
                    style, photo, dim = 0.7f * veil, motion = s.backdropMotion && !s.reduceMotion, modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.10f * veil), 0.55f to Color.Black.copy(alpha = 0.18f * veil + 0.04f),
                            1f to Color.Black.copy(alpha = 0.45f * veil + 0.18f)),
                    ),
                )
            }
        }
    }
}

@Composable
private fun VideoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

/**
 * The main player's video, drawn on a TextureView so it composes with the layout. [bottomFade]: the share of its
 * height, from the bottom, over which it dissolves into what's behind (the full-bleed video, #120).
 */
@OptIn(UnstableApi::class)
@Composable
fun MusicVideoSurface(modifier: Modifier, fill: Boolean, bottomFade: Float = 0f, alpha: () -> Float = { 1f }) {
    val pc = LocalContainer.current.player
    AndroidView(
        factory = { ctx ->
            FadingFrame(ctx).apply {
                addView((LayoutInflater.from(ctx).inflate(R.layout.texture_player, this, false) as PlayerView).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    player = pc.player
                })
                // The fade's gradient is as tall as the view: laid out again, it's made again.
                if (android.os.Build.VERSION.SDK_INT >= 31) addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) v.bottomFade((v as FadingFrame).wanted)
                }
            }
        },
        update = { frame ->
            val view = frame.getChildAt(0) as PlayerView
            view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (view.player !== pc.player) view.player = pc.player
            frame.alpha = alpha()
            frame.wanted = bottomFade
            if (android.os.Build.VERSION.SDK_INT >= 31) frame.bottomFade(bottomFade) else frame.fade = bottomFade
        },
        onRelease = { (it.getChildAt(0) as PlayerView).player = null },
        modifier = modifier,
    )
}

/** True while Prism is a picture-in-picture window (provided by MainActivity). */
val LocalPip = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Edge-to-edge landscape video with tap-to-show controls. In picture-in-picture ([pip]) it's the
 * video alone: Android draws the window's own controls (from the media session) over it.
 */
@Composable
private fun FullscreenVideo(song: Song, forceLandscape: Boolean, pip: Boolean = false, onExit: () -> Unit) {
    val pc = LocalContainer.current.player
    val view = LocalView.current
    val activity = view.context as? Activity
    val isPlaying by pc.isPlaying.collectAsState()
    var controls by remember { mutableStateOf(true) }
    val position = rememberPositionState(fast = false)
    val prefs = LocalContainer.current.settings
    DisposableEffect(forceLandscape) {
        val window = activity?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousOrientation = activity?.requestedOrientation
        if (forceLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // Back to the bars as the settings have them (#115: the status bar may stay hidden).
            com.prism.music.ui.components.SystemBars.apply(ctl, com.prism.music.ui.components.SystemBars.hidden(prefs.current))
            if (forceLandscape) activity?.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(controls, isPlaying) { if (controls && isPlaying) { delay(3500); controls = false } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { pc.togglePlay() }) },
    ) {
        MusicVideoSurface(Modifier.fillMaxSize(), fill = false)
        AnimatedVisibility(controls && !pip, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(song.artistText, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onExit),
                    contentAlignment = Alignment.Center,
                ) { Icon(PrismIcons.FullscreenExit, tr(R.string.exit_full_screen), tint = Color.White) }
                Box(
                    Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { pc.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (isPlaying) PrismIcons.Pause else PrismIcons.Play, tr(R.string.play_pause), tint = Color.White, modifier = Modifier.size(44.dp)) }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp)) {
                    PlayheadScrubber(position, pc.duration)
                }
            }
        }
    }
}

/** The last double-tap seek on the cover (#79): which way, how many taps in a row, and when (to time the label out). */
/** A seek shown over the cover: [steps] in a row of [step] seconds each, back or [ahead]. */
internal data class SeekFlash(val ahead: Boolean, val steps: Int, val at: Long, val step: Int = 10)

/** "−10 s" / "+10 s" over the cover's side while double-taps seek. */
@Composable
internal fun SeekLabel(f: SeekFlash, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!f.ahead) Icon(PrismIcons.Previous, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            (if (f.ahead) "+" else "−") + "${f.steps * f.step} s",
            color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp),
        )
        if (f.ahead) Icon(PrismIcons.Next, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * "Covers blend too" (1.7.8, A6, after Apple Music): while a crossfade plays into [songId], the ending song's cover lies
 * over it and fades away over the blend, so the cover changes with the sound instead of at the blend's start. With
 * Reduce motion it simply switches halfway. Nothing is drawn otherwise.
 */
@Composable
private fun BlendingCover(songId: String, modifier: Modifier) {
    val c = LocalContainer.current
    val settings = com.prism.music.ui.theme.LocalAppSettings.current
    if (!settings.view.blendCovers) return
    val blend by c.mixState.blend.collectAsState()
    val now = blend?.takeIf { it.toId == songId } ?: return
    val queue by c.player.queue.collectAsState()
    val from = remember(now.fromId) { queue.firstOrNull { it.song.id == now.fromId }?.song } ?: return
    val alpha = if (settings.reduceMotion) (if (now.progress < 0.5f) 1f else 0f) else 1f - now.progress
    if (alpha <= 0f) return
    coil3.compose.AsyncImage(
        hiRes(from.thumbnail, 1200), null, contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/**
 * The status bar's room at the top, also while it's hidden (Hide status bar): the player keeps its place and only the bar
 * goes (the owner, 1.8.0: it moved the whole player up).
 */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun statusBarRoom(): WindowInsets = WindowInsets.statusBarsIgnoringVisibility

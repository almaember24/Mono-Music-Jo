package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.shows
import com.prism.music.data.prefs.showsMenuButton
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.data.model.Song
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalWindowLayout
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the second pane of a wide player shows. */
enum class PlayerPane(val label: String) { LYRICS(tr(R.string.lyrics)), QUEUE(tr(R.string.up_next)), STATS(tr(R.string.stats)) }

/**
 * The player on a wide window (a tablet, an unfolded foldable, a phone on its side): the cover and controls on one
 * side and lyrics, the queue or stream stats on the other, both always in view, so nothing collapses into a header.
 * A foldable half-open like a laptop (tabletop) gets the cover above the fold and the controls below it.
 */
@Composable
internal fun WidePlayerContent(
    song: Song,
    isPlaying: Boolean,
    buffering: Boolean,
    /** Read only in the scrubber (and Previous's 3 s rule), so the player isn't recomposed as the song plays. */
    position: androidx.compose.runtime.State<Long>,
    hasNext: Boolean,
    hasPrevious: Boolean,
    videoMode: Boolean,
    videoAvailable: Boolean,
    lyricsState: LyricsState,
    covered: Boolean,
    canvas: CanvasArtwork?,
    canvasSaved: Boolean,
    artRequest: ImageRequest,
    volume: PlayerVolume,
    pane: PlayerPane,
    onPane: (PlayerPane) -> Unit,
    dismissGesture: Modifier,
    onMore: () -> Unit,
    onLyricsSource: () -> Unit,
    onCollapse: () -> Unit,
    onSing: () -> Unit = {},
    onSingNeedsDownload: () -> Unit = onSing,
    /** The bottom row's buttons that open something over the player. */
    onButton: (com.prism.music.data.prefs.PlayerButton) -> Unit = {},
) {
    val c = LocalContainer.current
    val pc = c.player
    val settings by c.settings.flow.collectAsState()
    val window = LocalWindowLayout.current
    val density = LocalDensity.current

    val pastStart by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { position.value > 3000 } }
    val deck: @Composable (short: Boolean) -> Unit = { short ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // What's on the player (1.8.0) hides parts here too.
            if (settings.shows(PlayerPart.PROGRESS)) {
                PlayheadScrubber(position, pc.duration) {
                    if (!BestPartChip() && settings.showQuality) QualityLabel(Modifier.align(Alignment.Center), selected = pane == PlayerPane.STATS) {
                        onPane(if (pane == PlayerPane.STATS) PlayerPane.LYRICS else PlayerPane.STATS)
                    }
                }
                Spacer(Modifier.height(if (short) 2.dp else 8.dp))
            }
            TransportRow(
                isPlaying, buffering, previousEnabled = hasPrevious || pastStart, nextEnabled = hasNext, compact = short, episode = song.isEpisode,
                skips = settings.shows(PlayerPart.PREV_NEXT),
            )
            if (!short) {
                Spacer(Modifier.height(10.dp))
                if (settings.shows(com.prism.music.data.prefs.PlayerPart.VOLUME)) VolumeRow(volume)
            }
            Spacer(Modifier.height(if (short) 2.dp else 6.dp))
            if (settings.shows(PlayerPart.BUTTONS)) PlayerActionRow(
                lyricsOpen = pane == PlayerPane.LYRICS, queueOpen = pane == PlayerPane.QUEUE, videoMode, videoAvailable,
                onToggleLyrics = { onPane(PlayerPane.LYRICS) },
                onToggleQueue = { onPane(if (pane == PlayerPane.QUEUE) PlayerPane.LYRICS else PlayerPane.QUEUE) },
                onVideoMode = { pc.setVideoMode(it) },
                episode = song.isEpisode,
                onButton = onButton,
            )
            if (!short && settings.showOutputDevice) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    OutputCaption()
                    SleepBadge(Modifier.padding(start = 8.dp))
                }
            } else if (!short) SleepBadge()
        }
    }

    val hinge = window.hinge
    if (window.tabletop && hinge != null) {
        // Half-open like a laptop: the cover (or video) stands up above the fold, everything you touch lies below it.
        val foldTop = with(density) { hinge.top.toDp() }
        val foldBottom = with(density) { hinge.bottom.toDp() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(foldTop).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().then(dismissGesture), contentAlignment = Alignment.Center) {
                    WideCover(song, videoMode, isPlaying, covered, canvas, canvasSaved, artRequest)
                }
            }
            Spacer(Modifier.height(foldBottom - foldTop))
            Column(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 32.dp, vertical = 8.dp)) {
                PlayerCredits(song, onMore, onCollapse)
                Spacer(Modifier.height(4.dp))
                CurrentLyricStrip(lyricsState, isPlaying && !covered) { }
                deck(true)
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // ---------------- The cover and the controls
        BoxWithConstraints(Modifier.weight(if (window.shortLandscape) 0.5f else 0.46f).fillMaxHeight().padding(horizontal = 24.dp)) {
            val short = maxHeight < 520.dp
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(if (short) 18.dp else 28.dp).then(dismissGesture)) {
                    if (com.prism.music.ui.theme.LocalAppSettings.current.playerHandle) Box(
                        Modifier.align(Alignment.Center).size(38.dp, 5.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.6f)),
                    )
                }
                if (short) {
                    // A phone on its side: a small cover beside the title, so the controls still fit underneath.
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
                            WideCover(song, videoMode, isPlaying, covered, canvas, canvasSaved, artRequest, maxSide = minOf(maxWidth, maxHeight))
                        }
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.weight(1f)) { PlayerCredits(song, onMore, onCollapse) }
                    }
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        WideCover(song, videoMode, isPlaying, covered, canvas, canvasSaved, artRequest, maxSide = minOf(maxWidth, maxHeight))
                    }
                    Spacer(Modifier.height(16.dp))
                    PlayerCredits(song, onMore, onCollapse)
                }
                Spacer(Modifier.height(if (short) 4.dp else 10.dp))
                deck(short)
                Spacer(Modifier.height(if (short) 4.dp else 12.dp))
            }
        }

        // ---------------- Lyrics / Up next / Stats
        Column(Modifier.weight(if (window.shortLandscape) 0.5f else 0.54f).fillMaxHeight().padding(start = 8.dp, end = 24.dp, top = 8.dp)) {
            PaneTabs(pane, onPane, Modifier.padding(bottom = 6.dp))
            Crossfade(pane, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(220), label = "pane") { p ->
                when (p) {
                    PlayerPane.LYRICS -> Column(Modifier.fillMaxSize()) {
                        LyricsPanel(lyricsState, isPlaying && !covered, controlsOpen = true, onRevealControls = {}, onHideControls = {}, modifier = Modifier.weight(1f).fillMaxWidth(), lyricsGround = false, onChangeSource = onLyricsSource)
                        LyricsButtonsRow(song, lyricsState, onChangeSource = onLyricsSource, onNeedsDownload = onSingNeedsDownload)
                        Spacer(Modifier.height(10.dp))
                    }
                    PlayerPane.QUEUE -> InlineQueue(onRevealPlayer = {}, onHidePlayer = {}, modifier = Modifier.fillMaxSize())
                    PlayerPane.STATS -> NerdStatsPanel(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The cover (or the canvas, or the music video) at up to [maxSide], with the cover's own gestures: double-tap a half to seek, swipe to skip. */
@Composable
private fun WideCover(
    song: Song,
    videoMode: Boolean,
    isPlaying: Boolean,
    covered: Boolean,
    canvas: CanvasArtwork?,
    canvasSaved: Boolean,
    artRequest: ImageRequest,
    maxSide: Dp = Dp.Unspecified,
) {
    val c = LocalContainer.current
    val pc = c.player
    val settings by c.settings.flow.collectAsState()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    var seekFlash by remember { mutableStateOf<SeekFlash?>(null) }
    LaunchedEffect(seekFlash?.at) { if (seekFlash != null) { delay(750); seekFlash = null } }
    var canvasCover by remember(canvas) { mutableFloatStateOf(0f) }
    val seekStep = settings.doubleTapSeekSeconds
    val shape = RoundedCornerShape(settings.playerArtCorners.dp)
    val sized = if (maxSide != Dp.Unspecified) Modifier.size(maxSide) else Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
    Box(
        sized
            .offset { IntOffset(swipe.value.roundToInt(), 0) }
            .pointerInput(seekStep) {
                if (seekStep <= 0) return@pointerInput
                detectTapGestures(onDoubleTap = { at ->
                    val ahead = at.x > size.width / 2
                    haptics.gestureEnd()
                    pc.seekBy(if (ahead) seekStep * 1000L else -seekStep * 1000L)
                    seekFlash = SeekFlash(ahead, if (seekFlash?.ahead == ahead) seekFlash!!.steps + 1 else 1, System.nanoTime(), seekStep)
                })
            }
            .pointerInput(Unit) {
                var total = 0f
                val threshold = 72.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                    onDragEnd = {
                        if (total <= -threshold) { haptics.gestureEnd(); pc.next() }
                        else if (total >= threshold) { haptics.gestureEnd(); pc.previous() }
                        scope.launch { swipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                ) { _, d ->
                    if ((abs(total) < threshold) != (abs(total + d) < threshold)) haptics.threshold()
                    total += d
                    scope.launch { swipe.snapTo(total * 0.35f) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (videoMode) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).shadow(10.dp, shape).clip(shape).background(Color.Black)) {
                MusicVideoSurface(Modifier.fillMaxSize(), fill = false)
            }
        } else {
            Box(Modifier.fillMaxSize().shadow(10.dp, shape).clip(shape).background(Color.Black.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                if (song.isLocal) com.prism.music.ui.components.LocalCoverFill(song)
                else Icon(PrismIcons.Song, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
                AsyncImage(artRequest, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - canvasCover })
                canvas?.let { clip -> CanvasPlayer(clip, canvasSaved, isPlaying, Modifier.fillMaxSize(), onCoverChanged = { canvasCover = it }, hold = covered) }
            }
        }
        seekFlash?.let { f -> SeekLabel(f, Modifier.align(if (f.ahead) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 18.dp)) }
    }
}

/** Lyrics · Up next · Stats over the second pane, in the player's white-on-dark. */
@Composable
private fun PaneTabs(pane: PlayerPane, onPane: (PlayerPane) -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(3.dp)) {
        PlayerPane.entries.forEach { p ->
            val on = p == pane
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .then(if (on) Modifier.border(1.2.dp, com.prism.music.ui.theme.PrismTokens.spectrum, RoundedCornerShape(50)) else Modifier)
                    .clickable(onClickLabel = p.label) { haptics.tick(); onPane(p) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    p.label, color = Color.White.copy(alpha = if (on) 1f else 0.65f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Title and artist (each opens its page), like (unless the buttons row has it), and the song's menu. */
@Composable
internal fun PlayerCredits(song: Song, onMore: () -> Unit, onCollapse: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val liked by c.library.likedIds.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val heart = (com.prism.music.data.prefs.PlayerButton.LIKE !in settings.playerButtons || !settings.shows(PlayerPart.BUTTONS)) && settings.shows(PlayerPart.LIKE)
    // With the ⋯ button hidden (1.8.0), a long press on the title or the artist opens the menu.
    val menuHeld: (() -> Unit)? = if (settings.shows(PlayerPart.MENU)) null else onMore
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // #328: the title, artists and album of the version picked to play in its place, if any.
            @Suppress("NAME_SHADOWING") val song = com.prism.music.data.SongVersions.shownOf(song)
            if (settings.shows(PlayerPart.TITLE)) PlayerMarquee(
                song.title, MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp), Color.White, true,
                Modifier.combinedClickable(enabled = song.album?.id != null || menuHeld != null, onLongClick = menuHeld) {
                    song.album?.id?.let { onCollapse(); nav.go(Routes.album(it)) }
                },
            )
            val linked = com.prism.music.ui.components.openableArtists(song)
            var pickArtist by remember(song.id) { mutableStateOf(false) }
            if (settings.shows(PlayerPart.ARTIST)) Box {
                PlayerMarquee(
                    // #289: each name its own link, if asked for.
                    artistLinkText(song.artistText, linked, settings.view.artistLinks) { a -> onCollapse(); nav.go(Routes.artist(a)) },
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W500, fontSize = 17.sp, lineHeight = 22.sp),
                    Color.White.copy(alpha = 0.6f), true,
                    Modifier.combinedClickable(enabled = linked.isNotEmpty() || menuHeld != null, onLongClick = menuHeld) {
                        if (linked.size > 1) pickArtist = true else linked.firstOrNull()?.let { onCollapse(); nav.go(Routes.artist(it)) }
                    },
                    delayMs = 5500,
                )
                DropdownMenu(pickArtist, { pickArtist = false }) {
                    linked.forEach { a ->
                        DropdownMenuItem({ Text(a.name) }, { pickArtist = false; onCollapse(); nav.go(Routes.artist(a)) }, leadingIcon = { Icon(PrismIcons.Person, null) })
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        val isLiked = song.id in liked
        if (heart) {
            CircleGlyph(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, if (isLiked) tr(R.string.remove_from_liked) else tr(R.string.widget_like), isLiked) { c.library.toggleLike(song) }
            Spacer(Modifier.width(8.dp))
        }
        if (settings.showsMenuButton(wide = true)) CircleGlyph(PrismIcons.More, tr(R.string.song_options), onClick = onMore)
    }
}

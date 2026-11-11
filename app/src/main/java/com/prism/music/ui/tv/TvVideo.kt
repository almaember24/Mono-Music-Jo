package com.prism.music.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.TvMediaKeys
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.delay

/*
 * Prism TV's music video (1.8.0): the video full screen, and over it, on any key, the controls: the progress line
 * (← → seek), previous / play-pause / next, Lyrics, Queue, "Back to the song" and ⋯. They fade after a few seconds
 * without the remote; while they're hidden the screen itself holds focus, so focus is never on something you can't see.
 * The queue is the player's own side panel.
 */

/** The video screen's controls: [tag] is each one's test tag under [TAG_VIDEO] ("tv_video/queue"). */
enum class TvVideoControl(val tag: String) {
    SEEK("seek"), PREVIOUS("previous"), PLAYPAUSE("playpause"), NEXT("next"), LYRICS("lyrics"), QUEUE("queue"), SONG("song"), MORE("more");

    val fullTag: String get() = "$TAG_VIDEO/$tag"
}

/**
 * The controls a video shows, in D-pad order (the progress line above the row): no progress line for a live stream
 * (nothing to seek in), no Lyrics for an episode.
 */
fun tvVideoControls(hasLyrics: Boolean, seekable: Boolean): List<TvVideoControl> =
    TvVideoControl.entries.filter { c ->
        when (c) {
            TvVideoControl.SEEK -> seekable
            TvVideoControl.LYRICS -> hasLyrics
            else -> true
        }
    }

/** Where focus goes when the controls come back: the control left last, or play/pause when that one isn't shown. */
fun tvVideoFocusTarget(last: TvVideoControl, shown: List<TvVideoControl>): TvVideoControl =
    if (last in shown) last else TvVideoControl.PLAYPAUSE

/**
 * Holding ← or → on the progress line: a step on the press, then every [SEEK_REPEAT_EVERY]th key repeat (a remote
 * repeats a held key ~20 times a second; seeking 10 s on each would fly through the song).
 */
fun tvSeekStep(repeatCount: Int): Boolean = repeatCount % SEEK_REPEAT_EVERY == 0

private const val SEEK_REPEAT_EVERY = 3

/**
 * How far a step goes (1.8.3, D29): [TV_SEEK_STEP_MS] for a press, three times that once the key has been held about
 * a second and a half ([SEEK_FASTER_AFTER] repeats), to cross a long song or a mix.
 */
fun tvSeekStepMs(repeatCount: Int): Long = if (repeatCount >= SEEK_FASTER_AFTER) TV_SEEK_STEP_MS * 3 else TV_SEEK_STEP_MS

private const val SEEK_FASTER_AFTER = 30

/** How long the video's controls stay after the last key. */
const val TV_VIDEO_CONTROLS_MS = 4_000L

/** How far a step on the seek line moves (the remote's ← →, as its rewind and fast forward). */
const val TV_SEEK_STEP_MS = TvMediaKeys.SEEK_MS

/**
 * The controls over the bottom of a video: title, the seek line (← → seek), then the row; a null [onLyrics],
 * [onQueue] or [onMore] leaves that button out. [enabled] false (hidden, fading out, a panel over them): none of them
 * can take focus. [requesters] put focus on one; [onFocused] says which one has it. Drawn on their own (the design
 * pages), focus opens on [entry]; [TvVideoPlace] passes null and puts focus itself.
 */
@Composable
fun TvVideoControls(
    now: TvNow,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSong: () -> Unit,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},
    onLyrics: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    enabled: Boolean = true,
    requesters: Map<TvVideoControl, FocusRequester> = remember { TvVideoControl.entries.associateWith { FocusRequester() } },
    onFocused: (TvVideoControl) -> Unit = {},
    entry: TvVideoControl? = TvVideoControl.PLAYPAUSE,
) {
    val shown = tvVideoControls(now.hasLyrics && onLyrics != null, seekable = now.total.isNotEmpty())
        .filter { (it != TvVideoControl.QUEUE || onQueue != null) && (it != TvVideoControl.MORE || onMore != null) }
    if (entry != null) LaunchedEffect(Unit) {
        val target = tvVideoFocusTarget(entry, shown)
        repeat(40) { if (runCatching { requesters.getValue(target).requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(50) }
    }
    fun Modifier.control(c: TvVideoControl): Modifier = this
        .focusProperties { canFocus = enabled }
        .focusRequester(requesters.getValue(c))
        .onFocusChanged { if (it.isFocused) onFocused(c) }
        .testTag(c.fullTag)
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 72.dp, bottom = 27.dp).testTag(TAG_VIDEO_CONTROLS),
    ) {
        Text(now.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
        Text(now.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
        Spacer(Modifier.height(10.dp))
        if (TvVideoControl.SEEK in shown) {
            // Down from the line lands on play/pause, whichever button is under it.
            TvSeekLine(now, onSeek, onPlayPause, Modifier.focusProperties { down = requesters.getValue(TvVideoControl.PLAYPAUSE) }.control(TvVideoControl.SEEK))
        } else TvProgress(now.fraction, now.played, now.total, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIconButton(PrismIcons.Previous, tr(R.string.previous_song), onPrevious, Modifier.control(TvVideoControl.PREVIOUS))
            TvIconButton(
                if (now.playing) PrismIcons.Pause else PrismIcons.Play, tr(if (now.playing) R.string.widget_pause else R.string.widget_play),
                onPlayPause, Modifier.control(TvVideoControl.PLAYPAUSE), big = true,
            )
            TvIconButton(PrismIcons.Next, tr(R.string.next_song), onNext, Modifier.control(TvVideoControl.NEXT))
            Spacer(Modifier.width(12.dp))
            if (onLyrics != null && TvVideoControl.LYRICS in shown) TvButton(tr(R.string.lyrics), onLyrics, Modifier.control(TvVideoControl.LYRICS), icon = PrismIcons.Lyrics)
            if (onQueue != null) TvButton(tr(R.string.queue), onQueue, Modifier.control(TvVideoControl.QUEUE), icon = PrismIcons.Queue)
            TvButton(tr(R.string.back_to_the_song), onSong, Modifier.control(TvVideoControl.SONG), icon = PrismIcons.Song)
            if (onMore != null) TvIconButton(PrismIcons.More, tr(R.string.more), onMore, Modifier.control(TvVideoControl.MORE))
        }
    }
}

/**
 * The song's progress as one focusable line: ← and → move 10 s back or ahead (as the remote's rewind and fast
 * forward), OK pauses and plays. Focused, it's outlined.
 */
@Composable
fun TvSeekLine(now: TvNow, onSeek: (Long) -> Unit, onPlayPause: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val description = tr(R.string.tv_seek_line, now.played, now.total)
    val ring = tvRing()
    Box(
        modifier
            .semantics { contentDescription = description }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeats = e.nativeKeyEvent.repeatCount
                val step = tvSeekStep(repeats)
                when (e.key) {
                    Key.DirectionLeft -> { if (step) onSeek(-tvSeekStepMs(repeats)); true }
                    Key.DirectionRight -> { if (step) onSeek(tvSeekStepMs(repeats)); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (e.nativeKeyEvent.repeatCount == 0) onPlayPause(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            // Focused, the split light rings it (as every focused thing on the TV).
            .then(if (focused) Modifier.border(TV_RING_THIN, ring, TvShapes.card) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        TvProgress(now.fraction, now.played, now.total, Modifier.fillMaxWidth())
    }
}

/**
 * A music video full screen ([video] draws it; while it isn't [ready] the song's cover stands in). Any key shows
 * [TvVideoControls]; they fade [TV_VIDEO_CONTROLS_MS] after the last key while the video plays (paused, they stay).
 * Back hides them; with them hidden, Back is the page's (back to the song). [panelOpen]: a side panel ([panel], the
 * queue or ⋯) is over the screen and has focus; when it closes, focus returns to the control that opened it.
 * [panelCovers]: the panel covers the whole screen (the lyrics over the video), so the controls hide under it.
 */
@Composable
fun TvVideoPlace(
    now: TvNow,
    status: TvVideoStatus,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onSong: () -> Unit,
    onMore: () -> Unit,
    panelOpen: Boolean = false,
    panelCovers: Boolean = false,
    panel: @Composable BoxScope.() -> Unit = {},
    video: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(true) }
    // The control that had focus last (kept while Lyrics are open over the video, so coming back lands there).
    var last by rememberSaveable { mutableStateOf(TvVideoControl.PLAYPAUSE) }
    // A control waiting to take focus as soon as it's placed (the controls just came back, a panel closed).
    var wanted by remember { mutableStateOf<TvVideoControl?>(last) }
    var touched by remember { mutableLongStateOf(0L) }
    // A key whose key-down showed the controls: its key-up goes too (a button under it would click on key-up).
    var swallowUp by remember { mutableStateOf<Key?>(null) }
    var screenFocused by remember { mutableStateOf(false) }
    val screen = remember { FocusRequester() }
    val requesters = remember { TvVideoControl.entries.associateWith { FocusRequester() } }
    val available = tvVideoControls(now.hasLyrics, seekable = now.total.isNotEmpty())
    val availableNow by rememberUpdatedState(available)
    val hide: () -> Unit = {
        // Focus first goes to the screen, then the controls fade: it's never left on one fading away.
        runCatching { screen.requestFocus() }
        shown = false
        wanted = null
    }
    val show: () -> Unit = { shown = true; wanted = last }

    androidx.compose.runtime.LaunchedEffect(touched, now.playing, shown, panelOpen) {
        if (shown && now.playing && !panelOpen) {
            delay(TV_VIDEO_CONTROLS_MS)
            hide()
        }
    }
    // A panel closed: the controls are back, with the button that opened it focused.
    var wasOpen by remember { mutableStateOf(panelOpen) }
    LaunchedEffect(panelOpen) {
        if (wasOpen && !panelOpen) { shown = true; wanted = last; touched = System.nanoTime() }
        wasOpen = panelOpen
    }
    // Puts focus on the wanted control once it's placed (a few frames after the controls appear).
    LaunchedEffect(wanted, shown, panelOpen) {
        val w = wanted ?: return@LaunchedEffect
        if (!shown || panelOpen) return@LaunchedEffect
        val target = tvVideoFocusTarget(w, availableNow)
        repeat(40) {
            if (runCatching { requesters.getValue(target).requestFocus() }.getOrDefault(false)) { wanted = null; return@LaunchedEffect }
            delay(50)
        }
        wanted = null
    }
    // Back with the controls up hides them (as TV players do); with them hidden Back leaves (the shell's).
    BackHandler(enabled = shown && !panelOpen) { hide() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag(TAG_VIDEO)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp) {
                    if (swallowUp == e.key) { swallowUp = null; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touched = System.nanoTime()
                if (panelOpen || e.key == Key.Back) return@onPreviewKeyEvent false
                // Hidden: the key only brings the controls back.
                if (!shown) { show(); swallowUp = e.key; return@onPreviewKeyEvent true }
                // Shown but the screen still has focus (they're just coming back): focus goes onto them.
                if (screenFocused) { wanted = last; swallowUp = e.key; return@onPreviewKeyEvent true }
                false
            },
    ) {
        // The screen: holds focus while the controls are hidden (focus is never nowhere, never on hidden controls).
        Box(
            Modifier.fillMaxSize()
                .focusProperties { canFocus = !panelOpen }
                .focusRequester(screen)
                .onFocusChanged { screenFocused = it.isFocused }
                .testTag(TAG_VIDEO_SCREEN)
                .focusable(),
        ) {
            // The video's view stays for the whole page (it used to come and go with the video mode, and on a TV a view
            // put back mid-song could wait long for a picture); the cover is over it until it has one.
            video()
            // Once a picture has shown, the cover comes back only if the next one is slow (a seek in what's loaded
            // doesn't flash it).
            var hadPicture by remember { mutableStateOf(false) }
            LaunchedEffect(status) { if (status == TvVideoStatus.PICTURE) hadPicture = true }
            AnimatedVisibility(
                status != TvVideoStatus.PICTURE,
                enter = fadeIn(tween(250, delayMillis = if (hadPicture) COVER_AFTER_PICTURE_MS else 0)),
                exit = fadeOut(tween(300)),
            ) { TvVideoCover(now, status) }
        }
        AnimatedVisibility(shown && !(panelOpen && panelCovers), Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            TvVideoControls(
                now, onPlayPause, onNext, onPrevious, onSong,
                onSeek = onSeek, onLyrics = onLyrics, onQueue = onQueue, onMore = onMore,
                enabled = shown && !panelOpen, requesters = requesters,
                onFocused = { last = it }, entry = null,
            )
        }
        if (panelOpen) panel()
    }
}

/** The panels over the video (the lyrics over all of it). */
private enum class TvVideoPanel { NONE, QUEUE, MORE, LYRICS }

/**
 * A music video, full screen, on the engine. Opening the page turns video mode on (and again when coming back to it
 * from an album or Sing); leaving it ends video mode, the song plays on as audio. Lyrics open over the video, which
 * plays on under them. The queue is the player's panel: a song picked plays as its video.
 */
@Composable
fun TvVideoPage(nav: TvNav) {
    val c = LocalContainer.current
    val now = rememberTvNow(c)
    val video by c.player.videoMode.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val loading by c.player.videoLoading.collectAsState()
    val failedFor by c.player.videoFailedFor.collectAsState()
    val frames = rememberTvVideoFrames(c.player.player)
    val context = LocalContext.current
    LaunchedEffect(Unit) { TvSing.load(context) }
    val singReady = TvSing.state.collectAsState().value.sings
    DisposableEffect(Unit) { onDispose { c.player.setVideoMode(false) } }
    // Each song on this page plays as its video: asked at once for the first, and for each one after once the
    // listener stops skipping (a skip cancels the wait, so skip-skip-skip asks only for the song it ends on). The
    // service usually has it lined up already ("Keep playing videos"); this is for when it hadn't (that setting off, a
    // lookup that failed as the song started). Not again for a song whose video failed: it says so instead.
    val firstSong = remember { song?.id }
    LaunchedEffect(song?.id) {
        val id = song?.id ?: return@LaunchedEffect
        if (id != firstSong) delay(TV_VIDEO_SETTLE_MS)
        if (!c.player.videoMode.value && !c.player.videoLoading.value && c.player.videoFailedFor.value != id) c.player.setVideoMode(true)
    }
    // A song that stays as its song (no video, or it failed): said after the ask above has had time to answer.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(song?.id, video, loading) {
        settled = false
        if (!video && !loading) { delay(TV_VIDEO_SETTLE_MS * 2); settled = true }
    }
    if (now == null) { TvStarting { nav.back() }; return }
    val status = tvVideoStatus(video, frames.shown, loading, failed = failedFor != null && failedFor == song?.id, settled = settled)
    var panel by rememberSaveable { mutableStateOf(TvVideoPanel.NONE) }
    val close: () -> Unit = { panel = TvVideoPanel.NONE }
    TvVideoPlace(
        now, status,
        onPlayPause = { c.player.togglePlay() },
        onNext = { c.player.next() },
        onPrevious = { c.player.previous() },
        onSeek = { ms -> c.player.seekBy(ms) },
        onLyrics = { panel = TvVideoPanel.LYRICS },
        onQueue = { panel = TvVideoPanel.QUEUE },
        onSong = { nav.back() },
        onMore = { panel = TvVideoPanel.MORE },
        panelOpen = panel != TvVideoPanel.NONE,
        panelCovers = panel == TvVideoPanel.LYRICS,
        panel = {
            when (panel) {
                TvVideoPanel.NONE -> Unit
                TvVideoPanel.LYRICS -> TvVideoLyrics(close)
                // A song picked plays (as its video) and the panel closes, so it's watched.
                TvVideoPanel.QUEUE -> TvPanel("queue", close) { TvQueuePlace(onPicked = close) }
                TvVideoPanel.MORE -> TvPanel("more", close) { TvActionPanel(song?.title ?: "", songActions(c, nav, song, singReady, close)) }
            }
        },
    ) {
        TvVideoSurface(Modifier.fillMaxSize())
    }
}

/**
 * The TV's video: the player on a SurfaceView ([R.layout.surface_player]), not the phone's TextureView
 * ([com.prism.music.ui.player.MusicVideoSurface], which composes the picture into the window so it can fade). On a SurfaceView the picture goes
 * to the display's own video layer under the window, so what's drawn over it (the lyrics and their scrim, the controls,
 * the cover) is drawn again only when it changes, not for every frame of the video. On the owner's Google TV a playing
 * video with lyrics over it ran at ~47 fps on the TextureView (111 of 474 frames missed in 10 s): each video frame drew
 * the whole window again, the lyrics' faded list layer with it. The shutter is see-through and the last picture is kept
 * on a reset, so a song change or the lyrics opening never flashes black; the cover covers it while there's none.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TvVideoSurface(modifier: Modifier = Modifier) {
    val pc = LocalContainer.current.player
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.surface_player, null) as androidx.media3.ui.PlayerView).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = pc.player
            }
        },
        update = { view -> if (view.player !== pc.player) view.player = pc.player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** What the video screen shows behind its controls. */
enum class TvVideoStatus {
    /** The video's own picture. */
    PICTURE,
    /** The cover with a small spinner: the video is on its way (it's starting, or a skip or seek is loading). */
    LOADING,
    /** The cover and "No music video for this song": the song plays as itself. */
    NO_VIDEO,
    /** The cover and "The music video didn't load": the song plays as itself. */
    FAILED,
}

/**
 * What the screen shows: the picture once the video ([videoItem]: the item playing is its video) has put a frame on
 * screen since it started or was last moved ([frames]); the cover with a spinner while it's on its way ([loading],
 * or a song just started and the video may still be lined up: not [settled] yet); else the cover with why.
 */
fun tvVideoStatus(videoItem: Boolean, frames: Boolean, loading: Boolean, failed: Boolean, settled: Boolean): TvVideoStatus = when {
    videoItem && frames -> TvVideoStatus.PICTURE
    videoItem || loading -> TvVideoStatus.LOADING
    failed -> TvVideoStatus.FAILED
    settled -> TvVideoStatus.NO_VIDEO
    else -> TvVideoStatus.LOADING
}

/** The cover over the video while it has no picture, with a spinner (on its way) or the reason (none, or failed). */
@Composable
private fun TvVideoCover(now: TvNow, status: TvVideoStatus) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(TAG_VIDEO_COVER), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(now.art, Modifier.size(360.dp), shape = TvShapes.art, size = 1080)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    TvVideoStatus.LOADING -> com.prism.music.ui.components.PrismSpinner(Modifier.size(28.dp).testTag(TAG_VIDEO_SPINNER))
                    TvVideoStatus.NO_VIDEO -> Text(tr(R.string.no_music_video_for_this_song), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                    TvVideoStatus.FAILED -> Text(tr(R.string.tv_video_failed), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                    TvVideoStatus.PICTURE -> Unit
                }
            }
        }
    }
}

/**
 * Frames of the video put on screen since it started or was last moved (a seek, a skip, the item swapped), counted
 * from the player's own frame callback. The cover stands in until there's one, so a picture that's slow to come back
 * shows a spinner, never a stale cover, and the screen knows the moment it's back.
 */
class TvVideoFrames {
    /** Frames since the last reset (the player's thread counts them). */
    val sinceReset = java.util.concurrent.atomic.AtomicInteger(0)
    /** Resets so far (a test checks a seek or skip reset it before the frames it waits for). */
    @Volatile var resets = 0
        private set
    private val _shown = mutableStateOf(false)
    /** A frame has been shown since the last reset (main thread). */
    val shown: Boolean get() = _shown.value

    internal fun reset() { sinceReset.set(0); resets++; _shown.value = false }
    internal fun frame(post: (Runnable) -> Unit) { if (sinceReset.incrementAndGet() == 1) post(Runnable { if (sinceReset.get() > 0) _shown.value = true }) }
}

/** The video screen's frame count, for the TV's in-app tests (null while no video screen is open). */
object TvVideoProbe {
    @Volatile var frames: TvVideoFrames? = null
        internal set
}

/** Counts the frames [player] puts on screen ([TvVideoFrames]), while the video screen is open. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun rememberTvVideoFrames(player: androidx.media3.exoplayer.ExoPlayer?): TvVideoFrames {
    val frames = remember { TvVideoFrames() }
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose {}
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val onFrame = androidx.media3.exoplayer.video.VideoFrameMetadataListener { _, _, _, _ -> frames.frame { main.post(it) } }
        val moves = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = frames.reset()
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int,
            ) {
                // A seek, or a skip within an item; moving on to the next item is a transition (above).
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK || reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                    reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP || reason == androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
                ) frames.reset()
            }
        }
        p.setVideoFrameMetadataListener(onFrame)
        p.addListener(moves)
        TvVideoProbe.frames = frames
        onDispose {
            p.clearVideoFrameMetadataListener(onFrame)
            p.removeListener(moves)
            if (TvVideoProbe.frames === frames) TvVideoProbe.frames = null
        }
    }
    return frames
}

/** After a song starts, how long the screen waits (for the service to line up its video, or more skips) before asking. */
const val TV_VIDEO_SETTLE_MS = 1_500L
/** With a picture shown before, how long a new one may take before the cover (and spinner) comes back over it. */
private const val COVER_AFTER_PICTURE_MS = 600

/** Test tags: the video's controls (their bar), the screen under them (focus while they're hidden), the cover over it. */
const val TAG_VIDEO_CONTROLS = "tv_video/controls"
const val TAG_VIDEO_SCREEN = "tv_video/screen"
const val TAG_VIDEO_COVER = "tv_video/cover"
const val TAG_VIDEO_SPINNER = "tv_video/spinner"

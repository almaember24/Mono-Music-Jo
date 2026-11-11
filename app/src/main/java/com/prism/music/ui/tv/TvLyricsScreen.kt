package com.prism.music.ui.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.LyricsGapMark
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.player.RestingLine
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.SweptLine
import com.prism.music.ui.player.drawNote
import com.prism.music.ui.player.extraStyle
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalLyricsFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/*
 * Lyrics on the TV (1.8.0, after the owner asked for karaoke-style lyrics): the phone's lyrics page on a big screen. The
 * lines scroll as one list, the sung line gliding into place (only the list's offset moves); the sung line is drawn by
 * the phone's own SungLine with the lyrics' weight-axis typeface, so "Grows bolder" swells word by word; background
 * vocals, the romanized line and the translation sit under each line as the listener has them; instrumental breaks
 * have their mark; the Lyrics look's Glow, Shadow and Bold card come along (Glow's blur only on Android 12 and later,
 * as the phone).
 */

/** The lyrics' typeface for the weight axis (Settings' lyrics font, else the app's), or null for one without it. */
fun tvLyricsAxisChoice(s: AppSettings): FontChoice? =
    if (s.lyricsFont.isEmpty()) s.fontChoice else FontChoice.entries.firstOrNull { it.name == s.lyricsFont }

@Composable
private fun rememberTvLyricsAxis(s: AppSettings): FontFamily? {
    val choice = tvLyricsAxisChoice(s)
    return remember(choice) { choice?.let(AppFonts::axis) }
}

/** The lines' brightness by how far they are from the sung one, times the look's brightness for others (the phone's). */
private val FALLOFF = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)

/** How lines settle (the phone's lyrics page): the glide, brightness and size, over half a second. */
private val SETTLE_EASING = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.6f, 0.25f, 1f)
private const val SETTLE_MS = 500
private const val GLIDE_MS = SETTLE_MS

/** Lines not sung, a little smaller (the phone's, when the look moves). */
private const val RESTING_SCALE = 0.98f

/** The lines' fade at the list's edges: from nothing at the top to full at this share down, and back from this one. */
private const val EDGE_FADE_TOP = 0.12f
private const val EDGE_FADE_BOTTOM = 0.86f

/** Where the sung line settles: this share of the screen down. */
private const val SETTLE_AT = 0.3f

/**
 * Lyrics (TV): the song's lyrics large across the screen, the cover and title small in the corner, the progress at the
 * foot. [lyrics]' sung line ([TvLyrics.current]) settles a third of the way down; a line [picked] with the remote is
 * lit and comes there instead. [roman] (line for line, null where none) and [translations] go under each line;
 * [romanInstead]: the romanized line is sung in the line's place. [overVideo]: drawn over a playing music video, on a
 * soft dark scrim instead of the cover's backdrop (and no Bold card, which would hide the video).
 */
@Composable
fun TvLyricsScreen(
    now: TvNow,
    lyrics: TvLyrics,
    clock: State<Long>,
    modifier: Modifier = Modifier,
    picked: Int? = null,
    accent: Color? = null,
    roman: List<LyricLine?>? = null,
    romanInstead: Boolean = false,
    translations: List<String?>? = null,
    /** Puts focus back on the lines (after Back to now, or the timing closing). */
    linesFocus: androidx.compose.ui.focus.FocusRequester? = null,
    overVideo: Boolean = false,
    /** Where Right from the lines goes (the buttons' first, top right). */
    toButtons: androidx.compose.ui.focus.FocusRequester? = null,
    /** The title and the progress shown (over a video they fade while the remote is put down; the lines stay). */
    chrome: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val settings = LocalAppSettings.current
    val look = rememberLyricsLook(settings, now.art)
    val axis = rememberTvLyricsAxis(settings)
    val scale = settings.tv.lyricsScale * look.sizeScale
    val centred = settings.lyricsAlign == LyricsAlign.CENTER
    val onCard = look.card != null && !overVideo
    val style = TextStyle(
        fontSize = 40.sp * scale, lineHeight = 52.sp * scale, fontWeight = FontWeight(look.weight),
        // The phone's: the lyrics font when one is picked, else the app's body font (the titles' font can be a heavy one,
        // which drew every line bold).
        fontFamily = LocalLyricsFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        textDirection = androidx.compose.ui.text.style.TextDirection.Content,
    )
    Box(modifier.fillMaxSize().testTag(TAG_LYRICS)) {
        if (overVideo) TvVideoScrim()
        else {
            TvMeshBackdrop(now.art)
            // Bold card: the lines on the cover's own color, as the phone's lyrics page.
            look.card?.let { card -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(card, lerp(card, Color.Black, 0.35f))))) }
        }
        TvLyricsChrome(chrome) {
            Row(Modifier.padding(TvSafe).testTag("$TAG_LYRICS/title"), verticalAlignment = Alignment.CenterVertically) {
                Artwork(now.art, Modifier.size(56.dp), shape = RoundedCornerShape(8.dp), size = 160)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(now.title, style = MaterialTheme.typography.titleMedium, color = scheme.onBackground, maxLines = 1)
                    Text(now.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        // On its own: the song's progress moving (twice a second) redraws the header and the foot, not the lines.
        TvLyricsList(
            lyrics.lines, lyrics.current, picked, clock, style, look, axis, centred, onCard,
            note = settings.lyricsGapMark == LyricsGapMark.NOTE, roman, romanInstead, translations, linesFocus, toButtons,
        )
        TvLyricsChrome(chrome, Modifier.align(Alignment.BottomCenter)) {
            TvProgress(now.fraction, now.played, now.total, Modifier.fillMaxWidth().padding(TvSafe).testTag("$TAG_LYRICS/progress"))
        }
    }
}

/**
 * The lyrics' title, progress and buttons while [shown]: they fade in and out (at once with Reduce motion), and are
 * gone from the screen once faded.
 */
@Composable
private fun TvLyricsChrome(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ms = if (LocalAppSettings.current.reduceMotion) 0 else TV_LYRICS_CHROME_FADE_MS
    androidx.compose.animation.AnimatedVisibility(
        shown, modifier,
        enter = androidx.compose.animation.fadeIn(tween(ms)), exit = androidx.compose.animation.fadeOut(tween(ms)),
    ) { content() }
}

/** Over a music video, how long the lyrics' title, progress and Timing stay after the last key while the song plays. */
const val TV_LYRICS_CHROME_MS = 2_000L
private const val TV_LYRICS_CHROME_FADE_MS = 400

/**
 * The lines, as the phone's lyrics page moves them: the sung (or [picked]) line glides into place with one animated
 * offset, and each line eases to its brightness and size in its own layer, so nothing is composed again frame by frame;
 * only the sung line is redrawn as it's sung.
 */
@Composable
private fun TvLyricsList(
    lines: List<LyricLine>,
    current: Int,
    picked: Int?,
    clock: State<Long>,
    style: TextStyle,
    look: com.prism.music.ui.player.LyricsLook,
    axis: FontFamily?,
    centred: Boolean,
    onCard: Boolean,
    note: Boolean,
    roman: List<LyricLine?>?,
    romanInstead: Boolean,
    translations: List<String?>?,
    linesFocus: androidx.compose.ui.focus.FocusRequester?,
    toButtons: androidx.compose.ui.focus.FocusRequester?,
) {
    TvLyricsCounts.lists++
    val target = picked ?: current
    val list = rememberLazyListState(initialFirstVisibleItemIndex = target.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
    // The line comes to the settle point: gliding when it's on screen (the next line, mostly), at once from further.
    LaunchedEffect(target, lines) {
        if (target !in lines.indices) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
        val shown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        // A scroll lays the list out where it runs: always on the main thread (the in-app tests' frame clock resumed it
        // on another, and the next draw failed: "multithreaded access to SnapshotStateObserver"); no hop in the app.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (shown != null) list.animateScrollBy(shown.offset.toFloat(), tween(GLIDE_MS, easing = SETTLE_EASING))
            else list.scrollToItem(target)
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(start = 96.dp, end = 160.dp, top = 96.dp, bottom = 88.dp)
            .then(if (linesFocus != null) Modifier.focusRequester(linesFocus) else Modifier)
            .tvItem("$TAG_LYRICS/lines", entry = true)
            .then(if (toButtons != null) Modifier.focusProperties { right = toButtons } else Modifier)
            .focusable(),
    ) {
        val settle = maxHeight * SETTLE_AT
        LazyColumn(
            state = list,
            userScrollEnabled = false,
            // Lines fade out at the top and bottom edges (as on the phone), never cut across. The fade is masked only
            // over those two bands (in between the mask would keep every pixel as it is), with its brushes made once per
            // size: the sung line redraws this layer every frame, over a video too.
            modifier = Modifier.fillMaxSize().testTag("$TAG_LYRICS/list")
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithCache {
                    val topEnd = size.height * EDGE_FADE_TOP
                    val bottomStart = size.height * EDGE_FADE_BOTTOM
                    val topFade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = topEnd)
                    val bottomFade = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = bottomStart, endY = size.height)
                    val dstIn = androidx.compose.ui.graphics.BlendMode.DstIn
                    onDrawWithContent {
                        drawContent()
                        drawRect(topFade, size = androidx.compose.ui.geometry.Size(size.width, topEnd), blendMode = dstIn)
                        drawRect(bottomFade, topLeft = Offset(0f, bottomStart), size = androidx.compose.ui.geometry.Size(size.width, size.height - bottomStart), blendMode = dstIn)
                    }
                },
            contentPadding = PaddingValues(top = settle, bottom = maxHeight),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                val active = i == current
                if (line.isGap) {
                    TvGapMark(line, lines.getOrNull(i + 1)?.timeMs ?: line.endMs, clock, active, centred, note = note, modifier = Modifier.testTag("$TAG_LYRICS/gap/$i"))
                    return@itemsIndexed
                }
                // The phone's: lines dim with distance from the sung one, at the look's brightness for others, and breathe
                // back a little when the look moves; both eased, read only in the line's layer.
                val distance = if (current < 0) 3 else abs(i - current)
                val alpha = androidx.compose.animation.core.animateFloatAsState(
                    when {
                        active -> 1f
                        i == picked -> 0.95f
                        else -> FALLOFF[distance.coerceAtMost(FALLOFF.lastIndex)] * look.othersAlpha
                    }.coerceIn(0f, 1f),
                    tween(SETTLE_MS, easing = SETTLE_EASING), label = "alpha",
                )
                val size = androidx.compose.animation.core.animateFloatAsState(
                    if (active || i == picked || look.motion == com.prism.music.data.prefs.LyricsMotion.NONE) 1f else RESTING_SCALE,
                    tween(SETTLE_MS, easing = SETTLE_EASING), label = "size",
                )
                TvLyricLine(
                    line, i, active, i == picked, clock, style, look, axis, onCard,
                    roman?.getOrNull(i), romanInstead, translations?.getOrNull(i),
                    Modifier.graphicsLayer {
                        this.alpha = alpha.value
                        // The alpha on each thing drawn, not the line drawn offscreen first (that was every dimmed line,
                        // every frame: the Android TV 11 run went from 37% to 49% janky frames with it).
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        scaleX = size.value; scaleY = size.value
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (centred) 0.5f else 0f, 0.5f)
                    },
                )
            }
        }
    }
}

/** How often the lyrics' parts are composed (tests: the lines aren't composed again frame by frame). */
internal object TvLyricsCounts {
    @Volatile var lists = 0
    @Volatile var lines = 0
    /** The title, progress and buttons are shown (the in-app tests read it while lyrics play over a video). */
    @Volatile var chrome = true
}

/**
 * Over a music video: the picture darkened just enough for the lines to read, a little more at the top and foot (the
 * title, the progress), so the video still shows through.
 */
@Composable
private fun TvVideoScrim() {
    // Settings → Look → Background (1.8.1): the video more see-through, or darker.
    val k = com.prism.music.data.prefs.TvPrefs.videoShade(LocalAppSettings.current.tv.backdropDim)
    fun a(v: Float) = Color.Black.copy(alpha = (v * k).coerceAtMost(0.95f))
    Box(
        Modifier.fillMaxSize().testTag("$TAG_LYRICS/scrim").background(
            Brush.verticalGradient(0f to a(0.7f), 0.3f to a(0.5f), 0.75f to a(0.5f), 1f to a(0.8f)),
        ),
    )
}

/**
 * One line of the lyrics: sung or resting, with its background vocals, romanized line and translation under it. Its
 * brightness is [settle]'s (a layer), so the parts here are drawn at their own share of it.
 */
@Composable
private fun TvLyricLine(
    line: LyricLine,
    index: Int,
    active: Boolean,
    picked: Boolean,
    clock: State<Long>,
    style: TextStyle,
    look: com.prism.music.ui.player.LyricsLook,
    axis: FontFamily?,
    onCard: Boolean,
    romanLine: LyricLine?,
    romanInstead: Boolean,
    translated: String?,
    settle: Modifier,
) {
    TvLyricsCounts.lines++
    val alpha = 1f
    val shown = remember(line, romanLine, romanInstead, look.uppercase) { look.shown(if (romanInstead && romanLine != null) romanLine else line) }
    val extraInk = if (onCard) Color.Black else look.extraColor
    Column(
        Modifier.fillMaxWidth().then(settle).testTag("$TAG_LYRICS/line/$index")
            .then(if (picked) Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp * look.spacing),
    ) {
        val translation = @Composable {
            translated?.takeUnless { it.equals(line.text.trim(), ignoreCase = true) }?.let { t ->
                Text(t, style = look.extraStyle(style, 0.52f, 0.56f), color = extraInk.copy(alpha = if (active) 0.8f else alpha * 0.75f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
        val romanised = @Composable {
            if (!romanInstead && romanLine != null) {
                val r = look.shown(romanLine)
                val rs = look.extraStyle(style, 0.56f, 0.62f)
                if (active && r.isWordSynced) SweptLine(r, clock, rs, Modifier.fillMaxWidth().padding(vertical = 4.dp), litBrush = look.sung)
                else Text(r.text, style = rs, color = extraInk.copy(alpha = if (active) 0.85f else alpha * 0.8f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
        if (look.extraAbove) { romanised(); translation() }
        if (active) SungLine(shown, clock, style, look, axis, Modifier.fillMaxWidth(), onCard = onCard)
        else RestingLine(shown, style, look, alpha, Modifier.fillMaxWidth())
        if (!look.extraAbove) romanised()
        // Background vocals: smaller under the line, lit word by word in their own time (as on the phone).
        shown.backgroundLine?.let { bg ->
            val bs = style.copy(fontSize = style.fontSize * look.backingSize, lineHeight = style.lineHeight * look.backingSize * 1.07f, fontWeight = FontWeight.Normal, shadow = look.shadow)
            val m = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("$TAG_LYRICS/backing/$index")
            if (active) SweptLine(bg, clock, bs, m, litBrush = look.sung)
            else Text(bg.text, style = bs, color = look.others.copy(alpha = alpha * 0.75f), modifier = m)
        }
        if (!look.extraAbove) translation()
    }
}

/**
 * An instrumental break (TV): three dots (or a note, as Settings → Lyrics has it) lighting up as the break goes by,
 * larger while it's the one playing.
 */
@Composable
private fun TvGapMark(line: LyricLine, until: Long, clock: State<Long>, active: Boolean, centred: Boolean, note: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(if (active) 72.dp else 36.dp).padding(horizontal = 10.dp), contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart) {
        Box(
            Modifier.size(width = if (note) 32.dp else 72.dp, height = if (note) 32.dp else 16.dp)
                .graphicsLayer { alpha = if (active) 1f else 0.45f }
                .drawBehind {
                    val span = (until - line.timeMs).coerceAtLeast(1)
                    val through = if (!active) 0f else ((clock.value - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    if (note) {
                        drawNote(Color.White.copy(alpha = 0.35f))
                        clipRect(top = size.height * (1f - through)) { drawNote(Color.White) }
                        return@drawBehind
                    }
                    val r = size.height / 2
                    val stride = (size.width - 2 * r) / 2
                    repeat(3) { d ->
                        val lit = (through * 3 - d).coerceIn(0f, 1f)
                        drawCircle(Color.White.copy(alpha = 0.35f + 0.65f * lit), r, Offset(r + d * stride, size.height / 2))
                    }
                },
        )
    }
}

/**
 * The lyrics page's states around [TvLyricsScreen]: looking for lyrics, none found, or the lines. The lines take the
 * remote: ↑ ↓ pick a line (it lights up and comes into place), OK plays from the picked line (then the lyrics follow
 * the song from there) or pauses and plays with none picked. With a line picked, Back (or "Back to now", or
 * [TV_PICK_RETURN_MS] without a key) goes back to the sung line, following the song again; a second Back leaves.
 * Right reaches the Timing button: the song's lyrics timing, moved live. [buttons] go before Timing (Sing's key) and
 * [status] under them; [overVideo]: over a playing music video ([TvLyricsScreen]'s), where the title, the progress and
 * the buttons fade [TV_LYRICS_CHROME_MS] after the last key while the song plays.
 */
@Composable
fun TvLyricsPlace(
    now: TvNow,
    lines: List<LyricLine>,
    current: Int,
    clock: State<Long>,
    loading: Boolean,
    unavailable: Boolean,
    source: String?,
    accent: Color?,
    onPlayPause: () -> Unit = {},
    roman: List<LyricLine?>? = null,
    romanInstead: Boolean = false,
    translations: List<String?>? = null,
    overVideo: Boolean = false,
    status: String? = null,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    /** The timing's panel, its nudge taking the requester (the tests' stand-in; the song's own timing otherwise). */
    timingPanel: @Composable androidx.compose.foundation.layout.BoxScope.(androidx.compose.ui.focus.FocusRequester) -> Unit = { TvTimingPanel(it) },
    onSeek: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // D45 (1.8.3): the pick goes with the lyrics' words, so the same lyrics coming again (a new list of the same lines)
    // keep it; and lyrics with no timing have no sung line to go back to, so their pick stays until it's moved.
    val words = remember(lines) { lines.map { it.text } }
    val untimed = remember(lines) { lines.none { it.timeMs >= 0 } }
    var picked by remember(words) { mutableStateOf<Int?>(null) }
    var timing by remember { mutableStateOf(false) }
    var lastKey by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val linesFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val buttonsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Back to following the song: the lines take focus back (the chip or the timing may have had it).
    val follow: () -> Unit = { picked = null; runCatching { linesFocus.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = picked != null && !timing) { follow() }
    // "Back to now" has focus: the release check found it taken away with focus on it when the pick ran out.
    var nowFocused by remember { mutableStateOf(false) }
    LaunchedEffect(picked, lastKey, untimed) {
        if (picked == null || untimed) return@LaunchedEffect
        delay(TV_PICK_RETURN_MS)
        // The button goes with the pick: focus onto the lines first, never left on nothing.
        if (nowFocused) runCatching { linesFocus.requestFocus() }
        picked = null
    }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (overVideo) TvVideoScrim() else TvMeshBackdrop(now.art)
            Text(
                tr(if (loading || !unavailable) R.string.looking_for_lyrics else R.string.no_lyrics_found_for_this_song),
                // The lines' own tag: when they come (or go, as the song changes), focus stays on the lyrics.
                Modifier.tvItem("$TAG_LYRICS/lines", entry = true).focusProperties { right = buttonsFocus }.focusable(),
                style = MaterialTheme.typography.headlineMedium, color = scheme.onSurfaceVariant,
            )
            // Sing's key and how the song's getting ready, with no lyrics too.
            Column(Modifier.align(Alignment.TopEnd).padding(TvSafe), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.focusRequester(buttonsFocus).focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
                status?.let { Text(it, Modifier.width(560.dp).testTag("$TAG_LYRICS/status"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.End) }
            }
        }
        return
    }
    // Over a music video (the owner's TV: they "should disappear after a second or two"): the title, the progress and
    // the buttons fade TV_LYRICS_CHROME_MS after the last key while the song plays, as the video's own controls do; the
    // lines stay. A key then only brings them back; paused, they stay. On the lyrics page they always show.
    val autoHide = overVideo
    var chrome by remember { mutableStateOf(true) }
    var touched by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // A key whose key-down brought them back: its repeats and key-up go too (a button would click on key-up).
    var swallow by remember { mutableStateOf<Key?>(null) }
    var buttonsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(now.playing) { if (!now.playing) chrome = true }
    LaunchedEffect(autoHide, chrome, now.playing, timing, touched) {
        if (!autoHide || !chrome || !now.playing || timing) return@LaunchedEffect
        delay(TV_LYRICS_CHROME_MS)
        // Focus first onto the lines, then the buttons fade: it's never left on one going away.
        if (buttonsFocused) runCatching { linesFocus.requestFocus() }
        chrome = false
    }
    androidx.compose.runtime.SideEffect { TvLyricsCounts.chrome = chrome }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (!autoHide) return@onPreviewKeyEvent false
            if (swallow == e.key) { if (e.type == KeyEventType.KeyUp) swallow = null; return@onPreviewKeyEvent true }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            touched = System.nanoTime()
            // Hidden: the key only brings them back (Back still closes the lyrics).
            if (!chrome && e.key != Key.Back) { chrome = true; swallow = e.key; true } else false
        },
    ) {
        // The clock is read only where words are drawn (not here, or the whole page would be redrawn every frame).
        TvLyricsScreen(
            now, TvLyrics(lines, current, 0L, source), clock, picked = picked, accent = accent,
            roman = roman, romanInstead = romanInstead, translations = translations, linesFocus = linesFocus, overVideo = overVideo,
            toButtons = buttonsFocus, chrome = chrome,
            modifier = Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || timing) return@onPreviewKeyEvent false
                lastKey = System.nanoTime()
                when (e.key) {
                    Key.DirectionUp -> { picked = ((picked ?: current) - 1).coerceAtLeast(0); true }
                    Key.DirectionDown -> { picked = ((picked ?: current) + 1).coerceAtMost(lines.lastIndex); true }
                    Key.DirectionCenter, Key.Enter -> {
                        val p = picked
                        if (p != null) { onSeek(lines[p].timeMs); picked = null } else onPlayPause()
                        true
                    }
                    else -> false
                }
            },
        )
        // Top right: "Back to now" while a line is picked, and Timing. Right from the lyrics reaches them.
        Column(Modifier.align(Alignment.TopEnd).padding(TvSafe), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A group: Right from the lines lands on its first button. It stays while the buttons in it fade (Right
            // from the lines names it).
            Row(Modifier.focusRequester(buttonsFocus).onFocusChanged { buttonsFocused = it.hasFocus }.focusGroup()) {
                TvLyricsChrome(chrome) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Fading out, they can't take focus.
                        val canFocus = Modifier.focusProperties { canFocus = chrome }
                        if (picked != null) TvButton(
                    tr(R.string.tv_back_to_now), follow,
                    canFocus.onFocusChanged { nowFocused = it.isFocused }.tvItem("$TAG_LYRICS/now"), icon = com.prism.music.ui.theme.PrismIcons.Lyrics,
                )
                        buttons()
                        TvButton(tr(R.string.lyrics_timing), { timing = true }, canFocus.tvItem("$TAG_LYRICS/timing"), icon = com.prism.music.ui.theme.PrismIcons.Timer)
                    }
                }
            }
            status?.let { TvLyricsChrome(chrome) { Text(it, Modifier.width(560.dp).testTag("$TAG_LYRICS/status"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.End) } }
        }
        if (timing) {
            androidx.activity.compose.BackHandler { timing = false; runCatching { linesFocus.requestFocus() } }
            val nudge = remember { androidx.compose.ui.focus.FocusRequester() }
            timingPanel(nudge)
            // Focus onto the nudge as soon as it's placed (it isn't the screen's first item: the screen is open already,
            // so it wouldn't take focus itself, and focus stayed on Timing; the Google TV run).
            LaunchedEffect(Unit) {
                repeat(40) { if (runCatching { nudge.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(50) }
            }
        }
    }
}

/**
 * The song's lyrics timing, over the lyrics (TV): how far they're moved ("In sync", "+0.4 s · lyrics earlier"), and
 * the remote's keys for it: ← → 0.1 s (held, faster), ↑ ↓ 1 s, OK back to in sync. The lines move as it changes, so it
 * can be set by ear. Back closes it.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TvTimingPanel(focus: androidx.compose.ui.focus.FocusRequester) {
    val c = LocalContainer.current
    val pc = c.player
    val offset by pc.lyricOffset.collectAsState()
    val video by pc.videoMode.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).width(640.dp)
            .tvGlass(TvShapes.panel, TvPanelFill).padding(horizontal = 32.dp, vertical = 24.dp).testTag("$TAG_LYRICS/timingpanel"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr(R.string.lyrics_timing), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        Text(tr(if (video) R.string.for_this_song_s_music_video else R.string.for_this_song), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val repeats = e.nativeKeyEvent.repeatCount
                    // The timing as it is now, not as last drawn: presses faster than the screen redraws each count.
                    val now = pc.lyricOffset.value
                    when (e.key) {
                        Key.DirectionRight -> { pc.setLyricOffset(TvLyricTiming.nudged(now, TvLyricTiming.stepFor(repeats))); true }
                        Key.DirectionLeft -> { pc.setLyricOffset(TvLyricTiming.nudged(now, -TvLyricTiming.stepFor(repeats))); true }
                        Key.DirectionUp -> { pc.setLyricOffset(TvLyricTiming.nudged(now, TvLyricTiming.BIG_STEP)); true }
                        Key.DirectionDown -> { pc.setLyricOffset(TvLyricTiming.nudged(now, -TvLyricTiming.BIG_STEP)); true }
                        Key.DirectionCenter, Key.Enter -> { pc.setLyricOffset(0L); true }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .tvItem("$TAG_LYRICS/nudge")
                .focusable(interactionSource = interaction)
                .then(if (focused) Modifier.border(TV_RING_THIN, tvRing(), TvShapes.card) else Modifier)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (offset == 0L) tr(R.string.in_sync) else tr(if (offset > 0) R.string.lyrics_earlier else R.string.lyrics_later, TvLyricTiming.shown(offset)),
                style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface,
            )
        }
        Text(tr(R.string.tv_timing_keys, tr(R.string.reset)), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
    }
}
/** How many times a song's translation is asked for while its lyrics stay open (Google can be busy). */
private const val TRANSLATE_TRIES = 3

/**
 * The lyrics' translation into the listener's language, line for line, when Settings → Lyrics has translations on and the
 * song isn't in that language already; null otherwise (or until it comes).
 */
@Composable
fun rememberTvTranslations(lines: List<LyricLine>): List<String?>? {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val to = s.lyricsTranslateTo.ifBlank { com.prism.music.ui.player.defaultTranslateTarget() }
    val translation by produceState<com.prism.music.data.lyrics.LyricsTranslator.Translation?>(null, lines, s.lyricsTranslate, to) {
        value = null
        if (!s.lyricsTranslate || lines.isEmpty()) return@produceState
        val texts = lines.map { l -> if (l.isGap || (l.text.startsWith("[") && l.text.endsWith("]"))) "" else l.text }
        for (attempt in 0 until TRANSLATE_TRIES) {
            val got = runCatching { c.translator.translate(texts, to) }
            if (got.isSuccess) { value = got.getOrNull(); return@produceState }
            if (got.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@produceState
            delay(20_000L shl attempt)
        }
    }
    return translation?.takeUnless { it.sameLanguage }?.translations
}

/**
 * Lyrics timing on the TV (1.8.0): the phone's own per-song offset ([com.prism.music.playback.PlayerConnection.setLyricOffset],
 * up to [com.prism.music.ui.player.MAX_LYRIC_OFFSET_MS] either way), so the phone and the TV share each song's timing.
 * Positive shows the lyrics earlier.
 */
object TvLyricTiming {
    /** ← → move this much; held, the steps grow (after a few repeats 0.2 s, then 0.5 s). */
    fun stepFor(repeats: Int): Long = when {
        repeats >= 10 -> 500L
        repeats >= 4 -> 200L
        else -> 100L
    }

    /** ↑ ↓ move a whole second. */
    const val BIG_STEP = 1_000L

    /** [offset] moved [by], held within the phone's reach. */
    fun nudged(offset: Long, by: Long): Long =
        (offset + by).coerceIn(-com.prism.music.ui.player.MAX_LYRIC_OFFSET_MS, com.prism.music.ui.player.MAX_LYRIC_OFFSET_MS)

    /** "+0.4 s", "−1.2 s" (the phone's own way of writing it). */
    fun shown(ms: Long): String = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "%.1f s".format(kotlin.math.abs(ms) / 1000f)
}

/** After this long with no key, a picked line lets go and the lyrics follow the song again. */
const val TV_PICK_RETURN_MS = 4_000L

/**
 * The playing song's lyrics from the shared lyrics engine, in the listener's lyrics style, with the romanized line and
 * the translation as Settings → Lyrics has them: the lyrics page's, and over a music video ([overVideo]). With nothing
 * playing, [onGone] after a moment.
 */
@Composable
fun TvSongLyrics(onGone: () -> Unit, overVideo: Boolean = false) {
    val c = LocalContainer.current
    val song by c.player.currentSong.collectAsState()
    val now = rememberTvNow(c)
    val s = song
    if (s == null || now == null) { TvStarting(onGone); return }
    val state = com.prism.music.ui.player.rememberLyricsState(s)
    // Made again at each seek, so one while paused still moves the lines (the release check); paused, it stands still
    // rather than looking every frame.
    val jumps by c.player.jumps.collectAsState()
    val clock = androidx.compose.runtime.key(jumps) { com.prism.music.ui.player.rememberLyricClock(now.playing) }
    val accent = rememberTvAccent(now.art)
    val lines = state.lines
    val current by remember(lines) { androidx.compose.runtime.derivedStateOf { com.prism.music.playback.CarLyricsCard.lineAt(lines, clock.value).coerceAtLeast(0) } }
    val roman = com.prism.music.ui.player.rememberRomanized(state)
    val romanInstead = com.prism.music.ui.player.romanizeMode(s.id) == com.prism.music.data.prefs.RomanizeMode.INSTEAD
    val translations = rememberTvTranslations(lines)
    TvLyricsPlace(
        now, lines, current, clock, state.loading, state.unavailable, state.source?.label, accent, onPlayPause = { c.player.togglePlay() },
        roman = roman, romanInstead = romanInstead, translations = translations, overVideo = overVideo,
    ) { ms -> c.player.seekToLyric(ms) }
}

/**
 * Lyrics over a music video (1.8.0, the owner: "the lyrics should play over the video"): the video plays on full screen
 * under them, the lines on a soft scrim; picking a line, Back to now and Timing work as on the lyrics page. Back (with no
 * line picked and the timing closed) closes them, the video playing on. Its own focus memory, under the video's.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.TvVideoLyrics(onClose: () -> Unit) {
    val screen = LocalTvScreen.current + "/lyrics"
    val memory = LocalTvFocusMemory.current
    remember(screen) { memory.opening(screen); true }
    // Before the lyrics' own Back (a picked line, the timing), which come later and so go first.
    androidx.activity.compose.BackHandler { onClose() }
    androidx.compose.runtime.CompositionLocalProvider(LocalTvScreen provides screen) {
        Box(Modifier.matchParentSize().testTag(TAG_VIDEO_LYRICS)) { TvSongLyrics(onClose, overVideo = true) }
    }
}

/** Test tag: the lyrics over a music video. */
const val TAG_VIDEO_LYRICS = "tv_video/lyricsover"
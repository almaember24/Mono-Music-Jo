package com.prism.music.ui.lockscreen

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.prefs.LockLayout
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.withWords
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.rememberHaptics
import com.prism.music.ui.theme.withLyricsFont

/** Test tags of everything that can be touched on the lock screen; [LockScreenTest] checks nothing else can. */
internal object LockTags {
    const val ROOT = "lockScreen"
    const val COVER = "lockCover"
    const val LIKE = "lockLike"
    const val PREVIOUS = "lockPrevious"
    const val PLAY = "lockPlay"
    const val NEXT = "lockNext"
    const val SEEK = "lockSeek"
    const val VOLUME = "lockVolume"
    const val REPEAT = "lockRepeat"
    /** The read-only "Up next" lines (1.8.3, D47): tagged to be found, never touchable. */
    const val UP_NEXT = "lockUpNext"
    val touchable = setOf(COVER, LIKE, PREVIOUS, PLAY, NEXT, SEEK, VOLUME, REPEAT)
}

/** Which layout is showing, for tests (no accessibility service reads it). */
internal val LockLayoutKey = SemanticsPropertyKey<LockLayout>("LockLayout")
private var SemanticsPropertyReceiver.lockLayout by LockLayoutKey

/** Where the sound is going, as a line under the controls. */
internal class LockOutput(val icon: ImageVector, val label: String)

/**
 * What the lock screen shows, read from the player by [LockScreenActivity] (or made up by the settings' examples and the
 * render tests). The playhead and the volume are read through lambdas where they're drawn, so a song playing only redraws
 * two thin lines rather than recomposing the screen.
 */
internal class LockScreenContent(
    val title: String,
    val artist: String,
    /** The cover made frost: the screen's background. Null: plain black (Performance mode, a hidden song). */
    val frost: ImageBitmap?,
    /** Draws the cover in the given shape. */
    val cover: @Composable (Modifier, Shape) -> Unit,
    val coverShape: Shape,
    val playing: Boolean,
    val loading: Boolean = false,
    val liked: Boolean = false,
    val showLike: Boolean = true,
    val showVolume: Boolean = true,
    val output: LockOutput? = null,
    val position: () -> Long = { 0L },
    val durationMs: Long = 0L,
    val seekable: Boolean = durationMs > 0,
    val volume: () -> Float = { 0f },
    val time: String,
    val date: String,
    /** The synced lyrics for the Lyrics layout; null while there are none, and the cover takes their place. */
    val lyrics: (@Composable (Modifier) -> Unit)? = null,
    /** The repeat button's state (a Player.REPEAT_MODE_*), or null for no button (1.8.3, D47). */
    val repeat: Int? = null,
    /** The next songs as "Title · Artist", read only (1.8.3, D47); empty for none. */
    val upNext: List<String> = emptyList(),
)

/** What a touch does: all that can be done while locked. Null on the settings' examples, which can't be touched. */
internal class LockScreenActions(
    val onCover: () -> Unit,
    val onLike: () -> Unit,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onVolume: (Float) -> Unit,
    val onVolumeDone: () -> Unit,
    /** Off → all → one → off, as on the player. */
    val onRepeat: () -> Unit = {},
)

private val LocalLockActions = staticCompositionLocalOf<LockScreenActions?> { null }

/** Below this height (budget phones, the settings' examples) the clock, the cover and the Play disc give up some room. */
private val COMPACT_BELOW = 760.dp
/** Wider than this (a tablet, a phone on its side) the controls stay a phone's width, in the middle. */
private val WIDEST = 480.dp

/** One of the four layouts, filling what it's given. White on dark, whatever the theme: as the player. */
@Composable
internal fun LockScreenLayout(layout: LockLayout, content: LockScreenContent, actions: LockScreenActions?, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLockActions provides actions) {
        BoxWithConstraints(
            modifier.fillMaxSize().background(Color.Black).testTag(LockTags.ROOT).semantics { lockLayout = layout },
            contentAlignment = Alignment.TopCenter,
        ) {
            val compact = maxHeight < COMPACT_BELOW
            when (layout) {
                LockLayout.COVER -> CoverLayout(content, compact)
                LockLayout.FULL -> FullCoverLayout(content, compact, maxHeight)
                LockLayout.LYRICS -> LyricsLayout(content, compact)
                LockLayout.QUIET -> QuietLayout(content, compact)
            }
        }
    }
}

@Composable
private fun CoverLayout(c: LockScreenContent, compact: Boolean) {
    Ground(c.frost, 0.42f)
    Page {
        Clock(c, Alignment.CenterHorizontally, if (compact) 68.sp else 92.sp, Modifier.padding(top = if (compact) 4.dp else 14.dp))
        CoverSpace(c, 300.dp, Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 10.dp else 16.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp)) {
            TitleRow(c, heart = c.showLike)
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            Progress(c, 4.dp, times = true)
            Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
            Transport(c, if (compact) 60.dp else 72.dp, if (compact) 34.dp else 42.dp)
            if (c.showVolume) {
                Spacer(Modifier.height(if (compact) 6.dp else 18.dp))
                Volume(c)
            }
            c.output?.let {
                Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
                Output(it)
            }
            UpNext(c, if (compact) 2 else 3, Modifier.padding(top = if (compact) 8.dp else 16.dp))
        }
        Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
        Unlock()
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
    }
}

@Composable
private fun FullCoverLayout(c: LockScreenContent, compact: Boolean, height: Dp) {
    Ground(c.frost, 0.5f)
    // The cover fills the top of the screen and dissolves into its own frost.
    CoverTap(Modifier.fillMaxWidth().height(min(450.dp, height * 0.56f))) { m ->
        c.cover(
            m.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
            RectangleShape,
        )
    }
    Page {
        Spacer(Modifier.weight(1f))
        Clock(c, Alignment.Start, if (compact) 58.sp else 76.sp, Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(12.dp))
        GlassPane(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = if (compact) 12.dp else 20.dp)) {
                TitleRow(c, heart = c.showLike)
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                Progress(c, 4.dp, times = true)
                Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                Transport(c, if (compact) 60.dp else 72.dp, if (compact) 34.dp else 42.dp)
                if (c.showVolume) {
                    Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
                    Volume(c)
                }
            }
        }
        c.output?.let {
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            Output(it)
        }
        UpNext(c, if (compact) 2 else 3, Modifier.padding(start = 28.dp, end = 28.dp, top = if (compact) 8.dp else 14.dp))
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Unlock()
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
    }
}

@Composable
private fun LyricsLayout(c: LockScreenContent, compact: Boolean) {
    Ground(c.frost, 0.5f)
    Page {
        Clock(c, Alignment.Start, if (compact) 46.sp else 58.sp, Modifier.padding(start = 28.dp, end = 28.dp, top = if (compact) 2.dp else 8.dp))
        if (c.lyrics != null) c.lyrics.invoke(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = if (compact) 10.dp else 16.dp))
        else CoverSpace(c, 300.dp, Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
        GlassPane(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverTap(Modifier.size(58.dp)) { m -> c.cover(m, RoundedCornerShape(12.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.artist.isNotEmpty()) Text(c.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Glyph(PrismIcons.Previous, tr(R.string.widget_previous), 26.dp, 1f, LockTags.PREVIOUS) { it.onPrevious() }
                    Spacer(Modifier.width(2.dp))
                    Disc(c, 52.dp)
                    Spacer(Modifier.width(2.dp))
                    Glyph(PrismIcons.Next, tr(R.string.widget_next), 26.dp, 1f, LockTags.NEXT) { it.onNext() }
                }
                Spacer(Modifier.height(12.dp))
                // Repeat sits at the line's end here, so the title keeps its room on a narrow phone.
                if (c.repeat != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Progress(c, 3.dp, times = false) }
                    Spacer(Modifier.width(6.dp))
                    RepeatGlyph(c, 20.dp)
                } else Progress(c, 3.dp, times = false)
            }
        }
        // The lyrics keep their room: two songs ahead at most.
        UpNext(c, if (compact) 1 else 2, Modifier.padding(start = 28.dp, end = 28.dp, top = if (compact) 8.dp else 12.dp))
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Unlock()
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
    }
}

@Composable
private fun QuietLayout(c: LockScreenContent, compact: Boolean) {
    // Plain black, nothing moving: what Performance mode always uses.
    Page {
        Clock(c, Alignment.CenterHorizontally, if (compact) 80.sp else 104.sp, Modifier.padding(top = if (compact) 40.dp else 96.dp))
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverTap(Modifier.size(72.dp)) { m -> c.cover(m, RoundedCornerShape(14.dp)) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.artist.isNotEmpty()) Text(c.artist, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
            Progress(c, 2.dp, times = false)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                // With repeat, the same room on the left keeps Play in the middle, and it all fits a 360 dp phone.
                val inner = if (c.repeat != null) 16.dp else 24.dp
                if (c.repeat != null) { Spacer(Modifier.width(22.dp + 20.dp)); Spacer(Modifier.width(8.dp)) }
                Glyph(PrismIcons.Previous, tr(R.string.widget_previous), 30.dp, 0.85f, LockTags.PREVIOUS) { it.onPrevious() }
                Spacer(Modifier.width(inner))
                QuietPlay(c)
                Spacer(Modifier.width(inner))
                Glyph(PrismIcons.Next, tr(R.string.widget_next), 30.dp, 0.85f, LockTags.NEXT) { it.onNext() }
                if (c.repeat != null) { Spacer(Modifier.width(8.dp)); RepeatGlyph(c, 22.dp) }
            }
            UpNext(c, 3, Modifier.padding(top = if (compact) 14.dp else 24.dp))
        }
        Spacer(Modifier.height(if (compact) 24.dp else 44.dp))
        Unlock()
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
    }
}

/** The screen's column: inside the system bars, and a phone's width on anything wider. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    // An example has no system bars of its own (the phone's are the wrong size for it): it leaves their room.
    val bars = if (LocalLockActions.current != null) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier.padding(top = 24.dp, bottom = 12.dp)
    Column(Modifier.fillMaxSize().widthIn(max = WIDEST).then(bars), content = content)
}

@Composable
private fun Ground(frost: ImageBitmap?, veil: Float) {
    Box(Modifier.fillMaxSize()) {
        if (frost != null) Image(frost, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = veil * 0.7f),
                    0.5f to Color.Black.copy(alpha = veil),
                    1f to Color.Black.copy(alpha = (veil + 0.18f).coerceAtMost(1f)),
                ),
            ),
        )
    }
}

@Composable
private fun Clock(c: LockScreenContent, align: Alignment.Horizontal, size: TextUnit, modifier: Modifier = Modifier) {
    val textAlign = if (align == Alignment.Start) TextAlign.Start else TextAlign.Center
    Column(modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(c.date, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp, color = Color.White.copy(alpha = 0.72f), textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            c.time, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = size, lineHeight = size * 1.08f),
            fontWeight = FontWeight.ExtraLight, color = Color.White, textAlign = textAlign, maxLines = 1,
        )
    }
}

/** The cover in the room there is, at most [largest] across; gone when a very small window or very large text leaves none. */
@Composable
private fun CoverSpace(c: LockScreenContent, largest: Dp, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val across = min(largest, min(maxWidth - 60.dp, maxHeight))
        if (across >= 96.dp) {
            // The shadow comes from CoverShapes.shadowOf: Flower and Cookie's own outlines cost too much to cast one from.
            Box(Modifier.size(across).shadow(26.dp, com.prism.music.ui.theme.CoverShapes.shadowOf(c.coverShape))) {
                CoverTap(Modifier.fillMaxSize()) { m -> c.cover(m, c.coverShape) }
            }
        }
    }
}

/** A tap on the cover asks to unlock, then opens the full player. */
@Composable
private fun CoverTap(modifier: Modifier, cover: @Composable (Modifier) -> Unit) {
    val actions = LocalLockActions.current
    Box(
        modifier.then(
            if (actions != null) {
                Modifier.testTag(LockTags.COVER).clickable(remember { MutableInteractionSource() }, null, onClickLabel = tr(R.string.open_the_player)) { actions.onCover() }
            } else Modifier,
        ),
    ) { cover(Modifier.fillMaxSize()) }
}

@Composable
private fun TitleRow(c: LockScreenContent, heart: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.artist.isNotEmpty()) Text(c.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (heart) {
            Spacer(Modifier.width(8.dp))
            Glyph(
                if (c.liked) PrismIcons.HeartFilled else PrismIcons.Heart,
                tr(if (c.liked) R.string.lock_screen_unlike else R.string.widget_like), 26.dp, 0.9f, LockTags.LIKE,
            ) { it.onLike() }
        }
    }
}

@Composable
private fun Progress(c: LockScreenContent, thickness: Dp, times: Boolean) {
    val actions = LocalLockActions.current
    val duration = c.durationMs.coerceAtLeast(0L)
    LockBar(
        fraction = { if (duration > 0) (c.position().toFloat() / duration).coerceIn(0f, 1f) else 0f },
        thickness = thickness,
        fill = PrismTokens.spectrum(true),
        tag = LockTags.SEEK,
        description = tr(R.string.lock_screen_position),
        onChange = null,
        onDone = if (actions != null && c.seekable) actions.onSeek else null,
    )
    if (times) {
        val elapsed by remember(c) { derivedStateOf { c.position() / 1000 } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockText(elapsed), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            if (duration > 0) Text("-" + clockText((duration / 1000 - elapsed).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

private fun clockText(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * A thin line to read and drag: drawn from [fraction] in the draw pass only, with a taller area to touch than it shows.
 * [onChange] follows the finger and [onDone] gets where it was let go; with neither it's only a picture.
 */
@Composable
private fun LockBar(
    fraction: () -> Float,
    thickness: Dp,
    fill: Brush,
    tag: String,
    description: String,
    onChange: ((Float) -> Unit)?,
    onDone: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf<Float?>(null) }
    val changed by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val touchable = onChange != null || onDone != null
    val haptics = rememberHaptics()
    Canvas(
        modifier.fillMaxWidth().height(if (touchable) 28.dp else thickness + 6.dp).then(
            if (touchable) {
                Modifier.testTag(tag)
                    .semantics {
                        contentDescription = description
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                        setProgress { v -> done?.invoke(v) ?: changed?.invoke(v); true }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            val f = (o.x / size.width).coerceIn(0f, 1f)
                            haptics.click()
                            changed?.invoke(f)
                            done?.invoke(f)
                        }
                    }
                    .pointerInput(Unit) {
                        // Sideways only, so a swipe up that starts on the line still unlocks.
                        var last = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); held = last; changed?.invoke(last) },
                            onDragEnd = { done?.invoke(last); held = null },
                            onDragCancel = { held = null },
                        ) { change, _ ->
                            change.consume()
                            last = (change.position.x / size.width).coerceIn(0f, 1f)
                            held = last
                            changed?.invoke(last)
                        }
                    }
            } else Modifier,
        ),
    ) {
        val h = thickness.toPx()
        val top = (size.height - h) / 2
        val radius = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), radius)
        val w = size.width * (held ?: fraction())
        if (w > 0f) drawRoundRect(fill, Offset(0f, top), Size(w, h), radius)
    }
}

@Composable
private fun Transport(c: LockScreenContent, disc: Dp, gap: Dp) {
    if (c.repeat != null) {
        // With repeat on the right, the same room on the left keeps Play in the middle; the gaps give way on a narrow phone.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.widthIn(max = REPEAT_ROOM * 2 + 54.dp * 2 + disc + (gap - 10.dp) * 4).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(REPEAT_ROOM))
                Glyph(PrismIcons.Previous, tr(R.string.widget_previous), 34.dp, 1f, LockTags.PREVIOUS) { it.onPrevious() }
                Disc(c, disc)
                Glyph(PrismIcons.Next, tr(R.string.widget_next), 34.dp, 1f, LockTags.NEXT) { it.onNext() }
                RepeatGlyph(c, 24.dp)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Glyph(PrismIcons.Previous, tr(R.string.widget_previous), 34.dp, 1f, LockTags.PREVIOUS) { it.onPrevious() }
        Spacer(Modifier.width(gap - 10.dp))
        Disc(c, disc)
        Spacer(Modifier.width(gap - 10.dp))
        Glyph(PrismIcons.Next, tr(R.string.widget_next), 34.dp, 1f, LockTags.NEXT) { it.onNext() }
    }
}

/** What the repeat button takes across (its glyph and a finger's room) at its largest. */
private val REPEAT_ROOM = 44.dp

/** Repeat (1.8.3, D47): dim when off, bright for all, with a small "1" for one song. Nothing when not shown. */
@Composable
private fun RepeatGlyph(c: LockScreenContent, size: Dp) {
    val mode = c.repeat ?: return
    val actions = LocalLockActions.current
    val haptics = rememberHaptics()
    val label = tr(
        when (mode) {
            androidx.media3.common.Player.REPEAT_MODE_ALL -> R.string.repeat_all
            androidx.media3.common.Player.REPEAT_MODE_ONE -> R.string.repeat_one
            else -> R.string.repeat_off
        },
    )
    Box(
        Modifier.size(size + 20.dp).then(
            if (actions != null) Modifier.testTag(LockTags.REPEAT).clickable(remember { MutableInteractionSource() }, null, onClickLabel = label) { haptics.click(); actions.onRepeat() }
            else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val on = mode != androidx.media3.common.Player.REPEAT_MODE_OFF
        Icon(PrismIcons.Repeat, label, Modifier.size(size), tint = Color.White.copy(alpha = if (on) 1f else 0.45f))
        if (mode == androidx.media3.common.Player.REPEAT_MODE_ONE) Text(
            "1", Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).clip(CircleShape).background(Color.White).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp), fontWeight = FontWeight.Bold, color = Color.Black,
        )
    }
}

/** The next songs, to read only: the lock screen controls what's playing, nothing more (1.8.3, D47). */
@Composable
private fun UpNext(c: LockScreenContent, most: Int, modifier: Modifier = Modifier) {
    if (c.upNext.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag(LockTags.UP_NEXT), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(tr(R.string.up_next).uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
        c.upNext.take(most).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A glyph with a finger's room around it. */
@Composable
private fun Glyph(icon: ImageVector, description: String, size: Dp, alpha: Float, tag: String, onClick: (LockScreenActions) -> Unit) {
    val actions = LocalLockActions.current
    val haptics = rememberHaptics()
    Box(
        Modifier.size(size + 20.dp).then(
            if (actions != null) {
                Modifier.testTag(tag).clickable(remember { MutableInteractionSource() }, null, onClickLabel = description) { haptics.click(); onClick(actions) }
            } else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(size), tint = Color.White.copy(alpha = alpha)) }
}

/** Play / pause on a white disc, edged with the split light as on the player. */
@Composable
private fun Disc(c: LockScreenContent, size: Dp) {
    val actions = LocalLockActions.current
    val haptics = rememberHaptics()
    val ring = com.prism.music.ui.theme.LocalAppSettings.current.view.playRing
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White)
            .then(if (ring) Modifier.border(2.dp, PrismTokens.spectrumRing(), CircleShape) else Modifier)
            .then(playClick(actions, c.playing, haptics::click)),
        contentAlignment = Alignment.Center,
    ) { PlayGlyph(c, size * 0.42f, Color.Black) }
}

/** The Quiet layout's: a thin ring, no white disc. */
@Composable
private fun QuietPlay(c: LockScreenContent) {
    val actions = LocalLockActions.current
    val haptics = rememberHaptics()
    Box(
        Modifier.size(64.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape).then(playClick(actions, c.playing, haptics::click)),
        contentAlignment = Alignment.Center,
    ) { PlayGlyph(c, 26.dp, Color.White) }
}

@Composable
private fun playClick(actions: LockScreenActions?, playing: Boolean, tick: () -> Unit): Modifier =
    if (actions == null) Modifier
    else Modifier.testTag(LockTags.PLAY).clickable(remember { MutableInteractionSource() }, null, onClickLabel = tr(if (playing) R.string.widget_pause else R.string.widget_play)) { tick(); actions.onPlayPause() }

@Composable
private fun PlayGlyph(c: LockScreenContent, size: Dp, tint: Color) {
    if (c.loading) CircularProgressIndicator(color = tint, strokeWidth = 1.5.dp, modifier = Modifier.size(size))
    else Icon(if (c.playing) PrismIcons.Pause else PrismIcons.Play, tr(if (c.playing) R.string.widget_pause else R.string.widget_play), Modifier.size(size), tint = tint)
}

@Composable
private fun Volume(c: LockScreenContent) {
    val actions = LocalLockActions.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(PrismIcons.VolumeDown, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.width(10.dp))
        LockBar(
            fraction = c.volume,
            thickness = 4.dp,
            fill = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.85f)),
            tag = LockTags.VOLUME,
            description = tr(R.string.volume),
            onChange = actions?.onVolume,
            onDone = if (actions != null) { _ -> actions.onVolumeDone() } else null,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(PrismIcons.VolumeUp, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun Output(o: LockOutput) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(o.icon, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = 0.65f))
        Spacer(Modifier.width(6.dp))
        Text(o.label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Unlock() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(PrismIcons.Lock, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(4.dp))
        Text(tr(R.string.lock_screen_swipe), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
    }
}

@Composable
private fun GlassPane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), shape)) { content() }
}

/**
 * The Lyrics layout's lines: the one before, the one being sung (word by word, in the listener's lyrics style), and two
 * ahead. [clock] advances only while the screen is on and the song plays.
 */
@Composable
internal fun LockLyricLines(lines: List<LyricLine>, clock: State<Long>, look: LyricsLook, axis: FontFamily?, modifier: Modifier = Modifier) {
    val index by remember(lines) { derivedStateOf { lines.indexAtOrBefore(clock.value) } }
    val rest = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 26.sp * look.sizeScale, lineHeight = 33.sp * look.sizeScale, fontWeight = FontWeight.Light, textDirection = TextDirection.Content,
    ).withLyricsFont()
    val sung = rest.copy(fontSize = 36.sp * look.sizeScale, lineHeight = 44.sp * look.sizeScale, fontWeight = FontWeight(look.weight))
    AnimatedContent(
        index,
        modifier,
        transitionSpec = {
            (fadeIn(androidx.compose.animation.core.tween(260)) + slideInVertically(androidx.compose.animation.core.tween(320)) { it / 8 })
                .togetherWith(fadeOut(androidx.compose.animation.core.tween(160)) + slideOutVertically(androidx.compose.animation.core.tween(320)) { -it / 8 })
        },
        contentAlignment = Alignment.CenterStart,
        label = "lockLyrics",
    ) { i ->
        val previous = remember(lines, i) { lines.take(i.coerceAtLeast(0)).lastOrNull { !it.isGap } }
        val current = lines.getOrNull(i)?.takeIf { !it.isGap }
        val ahead = remember(lines, i) { lines.drop(i + 1).filter { !it.isGap }.take(2) }
        Column(Modifier.fillMaxWidth().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            previous?.let { Text(it.text, style = rest, color = Color.White.copy(alpha = 0.34f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            when {
                current == null -> Text("♪", style = sung, color = Color.White)
                !current.isWordSynced && look.plainWhenLineTimed ->
                    Text(current.text, style = sung.copy(fontWeight = FontWeight(look.weight + 100)), color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                else -> SungLine(current, clock, sung, look, axis, Modifier.fillMaxWidth(), maxLines = 4, dimAlpha = 0.45f)
            }
            ahead.forEachIndexed { n, line ->
                Text(line.text, style = rest, color = Color.White.copy(alpha = if (n == 0) 0.34f else 0.2f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A layout as a small phone for its card on the settings page: the listener's Look, the song playing (or a stand-in with
 * Prism's own example words), held still and not touchable.
 */
@Composable
internal fun LockScreenSample(layout: LockLayout, song: com.prism.music.data.model.Song?, width: Dp = 132.dp, modifier: Modifier = Modifier) {
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    val hide = s.behavior.lockScreenHideSong
    val shown = LockScreenRules.layoutFor(layout, false, hide)
    val hues = com.prism.music.ui.screens.standIns.first()
    val frost = if (hide || shown == LockLayout.QUIET) null else com.prism.music.ui.components.rememberFrost(song?.thumbnail)?.image
    val shape = lockCoverShape(s)
    val date = remember { java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date()).uppercase() }
    val lines = sampleLines()
    val clock = remember { mutableStateOf(SAMPLE_CLOCK_MS) }
    val content = LockScreenContent(
        title = if (hide) tr(R.string.lock_screen_hidden_title) else song?.title ?: tr(R.string.northern_lights),
        artist = if (hide) "" else song?.artistText ?: tr(R.string.prism_sample),
        frost = frost,
        cover = { m, sh -> if (hide) HiddenCover(m, sh) else com.prism.music.ui.screens.StandIn(song, m, sh, hues) },
        coverShape = shape,
        playing = true,
        showLike = s.behavior.lockScreenLike && !hide,
        showVolume = s.behavior.lockScreenVolume,
        output = if (s.behavior.lockScreenOutput) LockOutput(PrismIcons.Headphones, tr(R.string.this_phone)) else null,
        position = { 84_000L },
        durationMs = 202_000L,
        volume = { 0.6f },
        time = "9:41",
        date = date,
        lyrics = { m -> LockLyricLines(lines, clock, com.prism.music.ui.player.LyricsLook.Classic, null, m) },
        repeat = if (s.behavior.lockScreenRepeat) androidx.media3.common.Player.REPEAT_MODE_ALL else null,
        // Prism's own example words stand in for the songs ahead.
        upNext = if (s.behavior.lockScreenUpNext && !hide) {
            listOf(R.string.every_color_finds_its_way, R.string.we_follow_the_line_of_the, R.string.into_the_bright_of_the_day).map { tr(it) + " · " + tr(R.string.prism_sample) }
        } else emptyList(),
    )
    com.prism.music.ui.screens.PreviewPhone(width, modifier.clearAndSetSemantics { }) {
        com.prism.music.ui.screens.LookTheme(s, true) { LockScreenLayout(shown, content, null) }
    }
}

/** Where the examples' lyric clock stands: part way through the second line. */
private const val SAMPLE_CLOCK_MS = 7_000L

/** Prism's own example lines, also the lyric pages' examples. */
@Composable
private fun sampleLines(): List<LyricLine> {
    val texts = listOf(
        tr(R.string.somewhere_a_light_is_turning_on), tr(R.string.every_color_finds_its_way),
        tr(R.string.we_follow_the_line_of_the), tr(R.string.into_the_bright_of_the_day),
    )
    return remember(texts) {
        texts.mapIndexed { i, text -> LyricLine(i * 5_000L, text, (i + 1) * 5_000L).withWords() }
    }
}

/** The line playing at [ms] (the last one started), or -1 before the first. */
private fun List<LyricLine>.indexAtOrBefore(ms: Long): Int {
    var idx = -1
    for (i in indices) if (this[i].timeMs in 0..ms) idx = i else if (this[i].timeMs > ms) break
    return idx
}

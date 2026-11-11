package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import androidx.compose.foundation.layout.ColumnScope
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import com.prism.music.ui.components.framesWhileShown
import com.prism.music.ui.components.tickWhileShown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.compose.foundation.border
import com.prism.music.data.addressOrBlank
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.playback.AudioEffects
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val PLAYER_GUTTER = 30.dp
private val BOTTOM_ACTION_SIZE = 44.dp
private val PILL_SEGMENT_WIDTH = 64.dp
private val PILL_SEGMENT_WIDTH_TRIPLE = 52.dp

val textShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 4f)

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A hairline capsule with no thumb that thickens under the finger. */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    secondary: Float = 0f,
    /** Draw a round knob at the current value. */
    thumb: Boolean = false,
    /** Spectrum: the played part in the split light (the scrubber), rather than white. */
    spectrum: Boolean = false,
    /** Points (0–1) marked with a tick (the best part); dragging across one gives a haptic step. */
    marks: List<Float> = emptyList(),
    /** The played part's own fill (Settings → Player → progress bar color), over [spectrum]. */
    fill: Brush? = null,
    /** The song's loudness outline (0–1 a point, [ScrubberStyle.WAVE]): drawn as bars instead of a line. */
    wave: FloatArray? = null,
    /** The knob's shape when [thumb] (1.7.4, Settings → Player → Progress bar). */
    thumbShape: com.prism.music.data.prefs.ScrubberThumb = com.prism.music.data.prefs.ScrubberThumb.DOT,
    /** The visualizer's Pulse (0–1, read while drawing, so the bar alone redraws): the line swells and glows with it. */
    pulse: (() -> Float)? = null,
    /** Ribbons (#151): strands flowing over the played part, at this phase (seconds, read while drawing). */
    ribbons: (() -> Float)? = null,
    /**
     * The bar shaped by the crossfade (1.8.1): its height follows the fade-out at the end and the fade-in at the start,
     * and the knob rides it. Null (and with [wave]): the bar as 1.8.0 drew it.
     */
    shape: BlendShape? = null,
    /** Points (0–1) that give a haptic step when dragged across without a tick drawn (the shaped bar's start of the blend). */
    quietMarks: List<Float> = emptyList(),
) {
    var dragging by remember { mutableStateOf(false) }
    // The shaped bar's outline, kept between frames; only made once a shape is there.
    val outline = remember(shape != null) { if (shape != null) BlendBarPath() else null }
    // The strands' paths, rebuilt in place each frame rather than made anew.
    val strands = remember { List(RIBBON_STRANDS.size) { androidx.compose.ui.graphics.Path() } }
    val haptics = rememberHaptics()
    val marksNow by androidx.compose.runtime.rememberUpdatedState(marks)
    val quietNow by androidx.compose.runtime.rememberUpdatedState(quietMarks)
    var last by remember { mutableFloatStateOf(-1f) }
    fun crossed(v: Float) {
        if (last >= 0f && (marksNow.any { m -> (last < m) != (v < m) } || quietNow.any { m -> (last < m) != (v < m) })) haptics.tick()
        last = v
    }
    // The gesture below runs for the slider's whole life; without these it kept the first callbacks, which
    // seeked with the duration of the song that was playing when the player opened (a longer or shorter song
    // later landed in the wrong place).
    val onChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val onFinished by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)
    val height by animateDpAsState(
        if (dragging) activeHeight else idleHeight,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sliderHeight",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    last = -1f
                    (down.position.x / size.width).coerceIn(0f, 1f).let { crossed(it); onChange(it) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) { p.consume(); break }
                        if (p.positionChanged()) {
                            (p.position.x / size.width).coerceIn(0f, 1f).let { crossed(it); onChange(it) }
                            p.consume()
                        }
                    }
                    dragging = false
                    onFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val r = CornerRadius(size.height / 2f)
            val filled = size.width * value.coerceIn(0f, 1f)
            // The player is always dark: White light's white (not its mid grey) on it (Reddit: "white is giving gray").
            val brush = fill ?: if (spectrum) Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray(), startX = 0f, endX = size.width) else null
            if (wave != null && wave.isNotEmpty()) {
                // The waveform: a bar per point, its height the song's loudness there; the played part in the fill.
                val step = size.width / wave.size
                val w = (step * 0.6f).coerceAtLeast(1f)
                val bars = CornerRadius(w / 2)
                wave.forEachIndexed { i, v ->
                    val h = size.height * (0.18f + 0.82f * v.coerceIn(0f, 1f))
                    val x = step * i + (step - w) / 2
                    val top = Offset(x, (size.height - h) / 2)
                    val bar = Size(w, h)
                    if (x + w / 2 <= filled) {
                        if (brush != null) drawRoundRect(brush, top, bar, bars) else drawRoundRect(Color.White.copy(alpha = 0.92f), top, bar, bars)
                    } else drawRoundRect(Color.White.copy(alpha = 0.22f), top, bar, bars)
                }
            } else if (shape != null && outline != null) {
                // Shaped by the crossfade (1.8.1, as the owner's mockup): the bar's outline, the played part of it in the
                // fill up to the playhead, the rest in the track's grey.
                val path = outline.outline(shape, size.width, size.height)
                drawPath(path, Color.White.copy(alpha = 0.20f))
                if (secondary > 0f) clipRect(right = size.width * secondary.coerceIn(0f, 1f)) { drawPath(path, Color.White.copy(alpha = 0.12f)) }
                if (filled > 0f) {
                    val beat = pulse?.invoke()?.coerceIn(0f, 1f) ?: 0f
                    if (beat > 0.02f) {
                        val grow = size.height * 1.6f * beat
                        val glow = brush ?: androidx.compose.ui.graphics.SolidColor(Color.White)
                        drawRoundRect(glow, Offset(0f, -grow), Size(filled, size.height + 2 * grow), CornerRadius(size.height / 2 + grow), alpha = 0.22f * beat)
                    }
                    clipRect(right = filled) {
                        if (brush != null) drawPath(path, brush) else drawPath(path, Color.White.copy(alpha = 0.92f))
                    }
                }
            } else {
                drawRoundRect(Color.White.copy(alpha = 0.20f), cornerRadius = r)
                if (secondary > 0f) drawRoundRect(Color.White.copy(alpha = 0.12f), size = Size(size.width * secondary.coerceIn(0f, 1f), size.height), cornerRadius = r)
                val played = Size(filled.coerceAtLeast(size.height).coerceAtMost(size.width), size.height)
                if (filled > 0f) {
                    // Pulse: the played part swells around its middle and a soft glow rises from it.
                    val beat = pulse?.invoke()?.coerceIn(0f, 1f) ?: 0f
                    if (beat > 0.02f) {
                        val grow = size.height * 1.6f * beat
                        val glow = brush ?: androidx.compose.ui.graphics.SolidColor(Color.White)
                        drawRoundRect(glow, Offset(0f, -grow), Size(played.width, size.height + 2 * grow), CornerRadius(size.height / 2 + grow), alpha = 0.22f * beat)
                    }
                    if (brush != null) drawRoundRect(brush, size = played, cornerRadius = r)
                    else drawRoundRect(Color.White.copy(alpha = 0.92f), size = played, cornerRadius = r)
                    ribbons?.let { drawRibbonStrands(strands, filled, it(), brush) }
                }
            }
            // Ticks: a faint hairline that stands just past the line, there if you look, never in the way.
            marks.forEach { m ->
                val x = size.width * m.coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = 0.34f), Offset(x, -1.5.dp.toPx()), Offset(x, size.height + 1.5.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
            }
            if (thumb) {
                // On the shaped bar the knob rides the shape: as small as the bar is thin there (1.8.1).
                val ride = if (shape != null && outline != null && wave == null) BlendShape.knobScale(shape.height(value.coerceIn(0f, 1f))) else 1f
                val base = (if (dragging) 9.dp else 7.dp).toPx()
                val knob = base * ride
                val x = filled.coerceIn(knob, size.width - knob)
                val y = size.height / 2
                when (thumbShape) {
                    com.prism.music.data.prefs.ScrubberThumb.LINE -> {
                        // An upright tick standing past the bar.
                        val half = maxOf(size.height / 2 + 5.dp.toPx(), base) * ride
                        val w = (if (dragging) 4.dp else 3.dp).toPx()
                        drawLine(Color.Black.copy(alpha = 0.18f), Offset(x, y - half + 1f), Offset(x, y + half + 1f), w + 1.5f, StrokeCap.Round)
                        drawLine(Color.White, Offset(x, y - half), Offset(x, y + half), w, StrokeCap.Round)
                    }
                    com.prism.music.data.prefs.ScrubberThumb.RING -> {
                        drawCircle(Color.Black.copy(alpha = 0.18f), knob + 1.5f, Offset(x, y + 1f))
                        drawCircle(Color.Black.copy(alpha = 0.55f), knob, Offset(x, y))
                        drawCircle(Color.White, knob, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                    else -> {
                        drawCircle(Color.Black.copy(alpha = 0.18f), knob + 1.5f, Offset(x, y + 1f))
                        drawCircle(Color.White, knob, Offset(x, y))
                    }
                }
            }
        }
    }
}

/** Each Ribbons strand: its wavelength (a share of the bar's width), speed (waves a second, signed) and strength. */
private class RibbonStrand(val wave: Float, val speed: Float, val alpha: Float, val shift: Float)
private val RIBBON_STRANDS = listOf(
    RibbonStrand(0.21f, 0.32f, 0.70f, 0f),
    RibbonStrand(0.30f, -0.22f, 0.50f, 1.7f),
    RibbonStrand(0.43f, 0.45f, 0.36f, 3.9f),
)

/**
 * Ribbons (#151): thin strands rising off the played part and back onto it, each its own wavelength and drift, fading in
 * from the bar's start and out before the knob. Points every few pixels into reused paths; drawn above the bar, in the
 * slider's own room.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRibbonStrands(
    paths: List<androidx.compose.ui.graphics.Path>, filled: Float, phase: Float, brush: Brush?, barHeight: Float = size.height,
) {
    val top = 0f
    val rise = 8.dp.toPx() + barHeight * 0.4f
    val step = 3.dp.toPx()
    val fadeIn = 28.dp.toPx()
    val fadeOut = 14.dp.toPx()
    val end = filled - fadeOut * 0.4f
    if (end <= step) return
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
    RIBBON_STRANDS.forEachIndexed { k, s ->
        val path = paths[k]
        path.reset()
        val lambda = size.width * s.wave
        var x = 0f
        var first = true
        while (x <= end) {
            val env = (x / fadeIn).coerceIn(0f, 1f) * ((filled - x) / fadeOut).coerceIn(0f, 1f)
            val a = 2f * Math.PI.toFloat() * (x / lambda - phase * s.speed) + s.shift
            val y = top - rise * env * (0.5f - 0.5f * kotlin.math.cos(a))
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += step
        }
        if (brush != null) drawPath(path, brush, alpha = s.alpha, style = stroke)
        else drawPath(path, Color.White, alpha = s.alpha * 0.9f, style = stroke)
    }
}

/**
 * The progress bar's played part in [color] (Settings → Player, 1.7.4); null keeps the split light. The player is
 * always dark, so an accent too deep to see on it is lifted, and the cover's color is its brightest swatch.
 */
@Composable
fun scrubberFill(color: com.prism.music.data.prefs.ScrubberColor, cover: String?): Brush? = when (color) {
    com.prism.music.data.prefs.ScrubberColor.SPECTRUM -> null
    com.prism.music.data.prefs.ScrubberColor.WHITE -> androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.92f))
    com.prism.music.data.prefs.ScrubberColor.ACCENT -> androidx.compose.ui.graphics.SolidColor(onDark(MaterialTheme.colorScheme.primary))
    com.prism.music.data.prefs.ScrubberColor.COVER -> {
        val palette = com.prism.music.ui.components.rememberArtPalette(cover)
        androidx.compose.ui.graphics.SolidColor(onDark(palette?.vibrant ?: MaterialTheme.colorScheme.primary))
    }
}

/** [c] lightened until it reads on the player's dark background. */
private fun onDark(c: Color): Color {
    var out = c.copy(alpha = 1f)
    var n = 0
    while (out.luminance() < 0.28f && n++ < 6) out = androidx.compose.ui.graphics.lerp(out, Color.White, 0.18f)
    return out
}

/**
 * [PlayerScrubber] reading the playhead itself ([rememberPositionState]): as the song plays only the bar and its times
 * recompose, not the player around them (1.7.4; the whole player was redone four times a second).
 */
@Composable
fun PlayheadScrubber(position: androidx.compose.runtime.State<Long>, durationMs: Long, centerLabel: @Composable BoxScope.() -> Unit = {}) =
    PlayerScrubber(position.value, durationMs, centerLabel)

/** Seek bar with elapsed / remaining under it and a centre label between them. */
@Composable
fun PlayerScrubber(positionMs: Long, durationMs: Long, centerLabel: @Composable BoxScope.() -> Unit = {}) {
    val pc = LocalContainer.current.player
    val current by pc.currentSong.collectAsState()
    // A live stream has no start or end: a full bar marked LIVE, not a clock of its rolling window.
    if (current?.isLive == true) {
        Column(Modifier.fillMaxWidth()) {
            ThinSlider(value = 1f, onValueChange = {}, idleHeight = 7.dp, activeHeight = 7.dp)
            Box(Modifier.fillMaxWidth().offset(y = (-9).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Color(0xFF828282), androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
                centerLabel()
            }
        }
        return
    }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val shown = scrub ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val style = LocalAppSettings.current.scrubberStyle
    // The song's length or the time left (#82): tapping the time switches, and it stays that way.
    val showLength = LocalAppSettings.current.showTrackLength
    val c = LocalContainer.current
    // The best part's place on the bar (it's given in the lyrics' timeline, shifted from the song's by the timing offset).
    val best by c.bestParts.current.collectAsState()
    val shift by pc.lyricShift.collectAsState()
    val bestMark = best?.takeIf { it.songId == current?.id && LocalAppSettings.current.bestPartButton }
        ?.startMs?.let { ((it - shift).toFloat() / duration).takeIf { f -> f in 0.01f..0.99f } }
    // Where the crossfade into the next song starts (A6, an option): once it's known, while Crossfade is on.
    val blend by c.mixState.mark.collectAsState()
    val settings = LocalAppSettings.current
    // The bar shaped by the crossfade (1.8.1, an option; the lines only: the waveform and Ribbons keep the mark). Its
    // own start of the blend needs no tick, but dragging across it still gives the step.
    val shaped = settings.view.crossfadeShape && style != ScrubberStyle.WAVE && style != ScrubberStyle.RIBBONS
    val blendShape = if (shaped) rememberBlendShape(current?.id, durationMs, blend) else null
    val blendMark = blend?.takeIf { settings.view.crossfadeMark && it.songId == current?.id && blendShape?.fadesOut != true }
        ?.let { (it.startMs.toFloat() / duration).takeIf { f -> f in 0.01f..0.99f } }
    val times = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val fill = scrubberFill(settings.scrubberColor, current?.let { com.prism.music.data.PlaylistCovers.artOf(it) })
    // Waveform: the song's outline from its scan (a plain bar until there is one).
    val waveStamp by c.waves.stamp.collectAsState()
    val wave by androidx.compose.runtime.produceState<FloatArray?>(null, style, current?.id, waveStamp) {
        val id = current?.id
        value = if (style != ScrubberStyle.WAVE || id == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.waves.get(id) }
    }
    val waved = wave != null
    // Thickness (1.7.4): the style's own heights, scaled; the waveform keeps its room.
    val thick = if (waved) 1f else settings.scrubberThickness
    val knob = when (settings.scrubberThumb) {
        com.prism.music.data.prefs.ScrubberThumb.AUTO -> if (style == ScrubberStyle.THUMB || style == ScrubberStyle.RIBBONS) com.prism.music.data.prefs.ScrubberThumb.DOT else com.prism.music.data.prefs.ScrubberThumb.NONE
        else -> settings.scrubberThumb
    }
    val pulse = if (settings.visualizer == com.prism.music.data.prefs.VisualizerStyle.PULSE) rememberVisualizerLevel() else null
    // Ribbons drift only while the song plays and Prism is on screen; with Reduce motion (and Performance mode) they hold still.
    val ribbonPhase = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val playing by pc.isPlaying.collectAsState()
    val ribbonsMove = style == ScrubberStyle.RIBBONS && playing && !settings.reduceMotion
    if (ribbonsMove) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.framesWhileShown { ribbonPhase.floatValue += it }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        // Spectrum: a thin line, the played part in the split light (or the color picked for it).
        ThinSlider(
            value = shown,
            modifier = Modifier.testTag("progressBar").then(
                if (blendShape != null) Modifier.semantics { this[CrossfadeShapeKey] = blendShape.semanticsValue() } else Modifier,
            ),
            onValueChange = { scrub = it },
            onValueChangeFinished = { scrub?.let { pc.seekTo((it * duration).toLong()) }; scrub = null },
            idleHeight = if (waved) 22.dp else barHeights(style, thick).first,
            activeHeight = if (waved) 26.dp else barHeights(style, thick).second,
            thumb = knob != com.prism.music.data.prefs.ScrubberThumb.NONE,
            thumbShape = knob,
            spectrum = true,
            marks = listOfNotNull(bestMark, blendMark),
            fill = fill,
            wave = wave,
            pulse = pulse?.let { level -> { level.floatValue } },
            ribbons = if (style == ScrubberStyle.RIBBONS && !waved) ({ ribbonPhase.floatValue }) else null,
            shape = blendShape,
            quietMarks = if (blendShape?.fadesOut == true) remember(blendShape) { listOf(blendShape.outFrom) } else emptyList(),
        )
        Box(Modifier.fillMaxWidth().offset(y = (-9).dp).heightIn(min = 16.dp)) {
            val elapsed = settings.playerTimes == com.prism.music.data.prefs.PlayerTimes.BOTH || settings.playerTimes == com.prism.music.data.prefs.PlayerTimes.ELAPSED
            val left = settings.playerTimes == com.prism.music.data.prefs.PlayerTimes.BOTH || settings.playerTimes == com.prism.music.data.prefs.PlayerTimes.LEFT
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (elapsed) Text(formatTime((shown * duration).toLong()), style = times, color = Color.White.copy(alpha = 0.55f)) else Spacer(Modifier.width(1.dp))
                if (left) Text(
                    if (showLength) formatTime(duration) else "−" + formatTime(duration - (shown * duration).toLong()),
                    style = times, color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = if (showLength) tr(R.string.show_time_left) else tr(R.string.show_song_length)) { c.settings.setShowTrackLength(!showLength) },
                )
            }
            centerLabel()
        }
    }
}

/**
 * The playing song's bar shape from the crossfade (1.8.1): its end from the blend after it ([ending], once its length is
 * known) and its start from how it came in. Worked out again only when one of them changes, not as the song plays.
 */
@Composable
private fun rememberBlendShape(songId: String?, durationMs: Long, ending: com.prism.music.playback.BlendMark?): BlendShape? {
    val into by LocalContainer.current.mixState.into.collectAsState()
    val curve = LocalAppSettings.current.sound.crossfadeCurve
    return remember(songId, durationMs, ending, into, curve) { BlendShape.of(songId, durationMs, ending, into, curve) }
}

/**
 * "Best part" in the middle under the bar while a song's best part is still ahead (at least 3 s away): in the song's
 * first 30 s, or for 15 s from when its best part became known if that took longer (up to 45 s in). Returns false
 * (draws nothing) otherwise, so the stream-quality label can take the place.
 */
@Composable
fun BoxScope.BestPartChip(): Boolean {
    val c = LocalContainer.current
    val pc = c.player
    val settings = LocalAppSettings.current
    val song by pc.currentSong.collectAsState()
    val best by c.bestParts.current.collectAsState()
    val now = best?.takeIf { it.songId == song?.id }
    val start = now?.startMs ?: return false
    // Past its window (or turned off): no clock of its own for the rest of the song.
    if (!settings.bestPartButton || pc.lyricPosition >= start || pc.lyricPosition >= BestPartButton.LATEST_MS) return false
    // A clock in the lyrics' timeline the best part is given in. Only whether the tag shows (or its window has passed)
    // recomposes this, not every tick of the clock.
    val shift = pc.lyricShift.collectAsState()
    val position = rememberPositionState(fast = false)
    val known = now.knownAtMs
    val phase by remember(start, known) {
        derivedStateOf {
            val at = position.value + shift.value
            when {
                BestPartButton.shows(at, start, known) -> 1
                at >= start || at >= BestPartButton.LATEST_MS -> 2
                else -> 0
            }
        }
    }
    if (phase != 1) return false
    val haptics = rememberHaptics()
    // A small glass tag with a hairline of split light round it; the flame and the words in plain white.
    // Held, it opens "Your best part" to set the listener's own.
    val tag = CircleShape
    Row(
        Modifier.align(Alignment.Center).clip(tag).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), tag)
            .combinedClickable(
                onClickLabel = tr(R.string.skip_to_the_best_part), onLongClickLabel = tr(R.string.set_your_own_best_part),
                onLongClick = { haptics.tick(); BestPartEditor.open = true },
            ) { haptics.tick(); pc.seekToLyric(start) }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (now.mine) PrismIcons.FlameFilled else PrismIcons.Flame, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(if (now.mine) tr(R.string.your_best_part) else tr(R.string.best_part), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.92f))
    }
    return true
}

/** Whether the "Your best part" sheet is open (the Best part button held, or ⋯ → Your best part). */
object BestPartEditor {
    var open by mutableStateOf(false)
}

/** When the player's "Best part" button is up. */
object BestPartButton {
    /** At [atMs] into the song, with its best part at [startMs], known since [knownAtMs] into it. */
    fun shows(atMs: Long, startMs: Long, knownAtMs: Long): Boolean {
        val until = maxOf(FIRST_MS, knownAtMs + AFTER_KNOWN_MS).coerceAtMost(LATEST_MS)
        return atMs < until && atMs < startMs - AHEAD_MS
    }

    private const val FIRST_MS = 30_000L
    private const val AFTER_KNOWN_MS = 15_000L
    const val LATEST_MS = 45_000L
    private const val AHEAD_MS = 3_000L
}

/** Previous, play/pause, next — oversized glyphs, no circles. */
@Composable
fun TransportRow(
    isPlaying: Boolean, isLoading: Boolean, previousEnabled: Boolean, nextEnabled: Boolean, compact: Boolean = false, episode: Boolean = false,
    /** Previous and next on the player: What's on the player can hide them (1.8.0). An episode keeps its back / ahead skips. */
    skips: Boolean = true,
) {
    val pc = LocalContainer.current.player
    val style = LocalAppSettings.current.transportStyle
    val playSize = if (compact) 58.dp else 74.dp
    // With shuffle and repeat beside it, play's touch area gives a little room back (narrow phones).
    val playTouch = if (compact) 76.dp else if (LocalAppSettings.current.playerShuffleRepeat) 80.dp else 92.dp
    val skipSize = if (compact) 44.dp else 53.dp
    // Settings → Player: shuffle and repeat either side of the transport.
    val modes = LocalAppSettings.current.playerShuffleRepeat
    val shuffle by pc.shuffle.collectAsState()
    val smart by LocalContainer.current.queue.smartShuffle.collectAsState()
    val repeat by pc.repeatMode.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        if (modes) ModeGlyph(
            if (smart) PrismIcons.SmartShuffle else PrismIcons.Shuffle,
            if (smart) tr(R.string.smart_shuffle_on) else if (shuffle) tr(R.string.shuffle_on) else tr(R.string.shuffle_off), shuffle,
        ) { pc.toggleShuffle() }
        val prevIcon = if (style == TransportStyle.GLYPHS) PrismIcons.Backward else PrismIcons.Previous
        val nextIcon = if (style == TransportStyle.GLYPHS) PrismIcons.Forward else PrismIcons.Next
        val skip = if (style == TransportStyle.GLYPHS) skipSize else skipSize * 0.82f
        // Settings → Player → "Hold to seek": holding previous / next jumps back / forward.
        val holdMs = LocalAppSettings.current.holdSeekSeconds * 1000L
        val back = if (holdMs > 0) ({ pc.seekBy(-holdMs) }) else null
        val ahead = if (holdMs > 0) ({ pc.seekBy(holdMs) }) else null
        // A podcast episode skips within itself, as podcast players do: back 15 s, ahead 30 s.
        if (skips || episode) {
            if (episode) SkipGlyph(EPISODE_BACK_S, ahead = false, touch = 53.dp) { pc.seekBy(-EPISODE_BACK_S * 1000L) }
            else TransportGlyph(prevIcon, tr(R.string.widget_previous), skip, 53.dp, if (style == TransportStyle.GLYPHS) 0.85f else 1f, previousEnabled, onHold = back) { pc.previous() }
        }
        if (style == TransportStyle.GLYPHS) {
            if (isLoading) {
                Box(Modifier.size(playTouch), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(if (compact) 30.dp else 38.dp))
                }
            } else {
                TransportGlyph(if (isPlaying) PrismIcons.Pause else PrismIcons.Play, if (isPlaying) tr(R.string.widget_pause) else tr(R.string.widget_play), playSize, playTouch) { pc.togglePlay() }
            }
        } else {
            PlayDisc(isPlaying, isLoading, filled = style == TransportStyle.BUTTON, size = if (compact) 64.dp else 78.dp) { pc.togglePlay() }
        }
        if (skips || episode) {
            if (episode) SkipGlyph(EPISODE_AHEAD_S, ahead = true, touch = 53.dp) { pc.seekBy(EPISODE_AHEAD_S * 1000L) }
            else TransportGlyph(nextIcon, tr(R.string.widget_next), skip, 53.dp, if (style == TransportStyle.GLYPHS) 0.85f else 1f, nextEnabled, onHold = ahead) { pc.next() }
        }
        if (modes) ModeGlyph(
            PrismIcons.Repeat,
            when (repeat) { Player.REPEAT_MODE_ALL -> tr(R.string.repeat_all); Player.REPEAT_MODE_ONE -> tr(R.string.repeat_one); else -> tr(R.string.repeat_off) },
            repeat != Player.REPEAT_MODE_OFF,
            badge = if (repeat == Player.REPEAT_MODE_ONE) "1" else null,
        ) { pc.cycleRepeat() }
    }
}

/** The Minimal player's one control (1.7.4): play / pause, large, in the Controls style picked; gestures do the rest. */
@Composable
fun MinimalPlay(isPlaying: Boolean, isLoading: Boolean) {
    val pc = LocalContainer.current.player
    val style = LocalAppSettings.current.transportStyle
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (style == TransportStyle.GLYPHS) {
            if (isLoading) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.padding(26.dp).size(40.dp))
            else TransportGlyph(if (isPlaying) PrismIcons.Pause else PrismIcons.Play, if (isPlaying) tr(R.string.widget_pause) else tr(R.string.widget_play), 84.dp, 100.dp) { pc.togglePlay() }
        } else PlayDisc(isPlaying, isLoading, filled = style == TransportStyle.BUTTON, size = 88.dp) { pc.togglePlay() }
    }
}

/** Shuffle or repeat beside the transport: bright with a dot under it when on, dim when off; [badge] marks repeat one. */
@Composable
private fun ModeGlyph(icon: ImageVector, description: String, active: Boolean, badge: String? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val alpha by animateFloatAsState(if (active) 1f else 0.45f, label = "modeAlpha")
    Box(
        Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = description) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        if (badge != null) Text(
            badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp),
        )
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape).background(Color.White))
    }
}

/**
 * Play / pause in a white disc ([filled]) or on glass, edged with one ring of split light: the disc's own edge, so
 * there's never a second ring or a gap that reads as one.
 */
@Composable
private fun PlayDisc(isPlaying: Boolean, isLoading: Boolean, filled: Boolean, size: Dp, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val pressed = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size + 14.dp).testTag("playDisc").clickable(pressed, indication = null) { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (filled) Color.White else Color.White.copy(alpha = 0.10f))
                .then(if (com.prism.music.ui.theme.LocalAppSettings.current.view.playRing) Modifier.border(2.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (filled) Color.Black else Color.White
            if (isLoading) CircularProgressIndicator(color = tint, strokeWidth = 1.5.dp, modifier = Modifier.size(size * 0.42f))
            else Crossfade(isPlaying, animationSpec = tween(160), label = "playDisc") { p ->
                Icon(if (p) PrismIcons.Pause else PrismIcons.Play, if (p) tr(R.string.widget_pause) else tr(R.string.widget_play), tint = tint, modifier = Modifier.size(size * (if (filled) 0.56f else 0.46f)))
            }
        }
    }
}

@Composable
private fun TransportGlyph(
    icon: ImageVector,
    description: String,
    size: Dp,
    touch: Dp,
    heightScale: Float = 1f,
    enabled: Boolean = true,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.3f, label = "transportAlpha")
    val haptics = rememberHaptics()
    val tap by rememberUpdatedState(onClick)
    val tapEnabled by rememberUpdatedState(enabled)
    val step by rememberUpdatedState(onHold)
    Box(
        Modifier.size(touch).then(
            if (onHold == null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { haptics.click(); onClick() }
            // A tap changes songs; holding seeks, one step at once and then again every moment it's held.
            // Holding works even when the tap can't (forward on the queue's last song).
            else Modifier
                .semantics { role = Role.Button; if (enabled) onClick { tap(); true } }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var ended = false
                        val up = withTimeoutOrNull(HOLD_TO_SEEK_MS) { waitForUpOrCancellation().also { ended = true } }
                        if (ended) {
                            if (up != null && tapEnabled) { up.consume(); haptics.click(); tap() }
                            return@awaitEachGesture
                        }
                        do {
                            haptics.tick()
                            step?.invoke()
                            ended = false
                            withTimeoutOrNull(HOLD_SEEK_REPEAT_MS) { waitForUpOrCancellation()?.consume(); ended = true }
                        } while (!ended)
                    }
                }
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(size).graphicsLayer { scaleY = heightScale })
    }
}

/** A circular arrow with the seconds in it: back ([ahead] false) or ahead by [seconds] in a podcast episode. */
@Composable
private fun SkipGlyph(seconds: Int, ahead: Boolean, touch: Dp, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val label = if (ahead) trp(R.plurals.ahead_n_seconds, seconds.toInt()) else trp(R.plurals.back_n_seconds, seconds.toInt())
    Box(
        Modifier.size(touch).clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label) { haptics.click(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Refresh turns clockwise (ahead); mirrored it turns back.
        Icon(PrismIcons.Refresh, null, tint = Color.White, modifier = Modifier.size(40.dp).graphicsLayer { if (!ahead) scaleX = -1f })
        Text("$seconds", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
    }
}

private const val EPISODE_BACK_S = 15
private const val EPISODE_AHEAD_S = 30

private const val HOLD_TO_SEEK_MS = 400L
private const val HOLD_SEEK_REPEAT_MS = 450L

/**
 * System media volume, mirrored into a value the bar can drag; or Prism's own volume (GitHub #116, Settings → Player →
 * "The volume slider changes"), which leaves the phone's alone.
 */
class PlayerVolume(
    context: Context,
    private val cast: com.prism.music.connect.Cast? = null,
    private val prefs: com.prism.music.data.prefs.SettingsRepository? = null,
) {
    private val am = context.getSystemService(AudioManager::class.java)
    /** Read each time: some phones give Bluetooth headphones a different number of steps than the speaker. */
    private val max get() = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
    var level by mutableFloatStateOf(read())
    var dragging by mutableStateOf(false)
    /** The step last asked of Android, so a drag only sends the steps it crosses. */
    private var sent = -1

    /** While casting it's the speaker's or TV's own volume (when it has one). */
    private val remote: Int? get() = cast?.takeIf { it.casting }?.volume

    /** The slider moves Prism's own volume, not the phone's. */
    private val own: Boolean get() = prefs?.current?.volumeControl == com.prism.music.data.prefs.VolumeControl.PRISM

    fun read(): Float = remote?.let { it / 100f }
        ?: if (own) prefs!!.current.appVolume
        else ((am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max)

    /**
     * Only a change of step reaches Android (#52). Every drag event used to set the volume, dozens a second: with
     * Bluetooth headphones each one is a message to the headphones, and they fell seconds behind the slider (or
     * dropped some), so the bar said 75% while the headphones played at 25%.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun drag(v: Float) {
        dragging = true
        level = v
        if (remote != null) { cast?.setVolume(kotlin.math.round(v * 100).toInt()); return }
        // Prism's own: heard at once (straight into the audio chain), saved when the finger lifts.
        if (own) { com.prism.music.playback.NormalizerProcessor.appVolume = com.prism.music.playback.NormalizerProcessor.volumeFor(v); return }
        val step = kotlin.math.round(v * max).toInt()
        if (step != sent) {
            sent = step
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        }
    }

    fun release() {
        dragging = false
        sent = -1
        if (remote == null && own) { prefs?.setAppVolume(level); return }
        // Back to the real level (the slider can rest between two steps).
        level = read()
    }
}

@Composable
fun rememberPlayerVolume(): PlayerVolume {
    val context = LocalContext.current
    val cast = LocalContainer.current.cast
    val prefs = LocalContainer.current.settings
    val v = remember { PlayerVolume(context, cast, prefs) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // Asked of Android off the main thread: a binder call twice a second showed in a slow phone's stalls (#103).
        lifecycle.tickWhileShown(500) {
            if (!v.dragging) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { v.read() }.let { if (!v.dragging) v.level = it }
        }
    }
    return v
}

val VOLUME_ROW_HEIGHT = 32.dp

/** The progress bar's line at rest and while held, for its [style] and thickness [thick] (the waveform aside). */
fun barHeights(style: ScrubberStyle, thick: Float): Pair<Dp, Dp> = when (style) {
    ScrubberStyle.HAIRLINE -> 5.dp to 10.dp
    ScrubberStyle.BOLD, ScrubberStyle.WAVE -> 9.dp to 14.dp
    ScrubberStyle.THUMB -> 4.dp to 6.dp
    ScrubberStyle.RIBBONS -> 6.dp to 9.dp
}.let { (idle, held) -> idle * thick to held * thick }

@Composable
fun VolumeRow(volume: PlayerVolume) {
    val s = LocalAppSettings.current
    // Reddit, 1.7.9.1: "Volume bar like the progress bar" takes its style's height and thickness; else its own, as before.
    val (idle, held) = if (s.volumeMatchesProgress) barHeights(s.scrubberStyle, s.scrubberThickness) else 6.dp to 10.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(PrismIcons.VolumeDown, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        ThinSlider(volume.level, volume::drag, Modifier.weight(1f).testTag("volumeBar"), volume::release, idleHeight = idle, activeHeight = held)
        Spacer(Modifier.width(10.dp))
        Icon(PrismIcons.VolumeUp, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
    }
}

/**
 * The player's bottom row, one capsule of glass: the buttons the listener picked (Settings → Player → Buttons, 1.7.4;
 * lyrics, the music video and the queue as 1.7 had it). While the queue is up the middle is shuffle / repeat /
 * autoplay instead, beside the queue's own button (to close it). Buttons that open something over the player (sleep
 * timer, add to playlist, share…) go to [onButton], which the player handles.
 */
@Composable
fun PlayerActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    videoMode: Boolean,
    videoAvailable: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onVideoMode: (Boolean) -> Unit,
    episode: Boolean = false,
    onButton: (PlayerButton) -> Unit = {},
) {
    val c = LocalContainer.current
    val pc = c.player
    if (episode) { EpisodeActionRow(queueOpen, onToggleQueue); return }
    val shuffle by pc.shuffle.collectAsState()
    val smart by c.queue.smartShuffle.collectAsState()
    val repeat by pc.repeatMode.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val videoLoading by pc.videoLoading.collectAsState()
    // Settings → Player → "Song / Video switch" off: audio only, the Video button goes.
    val chosen = settings.playerButtons.filter { it != PlayerButton.VIDEO || settings.showVideoSwitch }
    // Six to a row still fits a small phone: the buttons narrow a little past four.
    val glyph = when { chosen.size >= 6 -> 46.dp; chosen.size == 5 -> 50.dp; else -> 56.dp }
    // In one glass capsule (1.7), or (1.7.8, #155) as separate buttons spread across the row, as Prism 1.6 had them.
    val pill = settings.view.buttonsPill
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            if (pill) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(5.dp)
            else Modifier.fillMaxWidth(),
            horizontalArrangement = if (pill) Arrangement.spacedBy(4.dp) else Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                queueOpen,
                transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 140)) togetherWith fadeOut(tween(140))).using(SizeTransform(clip = false) { _, _ -> tween(220) })
                },
                label = "pill",
            ) { modes ->
                if (modes) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (PlayerButton.LYRICS in chosen) BottomGlyph(PrismIcons.Lyrics, tr(R.string.lyrics), false, onToggleLyrics)
                    Pill {
                        PillSegment(
                            if (smart) PrismIcons.SmartShuffle else PrismIcons.Shuffle, if (smart) tr(R.string.smart_shuffle) else tr(R.string.shuffle), shuffle,
                            width = PILL_SEGMENT_WIDTH_TRIPLE,
                        ) { pc.toggleShuffle() }
                        PillDivider()
                        PillSegment(
                            if (repeat == Player.REPEAT_MODE_ONE) null else PrismIcons.Repeat, tr(R.string.repeat),
                            repeat != Player.REPEAT_MODE_OFF, label = if (repeat == Player.REPEAT_MODE_ONE) "1" else null, width = PILL_SEGMENT_WIDTH_TRIPLE,
                        ) { pc.cycleRepeat() }
                        PillDivider()
                        PillSegment(PrismIcons.Autoplay, tr(R.string.autoplay), settings.autoplay, width = PILL_SEGMENT_WIDTH_TRIPLE) { c.settings.setAutoplay(!settings.autoplay) }
                    }
                    // #294: "Tidier queue": Back or pulling its title down closes it.
                    if (!settings.view.queueTidy) BottomGlyph(PrismIcons.Queue, tr(R.string.up_next), true, onToggleQueue)
                } else Row(
                    if (pill) Modifier else Modifier.fillMaxWidth(),
                    horizontalArrangement = if (pill) Arrangement.spacedBy(4.dp) else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (b in chosen) PlayerButtonGlyph(b, glyph, lyricsOpen, videoMode, videoAvailable, videoLoading, onToggleLyrics, onToggleQueue, onVideoMode, onButton)
                }
            }
        }
    }
}

/** One of the bottom row's buttons ([PlayerButton]), as it stands for the song playing (liked, downloaded, DJ on…). */
@Composable
private fun PlayerButtonGlyph(
    b: PlayerButton,
    width: Dp,
    lyricsOpen: Boolean,
    videoMode: Boolean,
    videoAvailable: Boolean,
    videoLoading: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onVideoMode: (Boolean) -> Unit,
    onButton: (PlayerButton) -> Unit,
) {
    val c = LocalContainer.current
    val pc = c.player
    val song by pc.currentSong.collectAsState()
    val s = song ?: return
    when (b) {
        PlayerButton.LYRICS -> BottomGlyph(if (lyricsOpen) PrismIcons.LyricsFilled else PrismIcons.Lyrics, if (lyricsOpen) tr(R.string.close_lyrics) else tr(R.string.lyrics), lyricsOpen, onToggleLyrics, width)
        // One Video toggle: on (marked) plays the music video; tapping again goes back to the song.
        PlayerButton.VIDEO -> PillSegment(
            PrismIcons.Video,
            when {
                videoMode -> tr(R.string.back_to_the_song)
                videoAvailable -> tr(R.string.music_video_3)
                pc.videoOffOnData.collectAsState().value -> tr(R.string.videos_are_off_on_mobile_data)
                else -> tr(R.string.no_music_video_for_this_song)
            },
            videoMode || videoLoading, enabled = videoAvailable || videoMode, loading = videoLoading, width = width,
        ) { onVideoMode(!(videoMode || videoLoading)) }
        PlayerButton.QUEUE -> BottomGlyph(PrismIcons.Queue, tr(R.string.up_next), false, onToggleQueue, width)
        PlayerButton.LIKE -> {
            val liked by c.library.likedIds.collectAsState()
            val on = s.id in liked
            BottomGlyph(if (on) PrismIcons.HeartFilled else PrismIcons.Heart, if (on) tr(R.string.remove_from_liked) else tr(R.string.widget_like), on, { c.library.toggleLike(s) }, width)
        }
        PlayerButton.DISLIKE -> {
            val disliked by c.library.dislikedIds.collectAsState()
            val on = s.id in disliked
            BottomGlyph(if (on) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown, tr(R.string.not_for_me), on, {
                c.library.setDisliked(s, !on)
                if (!on && pc.hasNext.value) pc.next()
            }, width)
        }
        PlayerButton.DOWNLOAD -> {
            val downloads by c.downloadState.collectAsState()
            val d = downloads[s.id]
            val done = d?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
            BottomGlyph(
                if (done) PrismIcons.DownloadDone else PrismIcons.Download,
                when { done -> tr(R.string.downloaded); d != null -> tr(R.string.downloading_2); else -> tr(R.string.download) }, done || d != null,
                { if (d != null) c.downloads.remove(s.id) else c.downloads.download(s) }, width, enabled = !s.isLocal && !s.isLive,
            )
        }
        PlayerButton.RADIO -> BottomGlyph(PrismIcons.Radio, tr(R.string.start_radio), false, { pc.playRadio(s) }, width, enabled = !s.isLocal)
        // Songs faster or slower (1.7.4), and higher or lower (1.7.8): a tap opens Speed & pitch; the speed shows while it isn't 1×.
        PlayerButton.SPEED -> {
            val speed by pc.musicSpeed.collectAsState()
            val pitch by pc.musicPitch.collectAsState()
            val haptics = rememberHaptics()
            if (speed == 1f) BottomGlyph(PrismIcons.Speed, if (pitch == 0) tr(R.string.speed_pitch_2) else tr(R.string.speed_pitch_pitch_moved), pitch != 0, { onButton(b) }, width, enabled = !s.isLive)
            else Box(
                Modifier.size(width = width, height = BOTTOM_ACTION_SIZE).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClickLabel = tr(R.string.speed_pitch, speedText(speed))) { haptics.tick(); onButton(b) },
                contentAlignment = Alignment.Center,
            ) {
                Text(speedText(speed), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = Color.White)
                OnMark(true)
            }
        }
        PlayerButton.DJ -> {
            val on by c.dj.on.collectAsState()
            BottomGlyph(if (on) PrismIcons.TurntableFilled else PrismIcons.Turntable, if (on) tr(R.string.dj_mode_off) else tr(R.string.dj_mode), on, { c.dj.setOn(!on) }, width)
        }
        PlayerButton.SLEEP -> {
            val at by pc.sleepAt.collectAsState()
            val end by pc.sleepEndOfSong.collectAsState()
            BottomGlyph(PrismIcons.Moon, tr(R.string.sleep_timer), at > 0 || end, { onButton(b) }, width)
        }
        PlayerButton.SING -> {
            val on by c.sing.on.collectAsState()
            BottomGlyph(PrismIcons.Mic, tr(R.string.sing), on, { onButton(b) }, width, enabled = !s.isEpisode)
        }
        PlayerButton.OUTPUT -> BottomGlyph(PrismIcons.Cast, tr(R.string.play_on), false, { onButton(b) }, width)
        PlayerButton.ADD_TO_PLAYLIST -> BottomGlyph(PrismIcons.PlaylistAdd, tr(R.string.add_to_playlist), false, { onButton(b) }, width)
        PlayerButton.SHARE -> BottomGlyph(PrismIcons.Share, tr(R.string.share), false, { onButton(b) }, width, enabled = !s.isLocal)
        PlayerButton.EQUALIZER -> BottomGlyph(PrismIcons.Equalizer, tr(R.string.equalizer), false, { onButton(b) }, width)
    }
}

/**
 * A podcast episode's capsule: no lyrics or music video (episodes have neither), but its playback speed, a tap away
 * (1× → 1.25× → … → 2× → 0.75×), and the queue.
 */
@Composable
private fun EpisodeActionRow(queueOpen: Boolean, onToggleQueue: () -> Unit) {
    val pc = LocalContainer.current.player
    val speed by pc.episodeSpeed.collectAsState()
    val haptics = rememberHaptics()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = "${speed.toString().removeSuffix(".0")}×"
            Box(
                Modifier.height(BOTTOM_ACTION_SIZE).clip(CircleShape)
                    .background(if (speed != 1f) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(onClickLabel = tr(R.string.playback_speed, label)) {
                        haptics.tick()
                        pc.setEpisodeSpeed(EPISODE_SPEEDS[(EPISODE_SPEEDS.indexOf(speed) + 1).mod(EPISODE_SPEEDS.size)])
                    }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            BottomGlyph(PrismIcons.Queue, tr(R.string.up_next), queueOpen, onToggleQueue)
        }
    }
}

/** The episode speeds a tap cycles through (the player's ⋯ has the same). */
val EPISODE_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
/** Songs' speeds (1.7.4): small steps, as music sounds odd far from its own tempo. */
val MUSIC_SPEEDS = listOf(1f, 1.1f, 1.25f, 1.5f, 0.75f, 0.9f)

private fun speedText(speed: Float) = speed.toString().removeSuffix(".0") + "×"

/** A group of bottom-row glyphs (shuffle, repeat, autoplay while the queue is up): Spectrum keeps them out of a capsule. */
@Composable
private fun Pill(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.height(BOTTOM_ACTION_SIZE).animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PillDivider() {
    Spacer(Modifier.width(4.dp))
}

/** The mark under a bottom-row glyph that's on (lyrics open, video playing, shuffle on): a short line of split light. */
@Composable
private fun BoxScope.OnMark(on: Boolean) {
    val shown by animateFloatAsState(if (on) 1f else 0f, tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD), label = "onMark")
    if (shown > 0f) Box(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 14.dp * shown, height = 2.dp)
            .clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrum),
    )
}

@Composable
private fun PillSegment(
    icon: ImageVector?,
    description: String,
    highlighted: Boolean,
    label: String? = null,
    enabled: Boolean = true,
    width: Dp = PILL_SEGMENT_WIDTH,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        Modifier.width(width).height(BOTTOM_ACTION_SIZE).clip(CircleShape)
            .background(if (highlighted && enabled) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .semantics { selected = highlighted }
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val tint = Color.White.copy(alpha = when { !enabled -> 0.25f; highlighted -> 1f; else -> 0.7f })
        Crossfade(Triple(icon, label, loading), animationSpec = tween(200), label = "segment") { (ic, lb, busy) ->
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 1.5.dp, modifier = Modifier.size(20.dp))
            else if (ic != null) Icon(ic, description, tint = tint, modifier = Modifier.size(24.dp))
            else if (lb != null) Text(lb, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        OnMark(highlighted && enabled)
    }
}

@Composable
private fun BottomGlyph(icon: ImageVector, description: String, highlighted: Boolean, onClick: () -> Unit, width: Dp = 56.dp, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    Box(
        Modifier.size(width = width, height = BOTTOM_ACTION_SIZE).clip(CircleShape)
            .background(if (highlighted && enabled) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .semantics { selected = highlighted }
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = when { !enabled -> 0.25f; highlighted -> 1f; else -> 0.7f }), modifier = Modifier.size(25.dp))
        OnMark(highlighted && enabled)
    }
}

/** A bare button beside the credits (like, menu): Spectrum keeps them out of discs; [active] (liked) in full white. */
@Composable
fun CircleGlyph(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val ink by animateFloatAsState(if (active) 1f else 0.8f, label = "glyphInk")
    val haptics = rememberHaptics()
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = if (active) 0.22f else 0.13f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = description) { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(icon, animationSpec = tween(180), label = "glyph") { Icon(it, description, tint = Color.White.copy(alpha = ink), modifier = Modifier.size(20.dp)) }
    }
}

/** The line under the transport: where the sound is going. Tap to pick another output, right in the player. */
@Composable
fun OutputCaption() {
    val context = LocalContext.current
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val preferred by c.player.preferredOutput.collectAsState()
    val profile by c.account.profile.collectAsState()
    val phoneName = (profile?.name ?: settings.accountName).trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { tr(R.string.s_phone, it) } ?: tr(R.string.this_phone)
    var current by remember { mutableStateOf(currentOutput(context, preferred)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(preferred, lifecycle) {
        lifecycle.tickWhileShown(1500) { current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { currentOutput(context, preferred) } }
    }
    var picking by remember { mutableStateOf(false) }
    val castState by c.cast.state.collectAsState()
    val castLabel = when (val cs = castState) {
        is com.prism.music.connect.CastState.Casting -> tr(R.string.playing_on, cs.renderer.name)
        is com.prism.music.connect.CastState.Connecting -> tr(R.string.connecting_to, cs.renderer.name)
        else -> null
    }
    // Where the sound goes, after a dot of split light; while casting, a glass pill with the cast mark, so it's plain
    // the music is playing somewhere else.
    val away = castLabel != null
    Row(
        Modifier.clip(CircleShape)
            .then(if (away) Modifier.background(Color.White.copy(alpha = 0.12f)).border(1.dp, com.prism.music.ui.theme.PrismTokens.spectrum, CircleShape) else Modifier)
            .clickable { picking = true }.padding(horizontal = if (away) 12.dp else 4.dp, vertical = if (away) 5.dp else 2.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (away) Icon(PrismIcons.Cast, null, Modifier.size(14.dp), tint = Color.White)
        else Box(Modifier.size(6.dp).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrumRing()))
        Spacer(Modifier.width(7.dp))
        Text(
            castLabel ?: current?.let { outputLabel(it, phoneName) } ?: phoneName,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = if (away) 0.95f else 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // The cast mark says the line opens speakers and TVs (Reddit 1.7: "where's the cast button?"). 1.8.0: no longer a
        // choice of its own (#149's switch is gone): the line itself can be hidden in What's on the player.
        if (!away) {
            Spacer(Modifier.width(7.dp))
            Icon(PrismIcons.Cast, tr(R.string.play_on_a_speaker_or_tv), Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.62f))
        }
    }
    if (picking) OutputPickerSheet(phoneName) { picking = false }
}

/**
 * While a sleep timer is set: a small moon and the time left ("12 min", "Song end") on the player, so it's plain the
 * music will stop. Tap it to change or cancel the timer. Nothing when no timer is set.
 */
@Composable
fun SleepBadge(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val sleepAt by c.player.sleepAt.collectAsState()
    val endOfSong by c.player.sleepEndOfSong.collectAsState()
    if (sleepAt <= 0 && !endOfSong) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sleepAt, lifecycle) { if (sleepAt > 0) lifecycle.tickWhileShown(15_000) { now = System.currentTimeMillis() } }
    val left = if (endOfSong) tr(R.string.song_end) else tr(R.string.min, ((sleepAt - now) / 60_000).coerceAtLeast(0) + 1)
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).clickable { open = true }
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = tr(R.string.sleep_timer_tap_to_change_it, left) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PrismIcons.Moon, null, Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.width(5.dp))
        Text(left, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
    if (open) SleepTimerDialog { open = false }
}

private val OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_DOCK,
)

private fun isBluetooth(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
    t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_HEARING_AID

/** Outputs music can play through right now: this phone, wired/USB, and connected Bluetooth devices. */
fun outputOptions(context: Context): List<AudioDeviceInfo> {
    val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in OUTPUT_TYPES }
        // One row per physical device (LE Audio and A2DP can both show for the same earbuds).
        .distinctBy { if (isBluetooth(it.type)) "bt:" + (it.addressOrBlank().ifBlank { it.productName.toString() }) else "t:${it.type}:${it.productName}" }
        .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else if (isBluetooth(it.type)) 2 else 1 }
}

/** Where audio is going: the device picked in Prism if still there, otherwise the system's media route. */
fun currentOutput(context: Context, preferred: AudioDeviceInfo?): AudioDeviceInfo? {
    val options = outputOptions(context)
    if (preferred != null) options.firstOrNull { it.id == preferred.id }?.let { return it }
    val am = context.getSystemService(AudioManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= 33 && am != null) {
        runCatching {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { routed ->
                options.firstOrNull { it.type == routed.type && (routed.address.isBlank() || it.address == routed.address) }
                    ?.let { return it }
            }
        }
    }
    return options.maxByOrNull { if (isBluetooth(it.type)) 3 else if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else 2 }
}

fun outputLabel(d: AudioDeviceInfo, phoneName: String): String = when (d.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> phoneName
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> tr(R.string.headphones)
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    else -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        ?: if (isBluetooth(d.type)) tr(R.string.bluetooth_device) else tr(R.string.usb_audio)
}

private fun outputIcon(d: AudioDeviceInfo): ImageVector = when {
    d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> PrismIcons.Phone
    d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || d.type == AudioDeviceInfo.TYPE_HDMI || d.type == AudioDeviceInfo.TYPE_DOCK -> PrismIcons.Speaker
    isBluetooth(d.type) -> PrismIcons.Headphones
    d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET -> PrismIcons.Usb
    else -> PrismIcons.Headphones
}

/**
 * Where the music plays, inside the player (no trip to the system's Bluetooth page): the song's cover as frosted glass,
 * like the player's other sheets, then glass groups for this phone's outputs, speakers and TVs on the Wi-Fi (Prism
 * Connect), and other Prism devices. What's playing sits on a white disc ringed in split light.
 */
@Composable
fun OutputPickerSheet(phoneName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val pc = c.player
    val song by pc.currentSong.collectAsState()
    val preferred by pc.preferredOutput.collectAsState()
    var options by remember { mutableStateOf(outputOptions(context)) }
    var current by remember { mutableStateOf(currentOutput(context, preferred)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(preferred, lifecycle) {
        lifecycle.tickWhileShown(1000) {
            val (o, now) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { outputOptions(context) to currentOutput(context, preferred) }
            options = o
            current = now
        }
    }
    val castState by c.cast.state.collectAsState()
    val castingTo = (castState as? com.prism.music.connect.CastState.Casting)?.renderer?.id
        ?: (castState as? com.prism.music.connect.CastState.Connecting)?.renderer?.id
    val castingName = (castState as? com.prism.music.connect.CastState.Casting)?.renderer?.name
    com.prism.music.ui.components.ActionSheet(song?.thumbnail, onDismiss) { _ ->
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
            Text(tr(R.string.play_on), style = MaterialTheme.typography.titleLarge)
            Text(
                castingName?.let { tr(R.string.playing_on, it) } ?: current?.let { tr(R.string.playing_on, outputLabel(it, phoneName)) } ?: tr(R.string.pick_where_the_music_plays),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // No scroll of its own: the sheet already scrolls (a scroller inside a scroller is measured with no height
        // limit, and Compose crashes on it).
        Column(Modifier.padding(bottom = 24.dp)) {
            OutputGroup(tr(R.string.this_phone_2)) {
                options.forEach { d ->
                    val selected = castingTo == null && current?.id == d.id
                    DeviceRow(
                        outputIcon(d), outputLabel(d, phoneName),
                        when {
                            d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tr(R.string.phone_speaker)
                            isBluetooth(d.type) -> tr(R.string.bluetooth)
                            d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                            else -> tr(R.string.wired)
                        },
                        selected = selected,
                    ) { pc.setOutput(d) }
                }
                if (android.os.Build.VERSION.SDK_INT >= 34) DeviceRow(PrismIcons.Headphones, tr(R.string.other_bluetooth_devices), tr(R.string.ones_that_aren_t_connected_yet)) {
                    if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) onDismiss()
                }
            }
            // Prism devices first: a TV with Prism on it plays there in the app, rather than in its own Cast player.
            PrismDevices()
            SpeakersAndTvs(castState, castingTo)
        }
    }
}

/** A glass group in the output picker, under a small label. */
@Composable
internal fun OutputGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 16.dp)) {
        com.prism.music.ui.components.Eyebrow(title, Modifier.padding(start = 8.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(ui.card).background(com.prism.music.ui.components.glassFill(1.2f)).border(0.7.dp, com.prism.music.ui.components.glassEdge(), ui.card), content = content)
    }
}

/**
 * One place music can play: its icon on a glass round (a white disc ringed in split light while it's the one
 * playing, a spinner while connecting), its name and kind, and an optional action at the end.
 */
@Composable
internal fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(start = 14.dp, end = if (trailing != null) 6.dp else 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (selected) scheme.onSurface else com.prism.music.ui.components.glassFill(1.8f))
                .then(if (selected) Modifier.border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) com.prism.music.ui.components.PrismSpinner(Modifier.size(20.dp), stroke = 2.dp)
            else Icon(icon, null, tint = if (selected) scheme.surface else scheme.onSurface, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle + if (selected) tr(R.string.playing) else "",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/** Prism Connect handoff in the output picker: other Prism devices on this Wi-Fi to move the music to (or bring it from). */
@Composable
private fun PrismDevices() {
    val handoff = LocalContainer.current.handoff
    val cast = LocalContainer.current.cast
    val devices by handoff.devices.collectAsState()
    val busy by handoff.busy.collectAsState()
    val castState by cast.state.collectAsState()
    val unreachable by handoff.unreachable.collectAsState()
    // A Prism TV the music plays on now (or is going to): it shows as playing, as a speaker cast to does.
    val tvNow = ((castState as? com.prism.music.connect.CastState.Casting)?.renderer ?: (castState as? com.prism.music.connect.CastState.Connecting)?.renderer)
        ?.takeIf { it.kind == com.prism.music.connect.RendererKind.PRISM_TV }
    DisposableEffect(Unit) {
        handoff.startDiscovery()
        onDispose { handoff.stopDiscovery() }
    }
    // A TV running Prism (1.8.0) gets its remote here (with its sign-in and the look), beside moving the music there.
    var remoteFor by remember { mutableStateOf<com.prism.music.connect.PrismDevice?>(null) }
    remoteFor?.let { tv -> com.prism.music.ui.components.TvRemoteSheet(tv) { remoteFor = null } }
    if (devices.isEmpty() && busy == null) return
    OutputGroup(tr(R.string.prism_devices)) {
        busy?.let { label -> DeviceRow(PrismIcons.Handoff, label, tr(R.string.one_moment), busy = true, enabled = false) {} }
        devices.forEach { d ->
            // A TV plays along with this phone (1.8.0), the phone staying its remote; another phone takes the music over.
            val thisTv = d.tv && tvNow != null && tvNow.id == "prism:${d.id ?: d.key}"
            val connecting = thisTv && castState is com.prism.music.connect.CastState.Connecting
            DeviceRow(
                if (d.tv) PrismIcons.Tv else PrismIcons.Handoff, d.name,
                when {
                    connecting -> tvNow!!.detail + tr(R.string.connecting)
                    thisTv -> tvNow!!.detail
                    // It didn't answer last time (1.8.1): said on its row, and a tap tries again (afresh: its addresses
                    // are looked up again if the ones known don't answer).
                    d.key in unreachable -> tr(R.string.link_tap_to_try_again)
                    else -> tr(R.string.move_the_music_there) + if (d.paired) "" else tr(R.string.pairs_the_first_time)
                },
                selected = thisTv && !connecting, busy = connecting, enabled = busy == null,
                trailing = {
                    if (d.tv) com.prism.music.ui.components.PrismTextButton(onClick = { remoteFor = d }, enabled = busy == null) { Text(tr(R.string.tv_remote)) }
                    else com.prism.music.ui.components.PrismTextButton(onClick = { handoff.bringFrom(d) }, enabled = busy == null) { Text(tr(R.string.bring_here)) }
                },
            ) { if (d.tv) { if (!thisTv) cast.connectTv(d) } else handoff.moveTo(d) }
        }
    }
}

private val speakerWords = listOf("speaker", "sonos", "receiver", "avr", "denon", "marantz", "yamaha", "onkyo", "pioneer", "bose", "amp", "audio", "soundbar", "wiim", "bluesound", "heos")

/** Prism Connect in the output picker: speakers, TVs and receivers on this Wi-Fi (Google Cast, UPnP / DLNA), looked for while it's open. */
@Composable
private fun SpeakersAndTvs(castState: com.prism.music.connect.CastState, castingTo: String?) {
    val cast = LocalContainer.current.cast
    val handoff = LocalContainer.current.handoff
    val renderers by cast.renderers.collectAsState()
    val prismTvs by handoff.devices.collectAsState()
    var lookedLong by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        cast.startDiscovery()
        onDispose { cast.stopDiscovery() }
    }
    LaunchedEffect(Unit) { delay(8_000); lookedLong = true }
    val shown = renderers.let { list ->
        // The one being cast to stays listed even if it's stopped answering searches.
        val playing = (castState as? com.prism.music.connect.CastState.Casting)?.renderer ?: (castState as? com.prism.music.connect.CastState.Connecting)?.renderer
        val all = if (playing != null && playing.kind != com.prism.music.connect.RendererKind.PRISM_TV && list.none { it.id == playing.id }) listOf(playing) + list else list
        // A TV whose Prism is listed under Prism devices isn't listed again as its own Cast or DLNA player.
        all.filter { r -> r.id == castingTo || prismTvs.none { it.tv && rendererHost(r) in it.hosts } }
    }
    // A TV that had Prism on it, not open now: the TV's own player still works, and Prism plays there once opened.
    val closedPrismTv = shown.firstNotNullOfOrNull { r -> handoff.prismTvAt(rendererHost(r)) }
    OutputGroup(tr(R.string.speakers_tvs)) {
        shown.forEach { r ->
            val selected = r.id == castingTo
            val connecting = castState is com.prism.music.connect.CastState.Connecting && selected
            val speaker = speakerWords.any { w -> "${r.name} ${r.manufacturer} ${r.model}".lowercase().contains(w) }
            DeviceRow(
                if (speaker) PrismIcons.Speaker else PrismIcons.Tv, r.name,
                r.detail + if (connecting) tr(R.string.connecting) else "",
                selected = selected && !connecting, busy = connecting,
            ) { if (!selected) cast.connect(r) }
        }
        // A Prism TV's music comes back to this phone, playing; a speaker's pauses.
        if (castingTo != null) DeviceRow(PrismIcons.Close, tr(R.string.stop_casting), tr(if (cast.toPrismTv) R.string.tv_stop_comes_back else R.string.the_music_pauses_pick_this_phone)) {
            cast.disconnect(keepPlaying = false)
        }
        if (shown.isEmpty()) {
            if (!lookedLong) DeviceRow(PrismIcons.Cast, tr(R.string.looking_on_this_wi_fi), tr(R.string.speakers_tvs_and_receivers), busy = true, enabled = false) {}
            else Text(
                tr(R.string.none_found_they_show_here_when),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        closedPrismTv?.let { name ->
            Text(
                tr(R.string.open_prism_on_first_android_only, name),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).testTag("output/open_prism_on_tv"),
            )
        }
    }
}

/** The address a Cast or DLNA player answers on (its location's host), to tell when it's a TV with Prism on it. */
internal fun rendererHost(r: com.prism.music.connect.Renderer): String? =
    runCatching { java.net.URI(r.location.replace("googlecast://", "http://")).host }.getOrNull()

@Composable
fun PlaybackOriginCaption(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(shadow = textShadow),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = PLAYER_GUTTER, vertical = 1.dp),
    )
}

/** Title / artist line that scrolls only when it doesn't fit, after a rest. */
@Composable
fun PlayerMarquee(text: String, style: TextStyle, color: Color, enabled: Boolean, modifier: Modifier = Modifier, delayMs: Int = 2500) =
    PlayerMarquee(androidx.compose.ui.text.AnnotatedString(text), style, color, enabled, modifier, delayMs)

/**
 * The artists' line under the player's title (#289, 1.8.1): with [separate] on and more than one artist, each name is its
 * own link to [open]; otherwise (or when a name can't be found in [text]) plain words, the line itself asking which.
 */
@Composable
fun artistLinkText(
    text: String,
    linked: List<com.prism.music.data.model.ArtistRef>,
    separate: Boolean,
    open: (com.prism.music.data.model.ArtistRef) -> Unit,
): androidx.compose.ui.text.AnnotatedString {
    val go by androidx.compose.runtime.rememberUpdatedState(open)
    return remember(text, linked, separate) {
        val spans = if (separate) com.prism.music.ui.components.artistSpans(text, linked) else null
        if (spans == null) androidx.compose.ui.text.AnnotatedString(text)
        else androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            spans.forEach { (range, a) ->
                addLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable("artist:${a.id ?: a.name}") { go(a) },
                    range.first, range.last + 1,
                )
            }
        }
    }
}

/** [PlayerMarquee] for words that carry links (#289: each artist's name opens their page). */
@Composable
fun PlayerMarquee(text: androidx.compose.ui.text.AnnotatedString, style: TextStyle, color: Color, enabled: Boolean, modifier: Modifier = Modifier, delayMs: Int = 2500) {
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.then(
            if (enabled) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = delayMs, repeatDelayMillis = 5000, velocity = 26.dp)
            else Modifier
        ),
        overflow = if (enabled) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/**
 * The quality badge's words: "Opus 160 kbps" for a stream; for lossless audio the file's format and resolution
 * ("WAV · 16-bit · 44.1 kHz", "FLAC · 96 kHz"), since a bitrate says little there (a WAV read "AUDIO/RAW 705 kbps").
 */
fun qualityText(stats: com.prism.music.playback.NerdStats): String {
    val codec = stats.codec.takeIf { it.isNotBlank() }?.let(::codecName).orEmpty()
    val lossless = stats.source.startsWith("Lossless") ||
        listOf("flac", "alac", "wav", "pcm", "aiff", "raw").any { stats.codec.contains(it, true) }
    return when {
        stats.source == "Downloaded" -> tr(R.string.downloaded)
        // One of your own files (#314): "MP3 · 320 kbps, from your file".
        stats.source.startsWith(com.prism.music.data.local.OwnFiles.SOURCE + " · ") ->
            tr(R.string.x_from_your_file, stats.source.removePrefix(com.prism.music.data.local.OwnFiles.SOURCE + " · "))
        lossless -> {
            // The file's own format when Prism knows it ("On this phone · WAV", "Lossless · FLAC on this phone").
            val named = Regex("\\b(WAV|AIFF|FLAC|ALAC)\\b").find(stats.source)?.value
            listOfNotNull(
                named ?: codec.takeIf { it != "PCM" && it.isNotBlank() } ?: tr(R.string.lossless),
                stats.bitDepth.takeIf { it > 0 }?.let { "$it-bit" },
                stats.sampleRate.takeIf { it > 0 }?.let(::sampleRateText),
            ).joinToString(" · ")
        }
        stats.streamBitrateKbps > 0 -> tr(R.string.kbps, codec, stats.streamBitrateKbps).trim()
        else -> codec
    }
}

/** Quality badge between the timestamps; tap for the full stats panel. */
@Composable
fun QualityLabel(modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val stats by LocalContainer.current.nerdStats.collectAsState()
    val text = qualityText(stats)
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = Color.White.copy(alpha = if (selected) 0.95f else 0.55f),
        modifier = modifier.clip(CircleShape)
            .background(Color.White.copy(alpha = if (selected) 0.14f else 0f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

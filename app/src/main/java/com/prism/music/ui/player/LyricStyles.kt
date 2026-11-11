package com.prism.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsColor
import com.prism.music.data.prefs.LyricsMotion
import com.prism.music.ui.theme.PrismTokens
import kotlin.math.roundToInt

/*
 * Lyrics, your way (1.7.5): how lyrics are drawn, worked out once from the settings ([rememberLyricsLook]) and used by every
 * place that shows them (the lyrics page, the one-line strip, immersive mode, the Looks preview and the settings' examples),
 * so they all match. Every default is the lyrics as 1.7.4 drew them.
 */

/** Everything about how lyrics look, from the settings. */
@Immutable
data class LyricsLook(
    /** How the sung line moves, whether lines timed as a whole move word by word too, and Glow's halo (1.8.0's parts). */
    val motion: LyricsMotion = LyricsMotion.GROW,
    val wordByWord: Boolean = false,
    val halo: Boolean = false,
    /** The size the lines are drawn at against 1.7's (Bold card's lines are bigger). */
    val sizeScale: Float = 1f,
    /** The sung words' fill: null is plain white (as before). */
    val sung: Brush? = null,
    /** One color standing for [sung] (a glow, a spectrum's middle). */
    val sungSolid: Color = Color.White,
    /** The other lines' color; their brightness is [othersAlpha] (times how far they are from the sung one). */
    val others: Color = Color.White,
    val othersAlpha: Float = 0.55f,
    /** The lines' weight, and where the sung word's grows to (Prism's sweep). */
    val weight: Int = 300,
    val sungWeight: Int = 500,
    /** Focus blur's strength (0 none). */
    val blur: Float = 1f,
    val spacing: Float = 1f,
    val backingSize: Float = 0.6f,
    val shadow: Shadow? = null,
    val uppercase: Boolean = false,
    val extraSize: Float = 1f,
    val extraColor: Color = Color.White,
    val extraItalic: Boolean = false,
    val extraAbove: Boolean = false,
    /** How many lines to come show (Int.MAX_VALUE all), and where the sung line settles (0 top … ~0.4 middle). */
    val ahead: Int = Int.MAX_VALUE,
    val position: Float = 0f,
    /** Bold card: the color the lines sit on (null for every other style). */
    val card: Color? = null,
) {
    /** Lines timed as a whole are drawn plain, not moving (Prism's own sweep, as 1.7 drew them). */
    val plainWhenLineTimed: Boolean get() = motion == LyricsMotion.GROW && !wordByWord

    /** The same look with no card under it (a wide player's lyrics pane, immersive mode): the other lines dim white. */
    fun withoutCard(othersAlpha: Float): LyricsLook = if (card == null) this else copy(card = null, others = Color.White, othersAlpha = othersAlpha)

    /** The text a line shows (in capitals when that's picked). */
    fun shown(line: LyricLine): LyricLine = if (uppercase) line.upper() else line

    companion object {
        /** The lyrics exactly as 1.7.4 drew them (Prism's defaults). */
        val Classic = LyricsLook()
    }
}

/** A soft shadow under the words, so they read over photos and moving covers. */
val LyricsShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 14f)

/** A lyrics color as a fill; null for white (what 1.7 drew). */
@Composable
internal fun lyricsFill(color: LyricsColor, custom: Int, cover: String?): Brush? = when (color) {
    LyricsColor.WHITE -> null
    LyricsColor.SPECTRUM -> PrismTokens.spectrum(true)
    LyricsColor.ACCENT -> scrubberFill(com.prism.music.data.prefs.ScrubberColor.ACCENT, cover)
    LyricsColor.COVER -> scrubberFill(com.prism.music.data.prefs.ScrubberColor.COVER, cover)
    LyricsColor.CUSTOM -> SolidColor(readableOnDark(Color(custom)))
}

/** One color for a fill: its own for a solid one, the light's middle for the spectrum. */
internal fun Brush?.solid(): Color = when (this) {
    null -> Color.White
    is SolidColor -> value
    else -> PrismTokens.spectrumColors(true).let { it[it.size / 2].second }
}

/** [c] lightened until it reads on the lyrics' dark backgrounds. */
internal fun readableOnDark(c: Color): Color {
    var out = c.copy(alpha = 1f)
    var n = 0
    while (out.luminance() < 0.22f && n++ < 8) out = lerp(out, Color.White, 0.16f)
    return out
}

/** Bold card's ground: the cover's color, steadied to a middle brightness so white lines read on it. */
internal fun cardColor(c: Color): Color {
    var out = c.copy(alpha = 1f)
    var n = 0
    while (out.luminance() > 0.2f && n++ < 10) out = lerp(out, Color.Black, 0.12f)
    n = 0
    while (out.luminance() < 0.06f && n++ < 10) out = lerp(out, Color.White, 0.08f)
    return out
}

/** The lyrics' look from [s], for a song with the cover [cover]. */
@Composable
fun rememberLyricsLook(s: AppSettings, cover: String?): LyricsLook {
    val p = s.lyricsLook
    val sung = lyricsFill(p.sungColor, p.sungCustom, cover)
    val others = lyricsFill(p.otherColor, p.otherCustom, cover).solid()
    val extra = lyricsFill(p.extraColor, p.otherCustom, cover).solid()
    // The card's ground is there only when the lyrics page shows the cover's color behind it.
    val palette = if (p.onCard) com.prism.music.ui.components.rememberArtPalette(cover) else null
    val card = if (!p.onCard) null else cardColor(palette?.vibrant ?: palette?.dominant ?: MaterialTheme.colorScheme.primary)
    return remember(p, sung, others, extra, card) { lyricsLookOf(p, sung, others, extra, card) }
}

/**
 * The look [p] draws, given its colors as fills ([sung] null for white; [others] and [extra] as one color each) and Bold
 * card's ground ([card], null when the card isn't drawn). Worked out the same way for the lyrics and their examples.
 */
fun lyricsLookOf(p: com.prism.music.data.prefs.LyricsLookPrefs, sung: Brush?, others: Color, extra: Color, card: Color?): LyricsLook {
    val motion = p.motionNow
    // Color fills needs a color to fill with: white fills with your light.
    val fill = sung ?: if (motion == LyricsMotion.FILL) PrismTokens.spectrum(true) else null
    val weight = p.weightNow.weight
    return LyricsLook(
        motion = motion,
        wordByWord = p.wordByWordNow,
        halo = p.haloNow,
        sizeScale = p.sizeScale,
        sung = fill,
        sungSolid = fill.solid(),
        others = if (card != null) Color.Black else others,
        othersAlpha = if (card != null) 0.8f else p.othersAlpha,
        weight = weight,
        sungWeight = (weight + 200).coerceAtMost(900),
        blur = if (card != null) 0f else p.blurAmount,
        spacing = p.spacing,
        backingSize = p.backingSize,
        shadow = if (p.shadow) LyricsShadow else null,
        uppercase = p.uppercase,
        extraSize = p.extraSize,
        extraColor = extra,
        extraItalic = p.extraItalic,
        extraAbove = p.extraPlace == com.prism.music.data.prefs.LyricsExtraPlace.ABOVE,
        ahead = p.ahead.lines,
        position = p.position.fraction,
        card = card,
    )
}

/** What's behind the lyrics page: Bold card brings its own color when the background is left as it is. */
fun AppSettings.lyricsPageBackground(): LyricsBackground = lyricsLook.pageBackground

/** The line in capitals, its words too (a word whose capitals are a different length, like ß, stays as it was). */
internal fun LyricLine.upper(): LyricLine {
    fun up(s: String) = s.uppercase().takeIf { it.length == s.length } ?: s
    return copy(text = up(text), words = words.map { it.copy(text = up(it.text)) }, background = background.map { it.copy(text = up(it.text)) })
}

/**
 * A line timed only as a whole, given words of its own: its time shared out by length, the way a karaoke screen fills a
 * line, over at most the time the words take to say (a line's time can run on through a pause). Word-timed lines,
 * breaks and lines without an end are as they are.
 */
internal fun LyricLine.withWords(): LyricLine {
    if (isWordSynced || isGap || endMs <= timeMs || text.isBlank()) return this
    val tokens = Regex("\\S+\\s*").findAll(text).map { it.value }.toList()
    if (tokens.isEmpty()) return this
    val letters = tokens.sumOf { it.trimEnd().length }.coerceAtLeast(1)
    val span = minOf(endMs - timeMs, letters * 75L + 500L, (endMs - timeMs) * 9 / 10).coerceAtLeast(200L)
    var at = timeMs
    val words = tokens.map { t ->
        val d = span * t.trimEnd().length / letters
        LyricWord(at, at + d, t).also { at += d }
    }
    // Keep the line's text exactly as its words read, so a sweep lines up.
    return copy(text = words.joinToString("") { it.text }, words = words)
}

/** How the line being sung is drawn ([sungPlan]): all at once, growing bolder, a light sweeping through, word by word. */
internal enum class SungKind { PLAIN, GROW, SWEEP, TYPEWRITER }

/**
 * The line being sung as [SungLine] draws it: [kind]; the weight it's set in ([weight], null keeps the caller's); the
 * words still to come ([dim], for a sweep or growing words); and Glow's halo behind it ([halo]).
 */
internal data class SungPlan(val kind: SungKind, val weight: Int?, val dim: Color = Color.Unspecified, val halo: Boolean = false)

/** Lines timed only as a whole get words of their own first ([withWords]): the look moves them word by word. */
internal val LyricsLook.timesWords: Boolean get() = wordByWord && motion != LyricsMotion.NONE

/**
 * How the line is drawn in this look: [wordSynced] (after [timesWords]), on Bold card's color ([onCard]), on the strip
 * ([strip]), with a typeface on a weight axis ([axis]). A glow makes the line a step bolder, as Glow drew it.
 */
internal fun LyricsLook.sungPlan(wordSynced: Boolean, onCard: Boolean, strip: Boolean, axis: Boolean, dimAlpha: Float): SungPlan {
    val bump = if (halo) 100 else 0
    val sweepDim = if (onCard) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = dimAlpha)
    val plan = when (motion) {
        LyricsMotion.GROW -> when {
            !wordSynced -> SungPlan(SungKind.PLAIN, sungWeight)
            // The strip sweeps a light through the words (it always has); the page grows them bolder.
            axis && !strip -> SungPlan(SungKind.GROW, null)
            else -> SungPlan(SungKind.SWEEP, if (strip) null else sungWeight, Color.White.copy(alpha = dimAlpha))
        }
        // Unsung words full white, the color filling them as they're sung.
        LyricsMotion.FILL -> if (!wordSynced) SungPlan(SungKind.PLAIN, null)
            else SungPlan(SungKind.SWEEP, weight + 100, if (onCard) sweepDim else Color.White.copy(alpha = 0.92f))
        LyricsMotion.SWEEP -> if (!wordSynced) SungPlan(SungKind.PLAIN, weight + bump) else SungPlan(SungKind.SWEEP, weight + bump, sweepDim)
        LyricsMotion.TYPEWRITER -> if (!wordSynced) SungPlan(SungKind.PLAIN, null) else SungPlan(SungKind.TYPEWRITER, weight + 100)
        // No movement: the line is simply as bright as it gets.
        LyricsMotion.NONE -> SungPlan(SungKind.PLAIN, weight + bump)
    }
    return plan.copy(halo = halo)
}

/**
 * The line being sung, drawn in [look]'s animation. [onCard]: on Bold card's own color (the lyrics page), where the words
 * still to come are dark; elsewhere they're dim white. [strip]: the one-line strip above the scrubber.
 */
@Composable
fun SungLine(
    line: LyricLine,
    clock: State<Long>,
    style: TextStyle,
    look: LyricsLook,
    axis: FontFamily?,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onCard: Boolean = false,
    strip: Boolean = false,
    dimAlpha: Float = 0.38f,
) {
    val st = style.copy(shadow = look.shadow ?: style.shadow)
    val timed = if (look.timesWords) remember(line) { line.withWords() } else line
    val plan = look.sungPlan(timed.isWordSynced, onCard, strip, axis != null, dimAlpha)
    val set = if (plan.weight != null) st.copy(fontWeight = FontWeight(plan.weight)) else st
    val drawn = @Composable { m: Modifier ->
        when (plan.kind) {
            SungKind.PLAIN -> PlainSung(line, set, look, m, maxLines)
            SungKind.GROW -> WeightSweptLine(timed, clock, st, axis!!, m, look.weight, look.sungWeight, look.sung)
            SungKind.SWEEP -> SweptLine(timed, clock, set, m, maxLines, litBrush = look.sung, dimColor = plan.dim)
            SungKind.TYPEWRITER -> TypewriterLine(timed, clock, set, look, m, maxLines)
        }
    }
    // The glow is a blurred copy of the sung words behind them (a text shadow is cut off at the line's edges). The copy is
    // the letters thickened by an outline and only lightly blurred, so the light hugs each letter's shape instead of
    // clouding the space round the words (a 10 dp blur did that before 1.7.5's release). Below Android 12 there's no blur,
    // so no glow copy.
    if (!plan.halo || android.os.Build.VERSION.SDK_INT < 31) { drawn(modifier); return }
    val glowFill = SolidColor(look.sungSolid.copy(alpha = 0.85f))
    val bright = if (plan.kind == SungKind.GROW) st.copy(fontWeight = FontWeight(look.sungWeight)) else set
    val density = androidx.compose.ui.platform.LocalDensity.current
    val glowStyle = remember(bright, density) {
        val px = with(density) { if (bright.fontSize.isSpecified) bright.fontSize.toPx() else 24.dp.toPx() }
        bright.copy(
            brush = glowFill, shadow = null,
            drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = px * GLOW_OUTLINE, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
    // Its children fill the line's width when the line does (the lyrics page), and wrap their words when it doesn't (the strip).
    Box(modifier, propagateMinConstraints = true) {
        val soft = Modifier.matchParentSize().blur(GLOW_BLUR, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
        if (!timed.isWordSynced || plan.kind == SungKind.PLAIN) Text(timed.text, soft, style = glowStyle, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        else SweptLine(timed, clock, glowStyle, soft, maxLines, litBrush = glowFill, dimColor = Color.Transparent)
        drawn(Modifier)
    }
}

/** A line not being sung, in the look's color and weight at [alpha]. */
@Composable
fun RestingLine(line: LyricLine, style: TextStyle, look: LyricsLook, alpha: Float, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        line.text, modifier, color = look.others.copy(alpha = alpha), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = style.copy(fontWeight = FontWeight(look.weight), shadow = look.shadow ?: style.shadow),
    )
}

/** The sung line all at once, in the sung color. */
@Composable
private fun PlainSung(line: LyricLine, style: TextStyle, look: LyricsLook, modifier: Modifier, maxLines: Int) {
    if (look.sung != null) Text(line.text, modifier, style = style.copy(brush = look.sung), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    else Text(line.text, modifier, style = style, color = Color.White, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Typewriter: each word appears as it's sung (quickly fading in), the rest of the line not there yet. */
@Composable
private fun TypewriterLine(line: LyricLine, clock: State<Long>, style: TextStyle, look: LyricsLook, modifier: Modifier, maxLines: Int) {
    // Each word's visibility in tenths, recomposing only when one changes.
    val shown by remember(line) {
        derivedStateOf {
            val now = clock.value
            line.words.map { w -> ((now - w.startMs).toFloat() / TYPE_IN_MS).coerceIn(0f, 1f).let { (it * 10).roundToInt() } }
        }
    }
    val text = buildAnnotatedString {
        line.words.forEachIndexed { i, w ->
            val a = shown[i] / 10f
            if (look.sung != null) pushStyle(SpanStyle(brush = look.sung, alpha = a)) else pushStyle(SpanStyle(color = Color.White.copy(alpha = a)))
            append(w.text)
            pop()
        }
    }
    Box(modifier) { Text(text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
}

private const val TYPE_IN_MS = 140f

/** Glow: the outline that thickens the glowing copy (a share of the font size), and how far it's blurred. */
private const val GLOW_OUTLINE = 0.07f
private val GLOW_BLUR = 3.dp

/**
 * What's behind the lyrics when it isn't the player's own ([LyricsBackground.PLAYER] draws nothing here): plain dark, the
 * cover blurred ([blurred]), the animated cover ([canvas], else the blurred cover), the cover's color, or the listener's
 * colors. Every choice is kept dark enough for white words.
 */
@Composable
fun LyricsBackdrop(
    kind: LyricsBackground,
    s: AppSettings,
    cover: String?,
    blurred: androidx.compose.ui.graphics.ImageBitmap?,
    canvas: com.prism.music.data.canvas.CanvasArtwork?,
    canvasSaved: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    hold: Boolean = false,
) {
    val scrim = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.5f to Color.Black.copy(alpha = 0.42f), 1f to Color.Black.copy(alpha = 0.7f))
    Box(modifier) {
        when (kind) {
            LyricsBackground.PLAYER -> {}
            LyricsBackground.DARK -> Box(Modifier.matchParentSize().background(Color(0xFF0C0C0C)))
            LyricsBackground.BLUR -> FullArtworkBlurBackdrop(blurred, Modifier.matchParentSize(), scrim = 1.15f)
            LyricsBackground.CANVAS -> if (canvas != null) {
                FullArtworkBlurBackdrop(blurred, Modifier.matchParentSize(), scrim = 1.15f)
                CanvasPlayer(canvas, canvasSaved, isPlaying, Modifier.matchParentSize(), hold = hold)
                Box(Modifier.matchParentSize().background(scrim))
            } else FullArtworkBlurBackdrop(blurred, Modifier.matchParentSize(), scrim = 1.15f)
            LyricsBackground.COVER_COLOR -> {
                val palette = com.prism.music.ui.components.rememberArtPalette(cover)
                val ground = cardColor(palette?.vibrant ?: palette?.dominant ?: Color(0xFF272727))
                val animated by androidx.compose.animation.animateColorAsState(ground, androidx.compose.animation.core.tween(500), label = "cardGround")
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(animated, lerp(animated, Color.Black, 0.35f)))))
            }
            LyricsBackground.COLOR -> {
                val first = cardColor(Color(s.lyricsBgColor))
                val second = if (s.lyricsBgColor2 == 0) lerp(first, Color.Black, 0.3f) else cardColor(Color(s.lyricsBgColor2))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(first, second))))
            }
        }
    }
}

/** A translation's or romanized line's style from [base] (the line's), as the look sets it. */
fun LyricsLook.extraStyle(base: TextStyle, size: Float, height: Float): TextStyle = base.copy(
    fontSize = base.fontSize * size * extraSize, lineHeight = base.lineHeight * height * extraSize, fontWeight = FontWeight.Normal,
    fontStyle = if (extraItalic) FontStyle.Italic else FontStyle.Normal, shadow = shadow ?: base.shadow,
)

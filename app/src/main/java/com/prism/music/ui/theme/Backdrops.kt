package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.prism.music.ui.components.AmbientFrost
import com.prism.music.ui.components.LocalFrosted
import com.prism.music.ui.components.LocalPageBackdrop
import com.prism.music.ui.components.PageBackdrop
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.model.hiRes
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The screens whose background can be changed. */
enum class BackdropScreen(val label: String) { HOME(tr(R.string.home)), SEARCH(tr(R.string.search)), LIBRARY(tr(R.string.library)), REPLAY(tr(R.string.replay_2)), SETTINGS(tr(R.string.settings)) }

/**
 * The player's own background (1.7.4), kept in the same map as the screens' (so Looks keep its photo too), but not a
 * screen: "Same on every screen" leaves it alone. Missing, the player's "A background" shows Home's.
 */
const val PLAYER_BACKDROP = "PLAYER"

/** The screens' backgrounds alone, without the player's. */
val Map<String, String>.screensOnly: Map<String, String> get() = filterKeys { it != PLAYER_BACKDROP }

/** How a ready-made background is drawn. */
enum class BackdropKind {
    /** Default, Now playing and Your photo: drawn specially. */
    SPECIAL,
    /** A base color with three slow-drifting light fields. */
    FIELDS,
    /** A quiet top-to-bottom blend of two colors. */
    BLEND,
    /** The theme's accent, softly, over the theme's background (follows the accent color). */
    TINT,
    /** Simple line patterns in the accent color over the theme's background. */
    DOTS, PAPER, PINSTRIPE, WAVES, RIPPLES, DIAMONDS, ZIGZAG, HALFTONE, CROSSES, SCALES, CONFETTI,
    /** Detailed illustrations in their own palettes ([Design]). */
    CONSTELLATION, CONTOURS, MOSAIC, BOKEH, SILK, HORIZON,
    /** The seasonal scenes (1.8.0, [SeasonalDesigns]): Thanksgiving's harvest and Christmas. */
    HARVEST, HOLIDAY,
}

/** Which row of the picker a background sits in. */
enum class BackdropGroup(val label: String) { YOURS(tr(R.string.yours)), COLOR(tr(R.string.color_2)), SIMPLE(tr(R.string.simple)), DESIGNS(tr(R.string.designs)) }

/**
 * Ready-made backgrounds. Each has a dark and a light version (base color first, then its light
 * fields), so the same pick looks at home in either theme: deep and glowing in dark mode, airy
 * pastels in light mode. Pattern designs take their ink from the theme's accent instead.
 * Names are stored in settings, so keep them stable.
 */
enum class Backdrop(
    val label: String,
    val kind: BackdropKind,
    val group: BackdropGroup,
    val dark: List<Long> = emptyList(),
    val light: List<Long> = emptyList(),
    /** Only offered in this season's month (a seasonal Look's own background); null for always. */
    val season: com.prism.music.data.looks.Season? = null,
) {
    /** Ambient: frost from what's playing (Aura × Spectrum's own look), or the plain page with frosted pages off. */
    DEFAULT(tr(R.string.ambient), BackdropKind.SPECIAL, BackdropGroup.YOURS),
    ARTWORK(tr(R.string.widget_now_playing), BackdropKind.SPECIAL, BackdropGroup.YOURS),
    PHOTO(tr(R.string.your_photo), BackdropKind.SPECIAL, BackdropGroup.YOURS),

    /** The prism's split light as three slow fields: red, green and violet (1.7). */
    SPECTRUM(tr(R.string.spectrum), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF0A0A0A, 0xFF848484, 0xFFA5A5A5, 0xFF787878), listOf(0xFFF6F6F6, 0xFFBABABA, 0xFFC7C7C7, 0xFFB8B8B8)),
    AURORA(tr(R.string.aurora), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF111111, 0xFFA6A6A6, 0xFF787878, 0xFF8F8F8F), listOf(0xFFF3F3F3, 0xFFC3C3C3, 0xFFB2B2B2, 0xFFC8C8C8)),
    PRISM(tr(R.string.app_name), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF0E0E0E, 0xFFA6A6A6, 0xFFD4D4D4, 0xFFC2C2C2), listOf(0xFFF8F8F8, 0xFFC5C5C5, 0xFFDFDFDF, 0xFFD3D3D3)),
    SUNSET(tr(R.string.sunset), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF181818, 0xFF929292, 0xFF7D7D7D, 0xFFC5C5C5), listOf(0xFFF7F7F7, 0xFFBBBBBB, 0xFFB5B5B5, 0xFFD9D9D9)),
    OCEAN(tr(R.string.ocean), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF131313, 0xFF5B5B5B, 0xFF828282, 0xFFA6A6A6), listOf(0xFFF5F5F5, 0xFFAAAAAA, 0xFFC0C0C0, 0xFFD6D6D6)),
    FOREST(tr(R.string.forest), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF101010, 0xFF555555, 0xFF939393, 0xFFD3D3D3), listOf(0xFFF5F5F5, 0xFFB0B0B0, 0xFFC7C7C7, 0xFFE8E8E8)),
    NEON(tr(R.string.neon), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF050505, 0xFF666666, 0xFFAAAAAA, 0xFF5D5D5D), listOf(0xFFF6F6F6, 0xFFBABABA, 0xFFD2D2D2, 0xFFA9A9A9)),
    ROSE(tr(R.string.ros), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF111111, 0xFFBDBDBD, 0xFF969696, 0xFFDDDDDD), listOf(0xFFF8F8F8, 0xFFD1D1D1, 0xFFBCBCBC, 0xFFE6E6E6)),
    MIDNIGHT(tr(R.string.midnight), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF050505, 0xFF212121, 0xFF393939, 0xFF2E2E2E), listOf(0xFFF1F1F1, 0xFFBFBFBF, 0xFFBFBFBF, 0xFFBEBEBE)),
    EMBER(tr(R.string.ember), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF0A0A0A, 0xFF595959, 0xFF949494, 0xFF252525), listOf(0xFFF7F7F7, 0xFFB0B0B0, 0xFFC9C9C9, 0xFFB4B4B4)),
    GRAPHITE(tr(R.string.graphite), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF111111, 0xFF2D2D2D, 0xFF454545, 0xFF1C1C1C), listOf(0xFFF5F5F5, 0xFFDEDEDE, 0xFFD3D3D3, 0xFFE8E8E8)),

    TINT(tr(R.string.soft_tint), BackdropKind.TINT, BackdropGroup.SIMPLE),
    DUSK(tr(R.string.dusk), BackdropKind.BLEND, BackdropGroup.SIMPLE,
        listOf(0xFF1C1C1C, 0xFF181818), listOf(0xFFEFEFEF, 0xFFEBEBEB)),
    SAND(tr(R.string.sand), BackdropKind.BLEND, BackdropGroup.SIMPLE,
        listOf(0xFF1E1E1E, 0xFF121212), listOf(0xFFF1F1F1, 0xFFE4E4E4)),
    MIST(tr(R.string.mist), BackdropKind.BLEND, BackdropGroup.SIMPLE,
        listOf(0xFF1A1A1A, 0xFF111111), listOf(0xFFF1F1F1, 0xFFEBEBEB)),
    DOTS(tr(R.string.dots), BackdropKind.DOTS, BackdropGroup.SIMPLE),
    PAPER(tr(R.string.paper), BackdropKind.PAPER, BackdropGroup.SIMPLE),
    PINSTRIPE(tr(R.string.pinstripe), BackdropKind.PINSTRIPE, BackdropGroup.SIMPLE),
    WAVES(tr(R.string.waves), BackdropKind.WAVES, BackdropGroup.SIMPLE),
    RIPPLES(tr(R.string.ripples), BackdropKind.RIPPLES, BackdropGroup.SIMPLE),
    DIAMONDS(tr(R.string.diamonds), BackdropKind.DIAMONDS, BackdropGroup.SIMPLE),
    ZIGZAG(tr(R.string.zigzag), BackdropKind.ZIGZAG, BackdropGroup.SIMPLE),
    HALFTONE(tr(R.string.halftone), BackdropKind.HALFTONE, BackdropGroup.SIMPLE),
    CROSSES(tr(R.string.crosses), BackdropKind.CROSSES, BackdropGroup.SIMPLE),
    SCALES(tr(R.string.scales), BackdropKind.SCALES, BackdropGroup.SIMPLE),
    CONFETTI(tr(R.string.confetti), BackdropKind.CONFETTI, BackdropGroup.SIMPLE),

    CONSTELLATION(tr(R.string.constellation), BackdropKind.CONSTELLATION, BackdropGroup.DESIGNS,
        listOf(0xFF0C0C0C, 0xFFC4C4C4, 0xFF6A6A6A, 0xFF8A8A8A), listOf(0xFFF5F5F5, 0xFF505050, 0xFFB7B7B7, 0xFFCACACA)),
    CONTOURS(tr(R.string.contours), BackdropKind.CONTOURS, BackdropGroup.DESIGNS,
        listOf(0xFF111111, 0xFF9F9F9F, 0xFF7B7B7B, 0xFFB6B6B6), listOf(0xFFF6F6F6, 0xFF6C6C6C, 0xFF7F7F7F, 0xFFA9A9A9)),
    MOSAIC(tr(R.string.mosaic), BackdropKind.MOSAIC, BackdropGroup.DESIGNS,
        listOf(0xFF121212, 0xFF595959, 0xFF7A7A7A, 0xFF7F7F7F), listOf(0xFFF7F7F7, 0xFFC2C2C2, 0xFFCCCCCC, 0xFFD1D1D1)),
    BOKEH(tr(R.string.bokeh), BackdropKind.BOKEH, BackdropGroup.DESIGNS,
        listOf(0xFF0C0C0C, 0xFFBDBDBD, 0xFF939393, 0xFF808080), listOf(0xFFF9F9F9, 0xFFC4C4C4, 0xFFB4B4B4, 0xFFA0A0A0)),
    SILK(tr(R.string.silk), BackdropKind.SILK, BackdropGroup.DESIGNS,
        listOf(0xFF0E0E0E, 0xFF999999, 0xFF999999, 0xFF9D9D9D), listOf(0xFFF5F5F5, 0xFF787878, 0xFF808080, 0xFF878787)),
    HORIZON(tr(R.string.horizon), BackdropKind.HORIZON, BackdropGroup.DESIGNS,
        listOf(0xFF0A0A0A, 0xFFBBBBBB, 0xFF777777, 0xFFA4A4A4), listOf(0xFFF6F6F6, 0xFFC3C3C3, 0xFFB0B0B0, 0xFF949494)),
    // Seasonal (1.8.0): base, then the glows' colors; the scenes keep their own inks ([SeasonalDesigns]).
    HARVEST(tr(R.string.harvest), BackdropKind.HARVEST, BackdropGroup.DESIGNS,
        listOf(0xFF121212, 0xFF9B9B9B, 0xFF636363, 0xFFB3B3B3), listOf(0xFFF4F4F4, 0xFFB3B3B3, 0xFF999999, 0xFFC9C9C9),
        season = com.prism.music.data.looks.Season.HARVEST),
    HOLIDAY(tr(R.string.holiday), BackdropKind.HOLIDAY, BackdropGroup.DESIGNS,
        listOf(0xFF101010, 0xFF5E5E5E, 0xFF696969, 0xFFC3C3C3), listOf(0xFFF7F7F7, 0xFF959595, 0xFF9D9D9D, 0xFFC7C7C7),
        season = com.prism.music.data.looks.Season.HOLIDAY),
    ;

    fun colors(dark: Boolean): List<Long> = if (dark) this.dark else light

    /** Whether the pickers offer it on [date]: always, or a seasonal one in its month. */
    fun offeredOn(date: java.time.LocalDate): Boolean = season?.contains(date) ?: true

    companion object {
        /** Stored as "NAME" or "PHOTO|/path/to/image". */
        fun parse(v: String?): Pair<Backdrop, String?> {
            if (v.isNullOrBlank()) return DEFAULT to null
            val name = v.substringBefore('|')
            val path = v.substringAfter('|', "").ifBlank { null }
            // #378: a word this isn't, with a file after it, is a photo saved under a translated name ("FOTO|…").
            val style = entries.firstOrNull { it.name == name } ?: if (path?.startsWith("/") == true) PHOTO else DEFAULT
            return style to path
        }

        /** What's saved for a photo of your own. The word is a key, not text: it's the same in every language (#378). */
        fun photoValue(path: String) = "${PHOTO.name}|$path"

    }
}

/**
 * Draws [screen]'s chosen background behind [content]. With frosted pages on, the default is the
 * frost made from what's playing (as on artist and album pages), and the page's panels, chips and
 * buttons turn to frosted glass over it, whichever background it has.
 */
@Composable
fun ScreenBackdrop(screen: BackdropScreen, content: @Composable BoxScope.() -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.flow.collectAsState()
    val (style, photo) = Backdrop.parse(s.backdrops[screen.name])
    val motion = s.backdropMotion && !s.reduceMotion
    val frosted = ambientFrostShown(s, LocalIsDark.current)
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0.dp) }
    val draw: @Composable (Modifier) -> Unit = { m ->
        when {
            style != Backdrop.DEFAULT -> BackdropLayer(style, photo, s.backdropDim, motion, m)
            frosted -> AmbientFrost(m)
            else -> Box(m.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
    val page = remember(style, photo, frosted, height, s.backdropDim, motion) { PageBackdrop(height, draw) }
    Box(Modifier.fillMaxSize().onSizeChanged { height = with(density) { it.height.toDp() } }) {
        // The frost only changes with the song, so it's kept as a picture in a layer: drawn afresh, its gradients and grain
        // over the whole screen cost a Galaxy A16's GPU about 15 ms of every frame, even a blinking cursor's (#355).
        if (style != Backdrop.DEFAULT) draw(Modifier.fillMaxSize())
        else if (frosted) draw(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen })
        // The background reaches under the navigation rail (wide windows); the page itself starts beside it.
        Box(Modifier.fillMaxSize().padding(start = LocalChromeStart.current)) {
            // Over any background (the ambient frost or a picked one), the page's panels and buttons are glass.
            CompositionLocalProvider(LocalFrosted provides (frosted || style != Backdrop.DEFAULT), LocalPageBackdrop provides page, LocalChromeStart provides 0.dp) { content() }
        }
    }
}

/**
 * Whether the Default background is the frost from what's playing. Pure black in dark mode means black pages (Reddit,
 * Pixel 9 Pro: "AMOLED mode stays grey"), so the frost steps aside for it.
 */
fun ambientFrostShown(s: com.prism.music.data.prefs.AppSettings, dark: Boolean): Boolean = s.frostedPages && !(dark && s.pureBlack)

/** One background, at any size (the settings previews use it too). */
@Composable
fun BackdropLayer(style: Backdrop, photo: String?, dim: Float, motion: Boolean, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val dark = LocalIsDark.current
    // Pure black (AMOLED) in dark mode: every background sits on true black, its own colors kept but quieter, so most of
    // the screen's pixels stay off whichever background is picked (it used to change only Ambient and the plain page).
    val black = dark && LocalAppSettings.current.pureBlack
    val palette = style.colors(dark).map { Color(it) }.let { if (black && it.isNotEmpty()) listOf(Color.Black) + it.drop(1) else it }
    Box(modifier) {
        when (style) {
            // With frosted pages on, the default is the frost from what's playing (the previews show it so).
            Backdrop.DEFAULT -> if (ambientFrostShown(LocalAppSettings.current, dark)) AmbientFrost(Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(bg))
            Backdrop.ARTWORK -> {
                val song by LocalContainer.current.player.currentSong.collectAsState()
                Box(Modifier.fillMaxSize().background(bg))
                val s = LocalAppSettings.current
                val url = hiRes(song?.thumbnail, 300)
                val fadeMs = nowPlayingFadeMs(s)
                if (fadeMs == null) AsyncImage(
                    url, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = if (black) 0.5f else 0.85f },
                )
                else FadingCover(url, fadeMs, Modifier.fillMaxSize().blur(70.dp).graphicsLayer {
                    alpha = if (black) 0.5f else 0.85f
                    // The two covers mix as one layer, so the background's own see-through stays the same through a fade.
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                })
            }
            Backdrop.PHOTO -> {
                Box(Modifier.fillMaxSize().background(bg))
                if (photo != null) AsyncImage(File(photo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            else -> when (style.kind) {
                BackdropKind.FIELDS -> Fields(palette, dark, motion, if (black) 0.45f else 1f)
                BackdropKind.BLEND -> style.colors(dark).map { Color(it) }.map { if (black) androidx.compose.ui.graphics.lerp(Color.Black, it, 0.45f) else it }.let { (top, bottom) ->
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
                }
                else -> if (style.group == BackdropGroup.DESIGNS) Design(style.kind, palette, dark, motion)
                    else Pattern(style.kind, dark, motion, if (black) 0.5f else 1f)
            }
        }
        // Keeps text readable: a wash of the theme's background, stronger toward the bottom. The light
        // palettes are pale already, so they take a lighter wash; the patterns are quiet and take less still.
        val base = if (dark) dim else dim * 0.85f
        val wash = if (style.group == BackdropGroup.SIMPLE) base * 0.5f else base
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = wash * 0.7f), bg.copy(alpha = wash), bg.copy(alpha = (wash + 0.25f).coerceAtMost(0.95f))))
            ),
        )
    }
}

/**
 * #283 (1.8.1): how long the "Now playing" background takes to fade into the next song's cover, at the accent's and
 * the light's own pace ("Between songs"; a fade even when they change at once); null cuts as before (the switch is
 * off, or Reduce motion is on).
 */
fun nowPlayingFadeMs(s: com.prism.music.data.prefs.AppSettings): Int? =
    if (!s.view.backdropFade || s.reduceMotion) null
    else s.artworkChange.ms.takeIf { it > 0 } ?: com.prism.music.data.prefs.ArtworkChange.FADE.ms

/**
 * A cover that fades into the next one: the new picture comes in over the old once it has loaded (never over a blank),
 * then takes its place. Only the "Now playing" background uses it, and only with its fade on.
 */
@Composable
internal fun FadingCover(url: String?, fadeMs: Int, modifier: Modifier) {
    // The picture fully in, and the one coming in over it.
    var shown by remember { mutableStateOf(url) }
    var incoming by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val fade = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(url) {
        if (url == shown) { incoming = null; return@LaunchedEffect }
        // Nothing playing any more: the last cover doesn't stay behind every screen.
        if (url == null) { shown = null; incoming = null; return@LaunchedEffect }
        // A fade cut short: what had come in so far stays as the picture under the next one.
        incoming?.takeIf { loaded && fade.value > 0.5f }?.let { shown = it }
        loaded = false
        fade.snapTo(0f)
        incoming = url
    }
    androidx.compose.runtime.LaunchedEffect(incoming, loaded) {
        if (incoming == null || !loaded) return@LaunchedEffect
        fade.animateTo(1f, tween(fadeMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        shown = incoming
        incoming = null
    }
    Box(modifier) {
        if (shown != null) AsyncImage(shown, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        incoming?.let { next ->
            AsyncImage(
                next, null, contentScale = ContentScale.Crop,
                onState = { if (it is coil3.compose.AsyncImagePainter.State.Success) loaded = true },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            )
        }
    }
}

/**
 * A moving background's clock: 0 to 1 over [periodMs], over and over ([rest] when it doesn't move). It's read where
 * the background is drawn, so each frame only redraws it (reading it while composing rebuilt the whole background every
 * frame). Under the full player, which hides it, it holds still, and carries on from that same place when the page
 * shows again.
 */
@Composable
internal fun drift(motion: Boolean, periodMs: Int, rest: Float): androidx.compose.runtime.State<Float> {
    val t = remember { androidx.compose.runtime.mutableFloatStateOf(if (motion) 0f else rest) }
    // How long it has moved for (ms), kept across holds.
    val moved = remember { doubleArrayOf(0.0) }
    val hidden = com.prism.music.ui.components.LocalHiddenByPlayer.current()
    androidx.compose.runtime.LaunchedEffect(motion, periodMs, hidden) {
        if (!motion) { t.floatValue = rest; return@LaunchedEffect }
        if (hidden) return@LaunchedEffect
        // As Compose's own animations: with Android's animations turned off they sit at their end.
        val scale = coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
        if (scale == 0f) { t.floatValue = 1f; return@LaunchedEffect }
        var last = -1L
        while (true) androidx.compose.animation.core.withInfiniteAnimationFrameNanos { now ->
            if (last >= 0) moved[0] += (now - last) / 1_000_000.0 / scale
            last = now
            t.floatValue = ((moved[0] % periodMs) / periodMs).toFloat()
        }
    }
    return t
}

@Composable
private fun Fields(colors: List<Color>, dark: Boolean, motion: Boolean, quiet: Float = 1f) {
    val clock = drift(motion, 28_000, 0.15f)
    // Pastels need a touch less strength to stay airy; the dark palettes glow at full ([quiet] lowers it on pure black).
    val strength = (if (dark) 0.7f else 0.62f) * quiet
    Canvas(Modifier.fillMaxSize().background(colors[0])) {
        val t = clock.value
        val a = t * 2 * PI.toFloat()
        val r = size.maxDimension * 0.6f
        fun field(color: Color, cx: Float, cy: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        field(colors[1], size.width * (0.15f + 0.25f * sin(a)), size.height * (0.12f + 0.08f * cos(a)))
        field(colors[2], size.width * (0.9f + 0.12f * cos(a * 2)), size.height * (0.45f + 0.12f * sin(a)))
        field(colors[3], size.width * (0.35f + 0.2f * cos(a)), size.height * (0.92f + 0.06f * sin(a * 2)))
    }
}

/**
 * The simple designs: the theme's background, a soft glow of the accent from the top, and (except
 * Soft tint) a fine pattern in the accent that fades out down the page. Inks are stronger in light
 * mode, where pale lines on a pale page would otherwise vanish.
 */
@Composable
private fun Pattern(kind: BackdropKind, dark: Boolean, motion: Boolean, glow: Float = 1f) {
    val scheme = MaterialTheme.colorScheme
    val bg = scheme.background
    val accent = scheme.primary
    val second = scheme.tertiary
    val t = drift(motion && (kind == BackdropKind.WAVES || kind == BackdropKind.RIPPLES), 24_000, 0.2f)
    val density = LocalDensity.current.density
    Canvas(Modifier.fillMaxSize().background(bg)) { drawPattern(kind, bg, accent, second, dark, t.value, density, glow) }
}

/** One simple design, drawn plainly (tests render it to an image). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPattern(
    kind: BackdropKind, bg: Color, accent: Color, second: Color, dark: Boolean, t: Float, density: Float, glow: Float = 1f,
) {
        drawRect(bg)
        val w = size.width
        val h = size.height
        // The glow every simple design shares: the accent, faintly, from the top.
        drawRect(Brush.verticalGradient(
            0f to accent.copy(alpha = (if (dark) 0.22f else 0.16f) * glow), 0.55f to accent.copy(alpha = (if (dark) 0.06f else 0.04f) * glow), 1f to Color.Transparent,
        ))
        if (kind == BackdropKind.TINT) {
            drawCircle(
                Brush.radialGradient(listOf(second.copy(alpha = if (dark) 0.16f else 0.12f), Color.Transparent), Offset(w, h * 0.85f), w * 0.9f),
                w * 0.9f, Offset(w, h * 0.85f),
            )
            return
        }
        val ink = accent.copy(alpha = if (dark) 0.20f else 0.26f)
        // Patterns fade out toward the bottom, where lists sit thickest.
        val fade = { y: Float -> (1f - (y / h) * 0.85f).coerceIn(0.15f, 1f) }
        val stroke = 1f * density
        when (kind) {
            BackdropKind.DOTS -> {
                val step = 18f * density
                val r = 1.4f * density
                var y = step / 2
                while (y < h) {
                    var x = step / 2
                    while (x < w) { drawCircle(ink.copy(alpha = ink.alpha * fade(y)), r, Offset(x, y)); x += step }
                    y += step
                }
            }
            BackdropKind.PAPER -> {
                val step = 24f * density
                var x = step
                while (x < w) { drawLine(Brush.verticalGradient(listOf(ink, ink.copy(alpha = ink.alpha * 0.15f))), Offset(x, 0f), Offset(x, h), stroke * 0.8f); x += step }
                var y = step
                while (y < h) { drawLine(ink.copy(alpha = ink.alpha * fade(y)), Offset(0f, y), Offset(w, y), stroke * 0.8f); y += step }
            }
            BackdropKind.PINSTRIPE -> {
                val step = 14f * density
                var x = -h
                while (x < w) {
                    drawLine(Brush.verticalGradient(listOf(ink, ink.copy(alpha = ink.alpha * 0.15f))), Offset(x, h), Offset(x + h, 0f), stroke)
                    x += step
                }
            }
            BackdropKind.WAVES -> {
                // Three soft bands rolling along the foot of the page, plus thin echoes at the top.
                val phase = t * 2 * PI.toFloat()
                listOf(Triple(accent, 0.70f, 0f), Triple(second, 0.78f, 1.7f), Triple(accent, 0.86f, 3.4f)).forEachIndexed { i, (color, at, shift) ->
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, h)
                        var x = 0f
                        while (x <= w + 4f) {
                            lineTo(x, h * at + sin(x / w * 2 * PI.toFloat() * 1.2f + phase + shift) * h * 0.03f)
                            x += 4f
                        }
                        lineTo(w, h)
                        close()
                    }
                    drawPath(path, color.copy(alpha = (if (dark) 0.14f else 0.12f) + i * 0.03f))
                }
                for (k in 0 until 4) {
                    val path = androidx.compose.ui.graphics.Path()
                    var x = 0f
                    val y0 = h * (0.08f + k * 0.035f)
                    path.moveTo(0f, y0)
                    while (x <= w + 4f) { path.lineTo(x, y0 + sin(x / w * 2 * PI.toFloat() + phase + k * 0.6f) * h * 0.012f); x += 4f }
                    drawPath(path, ink.copy(alpha = ink.alpha * (1f - k * 0.2f)), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                }
            }
            BackdropKind.RIPPLES -> {
                // Rings spreading from the top corner; with motion on they slowly travel outward.
                val origin = Offset(w * 0.92f, h * 0.06f)
                val step = 26f * density
                val maxR = size.maxDimension * 1.2f
                var r = step * t
                while (r < maxR) {
                    if (r > 0f) drawCircle(ink.copy(alpha = ink.alpha * (1f - r / maxR)), r, origin, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    r += step
                }
            }
            BackdropKind.DIAMONDS -> {
                // A fine lattice of diagonals both ways.
                val step = 22f * density
                val brush = Brush.verticalGradient(listOf(ink, ink.copy(alpha = ink.alpha * 0.12f)))
                var x = -h
                while (x < w + h) {
                    drawLine(brush, Offset(x, h), Offset(x + h, 0f), stroke * 0.8f)
                    drawLine(brush, Offset(x, 0f), Offset(x + h, h), stroke * 0.8f)
                    x += step
                }
            }
            BackdropKind.ZIGZAG -> {
                val rowStep = 24f * density
                val run = 9f * density
                val amp = 4.5f * density
                var y = rowStep / 2
                while (y < h) {
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, y) }
                    var x = 0f
                    var up = true
                    while (x < w + run) { x += run; path.lineTo(x, y + if (up) -amp else amp); up = !up }
                    drawPath(path, ink.copy(alpha = ink.alpha * fade(y)), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    y += rowStep
                }
            }
            BackdropKind.HALFTONE -> {
                // Dots that swell toward the top corner, like a print's halftone.
                // Kept to the corner and fine, so the title and first rows stay clear.
                val step = 11f * density
                val origin = Offset(w, 0f)
                val reach = size.minDimension * 0.95f
                var y = step / 2
                var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < w) {
                        val k = (1f - (Offset(x, y) - origin).getDistance() / reach).coerceIn(0f, 1f)
                        if (k > 0.06f) drawCircle(ink.copy(alpha = ink.alpha * 0.9f), step * 0.36f * k, Offset(x, y))
                        x += step
                    }
                    y += step * 0.87f; row++
                }
            }
            BackdropKind.CROSSES -> {
                val step = 30f * density
                val arm = 3.5f * density
                var y = step / 2
                var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) step / 2 else step
                    val c = ink.copy(alpha = ink.alpha * fade(y))
                    while (x < w) {
                        drawLine(c, Offset(x - arm, y), Offset(x + arm, y), stroke * 1.2f)
                        drawLine(c, Offset(x, y - arm), Offset(x, y + arm), stroke * 1.2f)
                        x += step
                    }
                    y += step / 2; row++
                }
            }
            BackdropKind.SCALES -> {
                // Overlapping arcs in offset rows, like fish scales or roof tiles.
                val rad = 13f * density
                var y = 0f
                var row = 0
                while (y < h + rad) {
                    var x = if (row % 2 == 0) 0f else rad
                    val c = ink.copy(alpha = ink.alpha * fade(y))
                    while (x < w + rad) {
                        drawArc(c, 0f, 180f, false, Offset(x - rad, y - rad), androidx.compose.ui.geometry.Size(rad * 2, rad * 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        x += rad * 2
                    }
                    y += rad * 0.9f; row++
                }
            }
            BackdropKind.CONFETTI -> {
                // Short dashes scattered at all angles, in the accent and its partner color (same scatter every time).
                val rnd = kotlin.random.Random(42)
                val len = 7f * density
                repeat((w * h / (34f * 34f * density * density)).toInt().coerceAtMost(900)) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val a = rnd.nextFloat() * PI.toFloat()
                    val col = (if (rnd.nextBoolean()) accent else second).copy(alpha = ink.alpha * 1.15f * fade(y))
                    drawLine(col, Offset(x - cos(a) * len / 2, y - sin(a) * len / 2), Offset(x + cos(a) * len / 2, y + sin(a) * len / 2),
                        stroke * 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            else -> {}
        }
}

package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Prism's 1.7 look, "Aura × Spectrum": Aura's ambient color from the playing cover, frosted glass and soft corners,
 * with the split light (the prism) running through it: what to continue, progress, where you are, what's playing, the
 * play ring, Prism's own covers, switches, Settings' sections. Other color comes from the artwork, through
 * [readableOn] so it stays legible.
 *
 * Screens read these tokens instead of literal values; the approved proposal is `.claude/redesign-aura-spectrum.html`.
 */
@Stable
object PrismTokens {
    /**
     * Appearance → Color → Spectrum: "White light" (true) is the light before the prism splits it. Everything below that
     * would be the spectrum (lines, rings, the hue tiles) is then neutral grey and ink instead, and the accent goes
     * neutral too ([PrismTheme]). Set by [PrismTheme] from the settings; snapshot state, so what reads it redraws.
     */
    var white by mutableStateOf(false)

    /**
     * White light with an accent color (as in Prism's earlier look): where the spectrum would be, the accent instead,
     * as read on dark pages, on light ones, and on the page showing now. Null for a neutral (white) accent: greys and ink.
     */
    class Accent(val onDark: Color, val onLight: Color, val now: Color)
    var whiteAccent by mutableStateOf<Accent?>(null)
    private fun solid(c: Color) = listOf(0f to c, 1f to c)

    /**
     * Appearance → Color → "Your light" (1.7.2): the split light's own colors, Prism's by default. Set by [PrismTheme]
     * from the settings ([SpectrumPresets.light]); snapshot state, so everything drawn with it follows.
     */
    var light by mutableStateOf(SpectrumPresets.PRISM_LIGHT)

    /**
     * Appearance → Color → Contrast (1.7.2): secondary text, quiet icons and hairlines are this much firmer
     * (0 standard, 1 medium, 2 high). Set by [PrismTheme].
     */
    var contrast by mutableStateOf(0)

    private val splitColors get() = light.colors
    private val splitColorsDeep get() = light.deep
    // White light: a quiet grey sheen. Mid greys where the line must read on either theme (borders, fills under white
    // icons); near-white on dark pages and near-black on light ones where it carries text or a progress line.
    private val whiteColors = listOf(0.00f to Color(0xFF9C9CA4), 0.50f to Color(0xFF7E7E86), 1.00f to Color(0xFF9C9CA4))
    // White on dark pages, really white (Reddit 1.7: "when I chose white it's giving grey, not white").
    private val whiteOnDark = listOf(0.00f to Color(0xFFFFFFFF), 1.00f to Color(0xFFF0F0F3))
    private val whiteOnLight = listOf(0.00f to Color(0xFF141418), 1.00f to Color(0xFF55555D))
    private val ringSplit get() = light.ring
    private val ringWhite = listOf(Color(0xFF8E8E96), Color(0xFF64646C), Color(0xFF8E8E96), Color(0xFFB2B2B8), Color(0xFF8E8E96))

    /** The split light, red to violet (or White light's grey sheen). */
    val spectrumColors get() = if (white) whiteAccent?.let { solid(it.now) } ?: whiteColors else splitColors
    /** The same, deeper, for text and thin lines on light backgrounds (the bright yellow doesn't read on white). */
    val spectrumColorsDeep get() = if (white) whiteAccent?.let { solid(it.onLight) } ?: whiteOnLight else splitColorsDeep
    fun spectrumColors(dark: Boolean) = runOf(white, whiteAccent, light, dark)

    /**
     * The light as it's drawn on a [dark] or light page for any style, accent and light: what [spectrumColors] gives for
     * the settings in use, and what Settings' examples give for settings not picked yet.
     */
    fun runOf(white: Boolean, accent: Accent?, light: SpectrumPresets.Light, dark: Boolean): List<Pair<Float, Color>> = if (white) {
        accent?.let { solid(if (dark) it.onDark else it.onLight) } ?: if (dark) whiteOnDark else whiteOnLight
    } else if (dark) light.colors else light.deep

    /** The ring's colors (play ring, Liked songs) for any style, accent and light. */
    fun ringOf(white: Boolean, accent: Accent?, light: SpectrumPresets.Light): List<Color> =
        if (white) accent?.let { listOf(it.now, it.now) } ?: ringWhite else light.ring
    val spectrum: Brush get() = Brush.horizontalGradient(*spectrumColors.toTypedArray())
    /** The split light across a width (for text and lines), deeper on light backgrounds. */
    fun spectrum(dark: Boolean, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY): Brush =
        Brush.horizontalGradient(*spectrumColors(dark).toTypedArray(), startX = startX, endX = endX)
    /** The split light round a circle (the play ring, Liked songs' cover, small dots), from red back to red. */
    fun spectrumRing(center: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Unspecified): Brush =
        Brush.sweepGradient(ringOf(white, whiteAccent, light), center)
    /** The spectrum's seven hues in order, one per Settings section (and a menu's quick actions). */
    private val splitHues get() = light.hues
    /** White light's single tile color: a grey white icons still read on. */
    private val whiteHue = Color(0xFF74747C)
    val hues: List<Color> get() = if (white) List(splitHues.size) { whiteHue } else splitHues
    fun hue(i: Int): Color = if (white) whiteHue else splitHues[((i % splitHues.size) + splitHues.size) % splitHues.size]

    /** The color [t] (0 red … 1 violet) along the hue run, so any number of rows can run evenly red to violet. */
    fun spectrumAt(t: Float): Color {
        if (white) return whiteHue
        val x = t.coerceIn(0f, 1f) * (splitHues.size - 1)
        val i = x.toInt().coerceAtMost(splitHues.size - 2)
        return androidx.compose.ui.graphics.lerp(splitHues[i], splitHues[i + 1], x - i)
    }
    object Space {
        /** The screen's side margin. */
        val gutter = 22.dp
        val row = 64.dp
        val rowArt = 48.dp
        val section = 32.dp
    }

    /** Aura's corners: soft. */
    object Radius {
        val smallArt = 10.dp
        val art = 14.dp
        val large = 22.dp
    }

    /** Durations in ms; with reduced motion everything becomes a [QUICK] fade. */
    object Motion {
        const val QUICK = 120
        const val STANDARD = 240
        const val EMPHASISED = 380
    }
}

/**
 * A color Prism draws itself (a mood or genre tile, Replay's card, a glow) as it should look now: unchanged in
 * Spectrum; in White light a grey of about its weight, kept dark enough for white text on it.
 */
fun Color.lit(): Color {
    if (!PrismTokens.white) return this
    val l = toHsl().third
    return Color.hsl(0f, 0f, (0.16f + l * 0.30f).coerceIn(0.16f, 0.40f), alpha)
}

/** How strong secondary ink, quiet ink and hairlines are at each contrast level (Standard, Medium, High). */
object InkLevels {
    val secondary = floatArrayOf(0.60f, 0.72f, 0.84f)
    val quiet = floatArrayOf(0.38f, 0.52f, 0.68f)
    val line = floatArrayOf(0.09f, 0.14f, 0.22f)
}

/** Secondary text: 60% ink (firmer with more contrast). */
val ColorScheme.ink2: Color get() = onBackground.copy(alpha = InkLevels.secondary[PrismTokens.contrast.coerceIn(0, 2)])
/** Tertiary text and quiet icons: 38% ink (firmer with more contrast). */
val ColorScheme.ink3: Color get() = onBackground.copy(alpha = InkLevels.quiet[PrismTokens.contrast.coerceIn(0, 2)])
/** Hairlines. */
val ColorScheme.hairline: Color get() = onBackground.copy(alpha = InkLevels.line[PrismTokens.contrast.coerceIn(0, 2)])

/**
 * The split light's colors (1.7.2): Prism's own, a preset, or the listener's. [Light] holds every form the tokens use:
 * the run for dark pages ([Light.colors]), a deeper one for light pages ([Light.deep]), the closed ring, and seven tile
 * hues white icons sit on.
 */
object SpectrumPresets {
    /** [season]: a seasonal Look's light (1.8.0), only offered in its month. */
    class Preset(val key: String, val label: String, val colors: List<Color>, val season: com.prism.music.data.looks.Season? = null)

    class Light(
        val colors: List<Pair<Float, Color>>,
        val deep: List<Pair<Float, Color>>,
        val ring: List<Color>,
        val hues: List<Color>,
    )

    private fun c(vararg v: Long) = v.map { Color(it) }

    /** Prism's own split light, exactly as 1.7 drew it. */
    val PRISM_LIGHT = Light(
        colors = listOf(
            0.00f to Color(0xFFFF4D5E), 0.20f to Color(0xFFFF9F43), 0.38f to Color(0xFFFFD84D),
            0.56f to Color(0xFF3DDC97), 0.76f to Color(0xFF36B8FF), 1.00f to Color(0xFF7C5CFF),
        ),
        deep = listOf(
            0.00f to Color(0xFFE23347), 0.20f to Color(0xFFE57A12), 0.38f to Color(0xFFC79A00),
            0.56f to Color(0xFF15A86C), 0.76f to Color(0xFF1789D6), 1.00f to Color(0xFF6A44F0),
        ),
        ring = c(0xFFFF4D5E, 0xFFFF9F43, 0xFFFFD84D, 0xFF3DDC97, 0xFF36B8FF, 0xFF7C5CFF, 0xFFFF4D5E),
        hues = c(0xFFFF5A6A, 0xFFFF9F43, 0xFFF2C53D, 0xFF3DDC97, 0xFF36B8FF, 0xFF5A8BFF, 0xFF8B6CFF),
    )

    val all = listOf(
        Preset("PRISM", tr(R.string.app_name), PRISM_LIGHT.colors.map { it.second }),
        Preset("SUNSET", tr(R.string.sunset), c(0xFFD4418E, 0xFFFF5F6D, 0xFFFF9966, 0xFFFFC371)),
        Preset("OCEAN", tr(R.string.ocean), c(0xFF2B59FF, 0xFF2FA8FF, 0xFF2BD9E8, 0xFF7CF5C8)),
        Preset("AURORA", tr(R.string.aurora), c(0xFF3DDC97, 0xFF36E2D2, 0xFF5A8BFF, 0xFFB15CFF)),
        Preset("FOREST", tr(R.string.forest), c(0xFFB8E986, 0xFF5FD38D, 0xFF2BB673, 0xFF1D9A8A)),
        Preset("EMBER", tr(R.string.ember), c(0xFFFF3D3D, 0xFFFF6D00, 0xFFFFAB00, 0xFFFFD54F)),
        Preset("CANDY", tr(R.string.candy), c(0xFFFF7EB3, 0xFFFFB86B, 0xFF7CE0FF, 0xFFB79CFF)),
        Preset("NEON", tr(R.string.neon), c(0xFFFF2E93, 0xFFFFE600, 0xFF00FFA3, 0xFF00D1FF, 0xFFB026FF)),
        Preset("ICE", tr(R.string.ice), c(0xFFE0F7FF, 0xFF9BE3FF, 0xFF5AB8FF, 0xFF8C9EFF)),
        Preset("GOLD", tr(R.string.gold), c(0xFFFFE29F, 0xFFFFC75F, 0xFFE8A33D, 0xFFFFD98E)),
        Preset("SILVER", tr(R.string.silver), c(0xFFF5F5F7, 0xFFB8B8C0, 0xFF8E8E96, 0xFFD6D6DC)),
        // Okabe & Ito's palette: hues that stay apart for every common kind of color blindness.
        Preset("CLEAR", tr(R.string.color_blind_safe), c(0xFFF0E442, 0xFFE69F00, 0xFFD55E00, 0xFFCC79A7, 0xFF0072B2, 0xFF56B4E9, 0xFF009E73)),
        // Seasonal (1.8.0): Thanksgiving's cranberry, burnt orange, amber, gold and olive; Christmas as a candy cane meeting a
        // tree: red, silver white, pine green and gold. (Kept readable on light and dark pages, like every preset.)
        Preset("HARVEST", tr(R.string.harvest), c(0xFF9B1B30, 0xFFC8642A, 0xFFFFA726, 0xFFE0B04A, 0xFF7A8B2E), com.prism.music.data.looks.Season.HARVEST),
        Preset("HOLIDAY", tr(R.string.holiday), c(0xFFC62828, 0xFFE3E8EC, 0xFF1E8A4C, 0xFFE6B740), com.prism.music.data.looks.Season.HOLIDAY),
    )

    fun preset(key: String): Preset? = all.firstOrNull { it.key == key }

    /** "From the cover" (#123, 1.7.7): the light takes the playing cover's colors ([coverStops]). */
    const val COVER = "COVER"
    const val COVER_LABEL = "From the cover"

    /**
     * The playing cover's colors for [COVER], picked from the palette Prism already makes for each song (MainActivity), in
     * hue order; empty (Prism's own light) until a cover has given two. Snapshot state: what's drawn with it follows.
     */
    var coverStops by mutableStateOf<List<Int>>(emptyList())

    /** A preset's name, ours included ("From the cover", "Your own"). */
    fun label(key: String): String = when (key) {
        COVER -> COVER_LABEL
        "CUSTOM" -> tr(R.string.your_own)
        else -> preset(key)?.label ?: tr(R.string.app_name)
    }

    /**
     * Up to five of a cover's swatches as a light: distinct (none within 28° of hue of another unless far apart in
     * lightness), colorful enough to be a light, in hue order. A cover with one color gives shades of it; one with none
     * (black and white, mostly white: #233 #235) a silver light, never Prism's rainbow, which isn't in the cover.
     */
    fun fromCover(swatches: List<Int>): List<Int> {
        val picked = ArrayList<Triple<Float, Float, Float>>()
        for (c in swatches) {
            val (h, s, l) = Color(c).toHsl()
            if (s < 0.18f || l < 0.12f || l > 0.92f) continue
            if (picked.any { p -> kotlin.math.abs(((p.first - h + 540f) % 360f) - 180f) < 28f && kotlin.math.abs(p.third - l) < 0.25f }) continue
            picked += Triple(h, s, l)
            if (picked.size == 5) break
        }
        if (picked.size >= 2) return picked.sortedBy { it.first }.map { (h, s, l) -> Color.hsl(h, s, l).toArgb() }
        picked.firstOrNull()?.let { (h, s, l) ->
            return listOf(Color.hsl(h, s, (l - 0.2f).coerceAtLeast(0.22f)), Color.hsl(h, s, l), Color.hsl(h, s * 0.75f, (l + 0.2f).coerceAtMost(0.86f))).map { it.toArgb() }
        }
        return SILVER
    }

    /**
     * The cover light [t] of the way (0..1) from [from] to [to] (#254, 1.7.9.2: it used to change at once, so with "From
     * the cover" the progress, Play's ring and the picked cards cut to the next song's colors whatever "Between songs"
     * said). Both are read as runs and sampled at the longer one's count, so a 3-color light can become a 5-color one.
     */
    fun fadeStops(from: List<Int>, to: List<Int>, t: Float): List<Int> {
        if (from.size < 2 || to.size < 2) return to
        val n = maxOf(from.size, to.size)
        fun at(list: List<Int>, i: Int): Color {
            val p = i / (n - 1).toFloat() * (list.size - 1)
            val lo = p.toInt().coerceAtMost(list.size - 2)
            return androidx.compose.ui.graphics.lerp(Color(list[lo]), Color(list[lo + 1]), p - lo)
        }
        return (0 until n).map { i -> androidx.compose.ui.graphics.lerp(at(from, i), at(to, i), t.coerceIn(0f, 1f)).toArgb() }
    }

    /** The light of a cover with no color in it. */
    val SILVER: List<Int> = listOf(0xFF8E949C.toInt(), 0xFFC9CDD2.toInt(), 0xFFF4F5F7.toInt())

    /** The colors [key] (or, for "CUSTOM", [stops]) stands for; Prism's when there's nothing usable. */
    fun colorsFor(key: String, stops: List<Int>): List<Color> =
        if (key == "CUSTOM" && stops.size >= 2) stops.map { Color(it) } else preset(key)?.colors ?: all.first().colors

    private val darkPage = Color(0xFF0E0E12)
    private val lightPage = Color(0xFFFAFAFB)

    /**
     * Everything the tokens need from [key]/[stops]. Prism's own, unbanded, is exactly 1.7's. Others: each color lifted
     * just enough to read as a line on dark pages, deepened on light ones (the bright yellow rule), and seven tile hues
     * sampled along the run, kept dark enough for a white icon.
     */
    fun light(key: String, stops: List<Int>, bands: Boolean): Light {
        // From the cover: your own light, in the playing cover's colors (Prism's own until a cover gives some).
        if (key == COVER) return light("CUSTOM", coverStops, bands)
        return synchronized(memo) { memo.getOrPut(Triple(key, stops, bands)) { build(key, stops, bands) } }
    }

    /** The same light for the same choice (the tokens compare by identity), for the presets and a few of your own. */
    private val memo = object : LinkedHashMap<Triple<String, List<Int>, Boolean>, Light>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Triple<String, List<Int>, Boolean>, Light>) = size > 48
    }

    private fun build(key: String, stops: List<Int>, bands: Boolean): Light {
        val prism = key == "PRISM" || (key == "CUSTOM" && stops.size < 2) || (key != "CUSTOM" && preset(key) == null)
        if (prism && !bands) return PRISM_LIGHT
        val colors = colorsFor(key, stops)
        val onDark = if (prism) PRISM_LIGHT.colors.map { it.second } else colors.map { readableOn(it, darkPage, 3f) }
        val onLight = if (prism) PRISM_LIGHT.deep.map { it.second } else colors.map { readableOn(it, lightPage, 3f) }
        fun run(list: List<Color>): List<Pair<Float, Color>> =
            if (bands) list.flatMapIndexed { i, col -> listOf(i / list.size.toFloat() to col, (i + 1) / list.size.toFloat() to col) }
            else list.mapIndexed { i, col -> i / (list.size - 1).toFloat() to col }
        val hues = if (prism) PRISM_LIGHT.hues else (0 until 7).map { i -> forWhiteIcon(sample(onDark, i / 6f)) }
        return Light(run(onDark), run(onLight), onDark + onDark.first(), hues)
    }

    /** The color [t] (0…1) along an evenly spaced run. */
    fun sample(list: List<Color>, t: Float): Color {
        if (list.size == 1) return list.first()
        val x = t.coerceIn(0f, 1f) * (list.size - 1)
        val i = x.toInt().coerceAtMost(list.size - 2)
        return androidx.compose.ui.graphics.lerp(list[i], list[i + 1], x - i)
    }

    /** Darkened (hue kept) until a white icon on it reads. */
    private fun forWhiteIcon(c: Color): Color {
        if (contrastRatio(Color.White, c) >= 2.4f) return c
        val (h, s, l) = c.toHsl()
        var lo = 0f; var hi = l
        repeat(20) { val mid = (lo + hi) / 2f; if (contrastRatio(Color.White, Color.hsl(h, s, mid)) >= 2.4f) lo = mid else hi = mid }
        return Color.hsl(h, s, lo)
    }
}

// ---------------------------------------------------------------- Contrast guard

/** WCAG 2 contrast ratio between two opaque colors, 1…21. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/**
 * [color], with its lightness moved (hue and saturation kept) just far enough to reach [minRatio] against
 * [background]: lighter on dark backgrounds, darker on light ones. Returned unchanged when it already passes. Any
 * cover's color, even white on white, neon or beige, comes out readable this way.
 */
fun readableOn(color: Color, background: Color, minRatio: Float = 4.5f): Color {
    if (contrastRatio(color, background) >= minRatio) return color
    val (h, s, l) = color.toHsl()
    val target = if (background.luminance() < 0.18f) 1f else 0f
    val end = Color.hsl(h, s, target, color.alpha)
    if (contrastRatio(end, background) < minRatio) return end
    // The smallest step from l toward the end that passes.
    var near = l
    var far = target
    repeat(24) {
        val mid = (near + far) / 2f
        if (contrastRatio(Color.hsl(h, s, mid, color.alpha), background) >= minRatio) far = mid else near = mid
    }
    return Color.hsl(h, s, far, color.alpha)
}

/** Hue (0–360), saturation and lightness (0–1). */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val mx = max(red, max(green, blue))
    val mn = min(red, min(green, blue))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-6f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        red -> 60f * (((green - blue) / d).mod(6f))
        green -> 60f * ((blue - red) / d + 2f)
        else -> 60f * ((red - green) / d + 4f)
    }
    return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

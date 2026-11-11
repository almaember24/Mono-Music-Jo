package com.prism.music.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.prism.music.AppContainer
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.ThemeMode

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }
val LocalIsDark = staticCompositionLocalOf { true }

val PrismViolet = Color(0xFF787878)

val AccentSwatches = listOf(
    0xFFFFFFFF, 0xFFE6E6E6, 0xFFCCCCCC, 0xFFB3B3B3, 0xFF999999, 0xFF808080,
    0xFF666666, 0xFF4D4D4D, 0xFF333333, 0xFF1F1F1F, 0xFF0F0F0F, 0xFF000000,
).map { Color(it) }

/** Greys have no hue to build a scheme around (theirs reads as 0°, red), so they get a neutral one. */
fun isNeutralSeed(argb: Int): Boolean {
    val out = FloatArray(3)
    ColorUtils.colorToHSL(argb, out)
    return out[1] < 0.08f || out[2] > 0.97f || out[2] < 0.03f
}

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/**
 * White (or any grey): pure white accents in dark mode; in light mode, where white on white would
 * vanish, the same monochrome look in near-black ink. No tint anywhere.
 */
private fun neutralScheme(dark: Boolean, pureBlack: Boolean): ColorScheme {
    fun g(l: Float) = hsl(0f, 0f, l)
    return if (dark) {
        val bg = if (pureBlack) Color.Black else g(0.055f)
        darkColorScheme(
            primary = Color.White, onPrimary = g(0.08f),
            primaryContainer = g(0.26f), onPrimaryContainer = Color.White, inversePrimary = g(0.20f),
            secondary = g(0.82f), onSecondary = g(0.10f),
            secondaryContainer = g(0.22f), onSecondaryContainer = g(0.95f),
            tertiary = g(0.70f), onTertiary = g(0.10f),
            tertiaryContainer = g(0.24f), onTertiaryContainer = g(0.95f),
            background = bg, onBackground = g(0.95f), surface = bg, onSurface = g(0.95f),
            surfaceVariant = g(0.20f), onSurfaceVariant = g(0.74f), surfaceTint = Color.White,
            inverseSurface = g(0.93f), inverseOnSurface = g(0.12f),
            outline = g(0.45f), outlineVariant = g(0.26f),
            surfaceBright = g(if (pureBlack) 0.16f else 0.20f), surfaceDim = bg,
            surfaceContainerLowest = if (pureBlack) Color.Black else g(0.04f),
            surfaceContainerLow = g(if (pureBlack) 0.05f else 0.085f),
            surfaceContainer = g(if (pureBlack) 0.07f else 0.105f),
            surfaceContainerHigh = g(if (pureBlack) 0.10f else 0.135f),
            surfaceContainerHighest = g(if (pureBlack) 0.13f else 0.165f),
        )
    } else {
        val bg = g(0.985f)
        lightColorScheme(
            primary = g(0.12f), onPrimary = Color.White,
            primaryContainer = g(0.88f), onPrimaryContainer = g(0.08f), inversePrimary = Color.White,
            secondary = g(0.34f), onSecondary = Color.White,
            secondaryContainer = g(0.90f), onSecondaryContainer = g(0.10f),
            tertiary = g(0.45f), onTertiary = Color.White,
            tertiaryContainer = g(0.90f), onTertiaryContainer = g(0.10f),
            background = bg, onBackground = g(0.08f), surface = bg, onSurface = g(0.08f),
            surfaceVariant = g(0.91f), onSurfaceVariant = g(0.34f), surfaceTint = g(0.12f),
            inverseSurface = g(0.16f), inverseOnSurface = g(0.95f),
            outline = g(0.52f), outlineVariant = g(0.82f),
            surfaceBright = bg, surfaceDim = g(0.88f),
            surfaceContainerLowest = Color.White, surfaceContainerLow = g(0.965f),
            surfaceContainer = g(0.95f), surfaceContainerHigh = g(0.93f), surfaceContainerHighest = g(0.905f),
        )
    }
}

/**
 * Builds a full Material 3 scheme from a single seed color (tonal approximation), with the colors text and icons sit
 * on checked for contrast ([readableOn]): a pale yellow or neon cover can't give an accent you can't read.
 */
fun schemeFromSeed(seed: Color, dark: Boolean, pureBlack: Boolean, exact: Boolean = false, minRatio: Float = 4.5f): ColorScheme {
    val c = tonalScheme(seed, dark, pureBlack)
    // Exact (1.7.2): the accent as picked, only moved as far as it must to read on the page; otherwise Prism's tone of it.
    val asPicked = exact && !isNeutralSeed(seed.toArgb())
    val primary = readableOn(if (asPicked) seed.copy(alpha = 1f) else c.primary, c.background, minRatio)
    val onPrimary = if (asPicked) inkOn(primary) else readableOn(c.onPrimary, primary)
    return c.copy(
        primary = primary, surfaceTint = primary,
        onPrimary = onPrimary,
        onPrimaryContainer = readableOn(c.onPrimaryContainer, c.primaryContainer, minRatio),
        secondary = readableOn(c.secondary, c.background, minRatio),
        onSecondaryContainer = readableOn(c.onSecondaryContainer, c.secondaryContainer, minRatio),
        tertiary = readableOn(c.tertiary, c.background, minRatio),
        onTertiaryContainer = readableOn(c.onTertiaryContainer, c.tertiaryContainer, minRatio),
        onSurfaceVariant = readableOn(c.onSurfaceVariant, c.background, minRatio),
    )
}

/** White or near-black, whichever reads better on [c]. */
private fun inkOn(c: Color): Color =
    if (contrastRatio(Color.White, c) >= contrastRatio(Color(0xFF111111), c)) Color.White else Color(0xFF111111)

/**
 * [scheme] with its page colors (background, surfaces, outlines, ink) rebuilt around hue [h] at [k] times Prism's usual
 * tint (0 = neutral grey, 1 = the hint Spectrum always had, 3 = strong), keeping its accent colors. Appearance →
 * Color → Background (1.7.2).
 */
fun withSurfaces(scheme: ColorScheme, h: Float, k: Float, dark: Boolean, pureBlack: Boolean, minRatio: Float = 4.5f): ColorScheme {
    val n = 0.10f * k
    // Text keeps at most the hint Prism always had, however strong the page tint: tinted harder, secondary text read
    // as a faded copy of the accent (an album's "Album · 2015", its description, song rows' artists).
    val ink = n.coerceAtMost(0.10f)
    val c = if (dark) {
        val bg = if (pureBlack) Color.Black else hsl(h, n * 1.4f, 0.055f)
        scheme.copy(
            background = bg, surface = bg, surfaceDim = bg,
            onBackground = hsl(h, ink, 0.93f), onSurface = hsl(h, ink, 0.93f),
            surfaceVariant = hsl(h, n * 1.5f, 0.20f), onSurfaceVariant = hsl(h, ink * 1.2f, 0.74f),
            inverseSurface = hsl(h, ink, 0.92f), inverseOnSurface = hsl(h, ink, 0.12f),
            outline = hsl(h, n, 0.45f), outlineVariant = hsl(h, n, 0.26f),
            surfaceBright = hsl(h, n * 1.4f, if (pureBlack) 0.16f else 0.20f),
            surfaceContainerLowest = if (pureBlack) Color.Black else hsl(h, n * 1.4f, 0.04f),
            surfaceContainerLow = hsl(h, n * 1.4f, if (pureBlack) 0.05f else 0.085f),
            surfaceContainer = hsl(h, n * 1.4f, if (pureBlack) 0.07f else 0.105f),
            surfaceContainerHigh = hsl(h, n * 1.4f, if (pureBlack) 0.10f else 0.135f),
            surfaceContainerHighest = hsl(h, n * 1.4f, if (pureBlack) 0.13f else 0.165f),
        )
    } else {
        val bg = hsl(h, 0.30f * k, 0.985f)
        scheme.copy(
            background = bg, surface = bg, surfaceBright = bg,
            onBackground = hsl(h, ink, 0.10f), onSurface = hsl(h, ink, 0.10f),
            surfaceVariant = hsl(h, 0.18f * k, 0.91f), onSurfaceVariant = hsl(h, ink, 0.34f),
            inverseSurface = hsl(h, ink, 0.18f), inverseOnSurface = hsl(h, ink, 0.95f),
            outline = hsl(h, n, 0.52f), outlineVariant = hsl(h, n, 0.82f),
            surfaceDim = hsl(h, 0.15f * k, 0.88f),
            surfaceContainerLowest = Color.White, surfaceContainerLow = hsl(h, 0.30f * k, 0.965f),
            surfaceContainer = hsl(h, 0.28f * k, 0.95f), surfaceContainerHigh = hsl(h, 0.25f * k, 0.93f),
            surfaceContainerHighest = hsl(h, 0.22f * k, 0.905f),
        )
    }
    return c.copy(
        primary = readableOn(c.primary, c.background, minRatio),
        onSurfaceVariant = readableOn(c.onSurfaceVariant, c.background, minRatio),
    )
}

/** Appearance → Color → Contrast above Standard: accent and secondary text firmed up against the page. */
private fun firmed(s: ColorScheme, minRatio: Float): ColorScheme = s.copy(
    primary = readableOn(s.primary, s.background, minRatio),
    secondary = readableOn(s.secondary, s.background, minRatio),
    tertiary = readableOn(s.tertiary, s.background, minRatio),
    onSurfaceVariant = readableOn(s.onSurfaceVariant, s.background, minRatio),
    outline = readableOn(s.outline, s.background, 3f),
)

private fun tonalScheme(seed: Color, dark: Boolean, pureBlack: Boolean): ColorScheme {
    if (isNeutralSeed(seed.toArgb())) return neutralScheme(dark, pureBlack)
    val out = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), out)
    val h = out[0]
    val s = out[1].coerceIn(0.30f, 0.92f)
    val n = 0.10f
    return if (dark) {
        val bg = if (pureBlack) Color.Black else hsl(h, n * 1.4f, 0.055f)
        darkColorScheme(
            primary = hsl(h, s, 0.76f),
            onPrimary = hsl(h, s, 0.14f),
            primaryContainer = hsl(h, s * 0.75f, 0.30f),
            onPrimaryContainer = hsl(h, s, 0.92f),
            inversePrimary = hsl(h, s, 0.42f),
            secondary = hsl(h, s * 0.35f, 0.76f),
            onSecondary = hsl(h, s * 0.35f, 0.16f),
            secondaryContainer = hsl(h, s * 0.30f, 0.26f),
            onSecondaryContainer = hsl(h, s * 0.35f, 0.92f),
            tertiary = hsl(h + 45f, s * 0.6f, 0.78f),
            onTertiary = hsl(h + 45f, s * 0.6f, 0.16f),
            tertiaryContainer = hsl(h + 45f, s * 0.5f, 0.28f),
            onTertiaryContainer = hsl(h + 45f, s * 0.6f, 0.92f),
            background = bg,
            onBackground = hsl(h, n, 0.93f),
            surface = bg,
            onSurface = hsl(h, n, 0.93f),
            surfaceVariant = hsl(h, n * 1.5f, 0.20f),
            onSurfaceVariant = hsl(h, n * 1.2f, 0.74f),
            surfaceTint = hsl(h, s, 0.76f),
            inverseSurface = hsl(h, n, 0.92f),
            inverseOnSurface = hsl(h, n, 0.12f),
            outline = hsl(h, n, 0.45f),
            outlineVariant = hsl(h, n, 0.26f),
            surfaceBright = hsl(h, n * 1.4f, if (pureBlack) 0.16f else 0.20f),
            surfaceDim = bg,
            surfaceContainerLowest = if (pureBlack) Color.Black else hsl(h, n * 1.4f, 0.04f),
            surfaceContainerLow = hsl(h, n * 1.4f, if (pureBlack) 0.05f else 0.085f),
            surfaceContainer = hsl(h, n * 1.4f, if (pureBlack) 0.07f else 0.105f),
            surfaceContainerHigh = hsl(h, n * 1.4f, if (pureBlack) 0.10f else 0.135f),
            surfaceContainerHighest = hsl(h, n * 1.4f, if (pureBlack) 0.13f else 0.165f),
        )
    } else {
        val bg = hsl(h, 0.30f, 0.985f)
        lightColorScheme(
            primary = hsl(h, s, 0.42f),
            onPrimary = Color.White,
            primaryContainer = hsl(h, s, 0.90f),
            onPrimaryContainer = hsl(h, s, 0.14f),
            inversePrimary = hsl(h, s, 0.78f),
            secondary = hsl(h, s * 0.30f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.40f, 0.90f),
            onSecondaryContainer = hsl(h, s * 0.35f, 0.14f),
            tertiary = hsl(h + 45f, s * 0.6f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(h + 45f, s * 0.6f, 0.90f),
            onTertiaryContainer = hsl(h + 45f, s * 0.6f, 0.14f),
            background = bg,
            onBackground = hsl(h, n, 0.10f),
            surface = bg,
            onSurface = hsl(h, n, 0.10f),
            surfaceVariant = hsl(h, 0.18f, 0.91f),
            onSurfaceVariant = hsl(h, n, 0.34f),
            surfaceTint = hsl(h, s, 0.42f),
            inverseSurface = hsl(h, n, 0.18f),
            inverseOnSurface = hsl(h, n, 0.95f),
            outline = hsl(h, n, 0.52f),
            outlineVariant = hsl(h, n, 0.82f),
            surfaceBright = bg,
            surfaceDim = hsl(h, 0.15f, 0.88f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, 0.30f, 0.965f),
            surfaceContainer = hsl(h, 0.28f, 0.95f),
            surfaceContainerHigh = hsl(h, 0.25f, 0.93f),
            surfaceContainerHighest = hsl(h, 0.22f, 0.905f),
        )
    }
}

private val base = Typography()

/**
 * The 1.7 type scale (Spectrum): thin and large at the top, calm in the middle, firm only for small labels.
 * The rule it keeps: ExtraLight (200) only at 28 sp and up, Light (300) from 21 sp, nothing under 400 below that,
 * so thin strokes never blur at row size or drop below contrast. Sizes are sp, so the phone's and Prism's own text size
 * still apply. [family] is the typeface picked in Look & feel (Outfit by default).
 */
fun prismTypography(
    family: FontFamily = FontFamily.Default,
    /** Big titles' font (1.7.7, Text → Titles); [family] when not picked. */
    titleFamily: FontFamily = family,
    /** Added to every style's weight (1.7.7, Text → Weight): −100 lighter, +100 bolder. */
    weightShift: Int = 0,
    /** Added to every style's letter spacing, in em (1.7.7, Text → Letter spacing). */
    tracking: Float = 0f,
): Typography {
    fun style(size: Float, line: Float, weight: Int, trk: Float = 0f, f: FontFamily = family) =
        TextStyle(fontFamily = f, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight((weight + weightShift).coerceIn(100, 900)), letterSpacing = (trk + tracking).em)
    return base.copy(
        // Replay's big numbers, the welcome screen.
        displayLarge = style(56f, 60f, 200, -0.03f, titleFamily),
        displayMedium = style(44f, 50f, 200, -0.025f, titleFamily),
        displaySmall = style(36f, 42f, 200, -0.02f, titleFamily),
        // Page titles (Home's greeting, Library, Settings…).
        headlineLarge = style(36f, 42f, 200, -0.02f, titleFamily),
        // Hero titles: album, playlist, artist.
        headlineMedium = style(28f, 34f, 300, -0.015f, titleFamily),
        headlineSmall = style(24f, 30f, 300, -0.01f, titleFamily),
        // Section headings.
        titleLarge = style(22f, 28f, 300, -0.01f, titleFamily),
        // Row titles.
        titleMedium = style(16f, 22f, 500, -0.003f),
        titleSmall = style(14f, 20f, 500),
        bodyLarge = style(16f, 22f, 400),
        bodyMedium = style(14.5f, 20f, 400),
        // Subtitles and notes.
        bodySmall = style(13f, 18f, 400, 0.003f),
        // Buttons, tabs, chips; labelSmall also for eyebrows.
        labelLarge = style(14f, 18f, 500, 0.01f),
        labelMedium = style(12.5f, 16f, 500, 0.02f),
        labelSmall = style(11.5f, 14f, 500, 0.05f),
    )
}

val PrismTypography = prismTypography()

/**
 * Prism 1.6's type (1.7.8, Classic type, #155): Material's sizes with tight, heavy headings over a calm body, as 1.6.9
 * drew it. Text → Weight, spacing and the titles' font still apply on top.
 */
fun classicTypography(family: FontFamily, titleFamily: FontFamily = family, weightShift: Int = 0, tracking: Float = 0f): Typography {
    fun TextStyle.f(title: Boolean = false, weight: FontWeight? = fontWeight) = copy(
        fontFamily = if (title) titleFamily else family,
        fontWeight = FontWeight(((weight ?: FontWeight.Normal).weight + weightShift).coerceIn(100, 900)),
        letterSpacing = (letterSpacing.takeIf { it.isEm }?.value ?: 0f).plus(tracking).em,
    )
    return Typography(
        displayLarge = base.displayLarge.copy(letterSpacing = (-0.035).em).f(true, FontWeight.Black),
        displayMedium = base.displayMedium.copy(letterSpacing = (-0.03).em).f(true, FontWeight.Black),
        displaySmall = base.displaySmall.copy(letterSpacing = (-0.025).em).f(true, FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.copy(letterSpacing = (-0.025).em).f(true, FontWeight.ExtraBold),
        headlineMedium = base.headlineMedium.copy(letterSpacing = (-0.02).em).f(true, FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(letterSpacing = (-0.015).em).f(true, FontWeight.Bold),
        titleLarge = base.titleLarge.copy(letterSpacing = (-0.012).em).f(true, FontWeight.Bold),
        titleMedium = base.titleMedium.copy(letterSpacing = (-0.005).em).f(weight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(letterSpacing = 0.em).f(weight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.em).f(weight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.em).f(weight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.em).f(weight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(letterSpacing = 0.006.em).f(),
        bodyMedium = base.bodyMedium.copy(letterSpacing = 0.em).f(),
        bodySmall = base.bodySmall.copy(letterSpacing = 0.em).f(),
    )
}

/** The app's type for [s]: its font, titles in their own font if one's picked, the weight and spacing (1.7.7). */
fun appTypography(s: AppSettings): Typography {
    val family = AppFonts.family(s.fontChoice)
    if (s.type.classic) return classicTypography(family, s.type.titleFont?.let { AppFonts.family(it) } ?: family, s.type.weight.shift, s.type.spacing.em).hyphenated()
    return prismTypography(
        family, s.type.titleFont?.let { AppFonts.family(it) } ?: family,
        weightShift = s.type.weight.shift, tracking = s.type.spacing.em,
    ).hyphenated()
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

@Composable
fun isAppDark(settings: AppSettings = LocalAppSettings.current): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The playing cover's color as the theme has it (for Settings' examples of "Now playing"). */
val LocalArtworkColor = staticCompositionLocalOf<Color?> { null }

/**
 * Every color Prism draws with for one set of settings on one kind of page: the Material scheme, the accent that
 * stands in for the light in White light, and the light itself. [PrismTheme] uses it for the app; Appearance → Color
 * uses it to show what a choice would look like before it's picked, so the two can't disagree.
 */
class ThemeColors(
    val scheme: ColorScheme,
    val whiteAccent: PrismTokens.Accent?,
    val light: SpectrumPresets.Light,
    val white: Boolean,
    val dark: Boolean,
    /** 0 standard, 1 medium, 2 high. */
    val contrast: Int,
    /** The settings these colors came from (the Color page's examples draw the real page background with them). */
    val settings: AppSettings? = null,
) {
    /** The light across a line, as drawn on this page. */
    val run: List<Pair<Float, Color>> get() = PrismTokens.runOf(white, whiteAccent, light, dark)
    /** The light round a ring. */
    val ring: List<Color> get() = PrismTokens.ringOf(white, whiteAccent, light)
    val ink2: Color get() = scheme.onBackground.copy(alpha = InkLevels.secondary[contrast])
    val ink3: Color get() = scheme.onBackground.copy(alpha = InkLevels.quiet[contrast])
    val line: Color get() = scheme.onBackground.copy(alpha = InkLevels.line[contrast])
}

/** The color the accent grows from: the cover's, or the one picked (light mode's own, when there is one). */
fun seedFor(s: AppSettings, dark: Boolean, artworkColor: Color?): Color = when (s.colorSource) {
    ColorSource.ARTWORK -> artworkColor ?: Color(s.accent)
    ColorSource.CUSTOM -> Color(if (!dark && s.accentLight != 0) s.accentLight else s.accent)
    ColorSource.DYNAMIC -> Color(s.accent)
}

/** The wallpaper's schemes, when the accent follows it (Android 12+); null otherwise. */
fun dynamicSchemes(s: AppSettings, context: android.content.Context): ((Boolean) -> ColorScheme)? =
    if (s.colorSource == ColorSource.DYNAMIC && Build.VERSION.SDK_INT >= 31) { d -> if (d) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) } else null

/** See [ThemeColors]. [seed] is [seedFor] (animated, in the app); [dynamic] is [dynamicSchemes]. */
fun themeColors(s: AppSettings, dark: Boolean, seed: Color, dynamic: ((Boolean) -> ColorScheme)?): ThemeColors {
    val white = s.lightStyle == com.prism.music.data.prefs.LightStyle.WHITE
    val light = SpectrumPresets.light(s.spectrumPreset, s.spectrumStops, s.spectrumBands)
    val minRatio = s.contrast.minRatio
    val custom = s.colorSource == ColorSource.CUSTOM
    val exact = custom && s.accentExact
    fun accentScheme(d: Boolean): ColorScheme =
        dynamic?.invoke(d) ?: schemeFromSeed(if (d == dark || !custom) seed else seedFor(s, d, null), d, s.pureBlack, exact, minRatio)
    val accented = accentScheme(dark).let { sc ->
        if (dark && s.pureBlack && dynamic != null) sc.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black) else sc
    }
    val accentful = dynamic != null || !isNeutralSeed(seed.toArgb())
    // White light: neutral surfaces with the accent's own colors swapped in (greys only for a white accent).
    val built = if (!white) accented else {
        val neutral = schemeFromSeed(Color.White, dark, s.pureBlack, minRatio = minRatio)
        if (!accentful) neutral
        else neutral.copy(
            primary = accented.primary, onPrimary = accented.onPrimary, primaryContainer = accented.primaryContainer,
            onPrimaryContainer = accented.onPrimaryContainer, inversePrimary = accented.inversePrimary, surfaceTint = accented.primary,
        )
    }
    // Appearance → Color → Background (1.7.2): Automatic keeps what each style always had.
    val tinted = when (s.surfaceTint) {
        com.prism.music.data.prefs.SurfaceTint.AUTO -> built
        com.prism.music.data.prefs.SurfaceTint.NEUTRAL -> withSurfaces(built, 0f, 0f, dark, s.pureBlack, minRatio)
        // The accent's hue: the wallpaper's own when the accent follows it (the stored pick isn't what's shown then).
        com.prism.music.data.prefs.SurfaceTint.ACCENT -> withSurfaces(built, (if (dynamic != null) accented.primary else seed).toHsl().first, s.surfaceTintStrength, dark, s.pureBlack, minRatio)
        com.prism.music.data.prefs.SurfaceTint.CUSTOM -> withSurfaces(built, Color(s.surfaceColor).toHsl().first, s.surfaceTintStrength, dark, s.pureBlack, minRatio)
    }
    val scheme = if (minRatio > 4.5f) firmed(tinted, minRatio) else tinted
    // The White preset (1.7.2, "Black" in light mode) lights progress, Play and switches in white / black whatever the
    // accent: it's one of the Spectrum's lights, named for what it shows. (Until then White light put the accent there.)
    // The accent stays on the accent's own things (②).
    val whiteAccent: PrismTokens.Accent? = null
    return ThemeColors(scheme, whiteAccent, light, white, dark, s.contrast.ordinal.coerceIn(0, 2), s)
}

/**
 * Which of a cover's colors the accent takes ([com.prism.music.data.prefs.ArtworkSwatch]), lifted when [vivid] so a
 * grey cover still gives a color. Null when the cover has none to give.
 */
fun pickSwatch(p: androidx.palette.graphics.Palette, swatch: com.prism.music.data.prefs.ArtworkSwatch, vivid: Boolean): Int? {
    // A swatch from a speck of the picture isn't its color (Reddit, 2026-10: Jaze's "Desconectado", a deep blue night,
    // gave a pink accent from two tail lights, 7 of its 25,600 pixels): those fall through to the next choice.
    val total = p.swatches.sumOf { it.population }.coerceAtLeast(1)
    fun androidx.palette.graphics.Palette.Swatch?.big() = this?.takeIf { it.population >= total * MIN_SWATCH_SHARE }
    val picked = when (swatch) {
        com.prism.music.data.prefs.ArtworkSwatch.VIBRANT -> p.vibrantSwatch.big() ?: p.lightVibrantSwatch.big() ?: p.dominantSwatch ?: p.mutedSwatch
        com.prism.music.data.prefs.ArtworkSwatch.MUTED -> p.mutedSwatch.big() ?: p.lightMutedSwatch.big() ?: p.darkMutedSwatch.big() ?: p.dominantSwatch
        com.prism.music.data.prefs.ArtworkSwatch.DOMINANT -> p.dominantSwatch ?: p.vibrantSwatch ?: p.mutedSwatch
    }?.rgb ?: return null
    val opaque = picked or (0xFF shl 24)
    val hsl = ColorMath.toHsl(opaque)
    return when {
        vivid -> ColorMath.fromHsl(hsl[0], hsl[1].coerceAtLeast(0.55f), hsl[2].coerceIn(0.35f, 0.7f))
        // A black-and-white cover's grey is a little tinted (a photo, the JPEG): made a true grey, it reads as neutral,
        // so the accent goes grey as the cover is (Reddit, 1.7.9), instead of the tint pushed up into a color.
        hsl[1] < GREY_COVER_SATURATION -> ColorMath.fromHsl(0f, 0f, hsl[2])
        else -> opaque
    }
}

/** A cover's color this pale is its grey ("Always colorful" off keeps it grey). */
private const val GREY_COVER_SATURATION = 0.15f

/** The least of a cover's swatched pixels a swatch needs to be its accent (see [pickSwatch]). */
private const val MIN_SWATCH_SHARE = 0.02f

@Composable
fun PrismTheme(rawSettings: AppSettings, artworkColor: Color?, content: @Composable () -> Unit) {
    // Monochrome Prism: whatever was saved or previewed, the colors are greys (Mono.kt).
    val settings = remember(rawSettings) { rawSettings.monochrome() }
    val dark = isAppDark(settings)
    val context = LocalContext.current
    // White light: neutral surfaces (no tint from the accent) and, where the spectrum would be, the accent color as in
    // Prism's earlier look; with a white accent, greys and ink only (PrismTokens). Written before anything below reads
    // it, so the first frame is already right.
    val white = settings.lightStyle == com.prism.music.data.prefs.LightStyle.WHITE
    if (PrismTokens.white != white) PrismTokens.white = white
    // Your light (1.7.2): the split light's own colors, worked out once per change.
    // From the cover (#123): a new light with each cover (no fade: the whole app would redraw every frame of one).
    val coverStops = if (settings.spectrumPreset == SpectrumPresets.COVER) SpectrumPresets.coverStops else null
    val light = remember(settings.spectrumPreset, settings.spectrumStops, settings.spectrumBands, coverStops) {
        SpectrumPresets.light(settings.spectrumPreset, settings.spectrumStops, settings.spectrumBands)
    }
    if (PrismTokens.light !== light) PrismTokens.light = light
    val contrastStep = settings.contrast.ordinal
    if (PrismTokens.contrast != contrastStep) PrismTokens.contrast = contrastStep
    val seedTarget = seedFor(settings, dark, artworkColor)
    val seed by animateColorAsState(seedTarget, tween(if (settings.colorSource == ColorSource.ARTWORK) settings.artworkChange.ms else 900), label = "seed")
    val dynamic = dynamicSchemes(settings, context)
    val colors = remember(settings, seed, dark) { themeColors(settings, dark, seed, dynamic) }
    val scheme = colors.scheme
    val whiteAccent = colors.whiteAccent
    if (PrismTokens.whiteAccent?.now != whiteAccent?.now || (PrismTokens.whiteAccent == null) != (whiteAccent == null)) PrismTokens.whiteAccent = whiteAccent

    // Shapes (1.7.7) are part of it: a cover shape, the corner slider or the buttons' shape applies at once.
    val ui = remember(settings.corners, settings.shapes, settings.uiDensity, settings.cardSize, settings.titleSize, settings.reduceMotion) { PrismUi.from(settings) }
    val typography = remember(settings.fontChoice, settings.fontStamp, settings.type) { appTypography(settings) }
    // Settings → Appearance → Text → "Lyrics font" (#12): null keeps each lyric style's own face.
    val lyricsFont = remember(settings.lyricsFont, settings.fontStamp) {
        when (settings.lyricsFont) {
            "" -> null
            com.prism.music.data.prefs.FontChoice.CUSTOM.name -> AppFonts.custom(AppFonts.FontSlot.LYRICS)
            else -> com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == settings.lyricsFont }?.let(AppFonts::family)
        }
    }
    val density = LocalDensity.current
    // The app's own text size sits on top of the phone's.
    val scaled = remember(density, settings.textScale) { Density(density.density, density.fontScale * settings.textScale) }

    CompositionLocalProvider(LocalAppSettings provides settings, LocalIsDark provides dark, LocalUi provides ui, LocalDensity provides scaled, LocalLyricsFont provides lyricsFont, LocalArtworkColor provides artworkColor) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = ui.materialShapes) {
            // Text and icons outside a Surface read LocalContentColor, which
            // otherwise defaults to black — unreadable in dark mode.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                Box(Modifier.monochrome()) { content() }
            }
        }
    }
}

/**
 * Long words broken with a hyphen where they must break, in the language's own rules (1.7.9): a narrow tile split
 * German's "Lieblingssongs" as "Lieblingssong / s" without it. Android hyphenates only where a word doesn't fit.
 */
private fun Typography.hyphenated(): Typography {
    fun TextStyle.h() = copy(hyphens = androidx.compose.ui.text.style.Hyphens.Auto)
    return copy(
        displayLarge = displayLarge.h(), displayMedium = displayMedium.h(), displaySmall = displaySmall.h(),
        headlineLarge = headlineLarge.h(), headlineMedium = headlineMedium.h(), headlineSmall = headlineSmall.h(),
        titleLarge = titleLarge.h(), titleMedium = titleMedium.h(), titleSmall = titleSmall.h(),
        bodyLarge = bodyLarge.h(), bodyMedium = bodyMedium.h(), bodySmall = bodySmall.h(),
        labelLarge = labelLarge.h(), labelMedium = labelMedium.h(), labelSmall = labelSmall.h(),
    )
}

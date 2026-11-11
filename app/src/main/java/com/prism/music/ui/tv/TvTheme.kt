package com.prism.music.ui.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme

/**
 * Prism TV's theme (1.8.0): the phone's own [PrismTheme] (accent, Look colors, fonts) in its dark form, since TVs are
 * watched in dim rooms, with Compose for TV's theme laid over it in the same colors. Shared drawing (artwork, lyrics,
 * backgrounds) keeps reading Material 3; the TV's focus cards and side navigation read Compose for TV's.
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    val settings by LocalContainer.current.settings.flow.collectAsState()
    TvTheme(settings, content)
}

/** [TvTheme] on given [settings] (the design pages and tests draw TV screens without the app around them). */
@Composable
fun TvTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = remember(settings) { if (settings.themeMode == ThemeMode.DARK) settings else settings.copy(themeMode = ThemeMode.DARK) }
    PrismTheme(dark, artworkColor = null) {
        val m3 = androidx.compose.material3.MaterialTheme.colorScheme
        val type = androidx.compose.material3.MaterialTheme.typography
        val scheme = remember(m3) { tvScheme(m3) }
        val typography = remember(type) { tvTypography(type) }
        androidx.tv.material3.MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Material 3's colors in Compose for TV's scheme (the same roles; TV adds [border] for focus rings). */
internal fun tvScheme(m: androidx.compose.material3.ColorScheme): androidx.tv.material3.ColorScheme = androidx.tv.material3.darkColorScheme(
    primary = m.primary, onPrimary = m.onPrimary, primaryContainer = m.primaryContainer, onPrimaryContainer = m.onPrimaryContainer,
    inversePrimary = m.inversePrimary,
    secondary = m.secondary, onSecondary = m.onSecondary, secondaryContainer = m.secondaryContainer, onSecondaryContainer = m.onSecondaryContainer,
    tertiary = m.tertiary, onTertiary = m.onTertiary, tertiaryContainer = m.tertiaryContainer, onTertiaryContainer = m.onTertiaryContainer,
    background = m.background, onBackground = m.onBackground,
    surface = m.surface, onSurface = m.onSurface, surfaceVariant = m.surfaceVariant, onSurfaceVariant = m.onSurfaceVariant,
    surfaceTint = m.surfaceTint, inverseSurface = m.inverseSurface, inverseOnSurface = m.inverseOnSurface,
    error = m.error, onError = m.onError, errorContainer = m.errorContainer, onErrorContainer = m.onErrorContainer,
    border = m.outline, borderVariant = m.outlineVariant, scrim = m.scrim,
)

/** Prism's type (its font and the listener's text size) in Compose for TV's styles. */
internal fun tvTypography(t: androidx.compose.material3.Typography): androidx.tv.material3.Typography = androidx.tv.material3.Typography(
    displayLarge = t.displayLarge, displayMedium = t.displayMedium, displaySmall = t.displaySmall,
    headlineLarge = t.headlineLarge, headlineMedium = t.headlineMedium, headlineSmall = t.headlineSmall,
    titleLarge = t.titleLarge, titleMedium = t.titleMedium, titleSmall = t.titleSmall,
    bodyLarge = t.bodyLarge, bodyMedium = t.bodyMedium, bodySmall = t.bodySmall,
    labelLarge = t.labelLarge, labelMedium = t.labelMedium, labelSmall = t.labelSmall,
)

/**
 * TV design's overscan-safe margins (plan §3): 48 dp at the sides, 27 dp at the top and bottom. Nothing a listener
 * needs to read or reach is drawn outside them; backgrounds may run to the edges.
 */
val TvSafe = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

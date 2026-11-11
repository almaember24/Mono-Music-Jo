package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.components.DesignGalleryContent
import com.prism.music.ui.theme.PrismTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots the Design gallery (every shared component of the 1.7 redesign, in each state) on the PC, as the app's
 * theme draws it: dark and light, with the default accent and with a hard one (pale yellow), onto
 * app/build/design-previews/. Look at them after changing a component.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h3000dp-xxhdpi")
class DesignGalleryTest {
    @get:Rule val compose = createComposeRule()

    /** [glass]: as the app draws it, on a background (the Spectrum fields here, standing in for the ambient frost). */
    private fun shoot(name: String, settings: AppSettings, glass: Boolean = true) {
        compose.setContent {
            PrismTheme(settings, artworkColor = null) {
                Box(Modifier.width(412.dp).background(MaterialTheme.colorScheme.background)) {
                    if (glass) com.prism.music.ui.theme.BackdropLayer(
                        com.prism.music.ui.theme.Backdrop.SPECTRUM, null, 0.35f, motion = false, modifier = Modifier.matchParentSize(),
                    )
                    androidx.compose.runtime.CompositionLocalProvider(com.prism.music.ui.components.LocalFrosted provides glass) { DesignGalleryContent() }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dark() = shoot("gallery-dark", AppSettings(themeMode = ThemeMode.DARK))
    @Test fun light() = shoot("gallery-light", AppSettings(themeMode = ThemeMode.LIGHT))
    @Test fun lightPaleAccent() = shoot("gallery-light-yellow", AppSettings(themeMode = ThemeMode.LIGHT, accent = 0xFFFFE066.toInt()))
    @Test fun darkPlainPage() = shoot("gallery-dark-plain", AppSettings(themeMode = ThemeMode.DARK), glass = false)

    // 1.7.2 color settings.
    @Test fun neonLightDark() = shoot("gallery-dark-neon", AppSettings(themeMode = ThemeMode.DARK, spectrumPreset = "NEON"), glass = false)
    @Test fun sunsetBandsLight() = shoot("gallery-light-sunset-bands", AppSettings(themeMode = ThemeMode.LIGHT, spectrumPreset = "SUNSET", spectrumBands = true), glass = false)
    @Test fun ownLightAndTint() = shoot(
        "gallery-dark-own-tinted",
        AppSettings(
            themeMode = ThemeMode.DARK, spectrumPreset = "CUSTOM", spectrumStops = listOf(0xFF00C2FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF2E93.toInt()),
            surfaceTint = com.prism.music.data.prefs.SurfaceTint.CUSTOM, surfaceColor = 0xFF2EC27E.toInt(), surfaceTintStrength = 2.5f,
        ),
        glass = false,
    )
    @Test fun exactAccentHighContrastLight() = shoot(
        "gallery-light-exact-high",
        AppSettings(themeMode = ThemeMode.LIGHT, accent = 0xFFFF6B35.toInt(), accentExact = true, contrast = com.prism.music.data.prefs.ContrastLevel.HIGH),
        glass = false,
    )

    /**
     * Looks (1.7.3): each of Prism's own Looks on its three preview phones (Home, the player, the lyrics), as the Looks
     * page and its cards draw them, onto looks.png. The ambient frost needs what's playing, so Home shows its plain page.
     */
    @Test fun looks() {
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                androidx.compose.foundation.layout.Column(
                    Modifier.width(412.dp).background(MaterialTheme.colorScheme.background).padding(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    com.prism.music.data.looks.BuiltInLooks.all.forEach { look ->
                        val s = repo.previewLook(look.values).copy(frostedPages = false)
                        val dark = s.themeMode != ThemeMode.LIGHT
                        androidx.compose.material3.Text(look.name + " · " + look.note, style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            com.prism.music.ui.screens.PreviewPhone(118.dp) { com.prism.music.ui.screens.LookTheme(s, dark) { c -> com.prism.music.ui.screens.MiniHome(c, null) } }
                            com.prism.music.ui.screens.PreviewPhone(118.dp) { com.prism.music.ui.screens.LookTheme(s, true) { c -> com.prism.music.ui.screens.MiniPlayerScreen(c, null) } }
                            com.prism.music.ui.screens.PreviewPhone(118.dp) { com.prism.music.ui.screens.LookTheme(s, true) { c -> com.prism.music.ui.screens.MiniLyrics(c, null) } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "looks.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The seasonal Looks (1.8.0), Harvest and Holiday, each in dark and in light: Home, the player and the lyrics as the
     * Looks page draws them, with their colors throughout (accent, split light, page tint) and their background, onto
     * seasonal-looks.png.
     */
    @Test fun seasonalLooks() {
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                androidx.compose.foundation.layout.Column(
                    Modifier.width(820.dp).background(MaterialTheme.colorScheme.background).padding(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    for (season in com.prism.music.data.looks.Season.entries) {
                        val look = com.prism.music.data.looks.BuiltInLooks.of(season.lookId)!!
                        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                            val s = repo.previewLook(look.values).copy(frostedPages = false, themeMode = mode)
                            val dark = mode == ThemeMode.DARK
                            androidx.compose.material3.Text("${look.name} · ${mode.name.lowercase()}", style = MaterialTheme.typography.labelMedium)
                            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                                com.prism.music.ui.screens.PreviewPhone(190.dp) { com.prism.music.ui.screens.LookTheme(s, dark) { c -> com.prism.music.ui.screens.MiniHome(c, null) } }
                                com.prism.music.ui.screens.PreviewPhone(190.dp) { com.prism.music.ui.screens.LookTheme(s, dark) { c -> com.prism.music.ui.screens.MiniPlayerScreen(c, null) } }
                                com.prism.music.ui.screens.PreviewPhone(190.dp) { com.prism.music.ui.screens.LookTheme(s, dark) { c -> com.prism.music.ui.screens.MiniLyrics(c, null) } }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "seasonal-looks.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The player, your way (1.7.4): the Looks preview's player (as the pinned preview on Settings → Player draws it) in
     * each layout, and with the details changed (centred large title, accent bar, time left only, waveform, other
     * buttons, the deck in another order), onto player174.png.
     */
    @Test fun playerYourWay() {
        val base = AppSettings(themeMode = ThemeMode.DARK, frostedPages = false)
        val variants = com.prism.music.data.prefs.PlayerLayout.entries.map { it.label to base.copy(playerLayout = it) } + listOf(
            "Centred, large, accent, time left" to base.copy(
                playerTitleAlign = com.prism.music.data.prefs.PlayerTitleAlign.CENTER, playerTitleSize = com.prism.music.data.prefs.PlayerTitleSize.LARGE,
                scrubberColor = com.prism.music.data.prefs.ScrubberColor.ACCENT, playerTimes = com.prism.music.data.prefs.PlayerTimes.LEFT,
            ),
            "Waveform, five buttons" to base.copy(
                scrubberStyle = com.prism.music.data.prefs.ScrubberStyle.WAVE,
                playerButtons = listOf(
                    com.prism.music.data.prefs.PlayerButton.LIKE, com.prism.music.data.prefs.PlayerButton.LYRICS, com.prism.music.data.prefs.PlayerButton.QUEUE,
                    com.prism.music.data.prefs.PlayerButton.SLEEP, com.prism.music.data.prefs.PlayerButton.DJ,
                ),
            ),
            "Buttons first, lyric last" to base.copy(
                playerDeck = listOf(
                    com.prism.music.data.prefs.DeckPart.BUTTONS, com.prism.music.data.prefs.DeckPart.SCRUBBER, com.prism.music.data.prefs.DeckPart.TRANSPORT,
                    com.prism.music.data.prefs.DeckPart.VOLUME, com.prism.music.data.prefs.DeckPart.STRIP,
                ),
                lyricsOnPlayer = true,
            ),
        )
        compose.setContent {
            PrismTheme(base, artworkColor = null) {
                androidx.compose.foundation.layout.Column(
                    Modifier.width(412.dp).background(MaterialTheme.colorScheme.background).padding(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    variants.chunked(3).forEach { row ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            row.forEach { (name, s) ->
                                androidx.compose.foundation.layout.Column {
                                    androidx.compose.material3.Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    com.prism.music.ui.screens.PreviewPhone(122.dp) { com.prism.music.ui.screens.LookTheme(s, true) { c -> com.prism.music.ui.screens.MiniPlayerScreen(c, null) } }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "player174.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The color wheel and the spectrum presets, dark and light. */
    @Test fun colorPickerDark() = colorPicker(true)
    @Test fun colorPickerLight() = colorPicker(false)

    private fun colorPicker(dark: Boolean) {
        run {
            compose.setContent {
                PrismTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT), artworkColor = null) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.width(412.dp).background(MaterialTheme.colorScheme.background),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        com.prism.music.ui.components.ColorWheel(215f, 0.75f, 0.9f, Modifier.width(412.dp)) { _, _, _ -> }
                        com.prism.music.ui.theme.SpectrumPresets.all.forEach { p ->
                            val l = com.prism.music.ui.theme.SpectrumPresets.light(p.key, emptyList(), false)
                            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.material3.Text(p.label, Modifier.width(130.dp).padding(8.dp), style = MaterialTheme.typography.labelMedium)
                                Box(
                                    Modifier.width(180.dp).height(10.dp).background(
                                        androidx.compose.ui.graphics.Brush.horizontalGradient(*(if (dark) l.colors else l.deep).toTypedArray()),
                                        androidx.compose.foundation.shape.CircleShape,
                                    ),
                                )
                                l.hues.forEach { h -> Box(Modifier.padding(2.dp).size(12.dp).background(h)) }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/design-previews").apply { mkdirs() }
            File(dir, "color-picker-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

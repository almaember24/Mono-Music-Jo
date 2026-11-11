package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookGallery
import com.prism.music.data.looks.LookIcons
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.theme.PrismTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Prism's own community Looks (owner, 2026-10-29, from a Reddit request for "presets like Apple Music, YT Music,
 * Spotify"): the feel of each app in Prism's own names and pieces, never their names, logos or artwork. They live in the
 * community gallery (themes/), so they reach listeners without an update. [codes] writes their codes to
 * PRISM_COMMUNITY_LOOKS (one `id|code` per line) for themes/index.json; [previews] draws every gallery Look (Home,
 * player, lyrics) onto app/build/design-previews/community-looks.png to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h3000dp-xxhdpi", application = android.app.Application::class)
class CommunityLooksTest {
    @get:Rule val compose = createComposeRule()

    private val repo by lazy {
        SettingsRepository(org.robolectric.RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun color(argb: Long) = "i:${argb.toInt()}"

    private val looks = listOf(
        // A near-black app with one bright green: bold geometric type, a docked bar, lyrics as big lines on the cover's color.
        Look(
            "evergreen", "Evergreen",
            values = mapOf(
                "theme_mode" to "s:DARK", "color_source" to "s:CUSTOM", "accent" to color(0xFF1ED760), "accent_exact" to "b:1",
                "light_style" to "s:WHITE", "surface_tint" to "s:NEUTRAL", "font_choice" to "s:GEOMETRIC", "type_classic" to "b:1",
                "player_title_weight" to "s:BOLD", "corners" to "s:SUBTLE", "liquid_glass" to "b:0", "frosted_glass" to "b:0",
                "frosted_pages" to "b:0", "nav_style" to "s:DOCKED", "nav_labels" to "s:ALWAYS", "tab_icon_tint" to "s:ACCENT",
                "mini_player_style" to "s:CARD", "mini_progress" to "s:LINE", "player_background" to "s:MESH",
                "transport_style" to "s:BUTTON", "scrubber_style" to "s:HAIRLINE", "scrubber_color" to "s:ACCENT", "scrubber_thumb" to "s:DOT",
                "button_shape" to "s:PILL", "cover_shape_artists" to "s:CIRCLE", "lyrics_style" to "s:CARD",
                "lyrics_background" to "s:COVER_COLOR", "lyrics_weight" to "s:BOLD", "lyrics_align" to "s:START",
            ),
        ),
        // Pure black and red, plain system type, square-cornered covers, a slim mini player and a white Play button.
        Look(
            "redline", "Redline",
            values = mapOf(
                "theme_mode" to "s:DARK", "pure_black" to "b:1", "color_source" to "s:CUSTOM", "accent" to color(0xFFFF0033),
                "accent_exact" to "b:1", "light_style" to "s:WHITE", "surface_tint" to "s:NEUTRAL", "font_choice" to "s:SYSTEM", "type_classic" to "b:1",
                "player_title_weight" to "s:BOLD", "corners" to "s:SUBTLE", "liquid_glass" to "b:0", "frosted_glass" to "b:0",
                "frosted_pages" to "b:0", "nav_style" to "s:DOCKED", "nav_labels" to "s:ALWAYS", "tab_icon_tint" to "s:INK",
                "mini_player_style" to "s:SLIM", "mini_progress" to "s:LINE", "player_background" to "s:BLUR",
                "transport_style" to "s:BUTTON", "scrubber_style" to "s:HAIRLINE", "scrubber_color" to "s:ACCENT", "scrubber_thumb" to "s:DOT",
                "cover_shape_artists" to "s:CIRCLE", "button_shape" to "s:PILL", "lyrics_weight" to "s:BOLD", "lyrics_align" to "s:START",
                "lyrics_blur_amount" to "f:0.0",
            ),
        ),
        // Light frosted glass, a pink-red accent on the tabs and buttons, big bold titles, a moving color mesh behind the
        // player and lyrics that grow bold as they're sung, with Prism's Apple-manner icons.
        Look(
            "glasshouse", "Glasshouse",
            values = mapOf(
                "color_source" to "s:CUSTOM", "accent" to color(0xFFFA2D48), "accent_exact" to "b:1", "light_style" to "s:WHITE",
                "surface_tint" to "s:NEUTRAL", "font_choice" to "s:CLEAN", "title_size" to "s:LARGE", "type_classic" to "b:1",
                "player_title_weight" to "s:BOLD", "corners" to "s:EXTRA", "liquid_glass" to "b:1", "frosted_glass" to "b:1",
                "frosted_pages" to "b:1", "nav_style" to "s:FLOATING", "nav_labels" to "s:ALWAYS", "tab_icon_tint" to "s:ACCENT",
                "mini_player_style" to "s:CARD", "player_background" to "s:MESH", "transport_style" to "s:GLYPHS",
                "scrubber_style" to "s:BOLD", "scrubber_color" to "s:WHITE", "scrubber_thumb" to "s:NONE", "button_shape" to "s:PILL",
                "cover_shape_artists" to "s:CIRCLE", "lyrics_style" to "s:PRISM", "lyrics_weight" to "s:BOLD", "lyrics_align" to "s:START",
                "lyrics_position" to "s:THIRD",
            ),
            icons = LookIcons(pack = "CUPERTINO"),
        ),
    )

    /** Every value is one a Look may hold, and each enum name is one the setting knows (a typo would read as the default). */
    @Test
    fun theLooksAreWhole() {
        val kinds = repo.lookKinds
        for (look in looks) {
            assertEquals(look.id, look.values, LookCode.sanitized(look, kinds).values)
            val previewed = repo.lookAfter(look.values)
            for ((k, v) in look.values) assertEquals("${look.id} $k", v, previewed[k])
            val names = com.prism.music.data.looks.LookLabels
            for ((k, v) in look.values) if (v.startsWith("s:") && k != "backdrops") {
                assertEquals("${look.id} $k: '${v.drop(2)}' isn't one of its choices", true, names.value(k, v) != v.drop(2) || k == "font_choice")
            }
        }
    }

    @Test
    fun codes() {
        val out = System.getenv("PRISM_COMMUNITY_LOOKS") ?: return
        File(out).writeText(looks.joinToString("") { "${it.id}|${LookCode.encode(it)}\n" })
    }

    @Test
    fun previews() {
        val kinds = repo.lookKinds
        val gallery = LookGallery.parse(File("../themes/index.json").readText(), kinds).map { it.look }
        val shown = gallery.filter { g -> looks.none { it.name == g.name } } + looks
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                androidx.compose.foundation.layout.Column(
                    Modifier.width(412.dp).background(MaterialTheme.colorScheme.background).padding(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    shown.forEach { look ->
                        // Frosted pages need the app's playing cover, which a test hasn't got (as DesignGalleryTest.looks).
                        val s = repo.previewLook(look.values).copy(frostedPages = false)
                        val dark = s.themeMode != ThemeMode.LIGHT
                        androidx.compose.material3.Text(look.name, style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            com.prism.music.ui.screens.PreviewPhone(118.dp) { com.prism.music.ui.screens.LookTheme(s, dark) { c -> com.prism.music.ui.screens.MiniHome(c, null) } }
                            com.prism.music.ui.screens.PreviewPhone(118.dp) { com.prism.music.ui.screens.LookTheme(s, false) { c -> com.prism.music.ui.screens.MiniHome(c, null) } }
                            com.prism.music.ui.screens.PreviewPhone(118.dp) { com.prism.music.ui.screens.LookTheme(s, true) { c -> com.prism.music.ui.screens.MiniPlayerScreen(c, null) } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "community-looks.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

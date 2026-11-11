package com.prism.music.ui.screens

import com.prism.music.ui.theme.Mono
import com.prism.music.R
import com.prism.music.tr
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.hiRes
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ArtworkChange
import com.prism.music.data.prefs.ArtworkSwatch
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.ContrastLevel
import com.prism.music.data.prefs.LightStyle
import com.prism.music.data.prefs.SurfaceTint
import com.prism.music.ui.components.ColorPickerSheet
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.Segmented
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.theme.ColorMath
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.SpectrumPresets
import com.prism.music.ui.theme.ThemeColors
import com.prism.music.ui.theme.isNeutralSeed
import com.prism.music.ui.theme.pickSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Which color the picker is open for. */
private sealed interface Picking {
    data object Accent : Picking
    data object LightAccent : Picking
    data object Surface : Picking
    /** One color of your own light ([index] past the end adds one). */
    data class Stop(val index: Int) : Picking
}

/**
 * Appearance → Color (1.7.2): a preview of your Prism (dark and light, its three parts numbered), pinned under the bar
 * once scrolled past ([COLOR_PREVIEW_KEY], see SubPage's pinned); below it every choice is a card showing what picking
 * it looks like.
 */
fun androidx.compose.foundation.lazy.LazyListScope.colorPage() {
    item(key = COLOR_PREVIEW_KEY) { ColorPreviewBar() }
    item(key = "color") { ColorSettings() }
}

const val COLOR_PREVIEW_KEY = "color-preview"

/** Which mode the preview shows (null = the app's own); shared by the page's preview and its pinned copy. */
private val previewDark = mutableStateOf<Boolean?>(null)

/**
 * Your Prism as it is now: its Now playing in dark or light (a switch above it), with the three parts numbered on it and
 * named in a key underneath, each with a swatch of what it is. [pinned]: the one-row copy held under the bar while the
 * page scrolls.
 */
@Composable
fun ColorPreviewBar(pinned: Boolean = false) {
    val s = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = previewDark.value ?: LocalIsDark.current
    val c = rememberColors(s, dark)
    val song by LocalContainer.current.player.currentSong.collectAsState()
    if (pinned) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(frostFill(4f)).background(scheme.surface.copy(alpha = 0.82f))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        ) { ColorStage(c, song, compact = true) }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.your_prism), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
                Text(tr(R.string.changes_the_moment_you_pick), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Segmented(listOf(true, false), dark, { if (it) tr(R.string.dark) else tr(R.string.light) }, Modifier.width(164.dp)) { previewDark.value = it }
        }
        ColorStage(c, song)
        PartsKey(c)
    }
}

/** The three parts, numbered as on the preview, each with a swatch of itself in [c]. */
@Composable
private fun PartsKey(c: ThemeColors) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPart.entries.forEach { p ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(frostFill())
                    .border(1.dp, scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(16.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.number}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    when (p) {
                        ColorPart.LIGHT -> Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())))
                        ColorPart.ACCENT -> Box(Modifier.size(14.dp).clip(CircleShape).background(c.scheme.primary))
                        ColorPart.BACKGROUND -> Box(
                            Modifier.size(width = 26.dp, height = 14.dp).clip(RoundedCornerShape(4.dp)).background(c.scheme.background)
                                .border(1.dp, scheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                        )
                    }
                }
                var open by androidx.compose.runtime.saveable.rememberSaveable(p.name) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Whole words only: in the Classic Look's wider type "Background" was split mid-word (1.7.8 check).
                    WholeWords(p.title, MaterialTheme.typography.labelLarge, scheme.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                    InfoMark(open, p.title) { open = !open }
                }
                InfoText(open, p.detail)
            }
        }
    }
}

@Composable
private fun ColorSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val dark = LocalIsDark.current
    var picking by remember { mutableStateOf<Picking?>(null) }
    val white = s.lightStyle == LightStyle.WHITE
    val cover = rememberCoverPalette()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // How the page works, behind an ⓘ like every other explanation.
        var introOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(R.string.three_parts_numbered_in_the_preview),
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                )
                InfoMark(introOpen, tr(R.string.the_color_page)) { introOpen = !introOpen }
            }
            InfoText(
                introOpen,
                tr(R.string.each_group_s_says_everything_it),
                Modifier.padding(top = 4.dp),
            )
        }

        // White light is one of the Spectrum's choices (1.7.2): the light before the prism splits it.
        Group(
            tr(R.string.spectrum),
            info = changes(
                tr(R.string.the_light_the_colors_prism_lights),
                tr(R.string.the_progress_bar_and_play_ring_all),
                also = if (!white) null else
                    tr(R.string.lights_all_of_these_in_in_all, whiteName(dark), whiteName(dark).lowercase(), whiteName(!dark).lowercase(), if (dark) tr(R.string.mode_light) else tr(R.string.mode_dark)),
            ),
        ) {
            LightPresets(s, dark) { key ->
                when (key) {
                    WHITE_KEY -> prefs.setLightStyle(LightStyle.WHITE)
                    "CUSTOM" -> {
                        // Start your own from whatever is showing now.
                        val from = when {
                            s.spectrumStops.size >= 2 -> s.spectrumStops
                            s.spectrumPreset == SpectrumPresets.COVER && SpectrumPresets.coverStops.size >= 2 -> SpectrumPresets.coverStops
                            else -> SpectrumPresets.colorsFor(s.spectrumPreset, emptyList()).map { it.toArgb() }
                        }
                        prefs.setSpectrumStops(from)
                        prefs.setLightStyle(LightStyle.SPECTRUM)
                    }
                    else -> { prefs.setSpectrumPreset(key); prefs.setLightStyle(LightStyle.SPECTRUM) }
                }
            }
            if (!white && s.spectrumPreset == "CUSTOM") StopEditor(s.spectrumStops, onEdit = { picking = Picking.Stop(it) }) { prefs.setSpectrumStops(s.spectrumStops.reversed()) }
            if (!white) ChoiceCards(
                listOf(false, true), s.spectrumBands,
                title = { if (it) tr(R.string.bands) else tr(R.string.smooth) },
                detail = { if (it) tr(R.string.stripes_of_each_color_like_the) else tr(R.string.each_color_blends_into_the_next) },
                onSelect = { prefs.setSpectrumBands(it) },
            ) { LightStrip(rememberColors(s.copy(spectrumBands = it), dark)) }
        }

        Group(
            tr(R.string.accent_color),
            info = changes(
                tr(R.string.the_accent_a_second_color_for),
                tr(R.string.artist_names_and_links_on_album_all),
                also = if (s.colorSource != ColorSource.DYNAMIC) null else
                    tr(R.string.wallpaper_taken_from_your_phone_s_all),
            ),
        ) {
            // Picture's color takes over the accent on exactly the pages it shows most on: say so here, where it's missed.
            if (s.pageColors) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr(R.string.album_playlist_and_artist_pages_use),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                )
                PrismTextButton({ prefs.setPageColors(false) }) { Text(tr(R.string.use_mine)) }
            }
            val sources = ColorSource.entries.filter { it != ColorSource.DYNAMIC || Build.VERSION.SDK_INT >= 31 }
            ChoiceCards(
                sources, s.colorSource,
                title = { when (it) { ColorSource.ARTWORK -> tr(R.string.song_s_cover); ColorSource.DYNAMIC -> tr(R.string.wallpaper); ColorSource.CUSTOM -> tr(R.string.your_pick) } },
                detail = {
                    when (it) {
                        ColorSource.ARTWORK -> tr(R.string.follows_the_song_that_s_playing)
                        ColorSource.DYNAMIC -> tr(R.string.your_phone_wallpaper_s_colors)
                        ColorSource.CUSTOM -> tr(R.string.one_color_you_choose)
                    }
                },
                columns = 3,
                onSelect = { prefs.setColorSource(it) },
            ) { AccentSample(rememberColors(s.copy(colorSource = it), dark)) }
            when (s.colorSource) {
                ColorSource.CUSTOM -> {
                    Item(tr(R.string.accent_color), tr(R.string.tap_to_change, ColorMath.toHex(s.accent)), onClick = { picking = Picking.Accent }) { Swatch(s.accent) }
                    if (!isNeutralSeed(s.accent)) {
                        Label(tr(R.string.how_it_s_used), info = tr(R.string.prism_can_soften_it_to_suit))
                        ChoiceCards(
                            listOf(false, true), s.accentExact,
                            title = { if (it) tr(R.string.exactly_as_picked) else tr(R.string.softened) },
                            detail = { if (it) tr(R.string.your_color_as_it_is_only) else tr(R.string.lighter_on_dark_pages_deeper_on) },
                            onSelect = { prefs.setAccentExact(it) },
                        ) { exact -> AccentPair(s.copy(accentExact = exact)) }
                    }
                    // 1.8.0: one row instead of a switch that only revealed it (accentLight 0 = the same as the accent;
                    // the picker starts from the accent and only saves on Done).
                    Item(
                        tr(R.string.light_mode_accent),
                        if (s.accentLight == 0) tr(R.string.same_as_your_accent) else tr(R.string.tap_to_change, ColorMath.toHex(s.accentLight)),
                        info = tr(R.string.light_pages_get_an_accent_of),
                        onClick = { picking = Picking.LightAccent },
                    ) {
                        if (s.accentLight != 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            PrismTextButton(onClick = { prefs.setAccentLight(0) }) { Text(tr(R.string.use_the_same)) }
                            Swatch(s.accentLight)
                        } else Chevron()
                    }
                }
                ColorSource.ARTWORK -> {
                    Label(
                        tr(R.string.which_of_its_colors),
                        info = if (cover == null) tr(R.string.which_of_the_cover_s_colors) else tr(R.string.which_of_this_cover_s_colors),
                    )
                    ChoiceCards(
                        ArtworkSwatch.entries, s.artworkSwatch,
                        title = { it.label },
                        detail = { when (it) { ArtworkSwatch.VIBRANT -> tr(R.string.its_brightest_color); ArtworkSwatch.MUTED -> tr(R.string.a_softer_dusty_one); ArtworkSwatch.DOMINANT -> tr(R.string.the_color_it_has_most_of) } },
                        columns = 3,
                        onSelect = { prefs.setArtworkSwatch(it) },
                    ) { sw ->
                        val col = cover?.let { pickSwatch(it, sw, s.artworkVivid) }?.let { Color(it) } ?: SAMPLE_COVER[sw]!!
                        AccentSample(rememberColors(s.copy(colorSource = ColorSource.CUSTOM, accent = col.toArgb(), accentLight = 0, accentExact = false), dark))
                    }
                    Toggle(tr(R.string.always_colorful), tr(R.string.grey_or_black_and_white_covers), s.artworkVivid) { prefs.setArtworkVivid(it) }
                    Label(
                        tr(R.string.between_songs),
                        info = s.artworkChange.label + ": " + when (s.artworkChange) {
                            ArtworkChange.INSTANT -> tr(R.string.the_new_color_appears_the_moment)
                            ArtworkChange.FADE -> tr(R.string.the_color_fades_to_the_new)
                            ArtworkChange.SLOW -> tr(R.string.a_slow_gentle_fade_of_a)
                            ArtworkChange.ALBUM -> tr(R.string.songs_from_the_same_album_keep)
                        },
                    )
                    Chips(ArtworkChange.entries, s.artworkChange, { it.label }) { prefs.setArtworkChange(it) }
                }
                ColorSource.DYNAMIC -> {}
            }
        }

        Group(
            tr(R.string.page_background),
            info = changes(
                tr(R.string.the_background_what_prism_s_own),
                tr(R.string.the_ambient_frost_behind_home_search_all),
            ),
        ) {
            val homeBackdrop = com.prism.music.ui.theme.Backdrop.parse(s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name]).first
            if (homeBackdrop != com.prism.music.ui.theme.Backdrop.DEFAULT) Note(
                tr(R.string.home_has_the_background_glass_backgrounds_all, homeBackdrop.label)
            )
            // In White light, Automatic is already plain grey: Plain would be the same choice twice.
            val tints = SurfaceTint.entries.filter { !(white && it == SurfaceTint.NEUTRAL && s.surfaceTint != SurfaceTint.NEUTRAL) }
            ChoiceCards(
                tints, s.surfaceTint,
                title = { when (it) { SurfaceTint.AUTO -> tr(R.string.automatic_2); SurfaceTint.NEUTRAL -> tr(R.string.plain); SurfaceTint.ACCENT -> tr(R.string.accent_tint); SurfaceTint.CUSTOM -> tr(R.string.your_color) } },
                detail = {
                    when (it) {
                        SurfaceTint.AUTO -> if (white) tr(R.string.grey_the_frost_in_greys_too) else tr(R.string.the_cover_s_colors_in_the)
                        SurfaceTint.NEUTRAL -> tr(R.string.grey_the_frost_loses_its_color)
                        SurfaceTint.ACCENT -> if (s.colorSource == ColorSource.ARTWORK) tr(R.string.the_accent_s_color_frost_included) else tr(R.string.the_accent_s_color_frost_included_2)
                        SurfaceTint.CUSTOM -> tr(R.string.a_color_you_pick_frost_included)
                    }
                },
                onSelect = { prefs.setSurfaceTint(it) },
            ) { ColorSample(rememberColors(s.copy(surfaceTint = it), dark)) }
            if (s.surfaceTint == SurfaceTint.CUSTOM) Item(tr(R.string.background_color), tr(R.string.tap_to_change, ColorMath.toHex(s.surfaceColor)), onClick = { picking = Picking.Surface }) { Swatch(s.surfaceColor) }
            if (s.surfaceTint == SurfaceTint.ACCENT || s.surfaceTint == SurfaceTint.CUSTOM) StrengthSlider(s) { prefs.setSurfaceTintStrength(it) }
            if (s.pureBlack) Note(tr(R.string.pure_black_under_theme_keeps_dark))
        }

        Group(
            tr(R.string.text_contrast),
            info = changes(
                tr(R.string.how_strong_grey_text_and_lines),
                tr(R.string.secondary_text_artists_times_notes_dividers_all),
            ),
        ) {
            ChoiceCards(
                ContrastLevel.entries, s.contrast,
                title = { it.label },
                detail = { when (it) { ContrastLevel.STANDARD -> tr(R.string.soft_grey_text); ContrastLevel.MEDIUM -> tr(R.string.firmer); ContrastLevel.HIGH -> tr(R.string.strongest_easiest_to_read) } },
                columns = 3,
                onSelect = { prefs.setContrast(it) },
            ) { TextSample(rememberColors(s.copy(contrast = it), dark)) }
        }

        Group(
            tr(R.string.artist_album_pages),
            info = changes(
                tr(R.string.their_accent_yours_as_everywhere_else),
                tr(R.string.the_accent_on_artist_album_and_all),
                also = tr(R.string.an_artist_s_page_can_also),
            ),
        ) {
            // The playing cover's page accent, worked out exactly as its page does (none for a black-and-white cover).
            val art = cover?.let { p ->
                val dominant = (p.dominantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch)?.rgb?.let { Color(Mono.gray(it or (0xFF shl 24))) }
                val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch)?.rgb?.let { Color(Mono.gray(it or (0xFF shl 24))) } ?: dominant
                if (dominant != null && vibrant != null) com.prism.music.ui.components.ArtPalette(vibrant, dominant) else null
            } ?: com.prism.music.ui.components.ArtPalette(SAMPLE_COVER[ArtworkSwatch.VIBRANT]!!, SAMPLE_COVER[ArtworkSwatch.DOMINANT]!!)
            val c = rememberColors(s, dark)
            val pictureAccent = art.accent(dark)?.let { com.prism.music.ui.theme.readableOn(it, c.scheme.background) }
            ChoiceCards(
                listOf(false, true), s.pageColors,
                title = { if (it) tr(R.string.picture_s_color) else tr(R.string.your_accent) },
                detail = {
                    if (it) tr(R.string.each_page_and_menu_takes_a)
                    else tr(R.string.your_accent_on_every_page_and)
                },
                onSelect = { prefs.setPageColors(it) },
            ) { on -> ColorSample(c, pageAccent = if (on) pictureAccent else null) }
            // Reddit (1.7.9.2): the playing song's bars and title in the page's color, whichever accent the page has.
            Toggle(
                tr(R.string.playing_song_in_the_page_s_color), tr(R.string.its_bars_and_title_take_the_album_s_color),
                s.view.playingInPageColor, info = tr(R.string.on_album_playlist_and_artist_pages_the_playing_song),
            ) { prefs.setPlayingInPageColor(it) }
        }
        ResetColors()
    }

    picking?.let { p ->
        val (title, initial) = when (p) {
            Picking.Accent -> tr(R.string.accent_color) to s.accent
            Picking.LightAccent -> tr(R.string.light_mode_accent) to s.accentLight.takeIf { it != 0 }.let { it ?: s.accent }
            Picking.Surface -> tr(R.string.background_color) to s.surfaceColor
            is Picking.Stop -> tr(R.string.light_color, p.index + 1) to (s.spectrumStops.getOrNull(p.index) ?: s.spectrumStops.lastOrNull() ?: s.accent)
        }
        val removable = p is Picking.Stop && p.index < s.spectrumStops.size && s.spectrumStops.size > 2
        fun withColor(c: Int): AppSettings = when (p) {
            Picking.Accent -> s.copy(colorSource = ColorSource.CUSTOM, accent = c)
            Picking.LightAccent -> s.copy(accentLight = c)
            Picking.Surface -> s.copy(surfaceTint = SurfaceTint.CUSTOM, surfaceColor = c)
            is Picking.Stop -> s.copy(
                spectrumPreset = "CUSTOM",
                spectrumStops = if (p.index >= s.spectrumStops.size) s.spectrumStops + c else s.spectrumStops.mapIndexed { i, old -> if (i == p.index) c else old },
            )
        }
        ColorPickerSheet(
            title, initial,
            onDismiss = { picking = null },
            note = when (p) {
                Picking.Accent, Picking.LightAccent -> { c -> accentNote(c, s.accentExact) }
                else -> null
            },
            reset = if (removable) tr(R.string.remove_this_color) to { prefs.setSpectrumStops(s.spectrumStops.filterIndexed { i, _ -> i != (p as Picking.Stop).index }) } else null,
            // While you pick: Prism with that color, dark and light (light only for light mode's own accent).
            preview = { c ->
                val next = withColor(c)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p != Picking.LightAccent) ColorSample(rememberColors(next, true), Modifier.weight(1f), height = 128.dp)
                    ColorSample(rememberColors(next, false), Modifier.weight(1f), height = 128.dp)
                }
            },
        ) { c ->
            when (p) {
                Picking.Accent -> prefs.setAccent(c)
                Picking.LightAccent -> prefs.setAccentLight(c)
                Picking.Surface -> prefs.setSurfaceColor(c)
                is Picking.Stop -> prefs.setSpectrumStops(withColor(c).spectrumStops)
            }
        }
    }
}

/**
 * A group's ⓘ: what the part is, then everything its choice changes in the app ([also]: more for the choice made), so
 * nothing it touches is a surprise.
 */
private fun changes(what: String, changes: String, also: String? = null): String =
    tr(R.string.changes, what, changes) + (also?.let { "\n\n$it" } ?: "")

/** Stand-ins for a cover's colors while nothing is playing. */
private val SAMPLE_COVER = mapOf(
    ArtworkSwatch.VIBRANT to Color(0xFF919191), ArtworkSwatch.MUTED to Color(0xFF898989), ArtworkSwatch.DOMINANT to Color(0xFF272727),
)

/** The playing song's cover colors, for the "Song's cover" examples; null when nothing is playing. */
@Composable
private fun rememberCoverPalette(): Palette? {
    val c = LocalContainer.current
    val context = LocalContext.current
    val song by c.player.currentSong.collectAsState()
    val palette by produceState<Palette?>(null, song?.thumbnail) {
        val url = song?.thumbnail ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(hiRes(url, 160) ?: url).allowHardware(false).build()
                (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()?.let { Palette.from(it).generate() }
            }.getOrNull()
        }
    }
    return palette
}

/** The accent alone, small: an artist's name, a star and a link on the page. */
@Composable
private fun AccentSample(c: ThemeColors) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().height(64.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width = 30.dp, height = 8.dp).clip(CircleShape).background(c.scheme.primary))
        Text(tr(R.string.the_weeknd), color = c.scheme.primary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PrismIcons.StarFilled, null, Modifier.size(11.dp), tint = c.scheme.primary)
            Spacer(Modifier.width(4.dp))
            Text("MORE", color = c.scheme.primary, fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The accent as it comes out on a dark page and a light page, side by side. */
@Composable
private fun AccentPair(s: AppSettings) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(true, false).forEach { d ->
            val c = rememberColors(s, d)
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.weight(1f).height(52.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tr(R.string.artist), color = c.scheme.primary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.scheme.primary))
            }
        }
    }
}

/** The light alone: a progress line and a switch. */
@Composable
private fun LightStrip(c: ThemeColors) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, Brush.sweepGradient(c.ring), CircleShape))
    }
}

/** What Prism does with an accent, when it doesn't use it exactly as picked. */
private fun accentNote(c: Int, exact: Boolean): String? = when {
    isNeutralSeed(c) -> tr(R.string.white_black_and_greys_give_prism)
    !exact -> tr(R.string.shown_softened_lighter_on_dark_pages)
    ColorMath.contrast(c, 0xFF0E0E0E.toInt()) < 4.5 || ColorMath.contrast(c, 0xFFFAFAFA.toInt()) < 4.5 ->
        tr(R.string.made_a_little_lighter_or_darker)
    else -> null
}

/** A filled circle of [color], for a row's end. */
@Composable
private fun Swatch(color: Int) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape),
    )
}

/** Tint strength: the example follows the slider as it moves; it's kept when you let go. */
@Composable
private fun StrengthSlider(s: AppSettings, onSave: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(s.surfaceTintStrength) }
    LaunchedEffect(s.surfaceTintStrength) { v = s.surfaceTintStrength }
    val dark = LocalIsDark.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.tint_strength), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                worded(when { v < 0.6f -> tr(R.string.faint); v < 1.4f -> tr(R.string.gentle); v < 2.2f -> tr(R.string.strong); else -> tr(R.string.very_strong) }, v),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrismSlider(v, { v = it }, valueRange = 0.2f..3f, onValueChangeFinished = { onSave(v) })
        ColorSample(rememberColors(s.copy(surfaceTintStrength = v), dark), height = 128.dp)
    }
}

/** Each light as a ring and a strip, with its name; the picked one ringed. The last is your own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightPresets(s: AppSettings, dark: Boolean, onPick: (String) -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val white = s.lightStyle == LightStyle.WHITE
    // White is a preset like the others: white light on dark pages, black on light ones (named for what it shows).
    // A seasonal Look's light only in its month (or while it's the one picked).
    val today = remember { com.prism.music.data.looks.Seasons.today() }
    val keys = SpectrumPresets.all.filter { p -> p.season?.contains(today) ?: true || p.key == s.spectrumPreset }.map { it.key to it.label } + (SpectrumPresets.COVER to SpectrumPresets.COVER_LABEL) +
        (WHITE_KEY to whiteName(dark)) + ("CUSTOM" to tr(R.string.your_own))
    // Three to a row, every tile the same width (a short last row keeps its tiles' size, it doesn't stretch them).
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      keys.chunked(3).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { (key, label) ->
            val on = if (key == WHITE_KEY) white else !white && key == s.spectrumPreset
            val empty = key == "CUSTOM" && s.spectrumStops.size < 2
            val c = rememberColors(
                if (key == WHITE_KEY) s.copy(lightStyle = LightStyle.WHITE) else s.copy(spectrumPreset = key, lightStyle = LightStyle.SPECTRUM),
                dark,
            )
            Column(
                Modifier.weight(1f).clip(ui.tile)
                    .background(frostFill(if (on) 2.6f else 1f))
                    .then(if (on) Modifier.border(2.dp, PrismTokens.spectrum, ui.tile) else Modifier)
                    .clickable { onPick(key) }.padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .border(2.5.dp, if (empty) Brush.linearGradient(listOf(scheme.outline, scheme.outline)) else Brush.sweepGradient(c.ring), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (empty) Icon(PrismIcons.Add, null, Modifier.size(12.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.weight(1f).height(8.dp).clip(CircleShape)
                            .background(if (empty) Brush.linearGradient(listOf(scheme.outlineVariant, scheme.outlineVariant)) else Brush.horizontalGradient(*c.run.toTypedArray())),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 2)
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
      }
      }
    }
}

/** The Spectrum tile that turns on the White preset ([LightStyle.WHITE]) rather than naming a split-light preset. */
private const val WHITE_KEY = "WHITE"

/** The White preset's name as it shows: white light on dark pages, black on light ones. */
fun whiteName(dark: Boolean) = if (dark) tr(R.string.white) else tr(R.string.black)

/** Your own light's colors in order: tap one to change (or remove) it, + to add one, and a way to flip the order. */
@Composable
private fun StopEditor(stops: List<Int>, onEdit: (Int) -> Unit, onReverse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(tr(R.string.your_own_light_s_colors_left), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stops.forEachIndexed { i, c ->
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(c)).border(1.dp, scheme.onSurface.copy(alpha = 0.18f), CircleShape)
                        .semantics { contentDescription = tr(R.string.color, i + 1, ColorMath.toHex(c)) }
                        .clickable { onEdit(i) },
                )
            }
            if (stops.size < 7) Box(
                Modifier.size(38.dp).clip(CircleShape).border(1.5.dp, scheme.onSurface.copy(alpha = 0.3f), CircleShape).clickable { onEdit(stops.size) },
                contentAlignment = Alignment.Center,
            ) { Icon(PrismIcons.Add, tr(R.string.add_a_color), Modifier.size(20.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.tap_a_color_to_change_or), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            PrismTextButton(onReverse) { Text(tr(R.string.reverse)) }
        }
    }
}

@Composable
private fun ResetColors() {
    val prefs = LocalContainer.current.settings
    var ask by remember { mutableStateOf(false) }
    PrismTextButton(onClick = { ask = true }, Modifier.fillMaxWidth()) {
        Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.reset_colors_to_defaults))
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(tr(R.string.reset_colors)) },
        text = { Text(tr(R.string.style_light_accent_background_contrast_and)) },
        confirmButton = { PrismTextButton(onClick = { prefs.resetColors(); ask = false }) { Text(tr(R.string.reset)) } },
        dismissButton = { PrismTextButton(onClick = { ask = false }) { Text(tr(R.string.cancel)) } },
    )
}

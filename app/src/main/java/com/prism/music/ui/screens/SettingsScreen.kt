package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.components.tabTint
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import android.os.Build
import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil3.compose.AsyncImage
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.prefs.CarLyricsStyle
import com.prism.music.data.prefs.CardSize
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.Corners
import com.prism.music.data.prefs.CoverSize
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.GlassKind
import com.prism.music.data.prefs.LibraryView
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.MiniProgress
import com.prism.music.data.prefs.NavBarStyle
import com.prism.music.data.prefs.NavLabels
import com.prism.music.data.prefs.NavTab
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.data.prefs.TitleSize
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.data.prefs.UiDensity
import com.prism.music.download.SMART_DOWNLOAD_SIZES
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.download.formatGb
import com.prism.music.download.smartSongEstimate
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.featurePane
import com.prism.music.ui.theme.AccentSwatches
import com.prism.music.ui.theme.isNeutralSeed
import com.prism.music.ui.theme.screensOnly
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The settings pages (1.7.6's reorganization, owner): the hub lists what Prism does (playback, lyrics, controls, your
 * music, devices); everything about how Prism looks lives under one entry, Customize Prism ([APPEARANCE]), whose page
 * lists the look pages. Keys stay as they were, so links to a page (the tour, the player's ⋯, prism:// links) still work.
 */
enum class SettingsSection(val key: String, val title: String, val icon: ImageVector) {
    APPEARANCE("appearance", tr(R.string.customize_prism), PrismIcons.Palette),
    SOUND("sound", tr(R.string.playback_sound), PrismIcons.Equalizer),
    LYRICS("lyrics", tr(R.string.lyrics_sing), PrismIcons.Lyrics),
    CONTROLS("controls", tr(R.string.controls_gestures), PrismIcons.Touch),
    LIBRARY("library", tr(R.string.recommendations_replay), PrismIcons.Library),
    DOWNLOADS("downloads", tr(R.string.downloads_storage_data), PrismIcons.DownloadCircle),
    DEVICES("devices", tr(R.string.devices_connections), PrismIcons.Car),
    LANGUAGE("language", tr(R.string.language), PrismIcons.Globe),
    ABOUT("about", tr(R.string.backup_about), PrismIcons.Info),
    /** Not on the hub: Devices & connections' own pages. */
    CONNECT("connect", tr(R.string.speakers_tvs_browser), PrismIcons.Cast),
    DISCORD("discord", tr(R.string.discord), PrismIcons.Groups),
    LASTFM("lastfm", "Last.fm", PrismIcons.Replay),
    /** 1.8.1: Spotify through the listener's own developer app. */
    SPOTIFY("spotify", tr(R.string.spotify), PrismIcons.Library),
    /** Not on the hub: opened from About. */
    LICENSES("licenses", tr(R.string.open_source_licenses), PrismIcons.Info),
    /** Not on the hub: Customize Prism's pages. */
    LOOKS("looks", tr(R.string.looks), PrismIcons.Looks),
    COLOR("color", tr(R.string.color_2), PrismIcons.Palette),
    TEXT("text", tr(R.string.text), PrismIcons.Signature),
    ICONS("icons", tr(R.string.icons_shapes), PrismIcons.Category),
    GLASS("glass", tr(R.string.glass_backgrounds), PrismIcons.Photo),
    INTERFACE("interface", tr(R.string.tabs_mini_player), PrismIcons.Customize),
    HOME("home", tr(R.string.home_library), PrismIcons.Home),
    PLAYER("player", tr(R.string.player), PrismIcons.PlayCircle),
    LYRICS_LOOK("lyrics_look", tr(R.string.lyrics), PrismIcons.Lyrics);

    fun summary(s: AppSettings, dark: Boolean = true): String = when (this) {
        APPEARANCE -> tr(R.string.looks_colors_fonts_the_player_and)
        INTERFACE -> listOf(tr(R.string.nav_bar_x, s.navStyle.label), com.prism.music.trp(R.plurals.n_tabs, s.navTabs.size), tr(R.string.mini_player_x, s.miniPlayerStyle.label)).joinToString(" · ")
        PLAYER -> listOfNotNull(tr(R.string.player_layout_x, s.playerLayout.label), s.playerBackground.label, if (s.canvasEnabled) tr(R.string.animated_covers) else null).joinToString(" · ")
        SOUND -> listOfNotNull(if (s.lossless) tr(R.string.lossless) else tr(R.string.audio_quality_x, s.audioQuality.label), if (s.mix) tr(R.string.crossfade) else null, if (s.normalize) tr(R.string.normalized) else null, if (s.spatial) tr(R.string.prism_spatial) else null, if (s.eq.enabled) "EQ ${com.prism.music.playback.EqPresets.label(s.eq.preset)}" else null).joinToString(" · ")
        LYRICS -> listOfNotNull(tr(R.string.first, s.lyricsSources.first().label), if (s.lyricsTranslate) tr(R.string.translated) else null, tr(R.string.sing)).joinToString(" · ")
        LYRICS_LOOK -> tr(R.string.style_2, s.lyricsStyle.label, s.lyricsPosition.label)
        CONTROLS -> tr(R.string.swipes_and_taps_on_the_player)
        DOWNLOADS -> listOfNotNull(tr(R.string.smart_downloads_2, formatGb(s.offlineGb)), if (s.downloadWifiOnly) tr(R.string.wi_fi_only) else tr(R.string.any_network), if (s.dataSaver) tr(R.string.data_saver) else null).joinToString(" · ")
        DEVICES -> tr(R.string.android_auto_speakers_tvs_discord_last)
        CONNECT -> listOfNotNull(tr(R.string.speakers_tvs), if (s.webRemote) tr(R.string.browser_remote_on) else null, if (s.handoffAvailable) tr(R.string.available_for_handoff) else null).joinToString(" · ")
        HOME -> tr(R.string.home_s_sections_and_top_library)
        LIBRARY -> listOfNotNull(tr(R.string.your_artists_new_releases), if (s.contentCountry.isNotBlank()) countryName(s.contentCountry) else null, tr(R.string.replay_2)).joinToString(" · ")
        DISCORD -> if (s.discordPresence) tr(R.string.showing_what_you_play_on_your) else tr(R.string.show_what_you_re_playing_on)
        LASTFM -> tr(R.string.scrobble_what_you_play_to_your)
        SPOTIFY -> tr(R.string.spot_summary)
        LANGUAGE -> java.util.Locale.getDefault().let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase() }
        ABOUT -> tr(R.string.prism_backup_updates_diagnostics, com.prism.music.BuildConfig.VERSION_NAME)
        LICENSES -> tr(R.string.gpl_3_0_or_later)
        COLOR -> listOfNotNull(
            // The light's name (White shows as Black in light mode).
            if (s.lightStyle == com.prism.music.data.prefs.LightStyle.WHITE) tr(R.string.light_2, whiteName(dark))
            else com.prism.music.ui.theme.SpectrumPresets.label(s.spectrumPreset).let { if (it == com.prism.music.ui.theme.SpectrumPresets.COVER_LABEL) tr(R.string.light_from_the_cover) else tr(R.string.light_2, it) },
            when (s.colorSource) { ColorSource.CUSTOM -> tr(R.string.accent_x, com.prism.music.ui.theme.ColorMath.toHex(s.accent)); ColorSource.ARTWORK -> tr(R.string.accent_from_the_cover); ColorSource.DYNAMIC -> tr(R.string.accent_from_the_wallpaper) },
        ).joinToString(" · ")
        TEXT -> listOfNotNull(tr(R.string.font_x, s.fontChoice.label), if (s.textScale != 1f) tr(R.string.size_2, (s.textScale * 100).roundToInt()) else null).joinToString(" · ")
        ICONS -> tr(R.string.icon_pack_and_weight_app_icon)
        GLASS -> listOfNotNull(when { s.liquidGlass -> tr(R.string.liquid_glass); s.frostedGlass -> tr(R.string.frosted_glass); else -> tr(R.string.solid_bars) }, if (s.backdrops.screensOnly.isEmpty()) null else tr(R.string.backgrounds), if (s.reduceMotion) tr(R.string.reduced_motion) else null).joinToString(" · ")
        LOOKS -> tr(R.string.save_switch_and_share_whole_looks)
    }

    /** The look settings on this page, for its "Reset this page" (empty: the page has none, or its own reset). */
    val lookKeys: List<String> get() = when (this) {
        TEXT -> listOf("font_choice", "lyrics_font", "text_scale", "type_weight", "type_spacing", "title_font")
        ICONS -> listOf(
            "corners", "corner_scale", "cover_shape_songs", "cover_shape_albums", "cover_shape_artists", "cover_shape_player",
            "button_shape", "play_outlined",
        )
        // 1.8.0: Reduce motion is on the Customize Prism page now (MotionGroup), so this page's reset leaves it.
        GLASS -> listOf("liquid_glass", "glass_kind", "frosted_glass", "frosted_pages", "backdrops", "backdrop_dim", "backdrop_motion", "glass_blur", "glass_frost", "glass_more")
        INTERFACE -> listOf(
            // 1.8.0: the tab you're on's tint is with the tab bar now (from Icons & shapes).
            "tab_icon_tint",
            "nav_style", "nav_labels", "nav_hide_on_scroll", "nav_tabs", "start_tab", "mini_player_style", "mini_skip_buttons",
            "mini_prev_button", "mini_play_button", "mini_progress", "mini_progress_color", "mini_fill_strength", "ui_density", "title_size", "card_size", "hide_status_bar", "hide_status_bar_player",
            "tab_names", "nav_bar_size", "nav_bar_fill", "mini_player_joined",
        )
        PLAYER -> listOf(
            "player_background", "player_backdrop_show", "transport_style", "scrubber_style", "cover_size", "player_art_corners",
            "full_bleed_artwork", "video_full_bleed", "art_shrink_on_pause", "show_playing_from", "player_shuffle_repeat", "hide_volume_bar",
            "show_output_device", "immersive_art", "immersive_lyrics", "immersive_controls", "player_handle", "player_nav_band", "play_ring",
            // 1.8.0: what the page now holds (What's on the player, the progress bar, the crossfade's marks from Sound).
            "player_hidden_parts", "player_buttons", "player_buttons_pill", "player_times", "scrubber_color", "scrubber_thickness",
            "scrubber_thumb", "cover_flight", "crossfade_mark", "blend_covers", "video_controls_hide", "mesh_speed", "mesh_intensity",
            "player_blur", "crossfade_shape", "player_gradient_depth",
            "cover_flight_back", "video_buttons_hide", "bleed_roomy", "queue_lengths", "queue_played_moves", "queue_tidy",
        )
        LYRICS_LOOK -> listOf(
            "lyrics_align", "lyrics_scale", "lyrics_blur", "lyrics_progress_line", "lyrics_style", "lyrics_blur_amount", "lyrics_spacing",
            "lyrics_others_alpha", "lyrics_sung_color", "lyrics_sung_custom", "lyrics_other_color", "lyrics_other_custom", "lyrics_weight",
            "lyrics_backing_size", "lyrics_shadow", "lyrics_uppercase", "lyrics_ahead", "lyrics_position", "lyrics_extra_size", "lyrics_extra_color",
            "lyrics_extra_italic", "lyrics_extra_place", "lyrics_background", "immersive_background", "lyrics_bg_color", "lyrics_bg_color2", "lyrics_everywhere",
            "lyrics_strip_align", "lyrics_strip_two_lines", "lyrics_gap_mark",
            // 1.8.0 lyrics look
            "lyrics_motion", "lyrics_word_by_word", "lyrics_card", "lyrics_halo", "lyrics_bold_lines",
        )
        else -> emptyList()
    }

    /**
     * The look pages: they say when Performance mode is pausing their effects. The live preview (three phones) is on
     * Customize Prism's own page only, scrolling with it: pinned on every look page it took a third of the screen.
     */
    val isLook: Boolean get() = this in setOf(APPEARANCE, LOOKS, COLOR, TEXT, ICONS, GLASS, INTERFACE, HOME, PLAYER, LYRICS_LOOK)

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

private val hubGroups = listOf(
    tr(R.string.make_it_yours) to listOf(SettingsSection.APPEARANCE),
    tr(R.string.listening) to listOf(SettingsSection.SOUND, SettingsSection.LYRICS, SettingsSection.CONTROLS),
    tr(R.string.your_music) to listOf(SettingsSection.LIBRARY, SettingsSection.DOWNLOADS),
    tr(R.string.devices) to listOf(SettingsSection.DEVICES),
    "" to listOf(SettingsSection.LANGUAGE, SettingsSection.ABOUT),
)

/** Customize Prism's pages, as its page lists them: the look as a whole, then each part of Prism. */
private val lookWholes = listOf(SettingsSection.LOOKS, SettingsSection.COLOR, SettingsSection.TEXT, SettingsSection.ICONS, SettingsSection.GLASS)
private val lookParts = listOf(SettingsSection.INTERFACE, SettingsSection.HOME, SettingsSection.PLAYER, SettingsSection.LYRICS_LOOK)

/**
 * Settings: the account, then the categories; [section] shows one category's page. On a wide window the hub lists
 * the categories down the side with the picked one's page beside it.
 */
@Composable
fun SettingsScreen(bottomPadding: Dp, section: SettingsSection? = null, find: String? = null) {
    // The switches show what the listener chose, also while Performance mode draws Prism lighter.
    StoredSettings { SettingsHub(bottomPadding, section, find) }
}

/** [content] with the settings as stored, not as Performance mode draws them (pages that change settings). */
@Composable
fun StoredSettings(content: @Composable () -> Unit) {
    val stored by LocalContainer.current.settings.stored.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalAppSettings provides stored, content = content)
}

@Composable
private fun SettingsHub(bottomPadding: Dp, section: SettingsSection?, find: String?) {
    if (section != null || !com.prism.music.ui.theme.LocalWindowLayout.current.listDetail) {
        SettingsPage(bottomPadding, section, find)
        return
    }
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE.key) }
    var openFind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(400.dp).fillMaxHeight()) {
            SettingsPage(bottomPadding, null, null, selected = SettingsSection.of(open)) { picked, group -> open = picked.key; openFind = group }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.runtime.key(open, openFind) {
                SettingsPage(bottomPadding, SettingsSection.of(open) ?: SettingsSection.APPEARANCE, openFind, showBack = false)
            }
        }
    }
}

/**
 * One settings page: the hub ([section] null) or a category. [onOpen], when given, opens a category beside the hub
 * (wide windows) instead of as its own page; [selected] marks the open one.
 */
@Composable
private fun SettingsPage(
    bottomPadding: Dp,
    section: SettingsSection?,
    find: String?,
    selected: SettingsSection? = null,
    showBack: Boolean = true,
    onOpen: ((SettingsSection, String?) -> Unit)? = null,
) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    // As a tab, the hub is a root page and has nothing to go back to.
    val isTab = (section == null && NavTab.SETTINGS in s.navTabs) || !showBack
    // Search (#73), on the hub: matching settings in place of the categories. A result on the hub itself (the account)
    // is found here; one on a page opens that page at its group.
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var foundHere by remember { mutableStateOf<String?>(null) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val results = remember(query) { if (query.isBlank()) emptyList() else SettingsIndex.search(query) }
    // The hub only: its search is ready before the first letter, without the main thread reading the whole index (#355).
    if (section == null) androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SettingsIndex.prepare() }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsFind provides (find ?: foundHere)) {
    SubPage(
        title = section?.title ?: tr(R.string.settings),
        bottomPadding = bottomPadding,
        horizontalPadding = 16.dp,
        spacing = 22.dp,
        showBack = !isTab,
        // Only Color keeps its preview pinned under the bar: there each choice changes it, and it's one slim row.
        pinned = if (section == SettingsSection.COLOR) ({ ColorPreviewBar(pinned = true) }) else null,
        pinnedKey = if (section == SettingsSection.COLOR) COLOR_PREVIEW_KEY else null,
    ) {
        // Pages of the effects Performance mode pauses say so first, with the way back.
        if (s.performanceMode && section?.isLook == true) item(key = "paused") { PerformancePaused() }
        when (section) {
            null -> {
                item(key = "find") {
                    com.prism.music.ui.components.SearchField(query, tr(R.string.search_settings), { query = it; foundHere = null })
                }
                if (query.isNotBlank()) {
                    item(key = "results") {
                        if (results.isEmpty()) Text(
                            tr(R.string.no_settings_match, query.trim()),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) else Group("") {
                            results.take(30).forEach { e ->
                                // Keyed: a result still there after the next letter keeps its row, so its text isn't laid out again.
                                androidx.compose.runtime.key(e.section, e.group, e.title) {
                                    // Hidden until another switch is on: the result says so, and opens at that switch's group.
                                    val hidden = e.needs?.takeIf { !it.on(s) }
                                    NavRow(
                                        e.section?.icon ?: PrismIcons.Settings, e.shown,
                                        listOf(e.section?.title ?: tr(R.string.settings), e.group).filter { it != e.title }.joinToString(" › "),
                                        hint = hidden?.hint,
                                    ) {
                                        // The keyboard goes with the search: left up, the page's first Back only closed it.
                                        keyboard?.hide()
                                        if (e.section == null) { query = ""; foundHere = e.group }
                                        else if (onOpen != null) onOpen(e.section, e.group)
                                        else nav.go(Routes.settings(e.section.key, e.group))
                                    }
                                }
                            }
                        }
                    }
                    return@SubPage
                }
                item { AccountCard() }
                item(key = "performance") { PerformanceGroup() }
                // Every section's icon sits on its own color of the split light, red at the top to violet at the foot.
                val hubCount = hubGroups.sumOf { it.second.size }
                var hubIndex = 0
                hubGroups.forEach { (title, sections) ->
                    val first = hubIndex
                    hubIndex += sections.size
                    item {
                        Group(title) {
                            sections.forEachIndexed { i, sec ->
                                val hue = com.prism.music.ui.theme.PrismTokens.spectrumAt((first + i) / (hubCount - 1).coerceAtLeast(1).toFloat())
                                NavRow(sec.icon, sec.title, sec.summary(s, com.prism.music.ui.theme.LocalIsDark.current), selected = sec == selected, hue = hue) {
                                    if (onOpen != null) onOpen(sec, null) else nav.go(Routes.settings(sec.key))
                                }
                            }
                        }
                    }
                }
            }
            SettingsSection.APPEARANCE -> appearance()
            SettingsSection.LOOKS -> looksPage()
            SettingsSection.COLOR -> colorPage()
            SettingsSection.TEXT -> item(key = "text") { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { TypeSettings(); ResetPage(section) } }
            SettingsSection.ICONS -> item(key = "icons") {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { IconSettings(); AppIconSettings(); ShapeSettings(); HomeWidgetsGroup(); ResetPage(section) }
            }
            SettingsSection.GLASS -> item(key = "glass") { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { GlassSettings(); ResetPage(section) } }
            SettingsSection.INTERFACE -> layoutAndNavigation()
            SettingsSection.PLAYER -> item { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { PlayerSettings(); ResetPage(section) } }
            SettingsSection.SOUND -> item { SoundPage() }
            SettingsSection.LYRICS -> item { LyricsSettings() }
            SettingsSection.LYRICS_LOOK -> item { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { LyricsLookPage(); ResetPage(section) } }
            SettingsSection.CONTROLS -> item { ControlsPage() }
            SettingsSection.DOWNLOADS -> item { DownloadSettings() }
            SettingsSection.DEVICES -> item { DevicesPage() }
            SettingsSection.CONNECT -> item { ConnectSettings() }
            SettingsSection.HOME -> item { HomeLibraryLook() }
            SettingsSection.LIBRARY -> item { RecommendationsPage() }
            SettingsSection.DISCORD -> item { DiscordGroup() }
            SettingsSection.LASTFM -> item { LastFmGroup() }
            SettingsSection.SPOTIFY -> item { SpotifyPage() }
            // 1.8.0: Region (the country Prism Music is asked as) beside the language, from Recommendations & Replay.
            SettingsSection.LANGUAGE -> item { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { LanguageSettings(); ContentCountrySetting() } }
            SettingsSection.ABOUT -> item {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                BackupGroup()
                Group(tr(R.string.about_3)) {
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    Item(tr(R.string.prism, com.prism.music.BuildConfig.VERSION_NAME), tr(R.string.unofficial_not_affiliated_with_google_or), onClick = { runCatching { uri.openUri(SOURCE_URL) } }) {
                        Icon(PrismIcons.OpenInNew, tr(R.string.open_on_github), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Item(tr(R.string.your_data_stays_here), tr(R.string.prism_has_no_server), info = tr(R.string.your_history_replay_and_settings_live), onClick = {})
                    CheckForUpdates()
                    Item(tr(R.string.take_the_tour), tr(R.string.a_minute_s_walk_through_prism), onClick = { c.tourRequests.tryEmit(Unit) }) { Chevron() }
                    Item(tr(R.string.open_source_licenses), tr(R.string.gpl_3_0_or_later_and), onClick = { nav.go(Routes.settings(SettingsSection.LICENSES.key)) }) { Chevron() }
                    // Debug builds only, so it's left out of Settings search (named title: SettingsIndexTest skips it).
                    if (com.prism.music.BuildConfig.DEBUG) Item(
                        title = tr(R.string.design_gallery), subtitle = tr(R.string.every_component_of_the_redesign_in), onClick = { nav.go(Routes.DESIGN_GALLERY) },
                    ) { Chevron() }
                }
                // 1.8.0: only for sending a bug report, so folded away.
                MoreOptions(listOf(tr(R.string.diagnostics))) { DiagnosticsGroup() }
                }
            }
            SettingsSection.LICENSES -> item { LicensesPage() }
        }
    }
    }
}

/** What Performance mode pauses, in plain words (its ⓘ and the pages' notice). */
internal val PERFORMANCE_PAUSES =
    tr(R.string.blur_and_frosted_glass_the_frost_all)

/**
 * Performance mode (1.7.6): one switch for slower phones, Prism drawn about as lightly as 1.6 was. Nothing is removed and
 * no choice is changed: the effects that cost the most on every frame pause until it's off again.
 */
@Composable
private fun PerformanceGroup() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    Group(tr(R.string.performance)) {
        Toggle(
            tr(R.string.performance_mode), if (s.performanceMode) tr(R.string.on_prism_is_drawn_lightly) else tr(R.string.for_slower_phones_lighter_fewer_effects),
            s.performanceMode, icon = PrismIcons.Speed,
            info = tr(R.string.pauses_every_feature_still_works_and, PERFORMANCE_PAUSES.lowercase()),
        ) { c.settings.setPerformanceMode(it) }
    }
}

/** At the top of a page whose effects Performance mode is pausing. */
@Composable
internal fun PerformancePaused() {
    val c = LocalContainer.current
    Group("") {
        Item(
            tr(R.string.performance_mode_is_on), tr(R.string.some_of_these_effects_are_paused),
            info = tr(R.string.paused_you_can_still_change_them, PERFORMANCE_PAUSES.lowercase()),
            icon = PrismIcons.Speed, onClick = { c.settings.setPerformanceMode(false) },
        ) { PrismTextButton(onClick = { c.settings.setPerformanceMode(false) }) { Text(tr(R.string.turn_off)) } }
    }
}

private fun countryName(code: String): String =
    java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() && it != code } ?: code

/**
 * The country YouTube Music is asked as. Where it isn't offered, picking one where it is (the US, say) brings
 * Home, charts and search back; songs then play as long as YouTube itself is available there.
 */
@Composable
private fun ContentCountrySetting() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    var picking by remember { mutableStateOf(false) }
    val phone = java.util.Locale.getDefault().country
    Group(tr(R.string.region)) {
        Item(
            tr(R.string.content_country),
            if (s.contentCountry.isBlank()) tr(R.string.automatic_3, if (phone.isBlank()) tr(R.string.united_states) else countryName(phone)) else countryName(s.contentCountry),
            info = tr(R.string.home_charts_and_search_follow_this),
            onClick = { picking = true },
        ) { Chevron() }
    }
    if (picking) {
        val countries = remember {
            java.util.Locale.getISOCountries().map { it to countryName(it) }.sortedBy { it.second }
        }
        val pick: (String) -> Unit = { code -> c.settings.setContentCountry(code); picking = false }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(tr(R.string.content_country)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(420.dp)) {
                    item { CountryRow(tr(R.string.automatic_follow_the_phone), s.contentCountry.isBlank()) { pick("") } }
                    item { CountryRow(tr(R.string.united_states), s.contentCountry == "US") { pick("US") } }
                    items(countries.size) { i ->
                        val (code, name) = countries[i]
                        CountryRow(name, s.contentCountry == code) { pick(code) }
                    }
                }
            },
            confirmButton = { PrismTextButton(onClick = { picking = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CountryRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(PrismIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Playback diagnostics: a log of what the audio did (the AudioTrack, underruns, devices, Prism's own processing and
 * background work), kept on the phone, for chasing stutters. Shared only when the listener shares it.
 */
@Composable
private fun DiagnosticsGroup() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Group(tr(R.string.diagnostics)) {
        Toggle(
            tr(R.string.playback_diagnostics), tr(R.string.a_log_for_tracking_down_stutters), s.audioDiagnostics,
            info = tr(R.string.keeps_a_log_on_this_phone),
        ) { c.settings.setAudioDiagnostics(it) }
        fun share() {
            scope.launch {
                val file = withContext(Dispatchers.IO) { com.prism.music.playback.AudioDiagnostics.export(context, com.prism.music.playback.AudioDiagnostics.about(c)) }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, tr(R.string.prism_playback_diagnostics))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri(null, uri)
                context.startActivity(android.content.Intent.createChooser(send, tr(R.string.share_diagnostics)))
            }
        }
        // A crash kept on the phone (CrashLog): there to share whether or not diagnostics are on.
        var crashAt by remember { mutableStateOf(com.prism.music.CrashLog.lastAt(context)) }
        crashAt?.let { at ->
            Item(
                tr(R.string.last_crash), tr(R.string.tap_to_share_its_details, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))),
                onClick = { share() },
                info = tr(R.string.prism_closed_unexpectedly_what_went_wrong),
            ) { Chevron() }
            Item(tr(R.string.forget_crashes), null, onClick = { com.prism.music.CrashLog.clear(context); crashAt = null })
        }
        if (s.audioDiagnostics) {
            Item(tr(R.string.share_diagnostics), tr(R.string.the_log_and_audio_settings_as), onClick = { share() })
            Item(tr(R.string.clear_diagnostics), tr(R.string.starts_the_log_again), onClick = {
                scope.launch(Dispatchers.IO) { com.prism.music.playback.AudioDiagnostics.clear { com.prism.music.playback.AudioDiagnostics.about(c) } }
                android.widget.Toast.makeText(context, tr(R.string.diagnostics_cleared), android.widget.Toast.LENGTH_SHORT).show()
            })
        }
    }
}

/** Prism also checks on its own from Home; tapping this asks GitHub right now. */
@Composable
private fun CheckForUpdates() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Item(
        tr(R.string.check_for_updates),
        when {
            checking -> tr(R.string.asking_github)
            result != null -> result
            else -> tr(R.string.prism_also_checks_on_its_own)
        },
        onClick = {
            if (!checking) scope.launch {
                checking = true
                result = runCatching { c.updates.checkNow() }.fold(
                    { v -> if (v != null) tr(R.string.prism_is_out_tap_below_to, v) else tr(R.string.you_re_up_to_date) },
                    { tr(R.string.couldn_t_reach_github_try_again) },
                )
                checking = false
            }
        },
    ) {
        if (checking) com.prism.music.ui.components.PrismSpinner(Modifier.size(20.dp), stroke = 2.dp)
    }
    // The update itself, installed from here (#201): the same card as Home's, which needs Home to be a tab.
    UpdateBanner(inSettings = true)
}

@Composable
internal fun Chevron() = Icon(PrismIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------------------- Account

@Composable
private fun AccountCard() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    // A pane of frosted glass on a frosted page, the accent's gradient otherwise.
    val frosted = com.prism.music.ui.components.LocalFrosted.current
    val ink = com.prism.music.ui.components.featureInk()
    var switching by remember { mutableStateOf(false) }
    if (switching) AccountSwitcher { switching = false }
    val profile by c.account.profile.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    fun signOut() {
        c.settings.logout()
        // The account's library kept for offline goes with it (copies in Prism stay: they're the Prism Account's).
        c.library.forgetAccount()
        CookieManager.getInstance().removeAllCookies(null)
    }
    // With a Prism Account, signing out can keep the YouTube Music library in Prism first.
    if (confirmSignOut) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(tr(R.string.sign_out_of_youtube_music)) },
        text = { Text(tr(R.string.keep_a_copy_of_its_playlists)) },
        confirmButton = {
            PrismTextButton({
                confirmSignOut = false
                c.scope.launch {
                    val r = runCatching { c.account.bringOver() }.getOrNull()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        signOut()
                        android.widget.Toast.makeText(context, if (r != null) tr(R.string.signed_out_playlists_and_albums_kept, trp(R.plurals.n_playlists, r.playlists), trp(R.plurals.n_albums, r.albums)) else tr(R.string.signed_out), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(tr(R.string.keep_a_copy)) }
        },
        dismissButton = { PrismTextButton({ confirmSignOut = false; signOut() }) { Text(tr(R.string.just_sign_out)) } },
    )
    Column(Modifier.fillMaxWidth().featurePane(ui.card)) {
    // The Prism Account: who the listener is in Prism, on this phone.
    Row(
        Modifier.fillMaxWidth().clickable { nav.go(Routes.ACCOUNT) }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile != null || s.isLoggedIn) com.prism.music.ui.screens.ProfilePicture(Modifier.size(56.dp))
        else Box(Modifier.size(56.dp).clip(CircleShape).background(if (frosted) com.prism.music.ui.components.frostFill(1.6f) else scheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Add, null, tint = if (frosted) scheme.primary else ink)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile?.name ?: tr(R.string.make_a_prism_account),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (profile != null) tr(R.string.prism_account_on_this_phone) else tr(R.string.your_name_and_picture_kept_on),
                style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(PrismIcons.ChevronRight, null, tint = ink)
    }
    // YouTube Music: optional, for the account's library, history and recommendations.
    Row(
        Modifier.fillMaxWidth()
            .then(if (s.isLoggedIn) Modifier else Modifier.clickable { nav.go(Routes.LOGIN) })
            .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (frosted) com.prism.music.ui.components.frostFill(1.6f) else scheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (s.isLoggedIn && s.accountAvatar.isNotBlank()) AsyncImage(s.accountAvatar, null, Modifier.fillMaxSize())
            else Icon(PrismIcons.Login, null, Modifier.size(18.dp), tint = if (frosted) scheme.primary else ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (s.isLoggedIn) tr(R.string.youtube_music, s.accountName.ifBlank { tr(R.string.signed_in) }) else tr(R.string.sign_in_to_youtube_music),
                style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s.isLoggedIn) s.accountEmail.ifBlank { tr(R.string.signed_in_2) } else tr(R.string.optional_your_library_history_and_recommendation),
                style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.isLoggedIn) PrismOutlinedButton(onClick = { if (profile != null) confirmSignOut = true else signOut() }) {
            Icon(PrismIcons.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.sign_out))
        }
        else Icon(PrismIcons.ChevronRight, null, tint = ink)
    }
    // Another channel of the same Google account (brand accounts), or another Google account altogether.
    if (s.isLoggedIn) Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
        PrismTextButton(onClick = { switching = true }) {
            Icon(PrismIcons.Person, null, Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(6.dp)); Text(tr(R.string.switch_account), color = ink)
        }
        PrismTextButton(onClick = { nav.go(Routes.LOGIN_ADD) }) {
            Icon(PrismIcons.Add, null, Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(6.dp)); Text(tr(R.string.add_a_google_account), color = ink)
        }
    }
    // #372, #373, #377: YouTube's own answers say it isn't taking Prism's requests as this account.
    val accountSeen by c.innerTube.seen.collectAsState()
    if (s.isLoggedIn && accountSeen?.signedIn == false) Text(
        tr(R.string.youtube_no_longer_takes_this_sign_in), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = ink,
    )
    }
}

/**
 * Discord presence: "Listening to Prism" with the song on the listener's Discord profile. Turning it on the first time
 * signs in to Discord (on Discord's own page); the session stays on this phone.
 */
@Composable
private fun DiscordGroup() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val state by c.discord.state.collectAsState()
    val user by c.discord.userName.collectAsState()
    Group(tr(R.string.discord)) {
        Toggle(
            tr(R.string.show_what_you_re_playing),
            when {
                !c.discord.signedIn -> tr(R.string.listening_to_prism_on_your_profile)
                state == com.prism.music.playback.DiscordPresence.State.CONNECTED -> (user?.let { tr(R.string.on_as, it) } ?: tr(R.string.on_2))
                state == com.prism.music.playback.DiscordPresence.State.CONNECTING -> tr(R.string.connecting_to_discord)
                else -> (user?.let { tr(R.string.on_discord_as, it) } ?: tr(R.string.on_discord))
            },
            s.discordPresence && c.discord.signedIn,
            info = tr(R.string.your_profile_shows_the_song_and),
        ) { on ->
            if (on && !c.discord.signedIn) nav.go(Routes.DISCORD_LOGIN) else c.settings.setDiscordPresence(on)
        }
        if (c.discord.signedIn) Item(tr(R.string.sign_out_of_discord), tr(R.string.forgets_the_sign_in_on_this), onClick = {
            c.settings.setDiscordPresence(false)
            c.discord.signOut()
        })
        Item(
            tr(R.string.about_this), tr(R.string.a_small_risk_to_your_discord),
            info = tr(R.string.discord_has_no_way_for_android),
            onClick = {},
        )
    }
}

/**
 * Every channel Prism can use on this sign-in: each Google account's own and its brand accounts (one email can have
 * several YouTube channels). Picking one switches the library, likes and recommendations to it.
 */
@Composable
private fun AccountSwitcher(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts by androidx.compose.runtime.produceState<List<com.prism.music.data.model.YtAccount>?>(null) {
        value = runCatching { c.ytm.accounts() }.getOrDefault(emptyList())
    }
    com.prism.music.ui.components.ActionSheet(s.accountAvatar.ifBlank { null }, onDismiss) { close ->
        Text(
            tr(R.string.accounts_and_channels), Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
        )
        val list = accounts
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { com.prism.music.ui.components.PrismSpinner(Modifier.size(30.dp)) }
            list.isEmpty() -> Text(
                tr(R.string.youtube_didn_t_list_any_other),
                Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            )
            else -> list.groupBy { it.email }.forEach { (email, channels) ->
                if (email != null) Text(
                    email, Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                channels.forEach { a ->
                    val current = a.authUser == s.authUser && a.dataSyncId.substringBefore("||") == s.dataSyncId.substringBefore("||")
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !current) {
                            close {
                                c.scope.launch {
                                    c.library.switchAccount(a)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, tr(R.string.switched_to, a.name), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(com.prism.music.ui.components.glassFill(2f)), contentAlignment = Alignment.Center) {
                            if (a.avatar != null) AsyncImage(a.avatar, null, Modifier.fillMaxSize()) else Icon(PrismIcons.Person, null)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            a.byline?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                        if (current) Icon(PrismIcons.Check, tr(R.string.in_use), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------- Appearance

private fun androidx.compose.foundation.lazy.LazyListScope.appearance() {
    // The live preview, once, at the top (it scrolls with the page).
    item(key = LOOK_PREVIEW_KEY) { LookStrip() }
    item(key = "appearance") {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box {
                val s = LocalAppSettings.current
                val prefs = LocalContainer.current.settings
                Group(tr(R.string.theme)) {
                    Chips(ThemeMode.entries, s.themeMode, { it.label }) { prefs.setThemeMode(it) }
                    Toggle(
                        tr(R.string.pure_black), tr(R.string.amoled_friendly_dark_theme), s.pureBlack,
                        info = tr(R.string.in_dark_mode_pages_sit_on),
                    ) { prefs.setPureBlack(it) }
                }
            }
            // Every look page is here and only here (1.7.6): the look as a whole, then each part of Prism.
            Box {
                val s = LocalAppSettings.current
                val nav = LocalNavigator.current
                val dark = com.prism.music.ui.theme.LocalIsDark.current
                Group(tr(R.string.the_whole_look)) {
                    lookWholes.forEach { sec -> NavRow(sec.icon, sec.title, sec.summary(s, dark)) { nav.go(Routes.settings(sec.key)) } }
                }
            }
            Box {
                val s = LocalAppSettings.current
                val nav = LocalNavigator.current
                val dark = com.prism.music.ui.theme.LocalIsDark.current
                Group(tr(R.string.each_part)) {
                    lookParts.forEach { sec -> NavRow(sec.icon, sec.title, sec.summary(s, dark)) { nav.go(Routes.settings(sec.key)) } }
                }
            }
            Box { MotionGroup() }
            // "Customize this" (1.7.3): tap the mini player, tab bar, player or a Home section to change it there.
            Box {
                val c = LocalContainer.current
                val on by c.customizing.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current
                Group(tr(R.string.customize_this)) {
                    Toggle(
                        tr(R.string.customize_by_tapping), tr(R.string.tap_the_mini_player_tab_bar), on,
                        info = tr(R.string.while_it_s_on_those_parts),
                    ) {
                        c.customizing.value = it
                        if (it) android.widget.Toast.makeText(context, tr(R.string.tap_anything_outlined_to_change_it), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ResetLook()
        }
    }
}

/** Appearance → Glass & backgrounds (1.7.2: its own page). */
@Composable
private fun GlassSettings() {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box {
                val s = LocalAppSettings.current
                val nav = LocalNavigator.current
                Group(tr(R.string.backgrounds)) {
                    val screens = s.backdrops.screensOnly
                    val count = screens.size
                    Item(
                        tr(R.string.screen_backgrounds),
                        if (count == 0) tr(R.string.designs_or_your_own_photo)
                        else screens.values.map { com.prism.music.ui.theme.Backdrop.parse(it).first.label }.distinct().joinToString(" · ") + com.prism.music.trp(R.plurals.on_n_screens, count),
                        onClick = { nav.go(Routes.BACKGROUNDS) },
                    ) { Chevron() }
                }
            }
            Box {
                val s = LocalAppSettings.current
                val prefs = LocalContainer.current.settings
                Group(tr(R.string.glass)) {
                    // 1.8.0: one choice over liquidGlass + glassKind + frostedGlass (LookChoices).
                    Label(tr(R.string.glass), info = tr(R.string.glass_kinds_what_differs))
                    // #252 (1.8.2): each as a real piece of that glass, over the cover playing.
                    val choice = LookChoices.glass(s)
                    ChoiceCards(GlassChoice.entries, choice, { it.label }, { null }, onSelect = { LookChoices.setGlass(prefs, it) }) { GlassSample(it) }
                    val look = s.view.glass
                    if (choice != GlassChoice.OFF) {
                        // Both need Android 12's blur: below it the bars are solid, so there's nothing for them to change.
                        if (android.os.Build.VERSION.SDK_INT < 31) Note(tr(R.string.glass_needs_android_12))
                        else {
                            // Not on Clear: it has next to no blur to set.
                            if (choice != GlassChoice.LIQUID_CLEAR) SliderSetting(
                                tr(R.string.glass_blur), look.blur, com.prism.music.data.prefs.GlassLook.BLUR_RANGE, { "${(com.prism.music.ui.theme.glassSnap(it) * 100).roundToInt()}%" },
                                info = tr(R.string.glass_blur_info),
                            ) { prefs.setGlassBlur(com.prism.music.ui.theme.glassSnap(it)) }
                            SliderSetting(
                                tr(R.string.glass_frost), look.frost, com.prism.music.data.prefs.GlassLook.FROST_RANGE, { "${(com.prism.music.ui.theme.glassSnap(it) * 100).roundToInt()}%" },
                                info = tr(R.string.glass_frost_info),
                            ) { prefs.setGlassFrost(com.prism.music.ui.theme.glassSnap(it)) }
                        }
                    }
                    // #326: only what costs next to nothing, for now.
                    Toggle(
                        tr(R.string.glass_on_more_of_the_screen), tr(R.string.glass_on_more_of_the_screen_detail), look.more,
                        info = tr(R.string.glass_on_more_of_the_screen_info),
                    ) { prefs.setGlassMore(it) }
                    Toggle(tr(R.string.frosted_pages), tr(R.string.pages_frosted_with_their_picture), s.frostedPages, info = tr(R.string.artist_album_and_playlist_pages_sit)) { prefs.setFrostedPages(it) }
                }
            }
            // Reduce motion and how screens change are on the Customize Prism page itself (1.8.0: MotionGroup).
    }
}

/**
 * Motion (1.8.0, on the Customize Prism page: it changes the whole app, and Reduce motion is an accessibility choice):
 * Reduce motion, and how screens change (fades + #208's slide as one choice).
 */
@Composable
private fun MotionGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.motion)) {
        Toggle(tr(R.string.reduce_motion), tr(R.string.calmer_quicker_animations), s.reduceMotion, info = tr(R.string.no_drifting_backgrounds_no_artwork_bounce)) { prefs.setReduceMotion(it) }
        Label(tr(R.string.changing_screens), info = tr(R.string.changing_screens_info))
        Chips(ScreenChange.entries, LookChoices.screenChange(s), { it.label }) { LookChoices.setScreenChange(prefs, it) }
    }
}

@Composable
private fun TypeSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    // A font file from the phone (#12), copied into Prism: for the whole app, or only for the lyrics.
    fun importer(slot: AppFonts.FontSlot, done: (String) -> Unit) = { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AppFonts.import(context, uri, slot) } }
            r.onSuccess { error = null; done(it) }.onFailure { error = it.message ?: tr(R.string.that_font_couldn_t_be_used) }
        }
        Unit
    }
    val fontTypes = arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")
    val pickApp = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        importer(AppFonts.FontSlot.APP) { name -> prefs.setAppFont(name); prefs.setFontChoice(FontChoice.CUSTOM) })
    val pickLyrics = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        importer(AppFonts.FontSlot.LYRICS) { name -> prefs.setLyricsFontFile(name); prefs.setLyricsFont(FontChoice.CUSTOM.name) })
    val hasAppFont = remember(s.fontStamp) { AppFonts.customFile(AppFonts.FontSlot.APP) != null }
    val hasLyricsFont = remember(s.fontStamp) { AppFonts.customFile(AppFonts.FontSlot.LYRICS) != null }
    Group(tr(R.string.text)) {
        Label(tr(R.string.font))
        FontPicker(s.fontChoice, customName = s.appFontName.takeIf { hasAppFont }, stamp = s.fontStamp) { f ->
            if (f == FontChoice.CUSTOM && !hasAppFont) pickApp.launch(fontTypes) else prefs.setFontChoice(f)
        }
        Item(
            if (hasAppFont) tr(R.string.your_font, s.appFontName) else tr(R.string.use_a_font_from_your_phone),
            if (hasAppFont) tr(R.string.tap_to_pick_a_different_ttf) else tr(R.string.pick_a_ttf_or_otf_file),
            onClick = { pickApp.launch(fontTypes) },
        ) { Chevron() }
        // #318 (1.8.1): a font installed on the phone, without a file to pick (Android 10 and later list them).
        var installedFor by remember { mutableStateOf<AppFonts.FontSlot?>(null) }
        installedFor?.let { slot ->
            InstalledFontsSheet(slot, onPicked = { name ->
                if (slot == AppFonts.FontSlot.APP) { prefs.setAppFont(name); prefs.setFontChoice(FontChoice.CUSTOM) }
                else { prefs.setLyricsFontFile(name); prefs.setLyricsFont(FontChoice.CUSTOM.name) }
            }) { installedFor = null }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) Item(
            tr(R.string.installed_fonts), tr(R.string.installed_fonts_detail), info = tr(R.string.installed_fonts_info),
            onClick = { installedFor = AppFonts.FontSlot.APP },
        ) { Chevron() }
        if (hasAppFont) Item(tr(R.string.remove_your_font), tr(R.string.back_to_a_built_in_font), onClick = {
            AppFonts.remove(AppFonts.FontSlot.APP)
            if (s.fontChoice == FontChoice.CUSTOM) prefs.setFontChoice(FontChoice.PRISM)
            prefs.setAppFont("")
        })
        Label(tr(R.string.lyrics_font))
        // "App font" keeps each lyric view's own face (the app's); the rest are the same fonts as above, or a file just for lyrics.
        val lyricOptions = listOf("") + FontChoice.entries.filter { it != FontChoice.CUSTOM && AppFonts.available(it) }.map { it.name } + FontChoice.CUSTOM.name
        Chips(lyricOptions, s.lyricsFont, { v ->
            when (v) {
                "" -> tr(R.string.app_font)
                FontChoice.CUSTOM.name -> if (hasLyricsFont) s.lyricsFontName.ifBlank { tr(R.string.your_font_2) } else tr(R.string.from_a_file)
                else -> FontChoice.valueOf(v).label
            }
        }) { v -> if (v == FontChoice.CUSTOM.name && !hasLyricsFont) pickLyrics.launch(fontTypes) else prefs.setLyricsFont(v) }
        if (android.os.Build.VERSION.SDK_INT >= 29) Item(
            tr(R.string.installed_fonts_for_lyrics), tr(R.string.installed_fonts_detail),
            onClick = { installedFor = AppFonts.FontSlot.LYRICS },
        ) { Chevron() }
        if (hasLyricsFont) {
            Item(tr(R.string.lyrics_font_file_2, s.lyricsFontName), tr(R.string.tap_to_pick_a_different_file), onClick = { pickLyrics.launch(fontTypes) }) { Chevron() }
            Item(tr(R.string.remove_the_lyrics_font_file), null, onClick = {
                AppFonts.remove(AppFonts.FontSlot.LYRICS)
                if (s.lyricsFont == FontChoice.CUSTOM.name) prefs.setLyricsFont("")
                prefs.setLyricsFontFile("")
            })
        }
        error?.let { Note(it) }
        SliderSetting(tr(R.string.text_size), s.textScale, 0.85f..1.25f, { "${(it * 100).roundToInt()}%" }, steps = 7, info = tr(R.string.on_top_of_your_phone_s)) { prefs.setTextScale(it) }
    }
    TypeFeelGroup()
}

/** Weight, letter spacing and the titles' own font (1.7.7), each shown on a page title and a line of text. */
@Composable
private fun TypeFeelGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val scheme = MaterialTheme.colorScheme
    @Composable
    fun Sample(t: com.prism.music.data.prefs.TypeLook) {
        val type = remember(s.fontChoice, s.fontStamp, t) { com.prism.music.ui.theme.appTypography(s.copy(type = t)) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(tr(R.string.songs), style = type.headlineSmall, color = scheme.onSurface, maxLines = 1)
            Text(tr(R.string.played_today), style = type.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
    // Classic type (1.7.8, #155): Prism 1.6's bold headings, or today's light ones.
    Group(tr(R.string.type), info = tr(R.string.prism_1_7_writes_big_titles)) {
        ChoiceCards(listOf(false, true), s.type.classic, title = { if (it) tr(R.string.classic) else tr(R.string.app_name) }, detail = { if (it) tr(R.string.bold_headings_as_in_1_6) else tr(R.string.light_titles_as_now) }, onSelect = { prefs.setTypeClassic(it) }) {
            Sample(s.type.copy(classic = it))
        }
    }
    Group(tr(R.string.weight_and_spacing), info = tr(R.string.how_heavy_all_of_prism_s)) {
        ChoiceCards(com.prism.music.data.prefs.TypeWeight.entries, s.type.weight, title = { it.label }, detail = { null }, columns = 3, onSelect = { prefs.setTypeWeight(it) }) {
            Sample(s.type.copy(weight = it))
        }
        Label(tr(R.string.letter_spacing))
        ChoiceCards(com.prism.music.data.prefs.TypeSpacing.entries, s.type.spacing, title = { it.label }, detail = { null }, columns = 3, onSelect = { prefs.setTypeSpacing(it) }) {
            Sample(s.type.copy(spacing = it))
        }
    }
    Group(tr(R.string.titles), info = tr(R.string.page_section_and_album_titles_can)) {
        val options: List<FontChoice?> = listOf(null) + FontChoice.entries.filter { it != FontChoice.CUSTOM && it != s.fontChoice }
        ChoiceCards(options, s.type.titleFont, title = { it?.label ?: tr(R.string.same_as_the_text) }, detail = { it?.face }, columns = 2, onSelect = { prefs.setTitleFont(it) }) {
            Sample(s.type.copy(titleFont = it))
        }
    }
}

/**
 * Icon weight and ends (#19). Prism's icons are built as it starts, so a change is saved for the next start and a
 * Restart button appears; [com.prism.music.ui.theme.IconStyle] holds what's drawn now.
 */
@Composable
private fun IconSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawn = com.prism.music.ui.theme.IconStyle.weight to com.prism.music.ui.theme.IconStyle.rounded
    var saved by remember { mutableStateOf(com.prism.music.ui.theme.IconStyle.saved(context)) }
    val looks = LocalContainer.current.looks
    fun save(v: Pair<com.prism.music.ui.theme.IconWeight, Boolean>) {
        saved = v
        com.prism.music.ui.theme.IconStyle.save(context, v.first, v.second)
        looks.iconsChanged()
    }
    var pack by remember { mutableStateOf(com.prism.music.ui.theme.IconStyle.savedPack(context)) }
    var likeStar by remember { mutableStateOf(com.prism.music.ui.theme.IconStyle.savedLikeStar(context)) }
    var fill by remember { mutableStateOf(com.prism.music.ui.theme.IconStyle.savedFill(context)) }
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.icons)) {
        // Fill (1.7.7): each style drawn on four of Prism's icons, as they'd look.
        ChoiceCards(
            com.prism.music.ui.theme.IconFill.entries, fill, title = { it.label }, detail = { it.detail }, columns = 3,
            onSelect = {
                fill = it
                com.prism.music.ui.theme.IconStyle.saveFill(context, it)
                looks.iconsChanged()
            },
        ) { f ->
            // Three: a fourth was squeezed to a dot in a third of a phone's width.
            // The drawings' own names (design/icons), never words on the screen: translated, "Inicio" had no drawing and
            // the page closed Prism in every language but English (#255).
            val icons = remember(f) { listOf("Home", "Library", "Heart").map { com.prism.music.ui.theme.PrismIcons.preview(it, f) } }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                icons.forEach { Icon(it, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
        // "The tab you're on" is with the tab bar now (1.8.0: NavBarGroup).
        // Packs (1.7.7 adds Facet and Dots): each card draws the same four icons in its pack.
        Label(tr(R.string.pack))
        ChoiceCards(
            com.prism.music.ui.theme.IconPack.entries, pack, title = { it.label }, detail = { it.detail },
            onSelect = {
                pack = it
                com.prism.music.ui.theme.IconStyle.savePack(context, it)
                looks.iconsChanged()
            },
        ) { p ->
            // Cupertino is drawn by hand for its own icons; the others come from the drawings.
            val icons = remember(p) {
                if (p == com.prism.music.ui.theme.IconPack.CUPERTINO) com.prism.music.ui.theme.PrismIcons.cupertinoPreview()
                // The drawings' own names, never translated words (#255).
                else listOf("Home", "Search", "Heart", "Play").map { com.prism.music.ui.theme.PrismIcons.preview(it, com.prism.music.ui.theme.IconFill.OUTLINE, p) }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                icons.forEach { Icon(it, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
        Label(tr(R.string.weight))
        Chips(com.prism.music.ui.theme.IconWeight.entries, saved.first, { it.label }) { save(it to saved.second) }
        Toggle(tr(R.string.rounded), tr(R.string.round_ends_and_softer_corners), saved.second) { save(saved.first to it) }
        Toggle(tr(R.string.likes_as_stars), tr(R.string.a_star_instead_of_a_heart), likeStar) {
            likeStar = it
            com.prism.music.ui.theme.IconStyle.saveLikeStar(context, it)
            looks.iconsChanged()
        }
        if (saved != drawn || pack != com.prism.music.ui.theme.IconStyle.pack || likeStar != com.prism.music.ui.theme.IconStyle.likeStar ||
            fill != com.prism.music.ui.theme.IconStyle.fill) {
            Item(tr(R.string.restart_prism_to_see_them), tr(R.string.playback_stops_for_a_moment), onClick = {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    context.startActivity(android.content.Intent.makeRestartActivityTask(launch.component).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    Runtime.getRuntime().exit(0)
                }
            }) { Chevron() }
        }
    }
}

/** The launcher icon (#20), each shown as its own two layers in a rounded square. */
@Composable
private fun AppIconSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var current by remember { mutableStateOf(com.prism.music.ui.theme.AppIcons.current(context)) }
    Group(tr(R.string.app_icon), info = tr(R.string.your_launcher_may_take_a_moment)) {
        // An even grid across the page (#194: fixed 72 dp cells left an empty column at the side with larger text, and
        // cut "Spectrum" short): as many columns as fit, names made smaller rather than cut.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            val cell = 76.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = ((maxWidth + 8.dp) / (cell + 8.dp)).toInt().coerceIn(3, 6)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                com.prism.music.ui.theme.AppIcon.entries.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { icon ->
                            val on = icon == current
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable {
                                    if (!on) { com.prism.music.ui.theme.AppIcons.set(context, icon); current = icon }
                                }.padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.size(60.dp).then(if (on) Modifier.border(2.dp, com.prism.music.ui.theme.PrismTokens.spectrum, RoundedCornerShape(18.dp)) else Modifier)
                                        .padding(if (on) 4.dp else 0.dp).clip(RoundedCornerShape(15.dp)),
                                ) {
                                    // An adaptive icon's layers are 108 units with the visible 72 in the middle: draw them 1.5× and crop.
                                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(icon.background), null, Modifier.fillMaxSize().scale(1.5f))
                                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(icon.foreground), null, Modifier.fillMaxSize().scale(1.5f))
                                }
                                Spacer(Modifier.height(4.dp))
                                WholeWords(icon.label, MaterialTheme.typography.labelMedium, if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        // The last row keeps the others' column widths.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Each font's name, set in that font, with the typeface it is. */
/**
 * [text] on up to [maxLines] lines in [style], made smaller a step at a time (down to about half) until every line ends
 * between words or after a hyphen: a word too wide for the room is otherwise split mid-word. Judged from its own layout,
 * not measured ahead (BoxWithConstraints can't sit in the font cards' row, which is sized by its contents).
 */
@Composable
internal fun WholeWords(text: String, style: TextStyle, color: Color, maxLines: Int = 2, modifier: Modifier = Modifier) {
    var fitted by remember(text, style) { mutableStateOf(style) }
    Text(
        text, modifier, style = fitted, color = color, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { l ->
            val whole = !l.hasVisualOverflow && (0 until l.lineCount - 1).all { i -> text.getOrNull(l.getLineEnd(i) - 1).let { it == ' ' || it == '-' } }
            if (!whole && fitted.fontSize.value > style.fontSize.value * 0.5f) fitted = fitted.copy(fontSize = fitted.fontSize * 0.9f)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontPicker(current: FontChoice, customName: String?, stamp: Long, onPick: (FontChoice) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    // "Your font" is always pickable: without a file yet, picking it asks for one.
    val missing = remember(stamp) { FontChoice.entries.filterNot { it == FontChoice.CUSTOM || AppFonts.available(it) } }
    // An even grid across the page (#117: fixed-width cards left a gap at the side and came out at different heights
    // with larger text): as many columns as fit at ~100 dp, every card in a row as tall as the tallest.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Wider with larger text, so names like "Geometric" still fit.
        val card = 100.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth + 8.dp) / (card + 8.dp)).toInt().coerceIn(2, 5)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FontChoice.entries.chunked(columns).forEach { rowFonts ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowFonts.forEach { f ->
                        val on = f == current
                        val usable = f !in missing
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(ui.tile)
                                .background(com.prism.music.ui.components.glassFill(if (on) 2.6f else 1f))
                                .then(if (on) Modifier.border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, ui.tile) else Modifier)
                                .clickable(enabled = usable) { onPick(f) }
                                .then(if (usable) Modifier else Modifier.alpha(0.38f))
                                .padding(10.dp),
                        ) {
                            Text(tr(R.string.aa), style = TextStyle(fontFamily = AppFonts.family(f), fontWeight = FontWeight.Medium, fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = scheme.onSurface)
                            // "Dyslexia-friendly" in OpenDyslexic's wide letters: "friendly" alone is wider than the card,
                            // so Android split the word. The name gets smaller until no word is split.
                            WholeWords(
                                if (f == FontChoice.CUSTOM) customName ?: tr(R.string.your_font_3) else f.label,
                                MaterialTheme.typography.labelMedium.copy(fontFamily = AppFonts.family(f)),
                                if (on) scheme.onSurface else scheme.onSurfaceVariant,
                            )
                            // The typeface's own name, quietly, so it can be looked up (and matched to a file for Your font).
                            f.face?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    repeat(columns - rowFonts.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ShapeSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val scheme = MaterialTheme.colorScheme
    Group(tr(R.string.shape)) {
        // Corners as a slider (1.7.7): the old four steps are its marks, and a Look that names a step still works.
        val corner = s.shapes.cornerScale ?: s.corners.scale
        SliderSetting(
            tr(R.string.corners), corner, 0f..2f,
            { v -> Corners.entries.minByOrNull { kotlin.math.abs(it.scale - v) }!!.let { if (kotlin.math.abs(it.scale - v) < 0.08f) it.label else "${(v * 100).roundToInt()}%" } },
        ) { v ->
            // Close to a step: that step, so it reads by name.
            Corners.entries.firstOrNull { kotlin.math.abs(it.scale - v) < 0.06f }?.let { prefs.setCorners(it) } ?: prefs.setCornerScale(v)
        }
        // A peek at what the corners look like.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ui = LocalUi.current
            listOf(scheme.primary, scheme.tertiary, scheme.secondary).forEach { col ->
                Box(Modifier.weight(1f).aspectRatio(1.3f).clip(ui.tile).background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.55f)))))
            }
        }
    }
    CoverShapeGroup()
    ButtonShapeGroup()
}

/** Covers' shapes (1.7.7): which kind of cover, then each shape drawn as a cover would be. */
@Composable
private fun CoverShapeGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    var which by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("songs") }
    val kinds = listOf("songs" to tr(R.string.songs), "albums" to tr(R.string.albums), "artists" to tr(R.string.artists), "player" to tr(R.string.player))
    val now = when (which) { "albums" -> s.shapes.albums; "artists" -> s.shapes.artists; "player" -> s.shapes.player; else -> s.shapes.songs }
    Group(tr(R.string.covers_shapes), info = tr(R.string.each_kind_of_picture_can_have)) {
        com.prism.music.ui.components.Segmented(
            kinds.map { it.first }, which, { k -> kinds.first { it.first == k }.second }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) { which = it }
        ChoiceCards(
            com.prism.music.data.prefs.CoverShape.entries, now, title = { it.label }, detail = { null }, columns = 4,
            onSelect = { prefs.setCoverShape(which, it) },
        ) { shape -> CoverShapeSample(shape, round = which == "artists") }
        // 1.8.0 (from Player → Cover): it squares video pictures everywhere, not only on the player (#92).
        Toggle(
            tr(R.string.square_video_covers), tr(R.string.no_black_bars_when_a_video), s.squareVideoCovers,
            info = tr(R.string.youtube_s_video_pictures_are_wide),
        ) { prefs.setSquareVideoCovers(it) }
    }
}

@Composable
private fun CoverShapeSample(shape: com.prism.music.data.prefs.CoverShape, round: Boolean) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
                .clip(com.prism.music.ui.theme.CoverShapes.of(shape, ui.r(14.dp)))
                .background(Brush.linearGradient(listOf(scheme.primary, com.prism.music.ui.theme.PrismTokens.spectrumAt(if (round) 0.75f else 0.3f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(if (round) PrismIcons.Person else PrismIcons.Song, null, tint = Color.White.copy(alpha = 0.85f)) }
    }
}

/** Buttons' shape and the Play button (1.7.7). */
@Composable
private fun ButtonShapeGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val scheme = MaterialTheme.colorScheme
    Group(tr(R.string.buttons)) {
        ChoiceCards(
            com.prism.music.data.prefs.ButtonShape.entries, s.shapes.button, title = { it.label }, detail = { null }, columns = 3,
            onSelect = { prefs.setButtonShape(it) },
        ) { b ->
            val shape = LocalUi.current.copy(buttonShape = b).button
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(30.dp).clip(shape).background(scheme.onSurface), contentAlignment = Alignment.Center) {
                    Text(tr(R.string.widget_play), style = MaterialTheme.typography.labelMedium, color = scheme.surface)
                }
            }
        }
        Label(tr(R.string.play_on_album_and_playlist_pages))
        ChoiceCards(
            listOf(false, true), s.shapes.playOutlined, title = { if (it) tr(R.string.outlined) else tr(R.string.filled) },
            detail = { if (it) tr(R.string.just_its_outline) else tr(R.string.solid_with_a_glow) },
            onSelect = { prefs.setPlayOutlined(it) },
        ) { outlined ->
            val shape = LocalUi.current.button
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp).clip(shape)
                        .then(if (outlined) Modifier.border(1.5.dp, scheme.onSurface, shape) else Modifier.background(scheme.onSurface)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PrismIcons.Play, null, Modifier.size(16.dp), tint = if (outlined) scheme.onSurface else scheme.surface)
                    Spacer(Modifier.width(4.dp))
                    Text(tr(R.string.widget_play), style = MaterialTheme.typography.labelMedium, color = if (outlined) scheme.onSurface else scheme.surface)
                }
            }
        }
        // Classic controls (1.7.8, #155): each card draws a switch and a slider its way.
        Label(tr(R.string.switches_sliders_and_buttons), info = tr(R.string.prism_s_own_lit_with_your))
        ChoiceCards(listOf(false, true), s.shapes.classicControls, title = { if (it) tr(R.string.classic) else tr(R.string.app_name) }, detail = { if (it) tr(R.string.as_in_1_6) else tr(R.string.lit_with_your_light_as_now) }, onSelect = { prefs.setControlsClassic(it) }) { classic ->
            CompositionLocalProvider(LocalUi provides LocalUi.current.copy(classicControls = classic)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.prism.music.ui.components.PrismSlider(0.6f, {}, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    com.prism.music.ui.components.PrismSwitch(true, null)
                }
            }
        }
    }
}

@Composable
private fun ResetLook() {
    val prefs = LocalContainer.current.settings
    var ask by remember { mutableStateOf(false) }
    PrismTextButton(onClick = { ask = true }, Modifier.fillMaxWidth()) {
        Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.reset_look_layout_to_defaults))
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(tr(R.string.reset_look_layout)) },
        text = { Text(tr(R.string.font_text_size_corners_spacing_navigation)) },
        confirmButton = { PrismTextButton(onClick = { prefs.resetLook(); ask = false }) { Text(tr(R.string.reset)) } },
        dismissButton = { PrismTextButton(onClick = { ask = false }) { Text(tr(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------------------- Layout & navigation

private fun androidx.compose.foundation.lazy.LazyListScope.layoutAndNavigation() {
    item(key = "layout") {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            NavBarGroup()
            TabsEditor()
            MiniPlayerGroup()
            PagesGroup()
            Box {
                val s = LocalAppSettings.current
                val prefs = LocalContainer.current.settings
                Group(tr(R.string.screen)) {
                    Toggle(
                        tr(R.string.hide_the_status_bar), tr(R.string.more_room_swipe_down_from_the), s.hideStatusBar,
                        info = tr(R.string.the_clock_battery_and_notification_icons),
                    ) { prefs.setHideStatusBar(it) }
                    // Reddit, 1.7.9.1: only while the player is up (the rest of Prism keeps it). Moot with it hidden everywhere.
                    if (!s.hideStatusBar) Toggle(
                        tr(R.string.hide_status_bar_on_the_player), tr(R.string.only_while_the_player_its_lyrics), s.hideStatusBarPlayer,
                    ) { prefs.setHideStatusBarPlayer(it) }
                }
            }
            ResetPage(SettingsSection.INTERFACE)
        }
    }
}

@Composable
private fun NavBarGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.navigation_bar)) {
        Label(tr(R.string.style))
        Chips(NavBarStyle.entries, s.navStyle, { it.label }) { prefs.setNavStyle(it) }
        // The Minimal bar has no labels to show.
        if (s.navStyle != NavBarStyle.MINIMAL) {
            Label(tr(R.string.labels), info = tr(R.string.never_shows_only_the_icons_words))
            Chips(NavLabels.entries, s.navLabels, { it.label }) { prefs.setNavLabels(it) }
        }
        Label(tr(R.string.height))
        Chips(com.prism.music.data.prefs.NavBarSize.entries, s.navBarSize, { it.label }) { prefs.setNavBarSize(it) }
        Label(tr(R.string.background), info = tr(R.string.see_through_lets_the_page_show))
        Chips(com.prism.music.data.prefs.NavBarFill.entries, s.navBarFill, { it.label }) { prefs.setNavBarFill(it) }
        // #264 (1.8.0): only where it means something, a tablet or wide window (phones: always the bottom).
        if (com.prism.music.ui.theme.LocalWindowLayout.current.rail) {
            Label(tr(R.string.tabs_on_a_tablet), info = tr(R.string.tabs_on_a_tablet_info))
            Chips(com.prism.music.data.prefs.TabBarPosition.entries, s.behavior.tabBarPosition, { it.label }) { prefs.setTabBarPosition(it) }
        }
        // 1.8.0: from Icons & shapes (it's how the tab bar marks where you are).
        Label(tr(R.string.the_tab_you_re_on))
        ChoiceCards(
            com.prism.music.data.prefs.TabTint.entries, s.shapes.tabTint, title = { it.label }, detail = { null }, columns = 3,
            onSelect = { prefs.setTabTint(it) },
        ) { t ->
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                Icon(com.prism.music.ui.theme.PrismIcons.HomeFilled, null, Modifier.size(26.dp).tabTint(t))
            }
        }
        Toggle(tr(R.string.shrink_while_scrolling), tr(R.string.slims_down_as_you_scroll), s.navHideOnScroll) { prefs.setNavHideOnScroll(it) }
        Toggle(
            tr(R.string.swipe_between_tabs), tr(R.string.swipe_sideways_on_a_tab_s), s.swipeTabs,
            info = tr(R.string.a_sideways_swipe_on_a_tab),
        ) { prefs.setSwipeTabs(it) }
    }
}

@Composable
private fun MiniPlayerGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.mini_player)) {
        Chips(MiniPlayerStyle.entries, s.miniPlayerStyle, { it.label }) { prefs.setMiniPlayerStyle(it) }
        if (s.navStyle == NavBarStyle.FLOATING) Toggle(
            tr(R.string.joined_to_the_tab_bar), tr(R.string.one_piece_of_glass_with_the), s.miniPlayerJoined,
            info = tr(R.string.the_mini_player_sits_on_top),
        ) { prefs.setMiniPlayerJoined(it) }
        Toggle(tr(R.string.previous_button), tr(R.string.swiping_right_goes_back_too), s.miniPrevButton) { prefs.setMiniPrevButton(it) }
        // Reddit (1.8.1): play / pause can go too, as Next can.
        Toggle(tr(R.string.play_pause_button), tr(R.string.the_player_and_your_phone_s_controls_still_have_it), s.view.miniPlayButton) { prefs.setMiniPlayButton(it) }
        Toggle(tr(R.string.next_button), tr(R.string.swiping_left_skips_too), s.miniSkipButtons) { prefs.setMiniSkipButtons(it) }
        Label(tr(R.string.progress))
        Chips(MiniProgress.entries, s.miniProgress, { it.label }) { prefs.setMiniProgress(it) }
        // #231 (1.7.9.2): its color, and how strongly Fill tints the card.
        if (s.miniProgress != MiniProgress.NONE) {
            Label(tr(R.string.progress_bar_color))
            Chips(com.prism.music.data.prefs.ScrubberColor.entries, s.view.miniProgressColor, { if (it == com.prism.music.data.prefs.ScrubberColor.WHITE) tr(R.string.plain) else it.label }) { prefs.setMiniProgressColor(it) }
        }
        if (s.miniProgress == MiniProgress.FILL) SliderSetting(
            tr(R.string.fill_strength), s.view.miniFillStrength, 0.1f..0.9f, { "${(it * 100).roundToInt()}%" },
        ) { prefs.setMiniFillStrength(it) }
    }
}

@Composable
private fun PagesGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.pages)) {
        Label(tr(R.string.spacing))
        Chips(UiDensity.entries, s.uiDensity, { it.label }) { prefs.setUiDensity(it) }
        Label(tr(R.string.page_titles))
        Chips(TitleSize.entries, s.titleSize, { it.label }) { prefs.setTitleSize(it) }
        Label(tr(R.string.album_playlist_cards))
        Chips(CardSize.entries, s.cardSize, { it.label }) { prefs.setCardSize(it) }
        Spacer(Modifier.height(6.dp))
    }
}

/** "Reset this page" (1.7.3): this page's look settings back to how Prism comes, after asking. */
@Composable
private fun ResetPage(section: SettingsSection) {
    val c = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var ask by remember { mutableStateOf(false) }
    PrismTextButton(onClick = { ask = true }, Modifier.fillMaxWidth()) {
        Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.reset_this_page))
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(tr(R.string.reset_2, section.title)) },
        text = { Text(tr(R.string.this_page_s_look_goes_back)) },
        confirmButton = {
            PrismTextButton(onClick = {
                ask = false
                c.settings.resetLookKeys(section.lookKeys)
                // The icon style lives apart from the settings.
                if (section == SettingsSection.ICONS) {
                    com.prism.music.ui.theme.IconStyle.save(context, com.prism.music.ui.theme.IconWeight.REGULAR, true)
                    com.prism.music.ui.theme.IconStyle.savePack(context, com.prism.music.ui.theme.IconPack.PRISM)
                    com.prism.music.ui.theme.IconStyle.saveLikeStar(context, false)
                    com.prism.music.ui.theme.IconStyle.saveFill(context, com.prism.music.ui.theme.IconFill.OUTLINE)
                    c.looks.iconsChanged()
                }
            }) { Text(tr(R.string.reset)) }
        },
        dismissButton = { PrismTextButton(onClick = { ask = false }) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * "Customize this" (1.7.3): the settings for the element tapped, in a sheet, the same groups as on their pages, with a
 * way to the whole page. A Home section also gets its own style.
 */
@Composable
fun CustomizeSheet(target: com.prism.music.ui.components.CustomizeTarget, key: String?, onLeave: () -> Unit = {}, onDismiss: () -> Unit) {
    StoredSettings { CustomizeSheetContent(target, key, onLeave, onDismiss) }
}

@Composable
private fun CustomizeSheetContent(target: com.prism.music.ui.components.CustomizeTarget, key: String?, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val page = when (target) {
        com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER, com.prism.music.ui.components.CustomizeTarget.TAB_BAR -> SettingsSection.INTERFACE
        com.prism.music.ui.components.CustomizeTarget.PLAYER -> SettingsSection.PLAYER
        com.prism.music.ui.components.CustomizeTarget.HOME_SECTION -> SettingsSection.HOME
    }
    val section = key?.let { k -> s.homeLayout.firstOrNull { it.key == k } }
    com.prism.music.ui.components.ActionSheet(null, onDismiss) { close ->
        Text(
            section?.title ?: target.title, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
        )
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (target) {
                com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER -> MiniPlayerGroup()
                com.prism.music.ui.components.CustomizeTarget.TAB_BAR -> NavBarGroup()
                com.prism.music.ui.components.CustomizeTarget.PLAYER -> { PlayerLayoutGroup(); PlayerLookGroup(); PlayerCoverGroup(); PlayerButtonsGroup(); PlayerVisualizerGroup(); LyricsStyleGroup(); LyricsLookGroup(tr(R.string.lyrics)) }
                com.prism.music.ui.components.CustomizeTarget.HOME_SECTION -> {
                    if (section != null) Group(tr(R.string.this_section)) {
                        Label(tr(R.string.style))
                        Chips(com.prism.music.data.prefs.SectionStyle.entries, section.style, { it.label }) { st ->
                            c.settings.setHomeLayout(s.homeLayout.map { if (it.key == section.key) it.copy(style = st) else it })
                        }
                        Item(tr(R.string.hide_this_section), tr(R.string.bring_it_back_from_customize_home), onClick = {
                            c.settings.setHomeLayout(s.homeLayout.map { if (it.key == section.key) it.copy(visible = false) else it })
                            close {}
                        })
                    }
                    PagesGroup()
                    ShapeSettings()
                }
            }
            // The Undo bar sits under the sheet: a change made here can be taken back here.
            val steps by c.looks.steps.collectAsState()
            val openedAt = remember { System.currentTimeMillis() }
            steps.firstOrNull()?.takeIf { it.at >= openedAt }?.let { step ->
                PrismTextButton({ c.looks.undo() }, Modifier.fillMaxWidth()) {
                    Icon(PrismIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(tr(R.string.undo_2, step.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PrismTextButton({ close { onLeave(); nav.go(Routes.settings(page.key)) } }, Modifier.fillMaxWidth()) {
                Text(tr(R.string.all_settings, page.title)); Spacer(Modifier.width(6.dp)); Chevron()
            }
        }
    }
}

/** Which tabs the bar holds, in what order, and which one Prism opens on. */
@Composable
private fun TabsEditor() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val tabs = s.navTabs
    var renaming by remember { mutableStateOf<NavTab?>(null) }
    renaming?.let { t -> RenameTabDialog(t, s.tabNames[t] ?: t.label, { renaming = null }) { prefs.setTabName(t, it); renaming = null } }
    Group(tr(R.string.tabs), info = tr(R.string.tick_up_to_tabs_and_hold, NavTab.MAX)) {
        // Settings opens from Home and Library, or is a tab itself: one of them always stays.
        fun allowed(next: List<NavTab>) = next.size in 2..NavTab.MAX && next.any { it == NavTab.HOME || it == NavTab.LIBRARY || it == NavTab.SETTINGS }
        @Composable
        fun TabRow(t: NavTab, i: Int, modifier: Modifier, grip: Modifier?, held: Boolean) {
            val on = t in tabs
            val name = s.tabNames[t]
            Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrismCheck(on, enabled = if (on) allowed(tabs - t) else allowed(tabs + t), label = t.label) { v ->
                    val next = if (v) tabs + t else tabs - t
                    if (allowed(next)) {
                        prefs.setNavTabs(next)
                        if (!v && s.startTab == t) prefs.setStartTab(next.first())
                    }
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = tr(R.string.rename_2, t.label)) { renaming = t }.padding(vertical = 6.dp)) {
                    Text(name ?: t.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (name != null) Text(t.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (on && !held) {
                    IconButton(onClick = { prefs.setNavTabs(tabs.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, t.label)) }
                    IconButton(onClick = { prefs.setNavTabs(tabs.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < tabs.lastIndex) { Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, t.label)) }
                }
                if (grip != null) com.prism.music.ui.components.DragGrip(grip.padding(horizontal = 4.dp), tr(R.string.drag, t.label))
            }
        }
        com.prism.music.ui.components.ReorderList(tabs, { it }, { prefs.setNavTabs(it) }) { t, i, held, mod -> TabRow(t, i, mod, Modifier.dragHandle(), held) }
        NavTab.entries.filter { it !in tabs }.forEach { t -> TabRow(t, -1, Modifier, null, false) }
        Label(tr(R.string.open_prism_on), info = tr(R.string.from_prism_s_next_start))
        Chips(tabs, if (s.startTab in tabs) s.startTab else tabs.first(), { s.tabNames[it] ?: it.label }) { prefs.setStartTab(it) }
        Spacer(Modifier.height(4.dp))
    }
}

/** A tab's own name (1.7.6); blank gives it back its own. */
@Composable
private fun RenameTabDialog(tab: NavTab, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.rename_2, tab.label)) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                text, { text = com.prism.music.data.prefs.cleanTabName(it) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(tab.label) },
            )
        },
        confirmButton = { PrismTextButton(onClick = { onSave(text) }) { Text(tr(R.string.save)) } },
        dismissButton = {
            Row {
                if (current != tab.label) PrismTextButton(onClick = { onSave("") }) { Text(tr(R.string.use_2, tab.label)) }
                PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
            }
        },
    )
}

// ---------------------------------------------------------------------------- Player

@Composable
private fun LockScreenGroup() {
    // 1.8.2: Prism's own lock screen ([com.prism.music.ui.lockscreen.LockScreenActivity]); off unless switched on.
    val s = LocalAppSettings.current
    val container = LocalContainer.current
    val prefs = container.settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val b = s.behavior
    val gate = com.prism.music.ui.lockscreen.LockScreenGate
    val resumed = com.prism.music.ui.components.rememberResumeCount()
    // Read again on coming back from Android's page.
    val canStart = remember(resumed, b.lockScreen) { gate.canStartDirectly(context) }
    val maker = remember { com.prism.music.ui.lockscreen.LockScreenMaker.of() }
    val refused = remember(resumed, b.lockScreen) { com.prism.music.ui.lockscreen.LockScreenRefusals.lately(context) }
    val song by container.player.currentSong.collectAsState()
    Group(tr(R.string.lock_screen_title)) {
        Toggle(tr(R.string.lock_screen_title), tr(R.string.lock_screen_detail), b.lockScreen, info = tr(R.string.lock_screen_info)) { on ->
            prefs.setLockScreen(on)
            // From Android 13 an app installed from a file only gets "App was denied access" on that page: the steps come first.
            if (on && !gate.canStartDirectly(context) && !gate.mayBeRestricted) gate.openPermissionPage(context)
        }
        if (b.lockScreen) {
            if (!canStart) {
                Note(tr(R.string.lock_screen_needs_permission))
                if (gate.mayBeRestricted) {
                    Item(tr(R.string.lock_screen_app_info), tr(R.string.lock_screen_app_info_detail), onClick = { gate.openAppInfo(context) }, info = tr(R.string.lock_screen_app_info_info))
                    Item(tr(R.string.lock_screen_allow_step), tr(R.string.lock_screen_allow_detail), onClick = { gate.openPermissionPage(context) }, info = tr(R.string.lock_screen_allow_info))
                } else {
                    Item(tr(R.string.lock_screen_allow), tr(R.string.lock_screen_allow_detail), onClick = { gate.openPermissionPage(context) }, info = tr(R.string.lock_screen_allow_info))
                }
            }
            // D43 (1.8.3): vivo and Xiaomi add permissions of their own that Android can't read: one more step there,
            // and a line when the phone refused the player last time.
            maker?.let { m ->
                val detail = if (m == com.prism.music.ui.lockscreen.LockScreenMaker.XIAOMI) R.string.lock_screen_maker_xiaomi_detail else R.string.lock_screen_maker_vivo_detail
                Item(tr(R.string.lock_screen_maker_step), tr(detail), onClick = { com.prism.music.ui.lockscreen.LockScreenMaker.openPage(context, m) }, info = tr(R.string.lock_screen_maker_info))
            }
            if (canStart && refused) Note(tr(R.string.lock_screen_refused))
            Label(tr(R.string.lock_screen_layout_label))
            if (s.performanceMode) Note(tr(R.string.lock_screen_performance))
            ChoiceCards(
                com.prism.music.data.prefs.LockLayout.entries, b.lockScreenLayout, title = { it.label }, detail = { it.detail }, columns = 2,
                onSelect = { prefs.setLockScreenLayout(it) },
            ) { com.prism.music.ui.lockscreen.LockScreenSample(it, song) }
            Label(tr(R.string.lock_screen_show), info = tr(R.string.lock_screen_show_info))
            ShowChips(
                listOf(
                    ShowOption(tr(R.string.volume), b.lockScreenVolume) { prefs.setLockScreenVolume(it) },
                    ShowOption(tr(R.string.lock_screen_show_like), b.lockScreenLike) { prefs.setLockScreenLike(it) },
                    ShowOption(tr(R.string.lock_screen_show_output), b.lockScreenOutput) { prefs.setLockScreenOutput(it) },
                    // 1.8.3 (D47): off by default; the cards above show them once on.
                    ShowOption(tr(R.string.repeat), b.lockScreenRepeat) { prefs.setLockScreenRepeat(it) },
                    ShowOption(tr(R.string.up_next), b.lockScreenUpNext) { prefs.setLockScreenUpNext(it) },
                ),
            )
            Label(tr(R.string.lock_screen_paused_label), info = tr(R.string.lock_screen_paused_info))
            Chips(com.prism.music.data.prefs.LockPaused.entries, b.lockScreenPaused, { it.label }) { prefs.setLockScreenPaused(it) }
            Toggle(tr(R.string.lock_screen_hide), tr(R.string.lock_screen_hide_detail), b.lockScreenHideSong, info = tr(R.string.lock_screen_hide_info)) { prefs.setLockScreenHideSong(it) }
        }
    }
}

@Composable
private fun PlayerSettings() {
    val s = LocalAppSettings.current
    val container = LocalContainer.current
    val prefs = container.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // How the player looks (1.7.6: what it does on a touch is in Controls & gestures, what it keeps on the phone in
        // Downloads, storage & data). 1.8.0: its layout, then what's on it (one editor with the player drawn, replacing
        // "Show on the player", "Under the title" and the buttons; PlayerYourWay.kt), its look and its cover.
        // "Song length" went (tapping the time switches it, and it's remembered); the cast mark is no longer a choice.
        PlayerLayoutGroup()
        PlayerPartsGroup()
        PlayerLookGroup()
        PlayerCoverGroup()
        Group(tr(R.string.lyrics_and_the_queue), info = tr(R.string.the_player_s_controls_while_the)) {
            ChoiceCards(
                com.prism.music.data.prefs.PanelPlayer.entries, s.view.panelPlayer, title = { it.label }, detail = { it.detail }, columns = 2,
                onSelect = { prefs.setPanelPlayer(it) },
            ) { PanelPlayerSample(it) }
            if (s.view.panelPlayer == com.prism.music.data.prefs.PanelPlayer.AUTO_HIDE) Toggle(
                tr(R.string.scrolling_up_shows_the_controls), tr(R.string.back_up_the_lyrics_or_the), s.behavior.scrollShowsControls,
                info = tr(R.string.off_scrolling_back_up_to_an),
            ) { prefs.setScrollShowsControls(it) }
            Toggle(
                tr(R.string.queue_headings), tr(R.string.played_now_playing_and_up_next), s.view.queueHeadings,
                info = tr(R.string.off_the_queue_is_one_list),
            ) { prefs.setQueueHeadings(it) }
            // #275: room in the queue's header; DJ can be one of the player's buttons instead.
            Toggle(
                tr(R.string.dj_in_the_queue), tr(R.string.dj_in_the_queue_detail), s.view.queueDjChip,
                info = tr(R.string.dj_in_the_queue_info),
            ) { prefs.setQueueDjChip(it) }
            // #331, #294: the queue's rows.
            Toggle(tr(R.string.song_lengths_in_the_queue), null, s.view.queueLengths) { prefs.setQueueLengths(it) }
            Toggle(
                tr(R.string.move_songs_you_have_played), null, s.view.queuePlayedMoves,
                info = tr(R.string.move_songs_you_have_played_info),
            ) { prefs.setQueuePlayedMoves(it) }
            Toggle(
                tr(R.string.tidier_queue), null, s.view.queueTidy,
                info = tr(R.string.tidier_queue_info),
            ) { prefs.setQueueTidy(it) }
            // #280: which page the player opens on, and how readily the lyrics' controls move.
            Toggle(
                tr(R.string.reopen_on_the_last_page), null, s.behavior.playerRemembersPage,
                info = tr(R.string.reopen_on_the_last_page_info),
            ) { prefs.setPlayerRemembersPage(it) }
            Toggle(
                tr(R.string.steadier_lyrics), null, s.behavior.lyricsSteady,
                info = tr(R.string.steadier_lyrics_info),
            ) { prefs.setLyricsSteady(it) }
        }
        Group(tr(R.string.animated_covers)) {
            Toggle(
                tr(R.string.animated_covers), tr(R.string.moving_artwork_when_a_song_has), s.canvasEnabled,
                info = tr(R.string.motion_artwork_and_video_covers_turn),
            ) { prefs.setCanvasEnabled(it) }
            if (s.canvasEnabled) Toggle(
                tr(R.string.fill_the_player), tr(R.string.the_animated_cover_plays_behind_everything), s.canvasFillsBackground,
                info = tr(R.string.the_moving_artwork_fills_the_whole),
            ) { prefs.setCanvasFillsBackground(it) }
        }
        // #265 (1.8.0): the TV's ambient screen on a phone at its stand ([com.prism.music.ui.tv.AmbientHost]); off unless switched on.
        Group(tr(R.string.desk_mode)) {
            Toggle(tr(R.string.desk_mode), tr(R.string.desk_mode_detail), s.tv.deskMode, info = tr(R.string.desk_mode_info)) { prefs.setDeskMode(it) }
        }
        LockScreenGroup()
        MoreOptions(listOf(tr(R.string.title_and_times), tr(R.string.visualizer), tr(R.string.immersive_mode))) {
        PlayerTitleGroup()
        PlayerVisualizerGroup()
        Group(tr(R.string.immersive_mode)) {
            Toggle(
                tr(R.string.immersive_mode), tr(R.string.the_cover_fills_the_screen_lyrics), s.immersivePlayer,
                info = tr(R.string.like_a_lyric_video_tap_the),
            ) { prefs.setImmersivePlayer(it) }
            if (s.immersivePlayer) {
                Label(tr(R.string.artwork))
                Chips(com.prism.music.data.prefs.ImmersiveArt.entries, s.immersiveArt, { it.label }) { prefs.setImmersiveArt(it) }
                Label(tr(R.string.lyrics))
                Chips(com.prism.music.data.prefs.ImmersiveLyrics.entries, s.immersiveLyrics, { it.label }) { prefs.setImmersiveLyrics(it) }
                Toggle(tr(R.string.keep_controls_on_screen), tr(R.string.previous_play_and_next_stay_up), s.immersiveControls) { prefs.setImmersiveControls(it) }
                Toggle(
                    tr(R.string.show_the_video), tr(R.string.a_playing_music_video_behind_the), s.view.immersiveVideo,
                    info = tr(R.string.when_a_music_video_is_playing),
                ) { prefs.setImmersiveVideo(it) }
                Spacer(Modifier.height(6.dp))
            }
        }
        }
    }
}

/**
 * Settings → Controls & gestures (1.7.6): what touches do, wherever they were before: the player's cover and the mini
 * player (Player), holding previous / next and haptics, the notification's and car's buttons, Search's keyboard.
 */
@Composable
private fun ControlsPage() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // Swipes, double tap and long press (#79's seeking among them), then the buttons' own.
        PlayerGesturesGroup()
        Group(tr(R.string.buttons_and_touch)) {
            // 1.8.0: one slider, Off at its start (the switch only flipped this between 0 and 10 s). Same stored value.
            SliderSetting(
                tr(R.string.hold_previous_next_to_seek), s.holdSeekSeconds.toFloat(), 0f..20f,
                { if (it.roundToInt() == 0) tr(R.string.off) else "${it.roundToInt()} s" }, steps = 19,
                info = tr(R.string.a_tap_still_changes_songs),
            ) { prefs.setHoldSeekSeconds(it.roundToInt()) }
            Toggle(tr(R.string.haptics), tr(R.string.a_light_tap_on_buttons_and), s.haptics) { prefs.setHaptics(it) }
            Toggle(
                tr(R.string.swipe_up_for_the_queue), tr(R.string.on_the_player_s_lower_half), s.behavior.panelSwipes,
                info = tr(R.string.swipe_up_on_the_lower_half_all),
            ) { prefs.setPanelSwipes(it) }
        }
        Group(tr(R.string.screen)) {
            Toggle(
                tr(R.string.lock_to_portrait), tr(R.string.prism_stays_upright_when_the_phone), s.behavior.lockPortrait,
                info = tr(R.string.on_a_phone_prism_no_longer),
            ) { prefs.setLockPortrait(it) }
            Label(tr(R.string.picture_in_picture), info = tr(R.string.when_a_playing_music_video_floats))
            Chips(com.prism.music.data.prefs.PipMode.entries, s.behavior.pip, { it.label }) { prefs.setPipMode(it) }
        }
        SessionButtonsGroup()
    }
}

@Composable
private fun PlayerLookGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.look)) {
        // #320 (1.8.1): White is a white player with dark type, for the light theme.
        Label(tr(R.string.background), info = tr(R.string.player_background_white_info) + " " + tr(R.string.cover_gradient_info))
        // #232, #338 (1.8.2): each as a small player on that background, with the song playing.
        PlayerBackgroundCards()
        if (s.playerBackground == PlayerBackground.APPEARANCE) {
            val nav = LocalNavigator.current
            val own = s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP]
            Item(
                tr(R.string.which_background),
                if (own == null) tr(R.string.same_as_home_darkened, com.prism.music.ui.theme.Backdrop.parse(s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name]).first.label)
                else com.prism.music.ui.theme.Backdrop.parse(own).first.label + tr(R.string.darkened_2),
                onClick = { nav.go(Routes.BACKGROUNDS + "?player=true") },
            ) { Chevron() }
        }
        // #309 (1.8.1): the slider runs on past what it could before, to a fully dark player at 0% (stored values from
        // before draw as they did; only the percent shown for them moved).
        if (s.playerBackground == PlayerBackground.BLUR || s.playerBackground == PlayerBackground.APPEARANCE) SliderSetting(
            tr(R.string.show_background), com.prism.music.ui.player.backdropShowOnSlider(s.playerBackdropShow), 0f..1f,
            {
                val show = com.prism.music.ui.player.backdropShowFromSlider(it)
                worded(if (it < 0.01f) tr(R.string.dark) else if (show < 0.2f) tr(R.string.subtle) else if (show < 0.7f) tr(R.string.balanced) else tr(R.string.vivid), it)
            },
            info = tr(R.string.show_background_info),
        ) { prefs.setPlayerBackdropShow(com.prism.music.ui.player.backdropShowFromSlider(it)) }
        // The mesh's movement, the blur's softness (1.7.4; one movement slider since 1.8.0).
        PlayerBackgroundTuning()
        Label(tr(R.string.controls))
        Chips(TransportStyle.entries, s.transportStyle, { it.label }) { prefs.setTransportStyle(it) }
        // #231 (1.7.9.2): the split-light ring around play, which some find too much.
        Toggle(tr(R.string.ring_on_the_play_button), tr(R.string.the_split_light_around_play_and_pause), s.view.playRing) { prefs.setPlayRing(it) }
        // The row in a capsule or apart (1.8.0: a look choice here; which buttons are in What's on the player).
        ButtonsPillCards()
    }
    // 1.8.0: the progress bar's style, color, thickness and knob in one group (they were split between here and More options).
    PlayerProgressGroup()
}

/** The player with its lyrics open, drawn small: lines of lyrics, and the controls under them as [mode] leaves them. */
@Composable
private fun PanelPlayerSample(mode: com.prism.music.data.prefs.PanelPlayer) {
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 18.dp, vertical = 6.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(ink.copy(alpha = 0.06f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
        val controls = mode != com.prism.music.data.prefs.PanelPlayer.HIDDEN
        // Small (#230): the controls take a slimmer band at the bottom, the list most of the room.
        val small = mode == com.prism.music.data.prefs.PanelPlayer.SMALL
        val linesEnd = if (small) h * 0.76f else if (controls) h * 0.62f else h * 0.92f
        var y = h * 0.12f
        var i = 0
        while (y < linesEnd) {
            drawRoundRect(
                ink.copy(alpha = if (i == 1) 0.85f else 0.3f), androidx.compose.ui.geometry.Offset(w * 0.12f, y),
                androidx.compose.ui.geometry.Size(w * (if (i % 2 == 0) 0.62f else 0.74f), h * 0.07f), androidx.compose.ui.geometry.CornerRadius(h * 0.035f),
            )
            y += h * 0.14f; i++
        }
        if (small) {
            drawRect(ink.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.83f), androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.02f))
            listOf(0.4f, 0.5f, 0.6f).forEachIndexed { k, x ->
                drawCircle(ink.copy(alpha = 0.85f), if (k == 1) h * 0.05f else h * 0.03f, androidx.compose.ui.geometry.Offset(w * x, h * 0.92f))
            }
        } else if (controls) {
            val a = if (mode == com.prism.music.data.prefs.PanelPlayer.AUTO_HIDE) 0.3f else 0.85f
            drawRect(ink.copy(alpha = a * 0.6f), androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.72f), androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.025f))
            listOf(0.32f, 0.5f, 0.68f).forEachIndexed { k, x ->
                drawCircle(ink.copy(alpha = a), if (k == 1) h * 0.08f else h * 0.05f, androidx.compose.ui.geometry.Offset(w * x, h * 0.86f))
            }
        }
    }
}

@Composable
private fun PlayerCoverGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.cover)) {
        Label(tr(R.string.cover_size))
        Chips(CoverSize.entries, s.coverSize, { it.label }) { prefs.setCoverSize(it) }
        SliderSetting(tr(R.string.corners), s.playerArtCorners.toFloat(), 0f..32f, { tr(R.string.dp, it.roundToInt()) }, steps = 7) { prefs.setPlayerArtCorners(it.roundToInt()) }
        // Two switches again (1.8.1): one for the cover, one for a music video; the video's own button changes only the video.
        Toggle(tr(R.string.edge_to_edge_cover), null, s.fullBleedArtwork, info = tr(R.string.the_cover_runs_to_the_screen)) { prefs.setFullBleedArtwork(it) }
        Toggle(tr(R.string.edge_to_edge_video), null, s.videoFullBleed, info = tr(R.string.a_music_video_runs_to_the)) { prefs.setVideoFullBleed(it) }
        if (s.fullBleedArtwork) Chips(com.prism.music.data.prefs.BleedFit.entries, s.view.bleedFit, { it.label }) { prefs.setBleedFit(it) }
        // #293: over a full-bleed picture, smaller controls and a later fade.
        if (s.fullBleedArtwork || s.videoFullBleed) Toggle(
            tr(R.string.more_room_for_the_picture), null, s.view.bleedRoomy,
            info = tr(R.string.more_room_for_the_picture_info),
        ) { prefs.setBleedRoomy(it) }
        // With full-bleed artwork the banner covers the cover that shrinks (Reddit, 1.7.9.3): it says so rather than seem broken.
        Toggle(
            tr(R.string.shrink_when_paused),
            if (s.fullBleedArtwork) tr(R.string.not_with_full_bleed_artwork_the_banner_covers_it) else tr(R.string.the_cover_eases_back_while_paused),
            s.artShrinkOnPause,
        ) { prefs.setArtShrinkOnPause(it) }
        // Square video covers moved to Icons & shapes → Covers' shapes (1.8.0: it changes covers everywhere).
        Toggle(
            tr(R.string.cover_flies_into_place), tr(R.string.opening_the_player_the_mini_player), s.view.coverFlight,
            info = tr(R.string.instead_of_the_whole_player_sliding),
        ) { prefs.setCoverFlight(it) }
        // #223: and back the same way.
        if (s.view.coverFlight) Toggle(
            tr(R.string.cover_flies_back_too), null, s.view.coverFlightBack,
            info = tr(R.string.cover_flies_back_too_info),
        ) { prefs.setCoverFlightBack(it) }
    }
}

/** Animated covers kept on the phone: the ones saved from the player, and the recently played ones. */
@Composable
private fun CanvasStorage() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val saved by c.canvas.saved.collectAsState()
    var version by remember { mutableStateOf(0) }
    val sizes by androidx.compose.runtime.produceState(0L to 0L, version, saved) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.canvasStore.savedBytes to c.canvasStore.recentBytes }
    }
    fun mb(bytes: Long) = tr(R.string.t_0f_mb).format(bytes / 1_048_576f)
    Item(
        tr(R.string.saved_animated_covers),
        if ((saved.count == 0)) tr(R.string.none_yet) else tr(R.string.tap_to_remove_all, saved.count, mb(sizes.first)),
        info = tr(R.string.save_one_from_the_player_s),
        onClick = {
            if ((saved.count > 0)) scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.canvasStore.clearSaved() }
                version++
            }
        },
    )
    Item(
        tr(R.string.recently_played_covers),
        tr(R.string.tap_to_clear, mb(sizes.second)),
        info = tr(R.string.kept_up_to_400_mb_so),
        onClick = {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.canvasStore.clearRecent() }
                version++
            }
        },
    )
}

// ---------------------------------------------------------------------------- Playback & sound

@Composable
private fun SoundPage() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val found by c.localLossless.found.collectAsState()
    val synced by c.losslessSync.songs.collectAsState()
    val syncing by c.losslessSync.working.collectAsState()
    val askFiles = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Lossless still works without file access; it just can't use files on the phone.
        prefs.setLossless(true)
        if (granted) prefs.setLosslessLocal(true) // lossless sync scans when this turns on
    }
    LaunchedEffect(s.losslessLocal) { if (s.losslessLocal && found < 0) c.losslessSync.sync() }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // 1.8.0 (#210): one choice per decision. Each choice is a face over the settings Prism already keeps
        // (autoplay + its source, radioSongsOnly + songsEverywhere, mix + mixAuto), so nothing is migrated and Looks,
        // backups and old builds read them as before ([SoundChoices]).
        Group(tr(R.string.what_plays_next)) {
            Label(tr(R.string.when_the_queue_ends), info = tr(R.string.when_the_queue_ends_info))
            Chips(QueueEnd.entries, SoundChoices.queueEnd(s), { it.label }) { SoundChoices.setQueueEnd(prefs, it) }
            // #159: as asked for, a choice; off, what's left out is what's in the queue and what was heard in the last hours.
            Toggle(
                tr(R.string.no_repeats_title), tr(R.string.no_repeats_detail), s.behavior.autoplayNoRepeats,
                info = tr(R.string.no_repeats_info),
            ) { prefs.setAutoplayNoRepeats(it) }
            // #299: long mixes and compilations stay out of Autoplay.
            if (s.autoplay) SliderSetting(
                tr(R.string.longest_song_for_autoplay), s.behavior.autoplayMaxMinutes.toFloat(), 0f..com.prism.music.data.AutoplayLength.MAX_MINUTES.toFloat(),
                { if (it < 0.5f) tr(R.string.autoplay_no_length_limit) else tr(R.string.min, it.roundToInt()) },
                steps = com.prism.music.data.AutoplayLength.MAX_MINUTES / com.prism.music.data.AutoplayLength.STEP_MINUTES - 1,
                info = tr(R.string.longest_song_for_autoplay_info),
            ) { prefs.setAutoplayMaxMinutes(it.roundToInt()) }
            Label(tr(R.string.music_videos_label), info = tr(R.string.music_videos_label_info))
            Chips(VideoPolicy.entries, SoundChoices.videoPolicy(s), { it.label }) { SoundChoices.setVideoPolicy(prefs, it) }
            Toggle(tr(R.string.keep_playing_videos), tr(R.string.after_a_video_the_next_song), s.videoContinues) { prefs.setVideoContinues(it) }
            // #216 (1.8.1): explicit songs as before, never played, or not shown either.
            Label(tr(R.string.explicit_songs), info = tr(R.string.explicit_songs_info))
            Chips(com.prism.music.data.prefs.ExplicitContent.entries, s.behavior.explicitContent, { it.label }) { prefs.setExplicitContent(it) }
        }
        Group(tr(R.string.crossfade)) {
            Label(tr(R.string.crossfade), info = tr(R.string.crossfade_choice_info))
            Chips(Crossfade.entries, SoundChoices.crossfade(s), { it.label }) { SoundChoices.setCrossfade(prefs, it) }
            // #351: up to 30 s (a song's blend is still at most a third of it: MixEngine.makePlan).
            if (s.mix && !s.mixAuto) SliderSetting(tr(R.string.length), s.mixSeconds.toFloat(), 1f..com.prism.music.data.prefs.MAX_CROSSFADE_SECONDS.toFloat(), { "${it.roundToInt()} s" }, steps = com.prism.music.data.prefs.MAX_CROSSFADE_SECONDS - 2) { prefs.setMixSeconds(it.roundToInt()) }
            if (s.mix) {
                Toggle(
                    tr(R.string.match_tempo_and_beats_dj_style), tr(R.string.beats_line_up_while_songs_overlap), s.mixBeatMatch,
                    info = tr(R.string.the_next_song_plays_at_the),
                ) { prefs.setMixBeatMatch(it) }
                // #247: an album played as an album never blended; this keeps its songs whole anywhere they meet.
                Toggle(
                    tr(R.string.keep_an_album_s_songs_whole), tr(R.string.no_crossfade_between_songs_of_one_album), s.mixKeepAlbums,
                    info = tr(R.string.an_album_playing_through_never_blends),
                ) { prefs.setMixKeepAlbums(it) }
            }
            // The crossfade mark and blended covers are on the player now: Player → What's on the player.
        }
        Group(tr(R.string.speed_pitch_and_fades)) { SpeedAndFades() }
        Group(tr(R.string.quality)) {
            Toggle(
                tr(R.string.lossless_playback),
                when {
                    !s.lossless -> tr(R.string.the_best_audio_and_your_flac)
                    !s.losslessLocal -> tr(R.string.allow_file_access_to_use_your)
                    found < 0 -> tr(R.string.looking_for_lossless_files)
                    found == 0 -> tr(R.string.no_lossless_files_found_yet)
                    syncing -> tr(R.string.lossless_files_found_adding_them, found)
                    else -> tr(R.string.lossless_files_in_downloads, found, synced.size)
                },
                s.lossless,
                info = tr(R.string.always_plays_the_best_audio_there),
            ) { on ->
                if (!on) { prefs.setLossless(false); prefs.setLosslessLocal(false) }
                else if (c.localLossless.hasPermission()) {
                    prefs.setLossless(true); prefs.setLosslessLocal(true)
                } else askFiles.launch(LocalLossless.permission)
            }
            if (s.lossless) {
                if (s.losslessLocal) Item(
                    tr(R.string.scan_for_lossless_files_again), tr(R.string.also_checked_every_few_hours),
                    onClick = { scope.launch { c.losslessSync.sync() } },
                )
                else Item(tr(R.string.allow_access_to_lossless_files), null, onClick = { askFiles.launch(LocalLossless.permission) })
                // Data saver plays songs at this quality on mobile data, lossless or not (#40).
                if (s.dataSaver) {
                    // 1.8.0: the same row name as without lossless (it is the same setting, cellularQuality).
                    Label(tr(R.string.streaming_quality_on_mobile_data))
                    Chips(AudioQuality.entries.filter { it != AudioQuality.HIGH }, s.cellularQuality.takeIf { it != AudioQuality.HIGH } ?: AudioQuality.NORMAL, { it.label }) { prefs.setCellularQuality(it) }
                }
            } else {
                Label(tr(R.string.streaming_quality_on_wi_fi))
                Chips(AudioQuality.entries, s.audioQuality, { it.label }) { prefs.setAudioQuality(it) }
                Label(tr(R.string.streaming_quality_on_mobile_data))
                Chips(AudioQuality.entries, s.cellularQuality, { it.label }) { prefs.setCellularQuality(it) }
            }
            // Reddit 1.7: "am I getting the quality Premium offers?"
            if (s.isLoggedIn) {
                // #373: said plainly when YouTube isn't sending it, rather than songs just playing at the usual quality.
                val premium by c.streams.premiumState.collectAsState()
                val seen by c.innerTube.seen.collectAsState()
                Toggle(
                    tr(R.string.premium_quality),
                    when {
                        !s.premiumStream -> tr(R.string.t_256_kbps_with_youtube_music_premium)
                        seen?.signedIn == false -> tr(R.string.youtube_no_longer_takes_this_sign_in)
                        premium == com.prism.music.data.stream.StreamResolver.PremiumState.MISSING && c.streams.premiumStateIsCurrent() -> tr(R.string.youtube_isnt_sending_256_kbps)
                        else -> tr(R.string.t_256_kbps_with_youtube_music_premium)
                    },
                    s.premiumStream,
                    info = tr(R.string.prism_normally_fetches_songs_without_your),
                ) { prefs.setPremiumStream(it) }
            }
            Label(tr(R.string.music_video_quality))
            Chips(listOf(480, 720, 1080, 1440), s.videoMaxHeight, { "${it}p" }) { prefs.setVideoMaxHeight(it) }
            Spacer(Modifier.height(6.dp))
        }
        Group(tr(R.string.sound)) {
            // Normalize's switch and its level are one choice: Off, or how loud (#210).
            NormalizeLevel()
            // #325: plainer names, and "Songs only".
            Label(tr(R.string.skip_silence), info = tr(R.string.skip_silence_choice_info))
            Chips(TrimSilence.entries, SoundChoices.trimSilence(s), { it.label }) { SoundChoices.setTrimSilence(prefs, it) }
            SoundSettings(onOpenEq = { nav.go(Routes.EQ) })
        }
        MoreOptions(listOf(tr(R.string.stopping_and_pausing), tr(R.string.how_they_cross))) {
            Group(tr(R.string.stopping_and_pausing)) {
                Toggle(tr(R.string.stop_when_swiped_away), tr(R.string.swiping_prism_out_of_recent_apps), s.stopOnSwipeAway) { prefs.setStopOnSwipeAway(it) }
                Toggle(
                    tr(R.string.pause_when_muted), tr(R.string.volume_all_the_way_down_pauses), s.behavior.pauseOnMute,
                    info = tr(R.string.only_the_phone_s_media_volume),
                ) { prefs.setPauseOnMute(it) }
            }
            Group(tr(R.string.how_they_cross)) { CrossfadeCurveCards() }
        }
    }
}
// ---------------------------------------------------------------------------- Lyrics

/** Languages lyrics can be translated into (Google Translate codes); "" is the phone's own. */
private val TRANSLATE_LANGUAGES = listOf(
    "" to tr(R.string.phone_s_language), "en" to tr(R.string.english), "es" to tr(R.string.spanish), "pt" to tr(R.string.portuguese), "fr" to tr(R.string.french),
    "de" to tr(R.string.german), "it" to tr(R.string.italian), "nl" to tr(R.string.dutch), "pl" to tr(R.string.polish), "tr" to tr(R.string.turkish), "ru" to tr(R.string.russian),
    "uk" to tr(R.string.ukrainian), "ar" to tr(R.string.arabic), "hi" to tr(R.string.hindi), "id" to tr(R.string.indonesian), "vi" to tr(R.string.vietnamese),
    "tl" to tr(R.string.filipino), "ja" to tr(R.string.japanese), "ko" to tr(R.string.korean), tr(R.string.zh_cn) to tr(R.string.chinese_simplified), tr(R.string.zh_tw) to tr(R.string.chinese_traditional),
)

/** Kuromoji's dictionary, for Japanese lyrics' kanji: download, progress, or remove. */
@Composable
private fun JapaneseReadingData() {
    val c = LocalContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val states by c.models.states.collectAsState()
    val m = com.prism.music.data.ModelStore.Model.JAPANESE
    val state = states[m]
    val subtitle = when (state) {
        com.prism.music.data.ModelStore.State.Ready -> tr(R.string.on_this_phone_tap_to_remove, formatMb(c.models.sizeOnPhone(m)))
        is com.prism.music.data.ModelStore.State.Downloading -> tr(R.string.downloading_5, (state.fraction * 100).toInt())
        is com.prism.music.data.ModelStore.State.Failed -> tr(R.string.couldn_t_download_it_tap_to)
        else -> tr(R.string.only_for_japanese_songs, com.prism.music.data.ModelStore.Model.sizeText(m))
    }
    Item(tr(R.string.japanese_kanji_readings), subtitle, info = tr(R.string.only_japanese_needs_this_its_kanji), onClick = {
        when (state) {
            com.prism.music.data.ModelStore.State.Ready -> c.models.delete(m)
            is com.prism.music.data.ModelStore.State.Downloading -> {}
            else -> scope.launch { c.models.download(m) }
        }
    })
}

// In the same (decimal) megabytes as the download sizes, so "74 MB on the phone" and the installed size agree.
private fun formatMb(bytes: Long) = "${bytes / 1_000_000} MB"

/** Settings → Lyrics → Sing: its download, mobile data, and the room its songs' separated vocals take. */
@Composable
private fun SingSettings() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val states by c.models.states.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { c.models.refresh() }
    val runtime = com.prism.music.data.ModelStore.Model.singRuntime()
    val model = com.prism.music.data.ModelStore.Model.SING_MODEL
    var used by remember { mutableStateOf(c.sing.store.bytes()) }
    Group(tr(R.string.sing)) {
        if (runtime == null) {
            Note(tr(R.string.sing_doesn_t_run_on_this))
            return@Group
        }
        val ready = states[runtime] == com.prism.music.data.ModelStore.State.Ready && states[model] == com.prism.music.data.ModelStore.State.Ready
        val downloading = listOf(runtime, model).mapNotNull { states[it] as? com.prism.music.data.ModelStore.State.Downloading }.firstOrNull()
        Item(
            tr(R.string.sing_s_voice_separation),
            when {
                ready -> tr(R.string.on_this_phone_tap_to_remove, formatMb(c.models.sizeOnPhone(runtime) + c.models.sizeOnPhone(model)))
                downloading != null -> tr(R.string.downloading_5, (downloading.fraction * 100).toInt())
                else -> tr(R.string.needed_for_sing, com.prism.music.data.ModelStore.Model.sizeText(runtime, model))
            },
            info = tr(R.string.takes_the_singer_s_voice_out),
            onClick = {
                if (ready) { c.sing.setOn(false); c.models.delete(runtime); c.models.delete(model) }
                else if (downloading == null) scope.launch { c.sing.downloadEngine() }
            },
        )
        Toggle(
            tr(R.string.get_songs_ready_while_sing_is), tr(R.string.so_sing_starts_at_once_when), s.singAlwaysReady,
            info = tr(R.string.the_playing_song_and_the_next),
        ) { c.settings.setSingAlwaysReady(it) }
        // One step past 4 GB is "Unlimited" (#238).
        val top = 4096f + 256f
        SliderSetting(
            tr(R.string.room_for_sung_songs), s.singCacheMb.toFloat().coerceAtMost(top), 256f..top,
            { if (it >= top - 1f) tr(R.string.unlimited) else "${(it / 1024f).let { gb -> if (gb >= 1) "%.1f GB".format(gb) else "${it.roundToInt()} MB" }}" }, steps = 15,
        ) {
            c.settings.setSingCacheMb(if (it >= top - 1f) com.prism.music.data.prefs.SING_ROOM_UNLIMITED else it.roundToInt())
        }
        Item(tr(R.string.clear_sung_songs), tr(R.string.using, formatMb(used)), info = tr(R.string.songs_are_worked_out_again_the), onClick = { c.sing.store.clear(); used = 0 })
    }
}

/** The lyrics' animation; [title] names the group where "Animation" alone wouldn't say whose (the player's Customize sheet). */
@Composable
private fun LyricsLookGroup(title: String = tr(R.string.animation)) {
    // Lyrics in parts (1.8.0, #253): LyricsYourWay.kt.
    Group(title, info = tr(R.string.animation_info)) { LyricsAnimationChoices() }
}

/** Customize Prism → Lyrics (1.8.0, #253): where you start from, then the four parts; the line on the player folded away. */
@Composable
private fun LyricsLookPage() {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        LyricsStyleGroup()
        LyricsAnimationGroup()
        LyricsTextGroup()
        LyricsBackgroundGroup()
        LyricsEffectsGroup()
        MoreOptions(listOf(tr(R.string.translations_romanized_lines), tr(R.string.on_the_player))) {
            LyricsExtrasGroup()
            LyricsOnThePlayerGroup()
        }
    }
}

/** Settings → Lyrics & Sing: where lyrics come from, their language, Sing, and your files (their look is in Customize). */
@Composable
private fun LyricsSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.sources)) {
            Toggle(tr(R.string.prefer_karaoke_synced_lyrics), tr(R.string.word_by_word_first_then_by), s.preferSynced, info = tr(R.string.karaoke_lyrics_lit_up_word_by)) { prefs.setPreferSynced(it) }
            Toggle(tr(R.string.uncensor_lyrics), tr(R.string.fills_in_starred_out_words), s.skipCensored, info = tr(R.string.swear_words_starred_out_f_k)) { prefs.setSkipCensored(it) }
            LyricSourcesOrder()
            // 1.8.0: from the lyrics look page (it's about sources, not looks).
            Toggle(tr(R.string.lyrics_source_button), tr(R.string.lyrics_source_button_info), s.lyricsSourceButton) { prefs.setLyricsSourceButton(it) }
        }
        Group(tr(R.string.language)) {
            // 1.8.0: the switch is the first choice, Off (same stored settings: lyricsTranslate + lyricsTranslateTo).
            Label(tr(R.string.translate_into), info = tr(R.string.for_songs_in_another_language_machine))
            val off = "\u0000off"
            Chips(
                listOf(off) + TRANSLATE_LANGUAGES.map { it.first },
                if (!s.lyricsTranslate) off else s.lyricsTranslateTo.takeIf { code -> TRANSLATE_LANGUAGES.any { it.first == code } } ?: "",
                { code -> if (code == off) tr(R.string.off) else TRANSLATE_LANGUAGES.first { it.first == code }.second },
            ) { code -> if (code == off) prefs.setLyricsTranslate(false) else { prefs.setLyricsTranslateTo(code); prefs.setLyricsTranslate(true) } }
            Label(tr(R.string.romanize_lyrics), info = tr(R.string.other_scripts_in_latin_letters_to))
            Chips(com.prism.music.data.prefs.RomanizeMode.entries, s.romanize, { it.label }) { prefs.setRomanize(it) }
            if (s.romanize != com.prism.music.data.prefs.RomanizeMode.OFF) Toggle(
                tr(R.string.romanize_my_own_language_too), tr(R.string.lyrics_too, java.util.Locale.getDefault().displayLanguage), s.romanizeAll,
                info = tr(R.string.korean_japanese_russian_and_greek_are),
            ) { prefs.setRomanizeAll(it) }
            JapaneseReadingData()
        }
        SingSettings()
        // 1.8.0: rarely used, so folded away.
        MoreOptions(listOf(tr(R.string.your_music_files))) {
            Group(tr(R.string.your_music_files)) {
                val c = LocalContainer.current
                // #344 (1.8.2): the same switch as its row in Order above.
                Toggle(
                    tr(R.string.read_lyrics_from_your_music_files), tr(R.string.synced_or_plain_with_no_internet), s.behavior.embeddedLyrics,
                    info = tr(R.string.flac_ogg_opus_mp3_and_m4a_their),
                    // And its guard: one source stays on.
                    enabled = !s.behavior.embeddedLyrics || s.lyricsOrder.count(s::lyricsSourceOn) > 1,
                ) { prefs.setEmbeddedLyrics(it) }
                Item(
                    tr(R.string.save_lyrics_into_your_music_files), tr(R.string.so_other_players_show_them_too),
                    info = tr(R.string.synced_lyrics_go_into_your_flac),
                    onClick = { c.lyricsToFiles.request(null) },
                )
                // Reddit, 1.7.9.2: what goes in with them.
                Toggle(
                    tr(R.string.keep_word_timings), tr(R.string.karaoke_lyrics_light_word_by_word), s.lyricsFileWords,
                    info = tr(R.string.saved_as_enhanced_lrc_players),
                ) { prefs.setLyricsFileWords(it) }
                Toggle(
                    tr(R.string.include_the_translation), tr(R.string.under_each_line_in_your_translation_language), s.lyricsFileTranslation,
                ) { prefs.setLyricsFileTranslation(it) }
            }
        }
    }
}

/** The lyric sources in the order they're asked, each with its switch (#51); hold one to move it. */
@Composable
private fun LyricSourcesOrder() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Label(tr(R.string.order), info = tr(R.string.tried_top_to_bottom_hold_one))
    com.prism.music.ui.components.ReorderList(s.lyricsOrder, { it }, { prefs.setLyricsOrder(it) }) { src, i, held, mod ->
        val on = s.lyricsSourceOn(src)
        Row(mod.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (i == 0 && on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", color = if (i == 0 && on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).alpha(if (on) 1f else 0.45f)) {
                Text(src.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(src.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Turned off, a source is never asked (#51); at least one stays on.
            com.prism.music.ui.components.PrismSwitch(
                on, { v -> if (src == com.prism.music.data.prefs.LyricsSource.EMBEDDED) prefs.setEmbeddedLyrics(v) else prefs.setLyricsOff(if (v) s.lyricsOff - src else s.lyricsOff + src) },
                enabled = !on || s.lyricsOrder.count(s::lyricsSourceOn) > 1,
                modifier = Modifier.scale(0.8f),
            )
            if (!held) {
                IconButton(onClick = { prefs.setLyricsOrder(s.lyricsOrder.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                    Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, src.label))
                }
                IconButton(onClick = { prefs.setLyricsOrder(s.lyricsOrder.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < s.lyricsOrder.lastIndex) {
                    Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, src.label))
                }
            } else Spacer(Modifier.width(96.dp))
            com.prism.music.ui.components.DragGrip(Modifier.dragHandle().padding(start = 2.dp), tr(R.string.drag, src.label))
        }
    }
}

/**
 * Settings → Devices & connections (1.7.6, "Car & devices" until 1.7.8): Android Auto (its lyrics and its look, from
 * Lyrics before), then speakers, TVs and the browser remote, Discord and Last.fm (owner, 1.7.8: beside Discord, the
 * other account Prism tells what you play), each a page of its own.
 */
@Composable
private fun DevicesPage() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val nav = LocalNavigator.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.android_auto)) {
            Toggle(tr(R.string.lyrics_in_the_car), tr(R.string.on_the_car_s_player), s.carLyrics, info = tr(R.string.in_place_of_the_song_title)) { prefs.setCarLyrics(it) }
            if (s.carLyrics) {
                Label(tr(R.string.style), info = s.carLyricsStyle.label + ": " + s.carLyricsStyle.detail)
                Chips(CarLyricsStyle.entries, s.carLyricsStyle, { it.label }) { prefs.setCarLyricsStyle(it) }
                CarLyricsPreview(s.carLyricsStyle)
                if (s.carLyricsStyle != CarLyricsStyle.TEXT) Toggle(
                    tr(R.string.keep_the_song_title), tr(R.string.lyrics_on_the_cover_only), s.carLyricsKeepTitle,
                    info = tr(R.string.the_car_s_small_media_card),
                ) { prefs.setCarLyricsKeepTitle(it) }
                Toggle(tr(R.string.on_this_phone_too), tr(R.string.lock_screen_and_notification), s.carLyricsOnPhone) { prefs.setCarLyricsOnPhone(it) }
            }
            Toggle(
                tr(R.string.covers_in_a_grid), tr(R.string.home_and_library_as_cover_tiles), s.autoGrid,
                info = tr(R.string.the_car_shows_home_s_sections),
            ) { prefs.setAutoGrid(it) }
            Item(tr(R.string.buttons_in_the_car), tr(R.string.beside_play_in_the_order_the), onClick = { nav.go(Routes.settings(SettingsSection.CONTROLS.key, tr(R.string.notification_and_car))) }) { Chevron() }
        }
        Group(tr(R.string.connections)) {
            val dark = com.prism.music.ui.theme.LocalIsDark.current
            listOf(SettingsSection.CONNECT, SettingsSection.DISCORD, SettingsSection.LASTFM, SettingsSection.SPOTIFY).forEach { sec ->
                NavRow(sec.icon, sec.title, sec.summary(s, dark)) { nav.go(Routes.settings(sec.key)) }
            }
            // YouTube Music (live) beside Spotify's own (owner, 1.8.1): the switch stays in Bring your history, next to
            // Takeout, which brings the past; this row is where people look for a connection's history.
            val ytLive by LocalContainer.current.imports.youtubeLive.on.collectAsState()
            val detail = when {
                !s.isLoggedIn -> tr(R.string.ytlive_connection_signed_out)
                ytLive -> tr(R.string.ytlive_connection_on)
                else -> tr(R.string.ytlive_connection_off)
            }
            NavRow(PrismIcons.History, tr(R.string.youtube_music_2), detail) { nav.go(Routes.BRING_HISTORY) }
        }
        SharingGroup()
    }
}

/** What links Prism sends (Reddit R9), and YouTube Music links opening in Prism (R10). */
@Composable
private fun SharingGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val context = LocalContext.current
    // Android 12+ asks the listener whether Prism opens music.youtube.com links (an app that isn't YouTube Music can't
    // claim them), so the state is read again whenever Prism comes back from that page.
    val resumed = com.prism.music.ui.components.rememberResumeCount()
    val opensLinks = remember(resumed) { com.prism.music.data.LinkHandling.opensYouTubeMusicLinks(context) }
    Group(tr(R.string.sharing_and_links)) {
        Label(tr(R.string.links_you_share), info = tr(R.string.albums_have_no_page_on_youtube))
        ChoiceCards(
            listOf(false, true), s.shareYoutubeLinks, title = { if (it) tr(R.string.youtube) else tr(R.string.youtube_music_2) },
            detail = { if (it) tr(R.string.opens_in_youtube) else tr(R.string.opens_in_youtube_music) }, onSelect = { prefs.setShareYoutubeLinks(it) },
        ) { youtube ->
            Text(
                if (youtube) tr(R.string.youtu_be_fj9ruzimczq) else tr(R.string.music_youtube_com_watch_v_fj9ruzimczq),
                Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Item(
            tr(R.string.open_youtube_music_links_in_prism),
            when (opensLinks) {
                true -> tr(R.string.on_links_you_tap_open_here)
                false -> tr(R.string.off_turn_on_open_supported_links)
                null -> tr(R.string.choose_prism_when_a_link_asks)
            },
            info = tr(R.string.android_only_lets_an_app_open),
            onClick = { com.prism.music.data.LinkHandling.openSettings(context) },
        ) { Chevron() }
    }
}

// ---------------------------------------------------------------------------- Downloads & storage

/**
 * #314 (1.8.1): your own MP3/AAC/Ogg files in place of the stream, beside lossless sync's files in Downloads. Off by
 * default; it needs file access, not lossless playback.
 */
@Composable
private fun OwnSongFiles() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val scope = rememberCoroutineScope()
    val found by c.localLossless.ownFound.collectAsState()
    val synced by c.losslessSync.songs.collectAsState()
    val syncing by c.losslessSync.working.collectAsState()
    val inDownloads = remember(synced) { synced.values.count { it.second.lossy } }
    val askFiles = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prefs.setOwnLossyFiles(true) // lossless sync scans when this turns on
    }
    Group(tr(R.string.your_own_song_files)) {
        Toggle(
            tr(R.string.use_your_own_song_files),
            when {
                !s.sound.ownLossyFiles -> tr(R.string.own_files_off)
                found < 0 -> tr(R.string.own_files_looking)
                found == 0 -> tr(R.string.own_files_none)
                syncing -> tr(R.string.own_files_adding, found)
                else -> tr(R.string.own_files_status, found, inDownloads)
            },
            s.sound.ownLossyFiles,
            info = tr(R.string.own_files_info),
        ) { on ->
            if (!on) prefs.setOwnLossyFiles(false)
            else if (c.localLossless.hasPermission()) prefs.setOwnLossyFiles(true)
            else askFiles.launch(LocalLossless.permission)
        }
        if (s.sound.ownLossyFiles) Item(
            tr(R.string.scan_your_song_files_again), tr(R.string.also_checked_every_few_hours),
            onClick = { scope.launch { c.losslessSync.sync() } },
        )
    }
}

/** Which playlists stay downloaded as they grow (#298; this list's shape is stuntsnakee's, from PR #304). */
@Composable
private fun KeptPlaylistsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val library by c.library.playlists.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    val savedLists by c.prismLibrary.playlists.collectAsState()
    val signedIn = s.isLoggedIn
    val mine = remember(library, prismLists, savedLists, signedIn) {
        (prismLists.map { it.item() } + savedLists.map { it.item() } + if (signedIn) library.filterIsInstance<com.prism.music.data.model.PlaylistItem>() else emptyList())
            .filter { com.prism.music.download.PlaylistDownloads.canKeep(it.id) && !it.isMix }
            .distinctBy { com.prism.music.download.PlaylistDownloads.keepKey(it.id) }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(mine, query) { if (query.isBlank()) mine else mine.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    com.prism.music.ui.components.PrismSheet(onDismiss = onDismiss) {
        Text(tr(R.string.keep_playlists_downloaded), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        if (mine.size > 8) com.prism.music.ui.components.SearchField(query, tr(R.string.find_a_playlist), { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (mine.isEmpty()) Text(tr(R.string.no_playlists_yet), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)) {
            items(shown.size, key = { shown[it].id }) { i ->
                val p = shown[i]
                val on = com.prism.music.download.PlaylistDownloads.keepKey(p.id) in s.behavior.keptPlaylists
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.prism.music.ui.components.Artwork(c.covers.art(p), Modifier.size(46.dp), LocalUi.current.smallArt, size = 120, placeholderIcon = PrismIcons.QueueMusic)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.subtitle.isNotBlank()) Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PrismCheck(on, label = p.title) { c.keptPlaylists.set(p.id, it) }
                }
            }
        }
    }
}

@Composable
private fun DownloadSettings() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val context = LocalContext.current
    var cacheBytes by remember { mutableLongStateOf(c.songCacheBytes()) }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.downloads)) {
            Item(tr(R.string.manage_downloads), tr(R.string.see_and_delete_what_s_on), onClick = { nav.go(Routes.DOWNLOADS) }) { Chevron() }
            Toggle(tr(R.string.download_on_wi_fi_only), null, s.downloadWifiOnly) { prefs.setDownloadWifiOnly(it) }
            Toggle(tr(R.string.download_liked_songs_automatically), tr(R.string.including_likes_from_other_devices), s.downloadLikes) {
                prefs.setDownloadLikes(it)
                if (it) c.downloads.downloadAll(c.library.liked.value)
            }
            // #298 (1.8.2).
            var choosingKept by remember { mutableStateOf(false) }
            val keptCount = s.behavior.keptPlaylists.size
            Item(
                tr(R.string.keep_playlists_downloaded),
                if (keptCount == 0) tr(R.string.none_chosen) else trp(R.plurals.n_playlists_kept, keptCount),
                info = tr(R.string.new_songs_in_these_playlists_download),
                onClick = { choosingKept = true },
            ) { Chevron() }
            if (choosingKept) KeptPlaylistsSheet { choosingKept = false }
        }
        OwnSongFiles()
        // #207, #249 (1.8.2).
        Group(tr(R.string.local_files)) {
            var choosing by remember { mutableStateOf(false) }
            val skipped = s.behavior.localSkipFolders.size
            Item(
                tr(R.string.folders_in_local_files),
                if (skipped == 0) tr(R.string.every_folder_with_music) else trp(R.plurals.n_folders_left_out, skipped),
                info = tr(R.string.untick_a_folder_to_leave_it_out),
                onClick = { choosing = true },
            ) { Chevron() }
            if (choosing) LocalFoldersSheet { choosing = false }
        }
        Group(tr(R.string.smart_downloads)) {
            Toggle(
                tr(R.string.smart_downloads), tr(R.string.songs_you_ll_likely_play_ready), s.smartDownloads,
                info = tr(R.string.one_space_for_music_on_your),
            ) {
                prefs.setSmartDownloads(it)
                SmartDownloadWorker.schedule(context, it)
                if (it) SmartDownloadWorker.runNow(context) else c.trimSongCache()
            }
            val sizes = SMART_DOWNLOAD_SIZES
            var i by remember { mutableFloatStateOf(sizes.indexOfFirst { it >= s.offlineGb }.let { if (it < 0) sizes.lastIndex else it }.toFloat()) }
            val gb = sizes[i.roundToInt().coerceIn(0, sizes.lastIndex)]
            val freeGb = remember { context.filesDir.usableSpace / 1_073_741_824f }
            val picks by c.downloads.smartIds.collectAsState()
            val downloads by c.downloadState.collectAsState()
            val pickBytes = picks.sumOf { downloads[it]?.bytes ?: 0L }
            Label(tr(R.string.space_to_use_2, formatGb(gb)))
            val used = pickBytes + cacheBytes
            val usedText = if (used < 1_073_741_824L) "${used / 1_048_576} MB" else tr(R.string.t_1f_gb).format(used / 1_073_741_824.0)
            Note(
                smartSongEstimate(gb, s.audioQuality).let { n -> trp(R.plurals.about_n_songs_used, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "%,d".format(n), usedText) }.format(freeGb) +
                    if (gb > freeGb) tr(R.string.it_stops_when_storage_runs_low) else ""
            )
            PrismSlider(
                i, { i = it }, Modifier.padding(horizontal = 16.dp), valueRange = 0f..sizes.lastIndex.toFloat(), steps = sizes.size - 2,
                onValueChangeFinished = {
                    prefs.setOfflineGb(gb)
                    c.trimSongCache()
                    if (s.smartDownloads) SmartDownloadWorker.runNow(context)
                },
            )
            Item(tr(R.string.clear_smart_downloads), tr(R.string.your_own_downloads_stay), info = tr(R.string.removes_the_songs_it_downloaded_ahead), onClick = {
                c.downloads.clearSmartDownloads()
                cacheBytes = c.songCacheBytes()
            })
        }
        if (s.canvasEnabled) Group(tr(R.string.animated_covers)) {
            Toggle(
                tr(R.string.save_with_downloads), tr(R.string.downloaded_songs_keep_theirs), s.canvasWithDownloads,
                info = tr(R.string.a_downloaded_song_s_animated_cover),
            ) {
                prefs.setCanvasWithDownloads(it)
                if (it) c.downloads.saveCanvases()
            }
            CanvasStorage()
        }
        // How much of the next songs is loaded ahead (1.8.0: from Playback & sound; it's about data and buffering).
        Group(tr(R.string.load_ahead)) {
            Label(
                tr(R.string.load_ahead),
                info = when (s.preload) {
                    com.prism.music.data.prefs.Preload.START -> tr(R.string.now_the_next_song_s_start)
                    else -> tr(R.string.now_whole_songs_so_they_play)
                },
            )
            Chips(com.prism.music.data.prefs.Preload.entries, s.preload, { it.label }) { prefs.setPreload(it) }
        }
        // Every "on mobile data" choice in one place (1.7.6; they were spread over six pages). Each shows while the
        // thing it's about is on. Streaming quality on mobile data stays with the other qualities in Playback & sound.
        Group(tr(R.string.mobile_data), info = tr(R.string.what_prism_may_use_mobile_data)) {
            Toggle(
                tr(R.string.data_saver), tr(R.string.uses_much_less_data_off_wi), s.dataSaver,
                info = tr(R.string.on_mobile_data_pictures_at_the),
            ) { prefs.setDataSaver(it) }
            // #281: a video is what uses the most data of all.
            if (s.dataSaver) Toggle(
                tr(R.string.no_videos_on_mobile_data), null, s.behavior.noVideoOnData,
                info = tr(R.string.no_videos_on_mobile_data_info),
            ) { prefs.setNoVideoOnData(it) }
            Toggle(
                tr(R.string.load_ahead_on_mobile_data),
                if (s.preload == com.prism.music.data.prefs.Preload.START) tr(R.string.the_next_song_s_start_about) else tr(R.string.whole_songs_about_3_8_mb),
                s.preloadCellular,
                info = tr(R.string.off_songs_are_only_loaded_ahead),
            ) { prefs.setPreloadCellular(it) }
            if (s.smartDownloads) Toggle(tr(R.string.smart_downloads_on_mobile_data), tr(R.string.about_3_8_mb_a_song), s.smartDownloadsCellular) {
                prefs.setSmartDownloadsCellular(it)
                // The worker's network rule is fixed when it's scheduled, so it's scheduled again with the new one.
                SmartDownloadWorker.schedule(context, true, cellular = it)
                SmartDownloadWorker.runNow(context, force = false, cellular = it)
                c.downloads.refreshSmartIds()
            }
            Toggle(
                tr(R.string.lyrics_on_mobile_data), if (s.lyricsOnMobileData) tr(R.string.looked_up_as_each_song_starts) else tr(R.string.only_once_you_open_the_lyrics), s.lyricsOnMobileData,
                info = tr(R.string.off_prism_doesn_t_look_up),
            ) { prefs.setLyricsOnMobileData(it) }
            if (s.canvasEnabled) Toggle(tr(R.string.animated_covers_on_mobile_data), tr(R.string.saved_covers_play_either_way), s.canvasOnCellular) { prefs.setCanvasOnCellular(it) }
            if (s.canvasEnabled && s.canvasWithDownloads) Toggle(tr(R.string.save_animated_covers_on_mobile_data), tr(R.string.otherwise_they_wait_for_wi_fi), s.canvasWithDownloadsCellular) {
                prefs.setCanvasWithDownloadsCellular(it)
                if (it) c.downloads.saveCanvases()
            }
            if (s.normalize || s.mix) Toggle(
                tr(R.string.measure_songs_on_mobile_data), tr(R.string.for_normalize_volume_and_crossfade_1), s.mixTempoOnCellular,
                info = tr(R.string.normalize_volume_and_crossfade_measure_each),
            ) { prefs.setMixTempoOnCellular(it) }
            Toggle(
                tr(R.string.get_songs_ready_for_sing_on), tr(R.string.a_few_mb_a_song), s.singOnCellular,
                info = tr(R.string.sing_needs_each_song_whole_on),
            ) { prefs.setSingOnCellular(it) }
        }
    }
}

// ---------------------------------------------------------------------------- Library & Replay

private val libraryTabs = listOf("PLAYLISTS" to tr(R.string.playlists), "SONGS" to tr(R.string.songs), "ALBUMS" to tr(R.string.albums), "ARTISTS" to tr(R.string.artists), "HISTORY" to tr(R.string.history), "DOWNLOADS" to tr(R.string.downloads))

/** Customize Prism → Home & Library (1.7.6): how Home and Library are laid out. */
@Composable
private fun HomeLibraryLook() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.home)) {
            Item(tr(R.string.customize_home), tr(R.string.sections_the_top_of_home_shortcuts), onClick = { nav.go(Routes.CATALOGUE) }) { Chevron() }
            Toggle(tr(R.string.add_new_sections_automatically), tr(R.string.new_youtube_music_shelves_appear_on), s.autoAddSections) { prefs.setAutoAddSections(it) }
            Toggle(
                tr(R.string.continue_line), tr(R.string.continue_under_the_greeting), s.homeContinue,
                info = tr(R.string.the_album_you_were_last_playing),
            ) { prefs.setHomeContinue(it) }
            // #349: also on Customize Home; here too, where Settings' search opens it.
            Label(tr(R.string.quick_picks_from), info = tr(R.string.quick_picks_from_info))
            Chips(com.prism.music.data.prefs.QuickPicksSource.entries, s.behavior.quickPicksSource, { it.label }) { prefs.setQuickPicksSource(it) }
            Label(tr(R.string.recently_played_shows), info = tr(R.string.every_song_you_played_or_the))
            Chips(com.prism.music.data.prefs.RecentShows.entries, s.behavior.recentShows, { it.label }) { prefs.setRecentShows(it) }
            // 1.8.0: the two top-bar switches as one choice (same stored settings, HomeChoices).
            Label(tr(R.string.buttons_at_the_top_of_home), info = tr(R.string.off_podcasts_opens_from_library_playlists))
            Chips(TopButtons.entries, HomeChoices.topButtons(s), { it.label }) { HomeChoices.setTopButtons(prefs, it) }
            Toggle(tr(R.string.customize_button_at_the_bottom), tr(R.string.at_the_end_of_home), s.homeCustomizeButton) { prefs.setHomeCustomizeButton(it) }
        }
        // Search's own option (1.8.0: from Controls & gestures, it isn't a gesture).
        Group(tr(R.string.search)) {
            Toggle(tr(R.string.open_the_keyboard), tr(R.string.ready_to_type_when_search_opens), s.searchKeyboard) { prefs.setSearchKeyboard(it) }
        }
        val look = ReplayCardStyle.entries.firstOrNull { it.name == s.replayCard } ?: ReplayCardStyle.WASH
        Group(tr(R.string.replay_card), info = tr(R.string.how_replay_looks_on_home_from, look.label, look.note)) {
            Chips(ReplayCardStyle.entries, look, { it.label }) { prefs.setReplayCard(it.name) }
            // #286: what the bars are (the looks with bars show it below, as the card would).
            Label(tr(R.string.replay_card_graph), info = tr(R.string.replay_card_graph_info))
            Chips(com.prism.music.data.prefs.ReplayGraph.entries, s.view.replayGraph, { it.label }) { prefs.setReplayGraph(it) }
            ReplayCardPreview(look, Modifier.padding(top = 6.dp, bottom = 14.dp))
        }
        LibraryLookGroup()
    }
}

/** Settings → Recommendations & Replay (1.7.6): what Prism suggests and from where, and your listening recap. */
/** Last.fm scrobbling (#133, #160): connect your account in the browser, then what's sent. */
@Composable
private fun LastFmGroup() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val lastFm = c.lastFm
    val user by lastFm.user.collectAsState()
    val o by lastFm.options.collectAsState()
    val waiting by lastFm.waiting.collectAsState()
    Group(
        "Last.fm",
        info = tr(R.string.sends_what_you_listen_to_scrobbles_all),
    ) {
        if (!lastFm.available) {
            Note(tr(R.string.this_copy_of_prism_was_built))
            return@Group
        }
        if (user == null) {
            Item(tr(R.string.connect_last_fm), tr(R.string.opens_last_fm_to_let_prism), onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(lastFm.authUrl()))) }
            }) { Chevron() }
            return@Group
        }
        Toggle(tr(R.string.scrobble), tr(R.string.connected_as, user) + if (waiting > 0) tr(R.string.waiting_to_send, waiting) else "", o.scrobble) { lastFm.setOptions(o.copy(scrobble = it)) }
        Toggle(
            tr(R.string.first_artist_only), tr(R.string.a_b_c_goes_to_last), o.firstArtistOnly,
            info = tr(R.string.last_fm_matches_one_artist_per),
        ) { lastFm.setOptions(o.copy(firstArtistOnly = it)) }
        Toggle(tr(R.string.love_when_i_like_a_song), tr(R.string.liking_here_loves_it_on_last), o.loveOnLike) { lastFm.setOptions(o.copy(loveOnLike = it)) }
        Item(tr(R.string.disconnect), tr(R.string.stops_scrobbling_and_forgets_the_connection), onClick = { lastFm.signOut() })
    }
}

@Composable
private fun RecommendationsPage() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.made_for_you)) {
            Item(tr(R.string.your_artists), tr(R.string.they_shape_your_mixes_and_release), onClick = { nav.go(Routes.PICK_ARTISTS) }) { Chevron() }
            Toggle(tr(R.string.new_release_notifications), tr(R.string.when_a_starred_artist_puts_out), s.releaseNotifications) { prefs.setReleaseNotifications(it) }
            Item(tr(R.string.make_my_mixes_again), tr(R.string.fresh_daily_mixes_now), onClick = {
                c.scope.launch { c.forYou.refresh(force = true) }
                android.widget.Toast.makeText(context, tr(R.string.making_your_mixes), android.widget.Toast.LENGTH_SHORT).show()
            })
            Item(tr(R.string.exclusions), tr(R.string.what_s_kept_out_of_your), info = tr(R.string.artists_not_to_play_and_playlists), onClick = { nav.go(Routes.EXCLUSIONS) }) { Chevron() }
            // Suggestions skipped at once stay out of mixes and radio for a while (1.7.9): this lets them all back.
            Item(tr(R.string.forget_skipped_suggestions), tr(R.string.let_every_song_be_suggested_again), info = tr(R.string.forget_skipped_suggestions_info), onClick = {
                c.recFeedback.clear()
                android.widget.Toast.makeText(context, tr(R.string.suggestions_start_fresh), android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        // 1.8.2: each slider goes by what the phone really knows, and says so behind its ⓘ.
        Group(tr(R.string.tune_radios), info = tr(R.string.tune_radios_info)) { RadioTuneSliders() }
        Group(tr(R.string.liked_songs)) {
            Toggle(
                tr(R.string.a_song_and_its_video_as), tr(R.string.liked_both_they_show_once_as), s.mergeLikedVideos,
                info = tr(R.string.when_you_ve_liked_a_song),
            ) { prefs.setMergeLikedVideos(it) }
        }
        // 1.8.1: histories from Spotify, YouTube Music (Takeout) and Last.fm.
        val imported by c.imports.summaries.collectAsState(emptyList())
        val teach by c.imports.teach.collectAsState()
        val counted by c.imports.counted.collectAsState()
        Group(tr(R.string.imp_title), info = tr(R.string.imp_group_info)) {
            Item(tr(R.string.imp_title), tr(R.string.imp_row_detail), onClick = { nav.go(Routes.BRING_HISTORY) }) { Chevron() }
            if (imported.isNotEmpty()) Toggle(
                tr(R.string.imp_shape_mixes), tr(R.string.imp_shape_mixes_detail), teach, info = tr(R.string.imp_shape_mixes_info),
            ) { c.imports.setTeach(it) }
        }
        // 1.8.1: pause history (#296) and when a song counts as played (#263).
        ListeningHistoryGroup()
        // 1.8.0: the Last.fm link here is gone (its page is in Devices & connections; Settings search finds it).
        Group(tr(R.string.replay_2)) {
            Toggle(tr(R.string.show_time_in_hours), if (s.replayHours) tr(R.string.like_20h_4m) else tr(R.string.like_1_204_min), s.replayHours) { prefs.setReplayHours(it) }
            Toggle(tr(R.string.use_theme_colors), tr(R.string.your_accent_instead_of_prism_s), s.replayThemeColors) { prefs.setReplayThemeColors(it) }
            // #287: the stories' type, each shown as a slide would set it.
            Label(tr(R.string.replay_story_font), info = tr(R.string.replay_story_font_info))
            ChoiceCards(
                listOf(false, true), s.view.replayStoryFont,
                { if (it) tr(R.string.replay_story_font_own) else tr(R.string.replay_story_font_poster) }, { null },
                onSelect = { prefs.setReplayStoryFont(it) },
            ) { StoryTypeSample(it) }
            Toggle(tr(R.string.rewind_notification), tr(R.string.on_december_1_when_your_year), s.rewindNotification) { prefs.setRewindNotification(it) }
            // Prism's plays always count; each imported source only when switched on (off by default: Replay as before).
            // 1.8.1 (owner): every source is listed; one not brought in (or a live one that's off) is greyed, with the way to it.
            CountHistoryFrom(imported, counted)
        }
        // Region moved to the Language page (1.8.0).
    }
}

/**
 * Tune a radio (1.8.2): three sliders over every radio and autoplay, each with both its ends named. In the middle (where
 * they start) a radio plays as it comes. Halfway to an end is a real step: see [com.prism.music.data.foryou.RadioTuner].
 */
@Composable
private fun RadioTuneSliders() {
    val c = LocalContainer.current
    val tune = LocalAppSettings.current.behavior.radioTune
    fun word(v: Float, low: String, high: String) = when {
        kotlin.math.abs(v) < 0.05f -> tr(R.string.tune_as_it_comes)
        v < 0 -> worded(low, -v)
        else -> worded(high, v)
    }
    // Close to the middle is the middle: "as it comes" has to be easy to get back to.
    fun snap(v: Float) = if (kotlin.math.abs(v) < 0.05f) 0f else v
    SliderSetting(
        tr(R.string.tune_familiar_new), tune.newness, -1f..1f, { word(it, tr(R.string.tune_familiar), tr(R.string.tune_new)) }, steps = 7,
        info = tr(R.string.tune_familiar_new_info),
    ) { c.settings.setRadioTune(tune.copy(newness = snap(it))) }
    SliderSetting(
        tr(R.string.tune_hits_deep), tune.depth, -1f..1f, { word(it, tr(R.string.tune_hits), tr(R.string.tune_deep)) }, steps = 7,
        info = tr(R.string.tune_hits_deep_info),
    ) { c.settings.setRadioTune(tune.copy(depth = snap(it))) }
    SliderSetting(
        tr(R.string.tune_calm_energetic), tune.energy, -1f..1f, { word(it, tr(R.string.tune_calm), tr(R.string.tune_energetic)) }, steps = 7,
        info = tr(R.string.tune_calm_energetic_info),
    ) { c.settings.setRadioTune(tune.copy(energy = snap(it))) }
    // How much the last one has to go by: a figure, not an explanation.
    if (tune.energy != 0f) {
        val measured = remember { c.mixTempo.measuredCount() }
        Note(tr(R.string.tune_measured_songs, measured))
    }
}

/**
 * "Count history from" (1.8.1): Prism's plays (always), then every source in order. One with plays here (or a live one
 * that's on) has its switch; the others are greyed with "Bring it in" (Bring your history) or "Turn on" (the live ones'
 * own switches), and never count.
 */
@Composable
private fun CountHistoryFrom(imported: List<com.prism.music.data.db.ImportSummary>, counted: Set<com.prism.music.data.imports.ImportSource>) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val ytLive by c.imports.youtubeLive.on.collectAsState()
    // Spotify's connection is only built when it's connected (most never connect; see Spotify.pageOpened).
    val spotifyConnected = remember {
        com.prism.music.data.spotify.SpotifyStore.connectedIn(
            context.getSharedPreferences(com.prism.music.data.spotify.SpotifyStore.AUTH_PREFS, android.content.Context.MODE_PRIVATE),
        )
    }
    val spotifyLive = if (spotifyConnected) c.spotify.options.collectAsState().value.recent else false
    val liveOn = buildSet {
        if (ytLive) add(com.prism.music.data.imports.ImportSource.YOUTUBE_LIVE)
        if (spotifyLive) add(com.prism.music.data.imports.ImportSource.SPOTIFY_LIVE)
    }
    val count = java.text.NumberFormat.getIntegerInstance()
    Label(tr(R.string.imp_count_history_from), info = tr(R.string.imp_count_history_info))
    Item(tr(R.string.imp_prism_plays), tr(R.string.imp_always_counted), onClick = {}) {
        Icon(PrismIcons.Check, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    com.prism.music.data.imports.CountSources.rows(imported, liveOn, counted).forEach { row ->
        val source = row.source
        if (row.state == com.prism.music.data.imports.CountSources.State.COUNTABLE) {
            val detail = if (row.plays > 0) tr(R.string.imp_n_plays, count.format(row.plays)) else tr(R.string.imp_live_no_plays)
            Toggle(source.label, detail, row.counted) { c.imports.setCounted(source, it) }
        } else {
            val go = {
                if (source == com.prism.music.data.imports.ImportSource.SPOTIFY_LIVE) nav.go(Routes.settings(SettingsSection.SPOTIFY.key))
                else nav.go(Routes.BRING_HISTORY)
            }
            MissingSource(source.label, if (source.isLive) tr(R.string.off) else tr(R.string.imp_not_brought_in), if (source.isLive) tr(R.string.imp_turn_on) else tr(R.string.imp_bring_it_in), go)
        }
    }
}

/** A source with nothing to count yet: its name greyed, and the way to bring it in. */
@Composable
private fun MissingSource(title: String, detail: String, action: String, onClick: () -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = ui.gap(8.dp), bottom = ui.gap(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(0.5f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        com.prism.music.ui.components.PrismTextButton(onClick = onClick) { Text(action) }
    }
}

/** How Library shows its playlists, albums and artists. */
@Composable
private fun LibraryLookGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.library)) {
            // Each tab its own way (1.7.6); the button at the top of Library switches the tab you're on.
            listOf("PLAYLISTS" to tr(R.string.playlists_as), "ALBUMS" to tr(R.string.albums_as), "ARTISTS" to tr(R.string.artists_as)).forEach { (tab, title) ->
                Label(title)
                Chips(LibraryView.entries, s.libraryViews[tab] ?: s.libraryView, { it.label }) { prefs.setLibraryTabView(tab, it) }
            }
            Label(tr(R.string.covers_across), info = tr(R.string.in_the_grid_auto_fits_as))
            Chips(com.prism.music.data.prefs.LibraryColumns.entries, s.libraryColumns, { it.label }) { prefs.setLibraryColumns(it) }
            Toggle(tr(R.string.details_under_each_one), tr(R.string.song_counts_owners_and_years), s.librarySubtitles) { prefs.setLibrarySubtitles(it) }
            Label(tr(R.string.open_library_on))
            // "LOCAL" (On this phone) was a tab before Local files got its own page: Playlists stands in for it.
            val startTab = s.libraryStartTab.takeIf { k -> libraryTabs.any { it.first == k } } ?: "PLAYLISTS"
            Chips(libraryTabs.map { it.first }, startTab, { k -> libraryTabs.firstOrNull { it.first == k }?.second ?: k }) { prefs.setLibraryStartTab(it) }
            Spacer(Modifier.height(6.dp))
            Toggle(tr(R.string.full_width_playlist_pictures), tr(R.string.edge_to_edge_like_an_artist), s.playlistHeroCover) { prefs.setPlaylistHeroCover(it) }
            Toggle(tr(R.string.genres_on_playlists), tr(R.string.genres_on_playlists_detail), s.behavior.playlistGenres) { prefs.setPlaylistGenres(it) }
            // #310 (1.8.1): off, Downloads lists each song with just its artist under it.
            Toggle(
                tr(R.string.album_names_in_downloads), tr(R.string.album_names_in_downloads_detail), s.view.downloadsAlbums,
                info = tr(R.string.album_names_in_downloads_info),
            ) { prefs.setDownloadsAlbums(it) }
            var editingTiles by remember { mutableStateOf(false) }
            Item(
                tr(R.string.library_tiles), tr(R.string.of_shown_pick_and_order_them, s.libraryTiles.size, com.prism.music.data.prefs.LibraryTile.offered(com.prism.music.ui.theme.LocalContainer.current.uploads.has.value).size),
                onClick = { editingTiles = true },
            ) { Chevron() }
            if (editingTiles) LibraryTilesSheet { editingTiles = false }
        }
        Group(tr(R.string.artist_pages)) {
            Toggle(tr(R.string.artist_you_title), tr(R.string.artist_you_detail), s.behavior.artistYou, info = tr(R.string.artist_you_info)) { prefs.setArtistYou(it) }
            Toggle(tr(R.string.artist_essentials_title), tr(R.string.artist_essentials_detail), s.behavior.artistEssentials, info = tr(R.string.artist_essentials_info)) { prefs.setArtistEssentials(it) }
            Toggle(tr(R.string.artist_bio_title), tr(R.string.artist_bio_detail), s.behavior.artistBio, info = tr(R.string.artist_bio_info)) { prefs.setArtistBio(it) }
        }
    }
}

/** What the car shows right now (or would), drawn by the same code that draws it for the car. */
@Composable
private fun CarLyricsPreview(style: CarLyricsStyle) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val song by c.player.currentSong.collectAsState()
    var lyrics by remember { mutableStateOf<com.prism.music.data.lyrics.Lyrics?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var bg by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var card by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song?.id) {
        lyrics = null; card = null; line = null; loaded = false
        val s = song ?: return@LaunchedEffect
        lyrics = runCatching { c.lyrics.auto(s) }.getOrNull()?.takeIf { it.synced }
        loaded = true
        bg = runCatching { com.prism.music.playback.CarLyricsCard.background(context, s.thumbnail) }.getOrNull()
    }
    LaunchedEffect(lyrics, style, bg) {
        val l = lyrics ?: return@LaunchedEffect
        while (true) {
            val pos = c.player.lyricPosition
            var idx = com.prism.music.playback.CarLyricsCard.lineAt(l.lines, pos)
            if (idx < 0 || l.lines[idx].isGap) idx = l.lines.indexOfFirst { !it.isGap && it.text.isNotBlank() && it.timeMs >= pos }.takeIf { it >= 0 } ?: idx
            val ln = l.lines.getOrNull(idx)
            if (ln != null && !ln.isGap) {
                val sung = if (style == CarLyricsStyle.KARAOKE && ln.isWordSynced) ln.words.count { it.startMs <= pos } else -1
                line = ln.text
                card = if (style == CarLyricsStyle.TEXT) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.prism.music.playback.CarLyricsCard.render(bg, l.lines, idx, sung).asImageBitmap()
                }
            }
            kotlinx.coroutines.delay(300)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = LocalUi.current.shape(14.dp)
        val img = card
        if (img != null) androidx.compose.foundation.Image(img, tr(R.string.lyric_card), Modifier.size(132.dp).clip(shape))
        else com.prism.music.ui.components.Artwork(song?.thumbnail, Modifier.size(132.dp), shape, size = 300)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            val s = song
            when {
                s == null -> Text(tr(R.string.play_a_song_to_see_how), style = MaterialTheme.typography.bodyMedium)
                loaded && lyrics == null -> {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(tr(R.string.no_synced_lyrics_for_this_song), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(line ?: s.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    Text("${s.title} · ${s.artistText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

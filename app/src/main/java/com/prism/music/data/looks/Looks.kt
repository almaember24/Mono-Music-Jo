package com.prism.music.data.looks

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.prefs.LookValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The icon style a Look carries (Appearance → Icons & shapes; kept apart from the settings, see `IconStyle`). */
@Serializable
data class LookIcons(
    val pack: String = "PRISM", val weight: String = "REGULAR", val rounded: Boolean = true, val likeStar: Boolean = false,
    /** Outline, duotone or filled (1.7.7; Looks saved before carry the default). */
    val fill: String = "OUTLINE",
)

/**
 * A Look (1.7.3): a named snapshot of how Prism looks, every setting in `SettingsRepository.lookSpec` as tagged text
 * ([LookValue]; one it doesn't hold is at Prism's default), plus the icon style and, when it uses one, the listener's
 * own font. Photos it uses as backgrounds are kept with it as `PHOTO|asset:<hash>` ([LookStore] keeps the files), so a
 * Look never points at a file that can go away.
 */
@Serializable
data class Look(
    val id: String,
    val name: String,
    val values: Map<String, String> = emptyMap(),
    val icons: LookIcons = LookIcons(),
    /** The app font file's asset hash, when the Look uses the listener's own font. */
    val font: String? = null,
    val created: Long = 0,
    /** One of Prism's own ([BuiltInLooks]): it can be applied and copied, not changed or deleted. */
    val builtIn: Boolean = false,
    /** What it is, in a few words (Prism's own Looks). */
    val note: String = "",
    val format: Int = 1,
) {
    /** The asset hashes this Look's backgrounds use. */
    val photoAssets: List<String> get() = Backgrounds.parse(values[BACKDROPS]).values.mapNotNull(Backgrounds::assetOf)

    companion object {
        const val BACKDROPS = "backdrops"
        const val FONT_CHOICE = "font_choice"
        const val APP_FONT_NAME = "app_font_name"
        const val LYRICS_FONT = "lyrics_font"
        const val MAX_NAME = 40
    }
}

/** The per-screen backgrounds a Look keeps, stored as one JSON map ("HOME" → "AURORA" or "PHOTO|…"). */
object Backgrounds {
    private val json = Json { ignoreUnknownKeys = true }
    const val ASSET = "asset:"

    fun parse(tagged: String?): Map<String, String> =
        LookValue.text(tagged)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

    fun tag(map: Map<String, String>): String = "s:" + json.encodeToString<Map<String, String>>(map)

    /** The asset hash a background's photo is, or null when it isn't a kept photo. */
    fun assetOf(v: String): String? = v.takeIf { it.startsWith("PHOTO|$ASSET") }?.substringAfter(ASSET)?.takeIf(::isHash)

    fun isHash(s: String) = s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * #378: from 1.7.9 until 1.8.2.1 a photo was saved under the word's translation in 8 languages ("FOTO|/…/all-1.jpg").
     * [v] with the saved word put right; anything else as it is.
     */
    fun photoKeyFixed(v: String): String {
        val word = v.substringBefore('|', "")
        val path = v.substringAfter('|', "")
        return if (word.isNotEmpty() && word != "PHOTO" && path.startsWith("/")) "PHOTO|$path" else v
    }
}

/**
 * Looks as text to share (`PRISMLOOK1:` and the Look deflated, in URL-safe Base64), like Blend codes: small enough for a
 * QR code when it has no photo or font of its own ([MAX_QR_CHARS]). Only the name, the look settings and the icon style
 * travel; reading one keeps only settings a Look may hold, each with the right kind of value ([sanitized]).
 */
object LookCode {
    const val PREFIX = "PRISMLOOK1:"
    /** What fits in a QR code at medium error correction, with room to spare. */
    const val MAX_QR_CHARS = 1_800
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Wire(val n: String, val v: Map<String, String> = emptyMap(), val i: LookIcons = LookIcons(), val f: String? = null)

    fun encode(look: Look): String {
        val raw = json.encodeToString(Wire.serializer(), Wire(look.name, look.values, look.icons, look.font)).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The Look code somewhere in [text] (a message, a scanned QR code), or null. */
    fun findIn(text: String?): String? {
        val t = text ?: return null
        val at = t.indexOf(PREFIX)
        if (at < 0) return null
        val body = t.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (body.isEmpty()) null else PREFIX + body
    }

    /** The Look in [text], checked against [kinds] (what a Look may set, and as what), or null if there isn't one. */
    fun decode(text: String?, kinds: Map<String, Char>): Look? = runCatching {
        val code = findIn(text) ?: return null
        val bytes = Base64.getUrlDecoder().decode(code.removePrefix(PREFIX))
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024) return null
        }
        inflater.end()
        val w = json.decodeFromString(Wire.serializer(), out.toString(Charsets.UTF_8.name()))
        sanitized(Look(id = "", name = w.n, values = w.v, icons = w.i, font = w.f?.takeIf(Backgrounds::isHash)), kinds)
    }.getOrNull()

    /**
     * [look] with only what a Look may hold: known settings with the right kind of value, backgrounds that are designs
     * or kept photos (never a path on someone's phone), a name of a sensible length and a known icon style.
     */
    fun sanitized(look: Look, kinds: Map<String, Char>): Look {
        val values = look.values.filter { (k, v) -> kinds[k]?.let { LookValue.untag(v, it) } != null }.toMutableMap()
        values[Look.BACKDROPS]?.let { b ->
            val kept = Backgrounds.parse(b).filter { (screen, v) ->
                // "|" only ever comes with a photo, under any word (#378): a design's name never has one.
                screen.length <= 16 && ('|' !in v && v.length <= 32 || Backgrounds.assetOf(v) != null)
            }
            if (kept.isEmpty()) values.remove(Look.BACKDROPS) else values[Look.BACKDROPS] = Backgrounds.tag(kept)
        }
        val icons = look.icons.let { i ->
            LookIcons(
                pack = i.pack.takeIf { p -> com.prism.music.ui.theme.IconPack.entries.any { it.name == p } } ?: "PRISM",
                weight = i.weight.takeIf { w -> w in setOf("THIN", "REGULAR", "BOLD") } ?: "REGULAR",
                rounded = i.rounded, likeStar = i.likeStar,
                fill = i.fill.takeIf { f -> com.prism.music.ui.theme.IconFill.entries.any { it.name == f } } ?: "OUTLINE",
            )
        }
        val name = look.name.filter { !it.isISOControl() }.trim().take(Look.MAX_NAME).ifBlank { tr(R.string.a_look) }
        return look.copy(name = name, values = values, icons = icons)
    }
}

/** Prism's own Looks, each showing off what can be changed. The Prism Look is how Prism ships. */
object BuiltInLooks {
    private fun color(argb: Long) = "i:${argb.toInt()}"
    private fun everywhere(style: String) = Backgrounds.tag(listOf("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS").associateWith { style })

    val all: List<Look> = listOf(
        Look("prism", tr(R.string.app_name), builtIn = true, note = tr(R.string.how_prism_comes_outfit_the_split)),
        Look(
            "paper", tr(R.string.paper), builtIn = true, note = tr(R.string.light_serif_type_ink_instead_of),
            values = mapOf(
                "theme_mode" to "s:LIGHT", "font_choice" to "s:SERIF", "light_style" to "s:WHITE", "color_source" to "s:CUSTOM",
                "accent" to color(0xFF656565), "surface_tint" to "s:CUSTOM", "surface_color" to color(0xFFBDBDBD),
                "surface_tint_strength" to "f:1.6", "corners" to "s:SUBTLE", "backdrops" to everywhere("SAND"),
            ),
        ),
        Look(
            "midnight", tr(R.string.midnight), builtIn = true, note = tr(R.string.pure_black_thin_icons_a_deep),
            values = mapOf(
                "theme_mode" to "s:DARK", "pure_black" to "b:1", "color_source" to "s:CUSTOM", "accent" to color(0xFF616161),
                "spectrum_preset" to "s:OCEAN",
            ),
            icons = LookIcons(weight = "THIN"),
        ),
        Look(
            "neon", tr(R.string.neon), builtIn = true, note = tr(R.string.mono_type_neon_light_sharp_corners),
            values = mapOf(
                "theme_mode" to "s:DARK", "font_choice" to "s:MONO", "spectrum_preset" to "s:NEON", "corners" to "s:SHARP",
                "color_source" to "s:CUSTOM", "accent" to color(0xFF787878), "accent_exact" to "b:1", "backdrops" to everywhere("NEON"),
            ),
        ),
        Look(
            "soft", tr(R.string.soft), builtIn = true, note = tr(R.string.rounded_type_extra_round_corners_pastels),
            values = mapOf(
                "font_choice" to "s:ROUNDED", "corners" to "s:EXTRA", "spectrum_preset" to "s:CANDY", "color_source" to "s:CUSTOM",
                "accent" to color(0xFFAFAFAF), "backdrops" to everywhere("ROSE"),
            ),
        ),
        Look(
            "studio", tr(R.string.studio), builtIn = true, note = tr(R.string.white_light_no_accent_compact_classic),
            values = mapOf(
                "light_style" to "s:WHITE", "color_source" to "s:CUSTOM", "accent" to color(0xFFFFFFFF), "ui_density" to "s:COMPACT",
                "transport_style" to "s:GLYPHS", "font_choice" to "s:CLEAN", "corners" to "s:SUBTLE",
            ),
        ),
        // Waited for 1.7.4's Vinyl player (planned with the Looks in 1.7.3).
        Look(
            "vinyl", tr(R.string.vinyl), builtIn = true, note = tr(R.string.a_record_on_the_player_warm),
            values = mapOf(
                "theme_mode" to "s:DARK", "font_choice" to "s:SERIF", "spectrum_preset" to "s:GOLD", "color_source" to "s:CUSTOM",
                "accent" to color(0xFFACACAC), "player_layout" to "s:VINYL", "vinyl_tonearm" to "b:1", "player_background" to "s:BLUR",
                "player_title_align" to "s:CENTER", "scrubber_color" to "s:ACCENT", "transport_style" to "s:OUTLINE", "player_times" to "s:BOTH",
            ),
        ),
        // Prism 1.6 (#155): its icons, type, controls and plain color on today's screens, with every 1.7 feature.
        // White light with the accent puts the accent where the split light would be (progress, play, the tab).
        Look(
            "classic", tr(R.string.classic), builtIn = true, note = tr(R.string.prism_1_6_s_look_its),
            values = mapOf(
                "font_choice" to "s:SYSTEM", "type_classic" to "b:1", "controls_classic" to "b:1", "corners" to "s:ROUND",
                "light_style" to "s:WHITE", "liquid_glass" to "b:0", "frosted_glass" to "b:0", "frosted_pages" to "b:0",
                "nav_style" to "s:DOCKED", "nav_labels" to "s:ALWAYS", "mini_player_style" to "s:CARD", "mini_progress" to "s:LINE",
                "transport_style" to "s:BUTTON", "scrubber_style" to "s:HAIRLINE", "scrubber_color" to "s:ACCENT", "player_art_corners" to "i:8",
                "player_buttons_pill" to "b:0", "replay_card" to "s:QUIET",
            ),
            icons = LookIcons(pack = "CLASSIC"),
        ),
        // Seasonal (1.8.0, [Seasons]): only offered in their month, and put away when it ends.
        Look(
            Season.HARVEST.lookId, tr(R.string.harvest), builtIn = true, note = tr(R.string.harvest_note),
            values = mapOf(
                "color_source" to "s:CUSTOM", "accent" to color(0xFF7B7B7B), "spectrum_preset" to "s:${Season.HARVEST.light}",
                "surface_tint" to "s:CUSTOM", "surface_color" to color(0xFF636363), "surface_tint_strength" to "f:1.3",
                "backdrops" to everywhere(Season.HARVEST.backdrop),
            ),
        ),
        Look(
            Season.HOLIDAY.lookId, tr(R.string.holiday), builtIn = true, note = tr(R.string.holiday_note),
            values = mapOf(
                "color_source" to "s:CUSTOM", "accent" to color(0xFF575757), "spectrum_preset" to "s:${Season.HOLIDAY.light}",
                "surface_tint" to "s:CUSTOM", "surface_color" to color(0xFF474747), "surface_tint_strength" to "f:1.2",
                "backdrops" to everywhere(Season.HOLIDAY.backdrop),
            ),
        ),
    )

    fun of(id: String): Look? = all.firstOrNull { it.id == id }

    /** Prism's Looks to offer on [date]: all but a seasonal one outside its month. */
    fun shown(date: java.time.LocalDate = Seasons.today()): List<Look> = all.filter { Seasons.shown(it.id, date) }
}

/** How a look setting and its value read in the undo bar and History ("Corners → Sharp"). */
object LookLabels {
    private val titles = mapOf(
        "glass_blur" to tr(R.string.glass_blur), "glass_frost" to tr(R.string.glass_frost), "glass_more" to tr(R.string.glass_on_more_of_the_screen), "player_gradient_depth" to tr(R.string.gradient_depth),
        "theme_mode" to tr(R.string.theme), "pure_black" to tr(R.string.pure_black), "color_source" to tr(R.string.accent_from), "accent" to tr(R.string.accent_color),
        "light_style" to tr(R.string.light), "accent_exact" to tr(R.string.exactly_as_picked), "accent_light" to tr(R.string.light_mode_accent), "spectrum_preset" to tr(R.string.spectrum),
        "spectrum_stops" to tr(R.string.your_light_s_colors), "spectrum_bands" to tr(R.string.spectrum_in_bands), "surface_tint" to tr(R.string.page_background),
        "surface_tint_strength" to tr(R.string.background_strength), "surface_color" to tr(R.string.background_color), "contrast_level" to tr(R.string.contrast),
        "artwork_swatch" to tr(R.string.cover_color), "artwork_vivid" to tr(R.string.always_colorful), "artwork_change" to tr(R.string.between_songs),
        "page_picture_accent" to tr(R.string.pages_in_their_picture_s_color), "playing_page_color" to tr(R.string.playing_song_in_the_page_s_color), "liquid_glass" to tr(R.string.liquid_glass), "glass_kind" to tr(R.string.glass_style),
        "frosted_glass" to tr(R.string.frosted_glass), "frosted_pages" to tr(R.string.frosted_pages), "backdrops" to tr(R.string.backgrounds), "backdrop_dim" to tr(R.string.background_softness),
        "backdrop_motion" to tr(R.string.gentle_motion), "reduce_motion" to tr(R.string.reduce_motion), "font_choice" to tr(R.string.font), "app_font_name" to tr(R.string.your_font_2),
        "lyrics_font" to tr(R.string.lyrics_font), "text_scale" to tr(R.string.text_size), "corners" to tr(R.string.corners), "ui_density" to tr(R.string.spacing), "title_size" to tr(R.string.page_titles),
        "card_size" to tr(R.string.cards), "nav_style" to tr(R.string.tab_bar), "nav_labels" to tr(R.string.tab_labels), "nav_hide_on_scroll" to tr(R.string.shrink_while_scrolling),
        "nav_tabs" to tr(R.string.tabs), "tab_names" to tr(R.string.tab_names), "nav_bar_size" to tr(R.string.tab_bar_height), "nav_bar_fill" to tr(R.string.tab_bar_background),
        "mini_player_joined" to tr(R.string.mini_player_joined_to_the_tab), "start_tab" to tr(R.string.start_tab_2), "mini_player_style" to tr(R.string.mini_player), "mini_skip_buttons" to tr(R.string.mini_player_s_next),
        "mini_prev_button" to tr(R.string.mini_player_s_previous), "mini_play_button" to tr(R.string.mini_player_s_play_pause), "mini_progress" to tr(R.string.mini_player_progress), "mini_progress_color" to tr(R.string.mini_player_progress_color), "mini_fill_strength" to tr(R.string.mini_player_fill_strength), "play_ring" to tr(R.string.ring_on_the_play_button), "player_background" to tr(R.string.player_background),
        "player_backdrop_show" to tr(R.string.player_background_showing), "player_art_corners" to tr(R.string.cover_corners), "transport_style" to tr(R.string.controls),
        "cover_size" to tr(R.string.cover_size), "scrubber_style" to tr(R.string.progress_bar), "art_shrink_on_pause" to tr(R.string.cover_shrinks_on_pause),
        "show_output_device" to tr(R.string.output_device), "full_bleed_artwork" to tr(R.string.edge_to_edge_cover), "video_full_bleed" to tr(R.string.edge_to_edge_video),"hide_volume_bar" to tr(R.string.hide_volume_bar),
        "show_playing_from" to tr(R.string.playing_from), "player_shuffle_repeat" to tr(R.string.shuffle_repeat), "immersive_art" to tr(R.string.immersive_cover),
        "immersive_lyrics" to tr(R.string.immersive_lyrics), "immersive_controls" to tr(R.string.immersive_controls), "lyrics_align" to tr(R.string.lyrics_alignment),
        "lyrics_blur" to tr(R.string.lyrics_focus), "lyrics_progress_line" to tr(R.string.lyrics_progress_line),
        "lyrics_source_button" to tr(R.string.lyrics_source_button), "lyrics_scale" to tr(R.string.lyrics_size),
        "library_view" to tr(R.string.library_view), "library_views" to tr(R.string.library_tabs_views), "library_columns" to tr(R.string.library_columns),
        "library_subtitles" to tr(R.string.library_details), "home_header" to tr(R.string.top_of_home), "home_header_height" to tr(R.string.top_of_home_s_height),
        "playlist_hero_cover" to tr(R.string.playlist_picture), "replay_theme_colors" to tr(R.string.replay_in_your_colors),
        "replay_card" to tr(R.string.replay_card),
        "replay_card_graph" to tr(R.string.replay_card_graph), "replay_story_font" to tr(R.string.replay_story_font),
        "player_layout" to tr(R.string.player_layout), "vinyl_tonearm" to tr(R.string.tonearm), "player_deck" to tr(R.string.player_s_order), "player_buttons" to tr(R.string.player_buttons),
        "scrubber_color" to tr(R.string.progress_bar_color), "player_title_align" to tr(R.string.title_alignment), "player_title_size" to tr(R.string.title_size),
        "player_title_scroll" to tr(R.string.scrolling_titles), "player_times" to tr(R.string.times), "mesh_speed" to tr(R.string.mesh_motion), "mesh_intensity" to tr(R.string.mesh_colors),
        "player_blur" to tr(R.string.blur_strength), "canvas_fills_background" to tr(R.string.animated_cover_fills_the_player),
        "scrubber_thickness" to tr(R.string.progress_bar_thickness), "scrubber_thumb" to tr(R.string.progress_bar_knob), "visualizer" to tr(R.string.visualizer),
        "visualizer_color" to tr(R.string.visualizer_color),
        "lyrics_style" to tr(R.string.lyrics_style), "lyrics_blur_amount" to tr(R.string.lyrics_blur), "lyrics_spacing" to tr(R.string.lyrics_line_spacing),
        "lyrics_others_alpha" to tr(R.string.other_lines_brightness), "lyrics_sung_color" to tr(R.string.sung_line_color), "lyrics_sung_custom" to tr(R.string.sung_line_s_own_color),
        "lyrics_other_color" to tr(R.string.other_lines_color), "lyrics_other_custom" to tr(R.string.other_lines_own_color), "lyrics_weight" to tr(R.string.lyrics_weight),
        "lyrics_backing_size" to tr(R.string.background_vocals_size), "lyrics_shadow" to tr(R.string.lyrics_shadow), "lyrics_uppercase" to tr(R.string.lyrics_in_capitals),
        "lyrics_ahead" to tr(R.string.lines_ahead), "lyrics_position" to tr(R.string.sung_line_s_place), "lyrics_extra_size" to tr(R.string.translation_size),
        "lyrics_extra_color" to tr(R.string.translation_color), "lyrics_extra_italic" to tr(R.string.translation_in_italics), "lyrics_extra_place" to tr(R.string.translation_place),
        "lyrics_background" to tr(R.string.lyrics_background), "immersive_background" to tr(R.string.immersive_background), "lyrics_bg_color" to tr(R.string.lyrics_background_color),
        "lyrics_bg_color2" to tr(R.string.lyrics_gradient_s_second_color), "lyrics_everywhere" to tr(R.string.same_lyrics_look_everywhere),
        "lyrics_strip_align" to tr(R.string.lyric_line_on_the_player), "lyrics_strip_two_lines" to tr(R.string.lyric_line_up_to_two_lines),
        "lyrics_gap_mark" to tr(R.string.instrumental_breaks),
        // 1.8.0 lyrics look
        "lyrics_motion" to tr(R.string.lyrics_animation), "lyrics_word_by_word" to tr(R.string.lyrics_word_by_word),
        "lyrics_card" to tr(R.string.lyrics_on_bold_card), "lyrics_halo" to tr(R.string.lyrics_glow), "lyrics_bold_lines" to tr(R.string.bold_card_s_bold_lines),
        "panel_player" to tr(R.string.player_over_lyrics_queue), "queue_headings" to tr(R.string.queue_headings), "queue_dj_chip" to tr(R.string.dj_in_the_queue), "cast_mark" to tr(R.string.cast_mark_under_the_player),
        "immersive_video" to tr(R.string.video_in_immersive_mode), "bleed_fit" to tr(R.string.full_bleed_artwork_s_fit), "cover_flight" to tr(R.string.cover_flies_into_the_player),
        "video_controls_hide" to tr(R.string.controls_hide_over_videos), "crossfade_mark" to tr(R.string.crossfade_mark), "blend_covers" to tr(R.string.covers_blend_in_a_crossfade), "player_buttons_pill" to tr(R.string.player_buttons_in_a_capsule),
        "crossfade_shape" to tr(R.string.crossfade_on_the_progress_bar),
        // 1.8.2 requests (#223, #293, #294, #331).
        "cover_flight_back" to tr(R.string.cover_flies_back_too), "video_buttons_hide" to tr(R.string.buttons_on_videos_wait_for_a_tap),
        "bleed_roomy" to tr(R.string.more_room_for_the_picture), "queue_lengths" to tr(R.string.song_lengths_in_the_queue),
        "queue_played_moves" to tr(R.string.move_songs_you_have_played), "queue_tidy" to tr(R.string.tidier_queue),
        // 1.8.1 requests (#310, #289, #283).
        "downloads_albums" to tr(R.string.album_names_in_downloads), "player_artist_links" to tr(R.string.each_artist_its_own_link),
        "backdrop_fade" to tr(R.string.now_playing_background_fades),
        "hide_status_bar" to tr(R.string.hide_the_status_bar),
        "hide_status_bar_player" to tr(R.string.hide_status_bar_on_the_player),
        "volume_matches_progress" to tr(R.string.volume_bar_like_the_progress_bar),
        "player_handle" to tr(R.string.handle_at_the_top),
        "player_nav_band" to tr(R.string.band_behind_the_navigation_buttons),
        "player_hidden_parts" to tr(R.string.what_s_on_the_player),
        "corner_scale" to tr(R.string.corners), "cover_shape_songs" to tr(R.string.songs_covers), "cover_shape_albums" to tr(R.string.albums_covers),
        "cover_shape_artists" to tr(R.string.artists_pictures), "cover_shape_player" to tr(R.string.player_s_cover), "button_shape" to tr(R.string.buttons),
        "play_outlined" to tr(R.string.outlined_play), "type_weight" to tr(R.string.text_weight), "type_spacing" to tr(R.string.letter_spacing), "title_font" to tr(R.string.titles_font),
        "tab_icon_tint" to tr(R.string.the_current_tab_s_icon), "player_title_weight" to tr(R.string.title_weight), "type_classic" to tr(R.string.classic_type), "controls_classic" to tr(R.string.classic_controls),
    )

    /** Settings whose stored names are enum names, with each name's label. */
    private val choices: Map<String, Map<String, String>> by lazy {
        fun <E : Enum<E>> e(entries: List<E>, label: (E) -> String) = entries.associate { it.name to label(it) }
        val p = com.prism.music.data.prefs.ThemeMode.entries
        mapOf(
            "theme_mode" to e(p) { it.label },
            "color_source" to e(com.prism.music.data.prefs.ColorSource.entries) { it.label },
            "light_style" to e(com.prism.music.data.prefs.LightStyle.entries) { it.label },
            "surface_tint" to e(com.prism.music.data.prefs.SurfaceTint.entries) { it.label },
            "contrast_level" to e(com.prism.music.data.prefs.ContrastLevel.entries) { it.label },
            "artwork_swatch" to e(com.prism.music.data.prefs.ArtworkSwatch.entries) { it.label },
            "artwork_change" to e(com.prism.music.data.prefs.ArtworkChange.entries) { it.label },
            "glass_kind" to e(com.prism.music.data.prefs.GlassKind.entries) { it.label },
            "font_choice" to e(com.prism.music.data.prefs.FontChoice.entries) { it.label },
            "lyrics_font" to e(com.prism.music.data.prefs.FontChoice.entries) { it.label } + ("" to tr(R.string.app_font)),
            "corners" to e(com.prism.music.data.prefs.Corners.entries) { it.label },
            "cover_shape_songs" to e(com.prism.music.data.prefs.CoverShape.entries) { it.label },
            "cover_shape_albums" to e(com.prism.music.data.prefs.CoverShape.entries) { it.label },
            "cover_shape_artists" to e(com.prism.music.data.prefs.CoverShape.entries) { it.label },
            "cover_shape_player" to e(com.prism.music.data.prefs.CoverShape.entries) { it.label },
            "button_shape" to e(com.prism.music.data.prefs.ButtonShape.entries) { it.label },
            "type_weight" to e(com.prism.music.data.prefs.TypeWeight.entries) { it.label },
            "tab_icon_tint" to e(com.prism.music.data.prefs.TabTint.entries) { it.label },
            "player_title_weight" to e(com.prism.music.data.prefs.PlayerTitleWeight.entries) { it.label },
            "type_spacing" to e(com.prism.music.data.prefs.TypeSpacing.entries) { it.label },
            "title_font" to e(com.prism.music.data.prefs.FontChoice.entries) { it.label },
            "ui_density" to e(com.prism.music.data.prefs.UiDensity.entries) { it.label },
            "title_size" to e(com.prism.music.data.prefs.TitleSize.entries) { it.label },
            "card_size" to e(com.prism.music.data.prefs.CardSize.entries) { it.label },
            "nav_style" to e(com.prism.music.data.prefs.NavBarStyle.entries) { it.label },
            "nav_labels" to e(com.prism.music.data.prefs.NavLabels.entries) { it.label },
            "nav_bar_size" to e(com.prism.music.data.prefs.NavBarSize.entries) { it.label },
            "nav_bar_fill" to e(com.prism.music.data.prefs.NavBarFill.entries) { it.label },
            "start_tab" to e(com.prism.music.data.prefs.NavTab.entries) { it.label },
            "mini_player_style" to e(com.prism.music.data.prefs.MiniPlayerStyle.entries) { it.label },
            "mini_progress" to e(com.prism.music.data.prefs.MiniProgress.entries) { it.label },
            "mini_progress_color" to e(com.prism.music.data.prefs.ScrubberColor.entries) { it.label },
            "player_background" to e(com.prism.music.data.prefs.PlayerBackground.entries) { it.label },
            "transport_style" to e(com.prism.music.data.prefs.TransportStyle.entries) { it.label },
            "cover_size" to e(com.prism.music.data.prefs.CoverSize.entries) { it.label },
            "scrubber_style" to e(com.prism.music.data.prefs.ScrubberStyle.entries) { it.label },
            "immersive_art" to e(com.prism.music.data.prefs.ImmersiveArt.entries) { it.label },
            "immersive_lyrics" to e(com.prism.music.data.prefs.ImmersiveLyrics.entries) { it.label },
            "lyrics_align" to e(com.prism.music.data.prefs.LyricsAlign.entries) { it.label },
            "lyrics_strip_align" to e(com.prism.music.data.prefs.LyricsAlign.entries) { it.label },
            "library_view" to e(com.prism.music.data.prefs.LibraryView.entries) { it.label },
            "library_columns" to e(com.prism.music.data.prefs.LibraryColumns.entries) { it.label },
            "home_header" to e(com.prism.music.data.prefs.HomeHeader.entries) { it.label },
            "home_header_height" to e(com.prism.music.data.prefs.HomeHeaderHeight.entries) { it.label },
            "replay_card_graph" to e(com.prism.music.data.prefs.ReplayGraph.entries) { it.label },
            "player_layout" to e(com.prism.music.data.prefs.PlayerLayout.entries) { it.label },
            "scrubber_color" to e(com.prism.music.data.prefs.ScrubberColor.entries) { it.label },
            "player_title_align" to e(com.prism.music.data.prefs.PlayerTitleAlign.entries) { it.label },
            "player_title_size" to e(com.prism.music.data.prefs.PlayerTitleSize.entries) { it.label },
            "player_times" to e(com.prism.music.data.prefs.PlayerTimes.entries) { it.label },
            "player_deck" to e(com.prism.music.data.prefs.DeckPart.entries) { it.label },
            "player_buttons" to e(com.prism.music.data.prefs.PlayerButton.entries) { it.label },
            "scrubber_thumb" to e(com.prism.music.data.prefs.ScrubberThumb.entries) { it.label },
            "visualizer" to e(com.prism.music.data.prefs.VisualizerStyle.entries) { it.label },
            "visualizer_color" to e(com.prism.music.data.prefs.ScrubberColor.entries) { it.label },
            "lyrics_style" to e(com.prism.music.data.prefs.LyricsStyle.entries) { it.label },
            "lyrics_motion" to e(com.prism.music.data.prefs.LyricsMotion.entries) { it.label },
            "lyrics_sung_color" to e(com.prism.music.data.prefs.LyricsColor.entries) { it.label },
            "lyrics_other_color" to e(com.prism.music.data.prefs.LyricsColor.entries) { it.label },
            "lyrics_extra_color" to e(com.prism.music.data.prefs.LyricsColor.entries) { it.label },
            "lyrics_weight" to e(com.prism.music.data.prefs.LyricsWeight.entries) { it.label },
            "lyrics_ahead" to e(com.prism.music.data.prefs.LyricsAhead.entries) { it.label },
            "lyrics_position" to e(com.prism.music.data.prefs.LyricsPosition.entries) { it.label },
            "lyrics_extra_place" to e(com.prism.music.data.prefs.LyricsExtraPlace.entries) { it.label },
            "lyrics_background" to e(com.prism.music.data.prefs.LyricsBackground.entries) { it.label },
            "immersive_background" to e(com.prism.music.data.prefs.LyricsBackground.entries) { it.label },
            "spectrum_preset" to com.prism.music.ui.theme.SpectrumPresets.all.associate { it.key to it.label } +
                (com.prism.music.ui.theme.SpectrumPresets.COVER to com.prism.music.ui.theme.SpectrumPresets.COVER_LABEL) + ("CUSTOM" to tr(R.string.your_own)),
        )
    }

    private val percent = setOf(
        "text_scale", "lyrics_scale", "backdrop_dim", "player_backdrop_show", "surface_tint_strength", "mesh_speed", "mesh_intensity", "player_blur",
        "scrubber_thickness", "mini_fill_strength", "lyrics_blur_amount", "lyrics_spacing", "lyrics_others_alpha", "lyrics_backing_size", "lyrics_extra_size",
        "corner_scale", "glass_blur", "glass_frost", "player_gradient_depth",
    )
    private val colors = setOf("accent", "accent_light", "surface_color", "lyrics_sung_custom", "lyrics_other_custom", "lyrics_bg_color", "lyrics_bg_color2")

    fun title(name: String): String = titles[name] ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Whether [name] has its own words (LookTest: every look setting does). */
    fun named(name: String): Boolean = name in titles

    /** [tagged] as it reads ("Sharp", "On", "110%", "#3A86FF"); null is the setting's default. */
    fun value(name: String, tagged: String?): String {
        val v = LookValue.text(tagged) ?: return tr(R.string.default_text)
        return when (tagged!![0]) {
            'b' -> if (v == "1") tr(R.string.on_2) else tr(R.string.off)
            // "Show background" reads as its slider does (#309: its 0% is below the stored 0).
            'f' -> v.toFloatOrNull()?.let { f ->
                if (name == "player_backdrop_show") "${kotlin.math.round(com.prism.music.ui.player.backdropShowOnSlider(f) * 100).toInt()}%"
                else if (name in percent) "${(f * 100).toInt()}%" else v
            } ?: v
            'i' -> v.toIntOrNull()?.let { if (name in colors) "#%06X".format(it and 0xFFFFFF) else if (name == "player_art_corners") tr(R.string.dp, it) else v } ?: v
            else -> when (name) {
                "backdrops" -> tr(R.string.changed)
                "nav_tabs" -> v.split(',').joinToString(", ") { t -> choices["start_tab"]?.get(t) ?: t }
                "library_views" -> com.prism.music.data.prefs.parseLibraryViews(v).entries.joinToString(", ") { (t, view) ->
                    "${t.lowercase().replaceFirstChar { it.uppercase() }}: ${view.label}"
                }.ifEmpty { tr(R.string.default_text) }
                "tab_names" -> com.prism.music.data.prefs.parseTabNames(v).entries.joinToString(", ") { (t, n) -> "${t.label}: $n" }.ifEmpty { tr(R.string.their_own) }
                "player_deck", "player_buttons" -> v.split(',').joinToString(", ") { t -> choices[name]?.get(t) ?: t }
                // 1.8.0: the player's hidden parts, by name.
                "player_hidden_parts" -> v.split(',').mapNotNull { t -> com.prism.music.data.prefs.PlayerPart.entries.firstOrNull { it.name == t }?.label }
                    .joinToString(", ").ifEmpty { tr(R.string.none) }
                "spectrum_stops" -> tr(R.string.colors_2, v.split(',').size)
                "replay_card", "app_font_name" -> v.lowercase().replaceFirstChar { it.uppercase() }
                else -> choices[name]?.get(v) ?: v
            }
        }
    }

    /** The parts of Prism's look, in the order they're named, and the settings each is made of. */
    private val areaOf: List<Pair<String, Set<String>>> = listOf(
        "theme" to setOf("theme_mode", "pure_black"),
        "colors" to setOf(
            "color_source", "accent", "light_style", "accent_exact", "accent_light", "spectrum_preset", "spectrum_stops", "spectrum_bands",
            "surface_tint", "surface_tint_strength", "surface_color", "contrast_level", "artwork_swatch", "artwork_vivid", "artwork_change", "page_picture_accent", "playing_page_color",
        ),
        "type" to setOf("font_choice", "app_font_name", "lyrics_font", "text_scale", "type_classic"),
        "backgrounds" to setOf("liquid_glass", "glass_kind", "frosted_glass", "frosted_pages", "backdrops", "backdrop_dim", "backdrop_motion", "reduce_motion", "backdrop_fade", "glass_blur", "glass_frost", "glass_more"),
        "layout" to setOf(
            "corners", "ui_density", "title_size", "card_size", "nav_style", "nav_labels", "nav_hide_on_scroll", "nav_tabs", "start_tab",
            "mini_player_style", "mini_skip_buttons", "mini_prev_button", "mini_play_button", "mini_progress", "mini_progress_color", "mini_fill_strength", "library_view", "library_views", "library_columns", "library_subtitles", "home_header", "home_header_height", "playlist_hero_cover", "replay_theme_colors", "replay_card",
            "replay_card_graph", "replay_story_font",
            "hide_status_bar", "hide_status_bar_player", "tab_names", "nav_bar_size", "nav_bar_fill", "mini_player_joined", "controls_classic",
            "downloads_albums",
        ),
        "player" to setOf(
            "player_background", "player_backdrop_show", "player_art_corners", "transport_style", "cover_size", "scrubber_style", "art_shrink_on_pause",
            "show_output_device", "full_bleed_artwork", "video_full_bleed", "hide_volume_bar", "show_playing_from", "player_shuffle_repeat", "immersive_art", "immersive_lyrics", "immersive_controls",
            "player_layout", "vinyl_tonearm", "player_deck", "player_buttons", "scrubber_color", "player_title_align", "player_title_size",
            "player_title_weight", "player_title_scroll", "player_times", "mesh_speed", "mesh_intensity", "player_blur", "canvas_fills_background",
            "scrubber_thickness", "scrubber_thumb", "volume_matches_progress", "player_handle", "player_nav_band", "play_ring", "visualizer", "visualizer_color",
            "panel_player", "queue_headings", "queue_dj_chip", "cast_mark", "immersive_video", "bleed_fit", "cover_flight", "video_controls_hide", "crossfade_mark", "blend_covers", "player_buttons_pill",
            "player_hidden_parts", "crossfade_shape", "player_artist_links", "player_gradient_depth",
            "cover_flight_back", "video_buttons_hide", "bleed_roomy", "queue_lengths", "queue_played_moves", "queue_tidy",
        ),
        "lyrics" to setOf(
            "lyrics_align", "lyrics_blur", "lyrics_progress_line", "lyrics_source_button", "lyrics_scale", "lyrics_style", "lyrics_blur_amount", "lyrics_spacing",
            "lyrics_others_alpha", "lyrics_sung_color", "lyrics_sung_custom", "lyrics_other_color", "lyrics_other_custom", "lyrics_weight",
            "lyrics_backing_size", "lyrics_shadow", "lyrics_uppercase", "lyrics_ahead", "lyrics_position", "lyrics_extra_size", "lyrics_extra_color",
            "lyrics_extra_italic", "lyrics_extra_place", "lyrics_background", "immersive_background", "lyrics_bg_color", "lyrics_bg_color2", "lyrics_everywhere",
            "lyrics_strip_align", "lyrics_strip_two_lines", "lyrics_gap_mark",
        ),
    )

    /**
     * What a switch would change, in a line ("Changes your theme, colors and icons"), from the settings that differ
     * ([keys]) and whether the icon style does; null when nothing would.
     */
    fun summary(keys: Collection<String>, icons: Boolean, lead: Int = R.string.changes_your): String? {
        val parts = areaOf.filter { (_, set) -> keys.any { it in set } }.map { it.first }.toMutableList()
        // Anything not in a part above (a setting added later) still counts as the look.
        if (keys.any { k -> areaOf.none { k in it.second } } && parts.isEmpty()) parts += "look"
        if (icons) parts += "icons"
        if (parts.isEmpty()) return null
        return tr(lead, listed(parts.map(::areaName)))
    }

    /** An area's name in the listener's language ([areaOf]'s keys stay English). */
    private fun areaName(area: String): String = when (area) {
        "theme" -> tr(R.string.area_theme)
        "colors" -> tr(R.string.area_colors)
        "type" -> tr(R.string.area_type)
        "backgrounds" -> tr(R.string.area_backgrounds)
        "layout" -> tr(R.string.area_layout)
        "player" -> tr(R.string.area_player)
        "lyrics" -> tr(R.string.area_lyrics)
        "icons" -> tr(R.string.area_icons)
        else -> tr(R.string.area_look)
    }

    /** "a", "a and b", "a, b and c" in the listener's language. */
    fun listed(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> tr(R.string.list_last, parts.dropLast(1).reduce { a, b -> tr(R.string.list_middle, a, b) }, parts.last())
    }

    /** One change as it reads: "Corners → Sharp", or "Corners → Sharp and 2 more". */
    fun change(before: Map<String, String>, after: Map<String, String>, order: List<String>): String? {
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val first = changed.firstOrNull() ?: return null
        val one = "${title(first)} → ${value(first, after[first])}"
        return if (changed.size == 1) one else tr(R.string.and_more_4, one, changed.size - 1)
    }
}

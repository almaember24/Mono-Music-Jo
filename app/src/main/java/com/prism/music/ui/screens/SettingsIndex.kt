package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.innertube.SearchRank

/**
 * What Settings' search looks through (#73): every setting by its title, the page and group it sits in, and other words
 * people may look for it by. [SettingsIndexTest] checks that each setting row and group in [SettingsScreen] is listed,
 * so a new setting needs a line here.
 */
data class SettingEntry(
    /** The page it's on; null for the Settings hub itself (the account). */
    val section: SettingsSection?,
    val group: String,
    val title: String,
    val keywords: String = "",
    /** How a result reads when the title alone wouldn't say what it is ("Everywhere"). */
    val shown: String = title,
    /** The switch it only shows under (Crossfade's Length needs Crossfade on); the result says to turn that on first. */
    val needs: Needs? = null,
)

/** A setting that only shows while [on] holds; [hint] says what to change first ("Turn on Crossfade songs first"). */
class Needs(val hint: String, val on: (com.prism.music.data.prefs.AppSettings) -> Boolean)

object SettingsIndex {
    private fun turnOn(name: String, on: (com.prism.music.data.prefs.AppSettings) -> Boolean) = Needs(tr(R.string.turn_on_first, name), on)
    private val immersive = turnOn(tr(R.string.immersive_mode)) { it.immersivePlayer }
    private val canvas = turnOn(tr(R.string.animated_covers)) { it.canvasEnabled }
    private val canvasSave = turnOn(tr(R.string.animated_covers_and_save_with_downloads)) { it.canvasEnabled && it.canvasWithDownloads }
    private val holdSeek = turnOn(tr(R.string.hold_previous_next_to_seek)) { it.holdSeekSeconds > 0 }
    private val songsOnly = turnOn(tr(R.string.songs_not_videos)) { it.radioSongsOnly }
    private val crossfade = turnOn(tr(R.string.crossfade_songs)) { it.mix }
    private val manualLength = Needs(tr(R.string.turn_on_crossfade_songs_and_turn)) { it.mix && !it.mixAuto }
    private val lossless = turnOn(tr(R.string.lossless_playback)) { it.lossless }
    private val notLossless = Needs(tr(R.string.lossless_playback_always_plays_the_best)) { !it.lossless }
    private val saver = turnOn(tr(R.string.lossless_playback_and_data_saver)) { it.lossless && it.dataSaver }
    private val measuring = turnOn(tr(R.string.normalize_volume_or_crossfade_songs)) { it.normalize || it.mix }
    private val translate = turnOn(tr(R.string.translate_lyrics)) { it.lyricsTranslate }
    private val romanize = Needs(tr(R.string.pick_a_romanize_lyrics_option_first)) { it.romanize != com.prism.music.data.prefs.RomanizeMode.OFF }
    private val carLyrics = turnOn(tr(R.string.lyrics_in_the_car)) { it.carLyrics }
    private val smart = turnOn(tr(R.string.smart_downloads)) { it.smartDownloads }
    private val webRemote = turnOn(tr(R.string.control_from_a_browser)) { it.webRemote }
    private val ownFiles = turnOn(tr(R.string.use_your_own_song_files)) { it.sound.ownLossyFiles }
    private val lockScreen = turnOn(tr(R.string.lock_screen_title)) { it.behavior.lockScreen }

    private val A = SettingsSection.APPEARANCE
    private val CO = SettingsSection.COLOR
    private val T = SettingsSection.TEXT
    private val I = SettingsSection.ICONS
    private val G = SettingsSection.GLASS
    private val LK = SettingsSection.LOOKS
    private val L = SettingsSection.INTERFACE
    private val P = SettingsSection.PLAYER
    private val S = SettingsSection.SOUND
    private val Y = SettingsSection.LYRICS
    private val D = SettingsSection.DOWNLOADS
    private val H = SettingsSection.HOME
    private val LB = SettingsSection.LIBRARY
    private val B = SettingsSection.ABOUT
    private val C = SettingsSection.CONNECT
    private val DC = SettingsSection.DISCORD
    private val LF = SettingsSection.LASTFM
    private val SP = SettingsSection.SPOTIFY
    private val YL = SettingsSection.LYRICS_LOOK
    private val CT = SettingsSection.CONTROLS
    private val DV = SettingsSection.DEVICES

    val entries: List<SettingEntry> = listOf(
        // The hub
        SettingEntry(null, tr(R.string.account), tr(R.string.account), tr(R.string.google_youtube_sign_in_sign_out)),
        SettingEntry(null, tr(R.string.account), tr(R.string.prism_account), tr(R.string.profile_name_picture_photo_local_on)),
        SettingEntry(null, tr(R.string.performance), tr(R.string.performance_mode), tr(R.string.slow_lag_laggy_stutter_battery_old)),
        SettingEntry(null, tr(R.string.performance), tr(R.string.performance_mode_is_on), tr(R.string.paused_effects_turn_off)),
        SettingEntry(DC, tr(R.string.discord), tr(R.string.show_what_you_re_playing), tr(R.string.discord_rich_presence_status_listening)),
        SettingEntry(DC, tr(R.string.discord), tr(R.string.sign_out_of_discord)),
        SettingEntry(DC, tr(R.string.discord), tr(R.string.about_this), tr(R.string.discord_terms_risk)),
        SettingEntry(SettingsSection.LANGUAGE, tr(R.string.language), tr(R.string.language), tr(R.string.language_keywords)),
        // Appearance
        SettingEntry(A, tr(R.string.theme), tr(R.string.theme), tr(R.string.dark_light_system_mode_night)),
        SettingEntry(A, tr(R.string.theme), tr(R.string.pure_black), tr(R.string.amoled_oled)),
        SettingEntry(A, tr(R.string.the_whole_look), tr(R.string.color_2), tr(R.string.color_colors_colour_accent_split_light)),
        SettingEntry(A, tr(R.string.the_whole_look), tr(R.string.text), tr(R.string.font_typeface_size)),
        SettingEntry(A, tr(R.string.the_whole_look), tr(R.string.icons_shapes), tr(R.string.icon_pack_app_icon_corners)),
        SettingEntry(A, tr(R.string.the_whole_look), tr(R.string.glass_backgrounds), tr(R.string.blur_backgrounds_motion)),
        SettingEntry(A, tr(R.string.each_part), tr(R.string.tabs_mini_player), tr(R.string.layout_navigation_tab_bar_bottom_bar)),
        SettingEntry(A, tr(R.string.each_part), tr(R.string.home_library), tr(R.string.home_screen_library_look_views)),
        SettingEntry(A, tr(R.string.each_part), tr(R.string.player), tr(R.string.now_playing_look_layout_cover)),
        SettingEntry(A, tr(R.string.each_part), tr(R.string.lyrics), tr(R.string.lyrics_look_style_colors)),
        SettingEntry(A, tr(R.string.the_whole_look), tr(R.string.looks), tr(R.string.theme_preset_save_switch_share_style)),
        SettingEntry(A, tr(R.string.customize_this), tr(R.string.customize_by_tapping), tr(R.string.edit_mode_customise_tap_long_press)),
        // Appearance → Looks (1.7.3)
        SettingEntry(LK, tr(R.string.prism_s_looks), tr(R.string.prism_s_looks), tr(R.string.theme_preset_paper_midnight_neon_soft)),
        SettingEntry(LK, tr(R.string.your_looks), tr(R.string.your_looks), tr(R.string.saved_theme_preset_save_rename_duplicate)),
        SettingEntry(LK, tr(R.string.from_the_community), tr(R.string.browse_community_looks), tr(R.string.gallery_github_themes_shared_download_others)),
        SettingEntry(LK, tr(R.string.from_the_community), tr(R.string.couldn_t_load_the_gallery), tr(R.string.gallery_offline)),
        SettingEntry(LK, tr(R.string.from_the_community), tr(R.string.refresh_the_list), tr(R.string.gallery_github_themes)),
        SettingEntry(LK, tr(R.string.from_the_community), tr(R.string.add_your_look), tr(R.string.gallery_github_pull_request_contribute_share)),
        SettingEntry(LK, tr(R.string.get_a_look), tr(R.string.scan_a_look_s_qr_code), tr(R.string.camera_theme_share_friend)),
        SettingEntry(LK, tr(R.string.get_a_look), tr(R.string.paste_a_look_code), tr(R.string.clipboard_theme_share)),
        SettingEntry(LK, tr(R.string.get_a_look), tr(R.string.open_a_look_file), tr(R.string.prismlook_file_import_theme)),
        SettingEntry(LK, tr(R.string.day_and_night), tr(R.string.day_and_night_looks), tr(R.string.schedule_sunset_sunrise_automatic_dark_mode)),
        SettingEntry(LK, tr(R.string.day_and_night), tr(R.string.by_day), tr(R.string.schedule_day_look_morning)),
        SettingEntry(LK, tr(R.string.day_and_night), tr(R.string.by_night), tr(R.string.schedule_night_look_evening_dark)),
        SettingEntry(LK, tr(R.string.day_and_night), tr(R.string.switch_text), tr(R.string.schedule_sunrise_sunset_set_times)),
        SettingEntry(LK, tr(R.string.day_and_night), tr(R.string.day_starts_at), tr(R.string.schedule_time)),
        SettingEntry(LK, tr(R.string.day_and_night), tr(R.string.night_starts_at), tr(R.string.schedule_time)),
        SettingEntry(LK, tr(R.string.history), tr(R.string.history), tr(R.string.undo_go_back_changes_revert)),
        SettingEntry(LK, tr(R.string.history), tr(R.string.clear_history), tr(R.string.delete_forget_look_changes_history)),
        SettingEntry(LK, tr(R.string.your_looks), tr(R.string.include_its_photos), tr(R.string.share_file)),
        SettingEntry(LK, tr(R.string.your_looks), tr(R.string.include_its_font), tr(R.string.share_file)),
        SettingEntry(H, tr(R.string.this_section), tr(R.string.hide_this_section), tr(R.string.customize_this_home)),
        // Appearance → Color (1.7.2)
        SettingEntry(CO, tr(R.string.spectrum), tr(R.string.spectrum), tr(R.string.split_light_gradient_rainbow_preset_sunset)),
        SettingEntry(CO, tr(R.string.spectrum), tr(R.string.white), tr(R.string.white_light_black_monochrome_neutral_grey)),
        SettingEntry(CO, tr(R.string.spectrum), tr(R.string.smooth_or_bands), tr(R.string.stripes_hard_spectrum_split_light_rainbow)),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.accent_from), tr(R.string.song_cover_wallpaper_your_pick_now)),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.accent_color), tr(R.string.color_accent_picker_wheel_hex_code)),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.exactly_as_picked), tr(R.string.accent_color_exact_shade_softened_tone)),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.how_it_s_used), tr(R.string.accent_color_exact_softened_as_picked),
            needs = Needs(tr(R.string.pick_your_pick_for_the_accent)) { it.colorSource == com.prism.music.data.prefs.ColorSource.CUSTOM }),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.which_of_its_colors), tr(R.string.song_cover_vibrant_muted_dominant_swatch),
            needs = Needs(tr(R.string.pick_song_s_cover_for_the)) { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.light_mode_accent), tr(R.string.accent_color_light_theme) + " " + tr(R.string.accent_color_light_theme_another) + " " + tr(R.string.different_color_for_light_mode)),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.cover_color), tr(R.string.song_cover_now_playing_artwork_accent),
            needs = Needs(tr(R.string.pick_song_s_cover_for_the)) { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.always_colorful), tr(R.string.song_cover_now_playing_artwork_accent_2),
            needs = Needs(tr(R.string.pick_song_s_cover_for_the)) { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, tr(R.string.accent_color), tr(R.string.between_songs), tr(R.string.song_cover_now_playing_artwork_accent_3),
            needs = Needs(tr(R.string.pick_song_s_cover_for_the)) { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, tr(R.string.page_background), tr(R.string.page_background), tr(R.string.tint_surface_page_neutral_plain_color)),
        SettingEntry(CO, tr(R.string.page_background), tr(R.string.background_color), tr(R.string.tint_surface_page_color),
            needs = Needs(tr(R.string.pick_your_color_under_page_background)) { it.surfaceTint == com.prism.music.data.prefs.SurfaceTint.CUSTOM }),
        SettingEntry(CO, tr(R.string.page_background), tr(R.string.tint_strength), tr(R.string.surface_page_tint),
            needs = Needs(tr(R.string.pick_accent_tint_or_your_color)) { it.surfaceTint == com.prism.music.data.prefs.SurfaceTint.ACCENT || it.surfaceTint == com.prism.music.data.prefs.SurfaceTint.CUSTOM }),
        SettingEntry(CO, tr(R.string.text_contrast), tr(R.string.text_contrast), tr(R.string.high_contrast_accessibility_readability_legible)),
        SettingEntry(CO, tr(R.string.artist_album_pages), tr(R.string.photo_s_color), tr(R.string.artist_album_playlist_page_color_color)),
        SettingEntry(CO, tr(R.string.artist_album_pages), tr(R.string.playing_song_in_the_page_s_color), tr(R.string.playing_indicator_bars_now_playing_album_color)),
        SettingEntry(T, tr(R.string.text), tr(R.string.font), tr(R.string.typeface_custom_ttf_otf_outfit_prism)),
        SettingEntry(T, tr(R.string.type), tr(R.string.type), tr(R.string.classic_1_6_bold_headings_titles), shown = tr(R.string.classic_type)),
        SettingEntry(T, tr(R.string.weight_and_spacing), tr(R.string.weight_and_spacing), tr(R.string.bold_light_heavy_text_weight)),
        SettingEntry(T, tr(R.string.weight_and_spacing), tr(R.string.letter_spacing), tr(R.string.tracking_wide_tight_letters)),
        SettingEntry(T, tr(R.string.titles), tr(R.string.titles), tr(R.string.title_font_headings_different_font)),
        SettingEntry(T, tr(R.string.text), tr(R.string.remove_your_font)),
        SettingEntry(T, tr(R.string.text), tr(R.string.installed_fonts), tr(R.string.installed_fonts_keywords)),
        SettingEntry(T, tr(R.string.text), tr(R.string.installed_fonts_for_lyrics), tr(R.string.installed_fonts_keywords)),
        SettingEntry(T, tr(R.string.text), tr(R.string.lyrics_font), "typeface"),
        SettingEntry(T, tr(R.string.text), tr(R.string.lyrics_font_file)),
        SettingEntry(T, tr(R.string.text), tr(R.string.remove_the_lyrics_font_file)),
        SettingEntry(T, tr(R.string.text), tr(R.string.text_size), tr(R.string.font_size_scale_bigger_smaller)),
        SettingEntry(I, tr(R.string.icons), tr(R.string.pack), tr(R.string.icon_style_cupertino_apple)),
        SettingEntry(I, tr(R.string.icons), tr(R.string.weight), tr(R.string.icon_thin_bold)),
        SettingEntry(I, tr(R.string.icons), tr(R.string.rounded), tr(R.string.icon_corners)),
        SettingEntry(I, tr(R.string.icons), tr(R.string.likes_as_stars), tr(R.string.heart_star_like_favourite_favorite_icon)),
        SettingEntry(I, tr(R.string.icons), tr(R.string.restart_prism_to_see_them)),
        SettingEntry(I, tr(R.string.app_icon), tr(R.string.app_icon), tr(R.string.launcher_home_screen_icon_logo)),
        SettingEntry(I, tr(R.string.shape), tr(R.string.corners), tr(R.string.rounded_square_shape_radius_slider)),
        SettingEntry(I, tr(R.string.covers_shapes), tr(R.string.covers_shapes), tr(R.string.cover_shape_circle_squircle_flower_cookie)),
        SettingEntry(I, tr(R.string.buttons), tr(R.string.buttons), tr(R.string.button_shape_pill_rounded_square)),
        SettingEntry(I, tr(R.string.home_screen_widgets), tr(R.string.home_screen_widgets), tr(R.string.widget_background_transparent_see_through_color)),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.the_tab_you_re_on), tr(R.string.tab_icon_color_tint_accent_light)),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.tabs_on_a_tablet), tr(R.string.down_the_side) + " " + tr(R.string.at_the_bottom)),
        SettingEntry(I, tr(R.string.buttons), tr(R.string.play_on_album_and_playlist_pages), tr(R.string.play_button_outlined_filled)),
        SettingEntry(I, tr(R.string.buttons), tr(R.string.switches_sliders_and_buttons), tr(R.string.classic_controls_1_6_material_switch)),
        SettingEntry(G, tr(R.string.backgrounds), tr(R.string.screen_backgrounds), tr(R.string.wallpaper_photo_pattern_design)),
        SettingEntry(G, tr(R.string.backgrounds), tr(R.string.now_playing_background_fades), tr(R.string.now_playing_background_fades_keywords)),
        // 1.8.0: one Glass choice; Motion moved to the Customize Prism page. Old titles stay as search words.
        SettingEntry(G, tr(R.string.glass), tr(R.string.glass), tr(R.string.blur_transparent) + " " + tr(R.string.liquid_glass) + " " + tr(R.string.frosted_glass) + " " + tr(R.string.glass_style)),
        SettingEntry(G, tr(R.string.glass), tr(R.string.frosted_pages), tr(R.string.blur_artist_album_playlist)),
        SettingEntry(G, tr(R.string.glass), tr(R.string.glass_blur), tr(R.string.glass_blur_info), needs = turnOn(tr(R.string.frosted_glass)) { (it.frostedGlass || it.liquidGlass) && !(it.liquidGlass && it.glassKind == com.prism.music.data.prefs.GlassKind.CLEAR) }),
        SettingEntry(G, tr(R.string.glass), tr(R.string.glass_frost), tr(R.string.glass_frost_info), needs = turnOn(tr(R.string.frosted_glass)) { it.frostedGlass || it.liquidGlass }),
        SettingEntry(G, tr(R.string.glass), tr(R.string.glass_on_more_of_the_screen), tr(R.string.glass_on_more_of_the_screen_detail)),
        SettingEntry(A, tr(R.string.motion), tr(R.string.motion)),
        SettingEntry(A, tr(R.string.motion), tr(R.string.reduce_motion), tr(R.string.animations_accessibility)),
        SettingEntry(A, tr(R.string.motion), tr(R.string.changing_screens), tr(R.string.fades_between_screens_keywords) + " " + tr(R.string.slide_between_screens_keywords) + " " + tr(R.string.fades_between_screens) + " " + tr(R.string.slide_between_screens)),
        // Layout & navigation
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.style), tr(R.string.tab_bar_bottom_bar)),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.labels), tr(R.string.tab_bar_names),
            needs = Needs(tr(R.string.the_minimal_bar_has_no_labels)) { it.navStyle != com.prism.music.data.prefs.NavBarStyle.MINIMAL }),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.shrink_while_scrolling), tr(R.string.tab_bar_hide)),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.height), tr(R.string.tab_bar_size_tall_compact)),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.background), tr(R.string.tab_bar_see_through_solid_opacity)),
        SettingEntry(L, tr(R.string.navigation_bar), tr(R.string.swipe_between_tabs), tr(R.string.tab_bar_swipe_gesture_sideways)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.joined_to_the_tab_bar), tr(R.string.mini_player_merged_tab_bar_one)),
        SettingEntry(L, tr(R.string.tabs), tr(R.string.tabs), tr(R.string.tab_bar_order_home_search_library)),
        SettingEntry(L, tr(R.string.tabs), tr(R.string.open_prism_on), tr(R.string.start_tab_first_screen)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.mini_player), tr(R.string.style_card_slim_pill)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.previous_button), tr(R.string.back_skip)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.play_pause_button), tr(R.string.play_pause)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.next_button), tr(R.string.skip_forward)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.progress)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.progress_bar_color), tr(R.string.scrubber_color_accent_white_cover_spectrum)),
        SettingEntry(L, tr(R.string.mini_player), tr(R.string.fill_strength)),
        SettingEntry(L, tr(R.string.pages), tr(R.string.spacing), tr(R.string.density_compact)),
        SettingEntry(L, tr(R.string.pages), tr(R.string.page_titles)),
        SettingEntry(L, tr(R.string.pages), tr(R.string.album_playlist_cards)),
        SettingEntry(H, tr(R.string.search), tr(R.string.open_the_keyboard), tr(R.string.search_typing)),
        // Player
        SettingEntry(P, tr(R.string.look), tr(R.string.background), tr(R.string.player_blur_cover_color)),
        SettingEntry(P, tr(R.string.look), tr(R.string.show_background)),
        SettingEntry(P, tr(R.string.look), tr(R.string.cover_gradient), tr(R.string.cover_gradient_info)),
        SettingEntry(P, tr(R.string.look), tr(R.string.gradient_depth), tr(R.string.gradient_depth_info), needs = turnOn(tr(R.string.cover_gradient)) { it.playerBackground == com.prism.music.data.prefs.PlayerBackground.GRADIENT }),
        SettingEntry(P, tr(R.string.look), tr(R.string.controls), tr(R.string.buttons_transport)),
        SettingEntry(P, tr(R.string.look), tr(R.string.ring_on_the_play_button), tr(R.string.the_split_light_around_play_and_pause)),
        SettingEntry(P, tr(R.string.progress_bar), tr(R.string.progress_bar), tr(R.string.scrubber_seek_bar)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.cover_size), tr(R.string.large_full_width)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.corners), tr(R.string.cover_rounded)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.edge_to_edge_cover), tr(R.string.edge_to_edge) + " " + tr(R.string.full_bleed_artwork)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.edge_to_edge_video), tr(R.string.edge_to_edge) + " " + tr(R.string.full_bleed_video) + " " + tr(R.string.music_video_edge_to_edge_fill)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.shrink_when_paused)),
        SettingEntry(I, tr(R.string.covers_shapes), tr(R.string.square_video_covers), tr(R.string.video_thumbnail_crop_black_bars_letterbox)),
        SettingEntry(P, tr(R.string.immersive_mode), tr(R.string.immersive_mode), tr(R.string.lyric_video_full_screen)),
        SettingEntry(P, tr(R.string.immersive_mode), tr(R.string.artwork), needs = immersive),
        SettingEntry(P, tr(R.string.immersive_mode), tr(R.string.lyrics), needs = immersive),
        SettingEntry(P, tr(R.string.immersive_mode), tr(R.string.keep_controls_on_screen), tr(R.string.play_pause_skip_next_previous_buttons), needs = immersive),
        SettingEntry(P, tr(R.string.animated_covers), tr(R.string.animated_covers), tr(R.string.canvas_motion_video_cover)),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.animated_covers_on_mobile_data), tr(R.string.canvas_cellular_play), needs = canvas),
        SettingEntry(D, tr(R.string.animated_covers), tr(R.string.save_with_downloads), tr(R.string.canvas_offline), needs = canvas),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.save_animated_covers_on_mobile_data), tr(R.string.canvas_cellular), needs = canvasSave),
        SettingEntry(D, tr(R.string.animated_covers), tr(R.string.saved_animated_covers), tr(R.string.canvas_storage), needs = canvas),
        SettingEntry(D, tr(R.string.animated_covers), tr(R.string.recently_played_covers), tr(R.string.canvas_storage_cache), needs = canvas),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.from_the_album), tr(R.string.album_caption_playing_from)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.stream_quality), tr(R.string.codec_bitrate_nerd_stats)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.best_part), tr(R.string.skip_chorus_hook_highlight_most_replayed)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.times), tr(R.string.duration_time_left_remaining)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.shuffle_repeat)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.buttons_in_the_row), tr(R.string.music_video_audio_only)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.output_device), tr(R.string.bluetooth_speaker_headphones)),
        // The player, your way (1.7.4)
        SettingEntry(P, tr(R.string.layout), tr(R.string.layout), tr(R.string.player_layout_standard_cover_lyrics_minimal)),
        SettingEntry(P, tr(R.string.layout), tr(R.string.tonearm), tr(R.string.vinyl_record_turntable_arm_needle), needs = turnOn(tr(R.string.vinyl_layout)) { it.playerLayout == com.prism.music.data.prefs.PlayerLayout.VINYL }),
        // 1.8.0: Motion and Colors are one slider (old names still find it).
        SettingEntry(P, tr(R.string.look), tr(R.string.background_movement), tr(R.string.mesh_background_drift_animate_move_still) + " " + tr(R.string.mesh_background_saturation_vivid_soft_rich) + " " + tr(R.string.motion) + " " + tr(R.string.colors)),
        SettingEntry(P, tr(R.string.look), tr(R.string.softness), tr(R.string.blur_background_strength_soft_clear), needs = turnOn(tr(R.string.blurred_cover)) { it.playerBackground == com.prism.music.data.prefs.PlayerBackground.BLUR }),
        SettingEntry(P, tr(R.string.look), tr(R.string.buttons), tr(R.string.player_bottom_row_buttons_lyrics_video)),
        // 1.8.0: one editor for what's on the player (it took in Show on the player, Under the title and the buttons) and the ⋯ menu.
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.what_s_on_the_player), tr(R.string.what_s_on_the_player_keywords)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.buttons_in_the_row), tr(R.string.player_bottom_row_buttons_lyrics_video)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.edit_this_menu), tr(R.string.edit_this_menu_keywords)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.crossfade_mark), tr(R.string.crossfade_songs), needs = crossfade),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.covers_blend_too), tr(R.string.crossfade_songs), needs = crossfade),
        SettingEntry(CT, tr(R.string.notification_and_car), tr(R.string.notification_and_car), tr(R.string.notification_lock_screen_android_auto_car)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.under_the_title), tr(R.string.order_arrange_rearrange_move_current_lyric)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.current_lyric), tr(R.string.lyrics_line)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.volume), "slider"),
        SettingEntry(P, tr(R.string.title_and_times), tr(R.string.title_and_times), tr(R.string.title_alignment_centered_centre_left)),
        SettingEntry(P, tr(R.string.title_and_times), tr(R.string.title_size), tr(R.string.big_small_large_font)),
        SettingEntry(P, tr(R.string.title_and_times), tr(R.string.title_weight), tr(R.string.bold_heavy_thick_light_regular_font)),
        SettingEntry(P, tr(R.string.title_and_times), tr(R.string.scrolling_titles), tr(R.string.marquee_scroll_long_title_ellipsis)),
        SettingEntry(P, tr(R.string.title_and_times), tr(R.string.each_artist_its_own_link), tr(R.string.each_artist_its_own_link_keywords)),
        SettingEntry(P, tr(R.string.title_and_times), tr(R.string.times_under_the_progress_bar), tr(R.string.elapsed_remaining_time_left_none)),
        SettingEntry(P, tr(R.string.progress_bar), tr(R.string.progress_bar_color), tr(R.string.scrubber_color_accent_white_cover_spectrum)),
        SettingEntry(P, tr(R.string.progress_bar), tr(R.string.progress_bar_thickness), tr(R.string.scrubber_thick_thin_height_bold)),
        SettingEntry(P, tr(R.string.progress_bar), tr(R.string.knob_at_the_playhead), tr(R.string.scrubber_thumb_knob_dot_line_ring)),
        SettingEntry(P, tr(R.string.progress_bar), tr(R.string.volume_bar_like_the_progress_bar), tr(R.string.volume_bar_keywords)),
        // 1.8.1: the bar shaped by the crossfade (drawn only while Crossfade is on).
        SettingEntry(P, tr(R.string.progress_bar), tr(R.string.crossfade_on_the_progress_bar), tr(R.string.crossfade_shape_keywords), needs = crossfade),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.handle_at_the_top), tr(R.string.player_handle_keywords)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.band_behind_the_navigation_buttons), tr(R.string.nav_band_keywords)),
        SettingEntry(P, tr(R.string.visualizer), tr(R.string.visualizer), tr(R.string.visualiser_bars_wave_ring_pulse_glow)),
        SettingEntry(P, tr(R.string.visualizer), tr(R.string.visualizer_color), tr(R.string.visualiser_colour_accent_white_cover)),
        SettingEntry(P, tr(R.string.look), tr(R.string.which_background), tr(R.string.player_background_own_design_photo_pattern)),
        SettingEntry(P, tr(R.string.animated_covers), tr(R.string.fill_the_player), tr(R.string.canvas_background_full_screen_behind), needs = canvas),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.gestures), tr(R.string.swipe_double_tap_long_press_cover)),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.double_tap), tr(R.string.seek_skip_seconds_fast_forward_rewind)),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.long_press), tr(R.string.hold_cover)),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.pull_down), tr(R.string.mini_player_stop)),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.swipe_the_mini_player_up), tr(R.string.mini_player_swipe_up_open_player)),
        SettingEntry(CT, tr(R.string.buttons_and_touch), tr(R.string.hold_previous_next_to_seek), tr(R.string.fast_forward_rewind) + " " + tr(R.string.each_step)),
        SettingEntry(CT, tr(R.string.buttons_and_touch), tr(R.string.haptics), tr(R.string.vibration_vibrate)),
        // Playback & sound
        // 1.8.0 (#210): merged choices; the old titles stay as search words.
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.what_plays_next)),
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.when_the_queue_ends), tr(R.string.radio_continue_similar) + " " + tr(R.string.autoplay) + " " + tr(R.string.autoplay_from) + " " + tr(R.string.radio_your_music_prism_radio_youtube)),
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.no_repeats_title), tr(R.string.no_repeats_detail)),
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.longest_song_for_autoplay), tr(R.string.longest_song_for_autoplay_keywords), needs = turnOn(tr(R.string.autoplay)) { it.autoplay }),
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.music_videos_label), tr(R.string.music_videos_audio) + " " + tr(R.string.songs_not_videos) + " " + tr(R.string.songs_not_videos_everywhere)),
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.keep_playing_videos), tr(R.string.music_video_2)),
        SettingEntry(S, tr(R.string.what_plays_next), tr(R.string.explicit_songs), tr(R.string.explicit_songs_keywords)),
        SettingEntry(S, tr(R.string.sound), tr(R.string.skip_silence), tr(R.string.trim_silence_songs_gaps) + " " + tr(R.string.trim_silence) + " " + tr(R.string.skip_silence_in_podcasts) + " " + tr(R.string.trim_silence_episodes_pauses_talk)),
        SettingEntry(S, tr(R.string.stopping_and_pausing), tr(R.string.stopping_and_pausing)),
        SettingEntry(S, tr(R.string.stopping_and_pausing), tr(R.string.stop_when_swiped_away), tr(R.string.recents_background)),
        SettingEntry(S, tr(R.string.stopping_and_pausing), tr(R.string.pause_when_muted), tr(R.string.volume_zero_mute_pause_resume)),
        SettingEntry(LF, "Last.fm", tr(R.string.last_fm_scrobbling), tr(R.string.lastfm_last_fm_scrobble)),
        SettingEntry(LF, "Last.fm", tr(R.string.connect_last_fm), tr(R.string.lastfm_last_fm_scrobble_scrobbling_account)),
        SettingEntry(LF, "Last.fm", tr(R.string.scrobble), tr(R.string.lastfm_scrobble_send_plays)),
        SettingEntry(LF, "Last.fm", tr(R.string.first_artist_only), tr(R.string.lastfm_featured_artists_main_artist)),
        SettingEntry(LF, "Last.fm", tr(R.string.love_when_i_like_a_song), tr(R.string.lastfm_love_like_heart)),
        SettingEntry(LF, "Last.fm", tr(R.string.disconnect), tr(R.string.lastfm_sign_out)),
        // 1.8.1: Spotify, through the listener's own developer app.
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spotify), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spot_set_up), tr(R.string.spot_open_dashboard), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spot_set_up), tr(R.string.spot_redirect_uri), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spot_set_up), tr(R.string.spot_client_id), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spot_connected_as, "").trim(), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spot_recent), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spot_top), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spot_playlists), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spot_keep_in_step), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spotify), tr(R.string.spot_likes), tr(R.string.spot_keywords)),
        SettingEntry(SP, tr(R.string.spot_connection), tr(R.string.disconnect), tr(R.string.spot_keywords)),
        SettingEntry(H, tr(R.string.home), tr(R.string.recently_played_shows), tr(R.string.recently_played_albums_playlists_songs_home)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.lyrics_and_the_queue), tr(R.string.player_controls_hide_full_screen_lyrics)),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.hide_controls_over_videos), tr(R.string.music_video_controls_hide_tap_full)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.cover_flies_into_place), tr(R.string.animation_transition_open_player_cover_mini)),
        SettingEntry(P, tr(R.string.cover), tr(R.string.cover_flies_back_too), tr(R.string.animation_transition_open_player_cover_mini), needs = turnOn(tr(R.string.cover_flies_into_place)) { it.view.coverFlight }),
        SettingEntry(P, tr(R.string.cover), tr(R.string.more_room_for_the_picture), tr(R.string.more_room_for_the_picture_keywords), needs = turnOn(tr(R.string.edge_to_edge_cover)) { it.fullBleedArtwork || it.videoFullBleed }),
        SettingEntry(P, tr(R.string.what_s_on_the_player), tr(R.string.buttons_on_videos_wait_for_a_tap), tr(R.string.buttons_on_videos_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.song_lengths_in_the_queue), tr(R.string.song_lengths_in_the_queue_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.move_songs_you_have_played), tr(R.string.move_songs_you_have_played_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.tidier_queue), tr(R.string.tidier_queue_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.reopen_on_the_last_page), tr(R.string.reopen_on_the_last_page_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.steadier_lyrics), tr(R.string.steadier_lyrics_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.queue_headings), tr(R.string.played_now_playing_up_next_headings)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.dj_in_the_queue), tr(R.string.dj_in_the_queue_keywords)),
        SettingEntry(P, tr(R.string.lyrics_and_the_queue), tr(R.string.scrolling_up_shows_the_controls), tr(R.string.scroll_swipe_down_lyrics_seek_bar),
            needs = Needs(tr(R.string.pick_hide_after_a_moment_first)) { it.view.panelPlayer == com.prism.music.data.prefs.PanelPlayer.AUTO_HIDE }),
        SettingEntry(P, tr(R.string.immersive_mode), tr(R.string.show_the_video), tr(R.string.immersive_music_video_behind_lyrics), needs = immersive),
        SettingEntry(CT, tr(R.string.buttons_and_touch), tr(R.string.swipe_up_for_the_queue), tr(R.string.gesture_one_hand_queue_lyrics_swipe)),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.gestures_anywhere_on_the_player), tr(R.string.gestures_everywhere_keywords)),
        SettingEntry(CT, tr(R.string.gestures), tr(R.string.gestures_under_the_lyrics), tr(R.string.gestures_everywhere_keywords)),
        SettingEntry(CT, tr(R.string.screen), tr(R.string.lock_to_portrait), tr(R.string.rotation_orientation_landscape_upright)),
        SettingEntry(CT, tr(R.string.screen), tr(R.string.picture_in_picture), tr(R.string.pip_video_float_window_leave)),
        SettingEntry(D, tr(R.string.load_ahead), tr(R.string.load_ahead), tr(R.string.preload_buffer)),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.load_ahead_on_mobile_data), tr(R.string.preload_cellular)),
        SettingEntry(S, tr(R.string.crossfade), tr(R.string.crossfade), tr(R.string.mix_blend_transition_fade) + " " + tr(R.string.crossfade_songs) + " " + tr(R.string.automatic_length)),
        SettingEntry(S, tr(R.string.crossfade), tr(R.string.length), needs = manualLength),
        SettingEntry(S, tr(R.string.crossfade), tr(R.string.match_tempo_and_beats_dj_style), tr(R.string.bpm_beatmatch), needs = crossfade),
        SettingEntry(S, tr(R.string.crossfade), tr(R.string.keep_an_album_s_songs_whole), tr(R.string.keep_albums_keywords), needs = crossfade),
        SettingEntry(S, tr(R.string.how_they_cross), tr(R.string.how_they_cross), tr(R.string.curve_equal_power_linear_s_curve)),
        SettingEntry(S, tr(R.string.speed_pitch_and_fades), tr(R.string.speed_when_prism_starts), tr(R.string.playback_speed_default_tempo_faster_slower)),
        SettingEntry(S, tr(R.string.speed_pitch_and_fades), tr(R.string.pitch_when_prism_starts), tr(R.string.key_semitones_transpose_higher_lower_slowed)),
        SettingEntry(S, tr(R.string.speed_pitch_and_fades), tr(R.string.sleep_timer_fades_out), tr(R.string.sleep_fade_out_bedtime)),
        SettingEntry(S, tr(R.string.speed_pitch_and_fades), tr(R.string.fade_on_pause_and_play), tr(R.string.pause_fade_gentle_soft)),
        SettingEntry(S, tr(R.string.quality), tr(R.string.lossless_playback), tr(R.string.flac_hi_res)),
        SettingEntry(S, tr(R.string.quality), tr(R.string.allow_access_to_lossless_files), tr(R.string.flac_permission), needs = lossless),
        SettingEntry(S, tr(R.string.quality), tr(R.string.scan_for_lossless_files_again), "flac", needs = lossless),
        SettingEntry(S, tr(R.string.quality), tr(R.string.streaming_quality_on_wi_fi), tr(R.string.bitrate_audio_quality), needs = notLossless),
        SettingEntry(S, tr(R.string.quality), tr(R.string.streaming_quality_on_mobile_data), tr(R.string.bitrate_audio_quality_cellular) + " " + tr(R.string.bitrate_cellular)),
        SettingEntry(S, tr(R.string.quality), tr(R.string.music_video_quality), tr(R.string.resolution_1080p_720p)),
        SettingEntry(S, tr(R.string.quality), tr(R.string.premium_quality), tr(R.string.youtube_music_premium_256_kbps_aac),
            needs = Needs(tr(R.string.sign_in_to_youtube_music_first)) { it.isLoggedIn }),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.data_saver), tr(R.string.cellular_low_data)),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.no_videos_on_mobile_data), tr(R.string.no_videos_on_mobile_data_keywords), needs = turnOn(tr(R.string.data_saver)) { it.dataSaver }),
        SettingEntry(S, tr(R.string.sound), tr(R.string.same_loudness_for_every_song), tr(R.string.loudness_lufs_level) + " " + tr(R.string.normalize_volume) + " " + tr(R.string.normalize_loudness_lufs_loud_quiet_broadcast)),
        SettingEntry(S, tr(R.string.sound), tr(R.string.albums_as_they_are), tr(R.string.album_mode_normalize_album_gain), needs = turnOn(tr(R.string.normalize_volume)) { it.normalize }),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.measure_songs_on_mobile_data), "cellular", needs = measuring),
        SettingEntry(S, tr(R.string.sound), tr(R.string.prism_spatial), tr(R.string.spatial_audio_3d_surround_atmos)),
        SettingEntry(S, tr(R.string.sound), tr(R.string.intensity), tr(R.string.spatial_audio_width), shown = tr(R.string.prism_spatial_intensity), needs = Needs(tr(R.string.turn_on_prism_spatial_first)) { it.spatial }),
        SettingEntry(S, tr(R.string.sound), tr(R.string.equalizer), tr(R.string.eq_bass_boost_treble_loudness_headphone)),
        // Lyrics
        SettingEntry(YL, tr(R.string.text), tr(R.string.focus_blur)),
        SettingEntry(YL, tr(R.string.effects), tr(R.string.progress_line), tr(R.string.spectrum_line_underline_sung_lyric)),
        SettingEntry(Y, tr(R.string.sources), tr(R.string.lyrics_source_button), tr(R.string.change_lyrics_source)),
        SettingEntry(Y, tr(R.string.language), tr(R.string.translate_into), tr(R.string.translation_language) + " " + tr(R.string.translate_lyrics)),
        SettingEntry(Y, tr(R.string.language), tr(R.string.romanize_lyrics), tr(R.string.romanization_romaji_pinyin_latin_letters_transli)),
        SettingEntry(Y, tr(R.string.language), tr(R.string.romanize_my_own_language_too), tr(R.string.romanization_native_script), needs = romanize),
        SettingEntry(Y, tr(R.string.language), tr(R.string.japanese_kanji_readings), tr(R.string.romaji_kanji_dictionary_download_kuromoji_japane)),
        SettingEntry(Y, tr(R.string.sing), tr(R.string.sing_s_voice_separation), tr(R.string.karaoke_vocals_remove_instrumental_download_mode)),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.get_songs_ready_for_sing_on), tr(R.string.karaoke_cellular_sing)),
        SettingEntry(Y, tr(R.string.sing), tr(R.string.get_songs_ready_while_sing_is), tr(R.string.karaoke_prepare_ahead_background_preload_vocals)),
        SettingEntry(Y, tr(R.string.sing), tr(R.string.room_for_sung_songs), tr(R.string.karaoke_storage_space)),
        SettingEntry(Y, tr(R.string.sing), tr(R.string.clear_sung_songs), tr(R.string.karaoke_storage_delete)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.alignment), tr(R.string.left_centre_center_right)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.text_size), tr(R.string.lyrics_font_size)),
        // GitHub requests in 1.7.5 (#111 #115 #116).
        SettingEntry(LB, tr(R.string.liked_songs), tr(R.string.a_song_and_its_video_as), tr(R.string.merge_liked_music_video_duplicate_same)),
        SettingEntry(SettingsSection.INTERFACE, tr(R.string.screen), tr(R.string.hide_the_status_bar), tr(R.string.status_bar_clock_notifications_full_screen)),
        SettingEntry(SettingsSection.INTERFACE, tr(R.string.screen), tr(R.string.hide_status_bar_on_the_player), tr(R.string.status_bar_clock_notifications_full_screen)),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.what_s_on_the_player), tr(R.string.the_volume_slider_changes), tr(R.string.volume_independent_app_volume_prism_s)),
        // Lyrics, your way (1.7.5).
        // 1.8.0 (#253): the lyrics look in parts; "Lyrics style" is now where you start from.
        SettingEntry(YL, tr(R.string.start_from), tr(R.string.lyrics_style), tr(R.string.prism_classic_karaoke_bold_card_glow) + " " + tr(R.string.lyrics_start_from_keywords)),
        SettingEntry(YL, tr(R.string.animation), tr(R.string.animation), tr(R.string.lyrics_animation_keywords)),
        SettingEntry(YL, tr(R.string.animation), tr(R.string.word_by_word_on_every_line), tr(R.string.lyrics_word_by_word_keywords)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.weight), tr(R.string.lyrics_bold_thin_light_heavy_font)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.line_spacing), tr(R.string.lyrics_gap_room_between_lines)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.other_lines), tr(R.string.lyrics_dim_faint_brightness_opacity_inactive)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.capitals), tr(R.string.uppercase_caps_all_capitals_lyrics)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.the_line_being_sung), tr(R.string.lyrics_color_sung_highlight_accent_spectrum)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.the_other_lines), tr(R.string.lyrics_color_dim_inactive)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.where_the_sung_line_sits), tr(R.string.lyrics_position_middle_centre_center_top)),
        SettingEntry(YL, tr(R.string.text), tr(R.string.lines_to_come), tr(R.string.lyrics_ahead_next_lines_hide_preview)),
        SettingEntry(YL, tr(R.string.effects), tr(R.string.glow), tr(R.string.lyrics_glow_keywords)),
        SettingEntry(YL, tr(R.string.effects), tr(R.string.shadow), tr(R.string.lyrics_text_shadow_readable_photo)),
        SettingEntry(YL, tr(R.string.effects), tr(R.string.background_vocals), tr(R.string.backing_vocals_size_ad_libs)),
        SettingEntry(YL, tr(R.string.effects), tr(R.string.instrumental_breaks), tr(R.string.instrumental_gap_dots_music_note_break)),
        SettingEntry(YL, tr(R.string.on_the_player), tr(R.string.the_line_on_the_player), tr(R.string.current_lyric_strip_center_centered_left)),
        SettingEntry(YL, tr(R.string.on_the_player), tr(R.string.up_to_two_lines), tr(R.string.current_lyric_strip_wrap_two_lines)),
        SettingEntry(YL, tr(R.string.translations_romanized_lines), tr(R.string.translation_look), tr(R.string.translation_romanization_size_color_italic_above)),
        SettingEntry(YL, tr(R.string.background), tr(R.string.behind_the_lyrics), tr(R.string.lyrics_background_dark_blurred_animated_cover) + " " + tr(R.string.bold_card)),
        SettingEntry(YL, tr(R.string.background), tr(R.string.immersive_mode_background), tr(R.string.immersive_background_cover_dark_blurred_color)),
        SettingEntry(Y, tr(R.string.sources), tr(R.string.prefer_karaoke_synced_lyrics), tr(R.string.word_by_word_time_synced)),
        SettingEntry(Y, tr(R.string.sources), tr(R.string.uncensor_lyrics), tr(R.string.explicit_swear_stars)),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.lyrics_on_mobile_data), tr(R.string.cellular_data_saver_metered_load_lyrics)),
        SettingEntry(Y, tr(R.string.sources), tr(R.string.order), tr(R.string.lyrics_providers_sources_priority)),
        SettingEntry(Y, tr(R.string.your_music_files), tr(R.string.read_lyrics_from_your_music_files), tr(R.string.flac_mp3_tags_embed)),
        SettingEntry(Y, tr(R.string.your_music_files), tr(R.string.save_lyrics_into_your_music_files), tr(R.string.flac_mp3_tags_embed)),
        SettingEntry(Y, tr(R.string.your_music_files), tr(R.string.keep_word_timings), tr(R.string.lyrics_file_words_keywords)),
        SettingEntry(Y, tr(R.string.your_music_files), tr(R.string.include_the_translation), tr(R.string.lyrics_file_translation_keywords)),
        SettingEntry(DV, tr(R.string.android_auto), tr(R.string.lyrics_in_the_car), tr(R.string.android_auto_2)),
        SettingEntry(DV, tr(R.string.android_auto), tr(R.string.style), tr(R.string.android_auto_car), needs = carLyrics),
        SettingEntry(DV, tr(R.string.android_auto), tr(R.string.keep_the_song_title), tr(R.string.android_auto_car), needs = carLyrics),
        SettingEntry(DV, tr(R.string.android_auto), tr(R.string.on_this_phone_too), tr(R.string.lock_screen_notification), needs = carLyrics),
        SettingEntry(DV, tr(R.string.android_auto), tr(R.string.covers_in_a_grid), tr(R.string.android_auto_car_home_tiles_grid)),
        SettingEntry(DV, tr(R.string.android_auto), tr(R.string.buttons_in_the_car), tr(R.string.android_auto_car_buttons_shuffle_like)),
        SettingEntry(DV, tr(R.string.connections), tr(R.string.speakers_tvs_browser), tr(R.string.prism_connect_cast_remote_handoff)),
        SettingEntry(DV, tr(R.string.connections), tr(R.string.discord), tr(R.string.rich_presence_status)),
        SettingEntry(DV, tr(R.string.connections), "Last.fm", tr(R.string.lastfm_last_fm_scrobble_scrobbling)),
        SettingEntry(DV, tr(R.string.sharing_and_links), tr(R.string.sharing_and_links), tr(R.string.share_link)),
        SettingEntry(DV, tr(R.string.sharing_and_links), tr(R.string.links_you_share), tr(R.string.share_link_youtube_youtu_be_youtube)),
        SettingEntry(DV, tr(R.string.sharing_and_links), tr(R.string.open_youtube_music_links_in_prism), tr(R.string.open_by_default_supported_links_music)),
        // Downloads & storage
        SettingEntry(D, tr(R.string.downloads), tr(R.string.manage_downloads), tr(R.string.offline_delete)),
        SettingEntry(D, tr(R.string.downloads), tr(R.string.download_on_wi_fi_only), tr(R.string.cellular_mobile_data)),
        SettingEntry(D, tr(R.string.downloads), tr(R.string.download_liked_songs_automatically), tr(R.string.offline_likes)),
        SettingEntry(D, tr(R.string.downloads), tr(R.string.keep_playlists_downloaded), tr(R.string.keep_playlists_keywords)),
        // #314 (1.8.1).
        SettingEntry(D, tr(R.string.your_own_song_files), tr(R.string.use_your_own_song_files), tr(R.string.own_files_keywords)),
        SettingEntry(D, tr(R.string.your_own_song_files), tr(R.string.scan_your_song_files_again), tr(R.string.own_files_keywords), needs = ownFiles),
        SettingEntry(D, tr(R.string.local_files), tr(R.string.folders_in_local_files), tr(R.string.local_folders_keywords)),
        SettingEntry(D, tr(R.string.smart_downloads), tr(R.string.smart_downloads), tr(R.string.offline_cache_storage_space)),
        SettingEntry(D, tr(R.string.smart_downloads), tr(R.string.space_to_use), tr(R.string.storage_cache_size_gb)),
        SettingEntry(D, tr(R.string.mobile_data), tr(R.string.smart_downloads_on_mobile_data), "cellular", needs = smart),
        SettingEntry(D, tr(R.string.smart_downloads), tr(R.string.clear_smart_downloads), tr(R.string.cache_delete_free_space)),
        // Home screen
        SettingEntry(H, tr(R.string.home), tr(R.string.customize_home), tr(R.string.sections_order_shortcuts_quick_picks_speed)),
        SettingEntry(H, tr(R.string.home), tr(R.string.add_new_sections_automatically), "shelves"),
        SettingEntry(H, tr(R.string.home), tr(R.string.quick_picks_from), tr(R.string.quick_picks_from_keywords)),
        SettingEntry(H, tr(R.string.home), tr(R.string.speed_dial_your_plays_first), tr(R.string.speed_dial_your_plays_first_sub),
            needs = Needs(tr(R.string.turn_off) + " · " + tr(R.string.speed_dial_youtube_music_s_only)) { !it.speedDialYouTubeOnly }),
        SettingEntry(H, tr(R.string.home), tr(R.string.continue_line), tr(R.string.continue_album_resume_greeting_hide_context)),
        SettingEntry(H, tr(R.string.replay_card), tr(R.string.replay_card), tr(R.string.replay_teaser_look_style_design_quiet)),
        SettingEntry(H, tr(R.string.replay_card), tr(R.string.replay_card_graph), tr(R.string.replay_card_graph_keywords)),
        SettingEntry(H, tr(R.string.home), tr(R.string.buttons_at_the_top_of_home), tr(R.string.podcasts_button) + " " + tr(R.string.customize_button_at_the_top)),
        SettingEntry(H, tr(R.string.search), tr(R.string.search)),
        SettingEntry(H, tr(R.string.home), tr(R.string.customize_button_at_the_bottom)),
        SettingEntry(SettingsSection.LANGUAGE, tr(R.string.region), tr(R.string.content_country), tr(R.string.region_location_gl)),
        // Prism Connect
        SettingEntry(C, tr(R.string.speakers_tvs), tr(R.string.play_on_a_speaker_or_tv), tr(R.string.cast_casting_google_cast_google_tv)),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.desk_mode), tr(R.string.desk_mode), tr(R.string.desk_mode_keywords)),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_title), tr(R.string.lock_screen_keywords)),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_allow), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_app_info), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_allow_step), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_layout_label), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_show), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_paused_label), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(SettingsSection.PLAYER, tr(R.string.lock_screen_title), tr(R.string.lock_screen_hide), tr(R.string.lock_screen_keywords), needs = lockScreen),
        SettingEntry(C, tr(R.string.prism_on_a_tv), tr(R.string.share_prism_with_a_tv), tr(R.string.tv_install_keywords)),
        SettingEntry(C, tr(R.string.prism_on_a_tv), tr(R.string.downloader_code), tr(R.string.tv_install_keywords)),
        SettingEntry(C, tr(R.string.control_from_a_browser), tr(R.string.control_from_a_browser), tr(R.string.web_remote_computer_laptop_tablet_browser)),
        SettingEntry(C, tr(R.string.control_from_a_browser), tr(R.string.address), tr(R.string.web_remote_url_link_ip), needs = webRemote),
        SettingEntry(C, tr(R.string.control_from_a_browser), tr(R.string.pairing_code), tr(R.string.web_remote_pair_code_browser), shown = tr(R.string.browser_pairing_code), needs = webRemote),
        SettingEntry(C, tr(R.string.control_from_a_browser), tr(R.string.unpair_all_browsers), tr(R.string.web_remote_forget_browsers), needs = webRemote),
        SettingEntry(C, tr(R.string.handoff), tr(R.string.available_for_handoff), tr(R.string.move_playback_another_device_tablet_phone)),
        SettingEntry(C, tr(R.string.handoff), tr(R.string.pairing_code), tr(R.string.handoff_pair_code), shown = tr(R.string.handoff_pairing_code)),
        SettingEntry(LB, tr(R.string.made_for_you), tr(R.string.your_artists), tr(R.string.onboarding_pick_artists_favourites_starred_recom)),
        SettingEntry(LB, tr(R.string.made_for_you), tr(R.string.new_release_notifications), tr(R.string.release_radar_new_music_alerts)),
        SettingEntry(LB, tr(R.string.made_for_you), tr(R.string.make_my_mixes_again), tr(R.string.daily_mix_refresh_recommendations)),
        SettingEntry(LB, tr(R.string.tune_radios), tr(R.string.tune_familiar_new), tr(R.string.tune_radios) + " " + tr(R.string.autoplay)),
        SettingEntry(LB, tr(R.string.tune_radios), tr(R.string.tune_hits_deep), tr(R.string.tune_radios) + " " + tr(R.string.autoplay)),
        SettingEntry(LB, tr(R.string.tune_radios), tr(R.string.tune_calm_energetic), tr(R.string.tune_radios) + " " + tr(R.string.autoplay)),
        // Library & Replay
        SettingEntry(H, tr(R.string.library), tr(R.string.playlists_as), tr(R.string.grid_list_view)),
        SettingEntry(H, tr(R.string.library), tr(R.string.albums_as), tr(R.string.grid_list_view)),
        SettingEntry(H, tr(R.string.library), tr(R.string.artists_as), tr(R.string.grid_list_view)),
        SettingEntry(H, tr(R.string.library), tr(R.string.covers_across), tr(R.string.columns_grid_two_three_four)),
        SettingEntry(H, tr(R.string.library), tr(R.string.details_under_each_one), tr(R.string.subtitles_counts_song_count_owner_year)),
        SettingEntry(H, tr(R.string.library), tr(R.string.open_library_on), tr(R.string.start_tab)),
        SettingEntry(H, tr(R.string.library), tr(R.string.full_width_playlist_pictures)),
        SettingEntry(H, tr(R.string.library), tr(R.string.genres_on_playlists), tr(R.string.genres_on_playlists_keywords)),
        SettingEntry(H, tr(R.string.library), tr(R.string.album_names_in_downloads), tr(R.string.album_names_in_downloads_keywords)),
        SettingEntry(H, tr(R.string.library), tr(R.string.library_tiles), tr(R.string.liked_downloads_local_files_replay_podcasts)),
        SettingEntry(H, tr(R.string.artist_pages), tr(R.string.artist_you_title), tr(R.string.artist_you_detail)),
        SettingEntry(H, tr(R.string.artist_pages), tr(R.string.artist_essentials_title), tr(R.string.artist_essentials_detail)),
        SettingEntry(H, tr(R.string.artist_pages), tr(R.string.artist_bio_title), tr(R.string.artist_bio_keywords)),
        SettingEntry(LB, tr(R.string.replay_2), tr(R.string.show_time_in_hours), tr(R.string.stats_minutes)),
        SettingEntry(LB, tr(R.string.replay_2), tr(R.string.use_theme_colors), tr(R.string.colors_stats)),
        SettingEntry(LB, tr(R.string.replay_2), tr(R.string.rewind_notification), tr(R.string.rewind_wrapped_year_in_review_december)),
        SettingEntry(LB, tr(R.string.replay_2), tr(R.string.replay_story_font), tr(R.string.replay_story_font_keywords)),
        // 1.8.1: History (#296, #263).
        SettingEntry(LB, tr(R.string.hist_title), tr(R.string.hist_pause), tr(R.string.hist_pause_keywords)),
        SettingEntry(LB, tr(R.string.hist_title), tr(R.string.hist_count_after), tr(R.string.hist_count_keywords)),
        SettingEntry(
            LB, tr(R.string.hist_title), tr(R.string.hist_count_account), tr(R.string.hist_count_keywords),
            needs = Needs(tr(R.string.hist_needs_account)) { it.isLoggedIn },
        ),
        // 1.8.1: Bring your history.
        SettingEntry(LB, tr(R.string.imp_title), tr(R.string.imp_title), tr(R.string.imp_keywords)),
        SettingEntry(LB, tr(R.string.imp_title), tr(R.string.imp_shape_mixes), tr(R.string.imp_shape_mixes_keywords)),
        // On the Bring your history page (opened from this group): the fresh-download reminder.
        SettingEntry(LB, tr(R.string.imp_title), tr(R.string.imp_remind), tr(R.string.imp_remind_keywords)),
        // And YouTube Music (live): the account's History page every few hours (signed in).
        SettingEntry(
            LB, tr(R.string.imp_title), tr(R.string.ytlive_title), tr(R.string.ytlive_keywords),
            needs = Needs(tr(R.string.ytlive_sign_in)) { it.isLoggedIn },
        ),
        SettingEntry(LB, tr(R.string.replay_2), tr(R.string.imp_count_history_from), tr(R.string.imp_count_keywords)),
        SettingEntry(LB, tr(R.string.replay_2), tr(R.string.imp_prism_plays)),
        SettingEntry(LB, tr(R.string.made_for_you), tr(R.string.exclusions), tr(R.string.taste_profile_exclude_artist_playlist_don)),
        SettingEntry(LB, tr(R.string.made_for_you), tr(R.string.forget_skipped_suggestions), tr(R.string.skipped_suggestions_keywords)),
        // About
        SettingEntry(B, tr(R.string.backup), tr(R.string.back_up), tr(R.string.backup_export_save_move_new_phone)),
        SettingEntry(B, tr(R.string.backup), tr(R.string.restore_from_a_backup), tr(R.string.import_move_new_phone)),
        SettingEntry(B, tr(R.string.about_3), tr(R.string.app_name), tr(R.string.version_github)),
        SettingEntry(B, tr(R.string.about_3), tr(R.string.your_data_stays_here), "privacy"),
        SettingEntry(B, tr(R.string.about_3), tr(R.string.check_for_updates), tr(R.string.update_new_version)),
        SettingEntry(B, tr(R.string.about_3), tr(R.string.take_the_tour), tr(R.string.tutorial_walkthrough_help_guide_what_s)),
        SettingEntry(B, tr(R.string.about_3), tr(R.string.open_source_licenses), tr(R.string.license_gpl)),
        SettingEntry(B, tr(R.string.diagnostics), tr(R.string.playback_diagnostics), tr(R.string.stutter_judder_skip_glitch_lag_bluetooth)),
        SettingEntry(B, tr(R.string.diagnostics), tr(R.string.share_diagnostics), tr(R.string.log_send_report)),
        SettingEntry(B, tr(R.string.diagnostics), tr(R.string.clear_diagnostics), tr(R.string.log_reset)),
        SettingEntry(B, tr(R.string.diagnostics), tr(R.string.last_crash), tr(R.string.crash_closed_unexpectedly_error_report_stack)),
        SettingEntry(B, tr(R.string.diagnostics), tr(R.string.forget_crashes), tr(R.string.crash_delete_clear)),
    )

    /**
     * Settings matching [query]: every word typed has to turn up in the title, group, page or keywords. Titles that start
     * with the query come first, then titles that contain it, then the rest.
     */
    fun search(query: String): List<SettingEntry> {
        val q = SearchRank.norm(query)
        if (q.isBlank()) return emptyList()
        val words = q.split(' ')
        return prepared.filter { p -> words.all { it in p.hay } }
            .sortedBy { p -> when { p.title.startsWith(q) -> 0; q in p.title -> 1; else -> 2 } }
            .map { it.entry }
    }

    /** An entry's words as [search] compares them, read once: it runs while composing, for every letter typed (#355). */
    private class Prepared(val entry: SettingEntry, val title: String, val hay: String)

    private val prepared: List<Prepared> by lazy {
        entries.map { e -> Prepared(e, SearchRank.norm(e.title), SearchRank.norm("${e.title} ${e.group} ${e.section?.title ?: "Settings"} ${e.keywords}")) }
    }

    /** Reads every entry's words now, so that a first letter doesn't. For a background thread. */
    fun prepare() { prepared.size }
}

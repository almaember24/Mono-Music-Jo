package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.MenuAction
import com.prism.music.data.prefs.MenuGroup
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.episodeMenuFromPlayer
import com.prism.music.data.prefs.menuFolds
import com.prism.music.data.prefs.menuFromPlayer
import com.prism.music.data.prefs.menuLayout
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.data.shown
import com.prism.music.ui.components.ActionHeader
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.PlaylistPicker
import com.prism.music.ui.components.QuickActions
import com.prism.music.ui.components.SheetIconButton
import com.prism.music.ui.components.pane
import com.prism.music.ui.components.runAction
import com.prism.music.ui.components.shareTarget
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi

/**
 * The player's ⋯ menu, in the app's frosted look (the song's cover as frosted glass): like and share up top, four quick
 * tiles, then This song, Lyrics, Playing and Help (1.8.0). What the listener hid with "Edit this menu" folds under "More"
 * at the end, never gone; "Player settings" and "Edit this menu" always stay. What the player's hidden parts did (its
 * lyrics and queue buttons, previous / next…) shows under Playing, so hiding a part never takes a feature away.
 */
@Composable
fun PlayerOptionsSheet(
    song: Song,
    hasCanvas: Boolean,
    statsShown: Boolean,
    hasNext: Boolean,
    onLyricsSource: () -> Unit,
    onLyricsTiming: () -> Unit,
    onRedownloadLyrics: () -> Unit,
    onToggleStats: () -> Unit,
    onSleepTimer: () -> Unit,
    onCollapse: () -> Unit,
    /** Speed & pitch for songs (1.7.8, R12). */
    onSpeedPitch: () -> Unit = {},
    /** The lyrics have lines in a script other than Latin letters: romanizing them is offered. */
    romanizable: Boolean = false,
    onSing: () -> Unit = {},
    // What the player's hidden parts did (1.8.0).
    onOpenLyrics: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    videoMode: Boolean = false,
    videoAvailable: Boolean = false,
    onVideoMode: (Boolean) -> Unit = {},
    onButton: (PlayerButton) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloadState.collectAsState()
    val videos by c.videoState.collectAsState()
    val hidden by c.canvas.hidden.collectAsState()
    val saved by c.canvas.saved.collectAsState()
    val sleepAt by c.player.sleepAt.collectAsState()
    val sleepEnd by c.player.sleepEndOfSong.collectAsState()
    val together by c.together.state.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    var outputs by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    if (credits) { com.prism.music.ui.components.SongCreditsSheet(song) { credits = false; onDismiss() }; return }
    var details by remember { mutableStateOf(false) }
    if (details) { com.prism.music.ui.components.SongDetailsSheet(song) { details = false; onDismiss() }; return }
    var versions by remember { mutableStateOf(false) }
    val picks by c.versions.picks.collectAsState()
    if (versions) { com.prism.music.ui.components.SongVersionsSheet(song) { versions = false; onDismiss() }; return }
    // #305: the song's own cover, in the menu's place.
    var coverPick by remember { mutableStateOf(false) }
    if (coverPick) { com.prism.music.ui.components.CoverPickSheet(com.prism.music.ui.components.CoverFor.OfSong(song, c.covers.songKey(song.id))) { coverPick = false; onDismiss() }; return }
    if (pickPlaylist) { PlaylistPicker(song) { pickPlaylist = false; onDismiss() }; return }
    var tagging by remember { mutableStateOf(false) }
    if (tagging) { com.prism.music.ui.components.TagPicker(listOf(song), song.title) { tagging = false; onDismiss() }; return }
    val songTags by remember(song.id) { c.tags.tagsOf(song.id) }.collectAsState(emptyList())
    // Speakers and TVs, from the menu too (Reddit 1.7: "where's the cast button?").
    if (outputs) {
        val profile by c.account.profile.collectAsState()
        val name = (profile?.name ?: c.settings.current.accountName).trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { tr(R.string.s_phone, it) } ?: tr(R.string.this_phone)
        OutputPickerSheet(name) { outputs = false; onDismiss() }
        return
    }
    if (sharing) { com.prism.music.ui.components.ShareSheet(song.shareTarget()) { sharing = false; onDismiss() }; return }
    // Edit this menu, in its place; done, the menu comes back as it now is.
    if (editing) { MenuEditorSheet { editing = false }; return }

    val isLiked = song.id in liked
    val dl = downloads[song.id]
    val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    val sleepMin = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1 else 0
    val sleeping = sleepAt > 0 || sleepEnd
    val inSession = together !is com.prism.music.playback.together.TogetherState.Idle
    val downloadItem = ActionItem(
        if (downloaded) PrismIcons.DownloadDone else PrismIcons.Download, tr(R.string.download),
        value = when { downloaded -> tr(R.string.downloaded); dl != null -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> null },
        active = downloaded, keepOpen = true,
    ) { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) }
    val sleepItem = ActionItem(
        PrismIcons.Moon, tr(R.string.sleep_timer),
        value = when { sleepEnd -> tr(R.string.song_end); sleepAt > 0 -> tr(R.string.min, sleepMin); else -> null },
        active = sleeping, onClick = onSleepTimer,
    )

    val speed by c.player.episodeSpeed.collectAsState()
    val statsItem = ActionItem(PrismIcons.Stats, tr(R.string.stats_for_nerds), active = statsShown, onClick = onToggleStats)
    if (song.isEpisode) {
        val speeds = EPISODE_SPEEDS
        val episode = listOfNotNull(
            ActionItem(
                PrismIcons.Speed, tr(R.string.playback_speed_2), value = "${speed.toString().removeSuffix(".0")}×", active = speed != 1f, keepOpen = true,
            ) { c.player.setEpisodeSpeed(speeds[(speeds.indexOf(speed) + 1) % speeds.size]) },
            song.podcastId?.let { id -> ActionItem(PrismIcons.Podcasts, tr(R.string.go_to_podcast)) { onCollapse(); nav.go(Routes.podcast(id)) } },
            // What the episode's hidden row did (1.8.0): its queue.
            if (MenuAction.OPEN_QUEUE in c.settings.flow.collectAsState().value.episodeMenuFromPlayer())
                ActionItem(PrismIcons.Queue, tr(R.string.up_next), onClick = onOpenQueue) else null,
            statsItem,
        )
        ActionSheet(com.prism.music.data.PlaylistCovers.artOf(song), onDismiss) { close ->
            ActionHeader(com.prism.music.data.PlaylistCovers.artOf(song), song.shown.title, song.shown.artistText) {
                // (A feed's episode has no address on YouTube to share, and doesn't download.)
                if (!song.isRss) SheetIconButton(PrismIcons.Share, tr(R.string.share)) { sharing = true }
            }
            // An episode has no radio and goes in no playlist: download and sleep.
            QuickActions(listOfNotNull(downloadItem.takeIf { !song.isRss }, sleepItem.tile(MenuAction.SLEEP))) { runAction(it, close) }
            ActionGroup(tr(R.string.episode), episode) { runAction(it, close) }
        }
        return
    }

    val settings = c.settings.flow.collectAsState().value
    val translating = settings.lyricsTranslate
    // Says which language, so turning it on for a song already in it explains itself.
    val translateInto = translateTargetName(settings)
    val singOn = c.sing.on.collectAsState().value
    val djOn = c.dj.on.collectAsState().value
    val cover = com.prism.music.data.canvas.CanvasRepository.albumKey(song)
    val musicSpeed by c.player.musicSpeed.collectAsState()
    val musicPitch by c.player.musicPitch.collectAsState()
    val local = song.isLocal
    val fromPlayer = settings.menuFromPlayer().toSet()

    // Every action this song has, by what it is ("Go to" one row per artist). A file on the phone has no radio, download,
    // music video or speed (a Prism playlist is fine).
    val items = linkedMapOf<MenuAction, List<ActionItem>>()
    fun put(a: MenuAction, item: ActionItem?) { if (item != null) items[a] = listOf(item) }
    // This song
    put(MenuAction.ADD_TO_PLAYLIST, ActionItem(PrismIcons.PlaylistAdd, tr(R.string.add_to_playlist), keepOpen = true) { pickPlaylist = true })
    put(MenuAction.TAGS, ActionItem(PrismIcons.Tag, tr(R.string.add_a_tag), value = com.prism.music.ui.components.tagsValue(songTags), active = songTags.isNotEmpty(), keepOpen = true) { tagging = true })
    if (!local) put(MenuAction.RADIO, ActionItem(PrismIcons.Radio, tr(R.string.start_radio)) { c.player.playRadio(song) })
    if (!local) put(MenuAction.DOWNLOAD, downloadItem)
    put(MenuAction.ALBUM, song.shown.album?.id?.let { id -> ActionItem(PrismIcons.Album, tr(R.string.go_to_album)) { onCollapse(); nav.go(Routes.album(id)) } })
    com.prism.music.ui.components.goToArtists(song) { route -> onCollapse(); nav.go(route) }.takeIf { it.isNotEmpty() }?.let { items[MenuAction.ARTIST] = it }
    if (!song.isEpisode && !local && !song.isLive) put(MenuAction.CREDITS, ActionItem(PrismIcons.Info, tr(R.string.song_credits), keepOpen = true) { credits = true })
    // As in the song menu: YouTube has nothing to say about the listener's own uploads.
    if (!song.isEpisode && !local && !song.isLive && !song.isUpload) put(MenuAction.DETAILS, ActionItem(PrismIcons.Replay, tr(R.string.song_details), keepOpen = true) { details = true })
    // Another recording in its place (Reddit 1.7.9: an album song played as someone's cover).
    if (!local && !song.isLive && together !is com.prism.music.playback.together.TogetherState.Guest) put(
        MenuAction.VERSION, ActionItem(PrismIcons.SwapVertical, tr(R.string.play_a_different_version), active = song.id in picks, keepOpen = true) { versions = true },
    )
    // #305: a cover of the listener's choosing (a photo, another version's, or back to its own).
    if (!song.isLive) put(MenuAction.COVER, ActionItem(PrismIcons.Photo, tr(R.string.change_cover), active = c.covers.custom(c.covers.songKey(song.id)) != null, keepOpen = true) { coverPick = true })
    put(
        MenuAction.NOT_FOR_ME,
        if (song.id in disliked) ActionItem(PrismIcons.ThumbDownFilled, tr(R.string.not_for_me), active = true, keepOpen = true) { c.library.setDisliked(song, false) }
        else ActionItem(PrismIcons.ThumbDown, tr(R.string.not_for_me)) {
            c.library.setDisliked(song, true)
            toast(tr(R.string.got_it_prism_will_steer_clear))
            if (hasNext) c.player.next()
        },
    )
    // Lyrics
    put(MenuAction.SING, ActionItem(PrismIcons.Mic, tr(R.string.sing), value = if (singOn) tr(R.string.on_2) else null, active = singOn, onClick = onSing))
    put(MenuAction.LYRICS_SOURCE, ActionItem(PrismIcons.Lyrics, tr(R.string.lyrics_source), onClick = onLyricsSource))
    put(MenuAction.LYRICS_TIMING, ActionItem(PrismIcons.Timer, tr(R.string.lyrics_timing), onClick = onLyricsTiming))
    put(
        MenuAction.TRANSLATE,
        ActionItem(PrismIcons.Translate, tr(R.string.translate_lyrics), value = if (translating) tr(R.string.into, translateInto) else tr(R.string.off), active = translating, keepOpen = true) {
            c.settings.setLyricsTranslate(!translating)
        },
    )
    // This song in Latin letters, or not (whatever Settings → Lyrics says for songs in general).
    if (romanizable) {
        val romanOn = romanizeMode(song.id) != com.prism.music.data.prefs.RomanizeMode.OFF
        put(MenuAction.ROMANIZE, ActionItem(PrismIcons.Romanize, tr(R.string.romanize_lyrics), value = if (romanOn) tr(R.string.on_2) else tr(R.string.off), active = romanOn, keepOpen = true) {
            c.romanizer.setOverride(song.id, !romanOn)
        })
    }
    put(MenuAction.LYRICS_AGAIN, ActionItem(PrismIcons.Refresh, tr(R.string.download_lyrics_again)) { onRedownloadLyrics(); toast(tr(R.string.downloading_lyrics_again)) })
    // Into your own FLAC / MP3 file, for other players (#16).
    if (local || c.downloads.downloads.value[song.id]?.lossless == true) put(MenuAction.LYRICS_INTO_FILE, ActionItem(PrismIcons.Download, tr(R.string.save_lyrics_into_the_file)) { c.lyricsToFiles.request(listOf(song)) })
    // Playing: first what the player's hidden parts did.
    if (MenuAction.OPEN_LYRICS in fromPlayer) put(MenuAction.OPEN_LYRICS, ActionItem(PrismIcons.Lyrics, tr(R.string.show_lyrics), onClick = onOpenLyrics))
    if (MenuAction.OPEN_QUEUE in fromPlayer) put(MenuAction.OPEN_QUEUE, ActionItem(PrismIcons.Queue, tr(R.string.up_next), onClick = onOpenQueue))
    if (MenuAction.VIDEO_SWITCH in fromPlayer && !local && (videoAvailable || videoMode)) put(
        MenuAction.VIDEO_SWITCH, ActionItem(PrismIcons.Video, if (videoMode) tr(R.string.back_to_the_song) else tr(R.string.music_video_3), active = videoMode) { onVideoMode(!videoMode) },
    )
    if (MenuAction.PREVIOUS in fromPlayer) put(MenuAction.PREVIOUS, ActionItem(PrismIcons.Previous, tr(R.string.previous_song), keepOpen = true) { c.player.previous() })
    if (MenuAction.NEXT in fromPlayer && hasNext) put(MenuAction.NEXT, ActionItem(PrismIcons.Next, tr(R.string.next_song), keepOpen = true) { c.player.next() })
    if (MenuAction.DJ in fromPlayer) put(MenuAction.DJ, ActionItem(if (djOn) PrismIcons.TurntableFilled else PrismIcons.Turntable, tr(R.string.dj_mode), active = djOn, keepOpen = true) { c.dj.setOn(!djOn) })
    if (MenuAction.EQUALIZER in fromPlayer) put(MenuAction.EQUALIZER, ActionItem(PrismIcons.Equalizer, tr(R.string.equalizer)) { onButton(PlayerButton.EQUALIZER) })
    put(MenuAction.SLEEP, sleepItem)
    if (!local && !song.isLive) put(
        MenuAction.SPEED_PITCH,
        ActionItem(
            PrismIcons.Speed, tr(R.string.speed_pitch_2),
            value = if (musicSpeed == 1f && musicPitch == 0) null else "${com.prism.music.ui.screens.speedText(musicSpeed)} · ${com.prism.music.ui.screens.pitchText(musicPitch)}",
            active = musicSpeed != 1f || musicPitch != 0, onClick = onSpeedPitch,
        ),
    )
    put(MenuAction.OUTPUT, ActionItem(PrismIcons.Cast, tr(R.string.play_on_a_speaker_or_tv), keepOpen = true) { outputs = true })
    // The cover fills the screen with the lyrics over it; a tap there brings this back.
    val immersive = settings.immersivePlayer
    put(MenuAction.IMMERSIVE, ActionItem(PrismIcons.Fullscreen, tr(R.string.immersive_mode), value = if (immersive) tr(R.string.on_2) else null, active = immersive) { c.settings.setImmersivePlayer(!immersive) })
    if (!local) put(MenuAction.VIDEO_DOWNLOAD, com.prism.music.ui.components.videoDownloadAction(song, videos[song.id]))
    if (!local) put(MenuAction.LISTEN_TOGETHER, ActionItem(PrismIcons.Groups, tr(R.string.listen_together), value = if (inSession) tr(R.string.on_2) else null, active = inSession) { onCollapse(); nav.go(Routes.TOGETHER) })
    // Where Best part takes you in this song (also: hold the Best part button).
    if (!song.isLive) put(
        MenuAction.BEST_PART,
        ActionItem(PrismIcons.Flame, tr(R.string.your_best_part), value = c.bestParts.mine(song.id)?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) }) { BestPartEditor.open = true },
    )
    if (hasCanvas && !local) {
        put(MenuAction.ANIMATED_COVER, ActionItem(PrismIcons.AnimatedCover, tr(R.string.animated_cover), active = song.id !in hidden, keepOpen = true) { c.canvas.setHidden(song.id, song.id !in hidden) })
        put(
            MenuAction.SAVE_ANIMATED_COVER,
            if (saved.has(song.id, cover)) ActionItem(PrismIcons.Download, tr(R.string.animated_cover_saved), active = true, keepOpen = true) { c.canvasStore.unsave(song) }
            else ActionItem(PrismIcons.Download, tr(R.string.save_animated_cover), keepOpen = true) {
                c.canvasStore.save(song)
                toast(tr(R.string.saving_animated_covers_for_this_album))
            },
        )
    }
    // Help
    put(MenuAction.STATS, statsItem)
    // Playback diagnostics are on (Settings → About): the listener marks the moment they heard a stutter.
    if (settings.audioDiagnostics) put(MenuAction.MARK_STUTTER, ActionItem(PrismIcons.Info, tr(R.string.mark_a_stutter)) {
        val at = c.player.position
        com.prism.music.playback.AudioDiagnostics.log(
            "mark", tr(R.string.the_listener_heard_a_stutter_in, song.title, song.artistText, song.id).format(at / 60_000, at / 1000 % 60),
        )
        toast(tr(R.string.marked_at_d_02d).format(at / 60_000, at / 1000 % 60))
    })

    val layout = settings.view.parts.menuLayout(items.keys, settings.menuFolds())
    var moreOpen by remember { mutableStateOf(false) }
    ActionSheet(com.prism.music.data.PlaylistCovers.artOf(song), onDismiss) { close ->
        ActionHeader(com.prism.music.data.PlaylistCovers.artOf(song), song.shown.title, song.shown.artistText) {
            SheetIconButton(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, if (isLiked) tr(R.string.remove_from_liked) else tr(R.string.widget_like), active = isLiked) {
                c.library.toggleLike(song)
            }
            if (!local) SheetIconButton(PrismIcons.Share, tr(R.string.share)) { sharing = true }
        }
        QuickActions(layout.tiles.mapNotNull { a -> items[a]?.firstOrNull()?.tile(a) }) { runAction(it, close) }
        layout.groups.forEach { (group, actions) -> ActionGroup(group.label, actions.flatMap { items[it].orEmpty() }) { runAction(it, close) } }
        // Hidden with Edit this menu: folded here, a tap away.
        if (layout.more.isNotEmpty()) {
            val fold = ActionItem(if (moreOpen) PrismIcons.ChevronUp else PrismIcons.ChevronDown, tr(R.string.more), value = if (moreOpen) null else "${layout.more.size}", keepOpen = true) { moreOpen = !moreOpen }
            ActionGroup(null, listOf(fold) + if (moreOpen) layout.more.flatMap { items[it].orEmpty() } else emptyList()) { runAction(it, close) }
        }
        // The player's look, changed right here over the player ("Customize this", 1.7.3), with the whole Player page a
        // tap further; Settings → Player where there's no sheet to open. Edit this menu beside it. Both always stay.
        val customize = com.prism.music.ui.components.LocalCustomize.current
        val settingsRow = ActionItem(PrismIcons.Settings, tr(R.string.player_settings)) {
            if (customize != null) customize(com.prism.music.ui.components.CustomizeTarget.PLAYER, null)
            else { onCollapse(); nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.PLAYER.key)) }
        }
        val editRow = ActionItem(PrismIcons.Edit, tr(R.string.edit_this_menu), keepOpen = true) { editing = true }
        ActionGroup(null, listOf(settingsRow, editRow)) { runAction(it, close) }
    }
}

/** A tile's own short word where the row's would be long (the menu's first four, as they always read). */
private fun ActionItem.tile(a: MenuAction): ActionItem {
    val short = when (a) {
        MenuAction.RADIO -> tr(R.string.radio_2)
        MenuAction.ADD_TO_PLAYLIST -> tr(R.string.playlist)
        MenuAction.SLEEP -> tr(R.string.sleep)
        else -> return this
    }
    return ActionItem(icon, short, value, active, keepOpen, danger, onClick)
}

/** An action's icon and name in Edit this menu, whatever song is playing. */
internal fun menuIcon(a: MenuAction): ImageVector = when (a) {
    MenuAction.ADD_TO_PLAYLIST -> PrismIcons.PlaylistAdd
    MenuAction.TAGS -> PrismIcons.Tag
    MenuAction.RADIO -> PrismIcons.Radio
    MenuAction.DOWNLOAD -> PrismIcons.Download
    MenuAction.ALBUM -> PrismIcons.Album
    MenuAction.ARTIST -> PrismIcons.Person
    MenuAction.CREDITS -> PrismIcons.Info
    MenuAction.DETAILS -> PrismIcons.Replay
    MenuAction.VERSION -> PrismIcons.SwapVertical
    MenuAction.COVER -> PrismIcons.Photo
    MenuAction.NOT_FOR_ME -> PrismIcons.ThumbDown
    MenuAction.SING -> PrismIcons.Mic
    MenuAction.LYRICS_SOURCE -> PrismIcons.Lyrics
    MenuAction.LYRICS_TIMING -> PrismIcons.Timer
    MenuAction.TRANSLATE -> PrismIcons.Translate
    MenuAction.ROMANIZE -> PrismIcons.Romanize
    MenuAction.LYRICS_AGAIN -> PrismIcons.Refresh
    MenuAction.LYRICS_INTO_FILE -> PrismIcons.Download
    MenuAction.OPEN_LYRICS -> PrismIcons.Lyrics
    MenuAction.OPEN_QUEUE -> PrismIcons.Queue
    MenuAction.VIDEO_SWITCH -> PrismIcons.Video
    MenuAction.PREVIOUS -> PrismIcons.Previous
    MenuAction.NEXT -> PrismIcons.Next
    MenuAction.DJ -> PrismIcons.Turntable
    MenuAction.EQUALIZER -> PrismIcons.Equalizer
    MenuAction.SLEEP -> PrismIcons.Moon
    MenuAction.SPEED_PITCH -> PrismIcons.Speed
    MenuAction.OUTPUT -> PrismIcons.Cast
    MenuAction.IMMERSIVE -> PrismIcons.Fullscreen
    MenuAction.VIDEO_DOWNLOAD -> PrismIcons.Video
    MenuAction.LISTEN_TOGETHER -> PrismIcons.Groups
    MenuAction.BEST_PART -> PrismIcons.Flame
    MenuAction.ANIMATED_COVER -> PrismIcons.AnimatedCover
    MenuAction.SAVE_ANIMATED_COVER -> PrismIcons.Download
    MenuAction.STATS -> PrismIcons.Stats
    MenuAction.MARK_STUTTER -> PrismIcons.Info
}

internal fun menuLabel(a: MenuAction): String = when (a) {
    MenuAction.ADD_TO_PLAYLIST -> tr(R.string.add_to_playlist)
    MenuAction.TAGS -> tr(R.string.add_a_tag)
    MenuAction.RADIO -> tr(R.string.start_radio)
    MenuAction.DOWNLOAD -> tr(R.string.download)
    MenuAction.ALBUM -> tr(R.string.go_to_album)
    MenuAction.ARTIST -> tr(R.string.go_to_artist)
    MenuAction.CREDITS -> tr(R.string.song_credits)
    MenuAction.DETAILS -> tr(R.string.song_details)
    MenuAction.VERSION -> tr(R.string.play_a_different_version)
    MenuAction.COVER -> tr(R.string.change_cover)
    MenuAction.NOT_FOR_ME -> tr(R.string.not_for_me)
    MenuAction.SING -> tr(R.string.sing)
    MenuAction.LYRICS_SOURCE -> tr(R.string.lyrics_source)
    MenuAction.LYRICS_TIMING -> tr(R.string.lyrics_timing)
    MenuAction.TRANSLATE -> tr(R.string.translate_lyrics)
    MenuAction.ROMANIZE -> tr(R.string.romanize_lyrics)
    MenuAction.LYRICS_AGAIN -> tr(R.string.download_lyrics_again)
    MenuAction.LYRICS_INTO_FILE -> tr(R.string.save_lyrics_into_the_file)
    MenuAction.OPEN_LYRICS -> tr(R.string.show_lyrics)
    MenuAction.OPEN_QUEUE -> tr(R.string.up_next)
    MenuAction.VIDEO_SWITCH -> tr(R.string.music_video_3)
    MenuAction.PREVIOUS -> tr(R.string.previous_song)
    MenuAction.NEXT -> tr(R.string.next_song)
    MenuAction.DJ -> tr(R.string.dj_mode)
    MenuAction.EQUALIZER -> tr(R.string.equalizer)
    MenuAction.SLEEP -> tr(R.string.sleep_timer)
    MenuAction.SPEED_PITCH -> tr(R.string.speed_pitch_2)
    MenuAction.OUTPUT -> tr(R.string.play_on_a_speaker_or_tv)
    MenuAction.IMMERSIVE -> tr(R.string.immersive_mode)
    MenuAction.VIDEO_DOWNLOAD -> tr(R.string.download_video)
    MenuAction.LISTEN_TOGETHER -> tr(R.string.listen_together)
    MenuAction.BEST_PART -> tr(R.string.your_best_part)
    MenuAction.ANIMATED_COVER -> tr(R.string.animated_cover)
    MenuAction.SAVE_ANIMATED_COVER -> tr(R.string.save_animated_cover)
    MenuAction.STATS -> tr(R.string.stats_for_nerds)
    MenuAction.MARK_STUTTER -> tr(R.string.mark_a_stutter)
}

/**
 * Edit this menu (1.8.0): the quick tiles as they'll look, then every action in its group, each with a pin (one of the
 * tiles, up to four) and a switch (in the menu, or folded under "More"). From the ⋯ menu itself and Settings → Player.
 */
@Composable
fun MenuEditorSheet(onDismiss: () -> Unit) {
    ActionSheet(null, onDismiss) { close -> MenuEditor(onDone = { close {} }) }
}

/** The editor's content, on its own for [MenuEditorSheet]. */
@Composable
fun MenuEditor(onDone: () -> Unit) {
    val prefs = LocalContainer.current.settings
    val s = prefs.flow.collectAsState().value
    MenuEditorContent(
        s.view.parts, onDone,
        onShown = prefs::setMenuShown, onTiles = prefs::setMenuTiles, onReset = prefs::resetMenu,
        fromPlayer = s.menuFromPlayer(), folds = s.menuFolds(),
    )
}

/** [MenuEditor] as it draws [parts] (the renders draw it without the app). */
@Composable
fun MenuEditorContent(
    parts: com.prism.music.data.prefs.PlayerParts,
    onDone: () -> Unit,
    onShown: (MenuAction, Boolean) -> Unit,
    onTiles: (List<MenuAction>) -> Unit,
    onReset: () -> Unit,
    /** What the player's hidden parts bring to the menu now ([menuFromPlayer]): the foldable ones are listed too (#345). */
    fromPlayer: Collection<MenuAction> = emptyList(),
    /** Of those, the ones that may go under "More" now ([menuFolds]); the others stay, as the player's only way there. */
    folds: Set<MenuAction> = emptySet(),
) {
    val tiles = parts.menuTiles
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("menuEditor")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.edit_this_menu), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            com.prism.music.ui.components.PrismTextButton(onDone) { Text(tr(R.string.done)) }
        }
        // The tiles as the menu will show them; a tap takes one off.
        com.prism.music.ui.screens.Label(tr(R.string.quick_tiles), info = tr(R.string.up_to_four_actions_as_tiles_at_the_top))
        if (tiles.isEmpty()) Text(
            tr(R.string.no_quick_tiles), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) else QuickActions(tiles.map { a -> ActionItem(menuIcon(a), menuLabel(a)) { onTiles(tiles - a) } }) { it.onClick() }
        MenuGroup.entries.forEach { g ->
            val actions = MenuAction.entries.filter { it.group == g && (!it.fromPlayer || (it.foldable && it in fromPlayer)) }
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                Eyebrow(g.label, Modifier.padding(start = 8.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().pane(LocalUi.current.card)) {
                    actions.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp), color = scheme.onSurface.copy(alpha = 0.07f))
                        val tile = a in tiles
                        // From the player (#345): never a tile; while it's the player's only way there, it stays.
                        val stays = a.fromPlayer && a !in folds
                        MenuEditRow(
                            a, shown = tile || stays || a !in parts.menuHidden, tile = tile,
                            canTile = !a.fromPlayer && (tile || tiles.size < MenuAction.MAX_TILES),
                            onShown = { onShown(a, it) },
                            onTile = { on -> onTiles(if (on) tiles + a else tiles - a); if (on) onShown(a, true) },
                            stays = stays,
                        )
                    }
                }
            }
        }
        // Player settings and this row always stay: said once, under the ⓘ of the tiles' heading.
        if (parts.menuTiles != MenuAction.DEFAULT_TILES || parts.menuHidden.isNotEmpty()) com.prism.music.ui.components.PrismTextButton(
            onReset, Modifier.padding(start = 20.dp, top = 10.dp),
        ) { Text(tr(R.string.back_to_prism_s_menu)) }
    }
}

/** One action in Edit this menu: the row shows or hides it, the pin makes it a tile. */
@Composable
private fun MenuEditRow(
    a: MenuAction, shown: Boolean, tile: Boolean, canTile: Boolean, onShown: (Boolean) -> Unit, onTile: (Boolean) -> Unit,
    /** The player's only way to what it does (#345): it stays in the menu, its switch on and still. */
    stays: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val label = menuLabel(a)
    Row(
        // A tile is in the menu already: its switch stays on until it's unpinned.
        Modifier.fillMaxWidth().testTag("menuEdit:${a.name}").toggleable(shown, enabled = !tile && !stays, role = Role.Switch, onValueChange = onShown)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(menuIcon(a), null, Modifier.size(22.dp), tint = if (shown) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (shown) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!shown) Text(tr(R.string.under_more), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            else if (stays) Text(tr(R.string.menu_lyrics_only_way), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        val pinLabel = tr(R.string.quick_tile_s, label)
        Icon(
            if (tile) PrismIcons.PinFilled else PrismIcons.Pin, null,
            Modifier.size(44.dp)
                .toggleable(tile, enabled = canTile, role = Role.Checkbox, onValueChange = onTile)
                .semantics { contentDescription = pinLabel }
                .padding(11.dp),
            tint = when { tile -> scheme.onSurface; canTile -> scheme.onSurfaceVariant; else -> scheme.onSurfaceVariant.copy(alpha = 0.3f) },
        )
        com.prism.music.ui.components.PrismSwitch(shown, null)
    }
}

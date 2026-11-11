package com.prism.music.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.db.ImportMatchEntity
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.HistoryImports
import com.prism.music.data.imports.ImportPreview
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.ImportReminders
import com.prism.music.data.imports.LibraryJob
import com.prism.music.data.imports.LibraryNews
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismProgressBar
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)
private fun day(ms: Long?): String = ms?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "–"

/**
 * What an import brought, said after it: its plays ("Brought 312 plays of 40 songs…"), what its library added ("Added 3
 * liked songs and 12 playlist songs."), both, or that everything was already here.
 */
internal fun broughtMessage(stats: HistoryImporter.Stats?): String {
    stats ?: return tr(R.string.imp_cant_read)
    val lib = stats.library
    val added = listOfNotNull(
        lib.liked.takeIf { it > 0 }?.let { trp(R.plurals.imp_n_liked_songs, it) },
        lib.playlists.takeIf { it > 0 }?.let { trp(R.plurals.imp_n_new_playlists, it) },
        lib.playlistSongs.takeIf { it > 0 }?.let { trp(R.plurals.imp_n_playlist_songs, it) },
        lib.albums.takeIf { it > 0 }?.let { trp(R.plurals.imp_n_saved_albums, it) },
        lib.artists.takeIf { it > 0 }?.let { trp(R.plurals.imp_n_followed_artists, it) },
    ).takeIf { it.isNotEmpty() }?.let { parts ->
        val list = if (parts.size == 1) parts[0] else tr(R.string.imp_and, parts.dropLast(1).joinToString(", "), parts.last())
        tr(R.string.imp_added, list)
    }
    return when {
        stats.stored > 0 -> listOfNotNull(tr(R.string.imp_brought, count(stats.stored), count(stats.songs)), added).joinToString(" ")
        added != null -> added
        stats.alreadyThere > 0 -> tr(R.string.imp_already_there, count(stats.alreadyThere))
        else -> tr(R.string.imp_brought, count(stats.stored), count(stats.songs))
    }
}

/** "today", "1 day ago", "31 days ago": calendar days since [ms]. */
private fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val zone = java.time.ZoneId.systemDefault()
    val days = java.time.temporal.ChronoUnit.DAYS.between(
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(), java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
    ).toInt().coerceAtLeast(0)
    return if (days == 0) tr(R.string.imp_today) else trp(R.plurals.imp_days_ago, days)
}

/**
 * Bring your history (1.8.1): pick Spotify's data download, a Google Takeout file or Last.fm; see what's in it; choose
 * what to bring; then the imports, how matching is going, "Needs a look", and removing a source.
 */
@Composable
fun BringHistoryScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val imports = c.imports
    val summaries by imports.summaries.collectAsState(emptyList())
    val statuses by imports.statuses.collectAsState(emptyList())
    val reading by imports.reading.collectAsState()
    val mobile by imports.matchOnMobile.collectAsState()
    val lastFmUser by c.lastFm.user.collectAsState()
    val lastImports by imports.lastImports.collectAsState()
    val remind by imports.remindMonths.collectAsState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var source by remember { mutableStateOf<ImportSource?>(null) }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var looking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ImportSource?>(null) }
    var lastFmPeek by remember { mutableStateOf<com.prism.music.data.imports.LastFmPeek.Peek?>(null) }
    var history by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(true) }
    var playlists by remember { mutableStateOf(true) }
    var albums by remember { mutableStateOf(true) }
    var artists by remember { mutableStateOf(true) }

    fun done(stats: HistoryImporter.Stats?) { message = broughtMessage(stats) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val s = source ?: return@rememberLauncherForActivityResult
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        imports.picked.value = HistoryImports.Picked(s, picked)
    }
    // What was picked is looked at here (kept in HistoryImports, so it's looked at once, whoever picked it).
    val pickedNow by imports.picked.collectAsState()
    LaunchedEffect(pickedNow) {
        val (s, picked) = pickedNow ?: return@LaunchedEffect
        // Taken off at once; the look goes on in the page's scope (clearing it restarts this effect).
        imports.picked.value = null
        uris = picked; preview = null; lastFmPeek = null; message = null; looking = true
        scope.launch {
            val p = runCatching { imports.preview(s, picked) }.getOrNull()
            looking = false
            when {
                p == null || p.isEmpty -> message = tr(R.string.imp_nothing_found)
                // An older or the same download again.
                p.nothingNew -> message = if (p.plays > 0) tr(R.string.imp_nothing_new_range, day(p.firstAt), day(p.lastAt)) else tr(R.string.imp_nothing_new)
                else -> {
                    preview = p
                    val news = p.libraryNews
                    history = p.newPlays > 0; liked = news.liked > 0; playlists = news.playlists > 0 || news.playlistSongs > 0
                    albums = news.albums > 0; artists = news.artists > 0
                }
            }
        }
    }

    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr(R.string.imp_remove_title, r.label)) },
            text = { Text(tr(R.string.imp_remove_text)) },
            confirmButton = { PrismTextButton(onClick = { removing = null; c.scope.launch { imports.remove(r) } }) { Text(tr(R.string.remove)) } },
            dismissButton = { PrismTextButton(onClick = { removing = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    SubPage(tr(R.string.imp_title), bottomPadding, subtitle = tr(R.string.imp_subtitle), horizontalPadding = 16.dp, spacing = 22.dp) {
        item(key = "from") {
            Group(tr(R.string.imp_bring_from), info = tr(R.string.imp_bring_from_info)) {
                Item(ImportSource.SPOTIFY.label, tr(R.string.imp_spotify_detail), onClick = { source = ImportSource.SPOTIFY; picker.launch(arrayOf("*/*")) }, icon = PrismIcons.Library)
                Item(ImportSource.TAKEOUT.label, tr(R.string.imp_takeout_detail), onClick = { source = ImportSource.TAKEOUT; picker.launch(arrayOf("*/*")) }, icon = PrismIcons.History)
                Item(
                    ImportSource.LASTFM.label,
                    lastFmUser?.let { u ->
                        // Again: only what was scrobbled since.
                        lastImports[ImportSource.LASTFM]?.let { tr(R.string.imp_lastfm_detail_since, u, day(it)) } ?: tr(R.string.imp_lastfm_detail, u)
                    } ?: tr(R.string.imp_lastfm_connect),
                    onClick = {
                        if (lastFmUser == null) nav.go(Routes.settings(SettingsSection.LASTFM.key))
                        // 1.8.1: a look first (how many scrobbles, from when to when), then Bring them.
                        else if (reading == null && !looking) {
                            message = null; preview = null; lastFmPeek = null; looking = true
                            scope.launch {
                                val peek = imports.previewLastFm()
                                looking = false
                                when {
                                    peek == null -> message = tr(R.string.imp_cant_read)
                                    peek.isEmpty -> message = peek.since?.let { tr(R.string.imp_lastfm_nothing_new, day(it)) } ?: tr(R.string.imp_lastfm_none)
                                    else -> lastFmPeek = peek
                                }
                            }
                        }
                    },
                    icon = PrismIcons.Replay,
                )
            }
        }
        // 1.8.1: YouTube Music (live), the account's History page every few hours (off unless turned on here).
        item(key = "live") { YouTubeLiveGroup() }
        reading?.let { r ->
            item(key = "reading") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        r.pages?.let { (done, all) -> tr(R.string.imp_reading_lastfm, count(done), count(all)) } ?: tr(R.string.imp_reading, count(r.plays)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PrismProgressBar(r.pages?.let { (done, all) -> { done / all.coerceAtLeast(1).toFloat() } })
                }
            }
        }
        if (looking) item(key = "looking") { com.prism.music.ui.components.LoadingState() }
        message?.let { m -> item(key = "message") { Text(m, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp)) } }
        lastFmPeek?.let { p ->
            item(key = "lastfm") {
                Group(tr(R.string.imp_whats_in_it)) {
                    Note(
                        if (p.since != null) trp(R.plurals.imp_lastfm_new, p.total, count(p.total), day(p.since))
                        else trp(R.plurals.imp_lastfm_range, p.total, count(p.total), day(p.oldestAt), day(p.newestAt)),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrismButton(
                            onClick = { lastFmPeek = null; c.scope.launch { done(imports.bringLastFm()) } },
                            enabled = reading == null,
                        ) { Text(tr(R.string.imp_bring_them)) }
                        PrismTextButton(onClick = { lastFmPeek = null }) { Text(tr(R.string.cancel)) }
                    }
                }
            }
        }
        preview?.let { p ->
            item(key = "preview") {
                Group(tr(R.string.imp_whats_in_it)) {
                    if (p.isRepeat && p.plays > 0) {
                        // A newer download: what's new since the last import, then the download's dates.
                        Note(trp(R.plurals.imp_new_plays_since, p.newPlays, count(p.newPlays), day(p.lastImportAt)))
                        val old = p.plays - p.newPlays
                        Note(if (old > 0) tr(R.string.imp_download_range, day(p.firstAt), day(p.lastAt), count(old)) else tr(R.string.imp_download_dates, day(p.firstAt), day(p.lastAt)))
                        if (p.newPlays > 0) Toggle(tr(R.string.imp_history), tr(R.string.imp_history_detail), history) { history = it }
                    } else if (p.plays > 0) {
                        Note(tr(R.string.imp_plays_range, count(p.plays), count(p.songs), day(p.firstAt), day(p.lastAt)))
                        Toggle(tr(R.string.imp_history), tr(R.string.imp_history_detail), history) { history = it }
                    }
                    val lib = p.library
                    val news = p.libraryNews
                    // Some of it was brought before (a newer download, or one brought again after its history was removed).
                    val again = p.isRepeat || news != LibraryNews.of(lib)
                    if (news.liked > 0) {
                        val detail = if (again) tr(R.string.imp_liked_new_detail, count(news.liked)) else tr(R.string.imp_liked_detail, count(lib.liked.size))
                        Toggle(tr(R.string.imp_liked_songs), detail, liked, info = tr(R.string.imp_liked_info)) { liked = it }
                        // Off unless asked for: the likes stay in Prism, and library sync leaves them be.
                        if (liked && c.settings.current.isLoggedIn) {
                            val onAccount by imports.likeOnAccount.collectAsState()
                            Toggle(tr(R.string.imp_like_on_account), tr(R.string.imp_like_on_account_detail), onAccount, info = tr(R.string.imp_like_on_account_info)) { imports.setLikeOnAccount(it) }
                        }
                    }
                    if (news.playlists > 0 || news.playlistSongs > 0) {
                        val detail = if (again) trp(R.plurals.imp_playlists_new_count, news.playlistSongs, count(news.playlists), count(news.playlistSongs))
                            else tr(R.string.imp_playlists_detail, count(lib.playlists.size))
                        Toggle(tr(R.string.imp_playlists), detail, playlists, info = tr(R.string.imp_playlists_info)) { playlists = it }
                    }
                    if (news.albums > 0) {
                        val detail = if (again) tr(R.string.imp_albums_new_detail, count(news.albums)) else tr(R.string.imp_albums_detail, count(lib.albums.size))
                        Toggle(tr(R.string.imp_albums), detail, albums) { albums = it }
                    }
                    if (news.artists > 0) {
                        val detail = if (again) tr(R.string.imp_artists_new_detail, count(news.artists)) else tr(R.string.imp_artists_detail, count(lib.artists.size))
                        Toggle(tr(R.string.imp_artists), detail, artists) { artists = it }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrismButton(
                            onClick = {
                                val choice = HistoryImports.Choice(history, LibraryJob.LibraryChoice(liked, playlists, albums, artists))
                                val s = p.source
                                val picked = uris
                                preview = null
                                c.scope.launch { done(runCatching { imports.bring(s, picked, choice, p) }.getOrNull()) }
                            },
                            enabled = reading == null && (history || liked || playlists || albums || artists),
                        ) { Text(tr(R.string.imp_bring_them)) }
                        PrismTextButton(onClick = { preview = null }) { Text(tr(R.string.cancel)) }
                    }
                }
            }
        }
        if (summaries.isNotEmpty()) {
            item(key = "mine") {
                Group(tr(R.string.imp_your_imports)) {
                    summaries.forEach { s ->
                        val src = ImportSource.of(s.origin) ?: return@forEach
                        val pct = if (s.plays > 0) s.matched * 100 / s.plays else 0
                        Item(src.label, tr(R.string.imp_source_line, count(s.plays), day(s.firstAt), day(s.lastAt), pct), onClick = {}) {
                            Icon(
                                PrismIcons.Delete, tr(R.string.remove),
                                Modifier.size(40.dp).clickable { removing = src }.padding(8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // Spotify and Takeout come as downloads: when the last was brought in, where to ask for a fresh one, a reminder.
        val downloads = ImportReminders.SOURCES.mapNotNull { s -> lastImports[s]?.let { s to it } }
        if (downloads.isNotEmpty()) {
            item(key = "current") {
                Group(tr(R.string.imp_keep_current), info = tr(R.string.imp_keep_current_info)) {
                    downloads.forEach { (s, at) ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr(R.string.imp_last_import, s.label, day(at), ago(at)), style = MaterialTheme.typography.bodyLarge)
                            PrismOutlinedButton(onClick = { ImportReminders.requestUrl(s)?.let { runCatching { uriHandler.openUri(it) } } }) {
                                Text(tr(R.string.imp_request_fresh))
                            }
                        }
                    }
                    Label(tr(R.string.imp_remind), info = tr(R.string.imp_remind_info))
                    com.prism.music.ui.components.Segmented(
                        ImportReminders.CHOICES, remind,
                        { m -> if (m == 0) tr(R.string.off) else trp(R.plurals.imp_months, m) },
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) { imports.setRemindMonths(it) }
                }
            }
        }
        val byStatus = statuses.associate { MatchStatus.of(it.status) to it.n }
        if (byStatus.isNotEmpty()) {
            item(key = "matching") {
                Group(tr(R.string.imp_matching)) {
                    val found = (byStatus[MatchStatus.SURE] ?: 0) + (byStatus[MatchStatus.FIXED] ?: 0)
                    Note(tr(R.string.imp_matching_line, count(found), count(byStatus[MatchStatus.PENDING] ?: 0), count(byStatus[MatchStatus.NONE] ?: 0)))
                    val review = byStatus[MatchStatus.REVIEW] ?: 0
                    if (review > 0) Item(tr(R.string.imp_needs_a_look), trp(R.plurals.imp_needs_a_look_count, review, count(review)), onClick = { nav.go(Routes.IMPORT_REVIEW) }) {
                        Icon(PrismIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Toggle(tr(R.string.imp_match_on_mobile), tr(R.string.imp_match_on_mobile_detail), mobile) { imports.setMatchOnMobile(it) }
                }
            }
        }
    }
}

/**
 * "Keep your YouTube Music history in step" (1.8.1): signed in, a switch (off by default) and how its last look went;
 * signed out, what it needs. The limits are behind its ⓘ.
 */
@Composable
private fun YouTubeLiveGroup() {
    val c = LocalContainer.current
    val live = c.imports.youtubeLive
    val signedIn = com.prism.music.ui.theme.LocalAppSettings.current.isLoggedIn
    val on by live.on.collectAsState()
    val last by live.lastCheck.collectAsState()
    val guard by live.guard.state.collectAsState()
    Group(tr(R.string.ytlive_group)) {
        if (!signedIn) {
            Item(tr(R.string.ytlive_title), tr(R.string.ytlive_sign_in), onClick = {}, icon = PrismIcons.History, info = tr(R.string.ytlive_info))
        } else {
            val detail = when {
                !on -> tr(R.string.ytlive_sub_off)
                guard == com.prism.music.data.imports.LiveGuard.State.PAUSED -> tr(R.string.ytlive_sub_paused)
                guard == com.prism.music.data.imports.LiveGuard.State.NOT_ANSWERING -> tr(R.string.ytlive_sub_retry)
                last == null -> tr(R.string.ytlive_sub_first)
                else -> tr(R.string.ytlive_sub_checked, android.text.format.DateUtils.getRelativeTimeSpanString(last!!, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
            }
            Toggle(tr(R.string.ytlive_title), detail, on, icon = PrismIcons.History, info = tr(R.string.ytlive_info)) { c.imports.setYouTubeLive(it) }
        }
        SpotifyLiveRow()
    }
}

/**
 * Spotify's live history beside YouTube Music's (owner, 1.8.1): its switch lives on the Spotify page (Recent plays,
 * with the connection), so here it says where it stands and opens that page.
 */
@Composable
private fun SpotifyLiveRow() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Spotify's connection is only built when it's connected (most never connect; see Spotify.pageOpened).
    val connected = remember {
        com.prism.music.data.spotify.SpotifyStore.connectedIn(
            context.getSharedPreferences(com.prism.music.data.spotify.SpotifyStore.AUTH_PREFS, android.content.Context.MODE_PRIVATE),
        )
    }
    val recent = if (connected) c.spotify.options.collectAsState().value.recent else false
    val detail = when {
        !connected -> tr(R.string.ytlive_spotify_connect)
        recent -> tr(R.string.on_2)
        else -> tr(R.string.ytlive_spotify_recent_off)
    }
    NavRow(PrismIcons.Library, tr(R.string.spotify_live_source), detail) { nav.go(Routes.settings(SettingsSection.SPOTIFY.key)) }
}

/** "Needs a look" (1.8.1): each song Prism wasn't sure of, with up to three candidates to play and pick, or none. */
@Composable
fun ImportReviewScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val review by c.imports.review.collectAsState(null)
    SubPage(tr(R.string.imp_needs_a_look), bottomPadding, subtitle = tr(R.string.imp_needs_a_look_subtitle), horizontalPadding = 16.dp, spacing = 18.dp) {
        val list = review
        if (list == null) { item { com.prism.music.ui.components.LoadingState() }; return@SubPage }
        if (list.isEmpty()) { item { EmptyState(PrismIcons.Check, tr(R.string.imp_all_sorted), tr(R.string.imp_all_sorted_body)) }; return@SubPage }
        list.forEach { m -> item(key = m.songKey) { ReviewCard(m) } }
    }
}

@Composable
private fun ReviewCard(m: ImportMatchEntity) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val candidates = remember(m.candidates) { HistoryImporter.candidatesOf(m) }
    val length = m.durationMs.takeIf { it > 0 }?.let { tr(R.string.imp_length, clock(it / 1000)) }
    Group("${m.title} · ${m.artist}") {
        listOfNotNull(m.album, length).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Note(it) }
        candidates.forEach { s -> CandidateRow(s, onPlay = {
            c.player.playQueue(listOf(s), 0, QueueSource(QueueKind.SINGLE, s.title))
        }) { c.scope.launch { c.imports.fix(m.songKey, s) } } }
        Item(tr(R.string.imp_none_of_these), null, onClick = { c.scope.launch { c.imports.fix(m.songKey, null) } }, icon = PrismIcons.Close)
        Spacer(Modifier.size(ui.gap(2.dp)))
    }
}

@Composable
private fun CandidateRow(s: Song, onPlay: () -> Unit, onPick: () -> Unit) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(s.thumbnail, Modifier.size(48.dp).clickable(onClick = onPlay), ui.smallArt, size = 120)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                listOfNotNull(s.artistText, s.album?.title, s.durationSec.takeIf { it > 0 }?.let { clock(it.toLong()) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
        Icon(PrismIcons.Play, tr(R.string.play, s.title), Modifier.size(40.dp).clickable(onClick = onPlay).padding(8.dp), tint = MaterialTheme.colorScheme.onSurface)
        PrismTextButton(onClick = onPick) { Text(tr(R.string.imp_this_one)) }
    }
}

private fun clock(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

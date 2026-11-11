package com.prism.music.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/**
 * Select many (1.8.3, G141 / #381): long-press a song in a list to start picking, tap more, "All" for the whole list, and
 * act on them together from one bar. One selection for the app (MainActivity's, [LocalSongSelection]); a list takes part
 * by wrapping itself in [SelectableSongs], so lists that don't (Home's shelves, an artist's top songs) keep long-press as
 * the song's menu.
 */
@Stable
class SongSelection {
    /** The picked songs by id, in the order they were picked; null while not picking. */
    var picked by mutableStateOf<Map<String, Song>?>(null)
        private set
    val selecting: Boolean get() = picked != null

    /** The list the picking started in: its songs ("All") and how songs leave it, if they can. */
    var scope: SelectScope? by mutableStateOf(null)
        private set

    fun start(song: Song, scope: SelectScope?) {
        this.scope = scope
        picked = linkedMapOf(song.id to song)
    }

    fun toggle(song: Song) {
        val p = picked ?: return
        picked = if (song.id in p) p - song.id else p + (song.id to song)
    }

    /** Picks every song of the list; with all of them picked already, none. */
    fun all() {
        val p = picked ?: return
        val songs = scope?.songs.orEmpty()
        picked = if (songs.isNotEmpty() && songs.all { it.id in p }) emptyMap() else LinkedHashMap<String, Song>().apply { songs.forEach { put(it.id, it) } }
    }

    fun clear() {
        picked = null
        scope = null
    }
}

/** A list taking part in Select many: what's in it now, and how songs come out of it (an own playlist). */
@Stable
class SelectScope {
    var songs: List<Song> by mutableStateOf(emptyList())
    var remover: SelectionRemover? by mutableStateOf(null)
}

/**
 * Taking songs out of the list they were picked in: a Prism playlist ([asks] false), or an own YouTube playlist, which
 * writes to the account, so it asks first. [label] names the button when it isn't "Remove from this playlist" (a tag).
 */
class SelectionRemover(val listName: String, val asks: Boolean, val label: String? = null, val remove: (List<Song>) -> Unit)

/** The app's selection; null (a TV, a test without one) leaves every list as it was. */
val LocalSongSelection = staticCompositionLocalOf<SongSelection?> { null }

/** The list a row is in, when that list takes part in Select many. */
val LocalSelectScope = staticCompositionLocalOf<SelectScope?> { null }

/**
 * Makes the song rows in [content] pickable: long-press picks, and [songs] is what "All" picks. A change of [resetKey]
 * (another tab of the same list) ends the picking that started here.
 */
@Composable
fun SelectableSongs(songs: List<Song>, remover: SelectionRemover? = null, resetKey: Any? = null, content: @Composable () -> Unit) {
    val selection = LocalSongSelection.current
    if (selection == null) { content(); return }
    val scope = remember { SelectScope() }
    SideEffect { scope.songs = songs; scope.remover = remover }
    // Leaving the page ends the picking that started in it.
    DisposableEffect(scope) { onDispose { if (selection.scope === scope) selection.clear() } }
    androidx.compose.runtime.LaunchedEffect(resetKey) { if (selection.scope === scope) selection.clear() }
    // Back ends the picking here, inside the page: a handler outside the NavHost is registered before the NavHost's
    // own, which then takes Back first and leaves the page.
    androidx.activity.compose.BackHandler(selection.selecting && selection.scope === scope) { selection.clear() }
    CompositionLocalProvider(LocalSelectScope provides scope, content = content)
}

/** What the bar's buttons do, worked out from the picked songs (no UI: the unit tests' side). */
object SelectionPlan {
    /** Like the ones that aren't yet; with every one liked already, unlike them all. Feed episodes have no likes. */
    fun like(songs: Collection<Song>, liked: Set<String>): Pair<Boolean, List<Song>> {
        val likable = songs.filter { !it.isRss }
        val allLiked = likable.isNotEmpty() && likable.all { it.id in liked }
        return if (allLiked) false to likable else true to likable.filter { it.id !in liked }
    }

    /**
     * Download the ones that aren't yet; with every one on the phone already, take the downloads off (asked first).
     * Files on the phone and feed episodes aren't downloaded.
     */
    fun download(songs: Collection<Song>, downloaded: (Song) -> Boolean): Pair<Boolean, List<Song>> {
        val loadable = songs.filter { !it.isLocal && !it.isRss }
        val allDown = loadable.isNotEmpty() && loadable.all(downloaded)
        return if (allDown) false to loadable else true to loadable.filterNot(downloaded)
    }

    /** What may go into a playlist: not feed episodes (files on the phone only into Prism playlists, the picker's rule). */
    fun forPlaylist(songs: Collection<Song>): List<Song> = songs.filter { !it.isRss }
}

/** The bar while songs are picked: how many, All, ✕, and what to do with them. Takes the place of the mini player and tabs. */
@Composable
fun SelectionBar(selection: SongSelection, modifier: Modifier = Modifier) {
    val picked = selection.picked ?: return
    val c = LocalContainer.current
    val context = LocalContext.current
    val songs = picked.values.toList()
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloadState.collectAsState()
    val standIns by c.downloadStandIns.collectAsState()
    val together by c.together.state.collectAsState()
    val guest = together as? com.prism.music.playback.together.TogetherState.Guest
    var addingTo by remember { mutableStateOf<List<Song>?>(null) }
    var asking by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    fun downloaded(s: Song) = com.prism.music.download.rowDownloaded(s, downloads, standIns) { c.downloads.playsFromPhone(it) }

    addingTo?.let { list ->
        PlaylistPicker(list, trp(R.plurals.n_songs, list.size)) { addingTo = null; selection.clear() }
    }
    var tagging by remember { mutableStateOf<List<Song>?>(null) }
    tagging?.let { list -> TagPicker(list, null) { tagging = null; selection.clear() } }
    asking?.let { (text, go) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            text = { Text(text) },
            confirmButton = { PrismTextButton(onClick = { go(); asking = null; selection.clear() }) { Text(tr(R.string.remove)) } },
            dismissButton = { PrismTextButton(onClick = { asking = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    val glass = LocalAppSettings.current.glassEverywhere
    val (ink, action) = barInk(glass)
    BarSurface(glass, RoundedCornerShape(24.dp), modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("selectionBar")) {
        Column(
            // Every touch on the bar stays on it (the list under it mustn't get one).
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                }
            }.padding(vertical = 4.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selection.clear() }) { Icon(PrismIcons.Close, tr(R.string.close), tint = ink) }
                Text(
                    trp(R.plurals.n_songs_picked, picked.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (selection.scope?.songs.orEmpty().size > 1) PrismTextButton({ selection.all() }) { Text(tr(R.string.all), color = action) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val none = songs.isEmpty()
                BarButton(PrismIcons.PlaylistPlay, tr(R.string.play_next), ink, enabled = !none) {
                    if (guest != null) songs.filter { !it.isLocal && !it.isRss }.forEach { c.together.suggest(it, next = true) }
                    else c.player.playNext(songs, null)
                    toast(tr(R.string.playing_next)); selection.clear()
                }
                BarButton(PrismIcons.QueueMusic, tr(R.string.add_to_queue), ink, enabled = !none) {
                    if (guest != null) songs.filter { !it.isLocal && !it.isRss }.forEach { c.together.suggest(it, next = false) }
                    else c.player.addToQueue(songs, null)
                    toast(tr(R.string.added_to_queue)); selection.clear()
                }
                val forList = SelectionPlan.forPlaylist(songs)
                BarButton(PrismIcons.PlaylistAdd, tr(R.string.add_to_playlist), ink, enabled = forList.isNotEmpty()) { addingTo = forList }
                val forTags = songs.filter(com.prism.music.data.TagStore::taggable)
                BarButton(PrismIcons.Tag, tr(R.string.add_a_tag), ink, enabled = forTags.isNotEmpty()) { tagging = forTags }
                val (likeThem, likeWhich) = SelectionPlan.like(songs, liked)
                BarButton(
                    if (likeThem) PrismIcons.Heart else PrismIcons.HeartFilled, if (likeThem) tr(R.string.like) else tr(R.string.unlike), ink,
                    enabled = likeWhich.isNotEmpty(),
                ) {
                    // One after another (each also tells YouTube Music when signed in).
                    c.library.setLiked(likeWhich, likeThem)
                    selection.clear()
                }
                val (getThem, loadWhich) = SelectionPlan.download(songs, ::downloaded)
                BarButton(
                    if (getThem) PrismIcons.Download else PrismIcons.Delete, if (getThem) tr(R.string.download) else tr(R.string.remove_downloads), ink,
                    enabled = loadWhich.isNotEmpty(),
                ) {
                    if (getThem) {
                        c.downloads.downloadAll(loadWhich)
                        toast(tr(R.string.downloading, com.prism.music.data.model.songCount(loadWhich.size))); selection.clear()
                    } else asking = trp(R.plurals.remove_n_downloads_q, loadWhich.size) to {
                        c.downloads.removeMany(loadWhich.map { it.id })
                        toast(trp(R.plurals.removed_n_downloads, loadWhich.size))
                    }
                }
                selection.scope?.remover?.let { r ->
                    BarButton(PrismIcons.PlaylistRemove, r.label ?: tr(R.string.remove_from_this_playlist), ink, enabled = !none) {
                        if (r.asks) asking = trp(R.plurals.remove_n_from_youtube_playlist_q, songs.size, songs.size, r.listName) to { r.remove(songs) }
                        else { r.remove(songs); selection.clear() }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, ink: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    PrismTextButton(onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) ink else ink.copy(alpha = 0.38f))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) ink else ink.copy(alpha = 0.38f), maxLines = 1)
    }
}

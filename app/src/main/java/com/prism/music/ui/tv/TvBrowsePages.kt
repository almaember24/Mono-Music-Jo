package com.prism.music.ui.tv

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.prism.music.R
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import com.prism.music.data.innertube.TopSearch
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Searches typed (or said) lately: the phone's own list (one store, as Prism is one app on both). */
internal object TvSearchHistory {
    private const val MAX = 12
    private fun prefs(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    fun read(context: Context): List<String> = prefs(context).getString("q", "")!!.split("\n").filter { it.isNotBlank() }

    fun add(context: Context, q: String) {
        val v = q.trim().takeIf { it.isNotBlank() } ?: return
        val list = (listOf(v) + read(context).filter { it != v }).take(MAX)
        prefs(context).edit().putString("q", list.joinToString("\n")).apply()
    }
}

/** How long typing pauses before the results are asked for (each key on a TV keyboard is slow; this keeps up). */
private const val TYPE_PAUSE_MS = 450L

/** The rows Search shows under the top result and the songs, in this order (as the phone's Top results). */
private val SEARCH_ROWS = listOf(SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS, SearchFilter.VIDEOS)

/**
 * Search (TV): typing on the TV's keyboard (results come as you type), or by voice (the microphone button opens the
 * TV's speech recognizer; the remote's own Assistant button works anywhere through "Play … on Prism"). The results
 * are the phone's Top results, leaned toward what the listener plays.
 */
@Composable
fun TvSearchPlace(nav: TvNav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val recent = remember { mutableStateListOf<String>().apply { addAll(TvSearchHistory.read(context)) } }
    var result by remember { mutableStateOf<TopSearch?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { result = null; return@LaunchedEffect }
        delay(TYPE_PAUSE_MS)
        val taste = runCatching { c.taste.searchTaste() }.getOrDefault(SearchRank.Taste.NONE)
        result = runCatching { withContext(Dispatchers.Default) { c.ytm.searchTop(query.trim(), taste) } }.getOrNull() ?: result
    }
    var submitted by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val submit: (String) -> Unit = { q ->
        query = q; typing = false; submitted++
        TvSearchHistory.add(context, q)
        recent.clear(); recent.addAll(TvSearchHistory.read(context))
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(submit)
    }
    val r = result
    val songs = r?.sections?.firstOrNull { it.first == SearchFilter.SONGS }?.second.orEmpty().filterIsInstance<SongItem>()
    val rows = SEARCH_ROWS.mapNotNull { f -> r?.sections?.firstOrNull { it.first == f }?.second?.takeIf { it.isNotEmpty() }?.let { f to it } }
    val openIt: (BrowseItem) -> Unit = { item ->
        if (item is SongItem) { c.player.playSingle(item.song); nav.go(TvRoute.Player) }
        else open(c, nav, item, listOf(item), 0, query, radio = false) { scope.launch { it() } }
    }
    TvSearchScreen(
        query,
        onType = { typing = true },
        onVoice = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, tr(R.string.tv_search_hint))
            runCatching { voice.launch(intent) }.onFailure { Toast.makeText(context, tr(R.string.tv_no_voice), Toast.LENGTH_LONG).show() }
        },
        recent = recent,
        onRecent = submit,
        top = r?.top?.toTvItem(),
        songs = songs.map { s -> TvSong(s.id, s.song.title, s.song.artistText, if (s.song.isLive) null else tvTime(s.song.durationSec * 1000L), s.song.thumbnail) },
        shelves = rows.map { (f, items) -> TvShelf(f.label, items.map { it.toTvItem() }) },
        onItem = { item ->
            val found = (listOfNotNull(r?.top) + rows.flatMap { it.second }).firstOrNull { it.id == item.id }
            if (found != null) openIt(found)
        },
        onSong = { s -> songs.firstOrNull { it.id == s.id }?.let(openIt) },
        typing = typing,
        onQueryChange = { query = it },
        onSubmit = { submit(query) },
        onStopTyping = { typing = false },
        submitted = submitted,
    )
}

/** Library's tabs, in order. */
enum class TvLibraryTab { PLAYLISTS, ALBUMS, ARTISTS, LIKED, HISTORY }

/**
 * Library (TV): your playlists (YouTube's and Prism's own), albums, artists, liked songs and history, as on the phone
 * (signed in, what YouTube Music keeps for the account; Prism's own lists in any case). View and play only.
 */
@Composable
fun TvLibraryPlace(nav: TvNav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(TvLibraryTab.PLAYLISTS) }
    val ytPlaylists by c.library.playlists.collectAsState()
    val ytAlbums by c.library.albums.collectAsState()
    val artists by c.library.artists.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    val savedPlaylists by c.prismLibrary.playlists.collectAsState()
    val savedAlbums by c.prismLibrary.albums.collectAsState()
    val liked by c.library.likedShown.collectAsState()
    val recent by c.library.recent.collectAsState()
    val items: List<BrowseItem> = remember(tab, ytPlaylists, ytAlbums, artists, prismLists, savedPlaylists, savedAlbums, liked, recent) {
        when (tab) {
            TvLibraryTab.PLAYLISTS -> (prismLists.map { it.item() } + savedPlaylists.map { it.item() } + ytPlaylists).distinctBy { it.id.removePrefix("VL") }
            TvLibraryTab.ALBUMS -> (savedAlbums.map { it.item() } + ytAlbums).distinctBy { it.id }
            TvLibraryTab.ARTISTS -> artists
            TvLibraryTab.LIKED -> liked.map(::SongItem)
            TvLibraryTab.HISTORY -> recent.distinctBy { it.id }.map(::SongItem)
        }
    }
    val labels = listOf(tr(R.string.playlists), tr(R.string.albums), tr(R.string.artists), tr(R.string.liked_songs), tr(R.string.history))
    val settings by c.settings.flow.collectAsState()
    TvLibraryScreen(
        labels, tab.ordinal, { tab = TvLibraryTab.entries[it] },
        items.map { it.toTvItem() },
        onItem = { item ->
            val i = items.indexOfFirst { it.id == item.id }
            if (i >= 0) open(c, nav, items[i], items, i, labels[tab.ordinal], radio = false) { scope.launch { it() } }
        },
        // Prism's own lists and history show signed out too; an empty tab says why, and how to fill it.
        empty = { TvLibraryEmpty(settings.isLoggedIn) { nav.go(TvRoute.Pair(signIn = true)) } },
    )
}

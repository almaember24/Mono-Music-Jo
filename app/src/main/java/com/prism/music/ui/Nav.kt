package com.prism.music.ui

import com.prism.music.data.friendlyMessage
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val REPLAY = "replay"
    const val SETTINGS = "settings"
    const val EQ = "eq"
    const val CATALOGUE = "catalogue"
    const val LOGIN = "login"
    /** Signs another Google account in beside the current one (to switch between them). */
    const val LOGIN_ADD = "login_add"
    /** Signs in to Discord for "Listening to Prism" on the listener's profile. */
    const val DISCORD_LOGIN = "discord_login"
    const val DOWNLOADS = "downloads"
    const val LOCAL = "local"
    const val MOODS = "moods"
    const val BACKGROUNDS = "backgrounds"
    const val IMPORT = "import"
    const val TOGETHER = "together"
    const val PODCASTS = "podcasts"
    /** Library → Uploads: the music the listener uploaded to YouTube Music (#332). */
    const val UPLOADS = "uploads"
    const val SAVED = "saved_while_playing"
    const val EXPLORE = "explore"
    /** Release Radar's page: new albums, singles and EPs from the listener's artists. */
    const val NEW_RELEASES = "new_releases"
    /** "Pick artists you like" (after the welcome screen, or Settings → Home screen → Your artists). */
    const val PICK_ARTISTS = "pick_artists"
    /** The Prism Account: make one (after the welcome screen, or from Settings) or change it. */
    const val ACCOUNT = "prism_account"
    const val EXCLUSIONS = "exclusions"
    /** Bring your history (1.8.1): Spotify's data download, Google Takeout, Last.fm. */
    const val BRING_HISTORY = "bring_history"
    /** The imported songs that need a look: pick which song each is. */
    const val IMPORT_REVIEW = "import_review"
    /** Blend: your code, scanning a friend's, and your Blends. */
    const val BLENDS = "blends"
    const val BLEND_SCAN = "blend_scan"
    /** Scanning a Look's QR code (Appearance → Looks). */
    const val LOOK_SCAN = "look_scan"
    fun blend(who: String) = "blend/${Uri.encode(who)}"
    /** Song recognition: listening, and what it found. */
    const val RECOGNIZE = "recognize"
    const val RECOGNIZED = "recognized"
    /** Every shared component of the 1.7 redesign (debug builds, from Settings → About). */
    const val DESIGN_GALLERY = "design_gallery"
    /** One of Prism's own mixes (Daily Mix 1, the time-of-day mix, Release Radar). */
    fun mix(id: String) = "mix/${Uri.encode(id)}"
    fun podcast(id: String) = "podcast/${Uri.encode(id)}"
    /** A settings page; [find] is the group to bring into view (a Settings search result, #73). */
    fun settings(section: String, find: String? = null) = "settings/$section" + (find?.let { "?find=${Uri.encode(it)}" } ?: "")
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun playlist(id: String) = "playlist/${Uri.encode(id)}"
    /** A folder of playlists in Library (1.7.6). */
    fun folder(id: String) = "folder/${Uri.encode(id)}"
    /** One of the listener's tags (1.8.3), by its name. */
    fun tag(name: String) = "tag/${Uri.encode(name)}"
    /** Searching your library (1.8.3), with [query] typed in. */
    fun librarySearch(query: String = "") = "library-search?q=${Uri.encode(query)}"
    fun artist(id: String) = "artist/${Uri.encode(id)}"
    /** An artist's page; one a song names without a link (#78) is looked up by name when it opens. */
    fun artist(ref: com.prism.music.data.model.ArtistRef) =
        artist(ref.id ?: (com.prism.music.data.innertube.YouTubeMusic.ARTIST_BY_NAME + ref.name))
    fun liked(genre: String? = null) = if (genre == null) "liked" else "liked?genre=${Uri.encode(genre)}"
    fun browse(id: String, params: String?, title: String) =
        "browse/${Uri.encode(id)}?params=${Uri.encode(params ?: "")}&title=${Uri.encode(title)}"
}

/** Everything a screen needs to move around the app. */
class Navigator(val nav: NavController, val openPlayer: () -> Unit, private val playMix: (PlaylistItem) -> Unit = {}) {
    /**
     * Opens [route] on top. Only the very page that's showing is not opened twice (a double tap): launchSingleTop alone
     * compared the destination ("artist/{id}"), so a similar artist, another album or Licenses from About replaced the
     * page it was opened from, and Back skipped it.
     */
    fun go(route: String) {
        if (currentRoute() == normalized(route)) return
        nav.navigate(route)
    }

    /** The page showing, as the route that opened it (its pattern filled in with its arguments). */
    private fun currentRoute(): String? {
        val e = nav.currentBackStackEntry ?: return null
        val pattern = e.destination.route ?: return null
        // Android's (ICU) regex wants the closing brace escaped too.
        val filled = ARGUMENT.replace(pattern) { m ->
            @Suppress("DEPRECATION")
            e.arguments?.get(m.groupValues[1])?.toString()?.let(Uri::encode) ?: ""
        }
        return normalized(filled)
    }
    fun back() = nav.popBackStack()

    /** Without empty query arguments ("settings/about?find=" is "settings/about"). */
    private fun normalized(route: String) = route.replace(EMPTY_QUERY, "").replace("?&", "?").removeSuffix("?")

    private companion object {
        val ARGUMENT = Regex("\\{([^\\}]+)\\}")
        val EMPTY_QUERY = Regex("[?&][^=&?]+=(?=&|$)")
    }

    fun open(item: BrowseItem, onSong: (SongItem) -> Unit) {
        when (item) {
            is SongItem -> onSong(item)
            is AlbumItem -> go(Routes.album(item.id))
            // Stations and mixes only exist as a watch queue: tapping one starts it, as on YouTube Music.
            is PlaylistItem -> if (item.isMix) playMix(item) else go(Routes.playlist(item.id))
            is ArtistItem -> go(Routes.artist(item.id))
            is com.prism.music.data.model.PodcastItem -> go(Routes.podcast(item.id))
            is MoodItem -> go(Routes.browse(item.id, item.params, item.title))
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class LoaderViewModel : ViewModel() {
    val state = MutableStateFlow<Load<Any?>>(Load.Loading)
    var started = false
    var refreshing = MutableStateFlow(false)

    fun load(block: suspend () -> Any?, silent: Boolean = false) {
        started = true
        if (!silent) state.value = Load.Loading
        refreshing.value = silent
        viewModelScope.launch {
            state.value = try {
                // Off the main thread: the requests already were, but reading their answers into shelves and pages
                // wasn't, and a long playlist or Home held a slow phone's frames up for a few hundred ms (#103's log).
                Load.Ok(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() })
            } catch (e: Exception) {
                if (silent && state.value is Load.Ok) state.value else Load.Err(e.friendlyMessage())
            }
            refreshing.value = false
        }
    }
}

class Loaded<T>(val state: Load<T>, val refreshing: Boolean, val reload: (silent: Boolean) -> Unit)

/** Loads data once per back-stack entry (survives recomposition and tab switches). */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(key: String, block: suspend () -> T): Loaded<T> {
    val vm: LoaderViewModel = viewModel(key = key)
    LaunchedEffect(key) { if (!vm.started) vm.load(block) }
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    return Loaded(state as Load<T>, refreshing) { silent -> vm.load(block, silent) }
}

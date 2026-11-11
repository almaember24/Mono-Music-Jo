package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.pane
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.prism.music.AppContainer
import com.prism.music.data.PrismLibrary
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Home sections of the listener's own making (1.7.6, plan H1): an album, playlist, artist or mix pinned to Home, and
 * sections made by a rule over their own listening. Their songs are worked out once a session (a pull to refresh
 * asks again) and kept here, so scrolling a section off and back doesn't load it again.
 */
object OwnSections {
    private val cache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())

    fun isOwn(key: String) = key.startsWith(HomeSections.PIN_PREFIX) || key.startsWith("rule:")

    fun forget() { cache.value = emptyMap() }

    /** A pinned item's section: its key, name and picture. Songs play as a list, so it opens as one. */
    fun pinOf(item: BrowseItem): HomeSectionConfig? = when (item) {
        is AlbumItem -> HomeSectionConfig(HomeSections.pinKey("album", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
        is ArtistItem -> HomeSectionConfig(HomeSections.pinKey("artist", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
        is PlaylistItem -> if (item.isMix) HomeSectionConfig(HomeSections.pinKey("mix", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
            else HomeSectionConfig(HomeSections.pinKey("playlist", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
        else -> null
    }

    /** A holiday mix's own section (1.8.0), under its name. */
    fun pinOf(mix: com.prism.music.data.seasonal.HolidayMix): HomeSectionConfig =
        HomeSectionConfig(HomeSections.pinKey(HomeSections.HOLIDAY_PIN, mix.key), tr(mix.name), style = SectionStyle.LIST)

    /** Where a pinned section's "more" goes: its own page. */
    fun pageOf(key: String): String? {
        val (kind, id) = key.removePrefix(HomeSections.PIN_PREFIX).split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "album" -> Routes.album(id)
            "playlist" -> Routes.playlist(id)
            "artist" -> Routes.artist(id)
            HomeSections.HOLIDAY_PIN -> Routes.mix(com.prism.music.data.seasonal.HolidayMixes.PREFIX + id)
            else -> null
        }
    }

    /** Pins that can show as one tile (1.8.3, D48): those with a page of their own to open. */
    fun canTile(key: String) = key.startsWith(HomeSections.PIN_PREFIX) && pageOf(key) != null

    /** What a tile says under its name: "Album", "Playlist", "Artist". */
    fun kindOf(key: String): String? = when (key.removePrefix(HomeSections.PIN_PREFIX).substringBefore(':')) {
        "album" -> tr(R.string.album)
        "playlist", HomeSections.HOLIDAY_PIN -> tr(R.string.playlist)
        "artist" -> tr(R.string.artist)
        else -> null
    }

    /** [key]'s songs, kept ones if loaded this session (a tile asks only when Play is tapped). */
    suspend fun songs(c: AppContainer, key: String): List<Song> =
        cache.value[key] ?: load(c, key).also { if (it.isNotEmpty()) cache.value = cache.value + (key to it) }

    /** What a rule section says about itself under its name. */
    fun strapline(key: String): String? = when {
        key == HomeSections.LIKED_MONTH -> tr(R.string.your_likes_from_the_last_30)
        key == HomeSections.UNPLAYED_DOWNLOADS -> tr(R.string.on_the_phone_not_played_yet)
        key.startsWith(HomeSections.YEAR_PREFIX) -> tr(R.string.your_most_played_that_year)
        key.startsWith(HomeSections.PIN_PREFIX) -> tr(R.string.pinned)
        else -> null
    }

    /** The rule sections Customize Home can add (years with plays in them, newest first). */
    suspend fun rules(c: AppContainer): List<HomeSectionConfig> = withContext(Dispatchers.IO) {
        listOf(
            HomeSectionConfig(HomeSections.LIKED_MONTH, tr(R.string.liked_this_month), style = SectionStyle.LIST),
            HomeSectionConfig(HomeSections.UNPLAYED_DOWNLOADS, tr(R.string.downloads_you_haven_t_played)),
        ) + runCatching { c.db.plays().yearsPlayed() }.getOrDefault(emptyList()).take(6)
            .map { y -> HomeSectionConfig(HomeSections.yearKey(y), tr(R.string.most_played_in, y)) }
    }

    suspend fun load(c: AppContainer, key: String): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            when {
                key == HomeSections.LIKED_MONTH -> c.db.songs().likedSince(System.currentTimeMillis() - 30L * 86_400_000L).map { it.toSong() }.take(40)
                key == HomeSections.UNPLAYED_DOWNLOADS -> {
                    val songs = c.downloads.completedSongs()
                    val played = songs.map { it.id }.chunked(500).flatMap { c.db.plays().playedOf(it) }.toSet()
                    songs.filter { it.id !in played }.take(40)
                }
                key.startsWith(HomeSections.YEAR_PREFIX) -> {
                    val year = key.removePrefix(HomeSections.YEAR_PREFIX).toInt()
                    val from = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }.timeInMillis
                    val to = Calendar.getInstance().apply { clear(); set(year + 1, Calendar.JANUARY, 1) }.timeInMillis - 1
                    val ids = c.db.plays().mostPlayedIdsBetween(from, to, 40)
                    val byId = c.db.songs().getAll(ids).associateBy { it.id }
                    ids.mapNotNull { byId[it]?.toSong() }
                }
                key.startsWith(HomeSections.PIN_PREFIX) -> {
                    val (kind, id) = key.removePrefix(HomeSections.PIN_PREFIX).split(':', limit = 2)
                    when (kind) {
                        "album" -> c.ytm.album(id).songs
                        "playlist" -> c.prismLibrary.list(id)?.songs ?: if (PrismLibrary.isPrismList(id)) emptyList() else c.ytm.playlistAll(id, 100).songs
                        "artist" -> c.ytm.artist(id).shelves.firstOrNull { s -> s.items.any { it is SongItem } }
                            ?.items?.filterIsInstance<SongItem>()?.map { it.song }.orEmpty()
                        "mix" -> c.radio(null, id).songs
                        // One of Prism's holiday mixes (1.8.0): nothing outside its season.
                        HomeSections.HOLIDAY_PIN -> c.seasonal.mix(com.prism.music.data.seasonal.HolidayMixes.PREFIX + id)?.songs.orEmpty()
                        else -> emptyList()
                    }.take(50)
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** [key]'s songs: kept ones at once, else loaded (again after [refreshes] changes). */
    @Composable
    fun rememberSongs(key: String, refreshes: Int): State<List<Song>> {
        val c = LocalContainer.current
        return produceState(cache.value[key] ?: emptyList(), key, refreshes) {
            if (cache.value[key] != null && loadedAt[key] == refreshes) return@produceState
            val songs = load(c, key)
            cache.value = cache.value + (key to songs)
            loadedAt[key] = refreshes
            value = songs
        }
    }

    /** Which pull to refresh each section was last loaded for. */
    private val loadedAt = java.util.concurrent.ConcurrentHashMap<String, Int>()
}

/**
 * A pinned album or playlist as one tile (1.8.3, D48, Reddit): its cover, name and kind, and a play button. Tapping
 * the tile opens its page; nothing is loaded until Play is tapped.
 */
@Composable
fun PinnedTile(title: String, kind: String?, art: String?, onOpen: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = ui.gap(10.dp))
            .pane(ui.tile, scheme.surfaceContainerHigh.copy(alpha = 0.9f)).clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(84.dp), shape = ui.shape(10.dp), size = 300)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (kind != null) Text(kind, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(scheme.primary).clickable(onClick = onPlay).testTag("pinTilePlay"),
            contentAlignment = Alignment.Center,
        ) { Icon(PrismIcons.Play, tr(R.string.play, title), Modifier.size(24.dp), tint = scheme.onPrimary) }
    }
}

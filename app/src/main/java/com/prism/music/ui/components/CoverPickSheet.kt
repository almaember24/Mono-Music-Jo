package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.hiRes
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a cover is picked for (#305): a song, or an album (whose songs show it too). [original] is its own picture.
 */
sealed class CoverFor(val key: String, val title: String, val original: String?) {
    class OfSong(val song: Song, key: String) : CoverFor(key, song.title, song.thumbnail)
    class OfAlbum(val id: String, title: String, thumbnail: String?, key: String) : CoverFor(key, title, thumbnail)
}

/** The covers of [song]'s other recordings with the same title, as YouTube lists them: square album covers, no repeats. */
internal fun otherCovers(song: Song, found: List<Song>, max: Int = 8): List<String> {
    val title = com.prism.music.data.innertube.SearchRank.norm(song.title)
    return found.asSequence()
        .filter { it.id != song.id && !it.isVideo && com.prism.music.data.innertube.SearchRank.norm(it.title) == title }
        .mapNotNull { it.thumbnail }
        .filter { it != song.thumbnail }
        .distinctBy { hiRes(it, 120) }
        .take(max)
        .toList()
}

/**
 * Change a song's or an album's cover (#305, 1.8.1; Reddit too): a photo from the phone, the cover of another version of
 * the song (a lyric video's thumbnail swapped for the album's), or back to its own. Kept on this phone and in backups;
 * shown in lists, the player, the notification and Android Auto.
 */
@Composable
fun CoverPickSheet(target: CoverFor, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val own = c.covers.custom(target.key)
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    fun refresh() {
        when (target) {
            is CoverFor.OfSong -> c.player.refreshCovers { it.id == target.song.id }
            is CoverFor.OfAlbum -> c.player.refreshCovers { it.album?.id == target.id }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = c.covers.usePhoto(target.key, uri)
            if (ok) refresh()
            toast(if (ok) tr(R.string.cover_changed) else tr(R.string.couldn_t_use_that_picture))
            onDismiss()
        }
    }
    // Other recordings' covers, for a song (a lyric video's or a fan upload's picture swapped for the album's).
    val others by produceState<List<String>?>(null, target.key) {
        val song = (target as? CoverFor.OfSong)?.song?.takeIf { !it.isLocal && !it.isEpisode } ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val found = c.ytm.search("${song.title} ${song.primaryArtist}".trim(), SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
                otherCovers(song, found)
            }.getOrDefault(emptyList())
        }
    }
    PrismSheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(tr(R.string.change_cover), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(target.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp), maxLines = 1)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Artwork(target.original, Modifier.size(72.dp), LocalUi.current.smallArt, size = 226)
                    Text(tr(R.string.its_own_cover), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (own != null) Column {
                    Artwork(own, Modifier.size(72.dp), LocalUi.current.smallArt, size = 226)
                    Text(tr(R.string.yours), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SheetAction(PrismIcons.Photo, tr(R.string.choose_a_photo)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            val list = others
            if (target is CoverFor.OfSong && list == null) PrismSpinner(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).size(20.dp), stroke = 2.dp)
            if (!list.isNullOrEmpty()) {
                Text(
                    tr(R.string.covers_of_other_versions), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { url ->
                        Artwork(
                            url,
                            Modifier.size(84.dp).testTag("otherCover").then(
                                if (url == own) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, LocalUi.current.smallArt) else Modifier
                            ).clickable {
                                c.covers.useImage(target.key, url)
                                refresh()
                                toast(tr(R.string.cover_changed))
                                close {}
                            },
                            LocalUi.current.smallArt, size = 226,
                        )
                    }
                }
            }
            if (own != null) SheetAction(PrismIcons.Undo, tr(R.string.use_the_original)) {
                c.covers.reset(target.key)
                refresh()
                toast(tr(R.string.its_own_cover_again))
                close {}
            }
        }
    }
}

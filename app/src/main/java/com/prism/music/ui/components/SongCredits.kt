package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A song's credits (Reddit 1.7: "can we have view song credits?"): who performed, wrote and produced it, as YouTube
 * Music lists them. Opened from a song's menu and the player's ⋯.
 */
@Composable
fun SongCreditsSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    // null while loading; an empty list when YouTube has none.
    val credits by produceState<Result<List<Pair<String, List<String>>>>?>(null, song.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Another upload of the song (a video, a remaster's own id) often has none: the same song by title and
                // artist from the songs search then stands in.
                c.ytm.credits(song.id).ifEmpty {
                    val want = com.prism.music.data.innertube.SearchRank.norm(song.title)
                    val twin = c.ytm.search("${song.title} ${song.primaryArtist}", com.prism.music.data.innertube.SearchFilter.SONGS).items
                        .filterIsInstance<com.prism.music.data.model.SongItem>().map { it.song }
                        .firstOrNull { it.id != song.id && com.prism.music.data.innertube.SearchRank.norm(it.title) == want }
                    twin?.let { c.ytm.credits(it.id) }.orEmpty()
                }
            }
        }
    }
    PrismSheet(onDismiss) { _ ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(tr(R.string.song_credits), style = MaterialTheme.typography.titleLarge)
                Text(listOf(song.title, song.artistText).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val r = credits
            when {
                r == null -> PrismSpinner(Modifier.size(22.dp), stroke = 2.dp)
                r.isFailure -> Text(tr(R.string.couldn_t_load_the_credits_check), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.getOrThrow().isEmpty() -> Text(
                    if (song.isVideo) tr(R.string.music_videos_don_t_come_with)
                    else tr(R.string.youtube_music_has_no_credits_for),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> r.getOrThrow().forEach { (title, names) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Eyebrow(title)
                        names.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
}

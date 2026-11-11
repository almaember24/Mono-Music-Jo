package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What adding a Smart Shuffle pick to the playlist [title] did ([result] null: it failed), as Add to playlist says it
 * ("Added to …", "Already in …", "Couldn't add to …"; 1.8.1: "Added" showed when the song was already there), and
 * whether the song is the playlist's own now (no longer a pick).
 */
internal fun smartPickAdded(result: com.prism.music.data.innertube.YouTubeMusic.PlaylistAdd?, title: String): Pair<String, Boolean> =
    com.prism.music.ui.components.addedMessage(result, title, many = false) to (result != null && (result.added > 0 || result.alreadyThere > 0))

/**
 * Beside the like button while a Smart Shuffle pick plays: add it to the playlist it was picked for (a playlist on the
 * signed-in account), or "Not for me" (disliked, and on to the next song).
 */
@Composable
fun SmartPickButton(song: Song) {
    val c = LocalContainer.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val source by c.queue.source.collectAsState()
    val playlist = source.originId?.takeIf { source.kind == QueueKind.PLAYLIST && c.settings.current.isLoggedIn && it.startsWith("PL") }
    Box {
        CircleGlyph(PrismIcons.SmartShuffle, tr(R.string.smart_shuffle_pick), active = true) { open = true }
        DropdownMenu(open, { open = false }) {
            Text(
                tr(R.string.picked_for_this_queue_by_smart), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (playlist != null) DropdownMenuItem(
                { Text(tr(R.string.add_to, source.title.ifBlank { tr(R.string.this_playlist_2) })) },
                {
                    open = false
                    c.scope.launch(Dispatchers.Main) {
                        val result = runCatching { withContext(Dispatchers.IO) { c.ytm.addSongsToPlaylist(playlist, listOf(song.id)) } }.getOrNull()
                        val (message, kept) = smartPickAdded(result, source.title.ifBlank { tr(R.string.this_playlist_2) })
                        // Kept: it's the playlist's own song now, not a pick.
                        if (kept) c.queue.smartPicks.value = c.queue.smartPicks.value - song.id
                        if ((result?.added ?: 0) > 0) c.library.playlistChanged(playlist)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
                leadingIcon = { Icon(PrismIcons.PlaylistAdd, null) },
            )
            DropdownMenuItem(
                { Text(tr(R.string.not_for_me)) },
                { open = false; c.library.setDisliked(song, true); c.player.next() },
                leadingIcon = { Icon(PrismIcons.ThumbDown, null) },
            )
        }
    }
}

package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.prism.music.R
import com.prism.music.data.SavedWhilePlaying
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.SwipeToQueue
import com.prism.music.ui.components.pageTitleStyle
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/**
 * Library → Saved while playing (#159): songs in the song cache whole, so they play without a connection
 * ([SavedWhilePlaying] says what counts). Looked at each time the page comes up; nothing runs while it isn't shown.
 */
@Composable
fun SavedWhilePlayingScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val songs by produceState<List<Song>?>(null) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { value = SavedWhilePlaying.orNone { SavedWhilePlaying.list(c) } }
    }
    val source = QueueSource(QueueKind.LIBRARY, tr(R.string.saved_while_playing))
    var info by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction(PrismIcons.ArrowBack, tr(R.string.back)) { nav.back() }
            Spacer(Modifier.weight(1f))
            val list = songs.orEmpty()
            if (list.isNotEmpty()) {
                RoundAction(PrismIcons.Shuffle, tr(R.string.shuffle)) { c.player.playQueue(list, 0, source, shuffle = true) }
                Spacer(Modifier.width(8.dp))
                RoundAction(PrismIcons.Play, tr(R.string.widget_play)) { c.player.playQueue(list, 0, source) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.saved_while_playing), Modifier.weight(1f, fill = false), style = pageTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfoMark(info, tr(R.string.saved_while_playing)) { info = !info }
            }
            songs?.takeIf { it.isNotEmpty() }?.let { Text(songCount(it.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            InfoText(info, tr(R.string.saved_while_playing_info))
        }
        val list = songs
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState(PrismIcons.DownloadDone, tr(R.string.saved_while_playing_none_title), tr(R.string.saved_while_playing_none_body))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = bottomPadding + 16.dp)) {
                itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                    SwipeToQueue(s) { SongRow(s) { c.player.playQueue(list, i, source) } }
                }
            }
        }
    }
}

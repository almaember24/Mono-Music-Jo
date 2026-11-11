package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.Duplicates
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.SongRow

/**
 * A playlist's duplicates (1.8.3): each extra copy, with the copy that stays under it, and one button to take the
 * extras out, asked first; on a YouTube playlist ([youTube]) that writes to the account, and the question says so.
 */
@Composable
internal fun DuplicatesSheet(groups: List<Duplicates.Group>, youTube: Boolean, onDismiss: () -> Unit, onRemove: (List<Song>) -> Unit) {
    val extras = groups.flatMap { it.extras }
    var asking by remember { mutableStateOf(false) }
    com.prism.music.ui.components.PrismSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp).testTag("duplicates")) {
            Text(
                trp(R.plurals.n_songs_are_here_more_than_once, groups.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                groups.forEachIndexed { i, g ->
                    // The extra copies first: they're what goes.
                    g.extras.forEachIndexed { k, s -> item("x:$i:$k") { SongRow(s, trailingInfo = tr(R.string.extra_copy)) {} } }
                    item("k:$i") {
                        Text(
                            tr(R.string.keeping_s, g.keep.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
                PrismButton(onClick = { asking = true }, modifier = Modifier.testTag("removeDuplicates")) {
                    Text(trp(R.plurals.remove_n_extra_copies, extras.size))
                }
            }
        }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        text = { Text(trp(if (youTube) R.plurals.this_takes_n_songs_out_on_youtube else R.plurals.this_takes_n_songs_out, extras.size)) },
        confirmButton = { PrismTextButton(onClick = { asking = false; onRemove(extras) }, modifier = Modifier.testTag("confirmRemoveDuplicates")) { Text(tr(R.string.remove)) } },
        dismissButton = { PrismTextButton(onClick = { asking = false }) { Text(tr(R.string.cancel)) } },
    )
}

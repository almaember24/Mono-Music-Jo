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
import com.prism.music.data.innertube.SongFacts
import com.prism.music.data.model.Song
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A whole number as the phone's language writes it: "4,123,456,789". */
fun wholeNumber(n: Long, locale: Locale = Locale.getDefault()): String = java.text.NumberFormat.getIntegerInstance(locale).format(n)

/** A date as the phone's language writes it: "November 29, 2019". */
fun longDate(d: LocalDate, locale: Locale = Locale.getDefault()): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

/**
 * A song's plays and dates (#211), from a song's menu. Shows what YouTube's answer really holds and the listener's own
 * plays; anything missing has no row at all.
 */
@Composable
fun SongDetailsSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    // As a picked version plays (#328): that recording's numbers, under that recording's name.
    val shown = com.prism.music.data.SongVersions.shownOf(song)
    val id = c.versions.streamedOf(song.id) ?: song.id
    PrismSheet(onDismiss) { _ ->
        // Asked from inside the sheet, whose content composes apart from this page: an answer can't arrive before what
        // shows it is watching for it (under test one did, and the spinner stayed).
        val facts by produceState<Result<SongFacts>?>(null, id) {
            value = withContext(Dispatchers.IO) { runCatching { c.ytm.songFacts(id) } }
        }
        // Your own plays need no network, and count what Replay counts.
        val mine by produceState(0, song.id) {
            value = withContext(Dispatchers.IO) { runCatching { c.db.counted().songPlays(c.imports.countedOrigins(), song.id) }.getOrDefault(0) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(tr(R.string.song_details), style = MaterialTheme.typography.titleLarge)
                Text(listOf(shown.title, shown.artistText).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val r = facts
            val f = r?.getOrNull()
            if (r == null) PrismSpinner(Modifier.size(22.dp), stroke = 2.dp)
            f?.count?.let { DetailRow(if (shown.isVideo) tr(R.string.song_details_views) else tr(R.string.song_details_plays), wholeNumber(it)) }
            f?.releasedOn?.let { DetailRow(tr(R.string.song_details_released), longDate(it)) }
            // When the upload went up isn't when the song came out, so it isn't called that.
            if (f?.releasedOn == null) f?.publishedOn?.let { DetailRow(tr(R.string.song_details_on_youtube_since), longDate(it)) }
            if (mine > 0) DetailRow(tr(R.string.song_details_your_plays), wholeNumber(mine.toLong()))
            if (r?.isFailure == true) Text(tr(R.string.song_details_couldnt_load), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (f?.isEmpty == true && mine == 0) Text(tr(R.string.song_details_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

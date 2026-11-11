package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalContainer

/** Every mood and genre YouTube Music offers, best fits for you first. */
@Composable
fun MoodsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val moods = rememberLoad("moods") { c.taste.rank(c.ytm.moodsAndGenres()) }
    SubPage(tr(R.string.moods_genres), bottomPadding, subtitle = tr(R.string.best_fits_for_you_first)) {
        when (val s = moods.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(s.message) { moods.reload(false) } }
            is Load.Ok -> {
                val groups = listOf(tr(R.string.picked_for_you) to s.value.picks.take(12)) + s.value.sections
                // Keyed by place: two sections can share a title (#346, "Per te" twice in Italian).
                groups.forEachIndexed { g, (title, tiles) ->
                    if (tiles.isEmpty()) return@forEachIndexed
                    item(key = "h:$g") {
                        Text(
                            title, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
                        )
                    }
                    tiles.chunked(2).forEachIndexed { r, pair ->
                        item(key = "r:$g:$r") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { mood ->
                                    MoodTile(mood, Modifier.weight(1f).height(84.dp)) { nav.open(mood) { c.player.playSingle(it.song) } }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

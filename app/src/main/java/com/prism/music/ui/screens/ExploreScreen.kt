package com.prism.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.innertube.ChartCountry
import com.prism.music.data.innertube.Explore
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/** How many mood and genre tiles the page shows before "See all". */
private const val MOOD_TILES = 12
private const val SONG_ROWS = 6

/**
 * Explore (1.8.2): YouTube Music's charts by country, moods and genres, new releases and trending, in one place. The
 * charts and the rest load apart, so one failing leaves the other, and only shelves YouTube really sent are drawn.
 */
@Composable
fun ExploreScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    // For this visit: which country's charts to look at is no setting. Null: the one YouTube picks for the listener.
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val explore = rememberLoad("explore") { c.ytm.explore() }
    val charts = rememberLoad("charts:$country") { c.ytm.charts(country) }
    val nav = LocalNavigator.current
    val open: (BrowseItem) -> Unit = { item -> nav.open(item) { c.player.playSingle(it.song) } }
    val failedBoth = explore.state is Load.Err && charts.state is Load.Err

    SubPage(tr(R.string.explore), bottomPadding, Modifier.testTag("explore")) {
        if (failedBoth) {
            item("failed") { ErrorState((charts.state as Load.Err).message) { charts.reload(false); explore.reload(false) } }
            return@SubPage
        }
        when (val s = charts.state) {
            Load.Loading -> item("chartsLoading") { LoadingState() }
            is Load.Err -> item("chartsFailed") { ErrorState(s.message) { charts.reload(false) } }
            is Load.Ok -> {
                val page = s.value
                item("chartsHeader") {
                    SectionHeader(tr(R.string.explore_charts))
                    if (page.countries.size > 1) Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
                        PrismChip(false, { picking = true }, page.selected?.name ?: tr(R.string.explore_pick_country), icon = PrismIcons.ChevronDown)
                    }
                }
                exploreShelves(page.shelves, "chart", open)
                if (page.shelves.isEmpty()) item("chartsNone") {
                    Text(
                        tr(R.string.explore_no_charts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
        when (val s = explore.state) {
            Load.Loading -> item("exploreLoading") { LoadingState() }
            is Load.Err -> item("exploreFailed") { ErrorState(s.message) { explore.reload(false) } }
            is Load.Ok -> exploreShelves(s.value, "explore", open)
        }
    }

    if (picking) {
        val page = (charts.state as? Load.Ok)?.value
        CountrySheet(page?.countries.orEmpty(), page?.selected?.code, onDismiss = { picking = false }) { country = it.code; picking = false }
    }
}

/** Each shelf as what it holds: mood tiles, song rows, or a row of cards. Keys go by place, as titles can repeat (#346). */
private fun LazyListScope.exploreShelves(shelves: List<Shelf>, name: String, open: (BrowseItem) -> Unit) {
    shelves.forEachIndexed { place, shelf ->
        if (Explore.isMoods(shelf)) {
            val tiles = shelf.items.filterIsInstance<MoodItem>().filter { !com.prism.music.data.meta.Genres.isRegional(it.title) }
            if (tiles.isEmpty()) return@forEachIndexed
            item(key = "$name:$place:h") {
                val nav = LocalNavigator.current
                SectionHeader(shelf.title, onMore = { nav.go(Routes.MOODS) })
            }
            tiles.take(MOOD_TILES).chunked(2).forEachIndexed { r, pair ->
                item(key = "$name:$place:$r") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { mood -> PrismMoodTile(mood, Modifier.weight(1f)) { open(mood) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            return@forEachIndexed
        }
        item(key = "$name:$place:h") {
            val nav = LocalNavigator.current
            SectionHeader(shelf.title, strapline = shelf.strapline, onMore = shelf.moreBrowseId?.let { id ->
                { if (id.startsWith("VL")) nav.go(Routes.playlist(id)) else nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) }
            })
        }
        val songs = shelf.items.filterIsInstance<SongItem>().map { it.song }
        if (songs.size == shelf.items.size && songs.none { it.isVideo }) {
            items(songs.take(SONG_ROWS).size, key = { i -> "$name:$place:$i:" + songs[i].id }) { i ->
                val c = LocalContainer.current
                SongRow(songs[i]) { c.player.playQueue(songs, i, QueueSource(QueueKind.OTHER, shelf.title)) }
            }
        } else {
            item(key = "$name:$place:c") { ItemCarousel(shelf.items, open) }
        }
    }
}

@Composable
private fun CountrySheet(countries: List<ChartCountry>, selected: String?, onDismiss: () -> Unit, onPick: (ChartCountry) -> Unit) {
    PrismSheet(onDismiss = onDismiss) {
        Text(tr(R.string.explore_pick_country), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 22.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)) {
            items(countries, key = { it.code }) { country ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(country) }.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(country.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (country.code == selected) Icon(PrismIcons.Check, tr(R.string.chosen), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

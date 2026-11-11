package com.prism.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.PrismIcons

/**
 * A screen's light behind everything (TV, 1.8.0): the cover's color glowing from the top corner into the background.
 * No blur and no motion (TVs before Android 12 can't blur, and slow ones stutter on moving backgrounds).
 */
fun Modifier.tvBackdrop(accent: Color?, base: Color): Modifier = drawBehind {
    drawRect(base)
    if (accent != null) drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(size.width * 0.18f, 0f), radius = size.width * 0.75f,
        ),
    )
}

/**
 * Home (TV): what's playing as a large hero at the top, then rows the remote moves through (Quick picks, Speed dial,
 * your mixes, recently played, YouTube's shelves). Focus opens on the hero's "Now playing" (or the first row's first
 * card when nothing plays); each row remembers its column.
 */
@Composable
fun TvHomeScreen(
    greeting: String,
    now: TvNow?,
    shelves: List<TvShelf>,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenLyrics: () -> Unit,
    onItem: (row: Int, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    banner: (@Composable () -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    /** Where the now-playing hero sits among [shelves] (0: on top, as it comes); -1: hidden (Customize Home). */
    heroAt: Int = 0,
    /** Customize Home, at the end of Home. */
    onCustomize: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val heroShown = now != null && heroAt >= 0
    val heroIndex = if (heroShown) heroAt.coerceIn(0, shelves.size) else -1
    // Search stands in while Home loads; if it had focus when the song or the rows come (it goes), focus goes to their
    // first item as on opening Home, not to the side navigation.
    val memory = LocalTvFocusMemory.current
    val screen = LocalTvScreen.current
    if ((heroShown || shelves.isNotEmpty()) && memory.lastFocused[screen] == "$TAG_HOME/search") {
        memory.lastFocused.remove(screen)
        memory.opening(screen)
    }
    // Rows run to the screen's edges (their cards grow when focused, and slide in from the edge); everything else keeps
    // the 48 dp margins.
    // Keys that stay with each row (and card) as Home refreshes, so what's on screen isn't redrawn for nothing.
    val rowKeys = remember(shelves) { occurrenceKeys(shelves.map { it.title }) }
    val list = rememberLazyListState()
    // The list's items above the rows: the banner, and the greeting or the hero on top.
    TvPrefetch(list, shelves, rowsBefore = (if (banner != null) 1 else 0) + (if (!heroShown || heroIndex == 0) 1 else 0))
    LazyColumn(
        modifier.fillMaxSize().testTag(TAG_HOME),
        state = list,
        contentPadding = PaddingValues(top = 27.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (banner != null) item { Box(Modifier.padding(horizontal = 48.dp)) { banner() } }
        // Focus opens on Home's first thing: the hero's "Now playing" when it's on top, else the first row's first card.
        val hero: @Composable () -> Unit = {
            Box(Modifier.padding(horizontal = 48.dp).testTag("$TAG_HOME/hero")) {
                if (now != null) TvNowHero(now, onPlayPause, onOpenPlayer, onOpenLyrics, entry = heroIndex == 0)
            }
        }
        if (!heroShown) item {
            Box(Modifier.padding(horizontal = 48.dp)) {
                Column {
                    Text(greeting, style = MaterialTheme.typography.displaySmall, color = scheme.onBackground)
                    // Nothing to show yet (offline, or Home still loading): Search, so focus is never nowhere.
                    if (shelves.isEmpty() && onSearch != null) {
                        Spacer(Modifier.height(24.dp))
                        TvButton(tr(R.string.search), onSearch, Modifier.tvItem("$TAG_HOME/search", entry = true), icon = PrismIcons.Search)
                    }
                }
            }
        }
        if (heroIndex == 0) item(key = HERO_ITEM) { hero() }
        shelves.forEachIndexed { r, shelf ->
            item(key = rowKeys[r]) {
                Column {
                    TvRowTitle(shelf.title, Modifier.padding(start = 48.dp).testTag("$TAG_HOME/title$r"))
                    LazyRow(
                        Modifier.rememberedFocus().testTag("$TAG_HOME/row$r"),
                        contentPadding = TvRowPadding,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(shelf.items, key = { i, item -> item.id + "#" + i }) { i, item ->
                            val first = heroIndex != 0 && r == 0 && i == 0
                            TvCard(
                                item.art, item.title, item.subtitle, { onItem(r, i) },
                                modifier = Modifier.tvItem("$TAG_HOME/row$r/$i", entry = first),
                                round = item.kind == TvItem.Kind.ARTIST,
                            )
                        }
                    }
                }
            }
            if (heroIndex == r + 1) item(key = HERO_ITEM) { hero() }
        }
        // The way to Customize Home from Home itself: at its end, after the last row.
        if (onCustomize != null && (heroShown || shelves.isNotEmpty())) item {
            Box(Modifier.padding(horizontal = 48.dp)) {
                TvButton(tr(R.string.customize_home), onCustomize, Modifier.tvItem("$TAG_HOME/customize"), icon = PrismIcons.Customize)
            }
        }
    }
}

/** Home's hero: the playing song's cover, title, artist and progress, with play/pause, the player and its lyrics. */
@Composable
private fun TvNowHero(now: TvNow, onPlayPause: () -> Unit, onOpenPlayer: () -> Unit, onOpenLyrics: () -> Unit, entry: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(196.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(now.art, Modifier.size(196.dp), shape = RoundedCornerShape(14.dp), size = 544)
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.now_playing).uppercase(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, brush = tvLight()))
            Spacer(Modifier.height(6.dp))
            Text(now.title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(now.artist, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            TvProgress(now.fraction, now.played, now.total, Modifier.width(420.dp))
            Spacer(Modifier.height(10.dp))
            // Left from the hero's first button opens the side navigation (not the row of cards below; the cover beside
            // it can't take focus).
            val nav = LocalTvNavFocus.current
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TvIconButton(
                    if (now.playing) PrismIcons.Pause else PrismIcons.Play,
                    tr(if (now.playing) R.string.widget_pause else R.string.widget_play), onPlayPause,
                    Modifier.focusProperties { if (nav != null) left = nav }.tvItem("$TAG_HOME/playpause"),
                )
                TvButton(tr(R.string.now_playing), onOpenPlayer, Modifier.tvItem("$TAG_HOME/player", entry = entry), icon = PrismIcons.PlayCircle, primary = true)
                if (now.hasLyrics) TvButton(tr(R.string.lyrics), onOpenLyrics, Modifier.tvItem("$TAG_HOME/lyrics"), icon = PrismIcons.Lyrics)
            }
        }
    }
}

/** The update banner on Home (TV): a new Prism is out; OK downloads and installs it (Android asks once to allow it). */
@Composable
fun TvUpdateBanner(version: String, onUpdate: () -> Unit, modifier: Modifier = Modifier, note: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().tvGlass(TvShapes.card).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.tv_update_ready, version), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Text(note ?: tr(R.string.tv_update_note), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        TvButton(tr(R.string.update), onUpdate, Modifier.tvItem("$TAG_HOME/update"), icon = PrismIcons.Update, primary = true)
    }
}

const val TAG_HOME = "tv_home"
/** The hero's item key in Home's list (row keys are their titles, which never start with a space). */
private const val HERO_ITEM = " hero"

/** Keys from [titles], unique: a title that comes twice gets "#2" the second time. */
internal fun occurrenceKeys(titles: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return titles.map { t -> val n = seen.merge(t, 1, Int::plus) ?: 1; if (n == 1) t else "$t#$n" }
}

/** How many cards of a row ahead are fetched (a row's first screenful on a TV). */
private const val PREFETCH_CARDS = 8

/**
 * Fetches the next rows' pictures (at the size their cards draw them) as Home scrolls, so a row comes in with its
 * covers already there. [rowsBefore]: the list's items above the rows (the hero, a banner).
 */
@Composable
fun TvPrefetch(list: androidx.compose.foundation.lazy.LazyListState, shelves: List<TvShelf>, rowsBefore: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 132.dp.roundToPx() }
    LaunchedEffect(list, shelves) {
        val fetched = HashSet<Int>()
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val loader = coil3.SingletonImageLoader.get(context)
            for (r in (last - rowsBefore + 1)..(last - rowsBefore + 2)) {
                val shelf = shelves.getOrNull(r) ?: continue
                if (!fetched.add(r)) continue
                shelf.items.take(PREFETCH_CARDS).mapNotNull { it.art }.forEach { url ->
                    loader.enqueue(coil3.request.ImageRequest.Builder(context).data(com.prism.music.data.model.hiRes(url, 360)).size(px).build())
                }
            }
        }
    }
}
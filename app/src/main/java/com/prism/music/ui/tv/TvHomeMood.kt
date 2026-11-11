package com.prism.music.ui.tv

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.prism.music.R
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Shelf
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A mood's or genre's shelves, kept for this run of Prism (Back and in again shows them at once). */
private val moodShelves = java.util.concurrent.ConcurrentHashMap<String, List<Shelf>>()

/**
 * A mood or genre's page on the TV (Home's Moods & genres tiles, 1.8.0): its YouTube shelves as rows, drawn as Home's
 * are (its name on top), each card opening or playing as on Home.
 */
@Composable
fun TvMoodPage(mood: MoodItem, nav: TvNav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = mood.id + ":" + mood.params
    val shelves by produceState(moodShelves[key], key) {
        if (value != null) return@produceState
        val got = withContext(Dispatchers.IO) { runCatching { c.ytm.browseShelves(mood.id, mood.params) }.getOrNull() }
        if (got.isNullOrEmpty()) {
            Toast.makeText(context, tr(R.string.couldn_t_start, mood.title), Toast.LENGTH_SHORT).show()
            nav.back()
        } else { moodShelves[key] = got; value = got }
    }
    val loaded = shelves ?: run { TvStarting {}; return }
    val rows = remember(loaded) { loaded.mapNotNull { s -> s.items.take(20).takeIf { it.isNotEmpty() }?.let { s.title to it } } }
    val tvShelves = remember(rows) { rows.map { (title, items) -> TvShelf(title, items.map { it.toTvItem() }) } }
    TvHomeScreen(
        mood.title, null, tvShelves,
        onPlayPause = {}, onOpenPlayer = {}, onOpenLyrics = {},
        onItem = { r, i ->
            val (title, items) = rows.getOrNull(r) ?: return@TvHomeScreen
            val item = items.getOrNull(i) ?: return@TvHomeScreen
            if (!openOwn(c, nav, item, emptyMap()) && !open(c, nav, item, items, i, title, radio = false) { scope.launch { it() } })
                Toast.makeText(context, tr(R.string.couldn_t_start, item.title), Toast.LENGTH_SHORT).show()
        },
        heroAt = -1,
    )
}

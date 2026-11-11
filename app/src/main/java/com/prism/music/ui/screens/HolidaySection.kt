package com.prism.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.data.seasonal.HolidayMix
import com.prism.music.data.seasonal.HolidayMixes
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Playable
import com.prism.music.ui.components.PlayActionsSheet
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.ShareTarget
import com.prism.music.ui.components.SheetItem
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home's holiday section (1.8.0), as Spotify's and Apple Music's seasonal hubs are: "Thanksgiving" through November,
 * "Holiday music" through December, each of Prism's holiday mixes a card of its own cover. The cards cost nothing to
 * show; while they're shown on Wi-Fi the mixes are made ahead, one at a time ([com.prism.music.data.seasonal.SeasonalMixes.makeAhead]),
 * so opening one (its page, a long press, Play) is instant. Otherwise a mix is made when it's opened.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HolidaySection(cfg: HomeSectionConfig) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val today = c.seasonal.today()
    val holiday = c.seasonal.holiday() ?: return
    val mixes = remember(today) { HolidayMixes.on(today) }
    if (mixes.isEmpty()) return
    // Once the row is on screen (on Wi-Fi only: makeAhead checks), the first card first; it carries on behind Home.
    LaunchedEffect(mixes) { c.seasonal.makeAhead(mixes) }
    var menu by remember { mutableStateOf<HolidayMix?>(null) }
    menu?.let { m -> HolidayMixSheet(m) { menu = null } }
    SectionHeader(cfg.titled(tr(holiday.section)), Modifier.testTag("holidaySection"), strapline = tr(R.string.holiday_mixes_strapline))
    if (cfg.style != SectionStyle.CAROUSEL) {
        StyledEntries(cfg.style, mixes.map { m ->
            HomeEntry(m.id, tr(m.name), tr(m.blurb), art = { mod -> HolidayCover(m, mod, showTitle = false) }, onLongClick = { menu = m }) { nav.go(Routes.mix(m.id)) }
        })
        return
    }
    val w = LocalUi.current.cardWidth
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(mixes, key = { it.id }) { m ->
            Column(
                Modifier.width(w).clip(LocalUi.current.tile)
                    .combinedClickable(onClick = { nav.go(Routes.mix(m.id)) }, onLongClick = { menu = m })
                    .padding(4.dp),
            ) {
                HolidayCover(m, Modifier.fillMaxWidth().aspectRatio(1f).clip(LocalUi.current.art))
                Spacer(Modifier.height(8.dp))
                Text(tr(m.blurb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A holiday mix's long-press menu: play, shuffle, queue, a DJ set… and pin it to Home or keep a copy of it. */
@Composable
private fun HolidayMixSheet(mix: HolidayMix, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    // Drawn into its file the first time, off the main thread (the sheet opens at once and takes it when it's there).
    val cover by produceState<String?>(null, mix.id) { value = withContext(Dispatchers.Default) { runCatching { HolidayCovers.file(context, mix) }.getOrNull() } }
    val name = tr(mix.name)
    val target = Playable.Page(
        name, tr(mix.blurb), cover, album = false,
        share = ShareTarget(name, tr(mix.blurb), tr(R.string.mix_2), cover, null, songs = c.seasonal.keptNow(mix.id)?.songs.orEmpty()),
    ) {
        val songs = c.seasonal.mix(mix.id)?.songs.orEmpty()
        songs to QueueSource(QueueKind.PLAYLIST, name, originId = mix.id)
    }
    PlayActionsSheet(target, holidayActions(c, context, mix), extraTitle = tr(R.string.this_playlist), onDismiss = onDismiss)
}

/**
 * What a holiday mix offers besides playing (its card's long press and its page's ⋯): a section of its own on Home
 * (pinned at the top; shown in its season), and a copy kept as a Prism playlist, which stays when the season's over.
 */
fun holidayActions(c: AppContainer, context: android.content.Context, mix: HolidayMix): List<SheetItem> {
    val pin = OwnSections.pinOf(mix)
    val layout = c.settings.current.homeLayout
    val on = layout.any { it.key == pin.key }
    return listOf(
        SheetItem(if (on) PrismIcons.PinFilled else PrismIcons.Pin, if (on) tr(R.string.unpin_from_home) else tr(R.string.pin_to_home)) {
            val now = c.settings.current.homeLayout
            c.settings.setHomeLayout(if (now.any { it.key == pin.key }) now.filterNot { it.key == pin.key } else listOf(pin) + now)
            Toast.makeText(context, if (on) tr(R.string.taken_off_home) else tr(R.string.pinned_to_the_top_of_home), Toast.LENGTH_SHORT).show()
        },
        SheetItem(PrismIcons.PlaylistAdd, tr(R.string.save_a_copy_as_a_prism)) { copyHolidayMix(c, context, mix) },
    )
}

/** [mix]'s songs, as they are now, into a new Prism playlist of its name. */
private fun copyHolidayMix(c: AppContainer, context: android.content.Context, mix: HolidayMix) {
    val name = tr(mix.name)
    Toast.makeText(context, tr(R.string.copying, name), Toast.LENGTH_SHORT).show()
    c.scope.launch(Dispatchers.Main) {
        val songs = withContext(Dispatchers.IO) { runCatching { c.seasonal.mix(mix.id)?.songs }.getOrNull() }
        if (songs.isNullOrEmpty()) {
            Toast.makeText(context, tr(R.string.couldn_t_load, name), Toast.LENGTH_SHORT).show()
            return@launch
        }
        c.prismLibrary.create(name, songs)
        Toast.makeText(context, tr(R.string.saved_as_a_prism_playlist, name, com.prism.music.data.model.songCount(songs.size)), Toast.LENGTH_SHORT).show()
    }
}

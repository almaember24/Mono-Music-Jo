package com.prism.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.prism.music.R
import com.prism.music.data.foryou.Discover
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * New to you or Deep cuts on Home (1.8.2): the week's songs in the section's style, More for the mix's page. Made the first
 * time the section is seen each week, in the app's scope so leaving Home doesn't cut it short; until then, and with too
 * little to go on, the section stays away.
 */
@Composable
internal fun DiscoverMixSection(cfg: HomeSectionConfig, online: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val id = if (cfg.key == HomeSections.NEW_TO_YOU) Discover.NEW else Discover.DEEP
    LaunchedEffect(id) { c.scope.launch { runCatching { c.discover.refresh(id) } } }
    val mixes by c.discover.mixes.collectAsState()
    val mix = mixes[id] ?: return
    // Offline, only what plays without a connection (new songs mostly don't).
    val songs = remember(mix, online) { if (online) mix.songs else mix.songs.filter { c.availableOffline(it.id) } }
    SongSection(
        cfg, cfg.titled(), mix.subtitle, songs,
        // A mix's queue: its skips and listens teach recommendations, as a Daily Mix's do.
        play = { list, i -> c.player.playQueue(list, i, QueueSource(QueueKind.PLAYLIST, mix.title, originId = mix.id)) },
        onMore = { nav.go(Routes.mix(mix.id)) },
    )
}

/** What was played a year ago today (1.8.2). With nothing played that day, there's no section at all. */
@Composable
internal fun OnThisDaySection(cfg: HomeSectionConfig, online: Boolean) {
    val c = LocalContainer.current
    val today = LocalDate.now()
    LaunchedEffect(today) { c.scope.launch { runCatching { c.discover.loadOnThisDay(today) } } }
    c.discover.onThisDayChanged.collectAsState().value
    val shown = c.discover.onThisDaySongs(today).filter { online || c.availableOffline(it.id) }
    if (shown.isEmpty()) return
    val day = remember(today) { today.minusYears(1).format(com.prism.music.localDates("yMMMd")) }
    SongSection(cfg, cfg.titled(), tr(R.string.on_this_day_line, day), shown)
}

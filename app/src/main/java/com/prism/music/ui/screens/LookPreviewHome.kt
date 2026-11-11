package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.MiniProgress
import com.prism.music.data.prefs.NavBarStyle
import com.prism.music.data.prefs.NavLabels
import com.prism.music.data.prefs.NavTab
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.components.pageTitleStyle
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.ThemeColors

/** Home as [c]'s settings draw it: its background, title, a card, a list and a row of covers, then the mini player and tab bar. */
@Composable
fun MiniHome(c: ThemeColors, song: Song?) {
    val s = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = c.scheme
    val light = Brush.horizontalGradient(*c.run.toTypedArray())
    Box(Modifier.fillMaxSize()) {
        PageGround(c, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 44.dp), verticalArrangement = Arrangement.spacedBy(ui.gap(14.dp))) {
            Text(tr(R.string.good_evening), style = pageTitleStyle(), color = scheme.onBackground, maxLines = 1)
            // Replay's card, quietly.
            Box(Modifier.fillMaxWidth().height(96.dp).clip(ui.card).background(frostFill(1.4f)).border(1.dp, c.line, ui.card).padding(16.dp)) {
                Column {
                    Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(light))
                    Spacer(Modifier.height(10.dp))
                    Text("42h 10m", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraLight, color = scheme.onBackground)
                    Text(tr(R.string.your_year_so_far), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
            }
            Text(tr(R.string.quick_picks), style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
            Column(verticalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                listOf(tr(R.string.northern_lights), tr(R.string.paper_planes), tr(R.string.slow_tide)).forEachIndexed { i, title ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0) StandIn(song, Modifier.size(44.dp), ui.smallArt, standIns[0])
                        else Box(Modifier.size(44.dp).clip(ui.smallArt).background(Brush.linearGradient(standIns[i])))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (i == 0) song?.title ?: title else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = if (i == 0) MaterialTheme.typography.bodyLarge.copy(brush = light) else MaterialTheme.typography.bodyLarge,
                                color = if (i == 0) Color.Unspecified else scheme.onBackground,
                            )
                            Text(tr(R.string.prism_sample), style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1)
                        }
                    }
                }
            }
            Text(tr(R.string.made_for_you), style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (1..3).forEach { i ->
                    Box(Modifier.size(ui.cardWidth).clip(ui.art).background(Brush.linearGradient(standIns[i + 1])))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val docked = s.navStyle == NavBarStyle.DOCKED
            Box(Modifier.padding(horizontal = if (docked) 10.dp else 12.dp).padding(bottom = 8.dp)) { MiniBar(c, song) }
            if (docked) TabBarPreview(c) else Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) { TabBarPreview(c) }
        }
    }
}

/** The mini player in its style ([MiniPlayerStyle]), with its buttons and progress. */
@Composable
private fun MiniBar(c: ThemeColors, song: Song?) {
    val s = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = c.scheme
    val shape = when (s.miniPlayerStyle) { MiniPlayerStyle.PILL -> CircleShape; MiniPlayerStyle.SLIM -> ui.shape(14.dp); MiniPlayerStyle.CARD -> ui.card }
    val height = when (s.miniPlayerStyle) { MiniPlayerStyle.CARD -> 64.dp; MiniPlayerStyle.SLIM -> 54.dp; MiniPlayerStyle.PILL -> 56.dp }
    val light = Brush.horizontalGradient(*c.run.toTypedArray())
    Box(Modifier.fillMaxWidth().height(height).clip(shape).background(frostFill(2.6f)).border(1.dp, c.line, shape)) {
        if (s.miniProgress == MiniProgress.FILL) Box(Modifier.fillMaxHeight().fillMaxWidth(0.42f).background(scheme.onBackground.copy(alpha = 0.08f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StandIn(song, Modifier.size(height - 20.dp), if (s.miniPlayerStyle == MiniPlayerStyle.PILL) CircleShape else ui.smallArt, standIns[0])
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: tr(R.string.northern_lights), style = MaterialTheme.typography.bodyMedium, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song?.artistText ?: tr(R.string.prism_sample), style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.miniPrevButton) Icon(PrismIcons.Previous, null, Modifier.size(22.dp), tint = scheme.onBackground)
            if (s.view.miniPlayButton) Box(Modifier.padding(horizontal = 6.dp).size(36.dp).clip(CircleShape).border(1.5.dp, Brush.sweepGradient(c.ring), CircleShape), contentAlignment = Alignment.Center) {
                Icon(PrismIcons.Play, null, Modifier.size(16.dp), tint = scheme.onBackground)
            }
            if (s.miniSkipButtons) Icon(PrismIcons.Next, null, Modifier.size(22.dp), tint = scheme.onBackground)
        }
        if (s.miniProgress == MiniProgress.LINE) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp).fillMaxWidth(0.42f).height(2.dp).clip(CircleShape).background(light))
    }
}

private fun NavTab.icon(): ImageVector = when (this) {
    NavTab.HOME -> PrismIcons.Home
    NavTab.SEARCH -> PrismIcons.Search
    NavTab.LIBRARY -> PrismIcons.Library
    NavTab.REPLAY -> PrismIcons.Replay
    NavTab.SETTINGS -> PrismIcons.Settings
    NavTab.LOCAL -> PrismIcons.Phone
    NavTab.DOWNLOADS -> PrismIcons.Download
    NavTab.LIKED -> PrismIcons.Heart
    NavTab.PODCASTS -> PrismIcons.Podcasts
}

/** The tab bar in its style ([NavBarStyle]) with its labels, the first tab current. */
@Composable
private fun TabBarPreview(c: ThemeColors) {
    val s = LocalAppSettings.current
    val scheme = c.scheme
    val light = Brush.horizontalGradient(*c.run.toTypedArray())
    val docked = s.navStyle == NavBarStyle.DOCKED
    val minimal = s.navStyle == NavBarStyle.MINIMAL
    val shape = if (docked) RoundedCornerShape(0.dp) else CircleShape
    Row(
        Modifier.fillMaxWidth().height(if (minimal) 54.dp else 62.dp).clip(shape)
            .background(if (docked) scheme.background.copy(alpha = 0.94f) else frostFill(2.6f))
            .then(if (docked) Modifier else Modifier.border(1.dp, c.line, shape)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        s.navTabs.forEachIndexed { i, tab ->
            val on = i == 0
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (docked && on) Box(Modifier.width(24.dp).height(2.dp).clip(CircleShape).background(light))
                if (docked) Spacer(Modifier.height(6.dp))
                val words = !minimal && s.navLabels == NavLabels.WORDS
                if (!words) Icon(tab.icon(), null, Modifier.size(22.dp), tint = if (on) scheme.onBackground else c.ink3)
                val showLabel = !minimal && (s.navLabels == NavLabels.ALWAYS || words || (s.navLabels == NavLabels.SELECTED && on))
                if (showLabel) Text(s.tabNames[tab] ?: tab.label, style = MaterialTheme.typography.labelSmall, color = if (on) scheme.onBackground else c.ink3, maxLines = 1)
                if (!docked && on) Box(Modifier.padding(top = 3.dp).width(14.dp).height(2.dp).clip(CircleShape).background(light))
            }
        }
    }
}

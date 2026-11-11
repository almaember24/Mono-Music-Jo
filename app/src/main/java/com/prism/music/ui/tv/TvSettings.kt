package com.prism.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.prism.music.ui.theme.PrismIcons

/** One setting on the TV: a title, its value (a choice) or a switch, and an explanation behind ⓘ (never shown as a note). */
data class TvSetting(val title: String, val value: String? = null, val on: Boolean? = null, val info: String? = null)

/** A group of settings in the left column. */
data class TvSettingGroup(val title: String, val icon: ImageVector, val settings: List<TvSetting>)

/**
 * Settings (TV): the short TV set (account, language, sound quality, lyrics, Sing, ambient screen, text size, about
 * and updates). Groups on the left; moving along them shows each group's settings on the right, where Right enters.
 * OK changes a setting (a switch flips, a choice opens its list); a setting with ⓘ has its explanation one step Right.
 * Built on the phone's settings layout (1.8.0's settings clean-up): the same groups, names and words.
 */
@Composable
fun TvSettingsScreen(
    groups: List<TvSettingGroup>,
    group: Int,
    onGroup: (Int) -> Unit,
    onSetting: (Int) -> Unit,
    onInfo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusedSetting: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val light = tvLight()
    Row(modifier.fillMaxSize().padding(start = 48.dp, top = 27.dp, bottom = 27.dp).testTag(TAG_SETTINGS)) {
        LazyColumn(Modifier.width(300.dp).fillMaxHeight().rememberedFocus(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(groups) { i, g ->
                ListItem(
                    selected = i == group,
                    onClick = { onGroup(i) },
                    modifier = Modifier.tvCurrentMark(i == group, light).tvItem("$TAG_SETTINGS/group/$i", entry = i == group && focusedSetting == null).onFocusChanged { if (it.isFocused && i != group) onGroup(i) },
                    leadingContent = { Icon(g.icon, null, Modifier.size(22.dp)) },
                    headlineContent = { Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ListItemDefaults.colors(
                        selectedContainerColor = TvGlassFill, focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface,
                        focusedSelectedContainerColor = scheme.onSurface, focusedSelectedContentColor = scheme.surface,
                    ),
                    shape = ListItemDefaults.shape(TvShapes.card),
                    border = ListItemDefaults.border(focusedBorder = tvFocusBorder(TvShapes.card, TV_RING_THIN)),

                )
            }
        }
        Spacer(Modifier.width(32.dp))
        val settings = groups.getOrNull(group)?.settings.orEmpty()
        // Right from the groups enters a group's settings at its first one (the design's path), whatever its height.
        val firstSetting = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().tvGlass(TvShapes.panelStart).padding(20.dp)
                .focusProperties { onEnter = { if (byDpad()) firstSetting.requestFocus() } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(settings) { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ListItem(
                        selected = false,
                        onClick = { onSetting(i) },
                        modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(firstSetting) else Modifier).then(Modifier.tvItem("$TAG_SETTINGS/setting/$i", entry = i == focusedSetting)),
                        headlineContent = { Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = s.value?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = s.on?.let { on -> { Switch(checked = on, onCheckedChange = null) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface),
                        shape = ListItemDefaults.shape(TvShapes.card),
                        border = ListItemDefaults.border(focusedBorder = tvFocusBorder(TvShapes.card, TV_RING_THIN)),
                    )
                    if (s.info != null) {
                        Spacer(Modifier.width(8.dp))
                        TvIconButton(PrismIcons.Info, s.info, { onInfo(i) }, Modifier.tvItem("$TAG_SETTINGS/info/$i"))
                    }
                }
            }
        }
    }
}

/** A setting's explanation (its ⓘ), over the settings until Back or OK. */
@Composable
fun TvInfoCard(title: String, text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.width(520.dp).tvGlass(TvShapes.panel, TvPanelFill).padding(32.dp).testTag("$TAG_SETTINGS/infocard")) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
}

const val TAG_SETTINGS = "tv_settings"

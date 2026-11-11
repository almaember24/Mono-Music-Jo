package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.ChoiceFlow
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.Segmented
import com.prism.music.ui.components.pane
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.hairline
import com.prism.music.ui.theme.ink3
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription

/** The group a Settings search result opened its page at (#73): it scrolls into view and glows for a moment. */
val LocalSettingsFind = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * A titled card of settings rows: a small label, then the rows on a pane of glass. [info]: what the whole group is
 * about, behind an ⓘ beside its title (opened inside the card, above the rows).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Group(title: String, info: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title, "group") { androidx.compose.runtime.mutableStateOf(false) }
    val ui = LocalUi.current
    val found = title.isNotBlank() && LocalSettingsFind.current == title
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    // Asked for with three quarters of the screen below its top, so the group lands in the upper part of the page
    // rather than just inside the bottom edge, under the tab bar.
    val reach = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() * 0.75f }
    if (found) LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300) // once the page has laid out
        requester.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, reach))
        glow.animateTo(1f, androidx.compose.animation.core.tween(220))
        kotlinx.coroutines.delay(900)
        glow.animateTo(0f, androidx.compose.animation.core.tween(800))
    }
    Column(Modifier.then(if (found) Modifier.bringIntoViewRequester(requester) else Modifier)) {
        if (title.isNotBlank()) Row(Modifier.padding(start = 12.dp, bottom = if (info != null) 2.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(title)
            if (info != null) InfoMark(open, title) { open = !open }
        }
        Column(
            Modifier.fillMaxWidth().pane(ui.card)
                // A search result's group: ringed in split light for a moment.
                .then(if (glow.value > 0.02f) Modifier.border((2.5f * glow.value).dp, com.prism.music.ui.theme.PrismTokens.spectrum, ui.card) else Modifier)
                .padding(vertical = ui.gap(6.dp)),
        ) {
            InfoText(open && info != null, info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            content()
        }
    }
}

/**
 * The less-used groups of a page, folded away under one row that names them (1.7.6: pages had a dozen groups each).
 * It opens by itself when Settings search leads to one of [groups].
 */
@Composable
fun MoreOptions(groups: List<String>, content: @Composable () -> Unit) {
    val found = LocalSettingsFind.current in groups
    var open by androidx.compose.runtime.saveable.rememberSaveable(groups.joinToString(), "more") { androidx.compose.runtime.mutableStateOf(found) }
    val ui = LocalUi.current
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(ui.card).pane(ui.card).clickable(onClickLabel = if (open) tr(R.string.show_fewer_options) else tr(R.string.show_more_options)) { open = !open }
                .padding(horizontal = 16.dp, vertical = ui.gap(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (open) tr(R.string.fewer_options) else tr(R.string.more_options), style = MaterialTheme.typography.bodyLarge)
                if (!open) Text(groups.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) PrismIcons.ChevronUp else PrismIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) content()
    }
}

/** A small heading inside a group, above a choice. [info]: what the choice means, behind an ⓘ beside it. */
@Composable
fun Label(text: String, info: String? = null) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(text, "label") { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (info != null) 8.dp else 14.dp, bottom = if (info != null) 2.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
            if (info != null) InfoMark(open, text) { open = !open }
        }
        InfoText(open && info != null, info, Modifier.padding(bottom = 4.dp))
    }
}

/**
 * Text that is always shown inside a group: a state or a figure (space used, an error), or steps to follow. A setting's
 * explanation doesn't go here: it goes behind the ⓘ of what it explains ([Toggle], [Item], [Label] or [Group] `info`).
 */
@Composable
fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The small ⓘ that opens a setting's details (ink when open). */
@Composable
fun InfoMark(open: Boolean, about: String, onClick: () -> Unit) {
    Icon(
        PrismIcons.Info, if (open) tr(R.string.hide_details) else tr(R.string.more_about, about),
        Modifier.padding(start = 6.dp).size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick).padding(6.dp),
        tint = if (open) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.ink3,
    )
}

/** The details an [InfoMark] opens, sliding open under it. */
@Composable
fun InfoText(open: Boolean, text: String?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(open && text != null) {
        Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
    }
}

/**
 * A setting's title and its one short line, plus (with [info]) a small ⓘ that opens the full explanation underneath:
 * how it works, what it uses, what leaves the phone. Settings read at a glance; the detail is one tap away.
 */
@Composable
private fun ColumnScope.SettingText(title: String, subtitle: String?, info: String?) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { androidx.compose.runtime.mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
        if (info != null) InfoMark(open, title) { open = !open }
    }
    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    InfoText(open && info != null, info, Modifier.padding(top = 6.dp))
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, info: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    // The whole row is the switch (one target for TalkBack too); the switch itself only shows the state.
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = ui.gap(13.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) { SettingText(title, subtitle, info) }
        Spacer(Modifier.width(12.dp))
        PrismSwitch(checked, null, enabled = enabled)
    }
}

@Composable
fun Item(title: String, subtitle: String?, onClick: () -> Unit, icon: ImageVector? = null, info: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = ui.gap(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) { SettingText(title, subtitle, info) }
        trailing?.invoke()
    }
}

/** One thing that can be shown or hidden, for [ShowChips]. */
class ShowOption(val label: String, val on: Boolean, val onChange: (Boolean) -> Unit)

/**
 * Several things to show or hide (what's on the player), as chips that tick on and off, instead of a long run of
 * switches: the whole set reads at a glance.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShowChips(options: List<ShowOption>) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            com.prism.music.ui.components.PrismChip(
                o.on, { o.onChange(!o.on) }, o.label,
                icon = if (o.on) PrismIcons.Check else null,
                modifier = androidx.compose.ui.Modifier.semantics { role = androidx.compose.ui.semantics.Role.Checkbox; stateDescription = if (o.on) tr(R.string.shown) else tr(R.string.hidden) },
            )
        }
    }
}

/**
 * A row that opens another page: an icon, title, summary, chevron. With a [hue] (Settings' sections, red to violet in
 * the spectrum's order) the icon sits on a tile of that color.
 */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, selected: Boolean = false, hint: String? = null, hue: Color? = null, onClick: () -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth()
            // The page open beside the list (wide windows) is marked.
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = ui.gap(11.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hue != null) androidx.compose.foundation.layout.Box(
            Modifier.size(34.dp).clip(ui.smallArt).background(hue), contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = Color.White) }
        else Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(if (hue != null) 14.dp else 18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            // What to do first (a search result hidden behind another switch).
            if (!hint.isNullOrBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Icon(PrismIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.ink3)
    }
}

/** One choice among a few: a segmented capsule for up to four short options, wrapping pills otherwise. */
@Composable
fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    // Roughly what fits across a phone in one capsule without cutting words off.
    val segmented = options.size in 2..4 && options.all { label(it).length <= 12 } && options.sumOf { label(it).length } <= 30
    if (segmented) Segmented(options, selected, label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onSelect)
    else ChoiceFlow(options, { it == selected }, label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onSelect)
}

/** A slider with its title and current value; saves when you let go. */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    steps: Int = 0,
    enabled: Boolean = true,
    /** What it does, behind an ⓘ beside the title. */
    info: String? = null,
    onSave: (Float) -> Unit,
) {
    var v by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { v = value }
    var open by androidx.compose.runtime.saveable.rememberSaveable(title, "slider") { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                if (info != null) InfoMark(open, title) { open = !open }
            }
            Text(valueText(v), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoText(open && info != null, info, Modifier.padding(top = 2.dp, bottom = 2.dp))
        PrismSlider(v, { v = it }, enabled = enabled, valueRange = range, steps = steps, onValueChangeFinished = { onSave(v) })
    }
}

/**
 * A slider's value as a word and its number ("Airy · 140%"): the word says what it does, the number lets you come back
 * to exactly the same place (#209: some sliders only said "Balanced").
 */
fun worded(word: String, v: Float) = "$word · ${kotlin.math.round(v * 100).toInt()}%"

/**
 * A round tick: filled in ink with a check when on, an empty hairline circle when off (the Tabs list). The 48 dp row
 * around it is the target.
 */
@Composable
fun PrismCheck(checked: Boolean, enabled: Boolean = true, label: String, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        // Named on and off alike (off, it used to have no name for a screen reader).
        Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .then(
                    if (checked) Modifier.background(scheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
                    else Modifier.border(1.5.dp, scheme.onSurface.copy(alpha = if (enabled) 0.35f else 0.15f), androidx.compose.foundation.shape.CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(PrismIcons.Check, null, Modifier.size(14.dp), tint = scheme.surface)
        }
    }
}
package com.prism.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.SmartField
import com.prism.music.data.SmartMatch
import com.prism.music.data.SmartRule
import com.prism.music.data.SmartRules
import com.prism.music.data.SmartSort
import com.prism.music.data.db.SongTraitsEntity
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/** A smart playlist to start from (1.8.3): its name and rules. */
data class SmartStarter(val name: String, val rules: SmartRules)

/** The starters: fast and liked; not played in a while; what goes with the song playing (its key and tempo). */
object SmartStarters {
    fun fastAndLiked() = SmartStarter(
        tr(R.string.smart_fast_and_liked),
        SmartRules(listOf(SmartRule(SmartField.BPM, min = 120.0), SmartRule(SmartField.LIKED)), SmartMatch.ALL, SmartSort.MOST_PLAYED, 50),
    )

    fun notPlayedInAWhile() = SmartStarter(
        tr(R.string.smart_not_played_in_a_while),
        SmartRules(listOf(SmartRule(SmartField.LAST_PLAYED, not = true, min = 90.0), SmartRule(SmartField.PLAYS, min = 3.0)), SmartMatch.ALL, SmartSort.MOST_PLAYED, 50),
    )

    /** Null when the song playing hasn't been measured (no key or tempo to go by). */
    fun goesWith(playing: SongTraitsEntity?): SmartStarter? {
        val camelot = playing?.camelot ?: return null
        val rules = listOfNotNull(
            SmartRule(SmartField.GOES_WITH, text = camelot),
            playing.bpm?.let { SmartRule(SmartField.BPM, min = Math.round(it - 8).toDouble(), max = Math.round(it + 8).toDouble()) },
        )
        return SmartStarter(tr(R.string.smart_goes_with_what_s_playing), SmartRules(rules, SmartMatch.ALL, SmartSort.MOST_PLAYED, 50))
    }
}

/** A field's name in the editor. */
fun smartFieldLabel(f: SmartField): String = when (f) {
    SmartField.BPM -> tr(R.string.rule_bpm)
    SmartField.KEY -> tr(R.string.key)
    SmartField.GOES_WITH -> tr(R.string.rule_goes_with)
    SmartField.ENERGY -> tr(R.string.rule_energy)
    SmartField.LOUDNESS -> tr(R.string.rule_loudness)
    SmartField.LIKED -> tr(R.string.liked)
    SmartField.DOWNLOADED -> tr(R.string.downloaded)
    SmartField.ON_PHONE -> tr(R.string.rule_on_phone)
    SmartField.TAG -> tr(R.string.rule_tag)
    SmartField.GENRE -> tr(R.string.genre)
    SmartField.ARTIST -> tr(R.string.artist)
    SmartField.ALBUM -> tr(R.string.album)
    SmartField.PLAYS -> tr(R.string.rule_times_played)
    SmartField.LAST_PLAYED -> tr(R.string.rule_last_played)
    SmartField.ADDED -> tr(R.string.rule_added)
    SmartField.NEVER_PLAYED -> tr(R.string.rule_never_played)
}

fun smartSortLabel(s: SmartSort): String = when (s) {
    SmartSort.MOST_PLAYED -> tr(R.string.sort_most_played)
    SmartSort.RECENTLY_ADDED -> tr(R.string.recently_added)
    SmartSort.RECENTLY_PLAYED -> tr(R.string.recently_played)
    SmartSort.LEAST_RECENTLY_PLAYED -> tr(R.string.sort_not_played_lately)
    SmartSort.BPM -> tr(R.string.rule_bpm)
    SmartSort.ENERGY -> tr(R.string.rule_energy)
    SmartSort.TITLE -> tr(R.string.title)
    SmartSort.RANDOM -> tr(R.string.sort_random)
}

/** A new rule on [f], with a value to start from. */
fun newRule(f: SmartField): SmartRule = when (f) {
    SmartField.BPM -> SmartRule(f, min = 100.0, max = 130.0)
    SmartField.ENERGY -> SmartRule(f, min = 0.6)
    SmartField.LOUDNESS -> SmartRule(f, min = -10.0)
    SmartField.KEY, SmartField.GOES_WITH -> SmartRule(f, text = "8A")
    SmartField.PLAYS -> SmartRule(f, min = 5.0)
    SmartField.LAST_PLAYED, SmartField.ADDED -> SmartRule(f, min = 30.0)
    else -> SmartRule(f)
}

/** All 24 keys by their Camelot code, with their names ("8A · A minor"). */
private val CAMELOT: List<Pair<String, String>> = (0 until 12).flatMap { t -> listOf(true, false).map { com.prism.music.playback.MusicKey(t, it) } }
    .sortedWith(compareBy({ it.camelotNumber }, { !it.minor })).map { it.camelot to it.name }

/**
 * A smart playlist's rules (1.8.3): a new one ([listId] null, with starters to begin from) or an existing one's. Each
 * rule in a card of its own; all or any; sort and length; how many songs they find, worked out as they change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartEditorSheet(listId: String?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val existing = remember(listId) { listId?.let { c.prismLibrary.list(it) } }
    var name by remember { mutableStateOf(existing?.title.orEmpty()) }
    var rules by remember { mutableStateOf(existing?.smart ?: SmartRules()) }
    // Bumped when a starter replaces the rules: the cards start over from its numbers (they keep their own typing).
    var starts by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val playing by c.player.currentSong.collectAsState()
    val playingTraits by produceState<SongTraitsEntity?>(null, playing?.id) { value = playing?.id?.let { runCatching { c.db.traits().get(it) }.getOrNull() } }
    val found by produceState<Int?>(null, rules) {
        value = null
        kotlinx.coroutines.delay(250)
        value = if (rules.rules.isEmpty()) null else runCatching { c.smart.run(rules).songs.size }.getOrNull()
    }
    PrismSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp).verticalScroll(rememberScrollState()).testTag("smartEditor")) {
            Text(if (existing == null) tr(R.string.new_smart_playlist) else tr(R.string.edit_rules), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            PrismTextField(name, { name = it.take(150) }, Modifier.fillMaxWidth().testTag("smartName"), label = { Text(tr(R.string.name)) }, singleLine = true)
            if (existing == null) {
                Spacer(Modifier.height(12.dp))
                Text(tr(R.string.start_from), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val goes = SmartStarters.goesWith(playingTraits)
                    listOfNotNull(SmartStarters.fastAndLiked(), SmartStarters.notPlayedInAWhile()).forEach { s ->
                        PrismChip(false, { name = s.name; rules = s.rules; starts++ }, s.name, icon = PrismIcons.Add)
                    }
                    PrismChip(false, {
                        if (goes == null) android.widget.Toast.makeText(context, tr(R.string.the_song_playing_isn_t_measured_yet), android.widget.Toast.LENGTH_SHORT).show()
                        else { name = goes.name; rules = goes.rules; starts++ }
                    }, tr(R.string.smart_goes_with_what_s_playing), icon = PrismIcons.Add)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismChip(rules.match == SmartMatch.ALL, { rules = rules.copy(match = SmartMatch.ALL) }, tr(R.string.match_all_rules))
                PrismChip(rules.match == SmartMatch.ANY, { rules = rules.copy(match = SmartMatch.ANY) }, tr(R.string.match_any_rule))
            }
            Spacer(Modifier.height(10.dp))
            rules.rules.forEachIndexed { i, r ->
                // Each card keeps its typing while others come and go.
                androidx.compose.runtime.key(starts, rules.rules.size, i, r.field) { RuleCard(
                    r, i,
                    onChange = { nr -> rules = rules.copy(rules = rules.rules.toMutableList().also { it[i] = nr }) },
                    onRemove = { rules = rules.copy(rules = rules.rules.toMutableList().also { it.removeAt(i) }) },
                ) }
                Spacer(Modifier.height(8.dp))
            }
            AddRuleButton { f -> rules = rules.copy(rules = rules.rules + newRule(f)) }
            Spacer(Modifier.height(14.dp))
            // Sort and length.
            var sortMenu by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.sort_by), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Box {
                    PrismChip(true, { sortMenu = true }, smartSortLabel(rules.sort), icon = PrismIcons.Sort)
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SmartSort.entries.forEach { s ->
                            DropdownMenuItem({ Text(smartSortLabel(s)) }, { rules = rules.copy(sort = s); sortMenu = false }, trailingIcon = { if (s == rules.sort) Icon(PrismIcons.Check, null) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.up_to), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                listOf(25, 50, 100, null).forEach { n ->
                    PrismChip(rules.limit == n, { rules = rules.copy(limit = n) }, n?.toString() ?: tr(R.string.all), Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    rules.rules.isEmpty() -> tr(R.string.add_a_rule_to_begin)
                    found == null -> " "
                    else -> tr(R.string.finds_n, com.prism.music.data.model.songCount(found!!))
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("smartFinds"),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrismTextButton(onClick = { close {} }) { Text(tr(R.string.cancel)) }
                PrismTextButton(onClick = {
                    val title = name.ifBlank { tr(R.string.smart_playlist) }
                    close {
                        if (existing != null) c.prismLibrary.setSmart(existing.id, title, rules)
                        else nav.go(Routes.playlist(c.prismLibrary.createSmart(title, rules)))
                    }
                }, enabled = rules.rules.isNotEmpty(), modifier = Modifier.testTag("smartSave")) { Text(tr(R.string.save)) }
            }
        }
    }
}

/** "Add a rule": every field, under Sound, Library and Your listening. */
@Composable
private fun AddRuleButton(onAdd: (SmartField) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PrismChip(false, { open = true }, tr(R.string.add_a_rule), Modifier.testTag("addRule"), icon = PrismIcons.Add)
        DropdownMenu(open, { open = false }) {
            val groups = listOf(
                tr(R.string.sound) to SmartField.entries.filter { it.sound },
                tr(R.string.library) to listOf(SmartField.LIKED, SmartField.DOWNLOADED, SmartField.ON_PHONE, SmartField.TAG, SmartField.GENRE, SmartField.ARTIST, SmartField.ALBUM),
                tr(R.string.your_listening) to listOf(SmartField.PLAYS, SmartField.LAST_PLAYED, SmartField.ADDED, SmartField.NEVER_PLAYED),
            )
            groups.forEachIndexed { gi, (label, fields) ->
                if (gi > 0) HorizontalDivider()
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                fields.forEach { f -> DropdownMenuItem({ Text(smartFieldLabel(f)) }, { onAdd(f); open = false }, Modifier.testTag("field:${f.name}")) }
            }
        }
    }
}

/** One rule: its field, what it asks, and ✕. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(r: SmartRule, index: Int, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    val c = LocalContainer.current
    Column(
        Modifier.fillMaxWidth().background(glassFill(1.2f), RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)
            .testTag("rule:$index"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(smartFieldLabel(r.field), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(PrismIcons.Close, tr(R.string.remove), Modifier.size(18.dp)) }
        }
        @Composable
        fun isOrNot() = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismChip(!r.not, { onChange(r.copy(not = false)) }, tr(R.string.rule_is))
            PrismChip(r.not, { onChange(r.copy(not = true)) }, tr(R.string.rule_isn_t))
        }
        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (r.field) {
                SmartField.BPM -> Range(r, tr(R.string.rule_from), tr(R.string.rule_to), onChange)
                SmartField.LOUDNESS -> Range(r, tr(R.string.rule_from), tr(R.string.rule_to), onChange, signed = true)
                // Energy as 0–100 (it's kept as 0..1).
                SmartField.ENERGY -> Range(r, tr(R.string.rule_from), tr(R.string.rule_to), onChange, scale = 100.0)
                SmartField.PLAYS -> Range(r, tr(R.string.rule_at_least), tr(R.string.rule_at_most), onChange, whole = true)
                SmartField.KEY, SmartField.GOES_WITH -> {
                    if (r.field == SmartField.KEY) isOrNot()
                    var open by remember { mutableStateOf(false) }
                    Box {
                        PrismChip(true, { open = true }, CAMELOT.firstOrNull { it.first == r.text }?.let { "${it.first} · ${it.second}" } ?: r.text.orEmpty(), icon = PrismIcons.ChevronDown)
                        DropdownMenu(open, { open = false }) {
                            CAMELOT.forEach { (code, keyName) -> DropdownMenuItem({ Text("$code · $keyName") }, { onChange(r.copy(text = code)); open = false }) }
                        }
                    }
                }
                SmartField.TAG -> {
                    isOrNot()
                    val tags by c.tags.summaries.collectAsState()
                    if (tags.isEmpty()) Text(tr(R.string.tags_are_your_own_labels), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { t -> PrismChip(r.text == t.tag, { onChange(r.copy(text = t.tag)) }, t.tag, icon = PrismIcons.Tag) }
                    }
                }
                SmartField.GENRE, SmartField.ARTIST, SmartField.ALBUM -> {
                    isOrNot()
                    var text by remember(index, r.field) { mutableStateOf(r.text.orEmpty()) }
                    PrismTextField(text, { text = it.take(100); onChange(r.copy(text = it.take(100))) }, Modifier.fillMaxWidth().testTag("ruleText:$index"), singleLine = true,
                        placeholder = { Text(if (r.field == SmartField.GENRE) tr(R.string.genre_example) else smartFieldLabel(r.field)) })
                }
                SmartField.LIKED, SmartField.DOWNLOADED, SmartField.ON_PHONE, SmartField.NEVER_PLAYED -> isOrNot()
                SmartField.LAST_PLAYED, SmartField.ADDED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrismChip(!r.not, { onChange(r.copy(not = false)) }, tr(R.string.rule_within))
                        PrismChip(r.not, { onChange(r.copy(not = true)) }, tr(R.string.rule_not_within))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField(r.min, { onChange(r.copy(min = it)) }, Modifier.width(110.dp).testTag("ruleDays:$index"), whole = true)
                        Spacer(Modifier.width(10.dp))
                        Text(tr(R.string.rule_days), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** From / to (either may be left empty), shown ×[scale]. */
@Composable
private fun Range(r: SmartRule, from: String, to: String, onChange: (SmartRule) -> Unit, scale: Double = 1.0, whole: Boolean = false, signed: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField(r.min?.times(scale), { onChange(r.copy(min = it?.div(scale))) }, Modifier.weight(1f), label = from, whole = whole || scale != 1.0, signed = signed)
        NumberField(r.max?.times(scale), { onChange(r.copy(max = it?.div(scale))) }, Modifier.weight(1f), label = to, whole = whole || scale != 1.0, signed = signed)
    }
}

/** [signed]: a keyboard with a minus (loudness is below zero; a number pad may have none). */
@Composable
private fun NumberField(value: Double?, onValue: (Double?) -> Unit, modifier: Modifier, label: String? = null, whole: Boolean = false, signed: Boolean = false) {
    fun shown(v: Double?) = v?.let { if (whole || it == Math.rint(it)) Math.round(it).toString() else "%.1f".format(java.util.Locale.ROOT, it) }.orEmpty()
    var text by remember { mutableStateOf(shown(value)) }
    PrismTextField(
        text, { t ->
            text = t.take(8)
            onValue(text.replace(',', '.').toDoubleOrNull())
        },
        modifier, singleLine = true, label = label?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else if (whole) KeyboardType.Number else KeyboardType.Decimal),
    )
}

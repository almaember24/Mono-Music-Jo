package com.prism.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.TagStore
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/** A song's tags beside "Add a tag" in its menus: "gym, rainy day", shortened when long. */
fun tagsValue(tags: List<String>): String? = tags.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { if (it.length > 22) it.take(21).trimEnd(',', ' ') + "…" else it }

/**
 * The listener's tags for [songs] (one, or many picked: 1.8.3): each tag a chip, lit when all of them have it; a tap
 * puts it on all of them, or takes it off when they all have it. A new one is typed at the top. [what] names them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(songs: List<Song>, what: String?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val list = remember(songs) { songs.filter(TagStore::taggable).distinctBy { it.id } }
    val ids = remember(list) { list.map { it.id } }
    val all by c.tags.summaries.collectAsState()
    var changed by remember { mutableIntStateOf(0) }
    val holds by produceState(emptyMap<String, Int>(), ids, changed, all) { value = c.tags.holds(ids) }
    var typed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun add(tag: String) = scope.launch { c.tags.add(tag, list); changed++ }
    fun addTyped() {
        if (TagStore.clean(typed) == null) return
        add(typed)
        typed = ""
    }
    fun toggle(tag: String) = scope.launch {
        if ((holds[tag] ?: 0) >= ids.size) c.tags.remove(tag, ids) else c.tags.add(tag, list)
        changed++
    }
    PrismSheet(onDismiss = onDismiss) { _ ->
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("tagPicker")) {
            Text(tr(R.string.tags), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(what, com.prism.music.data.model.songCount(ids.size).takeIf { ids.size > 1 }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrismTextField(
                    typed, { typed = it.take(TagStore.MAX_LENGTH) }, Modifier.weight(1f).testTag("newTag"),
                    placeholder = { Text(tr(R.string.new_tag)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTyped() }),
                )
                IconButton(onClick = { addTyped() }, enabled = TagStore.clean(typed) != null, modifier = Modifier.testTag("addTag")) {
                    Icon(PrismIcons.Add, tr(R.string.add_a_tag))
                }
            }
            Spacer(Modifier.height(12.dp))
            if (all.isEmpty()) Text(tr(R.string.tags_are_your_own_labels), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                all.forEach { t ->
                    val has = holds[t.tag] ?: 0
                    val on = ids.isNotEmpty() && has >= ids.size
                    PrismChip(
                        on, { toggle(t.tag) },
                        // Several songs, some of them tagged: how many.
                        if (has in 1 until ids.size) "${t.tag} · $has/${ids.size}" else t.tag,
                        Modifier.testTag("tag:${t.tag}"), icon = if (on) PrismIcons.Check else PrismIcons.Tag,
                    )
                }
            }
        }
    }
}

package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookSchedule
import com.prism.music.data.looks.LookStore
import com.prism.music.data.looks.SunClock
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.components.runAction
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Appearance → Looks (1.7.3): Prism's own Looks and yours, each a card showing it on three small phones; saving the look
 * you have, sharing a Look as a QR code, text or a file, getting one, a Look for day and one for night, and the history
 * of look changes to go back through.
 */

const val LOOK_PREVIEW_KEY = "look-preview"

/**
 * The live preview at the top of every look page: Home (with the mini player and tab bar), the player and the lyrics,
 * drawn in the app's settings as they are, so it changes as you pick. [pinned]: the small copy held under the bar.
 */
@Composable
fun LookStrip(pinned: Boolean = false) {
    val s = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    if (pinned) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(frostFill(4f)).background(scheme.surface.copy(alpha = 0.82f))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center,
        ) { LookPhones(s, 48.dp, gap = 10.dp) }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(start = 4.dp)) {
            Text(tr(R.string.your_prism), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
            Text(tr(R.string.home_the_player_and_lyrics_as), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = ((maxWidth - 24.dp) / 3).coerceAtMost(132.dp)
            LookPhones(s, w, gap = 12.dp)
        }
    }
}

/** The Looks page. */
fun androidx.compose.foundation.lazy.LazyListScope.looksPage() {
    item(key = LOOK_PREVIEW_KEY) { LookStrip() }
    item(key = "looks") { LooksSettings() }
}

@Composable
private fun LooksSettings() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val mine by c.looks.mine.collectAsState()
    val current by c.looks.currentId.collectAsState()
    val pending by c.looks.pending.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Look?>(null) }
    var sharing by remember { mutableStateOf<Look?>(null) }
    var renaming by remember { mutableStateOf<Look?>(null) }

    fun apply(look: Look) = scope.launch { c.looks.apply(look) }
    fun take(look: Look?) {
        if (look == null) Toast.makeText(context, tr(R.string.that_isn_t_a_prism_look), Toast.LENGTH_SHORT).show() else c.looks.pending.value = look
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { c.looks.readFile(it) } } }
            r.getOrNull()?.let { c.looks.pending.value = it }
                ?: Toast.makeText(context, r.exceptionOrNull()?.message ?: tr(R.string.that_file_isn_t_a_prism), Toast.LENGTH_SHORT).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // What you have now, and keeping it.
        val now = current?.let(c.looks::find)
        // One of yours, changed since it was saved: it can be updated with the changes.
        val edited by c.looks.edited.collectAsState()
        val app = LocalAppSettings.current
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    edited != null -> tr(R.string.you_re_on_with_changes, edited!!.name)
                    now != null -> tr(R.string.you_re_on, now.name)
                    else -> tr(R.string.you_re_on_your_own_mix)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                edited?.let { e -> remember(e, app) { c.looks.edits(e) }?.let { tr(R.string.since_you_saved_it_update_it, it, e.name) } }
                    ?: tr(R.string.a_look_keeps_colors_type_icons),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                edited?.let { e ->
                    PrismButton({ scope.launch { c.looks.update(e) } }) { Icon(PrismIcons.Update, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.update)) }
                    PrismOutlinedButton({ saving = true }) { Icon(PrismIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.save_as_new)) }
                } ?: PrismButton({ saving = true }) { Icon(PrismIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.save_as_a_look)) }
                if (now != null && edited == null) PrismOutlinedButton({ sharing = now }) { Icon(PrismIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.share)) }
            }
            edited?.let { e -> PrismTextButton({ apply(e) }) { Icon(PrismIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.go_back_to_as_saved, e.name), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            // Icons are drawn as Prism starts: a Look with other icons shows them after a restart.
            if (c.looks.iconsNeedRestart()) PrismTextButton({
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Runtime.getRuntime().exit(0)
                }
            }) { Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.restart_prism_to_see_its_icons)) }
        }

        // The season's Look in its month (1.8.0) on a card of its own above Prism's; out of its month it isn't offered
        // (unless it's the one on, until the season ends it).
        val today = remember { com.prism.music.data.looks.Seasons.today() }
        com.prism.music.data.looks.Seasons.current(today)?.let { season ->
            BuiltInLooks.of(season.lookId)?.let { look -> SeasonCard(look) { apply(look) } }
        }
        Group(tr(R.string.prism_s_looks)) {
            LookGrid(BuiltInLooks.all.filter { com.prism.music.data.looks.Seasons.shown(it.id, today) || it.id == current }, current, onPick = ::apply, onMore = { acting = it })
        }
        Group(tr(R.string.your_looks)) {
            if (mine.isEmpty()) Note(tr(R.string.looks_you_save_show_here_save))
            else LookGrid(mine, current, onPick = ::apply, onMore = { acting = it })
        }
        CommunityGroup(current)
        Group(tr(R.string.get_a_look), info = tr(R.string.you_see_a_look_first_and)) {
            Item(tr(R.string.scan_a_look_s_qr_code), tr(R.string.from_a_friend_s_prism), icon = PrismIcons.Camera, onClick = { nav.go(Routes.LOOK_SCAN) }) { Chevron() }
            Item(tr(R.string.paste_a_look_code), tr(R.string.a_code_someone_sent_you), icon = PrismIcons.Copy, onClick = { take(c.looks.decode(clipboard.getText()?.text)) })
            Item(tr(R.string.open_a_look_file), tr(R.string.a_prismlook_file_with_its_photos), icon = PrismIcons.Photo, onClick = { openFile.launch(arrayOf("application/zip", "application/octet-stream")) })
        }
        ScheduleGroup()
        HistoryGroup()
    }

    if (saving) NameDialog(tr(R.string.save_as_a_look), tr(R.string.name), tr(R.string.my_look), onDismiss = { saving = false }) { name ->
        saving = false
        scope.launch {
            val look = c.looks.saveCurrent(name)
            Toast.makeText(context, tr(R.string.saved, look.name), Toast.LENGTH_SHORT).show()
        }
    }
    renaming?.let { look ->
        NameDialog(tr(R.string.rename), tr(R.string.name), look.name, onDismiss = { renaming = null }) { name -> renaming = null; c.looks.rename(look.id, name) }
    }
    acting?.let { look ->
        LookActions(look, onDismiss = { acting = null }, onApply = { apply(look) }, onShare = { sharing = look }, onRename = { renaming = look })
    }
    sharing?.let { look -> ShareLookSheet(look) { sharing = null } }
    pending?.let { look -> PendingLookDialog(look) }
}

/** What the community gallery is showing: nothing asked yet, loading, its Looks, or why it couldn't load. */
private sealed interface GalleryState {
    data object Closed : GalleryState
    data object Loading : GalleryState
    data class Shown(val looks: List<com.prism.music.data.looks.GalleryLook>) : GalleryState
    data class Failed(val why: String) : GalleryState
}

/** Kept while Prism runs, so the list doesn't close every time the page is left. */
private val galleryState = mutableStateOf<GalleryState>(GalleryState.Closed)

/**
 * Looks people shared on GitHub (themes/ in Prism's repository, added by pull request). Nothing is downloaded until
 * the listener asks; a gallery Look opens as a preview first, like any shared one.
 */
@Composable
private fun CommunityGroup(current: String?) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var state by galleryState
    var opening by remember { mutableStateOf<String?>(null) }
    fun load(refresh: Boolean) = scope.launch {
        state = GalleryState.Loading
        state = runCatching { c.lookGallery.looks(refresh) }.fold(
            { GalleryState.Shown(it) },
            { GalleryState.Failed(if (it is java.net.UnknownHostException) tr(R.string.you_re_offline) else it.message ?: tr(R.string.couldn_t_reach_github)) },
        )
    }
    Group(tr(R.string.from_the_community)) {
        when (val s = state) {
            GalleryState.Closed -> Item(
                tr(R.string.browse_community_looks), tr(R.string.looks_people_shared_on_github), icon = PrismIcons.Globe,
                info = tr(R.string.the_list_is_downloaded_from_prism),
                onClick = { load(false) },
            ) { Chevron() }
            GalleryState.Loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                com.prism.music.ui.components.PrismSpinner(Modifier.size(28.dp))
            }
            is GalleryState.Failed -> Item(tr(R.string.couldn_t_load_the_gallery), tr(R.string.tap_to_try_again, s.why), onClick = { load(true) })
            is GalleryState.Shown -> {
                if (s.looks.isEmpty()) Note(tr(R.string.no_community_looks_yet_yours_could))
                else LookGrid(
                    s.looks.map { it.look }, current,
                    onPick = { look ->
                        val g = s.looks.first { it.look.id == look.id }
                        if (opening == null) scope.launch {
                            opening = g.entry.id
                            runCatching { c.lookGallery.fetch(g, c.looks) }
                                .onSuccess { c.looks.pending.value = it }
                                .onFailure { Toast.makeText(context, it.message ?: tr(R.string.couldn_t_download_that_look), Toast.LENGTH_SHORT).show() }
                            opening = null
                        }
                    },
                    onMore = null,
                )
                if (opening != null) Note(tr(R.string.downloading_its_photos))
                Item(tr(R.string.refresh_the_list), tr(R.string.from_github), onClick = { load(true) })
            }
        }
        Item(
            tr(R.string.add_your_look), tr(R.string.by_pull_request_on_github), icon = PrismIcons.OpenInNew,
            info = tr(R.string.save_your_look_share_it_as),
            onClick = { runCatching { uri.openUri(com.prism.music.data.looks.LookGallery.ADD_YOURS) } },
        )
    }
}

/** [looks] two to a row, each a card with the Look on two small phones; [current] is ringed. */
@Composable
private fun LookGrid(looks: List<Look>, current: String?, onPick: (Look) -> Unit, onMore: ((Look) -> Unit)?) {
    val edited by LocalContainer.current.looks.edited.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        looks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { look ->
                    LookCard(look, look.id == current, Modifier.weight(1f), { onPick(look) }, onMore?.let { m -> { m(look) } }, changed = look.id == edited?.id)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A Look as a card: its Home and player, its name and what it is, a ⋯ for more. Tapping it switches to it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LookCard(look: Look, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onMore: (() -> Unit)? = null, changed: Boolean = false) {
    val c = LocalContainer.current
    val app = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val s = remember(look, app) { c.looks.preview(look) }
    val song by c.player.currentSong.collectAsState()
    Column(
        modifier.clip(ui.tile).background(frostFill(if (selected) 2.6f else 1f))
            .then(if (selected) Modifier.border(2.dp, PrismTokens.spectrum, ui.tile) else Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.08f), ui.tile))
            .semantics { role = Role.RadioButton; this.selected = selected }
            // A long press opens the card's ⋯ too, as every other card in Prism does.
            .combinedClickable(onClickLabel = tr(R.string.use, look.name), onLongClickLabel = onMore?.let { tr(R.string.more_for, look.name) }, onLongClick = onMore, onClick = onClick)
            .padding(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = ((maxWidth - 8.dp) / 2).coerceAtMost(80.dp)
            val dark = com.prism.music.ui.theme.isAppDark(s)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewPhone(w) { LookTheme(s, dark) { colors -> MiniHome(colors, song) } }
                PreviewPhone(w) { LookTheme(s, true) { colors -> MiniPlayerScreen(colors, song) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(look.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(PrismIcons.CheckCircle, tr(R.string.in_use), Modifier.size(18.dp))
            // Yours, in use with changes not saved into it yet.
            if (changed) Icon(PrismIcons.Edit, tr(R.string.in_use_changed_since_saved), Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            if (onMore != null) Icon(
                PrismIcons.More, tr(R.string.more_for, look.name),
                Modifier.padding(start = 2.dp).size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(6.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
        if (look.note.isNotBlank()) Text(look.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 2, maxLines = 3)
    }
}

/** A Look's ⋯: switch to it, share it, and (yours) rename, copy or delete it; Prism's own can be copied to make your own. */
@Composable
private fun LookActions(look: Look, onDismiss: () -> Unit, onApply: () -> Unit, onShare: () -> Unit, onRename: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    ActionSheet(null, onDismiss) { close ->
        Text(look.name, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
        if (look.note.isNotBlank()) Text(look.note, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val edited by c.looks.edited.collectAsState()
        val changed = edited?.id == look.id
        val changes = remember(look, changed) { if (changed) c.looks.edits(look)?.let { tr(R.string.since_you_saved_it, it) } else c.looks.changes(look) }
        Text(
            changes ?: tr(R.string.you_re_on_it_now), Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
        )
        val items = listOfNotNull(
            // Yours: save the app's look as it is now into it (your changes since, or any settings you've made since).
            if (!look.builtIn && changes != null) ActionItem(
                PrismIcons.Update, if (changed) tr(R.string.update_with_your_changes) else tr(R.string.update_to_your_settings_now),
                onClick = { scope.launch { c.looks.update(look) } },
            ) else null,
            ActionItem(PrismIcons.Check, if (changed) tr(R.string.go_back_to_it_as_saved) else tr(R.string.use_this_look), onClick = onApply),
            ActionItem(PrismIcons.Share, tr(R.string.share), onClick = onShare),
            if (!look.builtIn) ActionItem(PrismIcons.Edit, tr(R.string.rename), onClick = onRename) else null,
            ActionItem(PrismIcons.Copy, if (look.builtIn) tr(R.string.save_a_copy_to_change) else tr(R.string.duplicate), onClick = {
                scope.launch { c.looks.keep(look, if (look.builtIn) look.name else tr(R.string.copy_2, look.name)) }
            }),
            if (!look.builtIn) ActionItem(PrismIcons.Delete, tr(R.string.delete), danger = true, onClick = { c.looks.delete(look.id) }) else null,
        )
        ActionGroup(null, items) { runAction(it, close) }
    }
}

/** A name to type ([initial] picked), for saving or renaming a Look. */
@Composable
private fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                name, { name = it.take(Look.MAX_NAME) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
            )
        },
        confirmButton = { PrismTextButton({ onDone(name) }, enabled = name.isNotBlank()) { Text(tr(R.string.save)) } },
        dismissButton = { PrismTextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * Sharing a Look: its QR code to scan, its code as text, or a .prismlook file. Codes leave out photos and your own font;
 * a file can carry them, if you tick them.
 */
@Composable
private fun ShareLookSheet(look: Look, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = remember(look) { c.looks.code(look) }
    val hasPhotos = look.photoAssets.isNotEmpty()
    val hasFont = look.font != null
    var withPhotos by remember { mutableStateOf(hasPhotos) }
    var withFont by remember { mutableStateOf(false) }
    ActionSheet(null, onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.share_2, look.name), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(12.dp))
            if (code.length <= LookCode.MAX_QR_CHARS) {
                Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp)) {
                    QrCode(code, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.scan_it_in_prism_settings_customize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else Text(tr(R.string.too_much_for_a_qr_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasPhotos || hasFont) Text(
                tr(R.string.codes_leave_out_photos_and_your),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismButton({
                    val text = tr(R.string.here_s_my_prism_look_share, look.name, code)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr(R.string.send, look.name)))
                }) { Text(tr(R.string.send_as_text)) }
                PrismOutlinedButton({
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                val name = look.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { tr(R.string.look) }
                                val file = File(context.cacheDir, "share").apply { mkdirs() }.resolve("$name.${LookStore.FILE_EXTENSION}")
                                file.outputStream().use { c.looks.writeFile(look, it, withPhotos, withFont) }
                                file
                            }
                        }
                        r.onSuccess { file ->
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            send.clipData = android.content.ClipData.newRawUri(null, uri)
                            context.startActivity(Intent.createChooser(send, tr(R.string.send, look.name)))
                        }.onFailure { Toast.makeText(context, it.message ?: tr(R.string.couldn_t_make_the_file), Toast.LENGTH_LONG).show() }
                    }
                }) { Text(tr(R.string.send_as_a_file)) }
            }
        }
        if (hasPhotos || hasFont) Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (hasPhotos) Toggle(tr(R.string.include_its_photos), tr(R.string.your_background_photos_go_in_the), withPhotos) { withPhotos = it }
            if (hasFont) Toggle(tr(R.string.include_its_font), tr(R.string.only_if_the_font_s_license), withFont) { withFont = it }
        }
    }
}

/** A Look someone shared, shown before anything changes: try it, keep it, or not. */
@Composable
private fun PendingLookDialog(look: Look) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember(look) { c.looks.preview(look) }
    val changes = remember(look) { c.looks.changes(look) }
    fun done() { c.looks.pending.value = null }
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text(look.name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LookPhones(s, 72.dp, gap = 8.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    (changes?.let { "$it. " } ?: tr(R.string.it_looks_just_like_yours_now)) + tr(R.string.a_look_only_changes_how_prism),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                PrismTextButton({
                    done()
                    scope.launch { c.looks.keep(look); Toast.makeText(context, tr(R.string.saved_to_your_looks), Toast.LENGTH_SHORT).show() }
                }) { Text(tr(R.string.save)) }
                PrismTextButton({ done(); scope.launch { c.looks.apply(look) } }) { Text(tr(R.string.use_it)) }
            }
        },
        dismissButton = { PrismTextButton(::done) { Text(tr(R.string.not_now)) } },
    )
}

/**
 * The season's Look, offered in its month (1.8.0): a quiet card with the Look on two small phones, what it is, and Use it;
 * the ⓘ says it's only for the month and what happens when it ends.
 */
@Composable
private fun SeasonCard(look: Look, onUse: () -> Unit) {
    val c = LocalContainer.current
    val app = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val s = remember(look, app) { c.looks.preview(look) }
    val song by c.player.currentSong.collectAsState()
    var info by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(ui.tile).background(frostFill(1.4f))
            .border(1.dp, scheme.onSurface.copy(alpha = 0.08f), ui.tile).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewPhone(56.dp) { LookTheme(s, com.prism.music.ui.theme.isAppDark(s)) { colors -> MiniHome(colors, song) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.for_the_season, look.name), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                InfoMark(info, look.name) { info = !info }
            }
            Text(look.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            InfoText(info, tr(R.string.season_info))
            PrismOutlinedButton(onUse) { Text(tr(R.string.use_it)) }
        }
    }
}

/**
 * Holiday Mode (1.8.0): asked once a season, the first time Prism opens in its month ([com.prism.music.data.looks.SeasonPrompt]).
 * The season's background, small and moving, what changes, and that it ends with the month. [onAnswer]: true to turn it
 * on; false for No thanks or closing it (asked either way).
 */
@Composable
fun HolidayModeDialog(season: com.prism.music.data.looks.Season, onAnswer: (Boolean) -> Unit) {
    val s = LocalAppSettings.current
    val style = com.prism.music.ui.theme.Backdrop.entries.first { it.name == season.backdrop }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(tr(R.string.holiday_mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))) {
                    com.prism.music.ui.theme.BackdropLayer(style, null, 0f, s.backdropMotion && !s.reduceMotion, Modifier.fillMaxSize())
                }
                Text(
                    tr(if (season == com.prism.music.data.looks.Season.HARVEST) R.string.holiday_mode_harvest else R.string.holiday_mode_holiday),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { PrismTextButton({ onAnswer(true) }) { Text(tr(R.string.turn_on)) } },
        dismissButton = { PrismTextButton({ onAnswer(false) }) { Text(tr(R.string.no_thanks)) } },
    )
}

/** A Look by day and another by night, switched at sunrise and sunset or at set times. */
@Composable
private fun ScheduleGroup() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val sch by c.looks.schedule.collectAsState()
    val mine by c.looks.mine.collectAsState()
    val all = BuiltInLooks.all + mine
    fun set(s: LookSchedule) = c.looks.setSchedule(s)
    fun time(minutes: Int): String = android.text.format.DateFormat.getTimeFormat(context)
        .format(java.util.Date(java.time.LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    fun pickTime(at: Int, done: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> done(h * 60 + m) }, at / 60, at % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    Group(tr(R.string.day_and_night)) {
        Toggle(
            tr(R.string.day_and_night_looks), tr(R.string.one_look_by_day_another_by), sch.on,
            info = tr(R.string.like_theme_system_for_whole_looks),
        ) { set(sch.copy(on = it)) }
        if (sch.on) {
            Label(tr(R.string.by_day))
            Chips(all.map { it.id }, sch.day, { id -> all.firstOrNull { it.id == id }?.name ?: id }) { set(sch.copy(day = it)) }
            Label(tr(R.string.by_night))
            Chips(all.map { it.id }, sch.night, { id -> all.firstOrNull { it.id == id }?.name ?: id }) { set(sch.copy(night = it)) }
            Label(tr(R.string.switch_text))
            Chips(listOf(true, false), sch.sun, { if (it) tr(R.string.sunrise_sunset) else tr(R.string.set_times) }) { set(sch.copy(sun = it)) }
            if (!sch.sun) {
                Item(tr(R.string.day_starts_at), time(sch.dayAt), onClick = { pickTime(sch.dayAt) { set(sch.copy(dayAt = it)) } })
                Item(tr(R.string.night_starts_at), time(sch.nightAt), onClick = { pickTime(sch.nightAt) { set(sch.copy(nightAt = it)) } })
            }
            val next = remember(sch) { c.looks.nextTurn(sch) }
            val nextLook = all.firstOrNull { it.id == (if (next.to == SunClock.Part.DAY) sch.day else sch.night) }?.name ?: ""
            val at = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(next.at.toEpochMilli()))
            Note(tr(R.string.next_at, nextLook, at))
        }
    }
}

/** The last changes to the look, newest first: tap one to go back to before it. */
@Composable
private fun HistoryGroup() {
    val c = LocalContainer.current
    val steps by c.looks.steps.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    Group(tr(R.string.history)) {
        if (steps.isEmpty()) Note(tr(R.string.your_last_changes_to_the_look, LookStore.MAX_STEPS))
        steps.forEachIndexed { i, step ->
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(step.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            Item(
                step.label, "$ago · ${if (i == 0) "tap to undo" else "tap to go back to before this"}",
                icon = PrismIcons.Undo, onClick = { c.looks.goBack(i) },
            )
        }
        // #152: the list can go; the look itself stays as it is.
        if (steps.isNotEmpty()) Item(tr(R.string.clear_history), tr(R.string.your_look_stays_as_it_is), icon = PrismIcons.Delete, onClick = { clearing = true })
    }
    if (clearing) androidx.compose.material3.AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text(tr(R.string.clear_the_look_s_history)) },
        text = { Text(tr(R.string.you_won_t_be_able_to)) },
        confirmButton = { com.prism.music.ui.components.PrismTextButton({ c.looks.clearHistory(); clearing = false }) { Text(tr(R.string.clear)) } },
        dismissButton = { com.prism.music.ui.components.PrismTextButton({ clearing = false }) { Text(tr(R.string.cancel)) } },
    )
}

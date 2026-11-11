package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.platform.testTag
import com.prism.music.data.model.quickPicks
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.HomeShortcut
import com.prism.music.data.prefs.PinnedPlaylist
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

private fun SectionStyle.icon(): ImageVector = when (this) {
    SectionStyle.CAROUSEL -> PrismIcons.Carousel
    SectionStyle.GRID, SectionStyle.DIAL -> PrismIcons.Customize
    SectionStyle.LIST -> PrismIcons.Queue
    SectionStyle.HERO -> PrismIcons.Rows
}

/**
 * Customize Home: the greeting, the sections Home shows (in order — hold and drag to move — each
 * with a look), the shortcut tiles, and a couple of options. Everything less common sits behind a row's ⋮ menu or a sheet.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CatalogueScreen(bottomPadding: Dp) {
    StoredSettings { CataloguePage(bottomPadding) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CataloguePage(bottomPadding: Dp) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val ytShelves by homeShelfCache.collectAsState()
    val layout = settings.homeLayout
    // Every section Prism can show — built-in and every shelf YouTube Music has offered — that isn't on Home yet.
    val picks = ytShelves.quickPicks(settings.accountName)
    val available = (HomeSections.defaults + ytShelves.filterNot { it === picks }.map { HomeSectionConfig(shelfKey(it.title), it.title) })
        .filter { cfg -> layout.none { it.key == cfg.key } }
        .distinctBy { it.key }
    var adding by remember { mutableStateOf(false) }
    var pinning by remember { mutableStateOf(false) }
    // The tiles only show while their section is on Home.
    val tilesOn = layout.any { it.key == HomeSections.GREETING && it.visible }

    fun save(list: List<HomeSectionConfig>) = c.settings.setHomeLayout(list)

    SubPage(
        tr(R.string.customize_home), bottomPadding,
        horizontalPadding = 16.dp,
        spacing = 22.dp,
        actions = { RoundAction(PrismIcons.History, tr(R.string.reset), glass = true) { save(HomeSections.initial) } },
    ) {
        item(key = "greeting") {
            Group(tr(R.string.top_of_home)) {
                // 1.7.6: the greeting as before, just the buttons, or the greeting over a picture.
                ChoiceCards(
                    com.prism.music.data.prefs.HomeHeader.entries, settings.homeHeader, title = { it.label }, detail = { it.note },
                    onSelect = { c.settings.setHomeHeader(it) },
                ) { HeaderSample(it) }
                if (settings.homeHeader != com.prism.music.data.prefs.HomeHeader.NONE) {
                    GreetingField()
                    Toggle(tr(R.string.show_your_name), null, settings.greetingShowName) { c.settings.setGreetingShowName(it) }
                }
                if (settings.homeHeader == com.prism.music.data.prefs.HomeHeader.PICTURE || settings.homeHeader == com.prism.music.data.prefs.HomeHeader.NOW_PLAYING) {
                    Label(tr(R.string.height))
                    Chips(com.prism.music.data.prefs.HomeHeaderHeight.entries, settings.homeHeaderHeight, { it.label }) { c.settings.setHomeHeaderHeight(it) }
                }
                if (settings.homeHeader == com.prism.music.data.prefs.HomeHeader.PICTURE) HeaderPicturePicker()
            }
        }
        item(key = "sections") {
            Group(tr(R.string.sections), info = tr(R.string.hold_a_section_or_drag_its)) {
                com.prism.music.ui.components.ReorderList(layout, { it.key }, { save(it) }) { cfg, i, held, mod ->
                    Column(mod) {
                        if (i > 0 && !held) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SectionRow(
                            cfg,
                            canUp = i > 0,
                            canDown = i < layout.lastIndex,
                            onMove = { by -> save(layout.toMutableList().apply { add(i + by, removeAt(i)) }) },
                            onChange = { new -> save(layout.map { if (it.key == cfg.key) new else it }) },
                            onRemove = { save(layout.filterNot { it.key == cfg.key }) },
                            held = held,
                            grip = Modifier.dragHandle(),
                        )
                    }
                }
                if (layout.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PrismIcons.Add, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text(tr(R.string.add_a_section), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
        item(key = "tiles") {
            // Greyed out (still editable) while the Shortcut tiles section is hidden or removed.
            Column(Modifier.alpha(if (tilesOn) 1f else 0.45f)) { Group(tr(R.string.section_shortcut_tiles), info = tr(R.string.shortcuts_appear_in_the_order_you)) {
                // Why they're greyed out: a state, so it stays in view.
                if (!tilesOn) Note(tr(R.string.show_the_section_to_see_these, tr(R.string.section_shortcut_tiles)))
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeShortcut.entries.forEach { sc ->
                        val on = sc in settings.homeShortcuts
                        PrismChip(
                            on, { c.settings.setHomeShortcuts(if (on) settings.homeShortcuts - sc else settings.homeShortcuts + sc) },
                            if (on) "${settings.homeShortcuts.indexOf(sc) + 1}  ${sc.label}" else sc.label,
                        )
                    }
                }
                Item(
                    tr(R.string.pinned_playlists),
                    settings.homePlaylists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.title } ?: tr(R.string.none),
                    onClick = { pinning = true },
                )
                Item(tr(R.string.recently_played_albums), null, onClick = { c.settings.setHomeRecentTiles((settings.homeRecentTiles + 1) % 7) }) {
                    Stepper(settings.homeRecentTiles, 0..6) { c.settings.setHomeRecentTiles(it) }
                }
            } }
        }
        item(key = "options") {
            Group(tr(R.string.options)) {
                Toggle(tr(R.string.add_new_sections_automatically), tr(R.string.new_shelves_from_youtube_music_appear), settings.autoAddSections) { c.settings.setAutoAddSections(it) }
                // YouTube Music's own sections of a kind, left off Home (1.7.6).
                Toggle(
                    tr(R.string.leave_off_music_video_sections), tr(R.string.youtube_music_s_sections_that_are), HomeSections.KIND_VIDEOS in settings.homeHideKinds,
                ) { c.settings.setHomeHideKind(HomeSections.KIND_VIDEOS, it) }
                Toggle(
                    tr(R.string.leave_off_podcast_sections), tr(R.string.youtube_music_s_sections_of_shows), HomeSections.KIND_PODCASTS in settings.homeHideKinds,
                ) { c.settings.setHomeHideKind(HomeSections.KIND_PODCASTS, it) }
                // #349: Prism's, made around your artists, or YouTube Music's own as they come.
                Label(tr(R.string.quick_picks_from), info = tr(R.string.quick_picks_from_info))
                Chips(com.prism.music.data.prefs.QuickPicksSource.entries, settings.behavior.quickPicksSource, { it.label }) { c.settings.setQuickPicksSource(it) }
                Toggle(
                    tr(R.string.quick_picks_start_a_radio),
                    if (settings.quickPicksRadio) tr(R.string.tapping_a_pick_plays_it_then) else tr(R.string.tapping_a_pick_plays_quick_picks),
                    settings.quickPicksRadio,
                ) { c.settings.setQuickPicksRadio(it) }
                Toggle(
                    tr(R.string.speed_dial_youtube_music_s_only), tr(R.string.without_the_albums_and_songs_prism), settings.speedDialYouTubeOnly,
                    info = tr(R.string.speed_dial_starts_with_youtube_music) + " " + tr(R.string.speed_dial_only_short_page),
                ) { c.settings.setSpeedDialYouTubeOnly(it) }
                // #372: tiles that change as you listen. Under "YouTube Music's only" there are none of yours to put first.
                if (!settings.speedDialYouTubeOnly) Toggle(
                    tr(R.string.speed_dial_your_plays_first), tr(R.string.speed_dial_your_plays_first_sub), settings.behavior.speedDialYoursFirst,
                    info = tr(R.string.speed_dial_your_plays_first_info),
                ) { c.settings.setSpeedDialYoursFirst(it) }
                Toggle(tr(R.string.customize_button_on_home), null, settings.homeCustomizeButton) { c.settings.setHomeCustomizeButton(it) }
            }
        }
    }

    if (adding) AddSectionSheet(available, ytShelves.isEmpty(), { adding = false }) { cfg ->
        // Speed dial goes in at the top, where YouTube Music has it (#53); everything else at the bottom. Either can be dragged.
        save(if (cfg.key == HomeSections.SPEED_DIAL) listOf(cfg.copy(visible = true)) + layout else layout + cfg.copy(visible = true))
    }
    if (pinning) PinPlaylistsSheet { pinning = false }
}

/** One section on Home: tap to show or hide it; ⋮ moves, restyles or removes it. */
@Composable
private fun SectionRow(
    cfg: HomeSectionConfig,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onChange: (HomeSectionConfig) -> Unit,
    onRemove: () -> Unit,
    held: Boolean = false,
    modifier: Modifier = Modifier,
    /** Dragging the grip moves the section at once (the row itself after a hold). */
    grip: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (renaming) SectionNameDialog(cfg, { renaming = false }) { onChange(cfg.copy(name = it)); renaming = false }
    val fg = if (cfg.visible) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        // No tap while held, so letting go of a drag doesn't also hide the section.
        modifier.fillMaxWidth().clickable(enabled = !held, onClickLabel = if (cfg.visible) tr(R.string.hide) else tr(R.string.show)) { onChange(cfg.copy(visible = !cfg.visible)) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (cfg.visible) cfg.style.icon() else PrismIcons.Hidden, null, Modifier.size(22.dp), tint = if (cfg.visible) scheme.primary else fg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(cfg.titled(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (!cfg.visible) tr(R.string.hidden) else if (cfg.tile) tr(R.string.one_tile) else cfg.style.label,
                    cfg.title.takeIf { cfg.name != null }?.let { tr(R.string.was, it) },
                    tr(R.string.pinned).takeIf { cfg.key.startsWith(HomeSections.PIN_PREFIX) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        com.prism.music.ui.components.DragGrip(grip, tr(R.string.drag, cfg.titled()))
        IconButton(onClick = { menu = true }) {
            Icon(PrismIcons.MoreVertical, tr(R.string.options_for, cfg.title), tint = scheme.onSurfaceVariant)
            DropdownMenu(menu, { menu = false }) {
                SectionStyle.entries.forEach { st ->
                    DropdownMenuItem(
                        { Text(st.label) }, { onChange(cfg.copy(style = st, visible = true, tile = false)); menu = false },
                        leadingIcon = { Icon(st.icon(), null) },
                        trailingIcon = { if (st == cfg.style && !cfg.tile) Icon(PrismIcons.Check, null) },
                    )
                }
                // A pinned album or playlist as one tile (1.8.3, D48).
                if (OwnSections.canTile(cfg.key)) DropdownMenuItem(
                    { Text(tr(R.string.one_tile)) }, { onChange(cfg.copy(tile = true, visible = true)); menu = false },
                    leadingIcon = { Icon(PrismIcons.Album, null) },
                    trailingIcon = { if (cfg.tile) Icon(PrismIcons.Check, null) },
                    modifier = Modifier.testTag("oneTile"),
                )
                HorizontalDivider()
                DropdownMenuItem({ Text(tr(R.string.rename)) }, { renaming = true; menu = false }, leadingIcon = { Icon(PrismIcons.Edit, null) })
                if (canUp) DropdownMenuItem({ Text(tr(R.string.move_up)) }, { onMove(-1); menu = false }, leadingIcon = { Icon(PrismIcons.ChevronUp, null) })
                if (canDown) DropdownMenuItem({ Text(tr(R.string.move_down)) }, { onMove(1); menu = false }, leadingIcon = { Icon(PrismIcons.ChevronDown, null) })
                DropdownMenuItem(
                    { Text(if (cfg.visible) tr(R.string.hide) else tr(R.string.show)) }, { onChange(cfg.copy(visible = !cfg.visible)); menu = false },
                    leadingIcon = { Icon(if (cfg.visible) PrismIcons.Hidden else PrismIcons.Visible, null) },
                )
                DropdownMenuItem({ Text(tr(R.string.remove)) }, { onRemove(); menu = false }, leadingIcon = { Icon(PrismIcons.Delete, null) })
            }
        }
    }
}

/** A small example of each kind of top of Home: the picture (if any), the greeting as bars, the round buttons. */
@Composable
private fun HeaderSample(kind: com.prism.music.data.prefs.HomeHeader) {
    val scheme = MaterialTheme.colorScheme
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    val ground = when (kind) {
        com.prism.music.data.prefs.HomeHeader.PICTURE -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFA6A6A6), Color(0xFF787878), Color(0xFF8F8F8F)))
        com.prism.music.data.prefs.HomeHeader.NOW_PLAYING -> androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF8F8F8F), Color(0xFF3E3E3E)))
        else -> androidx.compose.ui.graphics.SolidColor(scheme.surfaceContainerHigh)
    }
    Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val picture = kind == com.prism.music.data.prefs.HomeHeader.PICTURE || kind == com.prism.music.data.prefs.HomeHeader.NOW_PLAYING
            if (picture) drawRect(ground, alpha = 0.9f)
            if (picture) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to Color.Transparent, 1f to scheme.surfaceContainerHigh))
            val ink = if (dark || picture) Color.White else scheme.onSurface
            // The round buttons, top right.
            for (i in 0..2) drawCircle(ink.copy(alpha = 0.55f), 5.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width - (12 + i * 14).dp.toPx(), 14.dp.toPx()))
            if (kind != com.prism.music.data.prefs.HomeHeader.NONE) {
                val y = size.height - 22.dp.toPx()
                drawRoundRect(ink.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(10.dp.toPx(), y - 9.dp.toPx()), androidx.compose.ui.geometry.Size(size.width * 0.25f, 3.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawRoundRect(ink.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(10.dp.toPx(), y), androidx.compose.ui.geometry.Size(size.width * 0.55f, 7.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
    }
}

/** The picture behind the greeting: one of the background designs, or a photo of your own. */
@Composable
private fun HeaderPicturePicker() {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { savePhoto(context, uri, "home-header") }
            if (file != null) c.settings.setHomeHeaderPicture(com.prism.music.ui.theme.Backdrop.photoValue(file.absolutePath))
        }
    }
    val current = com.prism.music.ui.theme.Backdrop.parse(settings.homeHeaderPicture).first
    val designs = com.prism.music.ui.theme.Backdrop.entries.filter {
        (it.group == com.prism.music.ui.theme.BackdropGroup.COLOR || it.group == com.prism.music.ui.theme.BackdropGroup.DESIGNS) &&
            (it.offeredOn(com.prism.music.data.looks.Seasons.today()) || it == current)
    }
    Label(tr(R.string.picture))
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrismChip(current == com.prism.music.ui.theme.Backdrop.PHOTO, {
            picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, tr(R.string.your_photo), icon = PrismIcons.Photo)
        designs.forEach { d -> PrismChip(current == d, { c.settings.setHomeHeaderPicture(d.name) }, d.label) }
    }
}

/** A section's own name on Home (1.7.6); blank goes back to its own. */
@Composable
private fun SectionNameDialog(cfg: HomeSectionConfig, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(cfg.name ?: cfg.title) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.rename_section)) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                text, { text = it.replace('\n', ' ').take(40) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(cfg.title) },
            )
        },
        confirmButton = {
            com.prism.music.ui.components.PrismTextButton(onClick = { onSave(text.trim().takeIf { it.isNotEmpty() && it != cfg.title }) }) { Text(tr(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (cfg.name != null) com.prism.music.ui.components.PrismTextButton(onClick = { onSave(null) }) { Text(tr(R.string.use_2, cfg.title)) }
                com.prism.music.ui.components.PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
            }
        },
    )
}

/** The headline, edited in place; empty follows the time of day. */
@Composable
private fun GreetingField() {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    var text by remember { mutableStateOf(settings.greetingText) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(tr(R.string.headline), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        BasicTextField(
            text, { text = it; c.settings.setGreetingText(it) },
            Modifier.fillMaxWidth().padding(top = 2.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(tr(R.string.good_morning_afternoon_evening), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant.copy(alpha = 0.6f))
                inner()
            },
        )
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(36.dp)) { Icon(PrismIcons.Remove, tr(R.string.fewer)) }
        Text(if (value == 0) tr(R.string.off) else "$value", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(36.dp)) { Icon(PrismIcons.Add, tr(R.string.more)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSectionSheet(available: List<HomeSectionConfig>, notLoaded: Boolean, onDismiss: () -> Unit, onAdd: (HomeSectionConfig) -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    // Sections from your own listening (1.7.6), not on Home yet.
    val rules by androidx.compose.runtime.produceState(emptyList<HomeSectionConfig>()) {
        value = OwnSections.rules(c).filter { r -> settings.homeLayout.none { it.key == r.key } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(tr(R.string.add_a_section), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        val (prism, youtube) = available.partition { !it.key.startsWith(HomeSections.YT_PREFIX) }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item { SheetLabel(tr(R.string.your_own)) }
            items(rules, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
            item {
                Text(
                    tr(R.string.to_pin_an_album_playlist_artist),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (prism.isNotEmpty()) item { SheetLabel(tr(R.string.from_prism)) }
            items(prism, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
            item { SheetLabel(tr(R.string.from_youtube_music)) }
            if (youtube.isEmpty()) item {
                Text(
                    if (notLoaded) tr(R.string.open_home_once_to_load_youtube) else tr(R.string.everything_s_already_on_home),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(youtube, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinPlaylistsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val library by c.library.playlists.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    // Prism's own playlists (kept on the phone) can be pinned too, as well as the library's.
    val mine = remember(prismLists) { prismLists.map { it.item() } }
    val playlists = remember(library, mine) { mine + library.filterIsInstance<PlaylistItem>().filterNot { l -> mine.any { it.id == l.id } } }
    // Pinned ones first (kept even if they've left the library), then Prism's playlists, then the rest of the library.
    val pinnedIds = settings.homePlaylists.map { it.id }
    val pinned = settings.homePlaylists.map { it.id to it.title }
    val prismOptions = mine.filter { it.id !in pinnedIds }.map { it.id to it.title }
    val youtubeOptions = playlists.filter { it.id !in pinnedIds && mine.none { m -> m.id == it.id } }.map { it.id to it.title }
    val options = pinned + prismOptions + youtubeOptions
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(tr(R.string.pinned_playlists), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            tr(R.string.shown_as_tiles_after_the_shortcuts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (options.isEmpty()) item {
                Text(
                    tr(R.string.your_playlists_show_up_here_once_2), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            fun row(scope: androidx.compose.foundation.lazy.LazyListScope, list: List<Pair<String, String>>) = scope.items(list, key = { it.first }) { (id, title) ->
                val on = id in pinnedIds
                SheetRow(title, checked = on) {
                    c.settings.setHomePlaylists(
                        if (on) settings.homePlaylists.filterNot { it.id == id }
                        else settings.homePlaylists + playlists.first { it.id == id }.let { PinnedPlaylist(it.id, it.title, it.thumbnail) }
                    )
                }
            }
            if (pinned.isNotEmpty()) { item { SheetLabel(tr(R.string.pinned)) }; row(this, pinned) }
            if (prismOptions.isNotEmpty()) { item { SheetLabel(tr(R.string.prism_playlists)) }; row(this, prismOptions) }
            if (youtubeOptions.isNotEmpty()) { item { SheetLabel(tr(R.string.youtube_music_playlists)) }; row(this, youtubeOptions) }
        }
    }
}

@Composable
private fun SheetLabel(text: String) = com.prism.music.ui.components.Eyebrow(text, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun SheetRow(title: String, checked: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (checked) {
            null -> Icon(PrismIcons.Add, tr(R.string.add), tint = MaterialTheme.colorScheme.primary)
            true -> Icon(PrismIcons.Check, tr(R.string.pinned), tint = MaterialTheme.colorScheme.primary)
            false -> {}
        }
    }
}

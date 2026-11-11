package com.prism.music.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.data.model.Shelf
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/** One row of Home in the editor: its name and whether it shows. */
data class TvHomeEditorRow(val key: String, val title: String, val shown: Boolean)

/**
 * Customize Home (TV, 1.8.0): Home's rows top to bottom, as they'll be. The D-pad moves between them; OK shows or hides
 * one; Move beside it (or OK held) picks it up, Up and Down carry it, OK (or Back) puts it down. "Back to default" is
 * Home as it comes. Each change is kept at once.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TvHomeEditorScreen(
    rows: List<TvHomeEditorRow>,
    moving: String?,
    onToggle: (String) -> Unit,
    onPickUp: (String) -> Unit,
    onStep: (String, Int) -> Unit,
    onPutDown: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    /** The row being carried, as the page keeps it: read when a key comes, so a key before the next frame still finds it. */
    movingState: androidx.compose.runtime.State<String?>? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    // The row focus opens on: the first as the page opened (not whichever is first after a move, which would take
    // focus from the row being carried).
    val firstKey = remember { rows.firstOrNull()?.key }
    // What the rows' keys act on is read when the key comes, not when the row was last drawn: on a slow TV a key can
    // come before a row is drawn again (the TV suite: Up on a row just picked up still moved focus, the row "not moving").
    val movingUpdated = rememberUpdatedState(moving)
    val movingNow = movingState ?: movingUpdated
    val stepNow = rememberUpdatedState(onStep)
    val pickUpNow = rememberUpdatedState(onPickUp)
    val putDownNow = rememberUpdatedState(onPutDown)
    val toggleNow = rememberUpdatedState(onToggle)
    // Focus coming into the page (as it opens, or handed on by Android when the page under it went) lands on the list's
    // first row, not on "Back to default" above it: the list is what the page is for.
    val firstRow = remember { FocusRequester() }
    // Until the remote is first pressed here: if focus was put on "Back to default" anyway (Android hands focus to the
    // page's top item when the page under it goes), it goes to the first row.
    var touched by remember { mutableStateOf(false) }
    var resetFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(ENTRY_CHECKS) {
            kotlinx.coroutines.delay(ENTRY_CHECK_MS)
            if (!touched && resetFocused) runCatching { firstRow.requestFocus() }
        }
    }
    Column(
        modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp).testTag(TAG_HOME_EDITOR)
            .focusProperties { onEnter = { firstRow.requestFocus() } }.focusGroup()
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) touched = true; false },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.customize_home), style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
                Spacer(Modifier.height(6.dp))
                val moved = rows.firstOrNull { it.key == moving }
                Text(
                    if (moved != null) tr(R.string.tv_home_moving, moved.title) else tr(R.string.tv_home_editor_how),
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("$TAG_HOME_EDITOR/how"),
                )
            }
            Spacer(Modifier.width(24.dp))
            TvButton(tr(R.string.tv_home_back_to_default), onReset, Modifier.onFocusChanged { resetFocused = it.isFocused }.tvItem("$TAG_HOME_EDITOR/reset"), icon = PrismIcons.Undo)
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().rememberedFocus().testTag("$TAG_HOME_EDITOR/list"),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 27.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows, key = { it.key }) { r ->
                val isMoving = r.key == movingNow.value
                val bring = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                // A row being carried keeps focus where it was picked up (the row, or its Move button) as it goes from
                // place to place, and stays in view past the list's edge.
                val rowFocus = remember { FocusRequester() }
                val buttonFocus = remember { FocusRequester() }
                var onButton by remember { mutableStateOf(false) }
                var focused by remember { mutableStateOf(false) }
                LaunchedEffect(isMoving, rows.indexOf(r)) {
                    if (!isMoving) return@LaunchedEffect
                    runCatching { bring.bringIntoView() }
                    if (!focused) runCatching { (if (onButton) buttonFocus else rowFocus).requestFocus() }
                }
                Row(
                    Modifier.fillMaxWidth().bringIntoViewRequester(bring).onPreviewKeyEvent { e ->
                        val carried = r.key == movingNow.value
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown)) android.util.Log.d(EDITOR_LOG, "key ${e.key} on ${r.key} moving=$carried")
                        if (!carried || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> { stepNow.value(r.key, -1); scope.launch { runCatching { bring.bringIntoView() } }; true }
                            Key.DirectionDown -> { stepNow.value(r.key, 1); scope.launch { runCatching { bring.bringIntoView() } }; true }
                            // Carried, it stays itself: Left and Right don't wander off to the other button.
                            Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val click = { if (r.key == movingNow.value) putDownNow.value() else toggleNow.value(r.key) }
                    val hold = { if (r.key == movingNow.value) putDownNow.value() else pickUpNow.value(r.key) }
                    var held by remember { mutableStateOf(false) }
                    // A key-up whose key-down this row never had (OK let go on the page that opened the editor) is no press.
                    var pressed by remember { mutableStateOf(false) }
                    ListItem(
                        selected = isMoving,
                        onClick = click,
                        onLongClick = hold,
                        // OK pressed or held is read here, by how long the key has been down (a remote's repeats, or one
                        // key-down and its key-up apart), so a hold never also counts as a press when it's let go.
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                            val n = e.nativeKeyEvent
                            val long = n.eventTime - n.downTime >= HOLD_MS
                            when (e.type) {
                                KeyEventType.KeyDown -> { pressed = true; if (long && !held) { held = true; hold() } }
                                KeyEventType.KeyUp -> when {
                                    !pressed -> Unit
                                    held -> { held = false; pressed = false }
                                    long -> { pressed = false; hold() }
                                    else -> { pressed = false; click() }
                                }
                            }
                            true
                        }.then(if (r.key == rows.firstOrNull()?.key) Modifier.focusRequester(firstRow) else Modifier)
                            .focusRequester(rowFocus).onFocusChanged { if (it.isFocused) { onButton = false; focused = true } else if (!onButton) focused = false }
                            .tvItem("$TAG_HOME_EDITOR/row/${r.key}", entry = r.key == firstKey),
                        leadingContent = { Icon(if (isMoving) PrismIcons.SwapVertical else if (r.shown) PrismIcons.Visible else PrismIcons.Hidden, null, Modifier.size(22.dp)) },
                        headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(tr(if (r.shown) R.string.shown else R.string.hidden), maxLines = 1) },
                        trailingContent = { Switch(checked = r.shown, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent, selectedContainerColor = TvGlassFill, selectedContentColor = scheme.onSurface,
                            focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface,
                            focusedSelectedContainerColor = scheme.onSurface, focusedSelectedContentColor = scheme.surface,
                        ),
                        // As the settings' rows: Prism's soft corners, the split light round the focused one.
                        shape = ListItemDefaults.shape(TvShapes.card),
                        border = ListItemDefaults.border(focusedBorder = tvFocusBorder(TvShapes.card, TV_RING_THIN)),
                    )
                    Spacer(Modifier.width(8.dp))
                    TvIconButton(
                        if (isMoving) PrismIcons.Check else PrismIcons.SwapVertical,
                        if (isMoving) tr(R.string.done) else tr(R.string.tv_home_move, r.title),
                        hold,
                        Modifier.focusRequester(buttonFocus).onFocusChanged { if (it.isFocused) { onButton = true; focused = true } else if (onButton) focused = false }
                            .tvItem("$TAG_HOME_EDITOR/move/${r.key}"),
                        on = isMoving,
                    )
                }
            }
        }
    }
}

/** Customize Home's page: the TV's layout ([com.prism.music.data.prefs.TvPrefs.homeLayout]) and the phone's ([TvHomeLayout]'s file). */
@Composable
fun TvHomeEditorPage(nav: TvNav) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val account = settings.accountEmail.ifBlank { settings.accountName }.takeIf { settings.isLoggedIn }
    val owner = settings.accountName.takeIf { it.isNotBlank() }
    // YouTube's shelves on Home now (this run's, else the copy kept on the TV): the rows there are to lay out.
    val shelves by produceState(TvHomeShelves.of(account, System.currentTimeMillis()).first, account) {
        if (value.isEmpty()) c.homeCache.read()?.let { value = it }
    }
    val titles = TvHomeTitles(tr(R.string.quick_picks), tr(R.string.section_speed_dial), tr(R.string.recently_played))
    // Every row the TV can have (empty ones too, so they can be turned on before they have anything in them).
    val natural = remember(shelves, owner, settings.homeLayout) { tvHomeAvailable(shelves, owner, settings.homeLayout, titles) }
    // The editor's own copies while it's open (the TV's layout: order and the hero; the phone's: which rows show), so
    // quick presses never act on a layout still being written.
    var localTv by remember { mutableStateOf<List<HomeSectionConfig>?>(null) }
    var localPhone by remember { mutableStateOf<List<HomeSectionConfig>?>(null) }
    val tvLayout = localTv ?: settings.tv.homeLayout
    val phoneLayout = localPhone ?: settings.homeLayout
    val layout = TvHomeLayout.editable(tvLayout, natural, phoneLayout, settings.autoAddSections)
    val movingState = remember { mutableStateOf<String?>(null) }
    var moving by movingState
    // Rows turned on while the editor is open (Home hasn't shown them yet).
    val turnedOn = remember { mutableSetOf<String>() }
    fun keepTv(next: List<HomeSectionConfig>) { localTv = TvHomeLayout.saved(next); c.settings.setTvHomeLayout(localTv!!) }
    fun keepPhone(next: List<HomeSectionConfig>) { localPhone = next; c.settings.setHomeLayout(next) }
    BackHandler(enabled = moving != null) { moving = null }
    TvHomeEditorScreen(
        layout.map { TvHomeEditorRow(it.key, it.title, it.visible) },
        moving,
        onToggle = { key ->
            // As it is now, not as last drawn (see onStep).
            val phoneNow = localPhone ?: settings.homeLayout
            val now = TvHomeLayout.editable(localTv ?: settings.tv.homeLayout, natural, phoneNow, settings.autoAddSections)
            val row = now.first { it.key == key }
            // The hero is the TV's own; the other rows are shown or hidden in the phone's Home layout too.
            if (key == TvHomeLayout.HERO) { if (!row.visible) turnedOn += key; keepTv(TvHomeLayout.toggled(now, key)) }
            else {
                if (!row.visible) turnedOn += key else turnedOn -= key
                keepPhone(layoutWithShown(phoneNow, key, row.title, !row.visible))
                // The order as it is now is the TV's from here on (the phone's layout may name the row in another place).
                keepTv(now)
            }
        },
        onPickUp = { android.util.Log.d(EDITOR_LOG, "pick up $it"); moving = it },
        // Each press carries the row past one row Home shows: rows hidden, empty or out of season now are passed on the way
        // (what Home showed last, plus rows turned on here; before Home has shown, every row turned on counts).
        onStep = { key, by ->
            // The layout as it is now (the last press may not be drawn yet on a slow TV), not as it was last drawn.
            val now = TvHomeLayout.editable(localTv ?: settings.tv.homeLayout, natural, localPhone ?: settings.homeLayout, settings.autoAddSections)
            val home = TvHomeOnHome.keys
            val visible = now.filter { it.visible }.map { it.key }.toSet()
            val next = TvHomeLayout.movedPast(now, key, by) { k -> k in visible && (home.isEmpty() || k in home || k in turnedOn) }
            android.util.Log.d(EDITOR_LOG, "step $key by $by: ${now.indexOfFirst { it.key == key }} -> ${next.indexOfFirst { it.key == key }}")
            keepTv(next)
        },
        onPutDown = { android.util.Log.d(EDITOR_LOG, "put down $moving"); moving = null },
        movingState = movingState,
        onReset = {
            moving = null
            localTv = emptyList()
            c.settings.setTvHomeLayout(emptyList())
            keepPhone(TvHomeLayout.allShown(phoneLayout))
        },
    )
}

/**
 * Every row the TV's Home can have, titled as Home names them: the hero, every phone section the TV shows
 * ([TvHomeLayout.CATALOG]), the phone's own pins and rules, then YouTube's shelves on Home now ([shelves]).
 */
internal fun tvHomeAvailable(shelves: List<Shelf>, owner: String?, phone: List<HomeSectionConfig>, titles: TvHomeTitles): List<HomeSectionConfig> {
    val youtube = tvHomeRows(shelves, owner, emptyList(), emptyList(), titles).filter { it.key.startsWith(HomeSections.YT_PREFIX) }
    return TvHomeLayout.available(youtube.map { HomeSectionConfig(it.key, it.title) }, phone) { key ->
        when (key) {
            TvHomeLayout.HERO -> tr(R.string.now_playing)
            else -> tvRowTitle(phone, key)
        }
    }
}

const val TAG_HOME_EDITOR = "tv_home_editor"
/** Customize Home's moves in logcat (pick up, each step, put down), for a failure on a TV. */
private const val EDITOR_LOG = "PrismTvEditor"

/** How long OK is held to pick a row up (Android's own long press). */
private const val HOLD_MS = 500L
/** How long the editor watches, as it opens, for focus put on "Back to default" instead of the list (20 × 100 ms).
 */
private const val ENTRY_CHECKS = 20
private const val ENTRY_CHECK_MS = 100L

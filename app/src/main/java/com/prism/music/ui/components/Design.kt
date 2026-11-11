package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.TitleSize
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.hairline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.em

/** The style for big page titles, following the Title size setting. */
@Composable
fun pageTitleStyle(): TextStyle = when (LocalUi.current.titleSize) {
    TitleSize.LARGE -> MaterialTheme.typography.headlineLarge
    TitleSize.MEDIUM -> MaterialTheme.typography.headlineMedium
    TitleSize.SMALL -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal)
}

/** A top-level page's heading: a big title, an optional quiet line under it, and round actions on the right. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ui = LocalUi.current
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = ui.gap(14.dp), bottom = ui.gap(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = pageTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The disc behind a button that floats over a page (photos, artwork, scrolling content). Haze can't
 * blur inside the NavHost, so it carries its own near-solid fill: dark glass with white ink in dark
 * mode, milky white with near-black ink in light mode, legible over anything.
 */
@Composable
fun FloatingDisc(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.(ink: Color) -> Unit) {
    val dark = LocalIsDark.current
    // A disc of glass that reads over any photo: dark glass with white ink, or milky white with near-black ink.
    val fill = if (dark) Color(0xFF111111).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.78f)
    val ink = if (dark) Color.White else Color(0xFF171717)
    Box(
        modifier.size(42.dp)
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(CircleShape).background(fill).border(1.dp, glassEdge(), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content(ink) }
}

/** A header action: a round of glass (a floating disc, [FloatingDisc], when it floats over a photo or artwork). */
@Composable
fun RoundAction(icon: ImageVector, description: String, modifier: Modifier = Modifier, glass: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    if (glass) {
        FloatingDisc(modifier) { ink ->
            Box(Modifier.fillMaxSize().clickable(enabled = enabled, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
                Icon(icon, description, Modifier.size(22.dp), tint = ink.copy(alpha = if (enabled) 1f else 0.38f))
            }
        }
    } else {
        Box(
            modifier.size(42.dp).clip(CircleShape).background(glassFill(1.4f)).border(1.dp, glassEdge(), CircleShape)
                .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, Modifier.size(22.dp), tint = tint) }
    }
}

/** The widest a [SubPage]'s content gets; wider windows centre it. */
val SUBPAGE_MAX_WIDTH = 760.dp

/**
 * A secondary page (settings pages, Customize Home, Equalizer…): a big title that scrolls away under
 * a slim bar with a glass back button, which turns solid and shows the title once you've scrolled.
 */
@Composable
fun SubPage(
    title: String,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 0.dp,
    spacing: Dp = 0.dp,
    showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * Shown pinned under the bar once the list item keyed [pinnedKey] has scrolled up behind it (Appearance → Color's
     * preview: you always see what your choices do).
     */
    pinned: (@Composable () -> Unit)? = null,
    pinnedKey: Any? = null,
    content: LazyListScope.() -> Unit,
) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val showPinned by remember(state, pinnedKey) {
        derivedStateOf {
            if (pinned == null || pinnedKey == null) false
            else {
                // Item offsets start at the top padding, which is where the bar ends: below 0 is behind the bar.
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == pinnedKey }
                if (item == null) state.firstVisibleItemIndex > 0 else item.offset + item.size * 0.6f < 0
            }
        }
    }
    val scrolled by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 60 } }
    val bar by animateFloatAsState(if (scrolled) 1f else 0f, tween(220), label = "bar")
    // On a frosted page the content melts away under the bar into the page's own backdrop, as
    // on the artist and album pages; elsewhere the bar turns solid.
    val melts = LocalFrosted.current && LocalPageBackdrop.current != null
    BoxWithConstraints(modifier.fillMaxSize()) {
        // On a wide window the page keeps a readable width in the middle (it still scrolls from anywhere).
        val gutter = ((maxWidth - SUBPAGE_MAX_WIDTH) / 2).coerceAtLeast(0.dp)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontalPadding + gutter, end = horizontalPadding + gutter, top = statusTop + 60.dp, bottom = bottomPadding + 28.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            item(key = "subpage:title") {
                Column(Modifier.padding(start = 20.dp - horizontalPadding.coerceAtMost(20.dp), end = 20.dp, bottom = 10.dp)) {
                    Text(title, style = pageTitleStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            content()
        }
        if (melts) TopScrollEdge({ bar }, 60.dp)
        // Pinned, the preview sits on the page's own color edge to edge, so the rows scrolling up don't show beside or
        // behind it (they did, through the title, on the owner's phone).
        if (pinned != null) androidx.compose.animation.AnimatedVisibility(
            showPinned,
            Modifier.padding(top = statusTop + 60.dp).fillMaxWidth(),
            enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.slideInVertically(tween(200)) { -it / 3 },
            exit = androidx.compose.animation.fadeOut(tween(140)),
        ) {
            Box(Modifier.fillMaxWidth().background(scheme.surface).padding(start = horizontalPadding + gutter, end = horizontalPadding + gutter, bottom = 6.dp)) { pinned() }
        }
        // On the ambient frost the content melts away under the bar; on a plain page the bar turns solid once scrolled
        // (fully, while a preview is pinned under it).
        Box(
            Modifier.fillMaxWidth()
                .background(if (showPinned) scheme.surface else if (melts) Color.Transparent else scheme.surface.copy(alpha = 0.92f * bar))
                .statusBarsPadding()
                .height(60.dp),
        ) {
            if (showBack) RoundAction(PrismIcons.ArrowBack, tr(R.string.back), Modifier.align(Alignment.CenterStart).padding(start = 12.dp), glass = true) { nav.back() }
            Text(
                title, Modifier.align(Alignment.Center).padding(horizontal = 72.dp).graphicsLayer { alpha = bar },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions,
            )
        }
    }
}

/** A choice: a glass pill, lifted and ringed in split light when picked ([idle] fills an unpicked one, where asked). */
@Composable
fun PrismChip(
    selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    idle: Color = Color.Unspecified, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val quiet = glassFill(1.2f)
    val lifted = glassFill(2.6f)
    val bg by animateColorAsState(if (selected) lifted else if (idle != Color.Unspecified) idle else quiet, tween(180), label = "chipBg")
    val fg by animateColorAsState(if (selected) scheme.onSurface else scheme.onSurfaceVariant, tween(180), label = "chipFg")
    Row(
        modifier.height(36.dp).clip(CircleShape).background(bg)
            .then(if (selected) Modifier.spectrumRing(CircleShape) else Modifier.border(1.dp, glassEdge(), CircleShape))
            .semantics { this.selected = selected }
            .then(
                if (onLongClick == null) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel),
            ).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A sideways-scrolling row of [PrismChip]s. */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    onSelect: (T) -> Unit,
) {
    LazyRow(modifier, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i -> val o = options[i]; PrismChip(selected(o), { onSelect(o) }, label(o)) }
    }
}

/** Two to four options side by side in one glass capsule; the picked one is lifted out and ringed in split light. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lifted = glassFill(3f)
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(glassFill(1.2f)).border(1.dp, glassEdge(), CircleShape).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { o ->
            val on = o == selected
            val bg by animateColorAsState(if (on) lifted else Color.Transparent, tween(180), label = "seg")
            val fg by animateColorAsState(if (on) scheme.onSurface else scheme.onSurfaceVariant, tween(180), label = "segInk")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bg)
                    .then(if (on) Modifier.spectrumRing(CircleShape, 1.2.dp) else Modifier)
                    .semantics { this.selected = on }.clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = fg, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** Choices that wrap onto more lines when there are many. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceFlow(options: List<T>, selected: (T) -> Boolean, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> PrismChip(selected(o), { onSelect(o) }, label(o)) }
    }
}

/** A small uppercase label over a group: the overline style, widely spaced, quiet. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.em),
        color = color, maxLines = 1,
    )
}

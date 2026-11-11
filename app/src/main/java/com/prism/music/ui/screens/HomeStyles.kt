package com.prism.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons

/**
 * One thing in a Home section that isn't a YouTube item (a Prism mix, a Blend, a genre, a shortcut): its picture is
 * [art] when given, else [image], else [icon] on [fill].
 */
class HomeEntry(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val icon: ImageVector? = null,
    val fill: Brush? = null,
    /** Light text over [fill] (a colored tile); else the theme's. */
    val onFill: Boolean = false,
    val art: (@Composable (Modifier) -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** How tall a section's two-row Grid is, and a List's rows. */
private val GRID_CARD = 140.dp
private val GRID_ROW = 212.dp
private val LIST_ROW = 64.dp
private val LIST_WIDTH = 330.dp

/**
 * [entries] in a Home section's [style], the same way for every section that can be added to Home:
 * Carousel (a row of cards), Grid (two rows of smaller cards), List (rows of four, a column at a time) and Spotlight
 * (big pictures with their names over them).
 */
@Composable
fun StyledEntries(style: SectionStyle, entries: List<HomeEntry>) {
    if (entries.isEmpty()) return
    val ui = LocalUi.current
    when (style) {
        SectionStyle.CAROUSEL -> LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.key }) { e -> EntryCard(e, ui.cardWidth) }
        }
        // Prism's own tiles (mixes, moods) keep the two-row grid for 3 × 3 too: they're drawn, not covers.
        SectionStyle.GRID, SectionStyle.DIAL -> LazyHorizontalGrid(
            GridCells.Fixed(if (entries.size > 2) 2 else 1), Modifier.height(if (entries.size > 2) GRID_ROW * 2 else GRID_ROW),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(entries.size, key = { entries[it].key }) { i -> EntryCard(entries[i], GRID_CARD) }
        }
        SectionStyle.LIST -> {
            val rows = entries.size.coerceIn(1, 4)
            LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.height(LIST_ROW * rows), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(entries.size, key = { entries[it].key }) { i -> EntryRow(entries[i], Modifier.width(LIST_WIDTH)) }
            }
        }
        SectionStyle.HERO -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries.take(12), key = { it.key }) { e -> EntryHero(e) }
        }
    }
}

/**
 * YouTube's (and Release Radar's) albums, playlists, songs and artists in a Home section's [style]; List shows them as
 * rows whatever they are (it used to fall back to a carousel unless every one was a song).
 */
@Composable
fun StyledItems(style: SectionStyle, listed: List<BrowseItem>, open: (BrowseItem) -> Unit) {
    // #216: "Hide them" leaves explicit songs off Home's shelves (albums, artists and playlists stay).
    val items = com.prism.music.data.ExplicitFilter.items(com.prism.music.ui.theme.LocalAppSettings.current.behavior.explicitContent, listed)
    if (items.isEmpty()) return
    when (style) {
        SectionStyle.CAROUSEL -> ItemCarousel(items, open)
        SectionStyle.HERO -> HeroRow(items, open)
        // Reddit, 1.7.9.1: "Listen again" and the rest as YouTube Music's 3 × 3 pages, whole square covers.
        SectionStyle.DIAL -> SpeedDialPages(items, open)
        SectionStyle.GRID -> LazyHorizontalGrid(
            GridCells.Fixed(if (items.size > 2) 2 else 1), Modifier.height(if (items.size > 2) GRID_ROW * 2 else GRID_ROW),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(items.size) { i -> ItemCard(items[i], width = GRID_CARD) { open(items[i]) } }
        }
        SectionStyle.LIST -> {
            val rows = items.size.coerceIn(1, 4)
            LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.height(LIST_ROW * rows), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(items.size) { i -> Box(Modifier.width(LIST_WIDTH)) { ResultRow(items[i]) { open(items[i]) } } }
            }
        }
    }
}

@Composable
private fun EntryArt(e: HomeEntry, modifier: Modifier, shape: Shape, iconSize: Dp) {
    when {
        e.art != null -> e.art.invoke(modifier.clip(shape))
        e.image != null -> Artwork(e.image, modifier, shape, size = 544)
        else -> Box(
            modifier.clip(shape).background(e.fill ?: Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHigh))),
            contentAlignment = Alignment.Center,
        ) {
            e.icon?.let { Icon(it, null, Modifier.size(iconSize), tint = if (e.onFill) Color.White else MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(e: HomeEntry, width: Dp) {
    val ui = LocalUi.current
    Column(Modifier.width(width).clip(ui.tile).combinedClickable(onClick = e.onClick, onLongClick = e.onLongClick).padding(4.dp)) {
        EntryArt(e, Modifier.fillMaxWidth().aspectRatio(1f), ui.art, 40.dp)
        Spacer(Modifier.height(8.dp))
        Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        e.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: HomeEntry, modifier: Modifier) {
    val ui = LocalUi.current
    Row(
        modifier.height(LIST_ROW).combinedClickable(onClick = e.onClick, onLongClick = e.onLongClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryArt(e, Modifier.size(50.dp), ui.smallArt, 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryHero(e: HomeEntry) {
    val ui = LocalUi.current
    Box(
        Modifier.width(300.dp).aspectRatio(1.25f).clip(ui.shape(24.dp))
            .combinedClickable(onClick = e.onClick, onLongClick = e.onLongClick),
    ) {
        EntryArt(e, Modifier.fillMaxSize(), androidx.compose.foundation.shape.RoundedCornerShape(0.dp), 72.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
        Row(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                e.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(PrismIcons.ChevronRight, null, tint = Color.Black)
            }
        }
    }
}

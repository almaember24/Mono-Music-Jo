package com.prism.music.ui.components

import com.prism.music.ui.theme.monochrome
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.download.forRow
import com.prism.music.ui.theme.lit
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.drawscope.rotate
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.formatDuration
import com.prism.music.data.model.hiRes
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.hairline
import com.prism.music.ui.theme.ink3
import androidx.compose.foundation.border
import kotlinx.coroutines.launch

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    size: Int = 544,
    placeholderIcon: ImageVector = PrismIcons.Song,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            // Spectrum: a flat tile with a quiet icon until the picture arrives (color belongs to the artwork).
            .background(scheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholderIcon, null, tint = scheme.onSurface.copy(alpha = 0.28f), modifier = Modifier.fillMaxSize(0.36f))
        if (url != null) AsyncImage(
            model = hiRes(url, size),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A section title's style: medium weight, so headings read as headings beside the rows under them (#104). */
@Composable
fun sectionTitleStyle(): androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)

/**
 * A shelf's heading, as Apple Music sets them (and the artist pages do): a quiet strapline over the title. When it opens
 * the whole shelf, the whole row is the button, its chevron at the far end where a thumb reaches it (#107, as on
 * YouTube Music).
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, strapline: String? = null, onMore: (() -> Unit)? = null) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = ui.gap(18.dp), bottom = ui.gap(2.dp))
            .clip(RoundedCornerShape(12.dp))
            .then(if (onMore != null) Modifier.clickable(onClickLabel = tr(R.string.see_all), onClick = onMore) else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = ui.gap(8.dp), bottom = ui.gap(8.dp)),
    ) {
        if (!strapline.isNullOrBlank()) Text(
            strapline, style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.weight(1f), style = sectionTitleStyle(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (onMore != null) Icon(PrismIcons.ChevronRight, tr(R.string.see_all), Modifier.size(26.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    trailingInfo: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Marks one of an album's most-played tracks with a star, as Apple Music does. */
    starred: Boolean = false,
    /** More rows for the song's menu (e.g. "Remove from this playlist"). */
    extraActions: List<SheetItem> = emptyList(),
    /** The album's name after the artist when there's no [trailingInfo] (#310: Downloads can leave it out). */
    showAlbum: Boolean = true,
    onClick: () -> Unit,
) {
    // The likes, downloads and saved videos, collected once for the whole app (PrismRoot) rather than by each row: a
    // row coming into view during a fling started four collectors of its own (1.8.1).
    val marks = LocalRowMarks.current ?: LocalContainer.current.let { c ->
        rememberRowMarks(c.library.likedIds, c.downloadState, c.downloadStandIns, c.videoState) { s -> c.downloads.playsFromPhone(s) }
    }
    var menu by remember { mutableStateOf(false) }
    val done = androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    // Each row recomposes only when its own marks change, not every time any download, like or video changes anywhere
    // (a list on screen while Smart Downloads ran was rebuilt row by row at each step).
    val liked by remember(song.id, marks) { derivedStateOf { song.id in marks.liked.value } }
    // A music video downloaded as its song shows as downloaded (#315).
    // #385: a row whose song plays from a file on the phone too, whatever id the list gives it (read again whenever
    // the downloads change, which they do as lossless sync finds files).
    val downloaded by remember(song.id, marks) { derivedStateOf { com.prism.music.download.rowDownloaded(song, marks.downloads.value, marks.standIns.value, marks.fromPhone) } }
    val videoSaved by remember(song.id, marks) { derivedStateOf { marks.videos.value[song.id]?.state == done } }
    // Select many (1.8.3): in a list that takes part, long-press starts picking and a tap picks; elsewhere as before.
    val selection = LocalSongSelection.current
    val selectScope = LocalSelectScope.current
    val pickable = selection != null && selectScope != null && onLongClick == null
    val pick by remember(song.id, selection, pickable) { derivedStateOf { if (pickable) selection?.picked?.let { song.id in it } else null } }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    SongRowLayout(
        song, modifier, index, playing, trailingInfo, starred,
        liked = liked, downloaded = downloaded, videoSaved = videoSaved, showAlbum = showAlbum,
        onLongClick = when {
            pick != null -> ({ selection!!.toggle(song) })
            pickable -> ({ haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); selection!!.start(song, selectScope) })
            else -> onLongClick ?: { menu = true }
        },
        onMore = { menu = true }, onClick = if (pick != null) ({ selection!!.toggle(song) }) else onClick,
        pick = pick,
    )
    if (menu) SongActionsSheet(song, extraActions) { menu = false }
}

/**
 * What every [SongRow] marks: the liked songs, the downloads (+ the videos downloaded as their song, #315) and the saved
 * music videos, as states. Only their values are read, inside each row's own derivedStateOf.
 */
@androidx.compose.runtime.Stable
class RowMarks(
    val liked: androidx.compose.runtime.State<Set<String>>,
    val downloads: androidx.compose.runtime.State<Map<String, com.prism.music.download.DownloadInfo>>,
    val standIns: androidx.compose.runtime.State<Map<String, String>>,
    val videos: androidx.compose.runtime.State<Map<String, com.prism.music.download.VideoDownload>>,
    /** #385 (1.8.3): the song plays from a file on the phone (lossless sync's matcher, as playback asks it). */
    val fromPhone: (Song) -> Boolean = { false },
)

/** The app's [RowMarks], provided once at its root; null (a TV, a test) makes each row collect its own. */
val LocalRowMarks = androidx.compose.runtime.staticCompositionLocalOf<RowMarks?> { null }

/** The four flows collected here, once, as [RowMarks]. */
@Composable
fun rememberRowMarks(
    liked: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    downloads: kotlinx.coroutines.flow.StateFlow<Map<String, com.prism.music.download.DownloadInfo>>,
    standIns: kotlinx.coroutines.flow.StateFlow<Map<String, String>>,
    videos: kotlinx.coroutines.flow.StateFlow<Map<String, com.prism.music.download.VideoDownload>>,
    fromPhone: (Song) -> Boolean = { false },
): RowMarks {
    val l = liked.collectAsState()
    val d = downloads.collectAsState()
    val s = standIns.collectAsState()
    val v = videos.collectAsState()
    return remember(l, d, s, v) { RowMarks(l, d, s, v, fromPhone) }
}

/** [SongRow]'s look, given what it shows (so it can be drawn without the app around it: the Design gallery, tests). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRowLayout(
    song: Song,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    trailingInfo: String? = null,
    starred: Boolean = false,
    liked: Boolean = false,
    downloaded: Boolean = false,
    videoSaved: Boolean = false,
    showAlbum: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onMore: () -> Unit = {},
    onClick: () -> Unit = {},
    /** Select many: null when not picking, else whether this song is picked (a check in place of the ⋯). */
    pick: Boolean? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    val art = ui.songArt
    // #328: shown as the version picked to play in its place, if any.
    @Suppress("NAME_SHADOWING") val song = com.prism.music.data.SongVersions.shownOf(song)
    val pageMark = if (com.prism.music.ui.theme.LocalAppSettings.current.view.playingInPageColor) LocalPageColor.current else null
    Row(
        modifier
            .fillMaxWidth()
            .then(if (pick == true) Modifier.background(scheme.onSurface.copy(alpha = 0.08f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (pick != null) Modifier.semantics { selected = pick } else Modifier)
            // The playing song says so to TalkBack too, not only in color and the bars.
            .then(if (playing) Modifier.semantics { stateDescription = tr(R.string.now_playing) } else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = ui.gap(6.dp), bottom = ui.gap(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Row(Modifier.width(38.dp), verticalAlignment = Alignment.CenterVertically) {
                if (starred) Icon(PrismIcons.StarFilled, tr(R.string.popular), Modifier.size(11.dp), tint = scheme.ink3)
                else Spacer(Modifier.width(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    "$index", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (playing) scheme.onSurface else scheme.ink3,
                )
            }
        }
        // The cover picked for it or its album (#305), else its own.
        Artwork(com.prism.music.data.PlaylistCovers.artOf(song), Modifier.size(48.dp), art, size = 226)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // The playing song's title is written in split light (deeper on light backgrounds), beside the spectrum bars;
            // or, asked for (Reddit, 1.7.9.2), in the album's / playlist's / artist's own color on its page.
            val dark = com.prism.music.ui.theme.LocalIsDark.current
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = when {
                    !playing -> MaterialTheme.typography.bodyLarge
                    pageMark != null -> MaterialTheme.typography.bodyLarge.copy(color = pageMark, fontWeight = FontWeight.Medium)
                    else -> MaterialTheme.typography.bodyLarge.copy(brush = com.prism.music.ui.theme.PrismTokens.spectrum(dark), fontWeight = FontWeight.Medium)
                },
                color = if (playing && pageMark != null) pageMark else scheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val mark = Modifier.size(13.dp).padding(end = 3.dp)
                if (song.explicit) Icon(PrismIcons.Explicit, tr(R.string.explicit), mark, tint = scheme.ink3)
                if (liked) Icon(PrismIcons.HeartFilled, tr(R.string.liked), mark, tint = scheme.ink3)
                if (downloaded) Icon(PrismIcons.DownloadDone, tr(R.string.downloaded), mark, tint = scheme.ink3)
                if (videoSaved) Icon(PrismIcons.Video, tr(R.string.video_saved), mark, tint = scheme.ink3)
                Text(
                    listOfNotNull(song.artistText, trailingInfo ?: song.album?.title?.takeIf { showAlbum }).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
        if (playing) PlayingBars(
            Modifier.padding(start = 8.dp).size(13.dp, 14.dp).then(if (pageMark != null) Modifier.testTag("pageColorBars") else Modifier),
            color = pageMark ?: Color.White, spectrum = pageMark == null,
        )
        if (pick == null) IconButton(onClick = onMore) { Icon(PrismIcons.More, tr(R.string.more), Modifier.size(20.dp), tint = scheme.ink3) }
        else Box(Modifier.size(48.dp).testTag("pick"), contentAlignment = Alignment.Center) {
            if (pick) Icon(PrismIcons.CheckCircle, tr(R.string.picked), Modifier.size(22.dp), tint = scheme.onSurface)
            else Box(Modifier.size(20.dp).border(1.5.dp, scheme.ink3, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

/**
 * Three bouncing bars: what's playing. [spectrum]: Spectrum's mark, the bars in the split light (red, green, violet)
 * instead of [color]. Still with reduced motion, and while the music is paused ([playing], #302: they bounced on).
 */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = Color.White, spectrum: Boolean = false, playing: Boolean = LocalMusicPlaying.current()) {
    val still = com.prism.music.ui.theme.LocalUi.current.reduceMotion || !playing
    val colors = if (spectrum && !com.prism.music.ui.theme.PrismTokens.white) listOf(Color(0xFF969696), Color(0xFFA5A5A5), Color(0xFF949494)) else listOf(color, color, color)
    // With reduced motion there's no animation running at all (it used to tick every frame and just not show it); nor
    // while the full player hides the list it's in, nor while the song is paused.
    val hidden = LocalHiddenByPlayer.current()
    val heights: List<androidx.compose.runtime.State<Float>> = if (still || hidden) STILL_BARS else {
        val t = rememberInfiniteTransition(label = "bars")
        listOf(
            t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a"),
            t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "b"),
            t.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(370), RepeatMode.Reverse), label = "d"),
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEachIndexed { i, h ->
            // The heights are read as the bars are drawn: each frame moves them without recomposing the row.
            Box(Modifier.weight(1f).fillMaxSize().graphicsLayer {
                scaleY = h.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }.clip(RoundedCornerShape(1.dp)).background(colors[i]))
        }
    }
}

private val STILL_BARS: List<androidx.compose.runtime.State<Float>> = listOf(0.6f, 1f, 0.75f).map { androidx.compose.runtime.mutableFloatStateOf(it) }

@Composable
fun ItemCard(item: BrowseItem, modifier: Modifier = Modifier, width: Dp = Dp.Unspecified, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val round = item is ArtistItem
    val ui = com.prism.music.ui.theme.LocalUi.current
    val w = if (width == Dp.Unspecified) ui.cardWidth else width
    // Without a long press of its own, holding a card offers to play or shuffle it.
    val menu = rememberPlayMenu()
    val longPress = onLongClick ?: item.playable()?.let { p -> { menu(p) } }
    Column(modifier.width(w).clip(ui.tile).combinedClickable(onClick = onClick, onLongClick = longPress).padding(4.dp)) {
        when (item) {
            is MoodItem -> MoodTile(item, Modifier.fillMaxWidth().aspectRatio(1.6f), onClick)
            else -> {
                val video = item is SongItem && item.song.isVideo
                Artwork(
                    LocalContainer.current.covers.art(item),
                    Modifier.fillMaxWidth().aspectRatio(if (video) 16f / 9f else 1f),
                    // Each kind in its own shape (1.7.7); a wide video keeps rounded corners.
                    when {
                        round -> ui.artistArt
                        video -> ui.art
                        item is SongItem -> ui.songArt
                        else -> ui.albumArt
                    },
                    placeholderIcon = when (item) {
                        is ArtistItem -> PrismIcons.Person
                        is AlbumItem -> PrismIcons.Album
                        is PlaylistItem -> PrismIcons.QueueMusic
                        is com.prism.music.data.model.PodcastItem -> PrismIcons.Podcasts
                        else -> PrismIcons.Song
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    item.title, maxLines = if (video) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(lineHeight = MaterialTheme.typography.titleSmall.fontSize * 1.25f),
                    modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
                if (item.subtitle.isNotBlank()) Text(
                    com.prism.music.data.model.shownKinds(item.subtitle), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
            }
        }
    }
}

@Composable
fun MoodTile(item: MoodItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(item.color or 0xFF000000).lit()
    val ui = com.prism.music.ui.theme.LocalUi.current
    // The mood's own color, deepening across the tile, with a soft sheen; its name set light.
    Box(
        modifier
            .clip(ui.tile)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f).compositeOverBlack())))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), radius = 260f, center = androidx.compose.ui.geometry.Offset(0f, 0f))))
        Text(
            item.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)

@Composable
fun ItemCarousel(items: List<BrowseItem>, onClick: (BrowseItem) -> Unit, cardWidth: Dp = Dp.Unspecified) {
    val w = if (cardWidth == Dp.Unspecified) com.prism.music.ui.theme.LocalUi.current.cardWidth else cardWidth
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Each once: a shelf listing one twice crashed the row ("Key was already used", #346's kind).
        items(items.distinctBy { it.id + it.title }, key = { it.id + it.title }) { item ->
            ItemCard(item, width = if (item is SongItem && item.song.isVideo) w * 1.5f else w) { onClick(item) }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(56.dp), contentAlignment = Alignment.Center) { PrismSpinner(Modifier.size(30.dp)) }
}

/** Loading: an arc of split light going round. */
@Composable
fun PrismSpinner(modifier: Modifier = Modifier, stroke: androidx.compose.ui.unit.Dp = 2.5.dp) {
    val still = com.prism.music.ui.theme.LocalUi.current.reduceMotion
    // With reduced motion it doesn't turn, and no animation runs for it.
    val turn = if (still) null else rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    androidx.compose.foundation.Canvas(modifier.then(Modifier.semantics { contentDescription = tr(R.string.loading) })) {
        val w = stroke.toPx()
        val inset = w / 2
        val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        rotate(turn?.value ?: 0f) {
            drawArc(
                com.prism.music.ui.theme.PrismTokens.spectrumRing(center), -90f, 270f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/**
 * A thin progress line in split light on a faint track: [progress] (0…1) fills from the start, or with none a short
 * run of light travels along it.
 */
@Composable
fun PrismProgressBar(progress: (() -> Float)?, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 3.dp) {
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    val still = com.prism.music.ui.theme.LocalUi.current.reduceMotion
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // Read as it's drawn (it used to recompose the bar every frame).
    val run = if (progress == null && !still) {
        rememberInfiniteTransition(label = "bar").animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1300, easing = androidx.compose.animation.core.FastOutSlowInEasing)), label = "run")
    } else null
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(height).clip(CircleShape).semantics { contentDescription = tr(R.string.loading) }) {
        drawRect(track)
        val brush = com.prism.music.ui.theme.PrismTokens.spectrum(dark, 0f, size.width)
        if (progress != null) {
            drawRect(brush, size = size.copy(width = size.width * progress().coerceIn(0f, 1f)))
        } else {
            val from = (run?.value ?: 0f) * size.width
            drawRect(brush, topLeft = androidx.compose.ui.geometry.Offset(from, 0f), size = size.copy(width = size.width * 0.35f))
        }
    }
}

/** Something didn't load: a quiet icon on glass, one plain line, and one way to try again. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(glassFill(1.4f)).border(1.dp, glassEdge(), CircleShape), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.CloudOff, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            PrismOutlinedButton(onClick = onRetry) { Text(tr(R.string.try_again)) }
        }
    }
}

/** Nothing here yet: the icon on a round of glass ringed in split light, a light title and what to do about it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(glassFill(1.4f)).spectrumRing(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun MarqueeText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    Text(text, modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500), style = style, color = color, maxLines = 1)
}

/** A search pill for filtering a list in place ("Search in playlist"), as you type. [container] defaults to the theme's. */
@Composable
fun SearchField(query: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, container: Color = Color.Unspecified) {
    val scheme = MaterialTheme.colorScheme
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // A glass pill (or the page's [container] fill).
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(CircleShape)
            .background(if (container != Color.Unspecified) container else glassFill(1.4f)).border(1.dp, glassEdge(), CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PrismIcons.Search, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                query, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) IconButton(onClick = { onChange(""); focus.clearFocus() }) {
            Icon(PrismIcons.Close, tr(R.string.clear_search), Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Sheets

/**
 * A bottom sheet that always lets go. Material's sheet could be left half-dismissed (blocking the
 * screen) when flicked or tapped away while it was still opening; this watches where the sheet
 * actually lands and finishes the dismissal itself, exactly once. [content] gets a `close { … }`
 * that slides the sheet away first and then runs the action, so a follow-up sheet never overlaps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismSheet(
    onDismiss: () -> Unit,
    containerColor: Color = androidx.compose.material3.BottomSheetDefaults.ContainerColor,
    contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
    dragHandle: (@Composable () -> Unit)? = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val then = remember { arrayOfNulls<() -> Unit>(1) }
    val finish = remember {
        {
            if (done.compareAndSet(false, true)) {
                dismiss()
                then[0]?.invoke()
            }
        }
    }
    LaunchedEffect(state) {
        var shown = false
        androidx.compose.runtime.snapshotFlow { state.currentValue to state.targetValue }.collect { (now, target) ->
            if (now != androidx.compose.material3.SheetValue.Hidden) shown = true
            else if (shown && target == androidx.compose.material3.SheetValue.Hidden) finish()
        }
    }
    val close: (() -> Unit) -> Unit = remember {
        { action ->
            if (then[0] == null) then[0] = action
            scope.launch { try { state.hide() } finally { finish() } }
        }
    }
    ModalBottomSheet(onDismissRequest = finish, sheetState = state, containerColor = containerColor, contentColor = contentColor, dragHandle = dragHandle) {
        // A sheet is its own window, so the pictures in it are made grey here (Mono.kt).
        Column(Modifier.monochrome()) { content(close) }
    }
}

// ---------------------------------------------------------------- Song actions

/** "Download video": saves the song's music video (and the song) for offline; tapped again, takes the video back. */
@Composable
fun videoDownloadAction(song: Song, video: com.prism.music.download.VideoDownload?): ActionItem? {
    if (song.isLive) return null
    val c = LocalContainer.current
    val done = video?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    val failed = video?.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED
    return ActionItem(
        PrismIcons.Video, if (done) tr(R.string.remove_downloaded_video) else tr(R.string.download_video),
        value = when {
            done -> tr(R.string.saved_2)
            failed -> tr(R.string.failed_tap_to_remove)
            video != null -> "${video.percent.toInt().coerceAtLeast(0)}%"
            else -> null
        },
        active = done, keepOpen = true,
    ) { if (video != null) c.downloads.removeVideo(song.id) else c.downloads.downloadVideo(song) }
}

/**
 * A song's menu, wherever it's held or its ⋯ is tapped: like and share at hand, the queue
 * actions as tiles, then where to go. [extra] adds rows (e.g. "Remove from this playlist").
 */
@Composable
fun SongActionsSheet(song: Song, extra: List<SheetItem> = emptyList(), onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloadState.collectAsState()
    val videos by c.videoState.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(false) }
    val picks by c.versions.picks.collectAsState()
    val isLiked = song.id in liked
    val standIns by c.downloadStandIns.collectAsState()
    val dl = downloads.forRow(song.id, standIns)
    val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    val together by c.together.state.collectAsState()
    // A file on the phone isn't on YouTube: no radio, download, sharing, YouTube playlists, or sending it to a session.
    val local = song.isLocal
    // An episode from a feed isn't on YouTube either: no download, sharing, likes or playlists for it.
    val rss = song.isRss
    val guest = (together as? com.prism.music.playback.together.TogetherState.Guest)?.takeIf { !local && !rss }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    // The playlist picker and the share sheet take over from the sheet.
    if (pickPlaylist) { PlaylistPicker(song) { pickPlaylist = false; onDismiss() }; return }
    // 1.8.3: the listener's own tags on it.
    var tagging by remember { mutableStateOf(false) }
    if (tagging) { TagPicker(listOf(song), song.title) { tagging = false; onDismiss() }; return }
    val songTags by remember(song.id) { c.tags.tagsOf(song.id) }.collectAsState(emptyList())
    if (sharing) { ShareSheet(song.shareTarget()) { sharing = false; onDismiss() }; return }
    if (credits) { SongCreditsSheet(song) { credits = false; onDismiss() }; return }
    var details by remember { mutableStateOf(false) }
    if (details) { SongDetailsSheet(song) { details = false; onDismiss() }; return }
    if (versions) { SongVersionsSheet(song) { versions = false; onDismiss() }; return }
    // #305: the song's own cover.
    var cover by remember { mutableStateOf(false) }
    if (cover) { CoverPickSheet(CoverFor.OfSong(song, c.covers.songKey(song.id))) { cover = false; onDismiss() }; return }

    // #328: as the version picked to play in its place, if any (the actions still act on the song itself).
    val shown = com.prism.music.data.SongVersions.shownOf(song)
    // In someone's Listen Together session, songs go into their queue.
    val quick = listOf(
        if (guest != null) ActionItem(PrismIcons.Groups, tr(R.string.play_next)) { c.together.suggest(song, next = true); toast(tr(R.string.sent_to, guest.hostName)) }
        else ActionItem(PrismIcons.PlaylistPlay, tr(R.string.play_next)) { c.player.playNext(song) },
        if (guest != null) ActionItem(PrismIcons.PersonAdd, tr(R.string.add_to_queue)) { c.together.suggest(song, next = false); toast(tr(R.string.sent_to, guest.hostName)) }
        else ActionItem(PrismIcons.QueueMusic, tr(R.string.add_to_queue)) { c.player.addToQueue(song) },
        // A radio of a podcast episode is music radio: not offered for episodes.
        if (song.isEpisode || local) null else ActionItem(PrismIcons.Radio, tr(R.string.radio_2)) { c.player.playRadio(song) },
        if (local || rss) null else ActionItem(
            if (downloaded) PrismIcons.DownloadDone else PrismIcons.Download, tr(R.string.download),
            value = when { downloaded -> tr(R.string.downloaded); dl != null -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> null },
            active = downloaded, keepOpen = true,
        ) { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) },
    ).filterNotNull()
    val go = listOfNotNull(
        // Straight to its most replayed stretch (or its chorus).
        if (song.isEpisode || song.isLive || guest != null) null else ActionItem(PrismIcons.FlameFilled, tr(R.string.play_from_the_best_part)) { c.player.playFromBestPart(song) },
        song.podcastId?.let { id -> ActionItem(PrismIcons.Podcasts, tr(R.string.go_to_podcast)) { nav.go(Routes.podcast(id)) } },
        if (song.isEpisode && song.id in c.episodes.played.value) ActionItem(PrismIcons.Refresh, tr(R.string.mark_as_unplayed)) { c.episodes.forget(song.id); c.episodes.unmarkPlayed(song.id) }
        else if (song.isEpisode) ActionItem(PrismIcons.DoneAll, tr(R.string.mark_as_played)) { c.episodes.markPlayed(song.id) } else null,
        if (rss) null else ActionItem(PrismIcons.PlaylistAdd, tr(R.string.add_to_playlist), keepOpen = true) { pickPlaylist = true },
        if (rss) null else ActionItem(PrismIcons.Tag, tr(R.string.add_a_tag), value = tagsValue(songTags), active = songTags.isNotEmpty(), keepOpen = true) { tagging = true },
        // A picked version's album and artists (#328): it plays as that recording.
        shown.album?.id?.let { id -> ActionItem(PrismIcons.Album, tr(R.string.go_to_album), value = shown.album?.title?.takeIf { it.length <= 22 }) { nav.go(Routes.album(id)) } },
    ) + goToArtists(shown) { route -> nav.go(route) } + listOfNotNull(
        // Who performed, wrote and produced it (Reddit 1.7).
        if (song.isEpisode || local || song.isLive) null else ActionItem(PrismIcons.Info, tr(R.string.song_credits), keepOpen = true) { credits = true },
        // Its plays and dates (#211). The listener's own uploads aren't public: YouTube has nothing to say about them.
        if (song.isEpisode || local || song.isLive || song.isUpload) null else ActionItem(PrismIcons.Replay, tr(R.string.song_details), keepOpen = true) { details = true },
    )
    val more = extra.map { it.toAction() } + listOfNotNull(
        // Your own FLAC / MP3 files (a Local file, or the lossless file played for this song) can keep its lyrics (#16).
        if (local || dl?.lossless == true) ActionItem(PrismIcons.Lyrics, tr(R.string.save_lyrics_into_the_file)) { c.lyricsToFiles.request(listOf(song)) } else null,
        // Another recording of it in its place (Reddit 1.7.9: an album song played as someone's cover).
        if (song.isEpisode || local || song.isLive || guest != null) null
        else ActionItem(PrismIcons.SwapVertical, tr(R.string.play_a_different_version), active = song.id in picks, keepOpen = true) { versions = true },
        // #305: a cover of your choosing (a photo, another version's, or back to its own).
        if (song.isEpisode || song.isLive) null
        else ActionItem(PrismIcons.Photo, tr(R.string.change_cover), active = c.covers.custom(c.covers.songKey(song.id)) != null, keepOpen = true) { cover = true },
        if (song.isEpisode || local) null else videoDownloadAction(song, videos[song.id]),
        if (song.isEpisode) null else if (song.id in disliked) ActionItem(PrismIcons.ThumbDownFilled, tr(R.string.not_for_me), active = true, keepOpen = true) { c.library.setDisliked(song, false) }
        else ActionItem(PrismIcons.ThumbDown, tr(R.string.not_for_me)) { c.library.setDisliked(song, true); toast(tr(R.string.got_it_prism_will_steer_clear)) },
        // Plays as usual, but its plays don't shape recommendations (a song played for someone else).
        if (song.isEpisode || local) null else {
            val out = c.exclusions.isSongExcluded(song.id)
            ActionItem(PrismIcons.Block, if (out) tr(R.string.back_in_my_taste) else tr(R.string.leave_out_of_my_taste)) {
                c.exclusions.setSong(com.prism.music.data.foryou.Excluded(song.id, "${song.title} · ${song.artistText}", song.thumbnail), !out)
                toast(if (out) tr(R.string.it_counts_towards_your_recommendations_again) else tr(R.string.its_plays_won_t_shape_your))
            }
        },
    )

    val songArt = c.covers.art(song)
    ActionSheet(songArt, onDismiss) { close ->
        ActionHeader(
            songArt, shown.title,
            listOfNotNull(shown.artistText, formatDuration(shown.durationSec).takeIf { it.isNotEmpty() }).joinToString(" · "),
            placeholderIcon = PrismIcons.Song,
        ) {
            if (!rss) SheetIconButton(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, if (isLiked) tr(R.string.remove_from_liked_songs) else tr(R.string.add_to_liked_songs), active = isLiked) {
                c.library.toggleLike(song)
            }
            if (!local && !rss) SheetIconButton(PrismIcons.Share, tr(R.string.share)) { sharing = true }
        }
        if (guest != null) Text(
            tr(R.string.adding_to_s_session, guest.hostName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        QuickActions(quick) { runAction(it, close) }
        ActionGroup(null, go) { runAction(it, close) }
        ActionGroup(null, more) { runAction(it, close) }
    }
}

/** One extra row for [SongActionsSheet] or [PlayActionsSheet]; [danger] marks a delete. */
data class SheetItem(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(com.prism.music.ui.theme.LocalUi.current.shape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface) }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * Adds [song] to one of the listener's playlists, or to a new one: Prism playlists (on the phone) and, when signed in, the
 * account's YouTube Music ones. A new one is a YouTube Music playlist when signed in, else a Prism one.
 */
@Composable
fun PlaylistPicker(song: Song, onDismiss: () -> Unit) = PlaylistPicker(listOf(song), song.title, onDismiss)

/**
 * Adds [songs] (a song, or a whole album or playlist: Reddit R8) to one of the listener's playlists, or to a new one.
 * [what] names them under the sheet's title.
 */
@Composable
fun PlaylistPicker(songs: List<Song>, what: String, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val playlists by c.library.playlists.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    // A file on the phone can only go in a Prism playlist (YouTube Music has no copy of it).
    val signedIn = c.settings.current.isLoggedIn && songs.none { it.isLocal }
    val ids = songs.map { it.id }
    val many = songs.size > 1
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false; onDismiss() }, local = !signedIn) { name, privacy ->
            creating = false
            onDismiss()
            if (!signedIn) { c.prismLibrary.create(name, songs); toast(tr(R.string.added_to, name)); return@NewPlaylistDialog }
            c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                // A long list (Liked songs, a big playlist) goes in 50 at a time after the first: one request with
                // thousands of songs could be refused.
                val id = runCatching {
                    c.ytm.createPlaylist(name, ids.take(50), privacy = privacy)?.also { if (ids.size > 50) runCatching { c.ytm.addAllToPlaylist(it, ids.drop(50)) } }
                }.getOrNull()
                toast(if (id != null) tr(R.string.added_to, name) else tr(R.string.couldn_t_make_the_playlist))
                if (id != null) c.library.created(id, name, ids.size)
            }
        }
        return
    }
    // A smart playlist (1.8.3) takes what its rules find, nothing added by hand.
    val mine = prismLists.filter { it.smart == null }.map { it.item() } +
        (if (signedIn) playlists.filterIsInstance<PlaylistItem>().filter { !it.id.startsWith("LM") && !it.isMix } else emptyList())
    val shown = if (query.isBlank()) mine else mine.filter { it.title.contains(query.trim(), ignoreCase = true) }
    // #246 (1.8.1): the playlists that already have it, from what's on the phone (Prism playlists, the kept copies of
    // playlists opened before, what was added since): nothing is asked from YouTube for the marks.
    val holds by androidx.compose.runtime.produceState(emptyMap<String, Int>(), mine.map { it.id }, prismLists, ids) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val prism = prismLists.associate { l -> l.id to l.songs.map { it.id } }
            val known = com.prism.music.data.PlaylistMembership.known(mine, prism) { id -> c.offline.page(id)?.songs?.map { it.id } }
            com.prism.music.data.PlaylistMembership.counts(ids, known)
        }
    }
    PrismSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.padding(bottom = 20.dp).testTag("playlistPicker")) {
            Text(tr(R.string.add_to_playlist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                if (many) "$what · ${com.prism.music.data.model.songCount(songs.size)}" else what,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            SheetAction(PrismIcons.Add, tr(R.string.new_playlist)) { creating = true }
            if (mine.size > 8) SearchField(query, tr(R.string.find_a_playlist), { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (mine.isEmpty()) Text(
                if (signedIn) tr(R.string.your_playlists_show_up_here_once) else tr(R.string.make_your_first_playlist_it_s), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
            )
            // #401: a fast fling's leftover at the list's end stays with the list (it pushed the sheet past its top).
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.heightIn(max = 440.dp).nestedScroll(KeepUpwardScroll).testTag("playlistPickerList"),
            ) {
                items(shown, key = { it.id }) { p ->
                    val has = holds[p.id] ?: 0
                    // #246: says when it's in there already (or how many of several are).
                    val line = when {
                        has == 0 -> p.subtitle.removePrefix("Playlist • ").takeIf { it.isNotBlank() }
                        !many -> tr(R.string.already_has_this_song)
                        has >= ids.distinct().size -> tr(R.string.already_has_all_of_these)
                        else -> tr(R.string.already_has_n_of_these, has, ids.distinct().size)
                    }
                    PickerRow(c.covers.art(p), p.title, line, has > 0, if (has > 0) tr(R.string.already_in, p.title) else null, p.id) {
                        close {
                            if (com.prism.music.data.PrismLibrary.isPrismList(p.id)) {
                                val added = c.prismLibrary.add(p.id, songs)
                                toast(
                                    when {
                                        added == 0 -> tr(R.string.already_in, p.title)
                                        many -> tr(R.string.added_to_2, com.prism.music.data.model.songCount(added), p.title)
                                        else -> tr(R.string.added_to, p.title)
                                    },
                                )
                                return@close
                            }
                            c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                // Only what YouTube says went in is called added (Reddit, 1.7.9: "Added" for songs
                                // that never got there).
                                val result = runCatching { c.ytm.addSongsToPlaylist(p.id, ids) }
                                toast(addedMessage(result.getOrNull(), p.title, many))
                                // #246: the next time the sheet opens it says they're in, though the kept copy predates them.
                                result.getOrNull()?.takeIf { it.added + it.alreadyThere > 0 }?.let { com.prism.music.data.PlaylistMembership.noteAdded(p.id, ids) }
                                if ((result.getOrNull()?.added ?: 0) > 0) c.library.playlistChanged(p.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One playlist in [PlaylistPicker]: its cover, its name, [line] under it (null: none; [strong] when it says the songs are
 * in there), and a ✓ described as [mark] (tagged with the playlist's [id]) when they are.
 */
@Composable
internal fun PickerRow(art: String?, title: String, line: String?, strong: Boolean, mark: String?, id: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(46.dp), com.prism.music.ui.theme.LocalUi.current.smallArt, size = 120, placeholderIcon = PrismIcons.QueueMusic)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Always two lines tall (#401): the marks come in after the sheet opens, and a row that grew a line then
            // changed the list's heights in the middle of a fling. (A space, not "": an empty line is drawn shorter.)
            Text(
                line ?: " ", style = MaterialTheme.typography.bodySmall,
                modifier = if (line == null) Modifier.clearAndSetSemantics {} else Modifier,
                color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (mark != null) Icon(
            PrismIcons.Check, mark,
            Modifier.padding(start = 8.dp).size(20.dp).testTag("inPlaylist:$id"), tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * What to say after adding to the playlist [title]: only songs YouTube took count as added ([result] null = it failed).
 * Several songs say how many went in, so a partly refused album doesn't read as all of it.
 */
internal fun addedMessage(result: com.prism.music.data.innertube.YouTubeMusic.PlaylistAdd?, title: String, many: Boolean): String = when {
    result == null || (result.added == 0 && result.alreadyThere == 0) -> tr(R.string.couldn_t_add_to, title)
    result.added == 0 -> tr(R.string.already_in, title)
    many || result.added > 1 -> tr(R.string.added_to_2, com.prism.music.data.model.songCount(result.added), title)
    else -> tr(R.string.added_to, title)
}

/** Name a new playlist: a YouTube Music one (and who can open it), or with [local] a Prism one kept on this phone. */
@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, local: Boolean = false, onCreate: (name: String, privacy: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf("PRIVATE") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.new_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.prism.music.ui.components.PrismTextField(name, { name = it.take(150) }, label = { Text(tr(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (local) {
                    Text(
                        tr(R.string.kept_on_this_phone_and_in),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Segmented(listOf("PRIVATE", "UNLISTED", "PUBLIC"), privacy, { when (it) { "PRIVATE" -> tr(R.string.private_text); "UNLISTED" -> tr(R.string.unlisted); else -> tr(R.string.public_text) } }) { privacy = it }
                Text(
                    when (privacy) {
                        "PRIVATE" -> tr(R.string.only_you_can_see_it)
                        "UNLISTED" -> tr(R.string.anyone_with_the_link_can_open)
                        else -> tr(R.string.anyone_can_find_it)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { PrismTextButton(onClick = { onCreate(name.trim(), privacy) }, enabled = name.isNotBlank()) { Text(tr(R.string.create)) } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/** Chips that follow a pager: tap to jump, or swipe the pages and the chip moves along. */
@Composable
fun PagerChips(labels: List<String>, pager: androidx.compose.foundation.pager.PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pager.currentPage) { row.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(
        modifier, state = row,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels.size) { i ->
            PrismChip(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, labels[i])
        }
    }
}
/** "⇅ Title" — opens a menu of sort orders; the current one is ticked. */
@Composable
fun <T> SortButton(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PrismIcons.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label(selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(o), fontWeight = if (o == selected) FontWeight.Medium else FontWeight.Normal) },
                    onClick = { onSelect(o); open = false },
                    trailingIcon = if (o == selected) ({ Icon(PrismIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        }
    }
}

/**
 * The artists of a song that can be opened: linked ones, and (#78) ones YouTube named without a link, which open by
 * name ([com.prism.music.ui.Routes.artist]). A file on the phone has no YouTube artists.
 */
fun openableArtists(song: Song): List<com.prism.music.data.model.ArtistRef> =
    if (song.isLocal || song.isRss) emptyList()
    else song.artists.filter { it.name.isNotBlank() }.distinctBy { it.id ?: com.prism.music.data.innertube.SearchRank.norm(it.name) }

/**
 * #289 (1.8.1): where each of [artists] is written in [text] (the song's "A, B & C"), in order, so each name can open its
 * own page. Null when a name isn't there as written (the text then stays one link, which asks which artist).
 */
fun artistSpans(text: String, artists: List<com.prism.music.data.model.ArtistRef>): List<Pair<IntRange, com.prism.music.data.model.ArtistRef>>? {
    if (artists.size < 2) return null
    var from = 0
    return artists.map { a ->
        val at = text.indexOf(a.name, from).takeIf { it >= 0 } ?: return null
        from = at + a.name.length
        (at until from) to a
    }
}

/** "Go to …" for each of a song's artists (#42): a song with features lists every one, not only the first. */
fun goToArtists(song: Song, go: (String) -> Unit): List<ActionItem> =
    openableArtists(song).take(5).map { a -> ActionItem(PrismIcons.Person, tr(R.string.go_to, a.name)) { go(com.prism.music.ui.Routes.artist(a)) } }

/** A list's count on the left and its [SortButton] on the right. */
@Composable
fun <T> SortBar(count: String, options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        SortButton(options, selected, label, onSelect = onSelect)
    }
}

/**
 * Keeps the list [state] scrolls at its top while rows come in above the one it shows, until the listener scrolls it
 * themselves (#279). A lazy list holds on to the first row in view by its key: when a page's sections load in one by one
 * and only the last row was there at first, the page opened scrolled to its bottom.
 */
@Composable
fun KeepAtTopUntilScrolled(state: androidx.compose.foundation.lazy.LazyListState) {
    var scrolled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) scrolled = true }
    }
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.layoutInfo.totalItemsCount }.collect {
            if (!scrolled && (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0)) state.scrollToItem(0)
        }
    }
}
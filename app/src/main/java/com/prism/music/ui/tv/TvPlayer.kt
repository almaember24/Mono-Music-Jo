@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.prism.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismIcons

/**
 * Now playing (TV): full screen, the cover on the left and the song on the right: title, artist, progress, then the
 * transport row (shuffle, previous, play/pause, next, repeat) and the row under it (lyrics, queue, video, like, more).
 * Focus opens on play/pause; Up/Down move between the two rows; Back returns where it was opened from.
 */
@Composable
fun TvNowPlayingScreen(
    now: TvNow,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onVideo: () -> Unit,
    onLike: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    /** Seeks by this many ms: the progress line takes focus and ← → move along it (1.8.3, D29). Null: only a line. */
    onSeek: ((Long) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().testTag(TAG_PLAYER)) {
        TvMeshBackdrop(now.art)
        // Reddit (1.8.1): a two-line title with every row could run past a TV's foot, with large text or a TV that draws
        // bigger than most. The cover keeps to the room there is; the column's gaps give way, then the column itself.
        Row(Modifier.fillMaxSize().padding(TvSafe), verticalAlignment = Alignment.CenterVertically) {
            Artwork(now.art, Modifier.sizeIn(maxWidth = 340.dp, maxHeight = 340.dp).aspectRatio(1f), shape = RoundedCornerShape(18.dp), size = 1080)
            Spacer(Modifier.width(52.dp))
            TvFitColumn(Modifier.weight(1f).fillMaxHeight()) {
                Text(now.title, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TvFitGap(6.dp)
                Text(now.artist, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now.album != null) Text(now.album, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TvFitGap(32.dp)
                // Up from the controls lands on it; ← → seek, OK pauses; Down goes back to the controls. A live
                // stream has nothing to seek in: a plain line.
                if (onSeek != null && now.total.isNotEmpty()) TvSeekLine(
                    now, onSeek, onPlayPause,
                    // The line's ring needs room around it: drawn that much wider, so the bar lines up with the title.
                    Modifier.testTag("$TAG_PLAYER/progress").layout { m, c ->
                        val extra = 12.dp.roundToPx()
                        val w = c.maxWidth + 2 * extra
                        val p = m.measure(c.copy(minWidth = w, maxWidth = w))
                        layout(c.maxWidth, p.height) { p.place(-extra, 0) }
                    },
                ) else TvProgress(now.fraction, now.played, now.total, Modifier.testTag("$TAG_PLAYER/progress"))
                TvFitGap(20.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvIconButton(PrismIcons.Shuffle, tr(R.string.shuffle), onShuffle, Modifier.tvItem("$TAG_PLAYER/shuffle"), on = now.shuffle)
                    TvIconButton(PrismIcons.Previous, tr(R.string.previous_song), onPrevious, Modifier.tvItem("$TAG_PLAYER/previous"))
                    TvIconButton(
                        if (now.playing) PrismIcons.Pause else PrismIcons.Play, tr(if (now.playing) R.string.widget_pause else R.string.widget_play),
                        onPlayPause, Modifier.tvItem("$TAG_PLAYER/playpause", entry = true), big = true,
                    )
                    TvIconButton(PrismIcons.Next, tr(R.string.next_song), onNext, Modifier.tvItem("$TAG_PLAYER/next"))
                    TvIconButton(
                        PrismIcons.Repeat, tr(when (now.repeat) { 1 -> R.string.repeat_one; 2 -> R.string.repeat_all; else -> R.string.repeat_off }),
                        onRepeat, Modifier.tvItem("$TAG_PLAYER/repeat"), on = now.repeat != 0,
                    )
                }
                TvFitGap(20.dp)
                // Down from the controls lands on this row's first button, every time (as the design's path).
                val rowStart = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                Row(Modifier.layoutId(TV_FIT_WIDE).focusProperties { onEnter = { if (byDpad()) rowStart.requestFocus() } }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (now.hasLyrics) TvButton(tr(R.string.lyrics), onLyrics, Modifier.focusRequester(rowStart).tvItem("$TAG_PLAYER/lyrics"), icon = PrismIcons.Lyrics)
                    TvButton(tr(R.string.queue), onQueue, (if (now.hasLyrics) Modifier else Modifier.focusRequester(rowStart)).tvItem("$TAG_PLAYER/queue"), icon = PrismIcons.Queue)
                    if (now.hasVideo) TvButton(tr(R.string.video), onVideo, Modifier.tvItem("$TAG_PLAYER/video"), icon = PrismIcons.Video)
                    TvIconButton(if (now.liked) PrismIcons.HeartFilled else PrismIcons.Heart, tr(if (now.liked) R.string.liked else R.string.widget_like), onLike, Modifier.tvItem("$TAG_PLAYER/like"), on = now.liked)
                    TvIconButton(PrismIcons.More, tr(R.string.more), onMore, Modifier.tvItem("$TAG_PLAYER/more"))
                }
                if (now.next != null) {
                    TvFitGap(28.dp)
                    Text(tr(R.string.tv_up_next_line, now.next), Modifier.testTag("$TAG_PLAYER/upnext"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A gap in a [TvFitColumn]: this high when there's room, less when there isn't. */
@Composable
private fun TvFitGap(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height).layoutId(TV_FIT_GAP))

private const val TV_FIT_GAP = "gap"

/** A row of buttons in a [TvFitColumn]: it keeps its whole width, and the column is drawn smaller for it if it must be. */
private const val TV_FIT_WIDE = "wide"

/** How far a [TvFitColumn]'s gaps may close (a share of their height), before the column itself is drawn smaller. */
private const val TV_FIT_MIN_GAP = 0.35f

/**
 * A column centered in the height it's given, its parts at their own heights. Short of room, its gaps ([TvFitGap]) close
 * first; if that's not enough, the whole column is drawn smaller to fit (and for a row of buttons too wide for it,
 * [TV_FIT_WIDE], whose last ones would be squeezed off). So nothing runs off the screen or into the next part, at any TV
 * size or text size, and with room to spare it's a plain column.
 */
@Composable
private fun TvFitColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val widest = measurables.filter { it.layoutId == TV_FIT_WIDE }.maxOfOrNull { it.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) } ?: 0
        val narrowed = if (widest > width) width.toFloat() / widest else 1f
        val room = constraints.maxHeight
        val inner = (width / narrowed).toInt()
        val parts = measurables.map { m -> (m.layoutId == TV_FIT_GAP) to m.measure(androidx.compose.ui.unit.Constraints(maxWidth = inner)) }
        val solid = parts.filterNot { it.first }.sumOf { it.second.height }
        val gaps = parts.filter { it.first }.sumOf { it.second.height }
        val fit = tvFit(solid, gaps, (room / narrowed).toInt())
        val scale = fit.scale * narrowed
        layout(width, room) {
            var y = (room - (solid + gaps * fit.gaps) * scale) / 2f
            for ((gap, p) in parts) {
                if (gap) { y += p.height * fit.gaps * scale; continue }
                if (scale >= 1f) p.placeRelative(0, y.toInt())
                else p.placeRelativeWithLayer(0, y.toInt()) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
                y += p.height * scale
            }
        }
    }
}

/** How a [TvFitColumn] fits: its gaps at this share of their height, and the whole at this scale. */
internal data class TvFit(val gaps: Float, val scale: Float)

/** [solid] parts and [gaps] (px) into [room]: gaps close down to [TV_FIT_MIN_GAP] first, then the whole shrinks. */
internal fun tvFit(solid: Int, gaps: Int, room: Int): TvFit {
    if (solid + gaps <= room) return TvFit(1f, 1f)
    val share = if (gaps > 0) ((room - solid).toFloat() / gaps).coerceAtLeast(TV_FIT_MIN_GAP) else 1f
    if (share >= 1f || solid + gaps * share <= room) return TvFit(share.coerceAtMost(1f), 1f)
    return TvFit(share, room / (solid + gaps * share))
}

/**
 * The queue (TV): a panel over the player's right side, the playing song first, the rest under "Up next". Focus
 * opens on the next song; OK plays it, holding OK opens its menu (play next, remove); Back or Left closes the panel.
 */
@Composable
fun BoxScope.TvQueuePanel(source: String?, songs: List<TvSong>, current: Int, onSong: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Transparent, scheme.background.copy(alpha = 0.85f)))))
    Column(
        modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp).tvGlass(TvShapes.panelStart, TvPanelOverPage).padding(vertical = 27.dp, horizontal = 24.dp).testTag(TAG_QUEUE),
    ) {
        Text(tr(R.string.up_next), style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        if (source != null) Text(tr(R.string.tv_playing_from, source), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        // Opens at the playing song, so the next one (focus's place) is drawn however far down the queue it is.
        val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (songs.size - 1).coerceAtLeast(0)))
        LazyColumn(Modifier.rememberedFocus(), state = list, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(songs) { i, s ->
                TvSongRow(
                    s.title, s.artist, s.length, { onSong(i) },
                    // The next song; on the last song, that one (focus is never left behind the panel).
                    modifier = Modifier.tvItem("$TAG_QUEUE/$i", entry = i == (current + 1).coerceAtMost(songs.lastIndex)),
                    art = s.art, playing = i == current,
                )
            }
        }
    }
}

// The music video (its controls, the seek line, the queue over it): TvVideo.kt.

/**
 * Sing (TV): karaoke night. The lyrics screen with the voice taken out: the song's key under the title and a
 * "Vocals out" mark; the lyrics as large as they go. Only on TVs fast enough (Sing's speed check), else it says so.
 */
@Composable
fun TvSingScreen(now: TvNow, lyrics: TvLyrics, clock: State<Long>, key: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize()) {
        TvLyricsScreen(now, lyrics, clock, accent = accent)
        Row(Modifier.align(Alignment.TopEnd).padding(TvSafe), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvChip(tr(R.string.tv_vocals_out), PrismIcons.Mic, scheme.primary, scheme.onPrimary)
            TvChip(tr(R.string.tv_key_line, key), null, scheme.surfaceVariant, scheme.onSurface)
        }
    }
}

/** A small rounded label (not focusable): "Vocals out", "Key: +2". */
@Composable
fun TvChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, fill: Color, ink: Color) {
    Row(Modifier.background(fill, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { androidx.tv.material3.Icon(icon, null, Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/** One action in a side panel (the player's ⋯). */
data class TvAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/**
 * A panel of actions over the right side of a screen (the player's ⋯: go to the album, the artist, Sing…). Focus
 * opens on the first; Back or Left closes it (the caller's BackHandler).
 */
@Composable
fun BoxScope.TvActionPanel(title: String, actions: List<TvAction>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Transparent, scheme.background.copy(alpha = 0.85f)))))
    Column(
        modifier.align(Alignment.CenterEnd).fillMaxHeight().width(400.dp).tvGlass(TvShapes.panelStart, TvPanelOverPage).padding(vertical = 27.dp, horizontal = 24.dp).testTag(TAG_ACTIONS),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        // A list that scrolls: a choice can be long (Settings' 32 languages), and every one must be reachable.
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
            itemsIndexed(actions) { i, a -> TvButton(a.label, a.onClick, Modifier.fillMaxWidth().tvItem("$TAG_ACTIONS/$i", entry = i == 0), icon = a.icon) }
        }
    }
}

const val TAG_ACTIONS = "tv_actions"
const val TAG_PLAYER = "tv_player"
const val TAG_LYRICS = "tv_lyrics"
const val TAG_QUEUE = "tv_queue"
const val TAG_VIDEO = "tv_video"

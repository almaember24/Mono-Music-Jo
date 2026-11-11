package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.MiniProgress
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.hairline
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    /** No surface of its own: it sits in the tab bar's glass ("Joined to the tab bar", 1.7.6). */
    bare: Boolean = false,
    onOpen: () -> Unit,
) {
    val pc = LocalContainer.current.player
    val song by pc.currentSong.collectAsState()
    val playing by pc.isPlaying.collectAsState()
    val s = song ?: return
    val settings = LocalAppSettings.current
    val ui = LocalUi.current
    // Read only in the progress drawing below, so the playhead moving redraws that line, not the whole mini player.
    val position = rememberPositionState(fast = false)
    val drag = remember { Animatable(0f) }
    // Pulled down far enough, it stops the music and goes (#59).
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    val style = settings.miniPlayerStyle
    var menu by remember { mutableStateOf(false) }
    if (menu) com.prism.music.ui.components.SongActionsSheet(s) { menu = false }
    val container = LocalContainer.current
    var sleep by remember { mutableStateOf(false) }
    if (sleep) SleepTimerDialog { sleep = false }
    // The mini player's swipes (Settings → Player → Gestures): what doesn't need the full player.
    fun runMiniGesture(g: PlayerGesture) {
        when (g) {
            PlayerGesture.NEXT -> pc.next()
            PlayerGesture.PREVIOUS -> pc.previous()
            PlayerGesture.SEEK_BACK -> pc.seekBy(-10_000L)
            PlayerGesture.SEEK_AHEAD -> pc.seekBy(10_000L)
            PlayerGesture.PLAY_PAUSE -> pc.togglePlay()
            PlayerGesture.LIKE -> container.library.toggleLike(s)
            PlayerGesture.SLEEP -> sleep = true
            PlayerGesture.MENU -> menu = true
            PlayerGesture.STOP -> pc.stop()
            // The ones about the full player open it.
            PlayerGesture.OPEN, PlayerGesture.QUEUE, PlayerGesture.LYRICS, PlayerGesture.VIDEO, PlayerGesture.SING -> onOpen()
            PlayerGesture.SEEK_SIDES, PlayerGesture.NONE -> {}
        }
    }

    // Card: the full-width piece of glass over the tab bar, its progress line along the bottom.
    val bar = style == MiniPlayerStyle.CARD
    val height = when (style) { MiniPlayerStyle.CARD -> 64.dp; MiniPlayerStyle.SLIM -> 54.dp; MiniPlayerStyle.PILL -> 56.dp }
    val shape = when (style) {
        MiniPlayerStyle.CARD -> ui.card
        MiniPlayerStyle.SLIM -> ui.shape(16.dp)
        MiniPlayerStyle.PILL -> RoundedCornerShape(50)
    }
    val artSize = when (style) { MiniPlayerStyle.CARD -> 44.dp; MiniPlayerStyle.SLIM -> 40.dp; MiniPlayerStyle.PILL -> 44.dp }
    val artShape = if (style == MiniPlayerStyle.PILL) CircleShape else if (bar) ui.smallArt else ui.shape(9.dp)
    fun fraction() = (position.value.toFloat() / pc.duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    // #231 (1.7.9.2): the progress in your accent, plain ink or the cover's color instead of the split light.
    val progressBrush = miniProgressBrush(settings.view.miniProgressColor, com.prism.music.data.PlaylistCovers.artOf(s))
    // The same piece of glass as the tab bar under it, with the tab bar's own Background (see-through, solid…): Card used
    // to turn into a solid bar with the progress along its top whenever Frosted Glass was off (owner, 1.7.6).
    val tint = com.prism.music.navFill(settings.navBarFill)
    val surface: @Composable (Modifier, @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) -> Unit = { m, content ->
        if (bare) Box(m, content = content)
        else GlassSurface(m, shape = shape, tint = tint, content = content)
    }

    surface(
        modifier
            .fillMaxWidth()
            .height(height)
            .testTag("miniPlayer")
            .offset { IntOffset(drag.value.roundToInt(), pull.value.roundToInt()) }
            .graphicsLayer { alpha = 1f - (pull.value / (height.toPx() * 1.6f)).coerceIn(0f, 0.85f) }
            .pointerInput(Unit) {
                val stopAt = height.toPx() * 0.9f
                // Up (#149, 1.7.7): the card lifts a little against the finger (a third of the drag, at most a third of its
                // height) and opens the player, or does what's set, once lifted past [upAt].
                val upAt = -height.toPx() * 0.22f
                val upMost = -height.toPx() * 0.34f
                var raw = 0f
                detectVerticalDragGestures(
                    onDragStart = { raw = pull.value },
                    onDragEnd = {
                        scope.launch {
                            val up = container.settings.current.miniSwipeUp
                            if (pull.value <= upAt && up != PlayerGesture.NONE) {
                                haptics.gestureEnd()
                                runMiniGesture(up)
                                pull.animateTo(0f)
                                return@launch
                            }
                            // Pulled down: stop (#59) or whatever's set in Settings → Player → Gestures (1.7.4).
                            val down = container.settings.current.miniSwipeDown
                            if (pull.value > stopAt && down != PlayerGesture.NONE) {
                                haptics.gestureEnd()
                                if (down == PlayerGesture.STOP) {
                                    pull.animateTo(height.toPx() * 1.6f)
                                    pc.stop()
                                    pull.snapTo(0f)
                                } else {
                                    runMiniGesture(down)
                                    pull.animateTo(0f)
                                }
                            } else pull.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { pull.animateTo(0f) } },
                ) { change, d ->
                    change.consume()
                    raw += d
                    // #222: a way with no gesture set (Settings → Player → Gestures: Nothing) doesn't move the card at all.
                    val now = container.settings.current
                    val after = when {
                        raw >= 0f -> if (now.miniSwipeDown == PlayerGesture.NONE) 0f else raw
                        now.miniSwipeUp == PlayerGesture.NONE -> 0f
                        else -> (raw / 3f).coerceAtLeast(upMost)
                    }
                    if ((pull.value < stopAt) != (after < stopAt) || (pull.value > upAt) != (after > upAt)) haptics.threshold()
                    scope.launch { pull.snapTo(after) }
                }
            }
            .pointerInput(Unit) {
                var rawX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { rawX = drag.value },
                    onDragEnd = {
                        scope.launch {
                            val now = container.settings.current
                            val g = when {
                                drag.value < -140 -> now.miniSwipeLeft
                                drag.value > 140 -> now.miniSwipeRight
                                else -> PlayerGesture.NONE
                            }
                            if (g != PlayerGesture.NONE) { haptics.gestureEnd(); runMiniGesture(g) }
                            drag.animateTo(0f)
                        }
                    },
                ) { _, d ->
                    val before = drag.value
                    rawX += d
                    // #222: nor sideways, toward a swipe set to Nothing.
                    val now = container.settings.current
                    val after = if ((if (rawX < 0f) now.miniSwipeLeft else now.miniSwipeRight) == PlayerGesture.NONE) 0f else rawX
                    if ((kotlin.math.abs(before) < 140f) != (kotlin.math.abs(after) < 140f)) haptics.threshold()
                    scope.launch { drag.snapTo(after) }
                }
            },
    ) {
        // Fill: the played part of the song tints the whole card, in the split light (red at the start, violet at the
        // end; White light's accent or greys).
        if (settings.miniProgress == MiniProgress.FILL) Box(
            Modifier.matchParentSize().drawBehind {
                val f = fraction()
                if (f > 0f) drawRect(
                    progressBrush ?: com.prism.music.ui.theme.PrismTokens.spectrum(dark, 0f, size.width),
                    size = androidx.compose.ui.geometry.Size(size.width * f, size.height),
                    alpha = settings.view.miniFillStrength * (if (dark) 1f else 0.82f),
                )
            },
        )
        Row(
            // Holding it brings up the song's actions (like, add to playlist, share…) without opening the player.
            Modifier.fillMaxSize().combinedClickable(onClick = onOpen, onLongClick = { haptics.click(); menu = true })
                .padding(start = if (style == MiniPlayerStyle.PILL) 6.dp else if (bar) 16.dp else 8.dp, end = if (style == MiniPlayerStyle.PILL) 8.dp else if (bar) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(com.prism.music.data.PlaylistCovers.artOf(s), with(CoverFlight) { Modifier.size(artSize).miniCover().graphicsLayer { alpha = if (returning) 0f else 1f } }, artShape, size = 226)
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                s,
                transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
                modifier = Modifier.weight(1f),
                label = "mini",
            ) { playing ->
                // #328: as the version picked to play in its place, if any.
                val song = com.prism.music.data.SongVersions.shownOf(playing)
                Column {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (style != MiniPlayerStyle.SLIM || song.artistText.isNotBlank()) Text(
                        song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (settings.miniPrevButton) {
                IconButton(onClick = { haptics.click(); pc.previous() }, modifier = Modifier.size(40.dp)) { Icon(PrismIcons.Previous, tr(R.string.widget_previous)) }
            }
            // Play / pause can be left off (Reddit, 1.8.1): a tap still opens the player.
            if (!settings.view.miniPlayButton) {
                if (!settings.miniSkipButtons) Spacer(Modifier.width(8.dp))
            } else if (style == MiniPlayerStyle.PILL) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(scheme.onSurface).clickable { haptics.click(); pc.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (playing) PrismIcons.Pause else PrismIcons.Play, tr(R.string.play_pause), Modifier.size(24.dp), tint = scheme.surface) }
            } else {
                IconButton(onClick = { haptics.click(); pc.togglePlay() }, modifier = Modifier.size(44.dp)) {
                    Icon(if (playing) PrismIcons.Pause else PrismIcons.Play, tr(R.string.play_pause), Modifier.size(if (style == MiniPlayerStyle.SLIM) 26.dp else 28.dp))
                }
            }
            if (settings.miniSkipButtons) {
                IconButton(onClick = { haptics.click(); pc.next() }, modifier = Modifier.size(40.dp)) { Icon(PrismIcons.Next, tr(R.string.widget_next)) }
            }
        }
        if (settings.miniProgress == MiniProgress.LINE) {
            Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter).padding(horizontal = if (style == MiniPlayerStyle.PILL) 28.dp else 18.dp)) {
                drawLine(scheme.onSurface.copy(alpha = 0.12f), Offset(0f, 0f), Offset(size.width, 0f), 2.5.dp.toPx(), StrokeCap.Round)
                // Progress in split light (deeper on light glass).
                val f = fraction()
                if (f > 0f) drawLine(
                    progressBrush ?: com.prism.music.ui.theme.PrismTokens.spectrum(dark, 0f, size.width), Offset(0f, 0f), Offset(size.width * f, 0f), 2.5.dp.toPx(), StrokeCap.Round,
                )
            }
        }
    }
}

/** The mini player's progress in [color] (#231); null keeps the split light. Unlike the player it can sit on light glass. */
@Composable
private fun miniProgressBrush(color: com.prism.music.data.prefs.ScrubberColor, cover: String?):androidx.compose.ui.graphics.Brush? {
    val scheme = MaterialTheme.colorScheme
    return when (color) {
        com.prism.music.data.prefs.ScrubberColor.SPECTRUM -> null
        com.prism.music.data.prefs.ScrubberColor.WHITE -> androidx.compose.ui.graphics.SolidColor(scheme.onSurface)
        com.prism.music.data.prefs.ScrubberColor.ACCENT -> androidx.compose.ui.graphics.SolidColor(scheme.primary)
        com.prism.music.data.prefs.ScrubberColor.COVER -> {
            val palette = com.prism.music.ui.components.rememberArtPalette(cover)
            androidx.compose.ui.graphics.SolidColor(palette?.vibrant ?: scheme.primary)
        }
    }
}
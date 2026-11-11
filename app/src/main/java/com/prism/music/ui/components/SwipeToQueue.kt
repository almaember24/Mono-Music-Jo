package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.rememberHaptics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A song's row that can be swiped sideways: to the right it plays next, to the left it goes on the end of the queue
 * (in someone's Listen Together session, it's sent to theirs). Letting go short of the mark does nothing.
 */
@Composable
fun SwipeToQueue(song: Song, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // While songs are being picked (Select many), a sideways drag shouldn't queue one of them.
    if (LocalSongSelection.current?.selecting == true) { androidx.compose.foundation.layout.Box(modifier) { content() }; return }
    val c = LocalContainer.current
    val context = LocalContext.current
    val together by c.together.state.collectAsState()
    SwipeToQueue({ next ->
        val guest = (together as? com.prism.music.playback.together.TogetherState.Guest)?.takeIf { !song.isLocal }
        val text = if (guest != null) {
            c.together.suggest(song, next = next)
            tr(R.string.sent_to, guest.hostName)
        } else {
            if (next) c.player.playNext(song) else c.player.addToQueue(song)
            if (next) tr(R.string.playing_next) else tr(R.string.added_to_queue)
        }
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }, modifier, content)
}

/** An album's or playlist's row swiped the same way: all its songs play next or go on the end of the queue (#248). */
@Composable
fun SwipeToQueue(item: com.prism.music.data.model.BrowseItem, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val target = item.playable() ?: return content()
    SwipeToQueue({ next -> queuePlayable(c, context, target, item.title, next) }, modifier, content)
}

/** The swipe itself: [onQueue] with true to the right (play next), false to the left (end of the queue). */
@Composable
fun SwipeToQueue(onQueue: (next: Boolean) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val mark = with(density) { 84.dp.toPx() }
    val reach = with(density) { 150.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val scheme = MaterialTheme.colorScheme
    fun queue(next: Boolean) = onQueue(next)

    Box(modifier.fillMaxWidth().clipToBounds()) {
        // What the swipe uncovers, in the gap it opens: tinted once letting go would do it.
        val x = offset
        if (x != 0f) {
            val next = x > 0
            val armed = abs(x) >= mark
            Row(
                Modifier.matchParentSize(),
                horizontalArrangement = if (next) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .width(with(density) { abs(x).toDp() })
                        .background(if (armed) scheme.primary else scheme.surfaceContainerHighest)
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = if (next) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (armed) scheme.onPrimary else scheme.onSurfaceVariant
                    if (next) Icon(PrismIcons.PlaylistPlay, null, Modifier.size(22.dp), tint = ink)
                    if (abs(x) > mark * 0.7f) {
                        if (next) Spacer(Modifier.width(8.dp))
                        Text(
                            if (next) tr(R.string.play_next) else tr(R.string.add_to_queue), color = ink, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Clip,
                        )
                        if (!next) Spacer(Modifier.width(8.dp))
                    }
                    if (!next) Icon(PrismIcons.QueueMusic, null, Modifier.size(22.dp), tint = ink)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    rememberDraggableState { d ->
                        val before = offset
                        // Past the mark it follows the finger more slowly.
                        val after = (before + d * if (abs(before) > mark) 0.45f else 1f).coerceIn(-reach, reach)
                        offset = after
                        if ((abs(before) >= mark) != (abs(after) >= mark)) haptics.threshold()
                    },
                    Orientation.Horizontal,
                    onDragStopped = {
                        val from = offset
                        if (abs(from) >= mark) { haptics.gestureEnd(); queue(next = from > 0) }
                        animate(from, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
                    },
                ),
        ) { content() }
    }
}

package com.prism.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A slider along the right edge of a long list (1.7.9, Reddit: "a slider on the right to scroll, it's hard to swipe that
 * long"): it shows while the list moves, and dragging its thumb jumps straight to that part of the list, with a bubble
 * saying where ([label] for how far down the thumb is, 0..1: e.g. the song's number). Only for lists longer than [minItems].
 *
 * With [thumbOnly] (#376) only a finger-sized grip around the thumb takes touches, and it moves from where it is: the rest
 * of the edge still scrolls the list and reaches what's on it, so a list's rows lose nothing to it.
 */
@Composable
fun FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    minItems: Int = 60,
    description: String,
    thumbOnly: Boolean = false,
    label: (Float) -> String?,
) {
    val list = remember(state) {
        ScrolledList(
            { state.layoutInfo.totalItemsCount }, { state.layoutInfo.visibleItemsInfo.size }, { state.firstVisibleItemIndex },
            { state.isScrollInProgress }, { state.scrollToItem(it) },
        )
    }
    Scroller(list, modifier, minItems, description, thumbOnly, label)
}

/** The same for a grid (Library's lists are grids, with one column as a list). */
@Composable
fun FastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    minItems: Int = 60,
    description: String,
    thumbOnly: Boolean = false,
    label: (Float) -> String?,
) {
    val list = remember(state) {
        ScrolledList(
            { state.layoutInfo.totalItemsCount }, { state.layoutInfo.visibleItemsInfo.size }, { state.firstVisibleItemIndex },
            { state.isScrollInProgress }, { state.scrollToItem(it) },
        )
    }
    Scroller(list, modifier, minItems, description, thumbOnly, label)
}

/** What the slider needs of a list or a grid. */
private class ScrolledList(
    val total: () -> Int,
    val visible: () -> Int,
    val first: () -> Int,
    val scrolling: () -> Boolean,
    val jump: suspend (Int) -> Unit,
)

@Composable
private fun Scroller(list: ScrolledList, modifier: Modifier, minItems: Int, description: String, thumbOnly: Boolean, label: (Float) -> String?) {
    val total by remember(list) { derivedStateOf { list.total() } }
    if (total < minItems) return
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    // Shown while the list scrolls or the thumb is held, then fades away after a moment.
    val scrolling = list.scrolling()
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true
        else { delay(1_500); shown = false }
    }
    val scope = rememberCoroutineScope()
    val fraction by remember(list) {
        derivedStateOf {
            (list.first().toFloat() / (list.total() - list.visible().coerceAtLeast(1)).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }
    AnimatedVisibility(shown, modifier.fillMaxHeight(), enter = fadeIn(), exit = fadeOut()) {
        // Wide enough for the bubble beside the strip; only the strip itself takes touches.
        BoxWithConstraints(Modifier.fillMaxHeight().width(160.dp)) {
            val density = LocalDensity.current
            val thumbHeight = if (thumbOnly) 56.dp else 48.dp
            val travel = with(density) { (maxHeight - thumbHeight).toPx() }
            var dragFraction by remember { mutableStateOf(0f) }
            val at = if (dragging) dragFraction else fraction
            val y = (travel * at).roundToInt()
            // The thumb: a pill, wider while held.
            val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.85f else if (thumbOnly) 0.6f else 0.45f)
            if (thumbOnly) Box(
                // A finger's width, and it travels with the thumb; everything else on the edge is still the list's.
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.size(width = 48.dp, height = thumbHeight)
                    .semantics { contentDescription = description }
                    .pointerInput(travel) {
                        detectVerticalDragGestures(
                            onDragStart = { dragFraction = fraction; dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, amount ->
                            change.consume()
                            dragFraction = (dragFraction + amount / travel).coerceIn(0f, 1f)
                            // As the thumb reads the list, so it doesn't jump when the finger first moves or lifts.
                            // Not below 0: the list can be emptied under the finger.
                            val target = if (dragFraction >= 0.999f) list.total() - 1
                            else (dragFraction * (list.total() - list.visible().coerceAtLeast(1)).coerceAtLeast(1)).roundToInt()
                            scope.launch { list.jump(target.coerceIn(0, (list.total() - 1).coerceAtLeast(0))) }
                        }
                    },
            ) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(end = 4.dp).size(width = if (dragging) 14.dp else 10.dp, height = thumbHeight)
                        .background(thumbColor, RoundedCornerShape(50)),
                )
            } else Box(
                Modifier.align(Alignment.TopEnd).fillMaxHeight().width(36.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(travel) {
                        detectVerticalDragGestures(
                            onDragStart = { o -> dragging = true; dragFraction = (o.y / travel).coerceIn(0f, 1f) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            dragFraction = (change.position.y / travel).coerceIn(0f, 1f)
                            val target = (dragFraction * (list.total() - 1).coerceAtLeast(0)).roundToInt()
                            scope.launch { list.jump(target) }
                        }
                    },
            ) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 4.dp)
                        .size(width = if (dragging) 8.dp else 5.dp, height = thumbHeight)
                        .background(thumbColor, RoundedCornerShape(50)),
                )
            }
            if (dragging) {
                label(dragFraction)?.let { text ->
                    Box(
                        Modifier.align(Alignment.TopEnd).offset { IntOffset(with(density) { (if (thumbOnly) -56 else -44).dp.roundToPx() }, y) }
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

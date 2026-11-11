package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/** What a row of a [ReorderList] can use beyond its modifier. */
interface ReorderScope {
    /**
     * For a grip (the ⠿ handle): dragging it moves the row at once, no hold needed. The rest of the row still moves
     * after a hold.
     */
    fun Modifier.dragHandle(): Modifier
}

/**
 * A list to rearrange: hold any row (or drag its [ReorderScope.dragHandle]) and move it up or down; [onReorder] gets the
 * new order when it's let go. [row] draws each one ([held] while it's carried) and must apply the modifier it's given.
 *
 * As it moves (1.7.4): the carried row lifts off the page on a card edged in the split light, the rows it passes slide
 * out of its way, a light tick marks each place it passes, the page scrolls while it's held near the screen's top or
 * bottom (so the last row can go first in one move), and it settles into its place when let go.
 */
@Composable
fun <T> ReorderList(
    items: List<T>,
    keyOf: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable ReorderScope.(item: T, index: Int, held: Boolean, modifier: Modifier) -> Unit,
) {
    var dragKey by remember { mutableStateOf<Any?>(null) }
    var dragOrder by remember { mutableStateOf<List<T>?>(null) }
    // The finger's travel from the carried row's own place (its layout moves as it's reordered and as the page scrolls).
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var heldHeight by remember { mutableIntStateOf(0) }
    // After a swap, no other until the row has been laid out in its new place (else one move could count twice).
    var awaitingLayout by remember { mutableStateOf(false) }
    // Rows sliding out of the way, and the dropped row settling: an offset per row, eased to 0.
    val slides = remember { mutableStateMapOf<Any, Animatable<Float, *>>() }
    val current by rememberUpdatedState(items)
    val onReorderNow by rememberUpdatedState(onReorder)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // Scrolls the page around the list while a row is held at its edge.
    val scroller = remember { NestedScrollDispatcher() }
    val noConnection = remember { object : NestedScrollConnection {} }
    val rows = dragOrder ?: items

    fun slide(k: Any, from: Float) {
        val a = slides.getOrPut(k) { Animatable(0f) }
        scope.launch {
            a.snapTo(from)
            a.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun start(k: Any) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        dragOrder = current; dragKey = k; dragOffset = 0f; awaitingLayout = false
    }

    /** Moves the carried row a place up or down when it's been carried more than half over its neighbour. */
    fun place(k: Any) {
        val list = dragOrder ?: return
        if (awaitingLayout || heldHeight == 0) return
        val at = list.indexOfFirst { keyOf(it) == k }
        val to = when {
            dragOffset > heldHeight * 0.55f && at < list.lastIndex -> at + 1
            dragOffset < -heldHeight * 0.55f && at > 0 -> at - 1
            else -> at
        }
        if (to == at) return
        // The row passed over slides into the place the carried one left.
        slide(keyOf(list[to]), (to - at) * heldHeight.toFloat())
        dragOrder = list.toMutableList().apply { add(to, removeAt(at)) }
        awaitingLayout = true
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun move(k: Any, dy: Float) {
        dragOffset += dy
        place(k)
    }

    fun end(k: Any, keep: Boolean) {
        val order = dragOrder
        val left = dragOffset
        dragKey = null; dragOrder = null; dragOffset = 0f; awaitingLayout = false
        if (keep && order != null && order != current) onReorderNow(order)
        // Eases from where it was let go into its place.
        if (left != 0f) slide(k, left)
    }

    suspend fun PointerInputScope.dragAfterHold(k: Any) = detectDragGesturesAfterLongPress(
        onDragStart = { start(k) },
        onDrag = { change, amount -> change.consume(); move(k, amount.y) },
        onDragEnd = { end(k, true) },
        onDragCancel = { end(k, false) },
    )

    suspend fun PointerInputScope.dragNow(k: Any) = detectDragGestures(
        onDragStart = { start(k) },
        onDrag = { change, amount -> change.consume(); move(k, amount.y) },
        onDragEnd = { end(k, true) },
        onDragCancel = { end(k, false) },
    )

    Column(modifier.nestedScroll(noConnection, scroller)) {
        rows.forEachIndexed { i, item ->
            val k = keyOf(item)
            key(k) {
                val held = dragKey == k
                val lift by animateFloatAsState(if (held) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "lift")
                var rowHeight by remember { mutableIntStateOf(0) }
                var lastY by remember { mutableFloatStateOf(Float.NaN) }
                // Held in the top or bottom of the screen (where the bars float over the page): the page scrolls, faster
                // the further in it's held. The row stays under the finger and keeps finding its place as rows pass.
                if (held) LaunchedEffect(k) {
                    val density = view.resources.displayMetrics.density
                    val zone = EDGE_ZONE_DP * density
                    while (true) {
                        withFrameNanos { }
                        if (lastY.isNaN()) continue
                        val top = lastY + dragOffset
                        val bottom = top + rowHeight
                        fun speed(into: Float) = 2 * density + (into / zone).coerceIn(0f, 1f) * MAX_STEP_DP * density
                        val delta = when {
                            top < zone -> speed(zone - top)
                            bottom > view.height - zone -> -speed(bottom - (view.height - zone))
                            else -> 0f
                        }
                        // Handed to the page as scrolling (a side effect, not a finger's: a sheet the list sits in isn't
                        // pulled down by it).
                        if (delta != 0f) scroller.dispatchPostScroll(Offset.Zero, Offset(0f, delta), NestedScrollSource.SideEffect)
                        place(k)
                    }
                }
                val scopeForRow = remember(k) {
                    object : ReorderScope {
                        override fun Modifier.dragHandle(): Modifier = this.pointerInput(k) { dragNow(k) }
                    }
                }
                scopeForRow.row(
                    item, i, held,
                    Modifier
                        // Where its layout is on screen: a move of the layout (reordered, or the page scrolled) is taken
                        // off the finger's travel, so the carried row stays under the finger.
                        .onGloballyPositioned { c ->
                            rowHeight = c.size.height
                            if (held) heldHeight = c.size.height
                            val y = c.positionInWindow().y
                            if (held && !lastY.isNaN() && y != lastY) { dragOffset -= (y - lastY); awaitingLayout = false }
                            lastY = y
                        }
                        .zIndex(if (held || lift > 0f) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (held) dragOffset else slides[k]?.value ?: 0f
                            val s = 1f + 0.03f * lift
                            scaleX = s; scaleY = s
                            shadowElevation = 14.dp.toPx() * lift
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                            clip = false
                        }
                        .then(if (lift > 0f) Modifier.liftedCard(lift) else Modifier)
                        .pointerInput(k) { dragAfterHold(k) },
                )
            }
        }
    }
}

/** The carried row's card: a shade lighter than the page, edged in the split light, both fading in as it lifts. */
@Composable
private fun Modifier.liftedCard(lift: Float): Modifier {
    val light = com.prism.music.ui.theme.PrismTokens.spectrum(com.prism.music.ui.theme.LocalIsDark.current)
    val card = MaterialTheme.colorScheme.surfaceContainerHighest
    return this.drawBehind {
        // Kept in from the sides, so it never runs into a sheet's or the screen's edge.
        val inset = 6.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
        val w = 1.dp.toPx()
        val at = Offset(inset, w / 2)
        val box = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - w)
        drawRoundRect(card, at, box, r, alpha = 0.94f * lift)
        drawRoundRect(light, at, box, r, alpha = lift, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    }
}

/** How far in from the screen's top and bottom a held row starts the page scrolling (past the bars over it). */
private const val EDGE_ZONE_DP = 150f
/** The fastest the page scrolls under a held row, a frame. */
private const val MAX_STEP_DP = 18f

/** The grip shown at a reorderable row's end: drag it to move the row straight away. */
@Composable
fun DragGrip(modifier: Modifier = Modifier, description: String? = tr(R.string.drag_to_move)) {
    Icon(
        com.prism.music.ui.theme.PrismIcons.DragHandle, description,
        modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

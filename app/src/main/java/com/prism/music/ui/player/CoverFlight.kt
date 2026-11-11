package com.prism.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.prism.music.data.model.hiRes
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Cover flies into place" (1.7.8, #199, an option in Player → Look): opening the player, the mini player's cover grows
 * and moves to where the player's cover sits, instead of the whole player sliding up. The two covers say where they
 * are ([mini], [player]); while it flies ([flying]) the player's own cover waits unseen, and a copy is drawn on top
 * ([CoverFlightLayer]).
 */
object CoverFlight {
    /** The mini player's cover, in the window. */
    var mini: Rect? = null
    /** The player's cover, once it's laid out (null while the player is closed). */
    var player by mutableStateOf<Rect?>(null)
    var flying by mutableStateOf(false)
        private set
    val progress = Animatable(0f)

    /** Where the mini player's cover is, kept as it moves. */
    fun Modifier.miniCover(): Modifier = onGloballyPositioned { mini = it.boundsInRoot() }

    /** Where the player's cover is, kept as it moves. */
    fun Modifier.playerCover(): Modifier = onGloballyPositioned { player = it.boundsInRoot() }

    /** The player is opening: the cover flies, if both ends are known within a moment. */
    suspend fun fly() {
        if (mini == null) return
        val to = withTimeoutOrNull(300) { snapshotFlow { player }.filterNotNull().first() } ?: return
        if (to.width <= 0f) return
        flying = true
        try {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
        } finally {
            flying = false
        }
    }

    /** #223: flying back to the mini player, which keeps its own cover unseen until it lands. */
    var returning by mutableStateOf(false)
        private set

    /** #223: whether closing the player flies the cover back. As the way in, it's never with Reduce motion (or Performance mode). */
    fun returnsWith(flight: Boolean, back: Boolean, reduceMotion: Boolean): Boolean = flight && back && !reduceMotion

    /** Both ends are known: the player's cover is the one showing (not a banner, a record or a video), and the mini player has been laid out. */
    fun canReturn(): Boolean = player != null && mini != null

    /** The player is closing: the cover goes back from wherever it is, and the player's own place stays known until then. */
    suspend fun flyBack() {
        if (!canReturn()) return
        flying = true
        returning = true
        try {
            progress.animateTo(0f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
        } finally {
            flying = false
            returning = false
        }
    }

    const val FLIGHT_MS = 420
}

/** The flying cover, over everything, while [CoverFlight.flying]. */
@Composable
fun CoverFlightLayer(thumbnail: String?) {
    if (!CoverFlight.flying) return
    val from = CoverFlight.mini ?: return
    val to = CoverFlight.player ?: return
    val density = LocalDensity.current
    val small = with(density) { 8.dp.toPx() }
    val big = with(density) { 14.dp.toPx() }
    Box(
        Modifier
            // Placed and sized every frame (a layout step only): the cover between the two places, square as it is.
            .layout { m, c ->
                val f = CoverFlight.progress.value
                // Going back, the mini player is on its way in as well: wherever it's got to.
                @Suppress("NAME_SHADOWING") val from = if (CoverFlight.returning) CoverFlight.mini ?: from else from
                val w = (from.width + (to.width - from.width) * f).roundToInt().coerceAtLeast(1)
                val h = (from.height + (to.height - from.height) * f).roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) {
                    p.place((from.left + (to.left - from.left) * f).roundToInt(), (from.top + (to.top - from.top) * f).roundToInt())
                }
            }
            .graphicsLayer {
                clip = true
                shape = RoundedCornerShape(small + (big - small) * CoverFlight.progress.value)
            },
    ) {
        // The mini player's picture (already loaded) under the large one, which may still be on its way.
        coil3.compose.AsyncImage(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        coil3.compose.AsyncImage(hiRes(thumbnail, 1200), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
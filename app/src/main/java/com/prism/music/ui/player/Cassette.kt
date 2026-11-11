package com.prism.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.min

/**
 * The Cassette player (Settings → Player → Layout, 1.7.4): the song's cover as a tape's label, its two reels turning in
 * the window while the music plays and coasting to a stop on pause. The reels turn by rotating their own layers, so a
 * turning frame costs a transform, not a redraw. Still with Reduce motion, and while [hold] (a sheet over the player).
 */
@Composable
fun CassetteTape(
    art: ImageRequest,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    hold: Boolean = false,
    /** 0 as the player, 1 shrunk into the header. */
    collapsed: () -> Float = { 0f },
    onArtLoaded: () -> Unit = {},
) {
    val still = com.prism.music.ui.theme.LocalUi.current.reduceMotion
    val angle = remember { mutableFloatStateOf(0f) }
    val speed = remember { FloatArray(1) }
    val turning = isPlaying && !hold && !still
    LaunchedEffect(turning) {
        if (!turning && speed[0] < 0.5f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val target = if (turning) DEGREES_A_SECOND else 0f
            speed[0] += (target - speed[0]) * min(1f, dt * if (turning) 3f else 1.6f)
            angle.floatValue = (angle.floatValue + speed[0] * dt) % 360f
            if (!turning && speed[0] < 0.5f) { speed[0] = 0f; break }
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = maxWidth
        val h = w * CASSETTE_ASPECT
        val corner = w * 0.045f
        Box(
            Modifier.size(w, h).shadow(12.dp, RoundedCornerShape(corner)).clip(RoundedCornerShape(corner))
                .background(Brush.verticalGradient(listOf(Color(0xFF2B2B2B), Color(0xFF171717)))),
        ) {
            // The label: the cover across the top, the window over its middle.
            Box(
                Modifier.padding(start = w * 0.05f, end = w * 0.05f, top = h * 0.07f).fillMaxWidth().height(h * 0.64f)
                    .clip(RoundedCornerShape(w * 0.022f)).background(Color(0xFF2A2A2A)),
            ) {
                AsyncImage(
                    art, null, contentScale = ContentScale.Crop,
                    onState = { if (it is coil3.compose.AsyncImagePainter.State.Success) onArtLoaded() },
                    modifier = Modifier.fillMaxSize(),
                )
                // A band under the window, as a printed label has.
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(h * 0.34f).background(Color.Black.copy(alpha = 0.28f)))
                Box(
                    Modifier.align(Alignment.Center).size(w * 0.6f, h * 0.27f).clip(RoundedCornerShape(50))
                        .background(Color(0xFF0C0C0C).copy(alpha = 0.86f)),
                ) {
                    // The tape running between the reels, under them.
                    Canvas(Modifier.fillMaxSize()) {
                        val y = size.height * 0.5f
                        drawRect(Color(0xFF2E2E2E), Offset(size.width * 0.2f, y - size.height * 0.36f), Size(size.width * 0.6f, size.height * 0.08f))
                    }
                    val reel = h * 0.25f
                    Reel(angle, Modifier.align(Alignment.CenterStart).offset(x = w * 0.6f * 0.08f).size(reel))
                    Reel(angle, Modifier.align(Alignment.CenterEnd).offset(x = -w * 0.6f * 0.08f).size(reel))
                }
            }
            // The foot: the trapezoid with the tape guides and holes; screws in the corners.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - 2f * collapsed()).coerceIn(0f, 1f) }) {
                val footTop = size.height * 0.78f
                val foot = Path().apply {
                    moveTo(size.width * 0.24f, footTop)
                    lineTo(size.width * 0.76f, footTop)
                    lineTo(size.width * 0.80f, size.height)
                    lineTo(size.width * 0.20f, size.height)
                    close()
                }
                drawPath(foot, Color(0xFF101010))
                val holeY = footTop + (size.height - footTop) * 0.5f
                listOf(0.33f, 0.67f).forEach { x -> drawCircle(Color.Black, size.height * 0.035f, Offset(size.width * x, holeY)) }
                listOf(0.45f, 0.55f).forEach { x ->
                    drawRoundRect(Color(0xFF2F2F2F), Offset(size.width * x - size.height * 0.02f, holeY - size.height * 0.02f), Size(size.height * 0.04f, size.height * 0.04f), CornerRadius(2f))
                }
                val s = size.height * 0.03f
                listOf(Offset(0.04f, 0.06f), Offset(0.96f, 0.06f), Offset(0.04f, 0.94f), Offset(0.96f, 0.94f)).forEach { o ->
                    val at = Offset(size.width * o.x, size.height * o.y)
                    drawCircle(Color(0xFF565656), s, at)
                    drawLine(Color(0xFF2A2A2A), at - Offset(s * 0.6f, 0f), at + Offset(s * 0.6f, 0f), s * 0.35f)
                }
            }
        }
    }
}

/** A reel: the wound tape, the white hub and its teeth, turning by [angle]. */
@Composable
private fun Reel(angle: FloatState, modifier: Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = angle.floatValue }) {
        val r = size.minDimension / 2
        drawCircle(Color(0xFF2E2E2E), r)
        drawCircle(Color(0xFF3B3B3B), r * 0.92f)
        drawCircle(Color(0xFFE9E9E9), r * 0.5f)
        repeat(6) { i ->
            rotate(i * 60f) {
                drawRoundRect(Color(0xFF161616), Offset(center.x - r * 0.06f, center.y - r * 0.36f), Size(r * 0.12f, r * 0.14f), CornerRadius(r * 0.03f))
            }
        }
        drawCircle(Color(0xFF161616), r * 0.16f)
    }
}

/** A tape's height to its width (a real cassette is about 100 × 64 mm). */
const val CASSETTE_ASPECT = 0.64f

/** The reels turn faster than a record: a tape's hubs spin round a couple of times a second. */
private const val DEGREES_A_SECOND = 240f

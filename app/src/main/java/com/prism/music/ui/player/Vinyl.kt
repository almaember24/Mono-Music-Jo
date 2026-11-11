package com.prism.music.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.min

/**
 * The Vinyl player (Settings → Player → Layout, 1.7.4): the song's cover as the label of a record that turns while the
 * music plays, easing up to speed and slowing to a stop on pause, as a turntable does; with [tonearm], the arm swings
 * onto the record as it plays and lifts off when it stops.
 *
 * The record turns by rotating its layer (drawn once, grooves and label), so a turning frame costs the GPU a transform,
 * not a redraw or a recomposition. Still with Reduce motion, and while [hold] (a sheet over the player).
 */
@Composable
fun VinylRecord(
    art: ImageRequest,
    isPlaying: Boolean,
    tonearm: Boolean,
    modifier: Modifier = Modifier,
    hold: Boolean = false,
    /** 0 as the player, 1 shrunk into the header (the arm goes as it shrinks). */
    collapsed: () -> Float = { 0f },
    onArtLoaded: () -> Unit = {},
) {
    val still = com.prism.music.ui.theme.LocalUi.current.reduceMotion
    val angle = remember { mutableFloatStateOf(0f) }
    // Degrees a second now, kept across play and pause so it eases up and down rather than jumping.
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
            // Up to speed in about a second; a gentler run-down, as a platter's weight carries it.
            speed[0] += (target - speed[0]) * min(1f, dt * if (turning) 2.2f else 1.1f)
            angle.floatValue = (angle.floatValue + speed[0] * dt) % 360f
            if (!turning && speed[0] < 0.5f) { speed[0] = 0f; break }
        }
    }
    Box(modifier) {
        // The record, turning.
        Box(
            Modifier.fillMaxSize().shadow(12.dp, CircleShape)
                // Drawn once into a texture of its own; turning only rotates that (no redraw of the grooves and label).
                .graphicsLayer { rotationZ = angle.floatValue; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .clip(CircleShape)
                .drawWithCache {
                    val r = size.minDimension / 2
                    val c = Offset(size.width / 2, size.height / 2)
                    // Fine grooves: rings of a slightly lighter black, with a wider band between "tracks".
                    val rings = (r * 0.44f).let { inner -> ((r - inner) / 2.4f).toInt().coerceIn(12, 90) }
                    val sheen = Brush.sweepGradient(
                        0f to Color.White.copy(alpha = 0.0f), 0.12f to Color.White.copy(alpha = 0.07f), 0.25f to Color.White.copy(alpha = 0.0f),
                        0.5f to Color.White.copy(alpha = 0.0f), 0.62f to Color.White.copy(alpha = 0.07f), 0.75f to Color.White.copy(alpha = 0.0f),
                        1f to Color.White.copy(alpha = 0.0f), center = c,
                    )
                    onDrawBehind {
                        drawCircle(Color(0xFF0E0E0E), r, c)
                        for (i in 0 until rings) {
                            val rr = r * 0.46f + (r * 0.52f) * i / rings
                            val track = i % 18 == 0
                            drawCircle(Color.White.copy(alpha = if (track) 0.02f else 0.045f), rr, c, style = Stroke(if (track) 2.2f else 0.8f))
                        }
                        // Light catching the grooves: turns with the record, as it does on a real one under a lamp.
                        drawCircle(sheen, r, c)
                        drawCircle(Color.Black.copy(alpha = 0.6f), r, c, style = Stroke(r * 0.015f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The label: the cover, round.
            AsyncImage(
                art, null, contentScale = ContentScale.Crop,
                onState = { if (it is coil3.compose.AsyncImagePainter.State.Success) onArtLoaded() },
                modifier = Modifier.fillMaxSize(LABEL).clip(CircleShape).background(Color(0xFF2A2A2A)),
            )
            Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF0B0B0B)))
        }
        if (tonearm) {
            val lowered by animateFloatAsState(if (isPlaying) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 60f), label = "tonearm")
            Tonearm({ lowered }, collapsed, Modifier.fillMaxSize())
        }
    }
}

/** The tonearm: its pivot off the record's top right, resting beside it, swung in over the grooves by [lowered] (0–1). */
@Composable
private fun Tonearm(lowered: () -> Float, collapsed: () -> Float, modifier: Modifier) {
    Canvas(modifier.graphicsLayer { alpha = (1f - 2f * collapsed()).coerceIn(0f, 1f) }) {
        val w = size.minDimension
        val pivot = Offset(size.width - w * 0.04f, w * 0.06f)
        val length = w * 0.66f
        rotate(REST + (PLAYING - REST) * lowered(), pivot) {
            val end = Offset(pivot.x, pivot.y + length)
            val elbow = Offset(pivot.x - w * 0.03f, pivot.y + length * 0.78f)
            val metal = Brush.linearGradient(listOf(Color(0xFFE8E8E8), Color(0xFF9B9B9B)), pivot, end)
            drawLine(Color.Black.copy(alpha = 0.35f), pivot + Offset(3f, 4f), elbow + Offset(3f, 4f), w * 0.022f, StrokeCap.Round)
            drawLine(metal, pivot, elbow, w * 0.018f, StrokeCap.Round)
            drawLine(metal, elbow, end, w * 0.016f, StrokeCap.Round)
            // The headshell, the cartridge under it.
            rotate(-18f, end) {
                drawRoundRect(
                    Color(0xFF3B3B3B), Offset(end.x - w * 0.028f, end.y - w * 0.01f), Size(w * 0.056f, w * 0.085f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.01f),
                )
            }
        }
        // The pivot's base, over the arm.
        drawCircle(Color(0xFF2D2D2D), w * 0.065f, pivot)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFDBDBDB), Color(0xFF7D7D7D)), pivot, w * 0.04f), w * 0.04f, pivot)
    }
}

/** One turn in 3.6 s: slower than a real 33⅓, calmer to look at. */
private const val DEGREES_A_SECOND = 100f
/** The label's share of the record's width. */
private const val LABEL = 0.42f
/** The arm's angle at rest (beside the record) and playing (its needle in the outer grooves). */
private const val REST = -7f
private const val PLAYING = 24f

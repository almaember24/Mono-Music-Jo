package com.prism.music.ui.components

import com.prism.music.ui.theme.Mono
import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.prism.music.data.model.Song
import com.prism.music.ui.theme.PrismTokens

/**
 * A cover for a music file on the phone that has none: the song's initial, large and light, on a deep color of its own
 * (the same every time for the same song), with Prism's split light along the bottom as on its other made covers.
 */
@Composable
fun LocalCover(song: Song, modifier: Modifier = Modifier) {
    val (top, bottom) = remember(song.id, song.title) { localCoverColors(song.title + "\u0000" + song.artistText) }
    val initial = remember(song.title) { song.title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪" }
    BoxWithConstraints(modifier.background(Brush.linearGradient(listOf(top, bottom)))) {
        val size = maxWidth
        Text(
            initial,
            style = TextStyle(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.ExtraLight, color = Color.White.copy(alpha = 0.92f)),
            modifier = Modifier.align(Alignment.Center),
        )
        if (size > 120.dp) Text(
            song.artistText.ifBlank { tr(R.string.on_this_phone) },
            style = TextStyle(fontSize = (size.value * 0.045f).coerceAtLeast(11f).sp, color = Color.White.copy(alpha = 0.7f)),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = size * 0.07f, end = size * 0.07f, bottom = size * 0.07f),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height((size.value * 0.012f).coerceIn(2f, 5f).dp).background(PrismTokens.spectrum))
    }
}

/** Two deep colors for [key], from a hue of its own; dark enough for white type (see the player's backdrop). */
internal fun localCoverColors(key: String): Pair<Color, Color> {
    val hue = ((key.hashCode().toLong() and 0xffFFFFFFL) % 360L).toFloat()
    val top = ColorUtils.HSLToColor(floatArrayOf(hue, if (Mono.ENABLED) 0f else 0.55f, 0.34f))
    val bottom = ColorUtils.HSLToColor(floatArrayOf((hue + 38f) % 360f, if (Mono.ENABLED) 0f else 0.6f, 0.18f))
    return Color(top) to Color(bottom)
}

/** A full-size [LocalCover] filling its slot. */
@Composable
fun LocalCoverFill(song: Song) = LocalCover(song, Modifier.fillMaxSize())

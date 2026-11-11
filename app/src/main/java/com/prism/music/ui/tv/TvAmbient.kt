package com.prism.music.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.ui.components.Artwork

/**
 * The ambient screen (TV, and the phone's desk mode #265): after a while with no remote while music plays, the
 * screen dims to the cover, the song and the line being sung on near-black, instead of the TV's own screensaver
 * covering the lyrics. Burn-in safe: everything drifts slowly ([drift], a few dp per minute) and nothing is bright
 * white. Any key goes back to where the listener was; the key itself does nothing else.
 */
@Composable
fun TvAmbientScreen(now: TvNow, line: String?, clock: String, modifier: Modifier = Modifier, drift: DpOffset = DpOffset.Zero, accent: Color? = null) {
    val ink = Color(0xFFD9D9D9)
    Box(modifier.fillMaxSize().tvBackdrop(accent?.copy(alpha = 0.5f), Color(0xFF050505)).testTag(TAG_AMBIENT)) {
        Text(clock, Modifier.align(Alignment.TopEnd).padding(TvSafe).offset(drift.x, drift.y), style = MaterialTheme.typography.headlineMedium, color = ink.copy(alpha = 0.6f))
        Column(Modifier.align(Alignment.Center).offset(drift.x, drift.y), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(now.art, Modifier.size(240.dp), shape = RoundedCornerShape(16.dp), size = 544)
            Spacer(Modifier.height(24.dp))
            Text(now.title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(now.artist, style = MaterialTheme.typography.titleLarge, color = ink.copy(alpha = 0.6f), maxLines = 1)
            if (line != null) {
                Spacer(Modifier.height(28.dp))
                Text(line, Modifier.widthIn(max = 760.dp), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Medium), color = ink.copy(alpha = 0.85f), textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

const val TAG_AMBIENT = "tv_ambient"

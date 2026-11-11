package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalHazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * One of the Glass choices (#252) on its own small page: the cover playing and some lines, with a real piece of that glass
 * over them, drawn by the same [GlassSurface] as the tab bar, so it answers the sliders too. It only draws again when
 * something on it changes.
 */
@Composable
fun GlassSample(choice: GlassChoice, modifier: Modifier = Modifier) {
    val s = LocalAppSettings.current
    val song by LocalContainer.current.player.currentSong.collectAsState()
    val art = song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
    val v = LookChoices.glassValues(choice)
    val shown = s.copy(liquidGlass = v.liquid, frostedGlass = v.frosted ?: false, glassKind = v.kind ?: s.glassKind)
    val page = rememberHazeState()
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(10.dp))) {
        Box(Modifier.fillMaxSize().hazeSource(page).background(scheme.background)) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E))))) {
                    if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                // Lines of text, sharp, to show what the glass does to what passes behind it.
                Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(0.9f, 0.6f, 0.8f, 0.5f, 0.9f, 0.7f).forEach { w ->
                        Box(Modifier.fillMaxWidth(w).height(5.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.85f)))
                    }
                }
            }
        }
        CompositionLocalProvider(LocalHazeState provides page, LocalAppSettings provides shown) {
            GlassSurface(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(22.dp), elevation = 3.dp,
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { Box(Modifier.size(12.dp).clip(CircleShape).background(scheme.onSurface)) }
                }
            }
        }
    }
}

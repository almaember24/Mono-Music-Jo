package com.prism.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.tr
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.RestingLine
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalLyricsFont
import com.prism.music.ui.theme.PrismIcons

/**
 * Settings → Lyrics → Lyrics style (TV, 1.8.0, the owner's pick): the phone's lyrics styles (Prism, Classic karaoke,
 * Bold card, Glow, Typewriter, Calm), picked as the phone's "Start from" picks them, so the phone and the TV agree. The
 * focused style's live example is pinned on the left: three made-up lines, the middle one sung over and over in that
 * style. Light on a TV's GPU: no offscreen layer (Glow's halo is a soft text shadow here, not a blurred copy), and it
 * moves only while this panel is open (held still with Reduce motion on).
 */
@Composable
fun BoxScope.TvLyricsStyleChoice(current: LyricsStyle, onPick: (LyricsStyle) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(current) }
    Row(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)).testTag(TAG_LYRICS_STYLE)) {
        TvLyricsStyleExample(shown, Modifier.weight(0.55f).fillMaxHeight())
        Column(
            // Solid: Settings' rows didn't belong showing through the choices.
            Modifier.weight(0.45f).fillMaxHeight().tvGlass(TvShapes.panelStart, TvPanelFill.copy(alpha = 1f))
                .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr(R.string.lyrics_style), style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, modifier = Modifier.padding(bottom = 6.dp))
            for (style in LyricsStyle.entries) {
                Surface(
                    onClick = { onPick(style) },
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) shown = style }
                        .tvItem("$TAG_LYRICS_STYLE/${style.name.lowercase()}", entry = style == current),
                    shape = ClickableSurfaceDefaults.shape(TvShapes.card),
                    colors = ClickableSurfaceDefaults.colors(containerColor = TvGlassFill, focusedContainerColor = scheme.onSurface.copy(alpha = 0.16f)),
                    border = ClickableSurfaceDefaults.border(focusedBorder = tvFocusBorder(TvShapes.card, TV_RING_THIN)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(style.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                            Text(style.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
                        }
                        if (style == current) Icon(PrismIcons.Check, null, Modifier.size(22.dp), tint = scheme.onSurface)
                    }
                }
            }
        }
    }
}

/** How long the example's sung line takes, then rests, before it's sung again. */
private const val EXAMPLE_LOOP_MS = 3_800L

/** Where the example is held with Reduce motion on: part-way through the line (as the phone's own example). */
private const val EXAMPLE_HELD_MS = 1_300L

/**
 * The lyrics in [style] as the TV draws them: the line before and after resting, the middle one sung word by word, on
 * the lyrics' ground (Bold card's color for Bold card).
 */
@Composable
private fun TvLyricsStyleExample(style: LyricsStyle, modifier: Modifier) {
    val settings = LocalAppSettings.current
    val prefs = remember(settings.lyricsLook, style) { settings.copy(lyricsLook = settings.lyricsLook.startFrom(style)) }
    val look = rememberLyricsLook(prefs, null)
    val axis = remember(settings.lyricsFont, settings.fontChoice) { tvLyricsAxisChoice(settings)?.let(AppFonts::axis) }
    val clock = remember { mutableLongStateOf(EXAMPLE_HELD_MS) }
    // Only while the panel is open (this leaves the composition with it); still with Reduce motion on.
    if (!settings.reduceMotion) LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t -> clock.longValue = (t - start) % EXAMPLE_LOOP_MS }
    }
    val centred = settings.lyricsAlign == LyricsAlign.CENTER
    val base = TextStyle(
        fontSize = 30.sp * look.sizeScale, lineHeight = 38.sp * look.sizeScale, fontWeight = FontWeight(look.weight),
        fontFamily = LocalLyricsFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
    )
    val sung = remember(look.uppercase) {
        val words = Regex("\\S+\\s*").findAll(tr(R.string.every_color_finds_its_way)).map { it.value }.toList()
        look.shown(LyricLine(0, words.joinToString(""), 2_800, words.mapIndexed { k, w -> LyricWord(k * 520L, k * 520L + 480L, w) }))
    }
    val ground = look.card?.let { Brush.verticalGradient(listOf(it, lerp(it, Color.Black, 0.35f))) }
        ?: Brush.verticalGradient(listOf(Color(0xFF1B1B1B), Color(0xFF0B0B0B)))
    val example = tvExampleLook(look)
    Box(modifier.background(ground).padding(horizontal = 48.dp).testTag("$TAG_LYRICS_STYLE/example/${style.name.lowercase()}"), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((22 * look.spacing).dp)) {
            RestingLine(look.shown(LyricLine(-1, tr(R.string.somewhere_a_light_is_turning_on))), base, look, 0.7f * look.othersAlpha, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth()) {
                // Glow: a soft light behind the line, from a text shadow (the lyrics page blurs a copy; that's an
                // offscreen layer, too much to keep running in Settings on a TV).
                if (look.halo) Text(
                    sung.text, Modifier.fillMaxWidth(),
                    style = base.copy(fontWeight = FontWeight(look.sungWeight), shadow = Shadow(look.sungSolid.copy(alpha = 0.85f), blurRadius = 18f)),
                    color = Color.Transparent,
                )
                SungLine(sung, clock, base, example, axis, Modifier.fillMaxWidth().testTag("$TAG_LYRICS_STYLE/sung"), onCard = look.card != null)
            }
            RestingLine(look.shown(LyricLine(-1, tr(R.string.we_follow_the_line_of_the))), base, look, 0.7f * look.othersAlpha, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The look the example's sung line is drawn in: [look] without Glow's blurred copy (its light is a shadow behind it
 * instead), its words a step bolder as Glow sets them.
 */
internal fun tvExampleLook(look: LyricsLook): LyricsLook = if (!look.halo) look else look.copy(halo = false, weight = look.weight + 100)

/** Test tags: Settings → Lyrics style ("tv_lyrics_style/glow"), its example ("tv_lyrics_style/example/glow"). */
const val TAG_LYRICS_STYLE = "tv_lyrics_style"

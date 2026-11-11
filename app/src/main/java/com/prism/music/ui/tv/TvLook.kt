package com.prism.music.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.prism.music.ui.player.drawStillMesh
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import com.prism.music.ui.components.FrostedBackdrop
import com.prism.music.ui.components.rememberArtPalette
import com.prism.music.ui.components.rememberFrost
import com.prism.music.ui.player.ArtworkMeshBackdrop
import com.prism.music.ui.player.rememberArtworkMesh
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTokens

/*
 * Prism TV's look (1.8.0, after the owner's TV): the phone's Aura × Spectrum on a TV. The frost of what's playing (or
 * played last) behind the places, the cover's own frost behind its album or artist page, the cover-made mesh behind
 * the player; frosted glass panels; and the split light as the one focus mark (a spectrum ring round what has focus).
 *
 * Made for a mid-range TV's GPU: the frost is made once into a picture of the screen's size, from a small picture
 * blurred once in software (cached per cover), and shown as that picture; never blurred or drawn live (on the owner's
 * TV that took about 9 ms of every frame); nothing moves on its own while the remote is down.
 */

/** Soft corners, as the phone's ([PrismTokens.Radius]). */
object TvShapes {
    val card = RoundedCornerShape(PrismTokens.Radius.art)
    val panel = RoundedCornerShape(PrismTokens.Radius.large)
    val art = RoundedCornerShape(18.dp)
    /** A side panel's (the queue, a menu): soft on its leading edge, against the screen's edge on the other. */
    val panelStart = RoundedCornerShape(topStart = PrismTokens.Radius.large, bottomStart = PrismTokens.Radius.large)
}

/** The focus mark's width on cards and on buttons. */
val TV_RING = 3.dp
val TV_RING_THIN = 2.dp

/** The split light round what has focus (the phone's play ring, round any shape). */
@Composable
fun tvRing(): Brush = PrismTokens.spectrumRing()

/** The split light along a line (progress, the playing title, the current place's mark). */
@Composable
fun tvLight(): Brush = PrismTokens.spectrum(dark = true)

/** A focused card's or button's border: the split light round it. */
@Composable
fun tvFocusBorder(shape: Shape, width: androidx.compose.ui.unit.Dp = TV_RING): Border = Border(BorderStroke(width, tvRing()), shape = shape)

/** Frosted glass's fill on a TV (dark rooms: a light veil over the frost behind). */
val TvGlassFill = Color.White.copy(alpha = 0.10f)
/** Panels over a page (the queue, a menu, a setting's choices): darker glass, so what's on them reads. */
val TvPanelFill = Color(0xFF0D0D0D).copy(alpha = 0.86f)
/**
 * Side panels lying over a page's own words (the player's queue and ⋯): nearly solid, or the title and "Up next" under
 * them read through their rows (Reddit, 1.8.1: "Up next" over the list).
 */
val TvPanelOverPage = Color(0xFF0D0D0D)

/** Frosted glass: [fill], with the phone's light top edge. */
fun Modifier.tvGlass(shape: Shape, fill: Color = TvGlassFill): Modifier = this
    .background(fill, shape)
    .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.02f))), shape)

/**
 * The current place's mark (the side navigation, Settings' groups): a short bar at its leading edge with the split
 * light running down it, as the phone's tab mark (White light: white). [light] is kept for callers; the bar reads the
 * light itself, top to bottom (a bar this thin across a left-to-right light would show only its red end).
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.tvCurrentMark(on: Boolean, light: Brush? = null): Modifier = if (!on) this else drawWithContent {
    drawContent()
    val h = size.height * 0.5f
    val top = (size.height - h) / 2
    val run = Brush.verticalGradient(*PrismTokens.spectrumColors(dark = true).toTypedArray(), startY = top, endY = top + h)
    drawRoundRect(run, topLeft = Offset(4.dp.toPx(), top), size = Size(4.dp.toPx(), h), cornerRadius = CornerRadius(2.dp.toPx()))
}

/**
 * The frost behind Prism TV's places (Home, Search, Library, Settings): the phone's Ambient frost, from the cover of
 * what's playing or played last. Drawn once into its own layer; a new song's cover replaces it.
 */
@Composable
fun TvAura(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    val recent by c.library.recent.collectAsState()
    TvAuraFrom(current?.thumbnail ?: recent.firstOrNull()?.thumbnail, modifier)
}

/** [TvAura] from the cover at [url] (the design pages draw it without the app around them). */
@Composable
fun TvAuraFrom(url: String?, modifier: Modifier = Modifier) {
    val frost = rememberFrost(url)
    val palette = rememberArtPalette(url)
    // Made once into a picture (a TV's GPU drew it live at half of each frame's time), and shown as that picture.
    FrostedBackdrop(frost, palette, TV_AURA_HEIGHT, { 0f }, modifier.fillMaxSize(), parallax = 0f, ambient = true, baked = true)
}

/**
 * A backdrop that doesn't move: drawn into a layer of its own, whose pixels the TV keeps and puts back each frame (a
 * focus move redraws what moved, not the frost and its grain under it).
 */
fun Modifier.tvStill(): Modifier = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }

/** How much of the screen's top the frost's picture covers before its colors run on down (a TV's whole height). */
private val TV_AURA_HEIGHT = 540.dp

/**
 * An album's, playlist's or artist's page: its own picture's frost behind it, held to the Aura's deep tone (the
 * picture itself sits beside the text on a TV, not above it, so the whole page is where text reads). Drawn once into
 * its own layer.
 */
@Composable
fun TvPictureBackdrop(url: String?, modifier: Modifier = Modifier) {
    TvAuraFrom(url, modifier)
}

/**
 * The player's (and lyrics') backdrop: the phone's cover-made mesh, still, without a live blur; until the mesh is made
 * (a moment after a new cover), the cover's frost, so the player is never flat black.
 */
@Composable
fun TvMeshBackdrop(url: String?, modifier: Modifier = Modifier) {
    val mesh = rememberArtworkMesh(url)
    // Settings → Look → Background (1.8.1): darker, as the listener likes it behind the lyrics.
    val shade = com.prism.music.data.prefs.TvPrefs.coverShade(LocalAppSettings.current.tv.backdropDim)
    if (mesh == null) androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        TvAuraFrom(url)
        // A moment, till the mesh is made.
        if (shade > 0f) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade)))
    }
    // Made once into a picture, as the frost is (drawn live it cost the TV's GPU most of each frame); the shade is made
    // into it too, so it costs nothing as the lyrics move.
    else TvBaked(mesh to shade, modifier) {
        drawStillMesh(mesh)
        if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
    }
}

/**
 * A backdrop made once into a picture of its size (off the main thread) from [draw], again whenever [key] changes, and
 * shown as that picture; a new one fades in over the old.
 */
@Composable
fun TvBaked(key: Any, modifier: Modifier = Modifier, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    var size by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val latest by androidx.compose.runtime.rememberUpdatedState(draw)
    val picture by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, key, size, density) {
        if (size.width <= 0 || size.height <= 0) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { com.prism.music.ui.components.bakeFrost(size, density, latest) }.getOrNull()
        } ?: value
    }
    androidx.compose.animation.Crossfade(
        picture, modifier.fillMaxSize().onSizeChanged { size = it },
        animationSpec = androidx.compose.animation.core.tween(600), label = "baked",
    ) { p -> androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { if (p != null) drawImage(p) else drawRect(com.prism.music.ui.player.PlayerFallbackBackdrop) } }
}

/**
 * Settings → Colors (TV): the split light (Spectrum, as Prism comes) or White light (a white ring and white lines:
 * monochrome highlights). The phone's own setting ([com.prism.music.data.prefs.AppSettings.lightStyle]), so the phone
 * and the TV agree. Each choice shows a focused card and a progress line in its light; OK picks it.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.TvLightChoice(
    current: com.prism.music.data.prefs.LightStyle,
    onPick: (com.prism.music.data.prefs.LightStyle) -> Unit,
) {
    val scheme = androidx.tv.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Transparent, scheme.background.copy(alpha = 0.85f)))))
    androidx.compose.foundation.layout.Column(
        Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f).tvGlass(TvShapes.panelStart, TvPanelFill)
            .padding(horizontal = 32.dp, vertical = 27.dp).testTag(TAG_LIGHT),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        androidx.tv.material3.Text(com.prism.music.tr(com.prism.music.R.string.colors), style = androidx.tv.material3.MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            for (style in com.prism.music.data.prefs.LightStyle.entries) {
                val white = style == com.prism.music.data.prefs.LightStyle.WHITE
                androidx.tv.material3.Surface(
                    onClick = { onPick(style) },
                    modifier = Modifier.weight(1f).tvItem("$TAG_LIGHT/${style.name.lowercase()}", entry = style == current),
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvShapes.card),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = TvGlassFill, focusedContainerColor = scheme.onSurface.copy(alpha = 0.16f)),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(focusedBorder = tvFocusBorder(TvShapes.card, TV_RING_THIN)),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(18.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                        TvLightExample(white)
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.tv.material3.Text(style.label, style = androidx.tv.material3.MaterialTheme.typography.titleMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
                            if (style == current) androidx.tv.material3.Icon(com.prism.music.ui.theme.PrismIcons.Check, null, Modifier.size(22.dp), tint = scheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** A focused card and a progress line in the split light ([white] false) or White light, whichever is in use now. */
@Composable
private fun TvLightExample(white: Boolean) {
    val ring = Brush.sweepGradient(PrismTokens.ringOf(white, PrismTokens.whiteAccent, PrismTokens.light))
    val line = Brush.horizontalGradient(*PrismTokens.runOf(white, PrismTokens.whiteAccent, PrismTokens.light, dark = true).toTypedArray())
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(96.dp).border(TV_RING, ring, TvShapes.card).padding(6.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF999999), Color(0xFFA5A5A5))), TvShapes.card),
        )
        androidx.compose.foundation.layout.Box(Modifier.width(150.dp).height(6.dp).background(Color.White.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(0.55f).height(6.dp).background(line, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

/** Test tags: Settings → Colors' choices ("tv_light/spectrum", "tv_light/white"). */
const val TAG_LIGHT = "tv_light"
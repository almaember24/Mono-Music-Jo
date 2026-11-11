package com.prism.music.ui.components

import com.prism.music.ui.theme.Mono
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.prism.music.ui.theme.ArtistType
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.SignatureEffect

/** The text style for a signature at [sizeSp], with its effect (outline, glow, gradient) in the page's accent. */
@Composable
fun signatureStyle(type: ArtistType, sizeSp: Float, align: TextAlign = TextAlign.Center): TextStyle {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = LocalIsDark.current
    val density = LocalDensity.current
    // Standard is the plain headline, so it's in the font picked for Prism (#139: it was Google Sans Flex on a Pixel and
    // Android's own font elsewhere, whatever the listener had chosen). The other styles are typefaces of their own.
    val appFont = MaterialTheme.typography.headlineLarge.fontFamily
    val base = TextStyle(
        fontFamily = if (type.key == com.prism.music.ui.theme.ArtistTypography.standard.key && appFont != null) appFont else type.family,
        fontWeight = type.weight,
        fontStyle = if (type.italic) FontStyle.Italic else FontStyle.Normal,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * type.lineHeight).sp,
        letterSpacing = type.tracking.em,
        textAlign = align,
        color = ink,
        shadow = when (type.effect) {
            SignatureEffect.GLOW -> Shadow(accent.copy(alpha = 0.95f), blurRadius = sizeSp * 0.9f)
            else -> Shadow(Color.Black.copy(alpha = if (dark) 0.32f else 0f), blurRadius = 18f)
        },
        drawStyle = if (type.effect == SignatureEffect.OUTLINE) {
            Stroke(width = with(density) { (sizeSp * 0.03f).sp.toPx() }.coerceAtLeast(2f), join = StrokeJoin.Round)
        } else null,
    )
    return if (type.effect == SignatureEffect.GRADIENT) {
        // The accent, drifting a little round the color wheel.
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(accent.toArgb(), it) }
        val second = Color(ColorUtils.HSLToColor(floatArrayOf((hsl[0] + 48f) % 360f, if (Mono.ENABLED) 0f else hsl[1], (hsl[2] + if (dark) 0.08f else -0.06f).coerceIn(0.2f, 0.85f))))
        base.copy(brush = Brush.linearGradient(listOf(accent, second, ink.copy(alpha = 0.9f))))
    } else base
}

/**
 * An artist's name in their signature: shrunk until it fits in two lines, then written on from
 * left to right once [ready] (the typeface depends on the genre, which may take a moment).
 * Long-pressing it calls [onLongPress] (to pick another style).
 */
@Composable
fun ArtistSignature(name: String, type: ArtistType, ready: Boolean, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val reduceMotion = LocalUi.current.reduceMotion
    var scale by remember(name, type) { mutableFloatStateOf(1f) }
    var fits by remember(name, type) { mutableStateOf(false) }
    // Where the ink is (px), so the reveal sweeps the letters rather than the empty margins.
    var inkLeft by remember(name, type) { mutableFloatStateOf(0f) }
    var inkRight by remember(name, type) { mutableFloatStateOf(0f) }
    val reveal = remember(name, type.key) { Animatable(0f) }
    LaunchedEffect(ready, fits, type.key) {
        if (!ready || !fits) return@LaunchedEffect
        if (reduceMotion) reveal.snapTo(1f) else reveal.animateTo(1f, tween(1_300, easing = FastOutSlowInEasing))
    }
    val text = if (type.caps) name.uppercase() else name
    Text(
        text,
        modifier
            .then(if (onLongPress != null) Modifier.pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) } else Modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                if (!fits) return@drawWithContent
                drawContent()
                val r = reveal.value
                if (r < 1f) {
                    val edge = size.width * 0.16f
                    val from = inkLeft - edge
                    val x = from + (inkRight + edge - from) * r
                    drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x - edge, endX = x), blendMode = BlendMode.DstIn)
                }
            },
        maxLines = 2,
        softWrap = true,
        style = signatureStyle(type, type.size * scale),
        onTextLayout = { r ->
            if ((r.hasVisualOverflow || r.lineCount > 2 || (r.lineCount == 2 && name.length < 12)) && scale > 0.45f) scale *= 0.9f
            else {
                inkLeft = (0 until r.lineCount).minOf { r.getLineLeft(it) }
                inkRight = (0 until r.lineCount).maxOf { r.getLineRight(it) }
                fits = true
            }
        },
    )
}

/** A one-line sample of a signature, for the style picker. */
@Composable
fun SignatureSample(name: String, type: ArtistType, modifier: Modifier = Modifier) {
    val size = (type.size * 0.62f).coerceAtMost(34f)
    Text(
        if (type.caps) name.uppercase() else name, modifier,
        style = signatureStyle(type, size, TextAlign.Start).let { if (type.effect == SignatureEffect.GLOW) it else it.copy(shadow = null) },
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

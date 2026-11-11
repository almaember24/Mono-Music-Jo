package com.prism.music.ui.screens

import com.prism.music.tr
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.R
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.BackdropKind
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.drawPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A cover made in Prism (1.7.6, H4): two colors, a pattern from the backgrounds, the name, and the split light's edge. */
data class CoverDesign(
    val title: String,
    val top: Int = 0xFF787878.toInt(),
    val bottom: Int = 0xFF0A0A0A.toInt(),
    val pattern: BackdropKind? = BackdropKind.WAVES,
    val edge: Boolean = true,
)

/** The simple patterns a cover can carry. */
private val COVER_PATTERNS = listOf(
    BackdropKind.DOTS, BackdropKind.PINSTRIPE, BackdropKind.WAVES, BackdropKind.RIPPLES, BackdropKind.DIAMONDS,
    BackdropKind.ZIGZAG, BackdropKind.HALFTONE, BackdropKind.CROSSES, BackdropKind.SCALES, BackdropKind.CONFETTI,
)

// Outfit is a variable font: the weight is asked for on its axis, or it draws at its thinnest.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val coverFont = FontFamily(
    Font(R.font.outfit, FontWeight.Medium, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(500))),
)

/** The cover, at whatever size the scope is: the preview on screen and the saved picture are the same drawing. */
private fun DrawScope.drawCover(d: CoverDesign, text: TextMeasurer) {
    val w = size.width
    drawRect(Brush.verticalGradient(listOf(Color(d.top), Color(d.bottom))))
    d.pattern?.let { kind ->
        // The pattern's own ink in white over the colors (its background left clear). Waves stacks three bands along
        // the foot, where the name sits (about half white there): drawn at half strength on a cover.
        val layer = if (kind == BackdropKind.WAVES) 0.5f else 1f
        if (layer < 1f) drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = layer }) }
        drawPattern(kind, Color.Transparent, Color.White, Color.White, dark = true, t = 0f, density = w / 360f, glow = 0f)
        if (layer < 1f) drawIntoCanvas { it.restore() }
    }
    // A little shade under the white name, so a pale top color or pattern doesn't wash it out.
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)), startY = size.height * 0.5f, endY = size.height), topLeft = Offset(0f, size.height * 0.5f), size = Size(w, size.height * 0.5f))
    // The name, bottom left, as large as it fits in three lines.
    val name = d.title.trim().ifEmpty { tr(R.string.playlist) }
    val pad = w * 0.075f
    val px = when { name.length <= 10 -> 0.13f; name.length <= 22 -> 0.10f; else -> 0.08f } * w
    val style = TextStyle(color = Color.White, fontFamily = coverFont, fontWeight = FontWeight.Medium, fontSize = (px / density).sp, lineHeight = (px * 1.05f / density).sp)
    val layout = text.measure(name, style, overflow = TextOverflow.Ellipsis, maxLines = 3, constraints = Constraints(maxWidth = (w - pad * 2).toInt()))
    drawText(layout, topLeft = Offset(pad, size.height - pad - layout.size.height))
    if (d.edge) {
        val stroke = w * 0.018f
        drawRect(
            Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray(), startX = 0f, endX = w),
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, size.height - stroke), style = Stroke(stroke),
        )
    }
}

/** The cover as a picture [px] wide, for saving. */
fun renderCover(context: Context, d: CoverDesign, px: Int = 1200): android.graphics.Bitmap {
    val image = ImageBitmap(px, px)
    val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(px.toFloat(), px.toFloat())) {
        drawCover(d, measurer)
    }
    return image.asAndroidBitmap()
}

/**
 * Make a cover for playlist [key] (1.7.6): its name over two colors of your choosing, with a pattern and the split
 * light around it, seen as it's made; saved like a photo (on this phone, in backups).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CoverMakerSheet(key: String, title: String, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var design by remember { mutableStateOf(CoverDesign(title)) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Text(tr(R.string.make_a_cover), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(220.dp).clip(RoundedCornerShape(10.dp))) { drawCover(design, measurer) }
            }
            com.prism.music.ui.components.PrismTextField(
                design.title, { design = design.copy(title = it.take(60)) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                singleLine = true, label = { Text(tr(R.string.name_on_the_cover)) },
            )
            Text(tr(R.string.colors), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 14.dp))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Swatch(tr(R.string.top), design.top) { picking = 1 }
                Swatch(tr(R.string.bottom), design.bottom) { picking = 2 }
            }
            Text(tr(R.string.pattern), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismChip(design.pattern == null, { design = design.copy(pattern = null) }, tr(R.string.none))
                COVER_PATTERNS.forEach { k ->
                    PrismChip(design.pattern == k, { design = design.copy(pattern = k) }, k.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
            Toggle(tr(R.string.split_light_around_it), null, design.edge) { design = design.copy(edge = it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
                PrismTextButton(onClick = {
                    val made = design
                    scope.launch {
                        val bmp = withContext(Dispatchers.Default) { renderCover(context, made) }
                        c.covers.useBitmap(key, bmp)
                        onDismiss()
                    }
                }) { Text(tr(R.string.use_this_cover)) }
            }
        }
    }
    picking?.let { which ->
        com.prism.music.ui.components.ColorPickerSheet(
            if (which == 1) tr(R.string.top_color) else tr(R.string.bottom_color), if (which == 1) design.top else design.bottom, { picking = null },
        ) { picked -> design = if (which == 1) design.copy(top = picked) else design.copy(bottom = picked); picking = null }
    }
}

@Composable
private fun Swatch(label: String, color: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClickLabel = tr(R.string.change_the_color, label), onClick = onClick).padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(color)).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

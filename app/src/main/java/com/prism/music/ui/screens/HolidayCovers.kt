package com.prism.music.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.unit.sp
import com.prism.music.R
import com.prism.music.data.seasonal.Holiday
import com.prism.music.data.seasonal.HolidayMix
import com.prism.music.data.seasonal.Motif
import com.prism.music.tr
import com.prism.music.ui.theme.PrismTokens
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A holiday mix's cover (1.8.0), drawn by Prism like its other covers: the season's two colors, the mix's motif large
 * at the top (a leaf, a snowflake, an ornament…), a little snow in December, the name at the foot and the split light
 * along the bottom edge. No pictures from anywhere: the same drawing on Home, on its page and in the car.
 */
@Composable
fun HolidayCover(mix: HolidayMix, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val measurer = rememberTextMeasurer()
    val name = tr(mix.name)
    Canvas(modifier.semantics { contentDescription = name }) { drawHolidayCover(mix, if (showTitle) name else null, measurer) }
}

// Outfit is a variable font: the weight is asked for on its axis, or it draws at its thinnest.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val holidayFont = FontFamily(
    Font(R.font.outfit, FontWeight.Medium, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(560))),
)

fun DrawScope.drawHolidayCover(mix: HolidayMix, name: String?, text: TextMeasurer) {
    val w = size.width
    val h = size.height
    val top = Color(mix.top)
    val bottom = Color(mix.bottom)
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
    // A glow behind the motif, so it sits in light.
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.68f, h * 0.34f), radius = w * 0.5f), radius = w * 0.5f, center = Offset(w * 0.68f, h * 0.34f))
    // December's snow, Thanksgiving's few falling leaves: placed the same each time for the same mix.
    val rnd = java.util.Random(mix.key.hashCode().toLong())
    if (mix.holiday == Holiday.CHRISTMAS) repeat(22) {
        drawCircle(Color.White.copy(alpha = 0.18f + rnd.nextFloat() * 0.25f), radius = w * (0.006f + rnd.nextFloat() * 0.012f), center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.9f))
    } else repeat(4) {
        val at = Offset(rnd.nextFloat() * w * 0.55f, h * (0.08f + rnd.nextFloat() * 0.45f))
        rotate(rnd.nextFloat() * 360f, at) { drawMotif(Motif.LEAF, at, w * (0.07f + rnd.nextFloat() * 0.05f), Color.White.copy(alpha = 0.16f), Color.Transparent) }
    }
    val cut = lerp(top, bottom, 0.35f)
    val at = Offset(w * 0.68f, h * 0.34f)
    rotate(-10f, at) { drawMotif(mix.motif, at, w * 0.5f, Color.White.copy(alpha = 0.94f), cut) }
    // A little shade under the white name.
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)), startY = h * 0.5f, endY = h), topLeft = Offset(0f, h * 0.5f), size = Size(w, h * 0.5f))
    if (name != null) {
        val pad = w * 0.075f
        val px = when { name.length <= 12 -> 0.12f; name.length <= 22 -> 0.098f; else -> 0.082f } * w
        val style = TextStyle(color = Color.White, fontFamily = holidayFont, fontWeight = FontWeight.Medium, fontSize = (px / density).sp, lineHeight = (px * 1.05f / density).sp)
        val layout = text.measure(name, style, overflow = TextOverflow.Ellipsis, maxLines = 3, constraints = Constraints(maxWidth = (w - pad * 2).toInt().coerceAtLeast(1)))
        drawText(layout, topLeft = Offset(pad, h - pad - layout.size.height - w * 0.02f))
    }
    // Made by Prism: the split light along the foot, as on Made for you's covers.
    drawRect(Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray(), startX = 0f, endX = w), topLeft = Offset(0f, h - w * 0.022f), size = Size(w, w * 0.022f))
}

/**
 * [motif] in [ink], [s] across, centered on [c]; [cut] draws its inner lines (a pumpkin's ribs, an ornament's band) in the
 * cover's own color.
 */
internal fun DrawScope.drawMotif(motif: Motif, c: Offset, s: Float, ink: Color, cut: Color) {
    fun p(x: Float, y: Float) = Offset(c.x + x * s, c.y + y * s)
    fun line(a: Offset, b: Offset, width: Float, color: Color = ink) = drawLine(color, a, b, strokeWidth = width * s, cap = StrokeCap.Round)
    fun path(block: Path.() -> Unit) = Path().apply(block)
    fun Path.m(x: Float, y: Float) = moveTo(c.x + x * s, c.y + y * s)
    fun Path.l(x: Float, y: Float) = lineTo(c.x + x * s, c.y + y * s)
    fun Path.q(x1: Float, y1: Float, x: Float, y: Float) = quadraticTo(c.x + x1 * s, c.y + y1 * s, c.x + x * s, c.y + y * s)
    fun Path.cu(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = cubicTo(c.x + x1 * s, c.y + y1 * s, c.x + x2 * s, c.y + y2 * s, c.x + x * s, c.y + y * s)
    fun oval(x: Float, y: Float, rw: Float, rh: Float, color: Color = ink) = drawOval(color, topLeft = p(x - rw, y - rh), size = Size(rw * 2 * s, rh * 2 * s))
    fun star(cx: Float, cy: Float, r: Float, points: Int, inner: Float, color: Color = ink) = drawPath(path {
        for (i in 0 until points * 2) {
            val rr = if (i % 2 == 0) r else r * inner
            val a = -PI / 2 + i * PI / points
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            if (i == 0) m(x, y) else l(x, y)
        }
        close()
    }, color)
    val stroke = { width: Float -> Stroke(width * s, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    when (motif) {
        Motif.LEAF -> {
            drawPath(path { m(0f, -0.5f); q(0.48f, -0.18f, 0f, 0.38f); q(-0.48f, -0.18f, 0f, -0.5f); close() }, ink)
            if (cut.alpha > 0f) {
                line(p(0f, -0.4f), p(0f, 0.3f), 0.035f, cut)
                for (k in 0..2) { val y = -0.22f + k * 0.16f; line(p(0f, y + 0.08f), p(0.16f, y - 0.04f), 0.025f, cut); line(p(0f, y + 0.08f), p(-0.16f, y - 0.04f), 0.025f, cut) }
            }
            line(p(0f, 0.36f), p(0.02f, 0.5f), 0.045f)
        }
        Motif.PUMPKIN -> {
            oval(-0.2f, 0.08f, 0.24f, 0.3f); oval(0.2f, 0.08f, 0.24f, 0.3f); oval(0f, 0.08f, 0.26f, 0.32f)
            line(p(-0.09f, -0.18f), p(-0.11f, 0.36f), 0.03f, cut); line(p(0.09f, -0.18f), p(0.11f, 0.36f), 0.03f, cut)
            drawPath(path { m(-0.03f, -0.22f); l(-0.02f, -0.42f); q(0.06f, -0.48f, 0.1f, -0.42f); l(0.05f, -0.22f); close() }, ink)
        }
        Motif.MUG -> {
            drawRoundRect(ink, topLeft = p(-0.32f, -0.08f), size = Size(0.5f * s, 0.5f * s), cornerRadius = CornerRadius(0.08f * s))
            drawArc(ink, -90f, 180f, false, topLeft = p(0.08f, 0.02f), size = Size(0.26f * s, 0.26f * s), style = stroke(0.07f))
            for (x in listOf(-0.18f, 0.02f)) drawPath(path { m(x, -0.16f); q(x + 0.08f, -0.26f, x, -0.34f); q(x - 0.08f, -0.42f, x, -0.5f) }, ink, style = stroke(0.045f))
            line(p(-0.4f, 0.46f), p(0.3f, 0.46f), 0.05f)
        }
        Motif.MOON -> {
            drawCircle(ink, radius = 0.36f * s, center = p(-0.04f, 0.04f))
            drawCircle(cut, radius = 0.3f * s, center = p(0.14f, -0.08f))
            star(0.3f, 0.26f, 0.08f, 4, 0.35f); star(0.36f, -0.36f, 0.05f, 4, 0.35f)
        }
        Motif.HEART -> drawPath(path {
            m(0f, 0.42f); cu(-0.55f, 0.05f, -0.42f, -0.48f, 0f, -0.2f); cu(0.42f, -0.48f, 0.55f, 0.05f, 0f, 0.42f); close()
        }, ink)
        Motif.FOOTBALL -> rotate(-35f, c) {
            drawPath(path { m(-0.5f, 0f); q(0f, -0.52f, 0.5f, 0f); q(0f, 0.52f, -0.5f, 0f); close() }, ink)
            line(p(-0.18f, 0f), p(0.18f, 0f), 0.035f, cut)
            for (k in -2..2) line(p(k * 0.08f, -0.07f), p(k * 0.08f, 0.07f), 0.03f, cut)
            line(p(-0.36f, -0.12f), p(-0.36f, 0.12f), 0.03f, cut); line(p(0.36f, -0.12f), p(0.36f, 0.12f), 0.03f, cut)
        }
        Motif.NOTE -> {
            oval(-0.24f, 0.3f, 0.14f, 0.1f); oval(0.22f, 0.2f, 0.14f, 0.1f)
            line(p(-0.12f, 0.3f), p(-0.12f, -0.34f), 0.06f); line(p(0.34f, 0.2f), p(0.34f, -0.44f), 0.06f)
            drawPath(path { m(-0.15f, -0.34f); l(0.37f, -0.44f); l(0.37f, -0.3f); l(-0.15f, -0.2f); close() }, ink)
        }
        Motif.WHEAT -> {
            line(p(0f, 0.5f), p(0f, -0.42f), 0.04f)
            for (k in 0..4) {
                val y = -0.38f + k * 0.15f
                rotate(32f, p(0f, y)) { oval(0.08f, y, 0.09f, 0.045f) }
                rotate(-32f, p(0f, y)) { oval(-0.08f, y, 0.09f, 0.045f) }
            }
            oval(0f, -0.46f, 0.04f, 0.08f)
        }
        Motif.ORNAMENT -> {
            drawCircle(ink, radius = 0.36f * s, center = p(0f, 0.1f))
            drawRect(ink, topLeft = p(-0.1f, -0.36f), size = Size(0.2f * s, 0.12f * s))
            drawCircle(ink, radius = 0.06f * s, center = p(0f, -0.43f), style = stroke(0.03f))
            drawPath(path { m(-0.36f, 0.06f); q(-0.18f, -0.06f, 0f, 0.06f); q(0.18f, 0.18f, 0.36f, 0.06f) }, cut, style = stroke(0.05f))
            for (x in listOf(-0.18f, 0f, 0.18f)) drawCircle(cut, radius = 0.03f * s, center = p(x, 0.26f))
        }
        Motif.STAR -> { star(0f, 0.02f, 0.46f, 5, 0.45f); star(-0.36f, -0.36f, 0.08f, 4, 0.35f) }
        Motif.SNOWFLAKE -> for (k in 0 until 6) {
            val a = k * PI / 3
            fun at(r: Float, da: Double = 0.0) = p((r * cos(a + da)).toFloat(), (r * sin(a + da)).toFloat())
            line(c, at(0.46f), 0.05f)
            val mid = at(0.26f)
            line(mid, Offset(mid.x + (0.14f * s * cos(a + PI / 4)).toFloat(), mid.y + (0.14f * s * sin(a + PI / 4)).toFloat()), 0.04f)
            line(mid, Offset(mid.x + (0.14f * s * cos(a - PI / 4)).toFloat(), mid.y + (0.14f * s * sin(a - PI / 4)).toFloat()), 0.04f)
        }
        Motif.TREE, Motif.PINE -> {
            for ((i, y) in listOf(-0.32f, -0.1f, 0.12f).withIndex()) {
                val half = 0.18f + i * 0.08f
                drawPath(path { m(0f, y - 0.2f); l(half, y + 0.16f); l(-half, y + 0.16f); close() }, ink)
            }
            drawRect(ink, topLeft = p(-0.06f, 0.28f), size = Size(0.12f * s, 0.16f * s))
            if (motif == Motif.TREE) star(0f, -0.56f, 0.09f, 5, 0.45f)
            else for ((x, y) in listOf(-0.12f to -0.05f, 0.14f to 0.16f, -0.2f to 0.22f)) drawCircle(cut, radius = 0.025f * s, center = p(x, y))
        }
        Motif.GIFT -> {
            drawRect(ink, topLeft = p(-0.34f, -0.06f), size = Size(0.68f * s, 0.48f * s))
            drawRect(ink, topLeft = p(-0.4f, -0.2f), size = Size(0.8f * s, 0.12f * s))
            drawRect(cut, topLeft = p(-0.05f, -0.2f), size = Size(0.1f * s, 0.62f * s))
            drawRect(cut, topLeft = p(-0.4f, -0.09f), size = Size(0.8f * s, 0.03f * s))
            drawOval(ink, topLeft = p(-0.24f, -0.42f), size = Size(0.22f * s, 0.2f * s), style = stroke(0.05f))
            drawOval(ink, topLeft = p(0.02f, -0.42f), size = Size(0.22f * s, 0.2f * s), style = stroke(0.05f))
        }
        Motif.BELL -> {
            drawPath(path { m(-0.36f, 0.26f); cu(-0.3f, 0.2f, -0.3f, -0.36f, 0f, -0.36f); cu(0.3f, -0.36f, 0.3f, 0.2f, 0.36f, 0.26f); close() }, ink)
            drawRoundRect(ink, topLeft = p(-0.4f, 0.24f), size = Size(0.8f * s, 0.08f * s), cornerRadius = CornerRadius(0.04f * s))
            drawCircle(ink, radius = 0.07f * s, center = p(0f, 0.4f))
            drawCircle(ink, radius = 0.05f * s, center = p(0f, -0.42f), style = stroke(0.03f))
        }
        Motif.CANDY_CANE -> {
            drawPath(path { m(0.12f, 0.48f); l(0.12f, -0.14f); q(0.12f, -0.42f, -0.12f, -0.42f); q(-0.34f, -0.42f, -0.34f, -0.18f) }, ink, style = stroke(0.13f))
            for (k in 0..3) line(p(0.05f, 0.34f - k * 0.14f), p(0.19f, 0.26f - k * 0.14f), 0.035f, cut)
        }
        Motif.MIC -> {
            drawCircle(ink, radius = 0.17f * s, center = p(0f, -0.26f))
            for (k in -1..1) line(p(-0.14f, -0.26f + k * 0.07f), p(0.14f, -0.26f + k * 0.07f), 0.015f, cut)
            drawPath(path { m(-0.1f, -0.1f); l(0.1f, -0.1f); l(0.05f, 0.34f); l(-0.05f, 0.34f); close() }, ink)
            drawArc(ink, 0f, 180f, false, topLeft = p(-0.24f, -0.12f), size = Size(0.48f * s, 0.3f * s), style = stroke(0.035f))
            line(p(-0.16f, 0.44f), p(0.16f, 0.44f), 0.05f)
        }
        Motif.BOLT -> drawPath(path { m(0.08f, -0.5f); l(-0.26f, 0.06f); l(-0.02f, 0.06f); l(-0.12f, 0.5f); l(0.26f, -0.08f); l(0.02f, -0.08f); close() }, ink)
        Motif.FLOWER -> {
            for (k in 0 until 6) rotate(k * 60f, c) { drawPath(path { m(0f, 0f); q(0.13f, -0.24f, 0f, -0.48f); q(-0.13f, -0.24f, 0f, 0f); close() }, ink) }
            for (k in 0 until 6) rotate(k * 60f + 30f, c) { drawPath(path { m(0f, 0f); q(0.08f, -0.16f, 0f, -0.32f); q(-0.08f, -0.16f, 0f, 0f); close() }, ink.copy(alpha = ink.alpha * 0.7f)) }
            for (k in 0 until 5) { val a = k * 2 * PI / 5; drawCircle(cut, radius = 0.03f * s, center = p((0.05f * cos(a)).toFloat(), (0.05f * sin(a)).toFloat())) }
        }
        Motif.SNOWMAN -> {
            drawCircle(ink, radius = 0.24f * s, center = p(0f, 0.24f))
            drawCircle(ink, radius = 0.17f * s, center = p(0f, -0.14f))
            drawRect(ink, topLeft = p(-0.12f, -0.5f), size = Size(0.24f * s, 0.2f * s))
            drawRect(ink, topLeft = p(-0.2f, -0.31f), size = Size(0.4f * s, 0.04f * s))
            drawCircle(cut, radius = 0.025f * s, center = p(-0.06f, -0.17f)); drawCircle(cut, radius = 0.025f * s, center = p(0.06f, -0.17f))
            for (y in listOf(0.12f, 0.24f, 0.36f)) drawCircle(cut, radius = 0.025f * s, center = p(0f, y))
            line(p(-0.12f, -0.03f), p(0.12f, -0.03f), 0.04f, cut)
        }
        Motif.DISCO -> {
            line(p(0f, -0.5f), p(0f, -0.36f), 0.025f)
            val ball = path { addOval(Rect(p(-0.36f, -0.36f), Size(0.72f * s, 0.72f * s))) }
            drawPath(ball, ink)
            clipPath(ball) {
                for (k in -3..3) line(p(-0.4f, k * 0.1f), p(0.4f, k * 0.1f), 0.012f, cut)
                for (k in -3..3) line(p(k * 0.1f, -0.4f), p(k * 0.1f, 0.4f), 0.012f, cut)
            }
            star(0.3f, -0.3f, 0.1f, 4, 0.3f)
        }
        Motif.SPARKLE -> {
            star(0f, 0f, 0.42f, 4, 0.22f); star(0.32f, -0.3f, 0.12f, 4, 0.3f); star(-0.3f, 0.3f, 0.09f, 4, 0.3f)
        }
    }
}

/**
 * The covers as pictures, for what can't draw them itself (a mix's page header and the car): one file per mix and
 * language (the name is on it), made once, in files/seasonal/covers.
 */
object HolidayCovers {
    private const val VERSION = 1
    private const val PX = 720

    fun dir(context: Context) = File(context.filesDir, "seasonal/covers")

    fun file(context: Context, mix: HolidayMix): String? {
        val lang = context.resources.configuration.locales[0].toLanguageTag().replace(Regex("[^A-Za-z0-9-]"), "")
        val f = File(dir(context), "${mix.key}-$lang-v$VERSION.jpg")
        if (!f.exists()) runCatching {
            f.parentFile?.mkdirs()
            val image = ImageBitmap(PX, PX)
            val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(PX.toFloat(), PX.toFloat())) {
                drawHolidayCover(mix, tr(mix.name), measurer)
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(f)) tmp.delete()
        }
        return if (f.exists()) Uri.fromFile(f).toString() else null
    }
}

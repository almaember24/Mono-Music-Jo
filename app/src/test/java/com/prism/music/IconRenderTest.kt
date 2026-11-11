package com.prism.music

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.prism.music.ui.theme.PrismIcons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws Prism's own icons (PrismIcons) large, light on dark and dark on light, onto app/build/icon-previews/icons.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconRenderTest {
    /** Every icon in PrismIcons, in the order they're declared. */
    private val icons: List<ImageVector> = PrismIcons::class.java.declaredFields
        .filter { it.type == ImageVector::class.java }
        .map { f -> f.isAccessible = true; f.get(PrismIcons) as ImageVector }

    /** [v] as Compose draws it (fill strength included), its lines scaled by [weight] relative to the built one. */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIcon(v: ImageVector, color: Color, weight: Float = 1f) {
        fun group(g: VectorGroup) {
            for (node in g) when (node) {
                is VectorPath -> {
                    val path = node.pathData.toPath().apply { fillType = node.pathFillType }
                    if (node.fill != null) drawPath(path, color.copy(alpha = color.alpha * node.fillAlpha))
                    if (node.stroke != null) drawPath(path, color, style = Stroke(node.strokeLineWidth * weight, cap = node.strokeLineCap, join = node.strokeLineJoin))
                }
                is VectorGroup -> rotate(node.rotation, Offset(node.pivotX, node.pivotY)) { group(node) }
            }
        }
        group(v.root)
    }

    /**
     * The sheet to review the set by: every icon (selected twins too) with its name, in Thin, Regular and Bold at 24 dp,
     * three device pixels to the dp, light on dark, onto icon-previews/icon-review.png. Plus every icon at 20, 24 and
     * 32 dp at a phone's density (2.6), dark on light, onto icon-previews/icon-sizes.png.
     */
    @Test
    fun reviewSheets() {
        val named = PrismIcons::class.java.declaredFields.filter { it.type == ImageVector::class.java }
            .map { f -> f.isAccessible = true; f.name to (f.get(PrismIcons) as ImageVector) }
        val measurer = androidx.compose.ui.text.TextMeasurer(
            androidx.compose.ui.text.font.createFontFamilyResolver(org.robolectric.RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr,
        )
        val label = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp))
        val weights = com.prism.music.ui.theme.IconWeight.entries
        val now = com.prism.music.ui.theme.IconStyle.weight.scale

        run {
            val cellW = 390; val cellH = 118; val cols = 5
            val rows = (named.size + cols - 1) / cols
            val bmp = Bitmap.createBitmap(cols * cellW, rows * cellH + 40, Bitmap.Config.ARGB_8888)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
                drawRect(Color(0xFF060609))
                drawText(measurer.measure("Thin · Regular · Bold at 24 dp (×3)", label), Color(0x99EEEEF5), Offset(16f, 10f))
                named.forEachIndexed { i, (name, v) ->
                    val x = (i % cols) * cellW + 16f
                    val y = (i / cols) * cellH + 40f
                    drawText(measurer.measure(name, label), Color(0x99EEEEF5), Offset(x, y))
                    weights.forEachIndexed { k, w ->
                        translate(x + k * 90f, y + 26f) { scale(3f, Offset.Zero) { drawIcon(v, Color(0xFFEEEEF5), w.scale / now) } }
                    }
                }
            }
            val dir = File("build/icon-previews").apply { mkdirs() }
            File(dir, "icon-review.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        run {
            val d = 2.6f
            val sizes = listOf(20f, 24f, 32f)
            val cellW = (sizes.sum() * d + 60).toInt(); val cellH = (32 * d + 24).toInt(); val cols = 8
            val rows = (named.size + cols - 1) / cols
            val bmp = Bitmap.createBitmap(cols * cellW, rows * cellH, Bitmap.Config.ARGB_8888)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
                drawRect(Color(0xFFF6F6F8))
                named.forEachIndexed { i, (_, v) ->
                    var x = (i % cols) * cellW + 12f
                    val y = (i / cols) * cellH + 12f
                    sizes.forEach { s ->
                        translate(x, y + (32 - s) * d / 2) { scale(s / 24f * d, Offset.Zero) { drawIcon(v, Color(0xFF0A0A10)) } }
                        x += s * d + 18
                    }
                }
            }
            File(File("build/icon-previews"), "icon-sizes.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * Every drawing in the generated packs (1.7.7) beside Prism's own: Prism · Facet · Dots at 24 dp (×3), light on dark,
     * with its name, onto icon-previews/packs.png, to look over after changing a drawing or the generator.
     */
    @Test
    fun generatedPacks() {
        val names = com.prism.music.ui.theme.PrismIconData.names.toList()
        val packs = listOf(com.prism.music.ui.theme.IconPack.PRISM, com.prism.music.ui.theme.IconPack.FACET, com.prism.music.ui.theme.IconPack.DOTS, com.prism.music.ui.theme.IconPack.CLASSIC)
        val measurer = androidx.compose.ui.text.TextMeasurer(
            androidx.compose.ui.text.font.createFontFamilyResolver(org.robolectric.RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr,
        )
        val label = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp))
        val cellW = 390; val cellH = 118; val cols = 5
        val rows = (names.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * cellW, rows * cellH + 40, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawRect(Color(0xFF060609))
            drawText(measurer.measure("Prism · Facet · Dots · Classic at 24 dp (×3)", label), Color(0x99EEEEF5), Offset(16f, 10f))
            names.forEachIndexed { i, name ->
                val x = (i % cols) * cellW + 16f
                val y = (i / cols) * cellH + 40f
                drawText(measurer.measure(name, label), Color(0x99EEEEF5), Offset(x, y))
                packs.forEachIndexed { k, p ->
                    val v = PrismIcons.preview(name, com.prism.music.ui.theme.IconFill.OUTLINE, p)
                    translate(x + k * 90f, y + 26f) { scale(3f, Offset.Zero) { drawIcon(v, Color(0xFFEEEEF5)) } }
                }
            }
        }
        val dir = File("build/icon-previews").apply { mkdirs() }
        File(dir, "packs.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * A few icons in each Settings → Appearance → Icons style (#19), as `PrismIcons.line` draws them: Thin / Regular /
     * Bold, rounded and square, onto icon-previews/icon-styles.png.
     */
    @Test
    fun styles() {
        val sample = icons.take(30)
        val styles = com.prism.music.ui.theme.IconWeight.entries.flatMap { w -> listOf(w to true, w to false) }
        val cell = 96
        val bmp = Bitmap.createBitmap(sample.size * cell, styles.size * cell, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawRect(Color(0xFF121212))
            styles.forEachIndexed { r, (weight, rounded) ->
                sample.forEachIndexed { i, v ->
                    translate(i * cell + 12f, r * cell + 12f) {
                        scale(3f, Offset.Zero) {
                            fun group(g: VectorGroup) {
                                for (node in g) when (node) {
                                    is VectorPath -> {
                                        val path = node.pathData.toPath().apply { fillType = node.pathFillType }
                                        if (node.fill != null) drawPath(path, Color.White)
                                        // Restyle the current (Regular, rounded) icon the way IconStyle would build it.
                                        if (node.stroke != null) drawPath(
                                            path, Color.White,
                                            style = Stroke(
                                                node.strokeLineWidth / com.prism.music.ui.theme.IconStyle.weight.scale * weight.scale,
                                                cap = if (rounded) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt,
                                                join = if (rounded) androidx.compose.ui.graphics.StrokeJoin.Round else androidx.compose.ui.graphics.StrokeJoin.Miter,
                                            ),
                                        )
                                    }
                                    is VectorGroup -> rotate(node.rotation, Offset(node.pivotX, node.pivotY)) { group(node) }
                                }
                            }
                            group(v.root)
                        }
                    }
                }
            }
        }
        val dir = File("build/icon-previews").apply { mkdirs() }
        File(dir, "icon-styles.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The Cupertino pack beside Prism's own icon it replaces (Prism left, Cupertino right, light on dark then dark on
     * light), onto icon-previews/cupertino.png.
     */
    @Test
    fun cupertinoPack() {
        val holder = PrismIcons::class.java.declaredClasses.first { it.simpleName == "Cupertino" }
        val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val pack = holder.declaredFields.filter { it.type == ImageVector::class.java }
            .map { f -> f.isAccessible = true; f.name to (f.get(instance) as ImageVector) }
        val base = PrismIcons::class.java.declaredFields.filter { it.type == ImageVector::class.java }
            .associate { f -> f.isAccessible = true; f.name to (f.get(PrismIcons) as ImageVector) }
        val cell = 110
        val cols = 4 // pairs per row
        val rows = (pack.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * cell * 2, rows * cell * 2, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawRect(Color(0xFF121212), Offset.Zero, Size(bmp.width.toFloat(), rows * cell.toFloat()))
            drawRect(Color(0xFFF4F4F4), Offset(0f, rows * cell.toFloat()), Size(bmp.width.toFloat(), rows * cell.toFloat()))
            for (half in 0..1) pack.forEachIndexed { i, (name, v) ->
                val ink = if (half == 0) Color.White else Color(0xFF111111)
                val x = (i % cols) * cell * 2 + 8f
                val y = (i / cols) * cell + 8f + half * rows * cell
                base[name]?.let { translate(x, y) { scale(3.8f, Offset.Zero) { drawIcon(it, ink.copy(alpha = 0.45f)) } } }
                translate(x + cell, y) { scale(3.8f, Offset.Zero) { drawIcon(v, ink) } }
            }
        }
        val dir = File("build/icon-previews").apply { mkdirs() }
        File(dir, "cupertino.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("cupertino: " + pack.joinToString { it.first })
    }

    /**
     * The launcher icons (every Settings → Appearance → App icon choice) as a launcher shows them: full bleed, in a
     * circle and in a rounded square, their themed (monochrome) layer on a pale tint, and the notification icon at
     * status-bar size; onto icon-previews/launcher.png.
     */
    @Test
    fun launcherIconLarge() {
        // The Classic app icon at 1600 px, as the launcher shows it (circle) and as a rounded square, clear outside:
        // the showcase film's opening and closing mark.
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val px = 1600
        val shapes = mapOf(
            "app-icon-circle-1600.png" to android.graphics.Path().apply { addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW) },
            "app-icon-square-1600.png" to android.graphics.Path().apply { addRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * 0.225f, px * 0.225f, android.graphics.Path.Direction.CW) },
            // Full bleed, for places that round it themselves (Discord's application icon, store listings).
            "app-icon-full-1600.png" to android.graphics.Path().apply { addRect(0f, 0f, px.toFloat(), px.toFloat(), android.graphics.Path.Direction.CW) },
        )
        val dir = File("build/icon-previews").apply { mkdirs() }
        for ((name, clip) in shapes) {
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.clipPath(clip)
            for (res in listOf(com.prism.music.ui.theme.AppIcon.CLASSIC.background, com.prism.music.ui.theme.AppIcon.CLASSIC.foreground)) {
                val d = ctx.getDrawable(res)!!.mutate()
                d.setBounds(-px / 4, -px / 4, px + px / 4, px + px / 4)
                d.draw(c)
            }
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun launcherIcons() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val px = 216
        val gap = 24
        val icons = com.prism.music.ui.theme.AppIcon.entries
        val bmp = Bitmap.createBitmap(icons.size * (px + gap) + gap, 4 * (px + gap) + 140, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF2A2A30.toInt())
        fun layer(res: Int, x: Int, y: Int, clip: android.graphics.Path?, tint: Int? = null) {
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            clip?.let { c.clipPath(it) }
            // The adaptive layers are 108 dp with the visible 72 dp in the middle: draw them 1.5× and centre.
            val d = ctx.getDrawable(res)!!.mutate()
            tint?.let { d.setTint(it) }
            d.setBounds(-px / 4, -px / 4, px + px / 4, px + px / 4)
            d.draw(c)
            c.restore()
        }
        val circle = android.graphics.Path().apply { addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW) }
        val squircle = android.graphics.Path().apply { addRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * 0.28f, px * 0.28f, android.graphics.Path.Direction.CW) }
        icons.forEachIndexed { i, icon ->
            val x = gap + i * (px + gap)
            layer(icon.background, x, gap, null); layer(icon.foreground, x, gap, null)
            layer(icon.background, x, gap * 2 + px, circle); layer(icon.foreground, x, gap * 2 + px, circle)
            layer(icon.background, x, gap * 3 + px * 2, squircle); layer(icon.foreground, x, gap * 3 + px * 2, squircle)
            // Themed icons: Android draws the monochrome layer in a dark tint on a pale one.
            val y = gap * 4 + px * 3
            c.save(); c.translate(x.toFloat(), y.toFloat()); c.drawPath(circle, android.graphics.Paint().apply { color = 0xFFDCE3F5.toInt(); isAntiAlias = true }); c.restore()
            val mono = if (icon == com.prism.music.ui.theme.AppIcon.WAVE) R.drawable.ic_launcher_fg_wave else R.drawable.ic_launcher_fg_white
            layer(mono, x, y, circle, tint = 0xFF2B3A67.toInt())
        }
        // The notification icon at 24 dp on a phone (×2.6 and ×3.5), white on the status bar's dark grey.
        listOf(62, 84).forEachIndexed { k, s ->
            val d = ctx.getDrawable(R.drawable.ic_notification)!!
            val x = gap + k * 110
            d.setBounds(x, bmp.height - 120, x + s, bmp.height - 120 + s)
            d.draw(c)
        }
        val dir = File("build/icon-previews").apply { mkdirs() }
        File(dir, "launcher.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun contactSheet() {
        val cell = 120
        val cols = 10
        val rows = (icons.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * cell, rows * cell * 2, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawRect(Color(0xFF121212), Offset.Zero, Size(bmp.width.toFloat(), rows * cell.toFloat()))
            drawRect(Color(0xFFF4F4F4), Offset(0f, rows * cell.toFloat()), Size(bmp.width.toFloat(), rows * cell.toFloat()))
            for (half in 0..1) icons.forEachIndexed { i, v ->
                val x = (i % cols) * cell + 12f
                val y = (i / cols) * cell + 12f + half * rows * cell
                translate(x, y) { scale(4f, Offset.Zero) { drawIcon(v, if (half == 0) Color.White else Color(0xFF111111)) } }
            }
        }
        val dir = File("build/icon-previews").apply { mkdirs() }
        File(dir, "icons.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${File(dir, "icons.png").absolutePath}")
    }
}

package com.prism.music.ui.components

import com.prism.music.ui.theme.Mono
import com.prism.music.R
import com.prism.music.tr
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import com.prism.music.data.model.hiRes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.ui.theme.AccentSwatches
import com.prism.music.ui.theme.ColorMath
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prism's color picker (1.7.2), used wherever a color is picked: a hue ring around a saturation × brightness square,
 * sliders for exact RGB / HSL values, a hex code field, an eyedropper (the playing cover's colors, any spot of a photo,
 * the wallpaper's colors), Prism's swatches, recent and starred colors, and how readable the color is on dark and
 * light pages.
 *
 * [note], given the color, says how Prism will draw it when that differs from the color itself (an accent nudged for
 * contrast). [onPick] gets the color when "Use this color" is tapped; nothing changes before then.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    note: ((Int) -> String?)? = null,
    /** A way back to "no color of your own" (an artist page's photo color): its label and what it does. */
    reset: Pair<String, () -> Unit>? = null,
    /** What the color would look like where it's used, redrawn as the color changes. */
    preview: (@Composable (Int) -> Unit)? = null,
    onPick: (Int) -> Unit,
) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    // The color lives as HSV so the hue survives greys and black (where RGB has none).
    var hue by remember { mutableFloatStateOf(ColorMath.toHsv(initial)[0]) }
    var sat by remember { mutableFloatStateOf(ColorMath.toHsv(initial)[1]) }
    var value by remember { mutableFloatStateOf(ColorMath.toHsv(initial)[2]) }
    val color = ColorMath.fromHsv(hue, sat, value)
    fun set(argb: Int) {
        val hsv = ColorMath.toHsv(argb)
        // Greys keep the hue they had, so dragging back out of grey returns to it.
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0]
        sat = hsv[1]; value = hsv[2]
    }
    var mode by remember { mutableIntStateOf(0) }
    val recent by c.savedColors.recent.collectAsState()
    val saved by c.savedColors.saved.collectAsState()

    PrismSheet(onDismiss) { close ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // What's picked, its code, and a star to keep it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(color)).border(1.dp, scheme.onSurface.copy(alpha = 0.15f), RoundedCornerShape(16.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(ColorMath.toHex(color), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                val starred = color in saved
                Icon(
                    if (starred) PrismIcons.StarFilled else PrismIcons.Star, if (starred) tr(R.string.remove_from_saved_colors) else tr(R.string.save_this_color),
                    Modifier.size(44.dp).clip(CircleShape).clickable { c.savedColors.setSaved(color, !starred) }.padding(10.dp),
                    tint = if (starred) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }

            if (preview != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(tr(R.string.how_it_will_look))
                preview(color)
            }

            Segmented(listOf(0, 1), mode, { if (it == 0) tr(R.string.wheel) else tr(R.string.values) }, Modifier.fillMaxWidth()) { mode = it }
            if (mode == 0) ColorWheel(hue, sat, value, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { h, s, v -> hue = h; sat = s; value = v }
            else ColorValues(color, hue) { set(it) }

            HexField(color) { set(it) }

            Eyedropper { set(it) }

            Swatches(tr(R.string.app_name), AccentSwatches.map { it.toArgb() }, color) { set(it) }
            if (saved.isNotEmpty()) Swatches(tr(R.string.saved_2), saved, color) { set(it) }
            if (recent.isNotEmpty()) Swatches(tr(R.string.recent), recent, color) { set(it) }

            Readability(color)
            note?.invoke(color)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            reset?.let { (label, action) -> PrismTextButton({ close { action() } }, Modifier.fillMaxWidth()) { Text(label) } }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrismOutlinedButton({ close {} }, Modifier.weight(1f)) { Text(tr(R.string.cancel)) }
                PrismButton({
                    c.savedColors.used(color)
                    close { onPick(color) }
                }, Modifier.weight(1f)) { Text(tr(R.string.use_this_color)) }
            }
        }
    }
}

/** A hue ring with a saturation (across) × brightness (down) square inside. A light tick at each 30° of hue. */
@Composable
fun ColorWheel(hue: Float, sat: Float, value: Float, modifier: Modifier = Modifier, onChange: (Float, Float, Float) -> Unit) {
    val haptics = rememberHaptics()
    val latest by rememberUpdatedState(Triple(hue, sat, value))
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier.widthIn(max = 340.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 320.dp)
        val px = with(LocalDensity.current) { side.toPx() }
        val ringWidth = px * 0.085f
        val outer = px / 2f
        val inner = outer - ringWidth
        // The square fits inside the ring with a little room.
        val half = (inner - px * 0.035f) / sqrt(2f)
        val center = Offset(px / 2f, px / 2f)
        var dragging by remember { mutableIntStateOf(0) } // 1 ring, 2 square
        fun handle(p: Offset, start: Boolean) {
            val d = p - center
            val r = hypot(d.x, d.y)
            if (start) dragging = when {
                r >= inner - ringWidth * 0.4f && r <= outer + ringWidth * 0.6f -> 1
                kotlin.math.abs(d.x) <= half + 24f && kotlin.math.abs(d.y) <= half + 24f -> 2
                else -> 0
            }
            val (h0, s0, v0) = latest
            when (dragging) {
                1 -> {
                    val h = ((Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat() + 90f) % 360f + 360f) % 360f
                    if ((h0 / 30f).toInt() != (h / 30f).toInt()) haptics.tick()
                    change(h, s0, v0)
                }
                2 -> change(h0, ((d.x + half) / (2 * half)).coerceIn(0f, 1f), (1f - (d.y + half) / (2 * half)).coerceIn(0f, 1f))
            }
        }
        Canvas(
            Modifier.size(side)
                .semantics { contentDescription = tr(R.string.color_wheel) }
                .pointerInput(px) { detectTapGestures { handle(it, true) } }
                .pointerInput(px) {
                    detectDragGestures(onDragStart = { handle(it, true) }, onDragEnd = { dragging = 0 }) { ch, _ -> handle(ch.position, false) }
                },
        ) {
            val hues = (0..12).map { Color(ColorMath.fromHsv(it * 30f, 1f, 1f)) }
            // Sweep gradients start at 3 o'clock; the ring's red sits at 12, so turn it a quarter.
            rotate(-90f, center) {
                drawCircle(Brush.sweepGradient(hues, center), radius = outer - ringWidth / 2, center = center, style = Stroke(ringWidth))
            }
            val tl = Offset(center.x - half, center.y - half)
            val sq = Size(half * 2, half * 2)
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White, Color(ColorMath.fromHsv(hue, 1f, 1f))), tl.x, tl.x + sq.width), tl, sq, androidx.compose.ui.geometry.CornerRadius(10f))
            drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), tl.y, tl.y + sq.height), tl, sq, androidx.compose.ui.geometry.CornerRadius(10f))
            // Knobs: on the ring at the hue, and in the square at saturation × brightness.
            val a = Math.toRadians((hue - 90f).toDouble())
            val ringKnob = Offset(center.x + (outer - ringWidth / 2) * cos(a).toFloat(), center.y + (outer - ringWidth / 2) * sin(a).toFloat())
            val sqKnob = Offset(tl.x + sat * sq.width, tl.y + (1 - value) * sq.height)
            listOf(ringKnob to Color(ColorMath.fromHsv(hue, 1f, 1f)), sqKnob to Color(ColorMath.fromHsv(hue, sat, value))).forEach { (o, fill) ->
                drawCircle(Color.Black.copy(alpha = 0.25f), ringWidth * 0.46f, o + Offset(0f, 1.5f))
                drawCircle(fill, ringWidth * 0.40f, o)
                drawCircle(Color.White, ringWidth * 0.40f, o, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

/** Exact values: red, green and blue (0–255), then hue (°), saturation and lightness (%), each on its own gradient. */
@Composable
private fun ColorValues(color: Int, hue: Float, onChange: (Int) -> Unit) {
    val r = ColorMath.red(color); val g = ColorMath.green(color); val b = ColorMath.blue(color)
    val hsl = ColorMath.toHsl(color).also { if (it[1] < 0.001f) it[0] = hue }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Channel("R", r / 255f, "$r", Brush.horizontalGradient(listOf(Color(ColorMath.argb(255, 0, g, b)), Color(ColorMath.argb(255, 255, g, b))))) { onChange(ColorMath.argb(255, (it * 255).roundToInt(), g, b)) }
        Channel("G", g / 255f, "$g", Brush.horizontalGradient(listOf(Color(ColorMath.argb(255, r, 0, b)), Color(ColorMath.argb(255, r, 255, b))))) { onChange(ColorMath.argb(255, r, (it * 255).roundToInt(), b)) }
        Channel("B", b / 255f, "$b", Brush.horizontalGradient(listOf(Color(ColorMath.argb(255, r, g, 0)), Color(ColorMath.argb(255, r, g, 255))))) { onChange(ColorMath.argb(255, r, g, (it * 255).roundToInt())) }
        Spacer(Modifier.height(6.dp))
        Channel("H", hsl[0] / 360f, "${hsl[0].roundToInt()}°", Brush.horizontalGradient((0..6).map { Color(ColorMath.fromHsl(it * 60f, hsl[1].coerceAtLeast(0.5f), 0.5f)) })) {
            onChange(ColorMath.fromHsl(it * 359.9f, hsl[1], hsl[2]))
        }
        Channel("S", hsl[1], "${(hsl[1] * 100).roundToInt()}%", Brush.horizontalGradient(listOf(Color(ColorMath.fromHsl(hsl[0], 0f, hsl[2])), Color(ColorMath.fromHsl(hsl[0], 1f, hsl[2]))))) {
            onChange(ColorMath.fromHsl(hsl[0], it, hsl[2]))
        }
        Channel("L", hsl[2], "${(hsl[2] * 100).roundToInt()}%", Brush.horizontalGradient(listOf(Color.Black, Color(ColorMath.fromHsl(hsl[0], hsl[1], 0.5f)), Color.White))) {
            onChange(ColorMath.fromHsl(hsl[0], hsl[1], it))
        }
    }
}

/** One channel: its letter, a gradient track with a knob (tap or drag), and its value. */
@Composable
private fun Channel(name: String, fraction: Float, value: String, track: Brush, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    var width by remember { mutableFloatStateOf(1f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(22.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.weight(1f).height(36.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .semantics { contentDescription = "$name $value" }
                .pointerInput(Unit) { detectTapGestures { change((it.x / width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectDragGestures { ch, _ -> change((ch.position.x / width).coerceIn(0f, 1f)) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(track).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape))
            val x = with(LocalDensity.current) { (fraction.coerceIn(0f, 1f) * width).toDp() - 11.dp }
            Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(Color.White).border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape))
        }
        Text(value, Modifier.width(52.dp).padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** The color as a code, typed or pasted. */
@Composable
private fun HexField(color: Int, onColor: (Int) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(ColorMath.toHex(color)) }
    var bad by remember { mutableStateOf(false) }
    // Picked elsewhere (wheel, swatch): the field follows, unless what's typed already means that color.
    LaunchedEffect(color) { if (ColorMath.parseHex(text) != color) { text = ColorMath.toHex(color); bad = false } }
    fun take(t: String) {
        text = t
        val parsed = ColorMath.parseHex(t)
        bad = parsed == null && t.trim().removePrefix("#").length >= 6
        if (parsed != null && (t.trim().removePrefix("#").length == 6 || t.trim().removePrefix("#").length == 8 || t.trim().removePrefix("#").length == 3)) onColor(parsed or (0xFF shl 24))
    }
    PrismTextField(
        text, ::take, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(tr(R.string.color_code)) },
        placeholder = { Text("#3A86FF") },
        isError = bad,
        supportingText = if (bad) ({ Text(tr(R.string.a_code_is_6_hex_digits)) }) else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { ColorMath.parseHex(text)?.let { onColor(it or (0xFF shl 24)) } }),
        trailingIcon = {
            Icon(
                PrismIcons.Copy, tr(R.string.paste_a_color_code),
                Modifier.size(44.dp).clip(CircleShape).clickable { clipboard.getText()?.text?.let { take(it.trim().take(12)) } }.padding(11.dp),
            )
        },
    )
}

/** Colors to take from: the playing cover's palette, a spot on a photo, the wallpaper's colors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Eyedropper(onColor: (Int) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val song by c.player.currentSong.collectAsState()
    val cover by produceState(emptyList<Int>(), song?.thumbnail) {
        val url = song?.thumbnail ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) { runCatching { paletteOf(loadBitmap(context, hiRes(url, 320) ?: url, 160)) }.getOrNull().orEmpty() }
    }
    val wallpaper = remember {
        if (Build.VERSION.SDK_INT < 27) emptyList() else runCatching {
            android.app.WallpaperManager.getInstance(context).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                ?.let { w -> listOfNotNull(w.primaryColor, w.secondaryColor, w.tertiaryColor).map { it.toArgb() } }
        }.getOrNull().orEmpty()
    }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { photo = withContext(Dispatchers.IO) { runCatching { loadBitmap(context, uri, 1440) }.getOrNull() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(tr(R.string.take_a_color_from))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismChip(false, { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, tr(R.string.a_photo), icon = PrismIcons.Photo)
        }
        if (cover.isNotEmpty()) SwatchRow(tr(R.string.what_s_playing), cover, onColor)
        if (wallpaper.isNotEmpty()) SwatchRow(tr(R.string.your_wallpaper), wallpaper, onColor)
    }
    photo?.let { bmp -> PhotoDropper(bmp, onDismiss = { photo = null }) { onColor(it); photo = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(label: String, colors: List<Int>, onColor: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.distinct().forEach { col -> Dot(col, false) { onColor(col) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(label: String, colors: List<Int>, current: Int, onColor: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            colors.distinct().forEach { col -> Dot(col, col == current) { onColor(col) } }
        }
    }
}

@Composable
private fun Dot(color: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val light = ColorMath.luminance(color) > 0.6
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.onSurface else scheme.onSurface.copy(alpha = 0.15f), CircleShape)
            .semantics { contentDescription = ColorMath.toHex(color) }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(PrismIcons.Check, null, Modifier.size(16.dp), tint = if (light) Color.Black else Color.White) }
}

/** How the color reads as text on a dark page and a light one, and what text reads on it. */
@Composable
private fun Readability(color: Int) {
    val darkPage = 0xFF0E0E0E.toInt()
    val lightPage = 0xFFFAFAFA.toInt()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(tr(R.string.on_dark) to darkPage, tr(R.string.on_light) to lightPage).forEach { (label, bg) ->
            val r = ColorMath.readability(color, bg)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(bg)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(14.dp)).padding(10.dp),
            ) {
                Text(tr(R.string.aa), color = Color(color), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text("$label · ${r.label}", color = if (bg == darkPage) Color.White.copy(alpha = 0.75f) else Color.Black.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
        // Text on the color itself (buttons): whichever of white or black reads better.
        val onWhite = ColorMath.contrast(0xFFFFFFFF.toInt(), color) >= ColorMath.contrast(0xFF000000.toInt(), color)
        val ink = if (onWhite) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(color)).padding(10.dp)) {
            Text(tr(R.string.aa), color = Color(ink), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Text(tr(R.string.text_on_it, ColorMath.readability(ink, color).label), color = Color(ink).copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A photo to tap on: a loupe follows the finger and shows the color under it; let go (or tap) to take it. */
@Composable
private fun PhotoDropper(bitmap: Bitmap, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var at by remember { mutableStateOf<Offset?>(null) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // Where the photo sits inside the box (it's fitted, so there may be bars).
    fun sample(p: Offset): Int? {
        val scale = minOf(box.width / bitmap.width.toFloat(), box.height / bitmap.height.toFloat())
        val dx = (box.width - bitmap.width * scale) / 2f
        val dy = (box.height - bitmap.height * scale) / 2f
        val x = ((p.x - dx) / scale).toInt(); val y = ((p.y - dy) / scale).toInt()
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
        // A 5×5 average, so a single noisy pixel doesn't decide it.
        var r = 0; var g = 0; var b = 0; var n = 0
        for (yy in (y - 2)..(y + 2)) for (xx in (x - 2)..(x + 2)) {
            if (xx !in 0 until bitmap.width || yy !in 0 until bitmap.height) continue
            val px = bitmap.getPixel(xx, yy); r += ColorMath.red(px); g += ColorMath.green(px); b += ColorMath.blue(px); n++
        }
        return ColorMath.argb(255, r / n, g / n, b / n)
    }
    val under = at?.let(::sample)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(PrismIcons.Close, tr(R.string.close), glass = true, onClick = onDismiss)
                Text(tr(R.string.tap_or_drag_to_pick_a), Modifier.weight(1f).padding(horizontal = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }
                    .pointerInput(bitmap) { detectTapGestures { p -> sample(p)?.let(onPick) } }
                    .pointerInput(bitmap) {
                        detectDragGestures(
                            onDragStart = { at = it },
                            onDragEnd = { at?.let(::sample)?.let(onPick); at = null },
                            onDragCancel = { at = null },
                        ) { ch, _ -> at = ch.position }
                    },
            ) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                val p = at
                if (p != null && under != null) {
                    val d = LocalDensity.current
                    val size = 76.dp
                    val half = with(d) { (size / 2).roundToPx() }
                    // Above the finger, so it isn't hidden under it.
                    Box(
                        Modifier.offset { IntOffset(p.x.roundToInt() - half, p.y.roundToInt() - half * 3) }.size(size)
                            .clip(CircleShape).background(Color(under)).border(3.dp, Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

private suspend fun loadBitmap(context: android.content.Context, data: Any, px: Int): Bitmap? {
    val req = ImageRequest.Builder(context).data(data).size(px).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
}

/** A picture's main colors, liveliest first. */
private fun paletteOf(bitmap: Bitmap?): List<Int> {
    bitmap ?: return emptyList()
    val p = Palette.from(bitmap).maximumColorCount(16).generate()
    return listOfNotNull(
        p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.dominantSwatch, p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch,
    ).map { Mono.gray(it.rgb or (0xFF shl 24)) }.distinct()
}

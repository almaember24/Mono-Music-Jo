package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.db.mergeUploads
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.glassEdge
import com.prism.music.ui.components.pane
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens

/** Looks for Home's Replay card, from the quietest to the most colorful. */
enum class ReplayCardStyle(val label: String, val note: String) {
    QUIET(tr(R.string.quiet), tr(R.string.plain_glass_one_hairline_of_split)),
    LEDGER(tr(R.string.ledger), tr(R.string.three_numbers_side_by_side)),
    WASH(tr(R.string.wash), tr(R.string.a_faint_wash_of_color_and)),
    FRAME(tr(R.string.spectrum_frame), tr(R.string.glass_ringed_in_split_light_the)),
    RING(tr(R.string.ring), tr(R.string.the_year_so_far_as_a)),
    COVER(tr(R.string.cover), tr(R.string.your_top_song_s_cover_sets)),
    STRIPE(tr(R.string.prism_stripe), tr(R.string.a_band_of_refracted_light_down)),
    BARS(tr(R.string.spectrum_bars), tr(R.string.your_months_as_big_bars_red)),
    AURORA(tr(R.string.aurora), tr(R.string.full_color_like_a_rewind_slide)),
}

/**
 * What a Replay card shows: the year so far. [bars]: listening per bar of [graph], oldest first, the one now last
 * (months: January up to this month; #286: or the last weeks or days).
 */
data class ReplayCardData(
    val year: Int,
    val total: String,
    val bars: List<Long>,
    val topSong: String?,
    val topSongArtist: String?,
    val topArtist: String?,
    /** 0..1: how far through the year it is. */
    val yearDone: Float,
    val songCount: Int,
    val graph: com.prism.music.data.prefs.ReplayGraph = com.prism.music.data.prefs.ReplayGraph.MONTHS,
    /** "Includes Spotify" when Replay counts imported histories too (1.8.1); null: Prism's own plays only. */
    val includes: String? = null,
) {
    /** The bars' caption when they aren't the year's months ("Last 14 days"). */
    val graphCaption: String? get() = when (graph) {
        com.prism.music.data.prefs.ReplayGraph.MONTHS -> null
        com.prism.music.data.prefs.ReplayGraph.WEEKS -> tr(R.string.replay_last_weeks, bars.size)
        com.prism.music.data.prefs.ReplayGraph.DAYS -> tr(R.string.replay_last_days, bars.size)
    }
    /** The card's line under the time: what it counts when that's more than Prism's plays, else what Replay holds. */
    val subtitle: String get() = includes ?: tr(R.string.your_top_songs_artists_and_genres)
}

/** "Includes Spotify, Last.fm": the imported histories Replay counts (null: none). */
fun replayIncludes(counted: Collection<com.prism.music.data.imports.ImportSource>): String? =
    counted.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString(", ") { it.label }?.let { tr(R.string.imp_includes, it) }

/**
 * Listening in each bar of [graph] (#286), oldest first, the one [now] is in last: this year's months so far, the last
 * 12 weeks or the last 14 days, from what Replay counts ([plays]).
 */
suspend fun replayCardBars(plays: com.prism.music.data.imports.PlayStats, graph: com.prism.music.data.prefs.ReplayGraph, now: Long = System.currentTimeMillis()): List<Long> {
    val zone = java.time.ZoneId.systemDefault()
    val buckets = when (graph) {
        com.prism.music.data.prefs.ReplayGraph.MONTHS -> com.prism.music.data.ReplayBuckets.monthsSoFar(now, zone)
        com.prism.music.data.prefs.ReplayGraph.WEEKS -> com.prism.music.data.ReplayBuckets.lastWeeks(
            graph.count, now, zone, java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek,
        )
        com.prism.music.data.prefs.ReplayGraph.DAYS -> com.prism.music.data.ReplayBuckets.lastDays(graph.count, now, zone)
    }
    // (The store's ranges include both ends: a bar stops a moment before the next one starts.)
    return buckets.map { b -> runCatching { plays.totalMs(b.start, minOf(b.end - 1, now)) }.getOrDefault(0L) }
}

private val CARD_HEIGHT = 156.dp

/** Home's Replay card in [style]; [art] is the top song's cover (null: none yet). */
@Composable
fun ReplayCard(style: ReplayCardStyle, d: ReplayCardData, art: Painter?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ui = LocalUi.current
    val dark = LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    val hues = PrismTokens.spectrumColors(dark).map { it.second }
    fun hue(t: Float) = hues[(t.coerceIn(0f, 1f) * (hues.size - 1)).toInt()]
    val shape = ui.card
    val base = modifier.padding(horizontal = 16.dp).fillMaxWidth().height(CARD_HEIGHT)
    val tops = listOfNotNull(d.topArtist, d.topSong).joinToString(" · ")
    val big = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraLight)
    when (style) {
        // Plain glass and type; the only color is a hairline of split light along the bottom.
        ReplayCardStyle.QUIET -> Box(
            base.pane(shape).border(0.7.dp, glassEdge(), shape)
                .drawBehind { drawRect(PrismTokens.spectrum(dark), Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                .clickable(onClick = onClick).padding(22.dp),
        ) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Eyebrow(tr(R.string.replay, d.year))
                Spacer(Modifier.height(4.dp))
                Text(d.total, style = big, color = scheme.onSurface, maxLines = 1)
                Text(d.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(PrismIcons.ChevronRight, null, Modifier.align(Alignment.TopEnd), tint = scheme.onSurfaceVariant)
        }
        // Three numbers as a small ledger, ruled with hairlines; a dot of split light by the title.
        ReplayCardStyle.LEDGER -> Column(base.pane(shape).border(0.7.dp, glassEdge(), shape).clickable(onClick = onClick).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(PrismTokens.spectrumRing()))
                Spacer(Modifier.width(8.dp))
                Eyebrow(tr(R.string.replay, d.year), Modifier.weight(1f))
                Icon(PrismIcons.ChevronRight, null, tint = scheme.onSurfaceVariant)
            }
            d.includes?.let { Text(it, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                listOf(tr(R.string.listened) to d.total, tr(R.string.top_artist) to (d.topArtist ?: "—"), tr(R.string.top_song) to (d.topSong ?: "—")).forEachIndexed { i, (label, value) ->
                    if (i > 0) Box(Modifier.padding(horizontal = 12.dp).width(0.7.dp).fillMaxHeight().background(scheme.outlineVariant.copy(alpha = 0.6f)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                        Text(value, style = (if (i == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).copy(fontWeight = FontWeight.Light), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // 1.7 so far: a faint wash of split light from a corner, a spectrum dash, and the months as slim bars.
        ReplayCardStyle.WASH -> Box(
            base.pane(shape).border(0.7.dp, glassEdge(), shape)
                .drawBehind {
                    listOf(0.0f to 0.62f, 0.5f to 0.48f, 1.0f to 0.34f).forEach { (t, r) ->
                        val p = Offset(size.width * (0.98f - t * 0.16f), size.height * (0.04f + t * 0.22f))
                        drawCircle(Brush.radialGradient(listOf(hue(t).copy(alpha = if (dark) 0.16f else 0.10f), Color.Transparent), p, size.width * r), size.width * r, p)
                    }
                }
                .clickable(onClick = onClick).padding(22.dp),
        ) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.68f)) {
                Eyebrow(tr(R.string.replay, d.year))
                Box(Modifier.padding(top = 6.dp, bottom = 8.dp).size(28.dp, 2.dp).clip(CircleShape).background(PrismTokens.spectrum(dark)))
                Text(d.total, style = big, color = scheme.onSurface, maxLines = 1)
                Text(d.subtitle, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
                // #286: the last weeks or days say so; the year's months are the card's own.
                d.graphCaption?.let { Text(it, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1) }
                GraphBars(d, hues, Modifier.size(width = (d.bars.size * 7).dp, height = 54.dp), slim = true)
            }
        }
        // 1.7's first look: glass ringed in split light, the time set large and light in the spectrum.
        ReplayCardStyle.FRAME -> Box(
            base.pane(shape).border(1.5.dp, PrismTokens.spectrum(dark), shape).clickable(onClick = onClick).padding(22.dp),
        ) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Eyebrow(tr(R.string.replay, d.year))
                Text(d.total, style = big.copy(brush = PrismTokens.spectrum(dark)), maxLines = 1)
                Text(d.subtitle, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            Icon(PrismIcons.ReplayFilled, null, Modifier.align(Alignment.TopEnd), tint = scheme.onSurface)
        }
        // The year so far as a ring of split light (how far through the year), the time in its middle.
        ReplayCardStyle.RING -> Row(
            base.pane(shape).border(0.7.dp, glassEdge(), shape).clickable(onClick = onClick).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 9.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(scheme.onSurface.copy(alpha = 0.08f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(
                        Brush.sweepGradient(hues + hues.first(), center), -90f, 360f * d.yearDone, false, Offset(inset, inset), arc,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(yearPercent(d.yearDone), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light), color = scheme.onSurface)
                    Text(tr(R.string.of, d.year), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(tr(R.string.replay, d.year))
                Spacer(Modifier.height(4.dp))
                Text(d.total, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraLight), color = scheme.onSurface, maxLines = 1)
                Text(d.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // The top song's cover sets the color: blown up behind a scrim, and sharp beside the words.
        ReplayCardStyle.COVER -> Box(base.clip(shape).background(Color(0xFF141414)).clickable(onClick = onClick)) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.35f)))))
            Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp).clip(ui.art).background(Color.White.copy(alpha = 0.12f))) {
                    if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(tr(R.string.replay_your_1, d.year), color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.height(4.dp))
                    Text(d.topSong ?: tr(R.string.your_year_so_far), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    d.topSongArtist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f), maxLines = 1) }
                    Spacer(Modifier.height(8.dp))
                    Text(tr(R.string.listened_2, d.total), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                    d.includes?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
                }
            }
        }
        // A slanted band of refracted light down the left edge, the card's words beside it.
        ReplayCardStyle.STRIPE -> Box(
            base.pane(shape).border(0.7.dp, glassEdge(), shape)
                .drawBehind {
                    val band = 64.dp.toPx()
                    val slant = 26.dp.toPx()
                    val each = band / hues.size
                    hues.forEachIndexed { i, h ->
                        val x = i * each
                        drawPath(Path().apply {
                            moveTo(x + slant, 0f); lineTo(x + slant + each + 0.6f, 0f); lineTo(x + each + 0.6f, size.height); lineTo(x, size.height); close()
                        }, h.copy(alpha = if (dark) 0.92f else 0.85f))
                    }
                }
                .clickable(onClick = onClick).padding(start = 110.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
        ) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Eyebrow(tr(R.string.replay, d.year))
                Spacer(Modifier.height(4.dp))
                Text(d.total, style = big, color = scheme.onSurface, maxLines = 1)
                Text(d.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // The months as big bars along the card, red to violet (the months still to come as faint stubs), under the time.
        ReplayCardStyle.BARS -> Box(base.pane(shape).border(0.7.dp, glassEdge(), shape).clickable(onClick = onClick)) {
            val caption = listOfNotNull(d.graphCaption, d.includes).joinToString(" · ").takeIf { it.isNotEmpty() }
            // A caption line under the time: the bars give it the room.
            GraphBars(d, hues, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (caption != null) 72.dp else 86.dp).padding(horizontal = 18.dp, vertical = 14.dp), slim = false)
            Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 18.dp)) {
                Eyebrow(tr(R.string.replay, d.year))
                Text(d.total, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraLight), color = scheme.onSurface, maxLines = 1)
                caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Icon(PrismIcons.ChevronRight, null, Modifier.align(Alignment.TopEnd).padding(16.dp), tint = scheme.onSurfaceVariant)
        }
        // Full color: pools of the spectrum meeting in light, white type over them, like a Rewind slide.
        ReplayCardStyle.AURORA -> Box(
            base.clip(shape).background(Color(0xFF111111))
                .drawBehind {
                    listOf(Triple(0.08f, 0.2f, 6), Triple(0.45f, 0.95f, 0), Triple(0.78f, 0.15f, 3), Triple(1.0f, 0.85f, 4), Triple(0.3f, 0.6f, 2)).forEach { (x, y, i) ->
                        val p = Offset(size.width * x, size.height * y)
                        val r = size.width * 0.42f
                        val h = hue(i / 6f) // i on a 0–6 scale, whatever the spectrum's length (White light has fewer)
                        drawCircle(Brush.radialGradient(listOf(h.copy(alpha = 0.95f), h.copy(alpha = 0f)), p, r), r, p, blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
                    }
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))))
                }
                .clickable(onClick = onClick).padding(22.dp),
        ) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Eyebrow(tr(R.string.replay, d.year), color = Color.White.copy(alpha = 0.85f))
                Text(d.total, style = big, color = Color.White, maxLines = 1)
                Text(d.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(PrismIcons.ChevronRight, null, Modifier.align(Alignment.TopEnd), tint = Color.White)
        }
    }
}

/**
 * [style] with the listener's own year so far (as Home would show it), for picking a look in Settings. A tap does
 * nothing here.
 */
@Composable
fun ReplayCardPreview(style: ReplayCardStyle, modifier: Modifier = Modifier) {
    val c = com.prism.music.ui.theme.LocalContainer.current
    val year = androidx.compose.runtime.remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    val raw by androidx.compose.runtime.produceState<PreviewYear?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { previewYear(c) }.getOrNull() }
    }
    val y = raw ?: return
    val art = y.cover?.takeIf { style == ReplayCardStyle.COVER }?.let { coil3.compose.rememberAsyncImagePainter(it) }
    val graph = com.prism.music.ui.theme.LocalAppSettings.current.view.replayGraph
    val bars by androidx.compose.runtime.produceState(y.months, graph) {
        value = if (graph == com.prism.music.data.prefs.ReplayGraph.MONTHS) y.months
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { replayCardBars(c.imports.replay(), graph) }.getOrDefault(emptyList()) }
    }
    // As Home's card: no "Includes Spotify" line (owner, 1.8.1).
    val data = ReplayCardData(year, listenTotal(y.ms).full, bars, y.song, y.songArtist, y.artist, y.yearDone, 0, graph)
    ReplayCard(style, data, art, modifier) {}
}

private class PreviewYear(
    val ms: Long, val months: List<Long>, val song: String?, val songArtist: String?, val artist: String?, val cover: String?, val yearDone: Float,
)

/** The listener's year so far, as Home's Replay card reads it. */
private suspend fun previewYear(c: com.prism.music.AppContainer): PreviewYear {
    fun monthStart(m: Int) = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.MONTH, m); set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val start = monthStart(0).timeInMillis
    val now = System.currentTimeMillis()
    val plays = c.imports.replay()
    val months = replayCardBars(plays, com.prism.music.data.prefs.ReplayGraph.MONTHS, now)
    val song = plays.topSongs(start, now, 20).mergeUploads().firstOrNull()
    val artist = plays.topArtists(start, now, 1).firstOrNull()
    return PreviewYear(
        plays.totalMs(start, now), months, song?.title, song?.artistName, artist?.artistName, song?.thumbnail,
        yearFraction(),
    )
}

/** The Ring card's figure: how far through the year, in tenths of a percent, never rounded up ("81.3%"). */
fun yearPercent(fraction: Float): String {
    val tenths = kotlin.math.floor(fraction.toDouble() * 1000).toInt().coerceIn(0, 1000)
    return "${tenths / 10}.${tenths % 10}%"
}

/** How far through this year it is, to the minute (the Ring card's arc and percentage). */
fun yearFraction(now: Long = System.currentTimeMillis()): Float {
    val start = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.YEAR, 1) }
    return ((now - start.timeInMillis).toFloat() / (end.timeInMillis - start.timeInMillis)).coerceIn(0f, 1f)
}

/**
 * The card's bars in hue order, the one now brightest: the year's months ([slim] a small corner chart, else a full row of
 * twelve with the months to come as stubs), or (#286) the last weeks or days, each a bar.
 */
@Composable
private fun GraphBars(d: ReplayCardData, hues: List<Color>, modifier: Modifier, slim: Boolean) {
    val months = d.bars
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(modifier) {
        if (months.isEmpty()) return@Canvas
        val most = months.max().coerceAtLeast(1L).toFloat()
        val slots = if (slim || d.graph != com.prism.music.data.prefs.ReplayGraph.MONTHS) months.size else 12
        val step = size.width / slots
        val w = if (slim) 3.dp.toPx() else step * 0.56f
        for (i in 0 until slots) {
            val x = step * i + (step - w) / 2
            val col = hues[(i * (hues.size - 1)) / (slots - 1).coerceAtLeast(1)]
            if (i >= months.size) {
                drawRoundRect(faint, Offset(x, size.height - w * 0.6f), Size(w, w * 0.6f), CornerRadius(w / 2))
                continue
            }
            val h = (size.height * (months[i] / most)).coerceAtLeast(if (slim) w else w * 0.6f)
            drawRoundRect(
                col.copy(alpha = if (i == months.lastIndex) 1f else if (slim) 0.5f else 0.72f),
                Offset(x, size.height - h), Size(w, h), CornerRadius(if (slim) w / 2 else minOf(w / 2, 6.dp.toPx())),
            )
        }
    }
}

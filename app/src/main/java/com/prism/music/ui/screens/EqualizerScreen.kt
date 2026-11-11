package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.EqPresetStore
import com.prism.music.data.prefs.EqSettings
import com.prism.music.data.prefs.SavedEq
import androidx.compose.runtime.collectAsState
import com.prism.music.playback.AudioEffects
import com.prism.music.playback.EqPresets
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

private const val MAX_DB = 12f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val saved = LocalAppSettings.current.eq
    var eq by remember { mutableStateOf(saved) }
    fun update(new: EqSettings) { eq = new; c.settings.setEq(new) }

    com.prism.music.ui.components.SubPage(
        tr(R.string.equalizer), bottomPadding,
        subtitle = if (eq.enabled) tr(R.string.on, EqPresets.label(eq.preset)) else tr(R.string.off),
        horizontalPadding = 16.dp, spacing = 22.dp,
        actions = { PrismSwitch(eq.enabled, { update(eq.copy(enabled = it)) }) },
    ) {
            // Correction for the headphones themselves comes first (it's applied first, and it's on its own switch).
            item { HeadphoneCorrection() }
            item {
                Group(tr(R.string.t_10_band_equalizer, EqPresets.label(eq.preset))) {
                    EqGraph(eq, Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 8.dp)) { band, db ->
                        update(eq.copy(enabled = true, preset = tr(R.string.custom), bands = eq.bands.toMutableList().also { it[band] = db }))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        EqPresets.labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    }
                    com.prism.music.ui.components.ChoiceFlow(
                        EqPresets.presets.toList(), { it.first == eq.preset }, { EqPresets.label(it.first) }, Modifier.padding(12.dp),
                    ) { (name, bands) -> update(eq.copy(enabled = true, preset = name, bands = bands)) }
                }
            }
            item { YourEqPresets(eq) { update(it) } }
            item {
                Group(tr(R.string.tone)) {
                    SliderRow(tr(R.string.preamp), tr(R.string.t_1f_db).format(eq.preampDb), eq.preampDb, -12f..6f) { update(eq.copy(enabled = true, preampDb = it)) }
                    SliderRow(tr(R.string.bass_boost), "${eq.bassBoost / 10}%", eq.bassBoost.toFloat(), 0f..1000f) { update(eq.copy(enabled = true, bassBoost = it.toInt())) }
                    SliderRow(tr(R.string.loudness), tr(R.string.t_1f_db_2).format(eq.loudnessGainMb / 100f), eq.loudnessGainMb.toFloat(), 0f..1500f) { update(eq.copy(enabled = true, loudnessGainMb = it.toInt())) }
                }
            }
            item {
                val s = LocalAppSettings.current
                Group(tr(R.string.prism_spatial)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(tr(R.string.software_spatial_audio), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        PrismSwitch(s.spatial, { c.settings.setSpatial(it) })
                    }
                    if (s.spatial) SliderRow(tr(R.string.intensity), "${(s.spatialAmount * 100).toInt()}%", s.spatialAmount, 0.1f..1f) { c.settings.setSpatialAmount(it) }
                }
            }
    }
}

/**
 * Your own presets (1.7.8, A3): save the equalizer as it is under a name, share one as a code, paste one in, and pick a
 * preset for the output playing now (the car's, the earbuds') so it plays through that one there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YourEqPresets(eq: EqSettings, onApply: (EqSettings) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val saved by c.eqPresets.saved.collectAsState()
    val outputs by c.eqPresets.outputs.collectAsState()
    val output by c.headphoneEq.output.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SavedEq?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Group(
        tr(R.string.your_presets),
        info = tr(R.string.save_the_equalizer_as_it_is),
    ) {
        if (saved.isNotEmpty()) FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            saved.forEach { p ->
                Box {
                    com.prism.music.ui.components.PrismChip(
                        eq.enabled && eq.preset == p.name, { onApply(p.applyTo(eq)) }, p.name,
                        onLongClick = { menuFor = p },
                    )
                    androidx.compose.material3.DropdownMenu(menuFor == p, { menuFor = null }) {
                        androidx.compose.material3.DropdownMenuItem({ Text(tr(R.string.share_as_a_code)) }, {
                            menuFor = null
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${p.name}: an equalizer preset for Prism\n${EqPresetStore.code(p)}"), null))
                        })
                        androidx.compose.material3.DropdownMenuItem({ Text(tr(R.string.save_the_equalizer_as_it_is_2)) }, { menuFor = null; c.eqPresets.save(p.name, eq) })
                        androidx.compose.material3.DropdownMenuItem({ Text(tr(R.string.delete)) }, { menuFor = null; c.eqPresets.delete(p.name) })
                    }
                }
            }
        }
        Item(tr(R.string.save_as_a_preset), if (saved.isEmpty()) tr(R.string.keep_this_sound_under_a_name) else tr(R.string.hold_a_preset_to_share_or), onClick = { naming = true })
        Item(tr(R.string.add_a_shared_preset), tr(R.string.paste_a_code_someone_sent_you), onClick = {
            val preset = clipboard.getText()?.text?.let { EqPresetStore.parse(it) }
            if (preset == null) Toast.makeText(context, tr(R.string.copy_a_prism_equalizer_code_first), Toast.LENGTH_SHORT).show()
            else {
                c.eqPresets.add(preset)
                onApply(preset.applyTo(eq))
                Toast.makeText(context, tr(R.string.added, preset.name), Toast.LENGTH_SHORT).show()
            }
        })
        output?.let { out ->
            val bound = outputs[out.key]
            Label(
                tr(R.string.for_text, out.name),
                info = tr(R.string.whenever_plays_the_music_it_plays, out.name),
            )
            val names = listOf<String?>(null) + saved.map { it.name } + EqPresets.presets.keys
            com.prism.music.ui.components.ChoiceFlow(names.distinct(), { it == bound }, { it ?: tr(R.string.as_set) }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                c.eqPresets.bind(out.key, it)
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf(if (eq.preset == "Custom" || eq.preset in EqPresets.presets) "" else eq.preset) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(tr(R.string.save_as_a_preset)) },
            text = {
                com.prism.music.ui.components.PrismTextField(
                    name, { name = it.take(SavedEq.MAX_NAME) }, singleLine = true,
                    placeholder = { Text(tr(R.string.car_running_late_night)) },
                )
            },
            confirmButton = {
                com.prism.music.ui.components.PrismTextButton({
                    naming = false
                    onApply(c.eqPresets.save(name, eq).applyTo(eq))
                }, enabled = name.isNotBlank()) { Text(tr(R.string.save)) }
            },
            dismissButton = { com.prism.music.ui.components.PrismTextButton({ naming = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        PrismSlider(v, onChange, valueRange = range)
    }
}

/** Draggable 10-band curve. */
@Composable
private fun EqGraph(eq: EqSettings, modifier: Modifier, onBand: (Int, Float) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val alpha = if (eq.enabled) 1f else 0.4f
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width / 10
                fun y(db: Float) = size.height / 2 - (db / MAX_DB) * (size.height / 2 - 16f)
                listOf(-MAX_DB, -6f, 0f, 6f, MAX_DB).forEach { d -> drawLine(grid.copy(alpha = if (d == 0f) 0.9f else 0.35f), Offset(0f, y(d)), Offset(size.width, y(d)), 2f) }
                val pts = eq.bands.mapIndexed { i, db -> Offset(w * i + w / 2, y(db)) }
                val path = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    for (i in 1 until pts.size) {
                        val p0 = pts[i - 1]; val p1 = pts[i]
                        val mx = (p0.x + p1.x) / 2
                        cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                    }
                }
                val fill = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(primary.copy(alpha = 0.35f * alpha), tertiary.copy(alpha = 0.02f))))
                drawPath(path, Brush.horizontalGradient(listOf(primary.copy(alpha = alpha), tertiary.copy(alpha = alpha))), style = Stroke(6f, cap = StrokeCap.Round))
                pts.forEach { drawCircle(primary.copy(alpha = alpha), 11f, it); drawCircle(androidx.compose.ui.graphics.Color.White, 5f, it) }
            }
            Row(Modifier.fillMaxSize()) {
                (0 until 10).forEach { band ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().pointerInput(band) {
                            detectVerticalDragGestures { change, _ ->
                                val h = size.height.toFloat()
                                val db = ((h / 2 - change.position.y) / (h / 2 - 16f) * MAX_DB).coerceIn(-MAX_DB, MAX_DB)
                                onBand(band, (db * 2).toInt() / 2f)
                            }
                        }
                    )
                }
            }
        }
    }
}

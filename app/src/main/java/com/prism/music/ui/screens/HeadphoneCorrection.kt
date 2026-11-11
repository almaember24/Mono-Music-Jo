package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.HeadphoneEq
import com.prism.music.data.friendlyMessage
import com.prism.music.playback.EqProfile
import com.prism.music.playback.responseDb
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.SearchField
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

/**
 * Equalizer → Headphone correction: the headphones playing now, the correction picked for them (its curve and how
 * strongly it's applied), a search of AutoEq's measured headphones, and importing a ParametricEQ file.
 */
@Composable
fun HeadphoneCorrection() {
    val c = LocalContainer.current
    val hq = c.headphoneEq
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val output by hq.output.collectAsState()
    val bindings by hq.bindings.collectAsState()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hq.refreshOutput() }
    val out = output
    val binding = out?.key?.let { bindings[it] }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || out == null) return@rememberLauncherForActivityResult
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.removeSuffix(" ParametricEQ") ?: tr(R.string.imported)
                    EqProfile.parse(name, text, tr(R.string.imported))
                }.getOrNull()
            }
            if (profile == null) Toast.makeText(context, tr(R.string.that_file_has_no_parametriceq_filters), Toast.LENGTH_SHORT).show()
            else hq.bind(out, profile)
        }
    }

    Group(
        tr(R.string.headphone_correction),
        info = tr(R.string.each_pair_of_headphones_keeps_its),
    ) {
        when {
            out == null || !out.headphones -> Note(tr(R.string.plug_in_or_connect_your_headphones))
            binding == null -> {
                Item(tr(R.string.not_corrected, out.name), tr(R.string.pick_these_headphones_from_thousands_measured), onClick = { picking = true }) {
                    Icon(PrismIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                Item(binding.profile.name, "${out.name} · ${binding.profile.source.ifBlank { "AutoEq" }}", onClick = { picking = true }) {
                    PrismTextButton(onClick = { hq.unbind(out.key) }) { Text(tr(R.string.remove)) }
                }
                CorrectionCurve(binding.profile, binding.strength, Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 16.dp, vertical = 6.dp))
                var strength by remember(out.key) { mutableFloatStateOf(binding.strength) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row {
                        Text(tr(R.string.strength), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("${(strength * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    PrismSlider(strength, { strength = it }, valueRange = 0f..1f, onValueChangeFinished = { hq.setStrength(out.key, strength) })
                }
            }
        }
        if (out?.headphones == true) Item(tr(R.string.import_a_parametriceq_file), tr(R.string.an_autoeq_or_equalizerapo_txt_preamp), onClick = {
            import.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        })
        // Corrections picked for other headphones, so they can be removed while those aren't connected.
        bindings.filterKeys { it != out?.key }.forEach { (key, b) ->
            Item(b.deviceName, b.profile.name, onClick = {}) { PrismTextButton(onClick = { hq.unbind(key) }) { Text(tr(R.string.remove)) } }
        }
    }

    if (picking && out != null) HeadphonePicker(modelOf(out.name), onDismiss = { picking = false }) { entry ->
        scope.launch {
            runCatching { hq.profile(entry) }
                .onSuccess { hq.bind(out, it); picking = false }
                .onFailure { Toast.makeText(context, tr(R.string.couldn_t_get_that_correction, it.friendlyMessage()), Toast.LENGTH_SHORT).show() }
        }
    }
}

/**
 * The model in a Bluetooth name people have personalised ("Brandyn's AirPods Pro" → "AirPods Pro"), to start the
 * search with. Generic names ("Wired headphones") start it empty.
 */
internal fun modelOf(deviceName: String): String {
    if (deviceName == tr(R.string.wired_headphones) || deviceName == tr(R.string.headphones)) return ""
    return deviceName.replace(Regex("""^.{1,30}?['’]s\s+"""), "").replace(Regex("""\s*\(.*\)\s*$"""), "").trim()
}

/** Searching AutoEq's index (downloaded the first time, ~1 MB). */
@Composable
private fun HeadphonePicker(initial: String, onDismiss: () -> Unit, onPick: (HeadphoneEq.Entry) -> Unit) {
    val c = LocalContainer.current
    var query by remember { mutableStateOf(initial) }
    var results by remember { mutableStateOf<List<HeadphoneEq.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf<HeadphoneEq.Entry?>(null) }
    LaunchedEffect(query) {
        delay(250)
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        loading = true
        runCatching { c.headphoneEq.search(query) }
            .onSuccess { results = it; error = null }
            .onFailure { error = tr(R.string.couldn_t_load_autoeq_s_list, it.friendlyMessage()) }
        loading = false
    }
    PrismSheet(onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(tr(R.string.your_headphones), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
            SearchField(query, tr(R.string.search_headphones_e_g_sony_wh), { query = it })
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { com.prism.music.ui.components.PrismSpinner(Modifier.size(30.dp)) }
                error != null -> Note(error!!)
                query.isNotBlank() && results.isEmpty() -> Note(tr(R.string.no_headphones_match_autoeq_only_has, query.trim()))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(results, key = { it.path.joinToString("/") + it.name }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = fetching == null) { fetching = e; onPick(e) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PrismIcons.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tr(R.string.measured_by, e.by), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (fetching == e) com.prism.music.ui.components.PrismSpinner(Modifier.size(20.dp), stroke = 2.dp)
                    }
                }
            }
        }
    }
}

/** The correction's curve, 20 Hz to 20 kHz on a log scale, ±12 dB, with 0 dB marked. */
@Composable
private fun CorrectionCurve(profile: EqProfile, strength: Float, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val points = remember(profile, strength) {
        (0..120).map { i -> val hz = 20.0 * 1000.0.pow(i / 120.0); hz to profile.responseDb(hz, strength = strength.toDouble()) }
    }
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))) {
        val range = 12f
        fun x(hz: Double) = (log10(hz / 20) / 3).toFloat() * size.width
        fun y(db: Double) = size.height / 2 - (db.toFloat().coerceIn(-range, range) / range) * (size.height / 2 - 8f)
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { d -> drawLine(grid.copy(alpha = if (d == 0.0) 0.9f else 0.3f), Offset(0f, y(d)), Offset(size.width, y(d)), 2f) }
        listOf(100.0, 1000.0, 10000.0).forEach { hz -> drawLine(grid.copy(alpha = 0.3f), Offset(x(hz), 0f), Offset(x(hz), size.height), 2f) }
        val path = Path().apply { points.forEachIndexed { i, (hz, db) -> if (i == 0) moveTo(x(hz), y(db)) else lineTo(x(hz), y(db)) } }
        drawPath(path, Brush.horizontalGradient(listOf(primary, primary.copy(alpha = 0.7f))), style = Stroke(5f, cap = StrokeCap.Round))
    }
}

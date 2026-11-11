package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.ui.components.ActionHeader
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.screens.pitchText
import com.prism.music.ui.screens.speedText
import com.prism.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

/** One of the sheet's quick picks: a speed and a pitch together (1.7.8, R12). */
private class SpeedPreset(val name: String, val speed: Float, val pitch: Int)

/**
 * "Slowed" and "Sped up" as people make them: the pitch moves with the speed, as on a record played at another speed
 * (0.85× is about 3 semitones down, 1.25× about 4 up).
 */
private val PRESETS = listOf(
    SpeedPreset(tr(R.string.as_recorded), 1f, 0),
    SpeedPreset(tr(R.string.slowed), 0.85f, -3),
    SpeedPreset(tr(R.string.sped_up), 1.25f, 4),
    SpeedPreset(tr(R.string.a_little_faster), 1.1f, 0),
)

/**
 * Speed & pitch for songs (1.7.8, R12, Reddit OldCake6036): speed 0.5–2× and pitch ±12 semitones, each on its own, plus
 * quick picks; for now (this session), or kept as what Prism starts with (Settings → Playback & sound). Podcasts keep
 * their own speed; live streams and casting play as they are. Sing's key comes on top of the pitch here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedPitchSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val speed by pc.musicSpeed.collectAsState()
    val pitch by pc.musicPitch.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val isDefault = settings.sound.musicSpeed == speed && settings.sound.musicPitch == pitch
    ActionSheet(song.thumbnail, onDismiss) { _ ->
        ActionHeader(song.thumbnail, tr(R.string.speed_pitch_2), if (speed == 1f && pitch == 0) tr(R.string.as_recorded) else "${speedText(speed)} · ${pitchText(pitch)}")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.speed), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(speedText(speed), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            }
            PrismSlider(speed, { pc.setMusicSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f, steps = 29)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.pitch), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(pitchText(pitch), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            }
            PrismSlider(pitch.toFloat(), { pc.setMusicPitch(it.roundToInt()) }, valueRange = -12f..12f, steps = 23)
            Text(
                tr(R.string.semitones_up_or_down_apart_from),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { p ->
                    PrismChip(speed == p.speed && pitch == p.pitch, { pc.setMusicSpeed(p.speed); pc.setMusicPitch(p.pitch) }, p.name)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrismTextButton({ c.settings.setMusicDefaults(speed, pitch) }, enabled = !isDefault) {
                    Text(if (isDefault) tr(R.string.prism_starts_like_this) else tr(R.string.start_like_this_every_time))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

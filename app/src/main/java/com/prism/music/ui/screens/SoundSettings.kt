package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.prism.music.data.prefs.CrossfadeCurve
import com.prism.music.data.prefs.NORMALIZE_TARGETS
import kotlin.math.roundToInt
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

/**
 * Prism Spatial (a software spatializer, so it works without Dolby Atmos or
 * spatial-audio hardware) and the equalizer, at the foot of Playback & sound's Sound group.
 */
@Composable
fun SoundSettings(onOpenEq: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    Toggle(
        tr(R.string.prism_spatial), tr(R.string.wider_sound_for_any_headphones), s.spatial,
        info = tr(R.string.software_spatial_audio_a_wider_stage),
    ) { c.settings.setSpatial(it) }
    if (s.spatial) SliderSetting(tr(R.string.intensity), s.spatialAmount, 0.1f..1f, { "${(it * 100).toInt()}%" }) { c.settings.setSpatialAmount(it) }
    Item(
        tr(R.string.equalizer),
        if (s.eq.enabled) tr(R.string.on, com.prism.music.playback.EqPresets.label(s.eq.preset)) else tr(R.string.t_10_bands_your_presets_headphone_correction),
        onClick = onOpenEq,
    ) { Chevron() }
}

/** Crossfade's curve as cards, each drawing its two volumes over the blend (1.7.8, A1). */
@Composable
fun CrossfadeCurveCards() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    Label(tr(R.string.how_they_cross), info = tr(R.string.how_the_ending_song_goes_down))
    ChoiceCards(CrossfadeCurve.entries, s.sound.crossfadeCurve, { it.label }, { it.detail }, onSelect = { c.settings.setCrossfadeCurve(it) }) { curve ->
        CurveSample(curve)
    }
}

@Composable
private fun CurveSample(curve: CrossfadeCurve) {
    val incoming = MaterialTheme.colorScheme.primary
    val outgoing = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val steps = 40
        fun path(pick: (Pair<Float, Float>) -> Float) = androidx.compose.ui.graphics.Path().apply {
            for (i in 0..steps) {
                val p = i.toFloat() / steps
                val x = p * size.width
                val y = size.height - pick(curve.gains(p)) * (size.height - 4f) - 2f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawPath(path { it.second }, outgoing, style = stroke)
        drawPath(path { it.first }, incoming, style = stroke)
    }
}

/** Normalize volume's level, as cards with a level bar each (1.7.8, A2), and "Albums as they are". */
@Composable
fun NormalizeLevel() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    // 1.8.0 (#210): Normalize's switch is the first card ("Off"); picking a level turns it on. Same stored settings
    // (normalize + normalizeTarget), so nothing is migrated.
    Label(tr(R.string.same_loudness_for_every_song), info = tr(R.string.each_song_is_measured_on_your) + " " + tr(R.string.how_loud_normalize_volume_makes_every))
    val ink = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val off = Int.MIN_VALUE
    ChoiceCards(
        listOf(off) + NORMALIZE_TARGETS.map { it.first }, if (s.normalize) s.sound.normalizeTarget else off,
        { t -> if (t == off) tr(R.string.off) else NORMALIZE_TARGETS.first { it.first == t }.second },
        { t -> if (t == off) tr(R.string.as_each_song_was_mastered) else "$t LUFS" + when (t) { -14 -> tr(R.string.like_spotify_and_youtube); -16 -> tr(R.string.like_apple_music); -23 -> tr(R.string.like_tv_and_radio); else -> "" } },
        columns = 2, onSelect = { t ->
            if (t == off) c.settings.setNormalize(false) else { c.settings.setNormalizeTarget(t); c.settings.setNormalize(true) }
        },
    ) { t ->
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val f = if (t == off) 0f else ((t + 30f) / 25f).coerceIn(0.05f, 1f)
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            if (f > 0f) drawRoundRect(ink, size = size.copy(width = size.width * f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
    }
    if (s.normalize) Toggle(
        tr(R.string.albums_as_they_are), tr(R.string.an_album_keeps_its_own_quiet), s.sound.normalizeAlbums,
        info = tr(R.string.while_an_album_plays_through_every),
    ) { c.settings.setNormalizeAlbums(it) }
}

/** Speed and pitch each time Prism starts, and the fades (1.7.8, A4, A5, R12). */
@Composable
fun SpeedAndFades() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    SliderSetting(
        tr(R.string.speed_when_prism_starts), s.sound.musicSpeed, 0.5f..2f, { speedText(it) }, steps = 29,
        info = tr(R.string.songs_start_at_this_speed_each),
    ) { c.settings.setMusicDefaults(it, s.sound.musicPitch); c.player.setMusicSpeed(it) }
    SliderSetting(
        tr(R.string.pitch_when_prism_starts), s.sound.musicPitch.toFloat(), -12f..12f, { pitchText(it.roundToInt()) }, steps = 23,
        info = tr(R.string.moves_every_song_up_or_down),
    ) { c.settings.setMusicDefaults(s.sound.musicSpeed, it.roundToInt()); c.player.setMusicPitch(it.roundToInt()) }
    SliderSetting(
        tr(R.string.sleep_timer_fades_out), s.sound.sleepFadeSeconds.toFloat(), 0f..60f, { if (it < 1f) tr(R.string.off) else "${it.roundToInt()} s" }, steps = 11,
        info = tr(R.string.the_music_fades_out_over_the),
    ) { c.settings.setSleepFade(it.roundToInt()) }
    Toggle(
        tr(R.string.fade_on_pause_and_play), tr(R.string.a_quick_fade_instead_of_a), s.sound.pauseFade,
        info = tr(R.string.pausing_fades_the_sound_out_over),
    ) { c.settings.setPauseFade(it) }
}

/** "1×", "0.85×", "1.25×". */
fun speedText(speed: Float): String = "%.2f".format(speed).trimEnd('0').trimEnd('.') + "×"

/** "As recorded", "+3", "−2" (semitones). */
fun pitchText(semitones: Int): String = when {
    semitones == 0 -> tr(R.string.as_recorded)
    semitones > 0 -> "+$semitones"
    else -> "−${-semitones}"
}
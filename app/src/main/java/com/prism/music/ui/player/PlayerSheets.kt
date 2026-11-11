package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SliderDefaults
import com.prism.music.playback.VideoSyncState
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.MonoStyle
import kotlin.math.roundToInt

/** Plain-language codec name from a codec / mime string ("opus" → "Opus", "mp4a.40.2" → "AAC"). */
fun codecName(codec: String): String = when {
    codec.contains("opus", true) -> tr(R.string.opus)
    codec.contains("mp4a", true) || codec.contains("aac", true) -> "AAC"
    codec.contains("vorbis", true) -> tr(R.string.vorbis)
    codec.contains("flac", true) -> "FLAC"
    codec.contains("alac", true) -> "ALAC"
    // Uncompressed audio (WAV, AIFF) reaches the player as "audio/raw".
    codec.contains("raw", true) || codec.contains("pcm", true) || codec.contains("wav", true) -> "PCM"
    codec.contains("mp3", true) || codec.contains("mpeg", true) -> "MP3"
    codec.isBlank() -> "—"
    else -> codec.substringBefore('.').uppercase()
}

fun sampleRateText(hz: Int): String = if (hz <= 0) "—" else if (hz % 1000 == 0) "${hz / 1000} kHz" else tr(R.string.t_1f_khz).format(hz / 1000f)

/**
 * Stats for nerds as one of the player's panels: the artwork tucks into the header, as it does for
 * lyrics and the queue, and the stats sit on the player's own backdrop below it.
 */
@Composable
fun NerdStatsPanel(modifier: Modifier = Modifier) {
    val stats by LocalContainer.current.nerdStats.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Text(tr(R.string.stats_for_nerds), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        // Nothing has been decoded yet (e.g. a restored queue that hasn't started).
        if (stats.codec.isBlank() && stats.streamBitrateKbps <= 0) {
            Text(tr(R.string.play_the_song_to_see_its), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        // The essentials, big: what the audio is.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(tr(R.string.codec), codecName(stats.codec), Modifier.weight(1f))
            StatTile(tr(R.string.bitrate), stats.streamBitrateKbps.takeIf { it > 0 }?.let { tr(R.string.kbps_2, it) } ?: "—", Modifier.weight(1f))
            StatTile(tr(R.string.sample_rate), sampleRateText(stats.sampleRate), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        StatTile(tr(R.string.source), stats.source.ifBlank { "—" }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        val rows = listOf(
            tr(R.string.video_id) to stats.videoId,
            tr(R.string.source) to stats.source,
            tr(R.string.stream) to listOfNotNull(stats.itag.takeIf { it > 0 }?.let { tr(R.string.itag, it) }, stats.container.takeIf { it.isNotBlank() }, stats.client.takeIf { it.isNotBlank() }?.let { tr(R.string.client, it) }).joinToString(" · "),
            tr(R.string.codec) to listOfNotNull(stats.codec.takeIf { it.isNotBlank() }, stats.decoder.takeIf { it.isNotBlank() }).joinToString(" · "),
            tr(R.string.bitrate) to tr(R.string.kbps_2, stats.streamBitrateKbps) + if (stats.contentLength > 0) tr(R.string.t_2f_mb).format(stats.contentLength / 1_048_576f) else "",
            tr(R.string.format) to tr(R.string.hz_ch, stats.sampleRate, stats.channels),
            tr(R.string.output) to tr(R.string.hz, stats.outputEncoding, stats.outputSampleRate) + if (stats.offload) tr(R.string.offload) else "",
            tr(R.string.device) to stats.outputDevice,
            tr(R.string.spatializer) to stats.spatializer,
            tr(R.string.loudness) to (stats.loudnessDb?.let {
                (if (stats.loudnessMeasured) tr(R.string.t_1f_lufs).format(it) else tr(R.string.t_1f_db_youtube).format(it)) + tr(R.string.gain_1f_db).format(stats.normalizationGainDb)
            } ?: "—"),
            tr(R.string.effects) to stats.effects,
            tr(R.string.buffer) to tr(R.string.t_1f_s_d_kbps_est).format(stats.bufferMs / 1000f, stats.bandwidthKbps),
            tr(R.string.session) to tr(R.string.underruns, stats.audioSessionId, stats.underruns),
        ) + if (stats.videoResolution.isNotBlank()) listOf(tr(R.string.video) to tr(R.string.dropped, stats.videoResolution, stats.videoCodec, stats.droppedFrames)) else emptyList()
        rows.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MonoStyle, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(92.dp))
                Text(v.ifBlank { "—" }, style = MonoStyle, color = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

/** How far lyrics can be moved either way, in ms (#109). */
const val MAX_LYRIC_OFFSET_MS = 120_000L

/**
 * Per-song lyric timing: nudge earlier / later, plus how the lyrics were
 * matched to the music video when one is playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsTimingSheet(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val song by pc.currentSong.collectAsState()
    val offset by pc.lyricOffset.collectAsState()
    val sync by pc.videoSync.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    val scheme = MaterialTheme.colorScheme
    fun fmt(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "%.1f s".format(kotlin.math.abs(ms) / 1000f)
    // The slider spans ±15 s, and grows to keep a bigger offset (set with the buttons) on it with room to spare; only the
    // buttons grow it, so it never shifts under a dragging finger.
    fun reachFor(ms: Long) = maxOf(15f, kotlin.math.ceil(kotlin.math.abs(ms) / 5000f) * 5f + 5f).coerceAtMost(MAX_LYRIC_OFFSET_MS / 1000f)
    var reach by remember { mutableFloatStateOf(reachFor(pc.lyricOffset.value)) }
    // Up to two minutes either way (#109: some lyrics are an intro or a whole verse out, and 15 s wasn't enough).
    fun nudge(d: Long) {
        val to = (offset + d).coerceIn(-MAX_LYRIC_OFFSET_MS, MAX_LYRIC_OFFSET_MS)
        reach = maxOf(reach, reachFor(to))
        pc.setLyricOffset(to)
    }
    // The same frosted sheet as the player's ⋯ menu it opens from.
    com.prism.music.ui.components.ActionSheet(song?.thumbnail, onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
            Text(tr(R.string.lyrics_timing), style = MaterialTheme.typography.titleLarge)
            Text(
                if (videoMode) tr(R.string.for_this_song_s_music_video) else tr(R.string.for_this_song),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (offset == 0L) tr(R.string.in_sync) else tr(if (offset > 0) R.string.lyrics_earlier else R.string.lyrics_later, fmt(offset)),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(-5000L to "−5", -500L to "−0.5", -100L to "−0.1", 100L to "+0.1", 500L to "+0.5", 5000L to "+5").forEach { (d, label) ->
                    Text(
                        label, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                            .clickable(onClickLabel = tr(R.string.lyrics_seconds, label.replace("−", "-"))) { nudge(d) }.padding(horizontal = 11.dp, vertical = 10.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                Text(tr(R.string.later), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(tr(R.string.earlier), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            PrismSlider(offset / 1000f, { pc.setLyricOffset((it * 10).roundToInt() * 100L) }, valueRange = -reach..reach)
            Text(
                tr(R.string.kept_on_this_phone_for_this),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            if (offset != 0L) PrismTextButton(onClick = { pc.setLyricOffset(0) }, Modifier.align(Alignment.End)) { Text(tr(R.string.reset)) }
            if (videoMode) {
                Spacer(Modifier.height(10.dp))
                Text(tr(R.string.music_video_3), style = MaterialTheme.typography.titleSmall)
                Text(
                    when (val s = sync) {
                        VideoSyncState.Measuring -> tr(R.string.matching_the_lyrics_to_the_video)
                        is VideoSyncState.Synced -> when {
                            kotlin.math.abs(s.lagMs) < 150 -> tr(R.string.matched_automatically_the_video_lines_up)
                            s.lagMs > 0 -> tr(R.string.matched_automatically_the_song_starts_into, fmt(s.lagMs).removePrefix("+"))
                            else -> tr(R.string.matched_automatically_the_video_skips_the, fmt(-s.lagMs).removePrefix("−"))
                        }
                        VideoSyncState.Unmatched -> tr(R.string.couldn_t_match_this_video_to)
                        VideoSyncState.Off -> tr(R.string.the_video_uses_the_song_s)
                    },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                if (sync is VideoSyncState.Synced || sync == VideoSyncState.Unmatched) {
                    PrismTextButton(onClick = { pc.resyncVideo() }) {
                        Text(if (sync == VideoSyncState.Unmatched) tr(R.string.try_again) else tr(R.string.match_again))
                    }
                }
            }
        }
    }
}

/**
 * "Your best part": where this song's best part starts, set by the listener. Opens at their own (or the most replayed
 * part, or where the song is now); pick it on the bar, nudge it, hear it, save it. Saved, it's what the Best part button,
 * "Play from the best part", Rewind and Blend previews use for this song. Times are in the song's own (lyrics') timeline.
 */
@Composable
fun BestPartSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val song by pc.currentSong.collectAsState()
    val s = song ?: run { onDismiss(); return }
    val current by c.bestParts.current.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val duration = (s.durationSec * 1000L).coerceAtLeast(1L)
    val mine = remember(s.id) { c.bestParts.mine(s.id) }
    var auto by remember(s.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(s.id) { auto = c.bestParts.automaticMs(s) }
    var at by remember(s.id) {
        mutableStateOf((mine ?: current?.takeIf { it.songId == s.id }?.startMs ?: pc.lyricPosition).coerceIn(0L, duration))
    }
    fun fmt(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    fun set(ms: Long) { at = ms.coerceIn(0L, duration - 1_000L) }
    com.prism.music.ui.components.ActionSheet(s.thumbnail, onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
            Text(tr(R.string.your_best_part), style = MaterialTheme.typography.titleLarge)
            Text(
                tr(R.string.where_best_part_takes_you_in),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Text(fmt(at), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), modifier = Modifier.align(Alignment.CenterHorizontally))
            // Where it'd be without the listener's own: looked up, or (until then) what the player already has.
            val automatic = auto ?: current?.takeIf { it.songId == s.id && !it.mine }?.startMs
            Text(
                when {
                    automatic != null && kotlin.math.abs(at - automatic) < 1_000 -> tr(R.string.the_part_people_replay_most)
                    mine != null && at == mine -> tr(R.string.your_pick)
                    else -> tr(R.string.pick_a_moment_on_the_bar)
                },
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            PrismSlider(at.toFloat() / duration, { set((it * duration).toLong()) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5_000L to "−5 s", 5_000L to "+5 s").forEach { (d, label) ->
                    Text(
                        label, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                            .clickable { set(at + d) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
                val now = pc.lyricPosition
                Text(
                    tr(R.string.now, fmt(now)), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                        .clickable { set(pc.lyricPosition) }.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrismTextButton(onClick = { pc.seekToLyric(at) }) { Text(tr(R.string.listen_from_here)) }
                Spacer(Modifier.weight(1f))
                com.prism.music.ui.components.PrismButton(onClick = { scope.launch { c.bestParts.setMine(s, at); onDismiss() } }) { Text(tr(R.string.save)) }
            }
            if (mine != null) PrismTextButton(onClick = { scope.launch { c.bestParts.setMine(s, null); onDismiss() } }) {
                Text(if (auto != null) tr(R.string.back_to_the_part_people_replay) else tr(R.string.forget_my_best_part))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val sleepAt by pc.sleepAt.collectAsState()
    val endOfSong by pc.sleepEndOfSong.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.sleep_timer)) },
        text = {
            Column {
                if (endOfSong) Text(tr(R.string.stops_when_this_song_ends), color = MaterialTheme.colorScheme.primary)
                else if (sleepAt > 0) Text(tr(R.string.stops_in_min, ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1), color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(endOfSong, { pc.setSleepEndOfSong(true); onDismiss() }, { Text(tr(R.string.end_of_song)) })
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(false, { pc.setSleepTimer(m); onDismiss() }, { Text(tr(R.string.min, m)) })
                    }
                }
            }
        },
        confirmButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.close)) } },
        dismissButton = if (sleepAt > 0 || endOfSong) ({
            PrismTextButton(onClick = { pc.setSleepTimer(0); pc.setSleepEndOfSong(false); onDismiss() }) { Text(tr(R.string.turn_off)) }
        }) else null,
    )
}

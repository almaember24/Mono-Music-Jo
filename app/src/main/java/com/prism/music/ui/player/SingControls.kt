package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.prism.music.data.ModelStore
import com.prism.music.playback.sing.words
import com.prism.music.data.model.Song
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.rememberHaptics
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sing's state on screen (1.7.8, V): whether its button is being held as a slider (the player's controls stay up for it),
 * and the word that shows under the progress bar for a moment after a change ("Sing", "Sing off", "Voice 62%").
 */
object SingUi {
    var holding by androidx.compose.runtime.mutableStateOf(false)
    var word by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    /** Bumped with each [say], so the same word said twice shows again. */
    var wordStamp by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun say(text: String) { word = text; wordStamp++ }
    fun quiet() { word = null }
}

/** "Voice 62%", or "Voice none" at the bottom. */
private fun voiceWord(level: Float) = if (level <= 0.005f) tr(R.string.voice_none) else tr(R.string.voice, (level * 100).toInt())

/**
 * Sing tapped (its button, the player's button row): on or off, said under the progress bar. Before the engine is on
 * the phone, [onNeedsDownload] (its one-time download sheet).
 */
fun toggleSing(c: com.prism.music.AppContainer, onNeedsDownload: () -> Unit) {
    if (!c.sing.engineReady()) { onNeedsDownload(); return }
    val on = !c.sing.on.value
    c.sing.setOn(on)
    SingUi.say(if (!on) tr(R.string.sing_off) else if (c.player.currentSong.value?.let { c.sing.store.complete(it.id) } == true) tr(R.string.sing) else tr(R.string.sing_getting_ready))
}

/**
 * The buttons under the open lyrics: Sing's at the lower right (nothing for what can't be sung: live streams, podcasts),
 * and with Settings → Lyrics → "Lyrics source button" (1.7.9, asked for after 1.7.8 moved the source to the lyrics'
 * end) the source at the lower left.
 */
@Composable
fun LyricsButtonsRow(song: Song, state: LyricsState, onChangeSource: () -> Unit, onNeedsDownload: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    // Every phone sings (1.8.x): one Sing's engine doesn't run on uses the quick way.
    val singable = c.sing.canSing(song)
    val source = LocalAppSettings.current.lyricsSourceButton
    if (!singable && !source) return
    Box(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (source) LyricsSourceButton(state, onChangeSource, Modifier.align(Alignment.CenterStart).padding(end = if (singable) SING_BUTTON + 12.dp else 0.dp))
        if (singable) Box(Modifier.align(Alignment.CenterEnd)) { SingButton(song, onNeedsDownload) }
    }
}

/**
 * Where the lyrics come from, as a glass button of Sing's height and shape: the source (and "Karaoke" when the words
 * are timed), and a tap opens the Lyrics source sheet.
 */
@Composable
fun LyricsSourceButton(state: LyricsState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = LocalUi.current.button
    val haptics = rememberHaptics()
    val lyrics = state.lyrics
    Row(
        modifier.height(SING_BUTTON).clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable(onClickLabel = tr(R.string.change_lyrics_source)) { haptics.tick(); onClick() }
            .testTag("lyricsSourceButton")
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.loading) com.prism.music.ui.components.PrismSpinner(Modifier.size(16.dp), stroke = 2.dp)
        else Icon(PrismIcons.Lyrics, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                state.loading -> tr(R.string.looking_for_lyrics)
                lyrics == null -> tr(R.string.no_lyrics_found)
                else -> lyrics.source.label
            },
            style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        // Timed word by word (" · Karaoke"), quieter than the source. Android drops the string's leading space, so the
        // gap is padding (it read "LyricsPlus· Karaoke" on the phone).
        if (lyrics?.wordSynced == true && !state.loading) Text(
            tr(R.string.karaoke).trimStart(), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.55f),
            maxLines = 1, modifier = Modifier.padding(start = 5.dp),
        )
        Icon(PrismIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(16.dp), tint = Color.White.copy(alpha = 0.5f))
    }
}

/**
 * Sing, as on Apple Music (1.7.8, V): one small glass button with the microphone. A tap turns Sing on or off. Held
 * (or slid straight away), it grows upward into a tall glass slider for how much of the voice stays in, filled in the
 * split light from the bottom, with a tick at none, half and full; it shrinks back on release. While the song's vocals
 * are being worked out, a ring of light runs round it. The key is in the player's ⋯ → Sing.
 */
@Composable
fun SingButton(song: Song, onNeedsDownload: () -> Unit) {
    val c = LocalContainer.current
    val sing = c.sing
    val on by sing.on.collectAsState()
    val level by sing.level.collectAsState()
    val progress by sing.progress.collectAsState()
    val ready = sing.store.complete(song.id)
    val p = if (on && !ready) progress[song.id] ?: 0f else null
    val haptics = rememberHaptics()
    val shape = LocalUi.current.button
    val reduce = LocalAppSettings.current.reduceMotion
    val grow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val levelNow by rememberUpdatedState(level)
    val onNow by rememberUpdatedState(on)
    val ring = PrismTokens.spectrum
    val size = SING_BUTTON
    Box(
        Modifier.size(size)
            // The slider, drawn upward from the button while it's held.
            .drawBehind {
                val g = grow.value
                if (g <= 0f) return@drawBehind
                val h = this.size.height + (SING_SLIDER - SING_BUTTON).toPx() * g
                val top = this.size.height - h
                val r = CornerRadius(this.size.width / 2)
                drawRoundRect(Color.Black.copy(alpha = 0.35f * g), Offset(0f, top), Size(this.size.width, h), r)
                drawRoundRect(Color.White.copy(alpha = 0.10f * g), Offset(0f, top), Size(this.size.width, h), r)
                val fill = h * levelNow
                if (fill > 0f) drawRoundRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(PrismTokens.spectrumColors(true).map { it.second }.reversed(), top, this.size.height),
                    Offset(0f, this.size.height - fill), Size(this.size.width, fill), r, alpha = g,
                )
                drawRoundRect(Color.White.copy(alpha = 0.22f * g), Offset(0f, top), Size(this.size.width, h), r, style = Stroke(1.dp.toPx()))
            }
            .clip(shape)
            .background(Color.White.copy(alpha = if (on) 0.18f else 0.10f))
            .then(if (on) Modifier.border(1.dp, ring, shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), shape))
            // Getting the song ready: a ring of light runs round the button as far as it's done.
            .drawWithContent {
                drawContent()
                val f = p ?: return@drawWithContent
                val outline = shape.createOutline(this.size, layoutDirection, this)
                val path = when (outline) {
                    is androidx.compose.ui.graphics.Outline.Generic -> outline.path
                    is androidx.compose.ui.graphics.Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                    is androidx.compose.ui.graphics.Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                }
                val measure = PathMeasure().apply { setPath(path, true) }
                val part = Path()
                measure.getSegment(0f, measure.length * f.coerceIn(0.02f, 1f), part, true)
                drawPath(part, ring, style = Stroke(2.dp.toPx()))
            }
            .semantics {
                contentDescription = tr(R.string.sing)
                stateDescription = if (!on) tr(R.string.off) else voiceWord(level)
                progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                onClick(if (on) tr(R.string.turn_sing_off) else tr(R.string.turn_sing_on)) { toggleSing(c, onNeedsDownload); true }
                setProgress(tr(R.string.voice_2)) { v -> if (on) { sing.setLevel(v.coerceIn(0f, 1f)); true } else false }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // A tap, or a hold / slide that turns into the slider.
                    val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                            if (!change.pressed) return@withTimeoutOrNull true
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    if (tapped == true) { haptics.tick(); toggleSing(c, onNeedsDownload); return@awaitEachGesture }
                    if (!sing.engineReady()) { onNeedsDownload(); return@awaitEachGesture }
                    if (!onNow) sing.setOn(true)
                    SingUi.holding = true
                    haptics.tick()
                    scope.launch { if (reduce) grow.snapTo(1f) else grow.animateTo(1f, tween(180)) }
                    val travel = (SING_SLIDER - SING_BUTTON).toPx() + SING_BUTTON.toPx() / 2
                    val startLevel = levelNow
                    var y = down.position.y
                    var last = startLevel
                    SingUi.say(voiceWord(startLevel))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        y += change.positionChange().y
                        change.consume()
                        val v = (startLevel + (down.position.y - y) / travel).coerceIn(0f, 1f)
                        // A tick at none, half and full.
                        if (listOf(0f, 0.5f, 1f).any { m -> (last < m && v >= m) || (last > m && v <= m) }) haptics.tick()
                        if (v != last) { sing.setLevel(v); SingUi.say(voiceWord(v)); last = v }
                    }
                    SingUi.holding = false
                    scope.launch { if (reduce) grow.snapTo(0f) else grow.animateTo(0f, tween(220)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (on) PrismIcons.MicFilled else PrismIcons.Mic, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * The word under the progress bar (1.7.8, V): what Sing just did, for two seconds, in place of the stream quality.
 * Returns whether it's showing.
 */
@Composable
fun BoxScope.SingWord(): Boolean {
    val word = SingUi.word ?: return false
    val stamp = SingUi.wordStamp
    LaunchedEffect(stamp, SingUi.holding) { if (!SingUi.holding) { kotlinx.coroutines.delay(2000); SingUi.quiet() } }
    Text(
        word, Modifier.align(Alignment.Center),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = Color.White.copy(alpha = 0.85f),
    )
    return true
}

private val SING_BUTTON = 44.dp
private val SING_SLIDER = 200.dp
/**
 * Sing's sheet: the one-time download, Sing on or off, how much of the lead voice stays in, and the key moved for
 * your range; with how far the song's vocals are worked out, and why if they can't be.
 */
@Composable
fun SingSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val sing = c.sing
    val scope = rememberCoroutineScope()
    val on by sing.on.collectAsState()
    val level by sing.level.collectAsState()
    val semitones by sing.semitones.collectAsState()
    val progress by sing.progress.collectAsState()
    val problem by sing.problem.collectAsState()
    val models by c.models.states.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { c.models.refresh() }
    val runtime = ModelStore.Model.singRuntime()
    val downloading = listOfNotNull(runtime, ModelStore.Model.SING_MODEL).mapNotNull { models[it] as? ModelStore.State.Downloading }
    val failed = listOfNotNull(runtime, ModelStore.Model.SING_MODEL).mapNotNull { models[it] as? ModelStore.State.Failed }.firstOrNull()
    // Where Sing's engine doesn't run, the quick way needs no download.
    val ready = runtime == null || (models[runtime] == ModelStore.State.Ready && models[ModelStore.Model.SING_MODEL] == ModelStore.State.Ready)
    val singable = sing.canSing(song)
    val method by sing.method.collectAsState()

    ActionSheet(song.thumbnail, onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PrismIcons.MicFilled, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.sing), style = MaterialTheme.typography.titleLarge)
                    Text(tr(R.string.the_song_s_own_music_with), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ready) PrismSwitch(on, { sing.setOn(it) })
            }
            Spacer(Modifier.height(16.dp))
            when {
                !ready && runtime != null -> {
                    Text(
                        tr(R.string.sing_separates_each_song_s_voice, ModelStore.Model.sizeText(ModelStore.Model.SING_MODEL, runtime)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        downloading.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            com.prism.music.ui.components.PrismSpinner(Modifier.size(18.dp), stroke = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(tr(R.string.downloading_5, (downloading.first().fraction * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            if (failed != null) Text(tr(R.string.couldn_t_download_it, failed.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            com.prism.music.ui.components.PrismButton(
                                onClick = { scope.launch { if (sing.downloadEngine()) { sing.setOn(true); SingUi.say(tr(R.string.sing_getting_ready)); onDismiss() } } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (failed != null) tr(R.string.try_again) else tr(R.string.download_and_turn_on)) }
                        }
                    }
                }
                !singable -> Text(tr(R.string.sing_works_with_songs_not_live), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    // Read again as the separation moves along ([progress]): taking the voice out, the quick way
                    // standing in while the song's own separation goes, or the voice out (1.8.x).
                    progress.size
                    val now = sing.status(song.id)
                    val status = when {
                        !on -> tr(R.string.off_the_song_plays_as_it)
                        now == com.prism.music.playback.sing.Sing.Status.Out -> tr(R.string.ready_the_voice_is_out)
                        now is com.prism.music.playback.sing.Sing.Status.Starting && problem != null -> problem!!
                        else -> now.words()
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The quick way, said plainly: why, and what it can and can't do.
                    if (method == com.prism.music.playback.sing.Separation.QUICK) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (runtime == null) tr(R.string.sing_doesn_t_run_on_this) else tr(R.string.sing_quick_slow),
                            Modifier.testTag("singQuickNote"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Text(tr(R.string.lead_voice), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(if (level <= 0.005f) tr(R.string.none) else "${(level * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    PrismSlider(level, { sing.setLevel(it) }, enabled = on, valueRange = 0f..1f)
                    Text(tr(R.string.keep_a_little_of_it_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.key), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        PrismTextButton(onClick = { sing.setSemitones(semitones - 1) }, enabled = on && semitones > -6) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Text(if (semitones == 0) tr(R.string.original) else "%+d".format(semitones), Modifier.width(72.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        PrismTextButton(onClick = { sing.setSemitones(semitones + 1) }, enabled = on && semitones < 6) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                    Text(tr(R.string.moves_the_song_up_or_down), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

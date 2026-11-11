package com.prism.music.ui.tv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.playback.CarLyricsCard
import com.prism.music.playback.sing.Separation
import com.prism.music.playback.sing.SingPace
import com.prism.music.playback.sing.words
import com.prism.music.tr
import com.prism.music.ui.player.rememberLyricClock
import com.prism.music.ui.player.rememberLyricsState
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sing on a TV (1.8.0, plan §2): karaoke night. The speed check times Sing's full and light separation on this TV with
 * the playing song (1.8.x); a TV that keeps up with one of them gets it, one too slow, or whose processor Sing's engine
 * doesn't run on, gets the quick way (what's in the middle of the mix taken out) rather than nothing. The answer is kept.
 */
object TvSing {
    /** [SLOW] and [UNSUPPORTED]: Sing the quick way. */
    enum class State { UNCHECKED, CHECKING, READY, SLOW, UNSUPPORTED;
        fun label(): String = when (this) {
            UNCHECKED -> tr(R.string.tv_sing_check)
            // What it's doing (the row said "Play a song, then check" through the check, as if nothing had started).
            CHECKING -> tr(R.string.tv_sing_checking_now)
            READY -> tr(R.string.tv_sing_ready)
            SLOW -> tr(R.string.tv_sing_too_slow)
            UNSUPPORTED -> tr(R.string.tv_sing_no_cpu)
        }

        /**
         * Sing is in the player's ⋯: always but while the check runs. Unchecked, the first use times this TV as it goes
         * (and the quick way stands in meanwhile); the check only says ahead what it'll do.
         */
        val sings: Boolean get() = this != CHECKING
    }

    private val _state = MutableStateFlow(State.UNCHECKED)
    val state: StateFlow<State> = _state

    private fun prefs(context: Context) = context.getSharedPreferences("tv_sing", Context.MODE_PRIVATE)

    /** The kept answer of an earlier check (called as the settings open); no check needed where only the quick way runs. */
    fun load(context: Context) {
        if (_state.value != State.UNCHECKED) return
        _state.value = prefs(context).getString("state", null)?.let { s -> State.entries.firstOrNull { it.name == s } }?.takeIf { it != State.CHECKING }
            ?: if (com.prism.music.data.ModelStore.Model.singRuntime() == null) State.UNSUPPORTED else State.UNCHECKED
    }

    /** What the check's speeds (seconds of song a second; null: not timed) say this TV can do. */
    fun verdict(full: Double?, light: Double?): State? = when {
        full == null && light == null -> null
        SingPace.choose(true, full, light) == Separation.QUICK -> State.SLOW
        else -> State.READY
    }

    /** Checks this TV with the playing song; what to tell the listener. */
    suspend fun check(c: AppContainer): String {
        if (_state.value == State.CHECKING) return State.CHECKING.label()
        if (!c.sing.fullEngine) return keep(c, State.UNSUPPORTED)
        // Nothing playing to time it with: said so (it used to say "checking" and do nothing).
        val song = c.player.currentSong.value?.takeIf { c.sing.canSing(it) } ?: return tr(R.string.tv_sing_play_first)
        if (!c.sing.engineReady() && !c.sing.downloadEngine()) return tr(R.string.sing_s_engine_isn_t_downloaded)
        var speeds: Pair<Double?, Double?> = null to null
        var problem: String? = null
        val before = _state.value
        val result = checking(c.sing.on.value, c.sing::setOn) {
            runCatching { speeds = c.sing.timeWays(song) }.onFailure { problem = it.message }
            verdict(speeds.first, speeds.second) ?: State.UNCHECKED
        }
        // Nothing timed (the song couldn't be had): the answer stays as it was.
        if (result == State.UNCHECKED) { _state.value = before; return problem ?: tr(R.string.couldn_t_get_this_song_ready) }
        val best = listOfNotNull(speeds.first, speeds.second).maxOrNull()
        return keep(c, result) + (best?.let { "\n" + tr(R.string.tv_sing_measured, "%.1f".format(it)) } ?: "")
    }

    /**
     * The check's [run] with Sing on, then Sing as it was ([wasOn]) and the state as it was if it's cut short (the
     * listener left, the TV closed Prism): never left on, nor stuck at "checking".
     */
    internal suspend fun checking(wasOn: Boolean, setOn: (Boolean) -> Unit, run: suspend () -> State): State {
        val before = _state.value
        _state.value = State.CHECKING
        var done = false
        try {
            setOn(true)
            return run().also { done = true }
        } finally {
            if (!wasOn) setOn(false)
            if (!done) _state.value = before
        }
    }

    private fun keep(c: AppContainer, s: State): String {
        _state.value = s
        prefs(c.app).edit().putString("state", s.name).apply()
        return s.label()
    }
}

/**
 * Sing (TV): the lyrics large with the voice taken out, and how far the song is got ready (or why it can't be). Right
 * from the lyrics reaches the key's − and + (then Timing), as the lyrics page's Timing; ↑ ↓ and OK are the lyrics'.
 * Sing's engine is fetched first if it isn't on the TV (the check fetched it once; it may have gone since). Leaving
 * the page switches Sing off again (the song plays on as it is).
 */
@Composable
fun TvSingPage(nav: TvNav) {
    val c = LocalContainer.current
    // GitHub #342: with Sing let go of the song (its watchdog on a slow TV) and its page still open, the ambient screen
    // was free to come, cover and all, in the lyrics' place. Lyrics are what this page is for, Sing on or off. And Back
    // out of it after a long song with the remote put down (Back isn't an input Prism hears on a newer TV) brought the
    // ambient screen up as Sing went off, not the player; the quiet starts over as the page closes.
    HoldsAmbient()
    val song by c.player.currentSong.collectAsState()
    val now = rememberTvNow(c)
    val semitones by c.sing.semitones.collectAsState()
    val s = song
    if (s == null || now == null) { TvStarting { nav.back() }; return }
    var engineMissing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        c.sing.setOn(true)
        // Leaving the page, the song in hand is still finished for a while (Sing's grace), not thrown away.
        onDispose { c.sing.setOn(false) }
    }
    // Without its engine Sing would get nothing ready; the songs lined up are got ready, the playing one first.
    LaunchedEffect(s.id) {
        if (!c.sing.engineReady() && !c.sing.downloadEngine()) { engineMissing = true; return@LaunchedEffect }
        engineMissing = false
        // The playing song from where it plays, then the next (the player keeps it one ahead from there).
        val queue = c.player.queue.value.map { it.song }
        val at = queue.indexOfFirst { it.id == s.id }
        c.sing.prepare(listOf(s) + queue.drop(at + 1).take(1), c.player.position)
    }
    val state = rememberLyricsState(s)
    // Made again at each seek, so one while paused still moves the lines; paused, it stands still.
    val jumps by c.player.jumps.collectAsState()
    val clock = androidx.compose.runtime.key(jumps) { rememberLyricClock(now.playing) }
    val accent = rememberTvAccent(now.art)
    val lines = state.lines
    val current by remember(lines) { derivedStateOf { CarLyricsCard.lineAt(lines, clock.value).coerceAtLeast(0) } }
    val progress by c.sing.progress.collectAsState()
    val problem by c.sing.problem.collectAsState()
    val method by c.sing.method.collectAsState()
    // What Sing has on disk for the song (its files and their notes), read off the main thread and only when that can
    // have changed (the page redraws twice a second as the song plays).
    val disk by androidx.compose.runtime.produceState(TvSingDisk(), s.id, progress[s.id], method) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TvSingDisk(c.sing.store.complete(s.id), c.sing.status(s.id)) }
    }
    val status = tvSingStatus(engineMissing, progress[s.id], disk.complete, problem)
    val scheme = MaterialTheme.colorScheme
    TvLyricsPlace(
        now, lines, current, clock, state.loading, state.unavailable, state.source?.label, accent, onPlayPause = { c.player.togglePlay() },
        status = status,
        buttons = {
            // What Sing has for the song, honestly: the voice being taken out, the quick way standing in, or out.
            disk.status?.let { TvChip(it.words(), PrismIcons.Mic, scheme.primary, scheme.onPrimary) }
            TvIconButton(PrismIcons.Remove, tr(R.string.tv_key_line, "−"), { c.sing.setSemitones(c.sing.semitones.value - 1) }, Modifier.tvItem("$TAG_SING/keydown"))
            TvChip(tr(R.string.tv_key_line, if (semitones > 0) "+$semitones" else "$semitones"), null, scheme.surfaceVariant, scheme.onSurface)
            TvIconButton(PrismIcons.Add, tr(R.string.tv_key_line, "+"), { c.sing.setSemitones(c.sing.semitones.value + 1) }, Modifier.tvItem("$TAG_SING/keyup"))
        },
    ) { ms -> c.player.seekToLyric(ms) }
}

/** What Sing has on disk for a song: all of it done ([complete]), and what that means ([status], null until read). */
internal data class TvSingDisk(val complete: Boolean = false, val status: com.prism.music.playback.sing.Sing.Status? = null)

/** What Sing's page says of the playing song: being got ready (how far), ready, or why it can't be. */
fun tvSingStatus(engineMissing: Boolean, progress: Float?, complete: Boolean, problem: String?): String = when {
    engineMissing -> tr(R.string.sing_s_engine_isn_t_downloaded)
    progress != null -> tr(R.string.getting_this_song_ready_you_can, (progress * 100).toInt())
    complete -> tr(R.string.ready_the_voice_is_out)
    problem != null -> problem
    else -> tr(R.string.getting_this_song_ready)
}

/** Test tags: Sing's page (its key's − and +). */
const val TAG_SING = "tv_sing"

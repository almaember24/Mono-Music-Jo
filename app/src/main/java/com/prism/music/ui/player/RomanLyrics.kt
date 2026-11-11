package com.prism.music.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.prism.music.data.ModelStore
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.roman.Script
import com.prism.music.data.prefs.RomanizeMode
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import java.util.Locale

/** How the playing song's lyrics are romanized: Settings → Lyrics, or the listener's pick for this song. */
@Composable
fun romanizeMode(songId: String): RomanizeMode {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val overrides by c.romanizer.overrides.collectAsState()
    return when (overrides[songId]) {
        false -> RomanizeMode.OFF
        true -> if (settings.romanize == RomanizeMode.OFF) RomanizeMode.UNDER else settings.romanize
        null -> settings.romanize
    }
}

/**
 * [state]'s lines in Latin letters, line for line (null for lines that need none), or null while romanization is
 * off for the song or still being worked out.
 */
@Composable
fun rememberRomanized(state: LyricsState): List<LyricLine?>? {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val overrides by c.romanizer.overrides.collectAsState()
    val models by c.models.states.collectAsState()
    val mode = romanizeMode(state.song.id)
    val lines = state.lines
    // Scripts of the phone's own language are left as they are, unless asked for (all songs, or this one).
    val skip = remember(settings.romanizeAll, overrides[state.song.id]) {
        if (settings.romanizeAll || overrides[state.song.id] == true) emptySet() else Script.native(Locale.getDefault().language)
    }
    // Downloading the Japanese reading data mid-song spells that song out again, properly.
    val jaReady = models[ModelStore.Model.JAPANESE] == ModelStore.State.Ready
    val roman by produceState<List<LyricLine?>?>(null, state.song.id, lines, skip, mode == RomanizeMode.OFF, jaReady) {
        value = null
        if (mode == RomanizeMode.OFF || lines.isEmpty()) return@produceState
        if (lines.none { (Script.foreignIn(it.text) - skip).isNotEmpty() }) return@produceState
        // Only the lyrics' text goes to Google (a few KB), so data saver doesn't hold it back.
        value = runCatching { c.romanizer.romanize(state.song.id, lines, skip, network = c.network.online.value, title = state.song.title) }
            .onFailure { android.util.Log.w("PrismRoman", "Couldn't romanize the lyrics: $it") }.getOrNull()
            ?.takeIf { r -> r.any { it != null } }
    }
    return roman
}

/** Whether [state]'s lyrics have any line in a script other than Latin letters (the ⋯ menu offers romanizing). */
fun hasForeignScript(state: LyricsState): Boolean = state.lines.any { Script.foreignIn(it.text).isNotEmpty() }

package com.prism.music.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** How often the TV's progress line moves (it's a thin line seen from across the room: twice a second is smooth). */
private const val TICK_MS = 500L

/** A time as the player shows it: "3:41", "1:02:05". */
fun tvTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** What the TV shows of [song] playing: everything the player and Home's hero need, worked out in one place. */
fun tvNow(
    song: Song,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    liked: Boolean,
    shuffle: Boolean,
    repeat: Int,
    hasVideo: Boolean,
    next: Song?,
): TvNow {
    val total = durationMs.takeIf { it > 0 } ?: (song.durationSec * 1000L)
    val fraction = if (total > 0) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    return TvNow(
        title = song.title,
        artist = song.artistText,
        album = song.album?.title?.takeIf { it.isNotBlank() },
        art = song.thumbnail,
        fraction = fraction,
        played = tvTime(positionMs),
        total = if (song.isLive) "" else tvTime(total),
        playing = playing,
        liked = liked,
        shuffle = shuffle,
        repeat = repeat,
        // Every song can have lyrics; the lyrics screen says when none are found.
        hasLyrics = !song.isEpisode,
        hasVideo = hasVideo,
        next = next?.let { "${it.title} · ${it.artistText}" },
    )
}

/** The song playing now as [TvNow] (null when nothing is lined up), its progress moving while it's on screen. */
@Composable
fun rememberTvNow(c: AppContainer): TvNow? {
    val song by c.player.currentSong.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    val shuffle by c.player.shuffle.collectAsState()
    val repeat by c.player.repeatMode.collectAsState()
    val video by c.player.videoAvailable.collectAsState()
    val queue by c.player.queue.collectAsState()
    val upNext by c.player.upNext.collectAsState()
    val likedIds by c.library.likedIds.collectAsState()
    var position by remember { mutableLongStateOf(c.player.position) }
    var duration by remember { mutableLongStateOf(c.player.duration) }
    // A seek moves the line at once (paused too: the video's ← →, the remote's rewind and fast forward).
    val jumps by c.player.jumps.collectAsState()
    // Only while the screen is on view (stopped, nothing's watching it move).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(song?.id, playing, lifecycle, jumps) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                position = c.player.position
                duration = c.player.duration
                if (!playing) break
                delay(TICK_MS)
            }
        }
    }
    val s = song ?: return null
    val next = upNext.firstOrNull()?.let { i -> queue.getOrNull(i)?.song }
    return tvNow(s, playing, position, duration, s.id in likedIds, shuffle, repeat, video, next)
}

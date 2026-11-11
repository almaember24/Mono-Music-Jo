package com.prism.music.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Runs [tick] at once and then every [everyMs], but only while what's showing it is on screen: Prism's window started
 * (picture-in-picture counts), or for a page, the page being the one shown. Each time it comes back on screen it ticks
 * straight away, so what it feeds is up to date before the first frame.
 *
 * A `delay` loop in an effect otherwise carries on while Prism is out of sight (Compose only stops drawing then, not
 * effects), waking the phone a few times a second for a clock or a poll nobody can see.
 */
suspend fun Lifecycle.tickWhileShown(everyMs: Long, tick: suspend () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (isActive) {
            tick()
            delay(everyMs)
        }
    }
}

/**
 * Seconds as they pass, a frame at a time, while Prism is on screen (for a drawing that drifts, like the progress bar's
 * Ribbons): [onFrame] gets the time since the last frame. Follows the phone's animation speed (off: no frames at all).
 */
suspend fun Lifecycle.framesWhileShown(onFrame: (seconds: Float) -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        var last = 0L
        while (isActive) {
            androidx.compose.animation.core.withInfiniteAnimationFrameNanos { t ->
                if (last != 0L) onFrame((t - last) / 1e9f)
                last = t
            }
        }
    }
}

/**
 * How many times the screen has come back (resumed), as a key for what's read from Android's own settings pages and may
 * have been changed there while Prism was away.
 */
@androidx.compose.runtime.Composable
fun rememberResumeCount(): Int {
    var count by androidx.compose.runtime.remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) count++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return count
}

/**
 * Whether the full player hides the screens under it: open, done sliding in, and not being pulled down (a pull shows
 * the page behind it). The player is opaque, so while it hides them, what under it only moves for the eye (animated
 * backgrounds, the playing bars) holds still instead of being drawn every frame for nobody, beside the player's own.
 */
@Stable
class PlayerCover {
    /** The player is open and has finished sliding in (MainActivity). */
    var settled by mutableStateOf(false)
    /** The player is being pulled down or is sliding closed (the player itself). */
    var pulled by mutableStateOf(false)
    /** The player is on its way out (MainActivity); false again, before it has gone, when it's opened again. */
    var leaving by mutableStateOf(false)
    val hides: Boolean get() = settled && !pulled
}

/** The one [PlayerCover] (provided over the whole app, so the player can say it's being pulled). */
val LocalPlayerCover = staticCompositionLocalOf { PlayerCover() }

/**
 * True for what's under the full player (the screens, tabs and mini player) while it's hidden by it; false everywhere
 * else, the player itself included, and in the settings' previews.
 */
val LocalHiddenByPlayer = compositionLocalOf<() -> Boolean> { { false } }

/**
 * Whether the music is playing now (MainActivity reads it from the player): the playing bars beside the current song
 * bounce only then and stand still while it's paused (#302). Elsewhere (the settings' previews) they move.
 */
val LocalMusicPlaying = compositionLocalOf<() -> Boolean> { { true } }

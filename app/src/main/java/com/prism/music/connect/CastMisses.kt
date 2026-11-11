package com.prism.music.connect

/**
 * When a renderer counts as lost: [max] polls in a row with no answer. #239 (1.8.3): a poll that failed while Android
 * held Prism's network back (it had left the screen) says nothing about the speaker and isn't counted; before, ~28 s of
 * that ended a Sonos session that was playing fine.
 */
internal class CastMisses(private val max: Int) {
    var count = 0
        private set

    fun ok() { count = 0 }

    /** A poll went unanswered; true when the renderer is now taken as lost. */
    fun failed(blocked: Boolean): Boolean {
        if (blocked) return false
        return ++count >= max
    }
}

package com.prism.music.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.Player

/**
 * Settings → Playback & sound → "Pause when muted" (#157): turning the phone's media volume all the way down pauses the
 * music, turning it back up plays on, but only when it was the mute that paused it (a pause of your own stays).
 * Android announces volume changes through its settings, which this watches while the setting is on.
 */
class MuteWatch(private val context: Context, private val player: Player, private val on: () -> Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var pausedByMute = false
    private var last = -1
    private var registered = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = check()
    }

    /** Starts or stops watching to match the setting. Call when settings change, on the main thread. */
    fun update() {
        val want = on()
        if (want == registered) return
        registered = want
        if (want) {
            last = volume()
            runCatching { context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer) }
        } else {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
            pausedByMute = false
        }
    }

    fun release() {
        if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }

    private fun volume() = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)

    private fun check() {
        if (!on()) return
        val now = volume()
        if (now == last || now < 0) return
        val was = last
        last = now
        when {
            now == 0 && was > 0 && player.playWhenReady -> {
                pausedByMute = true
                player.pause()
                AudioDiagnostics.log("state", "paused: the phone's media volume went to 0")
            }
            now > 0 && was == 0 && pausedByMute -> {
                pausedByMute = false
                if (!player.playWhenReady && player.mediaItemCount > 0) {
                    player.play()
                    AudioDiagnostics.log("state", "playing on: the phone's media volume came back")
                }
            }
            // Anything else the listener did meanwhile (played or paused by hand) ends the mute's claim.
            now > 0 -> pausedByMute = false
        }
    }
}

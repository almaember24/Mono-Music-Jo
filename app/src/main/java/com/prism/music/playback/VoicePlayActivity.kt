package com.prism.music.playback

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * "Hey Google, play … on Prism" from the phone (Assistant sends `MEDIA_PLAY_FROM_SEARCH` to an activity, and only lists
 * apps that declare it as music sources; Gemini plays from its own list of linked services, #362, so it can't reach
 * Prism whatever's declared here). No screen: the query and the voice extras
 * (what kind of thing was asked for, the artist, album or playlist made out: [VoiceSearch]) go to the playback
 * service as a search request, and this closes at once. In the car, Android Auto asks the session directly
 * (the same request, through its play-from-search). It may be started with the phone locked, as it is in the car:
 * the manifest lets it run over the lock screen, where it shows nothing.
 */
class VoicePlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent?.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
        val extras = VoiceSearch.extrasOf(intent?.extras)
        val app = applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackServices.target(app)))).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            val item = MediaItem.Builder()
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build())
                .build()
            // The service starts playing once the request's songs are in: a queued prepare/play here would also run
            // after a request that found nothing, and resume the old queue.
            controller.setMediaItem(item)
            // Let the service pick up the request before this controller goes away.
            controller.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) controller.release() }
            })
            android.os.Handler(app.mainLooper).postDelayed({ controller.release() }, 20_000)
        }, ContextCompat.getMainExecutor(app))
        finish()
    }
}

package com.prism.music.playback

import com.prism.music.tr
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.prism.music.PrismActivity
import com.prism.music.R
import com.prism.music.container

/**
 * The "Prism" Quick Settings tile (1.7.8, Reddit): play or pause what Prism has lined up, with the song under its
 * name; lit while it plays. With nothing lined up (or Prism not running) a tap opens Prism. Holding it opens Prism too
 * (Android's tile long press, PrismActivity's QS_TILE_PREFERENCES filter).
 */
class PlayTileService : TileService() {
    override fun onStartListening() = refresh()

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val pc = applicationContext.container.player
        val ready = pc.player?.let { it.mediaItemCount > 0 } == true
        if (ready) {
            pc.togglePlay()
            // The state lands a moment later; the tile follows it then ([update] from the service), this is a first guess.
            qsTile?.apply { state = if (state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
            return
        }
        val intent = Intent(this, PrismActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val pc = applicationContext.container.player
        val song = pc.currentSong.value?.takeIf { pc.player != null }
        val playing = song != null && pc.isPlaying.value
        tile.label = tr(R.string.app_name)
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = song?.title ?: tr(R.string.widget_open)
        tile.contentDescription = song?.let { "${if (playing) "Pause" else "Play"} ${it.title}" } ?: tr(R.string.widget_open)
        tile.updateTile()
    }

    companion object {
        /** Asks Android to show the tile afresh (playing ↔ paused, another song); nothing happens when it isn't added. */
        fun update(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, PlayTileService::class.java)) }
        }
    }
}

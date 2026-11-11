package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.prism.music.AppContainer
import com.prism.music.connect.TvSync.cleaned
import com.prism.music.playback.toMediaItem
import com.prism.music.playback.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * A Prism TV playing along with a phone (1.8.0), for as long as the connection lasts ([run], blocking): it plays what the
 * phone says ([FollowState], by [TvSync.plan]), says where it is about once a second ([TvWhere]), and says what's done
 * on it, its remote, its own player and its queue ([TvAct]), for the phone to do too. One phone at a time: a new one
 * takes over. When the connection ends, for whatever reason, the TV pauses.
 */
internal class TvFollower(private val c: AppContainer, private val link: HLink, private val from: String) : Player.Listener {
    /** The last state taken in (main thread). */
    private var taken: FollowState? = null
    @Volatile private var seq = 0L
    /** Set while this applies the phone's state: what the player says then is the phone's doing, not the TV's. */
    private var applying = false
    private var appliedSeekAt = 0L
    /** The TV's own acts so far ([TvAct.n]); a state the phone sent before the latest isn't taken ([TvSync.current]). */
    private var acts = 0L
    /** The song the TV last played into the next from by itself, until the phone has caught up ([TvSync.plan]). */
    private var reachedFrom: String? = null
    private var lastSong: String? = null
    @Volatile private var over = false
    private val out = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-follow-out").apply { isDaemon = true } }

    fun run(first: FollowState) {
        synchronized(TvFollower) { active?.stop(); active = this }
        onMain { start(first) }
        val ticker = Thread({
            while (!over) {
                where()?.let { send(HMsg.Where(it)) }
                runCatching { Thread.sleep(WHERE_EVERY_MS) }
            }
        }, "tv-follow-where").apply { isDaemon = true; start() }
        try {
            while (!over) {
                when (val m = link.read() ?: break) {
                    is HMsg.FollowNow -> { onMain { apply(m.state.cleaned()) }; where()?.let { send(HMsg.Where(it)) } }
                    is HMsg.Volume -> setVolume(m.level)
                    is HMsg.FollowEnd -> break
                    else -> Unit
                }
            }
        } finally {
            over = true
            ticker.interrupt()
            onMain { end() }
            out.shutdown()
            link.close()
        }
    }

    /** Another phone took over: this connection ends. */
    private fun stop() {
        over = true
        send(HMsg.FollowEnd())
        out.execute { link.close() }
    }

    private fun start(first: FollowState) {
        c.queue.followingHost = true
        apply(first.cleaned())
        c.player.afterPending { c.player.player?.addListener(this) }
        Log.i(TAG, "playing along with $from")
    }

    private fun end() {
        c.player.player?.removeListener(this)
        synchronized(TvFollower) { if (active !== this) return; active = null }
        c.queue.followingHost = false
        c.player.setPlaying(false)
        Log.i(TAG, "stopped playing along with $from")
    }

    private fun apply(f: FollowState) {
        if (over) return
        // Sent before the phone had the TV's last act (a keepalive crossing a Next or a Pause on the TV): out of date.
        if (!TvSync.current(f, acts)) return
        seq = f.seq
        taken = f
        val p = c.player.player
        val have = if (p == null) TvSync.Have(emptyList(), -1, -1) else TvSync.Have(
            (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItemIndex,
            if (p.duration > 0) (p.duration - p.currentPosition).coerceAtLeast(0) else -1,
        )
        val plan = TvSync.plan(have, f, reachedFrom)
        // The phone has caught up with the song the TV went on to by itself.
        if (f.songs.getOrNull(f.index)?.id != reachedFrom) reachedFrom = null
        applying = true
        try {
            when {
                plan.load -> c.player.follow(f.songs, plan.startMs, f.title.ifBlank { tr(R.string.from, from) }, plan.playing, index = f.index)
                plan.around && p != null -> around(p, f)
            }
            if (plan.seekTo != null && !plan.load) { appliedSeekAt = System.currentTimeMillis(); c.player.seekTo(plan.seekTo) }
            c.player.afterPending {
                val pp = c.player.player ?: return@afterPending
                if (!plan.load && !plan.wait && pp.playWhenReady != plan.playing) c.player.setPlaying(plan.playing)
                pp.repeatMode = if (f.repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        } finally {
            applying = false
        }
    }

    /** The songs around the one playing set to the phone's, without a gap in it. */
    private fun around(p: Player, f: FollowState) {
        val idx = p.currentMediaItemIndex
        if (idx + 1 < p.mediaItemCount) p.removeMediaItems(idx + 1, p.mediaItemCount)
        p.addMediaItems(f.songs.drop(f.index + 1).map { it.toMediaItem() })
        if (idx > 0) p.removeMediaItems(0, idx)
        if (f.index > 0) p.addMediaItems(0, f.songs.take(f.index).map { it.toMediaItem() })
    }

    // ------------------------------------------------------------ What's done on the TV

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val before = lastSong
        lastSong = mediaItem?.mediaId
        if (applying || over) return
        // A song ending into the next: the phone gets there by itself (a moment later, its muted player being behind).
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { if (before != mediaItem?.mediaId) reachedFrom = before; return }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        val p = c.player.player ?: return
        val f = taken ?: return
        val id = mediaItem?.mediaId ?: return
        val at = p.currentMediaItemIndex
        val sameList = p.mediaItemCount == f.songs.size && (0 until p.mediaItemCount).all { p.getMediaItemAt(it).mediaId == f.songs[it].id }
        when {
            // What the phone asked for (applied later than expected): nothing to say.
            sameList && at == f.index -> Unit
            sameList -> act(TvAct(TvAct.PICK, index = at, songId = id))
            else -> {
                // A queue of the TV's own (picked on the TV): the phone plays it too.
                val from = (at - TvSync.MAX_SONGS / 2).coerceAtLeast(0)
                val songs = (from until minOf(p.mediaItemCount, from + TvSync.MAX_SONGS)).mapNotNull { i ->
                    p.getMediaItemAt(i).toSong()?.let(TvSync::goes)
                }
                val i = songs.indexOfFirst { it.id == id }
                if (i >= 0) act(TvAct(TvAct.REPLACE, positionMs = p.currentPosition, index = i, songs = songs))
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (applying || over || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
        // The phone is playing a file of its own (the TV can't have it): Play here would play the last song the TV had
        // under it, so it's turned back at once.
        if (playWhenReady && taken?.index == -1) { applying = true; try { c.player.setPlaying(false) } finally { applying = false }; return }
        act(TvAct(if (playWhenReady) TvAct.PLAY else TvAct.PAUSE))
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (applying || over || reason != Player.DISCONTINUITY_REASON_SEEK || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        // The phone's own seek, done a moment later than asked.
        if (System.currentTimeMillis() - appliedSeekAt < 1_500) return
        act(TvAct(TvAct.SEEK, positionMs = newPosition.positionMs))
    }

    private fun act(a: TvAct) {
        send(HMsg.Act(a.copy(n = ++acts)))
        out.execute { where()?.let { link.send(HMsg.Where(it)) } }
    }

    // ------------------------------------------------------------ Where it is

    private fun where(): TvWhere? = runCatching {
        onMain {
            val p = c.player.player ?: return@onMain null
            TvWhere(
                p.currentMediaItem?.mediaId, p.currentPosition, p.playWhenReady, p.playbackState == Player.STATE_BUFFERING,
                p.duration.coerceAtLeast(0), volume(), seq,
            )
        }
    }.getOrNull()

    private fun volume(): Int {
        val a = c.app.getSystemService(android.media.AudioManager::class.java) ?: return 0
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun setVolume(level: Int) {
        val a = c.app.getSystemService(android.media.AudioManager::class.java) ?: return
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        runCatching { a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (level.coerceIn(0, 100) * max + 50) / 100, 0) }
    }

    private fun send(m: HMsg) {
        if (out.isShutdown) return
        runCatching { out.execute { link.send(m) } }
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    companion object {
        private const val TAG = "PrismTvFollow"
        /** How often the TV says where it is. */
        const val WHERE_EVERY_MS = 1_000L
        /** The phone this TV is playing along with now. */
        @Volatile private var active: TvFollower? = null
    }
}

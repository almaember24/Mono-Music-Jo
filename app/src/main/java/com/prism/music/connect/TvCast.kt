package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.AppContainer
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Playing on a Prism TV (1.8.0), the phone's side, as casting is: the phone's own player carries on, muted, so the
 * queue, the notification, lyrics and play counts all go on as ever, and it leads what plays. Whenever that changes
 * (a song, play or pause, a seek, the queue, shuffle, repeat) the TV is sent the songs around the one playing
 * ([FollowState], [TvSync.window]); the TV leads where, so the phone's playhead follows the TV's ([TvSync.follow]).
 * What's done on the TV (its remote, its queue) comes back as [TvAct]s and is done here too. Runs on the main thread;
 * the connection's reading on a thread of its own, its writing on another.
 */
internal class TvCast(
    private val c: AppContainer,
    val renderer: Renderer,
    private val link: HLink,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    /** The TV went (the connection was lost, or it stopped playing along): why. */
    private val lost: (String) -> Unit,
    /** Its volume, or whether the phone plays the song itself, changed. */
    private val changed: () -> Unit,
) {
    /** The TV's volume, 0–100, once it has said. */
    @Volatile var volume: Int? = null
        private set
    /** The song playing can't go to the TV (a file on the phone): the phone plays it itself, the TV waits. */
    var local = false
        private set

    private var seq = 0L
    /** The last state that moved the TV (another song, a seek): its place isn't followed until the TV has it. */
    private var syncSeq = 0L
    private var sentSong: String? = null
    private var sent: List<Int> = emptyList()
    private var sentIds: List<String> = emptyList()
    private var lastSeekAt = 0L
    private var lastFollowAt = 0L
    private var lastCommandAt = 0L
    /** A place the phone went to itself, to stay with the TV (or because the TV went there): not sent back. */
    private var followTarget: Long? = null
    private var lastWhere: TvWhere? = null
    private var lastWhereAt = 0L
    /** The last of the TV's own acts taken in here, said back with every state ([FollowState.acts]). */
    private var actsTaken = 0L
    /** When the volume was last set here: the TV's word on it, sent before that reached it, is old for a moment. */
    private var volumeSetAt = 0L
    private var closed = false
    private var soon: Job? = null
    private var keepalive: Job? = null
    private val out = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-cast-out").apply { isDaemon = true } }

    /** The first state sent (with the connection), so what comes next counts on from it. */
    fun took(first: Built) {
        seq = first.state.seq
        syncSeq = seq
        sent = first.indices
        sentIds = first.state.songs.map { it.id }
        sentSong = sentIds.getOrNull(first.state.index)
        local = first.state.index < 0
    }

    fun start() {
        Thread({
            while (true) {
                val m = link.read() ?: break
                scope.launch { onMessage(m) }
            }
            scope.launch { if (!closed) lost(tr(R.string.lost_the_connection_to, renderer.name)) }
        }, "tv-cast-in").apply { isDaemon = true; start() }
        // A word every few seconds, so a quiet connection still shows it's there (and the TV's catches a lost one).
        keepalive = scope.launch { while (isActive && !closed) { delay(KEEPALIVE_MS); send() } }
    }

    /** Playing along is over ([back]: the music comes back to the phone). */
    fun end(back: Boolean) {
        if (closed) return
        closed = true
        soon?.cancel(); keepalive?.cancel()
        runCatching { out.execute { link.send(HMsg.FollowEnd(back)); link.close() } }
        out.shutdown()
    }

    fun setVolume(level: Int) {
        volume = level.coerceIn(0, 100)
        volumeSetAt = SystemClock.elapsedRealtime()
        post(HMsg.Volume(volume!!))
    }

    /**
     * Sends what plays now and where (the connection is up): the first state was made before it, which can be minutes
     * when the phone had to pair first (the review: the TV started the song and place from then).
     */
    fun resync() {
        val p = player() ?: return
        send(seekTo = p.currentPosition)
    }

    /** Where the TV was last heard to be in the song playing here, as of now (null: not known lately). */
    fun tvPlace(): Long? {
        val w = lastWhere ?: return null
        val p = player() ?: return null
        if (w.songId != p.currentMediaItem?.mediaId || SystemClock.elapsedRealtime() - lastWhereAt > 3_000) return null
        return w.positionMs + if (w.playing && !w.buffering) SystemClock.elapsedRealtime() - lastWhereAt else 0
    }

    // ------------------------------------------------------------ The phone's player changing

    fun onMediaItemTransition(reason: Int) {
        val p = player() ?: return
        lastCommandAt = SystemClock.elapsedRealtime()
        val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        // A song picked here starts where the phone starts it (a queue resumed mid-song); one played into, from the top.
        sendSoon(if (!natural && p.currentPosition > 1_500) p.currentPosition else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) 0 else -1)
    }

    fun onPlayWhenReadyChanged() { lastCommandAt = SystemClock.elapsedRealtime(); sendSoon() }

    fun onSeek(sameSong: Boolean, positionMs: Long) {
        if (!sameSong) return
        val target = followTarget
        followTarget = null
        // The phone going to where the TV is: nothing to send back.
        if (target != null && kotlin.math.abs(positionMs - target) < 400) return
        lastSeekAt = SystemClock.elapsedRealtime()
        send(seekTo = positionMs)
    }

    /** The queue, shuffle or repeat changed: what plays next is sent. */
    fun onQueueChanged() = sendSoon()

    private fun sendSoon(seekTo: Long = -1) {
        soon?.cancel()
        // A seek or a song going at once; the rest gathered (a queue edit is often several changes in a row).
        if (seekTo >= 0) { send(seekTo); return }
        soon = scope.launch { delay(100); send() }
    }

    private fun send(seekTo: Long = -1) {
        if (closed) return
        val p = player() ?: return
        val b = build(p, ++seq, seekTo, c.queue.source.value.title, actsTaken)
        sent = b.indices
        sentIds = b.state.songs.map { it.id }
        val song = sentIds.getOrNull(b.state.index)
        if (seekTo >= 0 || song != sentSong) syncSeq = seq
        sentSong = song
        if (local != (b.state.index < 0)) { local = b.state.index < 0; changed() }
        post(HMsg.FollowNow(b.state))
    }

    private fun post(m: HMsg) {
        if (closed && m !is HMsg.FollowEnd) return
        runCatching { out.execute { link.send(m) } }
    }

    // ------------------------------------------------------------ What the TV says

    private fun onMessage(m: HMsg) {
        if (closed) return
        when (m) {
            is HMsg.Where -> onWhere(m.at)
            is HMsg.Act -> onAct(m.act)
            is HMsg.FollowEnd -> lost(tr(R.string.lost_the_connection_to, renderer.name))
            else -> Unit
        }
    }

    private fun onWhere(w: TvWhere) {
        val now = SystemClock.elapsedRealtime()
        lastWhere = w
        lastWhereAt = now
        // Its volume, unless it's just been set here (a report sent before then would undo it while it's dragged).
        if (volume != w.volume && TvSync.takesVolume(now, volumeSetAt)) { volume = w.volume; changed() }
        val p = player() ?: return
        if (local || w.songId != p.currentMediaItem?.mediaId || w.seq < syncSeq) return
        if (!p.playWhenReady || p.playbackState != Player.STATE_READY) return
        if (now - lastSeekAt < 2_500 || now - lastFollowAt < 3_000 || now - lastCommandAt < 1_500) return
        val target = TvSync.follow(p.currentPosition, w, 0) ?: return
        lastFollowAt = now
        followTarget = target
        p.seekTo(target)
    }

    private fun onAct(a: TvAct) {
        // Taken in (whatever comes of it), and said back at once: the TV doesn't take states from before it.
        actsTaken = maxOf(actsTaken, a.n)
        sendSoon()
        val p = player() ?: return
        lastCommandAt = SystemClock.elapsedRealtime()
        when (a.kind) {
            TvAct.PLAY -> if (!p.playWhenReady) c.player.setPlaying(true)
            TvAct.PAUSE -> if (p.playWhenReady) c.player.setPlaying(false)
            TvAct.SEEK -> { followTarget = a.positionMs; p.seekTo(a.positionMs.coerceAtLeast(0)) }
            TvAct.PICK -> TvSync.pickIndex(a, sent, sentIds, { i -> p.getMediaItemAt(i).mediaId }, p.mediaItemCount, p.currentMediaItemIndex)
                ?.takeIf { it != p.currentMediaItemIndex }?.let { p.seekToDefaultPosition(it) }
            TvAct.REPLACE -> {
                val songs = a.songs.take(TvSync.MAX_SONGS).mapNotNull(TvSync::goes)
                val at = songs.indexOfFirst { it.id == a.songId }.takeIf { it >= 0 } ?: a.index.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
                if (songs.isEmpty()) return
                c.player.playQueue(songs, at, QueueSource(QueueKind.OTHER, tr(R.string.from, renderer.name)))
                if (a.positionMs > 1_500) c.player.afterPending { followTarget = a.positionMs; c.player.seekTo(a.positionMs) }
            }
        }
        Log.i(TAG, "${renderer.name}: ${a.kind}")
    }

    /** A [FollowState] and the phone's queue indices its songs are. */
    class Built(val state: FollowState, val indices: List<Int>)

    companion object {
        private const val TAG = "PrismTvCast"
        private const val KEEPALIVE_MS = 3_000L

        /**
         * What's playing on [p] as the TV is sent it ([TvSync.window]): only what can play there (YouTube songs); a
         * song playing that can't is index -1.
         */
        fun build(p: Player, seq: Long, seekTo: Long, title: String = "", acts: Long = 0): Built {
            val shuffle = p.shuffleModeEnabled
            // Repeat one plays the same song again: the window is the queue's order, the TV repeating it itself.
            val repeat = if (p.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else p.repeatMode
            val tl = p.currentTimeline
            val cur = p.currentMediaItemIndex
            val (window, _) = if (tl.isEmpty || cur == C.INDEX_UNSET) emptyList<Int>() to 0 else TvSync.window(
                cur,
                previous = { i -> tl.getPreviousWindowIndex(i, repeat, shuffle).takeIf { it != C.INDEX_UNSET } },
                next = { i -> tl.getNextWindowIndex(i, repeat, shuffle).takeIf { it != C.INDEX_UNSET } },
            )
            val kept = window.mapNotNull { i -> p.getMediaItemAt(i).toSong()?.let(TvSync::goes)?.let { i to it } }
            val index = kept.indexOfFirst { it.first == cur }
            return Built(
                FollowState(
                    songs = kept.map { it.second }, index = index, playing = p.playWhenReady, seekTo = seekTo, seq = seq,
                    repeatOne = p.repeatMode == Player.REPEAT_MODE_ONE, title = title, acts = acts,
                ),
                kept.map { it.first },
            )
        }
    }
}

package com.prism.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Skip silence for songs (#210, 1.8.0): only the silence before a song's first sound and after its last is left out,
 * never a pause inside it. Media3's own skipper, which songs used before, shortened every pause longer than a tenth of
 * a second (a breath before the last chorus, a stop in a song); podcast episodes still get that one.
 *
 * Which song plays and how long it is comes from the player (on every seek and new song, [onFlush]); across a gapless
 * move to the next song, which the player doesn't announce here, the processor counts its way to the next one, as
 * Sing's VocalStemProcessor does, by the player's timeline as it is now ([timelineChanged]). A song's silent start is
 * held back and dropped when its first sound comes within [EDGE_SECONDS] (a longer one is a quiet intro, played
 * whole); in its last [EDGE_SECONDS], silence is held back, then dropped if the song ends in it or let through if sound
 * comes back. What's dropped is counted
 * ([Chain]) so the playing position, and the lyrics with it, stay where the sound is, as with Media3's skipper.
 */
@UnstableApi
class EdgeSilenceProcessor : BaseAudioProcessor() {
    /** Settings → Skip silence. */
    @Volatile var enabled = false

    /** The player's repeat and shuffle modes, for working out which song follows (set from the playback thread). */
    @Volatile var repeatMode = Player.REPEAT_MODE_OFF
    @Volatile var shuffle = false

    /** Frames left out since the last flush (Media3 starts its own skipper's count again there too). */
    var skippedFrames = 0L
        private set

    private var rate = 48_000
    private var channels = 2
    private var float = false
    private var frameBytes = 4

    private var timeline: Timeline? = null
    /**
     * The player's timeline since the last flush ([timelineChanged]): songs put in or taken out after the playing one
     * (Play next, queue edits, DJ mode) don't flush the processors, and counting on into the next song by the timeline
     * the last flush brought took the old next song's length and cut pauses inside the real one (1.8.0 review).
     */
    @Volatile private var newTimeline: Timeline? = null
    private var periodUid: Any? = null
    private var periodFrames = -1L
    /** The song frame the next input frame is. */
    private var position = 0L
    /** The song is one to trim: not a podcast episode (Media3's skipper has those) nor a music video's sound. */
    private var trims = true
    /** Still in the silence before the song's first sound. */
    private var leading = false

    /** Silence near the end, held back until it's clear whether the song ends in it. */
    private var held = ByteArray(0)
    private var heldBytes = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        float = enc == C.ENCODING_PCM_FLOAT
        frameBytes = (if (float) 4 else 2) * channels
        return inputAudioFormat
    }

    /** The player's timeline changed (from the player's listener; any thread). Period uids stay the same across it. */
    fun timelineChanged(timeline: Timeline) {
        newTimeline = timeline
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        newTimeline = null
        timeline = streamMetadata.timeline.takeIf { !it.isEmpty }
        periodUid = streamMetadata.periodUid
        position = if (streamMetadata.positionOffsetUs == C.TIME_UNSET) 0 else streamMetadata.positionOffsetUs * rate / 1_000_000
        identify()
        // From the start of a song only: a seek into it plays from there as it is.
        leading = position == 0L
        heldBytes = 0
        skippedFrames = 0
    }

    override fun onQueueEndOfStream() {
        // The last song ended in silence that was held back: it's left out.
        skippedFrames += heldBytes / frameBytes
        heldBytes = 0
    }

    override fun onReset() {
        timeline = null; periodUid = null; periodFrames = -1; position = 0
        held = ByteArray(0); heldBytes = 0; skippedFrames = 0
    }

    /** Which song [periodUid] is, and how long. */
    private fun identify() {
        periodFrames = -1
        trims = true
        val tl = timeline ?: return
        val index = periodUid?.let { tl.getIndexOfPeriod(it) }?.takeIf { it != C.INDEX_UNSET } ?: return
        val period = tl.getPeriod(index, Timeline.Period())
        trims = trims(tl.getWindow(period.windowIndex, Timeline.Window()).mediaItem)
        periodFrames = if (period.durationUs == C.TIME_UNSET) -1 else period.durationUs * rate / 1_000_000
    }

    /** The song played through to its end without the player saying: on to the one that follows. */
    private fun songEnded() {
        skippedFrames += heldBytes / frameBytes
        heldBytes = 0
        position -= periodFrames
        leading = true
        val tl = timeline
        val index = periodUid?.let { tl?.getIndexOfPeriod(it) } ?: C.INDEX_UNSET
        val next = if (tl == null || index == C.INDEX_UNSET) C.INDEX_UNSET
        else tl.getNextPeriodIndex(index, Timeline.Period(), Timeline.Window(), repeatMode, shuffle)
        periodUid = if (next == C.INDEX_UNSET) null else tl!!.getUidOfPeriod(next)
        identify()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        // The queue changed: the same song, in the timeline as it is now (what follows it, and how long it is).
        newTimeline?.let { tl ->
            newTimeline = null
            timeline = tl.takeIf { !it.isEmpty }
            identify()
        }
        // Held-back silence may go out ahead of this buffer.
        val out = replaceOutputBuffer(bytes + heldBytes)
        var frames = bytes / frameBytes
        while (frames > 0) {
            if (periodFrames > 0 && position >= periodFrames) songEnded()
            // Up to the end of this song, so the next one starts afresh. At least a frame: still past the end (no song
            // after it to move on to), none at all would loop for ever or read back (as VocalStemProcessor).
            val n = if (periodFrames > 0) minOf(frames.toLong(), (periodFrames - position).coerceAtLeast(1)).toInt() else frames
            run(inputBuffer, out, n)
            frames -= n
        }
        out.flip()
    }

    private fun run(input: ByteBuffer, out: ByteBuffer, frames: Int) {
        if (!enabled || !trims) {
            if (heldBytes > 0) release(out)
            leading = false
            val limit = input.limit()
            input.limit(input.position() + frames * frameBytes)
            out.put(input)
            input.limit(limit)
            position += frames
            return
        }
        val edge = (EDGE_SECONDS * rate).toLong()
        val tailFrom = if (periodFrames > 0) periodFrames - edge else Long.MAX_VALUE
        // Frames that go out are copied in runs, from where the current run began.
        var runFrom = -1
        repeat(frames) {
            val start = input.position()
            var sound = false
            for (c in 0 until channels) {
                val v = if (float) input.float else input.short / 32768f
                if (v > SILENCE || v < -SILENCE) sound = true
            }
            val passes = when {
                // The start's silence is held back too, as it may not be a gap to skip: silent for longer than this,
                // it's a quiet intro, played whole (1.8.0 review: its first 10 s used to be dropped).
                leading && !sound && position < edge -> { hold(input, start); false }
                leading && !sound -> { release(out); true }
                sound -> {
                    if (leading) {
                        skippedFrames += heldBytes / frameBytes
                        heldBytes = 0
                    } else if (heldBytes > 0) release(out)
                    true
                }
                position >= tailFrom -> { hold(input, start); false }
                else -> true
            }
            if (passes) {
                leading = false
                if (runFrom < 0) runFrom = start
            } else if (runFrom >= 0) {
                copy(input, out, runFrom, start)
                runFrom = -1
            }
            position++
        }
        if (runFrom >= 0) copy(input, out, runFrom, input.position())
    }

    private fun copy(input: ByteBuffer, out: ByteBuffer, from: Int, to: Int) {
        out.put(input.duplicate().apply { limit(to); position(from) })
    }

    private fun hold(input: ByteBuffer, start: Int) {
        if (held.size < heldBytes + frameBytes) held = held.copyOf(maxOf(held.size * 2, heldBytes + frameBytes, rate * frameBytes))
        for (i in 0 until frameBytes) held[heldBytes + i] = input.get(start + i)
        heldBytes += frameBytes
    }

    private fun release(out: ByteBuffer) {
        out.put(held, 0, heldBytes)
        heldBytes = 0
    }

    /**
     * Media3's processor chain with [edges] among [processors]: what it leaves out is added to the skipped count the
     * player's position is worked out with, as Media3's own skipper's is.
     */
    class Chain(private val edges: EdgeSilenceProcessor, processors: Array<AudioProcessor>) : AudioProcessorChain {
        private val inner = DefaultAudioSink.DefaultAudioProcessorChain(*processors)
        override fun getAudioProcessors(): Array<AudioProcessor> = inner.audioProcessors
        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters = inner.applyPlaybackParameters(playbackParameters)
        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = inner.applySkipSilenceEnabled(skipSilenceEnabled)
        override fun getMediaDuration(playoutDuration: Long): Long = inner.getMediaDuration(playoutDuration)
        override fun getSkippedOutputFrameCount(): Long = inner.skippedOutputFrameCount + edges.skippedFrames
    }

    companion object {
        /** Quieter than this (about -54 dBFS) counts as silence. */
        private const val SILENCE = 0.002f
        /** The longest silent start that's skipped (a longer one is an intro), and how near the end silence is held back. */
        const val EDGE_SECONDS = 10.0

        /** A song is trimmed; a podcast episode (Media3's skipper) and a music video's sound (the picture) aren't. */
        fun trims(item: MediaItem): Boolean = item.videoModeId == null && item.toSong()?.isEpisode != true

        /** Whether Media3's skipper runs: only over a podcast episode, and only when podcasts are picked (#325). */
        fun pausesSkipped(isEpisode: Boolean, episodesOn: Boolean): Boolean = isEpisode && episodesOn
    }
}

package com.prism.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Builds audio-only sources normally, and an audio+video merged source when a
 * queue item is switched to its music video. The audio source is always first
 * so the timeline keeps the song's metadata.
 */
@OptIn(UnstableApi::class)
class PrismMediaSourceFactory(
    audioDsf: DataSource.Factory,
    videoDsf: DataSource.Factory,
    localDsf: DataSource.Factory? = null,
    /** A lossless file on the phone to play instead of the stream (the lossless add-on). */
    private val localAudio: ((MediaItem) -> Uri?)? = null,
    /** Live streams: prism://live/<id> resolved to the stream's HLS manifest. */
    liveDsf: DataSource.Factory? = null,
) : MediaSource.Factory {
    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    private val audio = ProgressiveMediaSource.Factory(audioDsf, extractors).setLoadErrorHandlingPolicy(NoteVideoLegFailures(picture = false, DefaultLoadErrorHandlingPolicy()))
    private val video = ProgressiveMediaSource.Factory(videoDsf, extractors).setLoadErrorHandlingPolicy(NoteVideoLegFailures(picture = true, DefaultLoadErrorHandlingPolicy()))
    private val local = localDsf?.let { ProgressiveMediaSource.Factory(it, extractors) }
    private val live = liveDsf?.let { HlsMediaSource.Factory(it).setPlaylistParserFactory(BestLiveAudioParserFactory()) }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        audio.setDrmSessionManagerProvider(drmSessionManagerProvider)
        local?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        video.setDrmSessionManagerProvider(drmSessionManagerProvider)
        live?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        audio.setLoadErrorHandlingPolicy(NoteVideoLegFailures(picture = false, loadErrorHandlingPolicy))
        local?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        video.setLoadErrorHandlingPolicy(NoteVideoLegFailures(picture = true, loadErrorHandlingPolicy))
        live?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (live != null && mediaItem.localConfiguration?.uri?.authority == "live") {
            return live.createMediaSource(mediaItem.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).setCustomCacheKey(null).build())
        }
        val videoId = mediaItem.localConfiguration?.uri?.getQueryParameter("video")
        if (videoId == null) {
            if (local != null) localAudio?.invoke(mediaItem)?.let { uri ->
                return local.createMediaSource(mediaItem.buildUpon().setUri(uri).setCustomCacheKey(null).build())
            }
            val item = if (mediaItem.localConfiguration == null) {
                mediaItem.buildUpon().setUri("prism://audio/${mediaItem.mediaId}").setCustomCacheKey(mediaItem.mediaId).build()
            } else mediaItem
            return audio.createMediaSource(item)
        }
        // A distinct URI marks the audio leg so toggling back rebuilds the source.
        val audioItem = mediaItem.buildUpon()
            .setUri(Uri.parse("prism://audio/$videoId?leg=video"))
            .setCustomCacheKey(videoId)
            .build()
        val videoItem = MediaItem.Builder().setMediaId("video:$videoId").setUri("prism://video/$videoId").build()
        return MergingMediaSource(true, false, audio.createMediaSource(audioItem), video.createMediaSource(videoItem))
    }
}

/**
 * The part of a music video that last failed to load (#411): its picture or its sound, and what it was read from (a
 * googlevideo link's itag, client and length, or the phone's own cache). The player's error says neither.
 */
data class VideoLegFailure(val videoId: String, val picture: Boolean, val uri: Uri?, val error: Throwable, val atMs: Long) {
    fun describe(): String {
        val from = when {
            uri == null -> "from an unknown source"
            uri.scheme == "prism" -> "read from the phone (Downloads or the song cache)"
            else -> "itag ${uri.getQueryParameter("itag")}, client ${uri.getQueryParameter("c")}, clen ${uri.getQueryParameter("clen")}"
        }
        return "${if (picture) "its picture" else "its sound"} failed ($from: ${error.javaClass.simpleName})"
    }
}

/** The last [VideoLegFailure], kept for the player's error that follows it. */
object VideoLegs {
    @Volatile var last: VideoLegFailure? = null

    /** [videoId]'s failure, when it's recent enough to be the one the player reports now. */
    fun lastFor(videoId: String, now: Long): VideoLegFailure? = last?.takeIf { it.videoId == videoId && now - it.atMs in 0..VIDEO_LEG_FAILURE_MS }

    const val VIDEO_LEG_FAILURE_MS = 60_000L
}

/** Notes each load error of a music video's legs ([VideoLegs]), then handles it as [inner] does. */
@OptIn(UnstableApi::class)
internal class NoteVideoLegFailures(
    private val picture: Boolean,
    private val inner: LoadErrorHandlingPolicy,
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) : LoadErrorHandlingPolicy by inner {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        runCatching {
            val asked = loadErrorInfo.loadEventInfo.dataSpec.uri
            // The picture's prism://video/<id>, or the sound's prism://audio/<id>?leg=video (a song alone isn't noted).
            if (asked.scheme == "prism" && (picture || asked.getQueryParameter("leg") == "video")) asked.lastPathSegment?.let { id ->
                VideoLegs.last = VideoLegFailure(id, picture, loadErrorInfo.loadEventInfo.uri, loadErrorInfo.exception, now())
            }
        }
        return inner.getRetryDelayMsFor(loadErrorInfo)
    }
}

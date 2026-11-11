package com.prism.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * YouTube's live manifests offer two audio renditions: HE-AAC at ~48 kbps (itag 233, `mp4a.40.5`) and AAC-LC at
 * 128 kbps (itag 234, `mp4a.40.2`), both marked default, and the player could settle on either. This drops the
 * HE-AAC one whenever the AAC-LC one is there, so live streams always play the better audio (video or not).
 */
@OptIn(UnstableApi::class)
class BestLiveAudioParserFactory(private val inner: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory()) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = inner.createPlaylistParser()
        return ParsingLoadable.Parser { uri: Uri, input: InputStream -> bestAudio(parser.parse(uri, input)) }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)

    companion object {
        fun bestAudio(playlist: HlsPlaylist): HlsPlaylist {
            if (playlist !is HlsMultivariantPlaylist || playlist.audios.size < 2) return playlist
            fun lc(r: HlsMultivariantPlaylist.Rendition) =
                r.format.codecs?.contains("mp4a.40.2") == true || r.url.toString().contains("/itag/234/")
            if (playlist.audios.none(::lc)) return playlist
            val keys = buildList {
                playlist.variants.indices.forEach { add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it)) }
                playlist.audios.forEachIndexed { i, r -> if (lc(r)) add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, i)) }
                playlist.subtitles.indices.forEach { add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, it)) }
            }
            android.util.Log.i("PrismLive", "audio renditions ${playlist.audios.map { it.format.codecs }} -> AAC-LC only")
            return playlist.copy(keys)
        }
    }
}

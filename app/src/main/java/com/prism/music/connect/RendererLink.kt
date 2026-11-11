package com.prism.music.connect

/** What a song being sent is, for a device that wants it as data rather than as DIDL-Lite (Google Cast). */
data class CastMedia(
    val title: String,
    val artist: String,
    val album: String?,
    val artUrl: String?,
    /** The MIME type it's served as ("audio/wav", "audio/mpeg"…). */
    val mime: String,
    val durationMs: Long,
)

/**
 * The device on the other end of Prism Connect, however it's spoken to: a UPnP / DLNA renderer ([RendererControl]) or a
 * Google Cast device ([GoogleCastLink]). [Cast] drives either the same way: load a song, play, pause, seek, and poll
 * where it is.
 */
interface RendererLink {
    /** Hands it [url] to play (paused until [play]). [didl] is the song as DIDL-Lite, [media] the same as data. */
    suspend fun load(url: String, didl: String, media: CastMedia)

    /** For gapless moves on: the song after this one. Throws where the device has no such thing. */
    suspend fun loadNext(url: String, didl: String, media: CastMedia)

    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(ms: Long)

    /** Whether it's playing, paused, stopped… (asks it afresh). */
    suspend fun transport(): TransportInfo

    /** The track it has and where it is in it (as of the last [transport] for devices that report both at once). */
    suspend fun position(): PositionInfo

    /** 0–100, or null when it doesn't say. */
    suspend fun volume(): Int?
    suspend fun setVolume(v: Int)

    /** The MIME types it plays; empty when it won't say. */
    suspend fun sinkMimes(): Set<String>

    /** Casting is over: whatever it showed for Prism goes away, and any connection is closed. */
    suspend fun close() {}
}

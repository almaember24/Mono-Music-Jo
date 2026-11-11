package com.prism.music.playback

/** Snapshot shown in the "Stats for nerds" overlay. */
data class NerdStats(
    val videoId: String = "",
    val source: String = "—",            // Downloaded / Cached / Streaming
    val itag: Int = 0,
    val container: String = "",
    val codec: String = "",
    val decoder: String = "",
    val streamBitrateKbps: Int = 0,
    val sampleRate: Int = 0,
    /** Bits per sample of uncompressed (PCM) input such as WAV; 0 when it's compressed or not known. */
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val contentLength: Long = 0,
    val client: String = "",
    val outputEncoding: String = "",
    val outputSampleRate: Int = 0,
    val offload: Boolean = false,
    val bandwidthKbps: Long = 0,
    val bufferMs: Long = 0,
    /** The song's loudness: measured (LUFS) when [loudnessMeasured], else YouTube's figure (dB from its reference). */
    val loudnessDb: Double? = null,
    val loudnessMeasured: Boolean = false,
    val normalizationGainDb: Float = 0f,
    val audioSessionId: Int = 0,
    val outputDevice: String = "",
    val spatializer: String = "",
    val effects: String = "",
    val videoResolution: String = "",
    val videoCodec: String = "",
    val droppedFrames: Int = 0,
    val underruns: Int = 0,
)

/** A music file's format by its MIME type, as people know it: "audio/x-wav" → "WAV", "audio/mpeg" → "MP3". */
fun fileFormatName(mime: String): String {
    val sub = mime.substringAfter('/').removePrefix("x-").lowercase()
    return when {
        sub == "wav" || sub == "wave" || sub == "vnd.wave" -> "WAV"
        sub.startsWith("aif") -> "AIFF"
        sub == "mpeg" || sub == "mp3" -> "MP3"
        sub == "mp4" || sub == "m4a" || sub == "aac" -> "AAC"
        sub == "ogg" || sub == "vorbis" -> "Ogg"
        sub == "matroska" || sub == "mka" -> "MKA"
        else -> sub.uppercase()
    }
}

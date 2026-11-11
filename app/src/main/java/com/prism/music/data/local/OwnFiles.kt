package com.prism.music.data.local

import com.prism.music.data.local.LocalLossless.Companion.norm
import com.prism.music.data.model.Song

/**
 * Your own song files (1.8.1, #314): MP3, AAC/M4A and Ogg/Opus files on the phone that lossless sync matches to songs
 * and plays in place of YouTube's stream, when Downloads, storage & data → "Use your own song files" is on (off by
 * default). Lossless files stay apart: they come first ([PlaySourceRule]), and they still need lossless playback.
 */
object OwnFiles {
    /** Which kinds of files on the phone sync looks at: lossless ones (as in 1.8.0) and your own lossy ones. */
    data class Kinds(val lossless: Boolean, val lossy: Boolean) {
        val any: Boolean get() = lossless || lossy
    }

    /** Lossless files need lossless playback and its file access, as in 1.8.0; your own lossy files need only their switch. */
    fun kinds(s: com.prism.music.data.prefs.AppSettings) = Kinds(s.lossless && s.losslessLocal, s.sound.ownLossyFiles)

    /**
     * MP3 and Ogg/Opus/AAC (ADTS) files by the MIME type Android's media store gives them, asked for in the query itself
     * (a phone with thousands of MP3s never loads the rest). MP4 audio (.m4a) comes from the ALAC pass, which reads
     * each once to tell AAC from ALAC ([MP4_MIMES]).
     */
    val LOSSY_MIMES = listOf(
        "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg", "audio/x-mp3",
        "audio/aac", "audio/x-aac", "audio/aacp",
        "audio/ogg", "application/ogg", "audio/opus", "audio/x-opus+ogg", "audio/vorbis", "audio/x-vorbis+ogg",
    )
    /** MP4 audio: AAC or ALAC inside, which only the file itself says. */
    val MP4_MIMES = listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm")

    fun isLossyMime(mime: String): Boolean = mime.lowercase().let { it in LOSSY_MIMES || it in MP4_MIMES }

    /** "MP3", "AAC", "Opus" or "Ogg" for a lossy file, by its MIME type and, for Ogg, its name (.opus); null otherwise. */
    fun formatOf(mime: String, name: String = ""): String? {
        val m = mime.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m in listOf("audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg", "audio/x-mp3") -> "MP3"
            m in MP4_MIMES || m in listOf("audio/aac", "audio/x-aac", "audio/aacp") -> "AAC"
            m.contains("opus") || (ext == "opus" && m in LOSSY_MIMES) -> "Opus"
            m in LOSSY_MIMES -> "Ogg"
            else -> null
        }
    }

    /** Lossy files' keys in lossless sync's link and miss lists: the file and when it last changed (retagged = new key). */
    fun key(uri: String, modified: Long) = "$KEY_PREFIX$uri@$modified"
    fun isOwnKey(key: String) = key.startsWith(KEY_PREFIX)
    private const val KEY_PREFIX = "own:"

    /** A lookup that found nothing for one of your files is tried again only after this long, or when the file changes. */
    const val MISS_RETRY_MS = 90L * 86_400_000L

    /** The words a lossy file's quality is shown in: "MP3 · 320 kbps". */
    fun label(t: LocalLossless.Track): String = listOfNotNull(t.format, t.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ")

    /** The player's source line for a song playing from your file (stats for nerds; the quality badge reads it). */
    const val SOURCE = "Your file"
    fun source(t: LocalLossless.Track) = "$SOURCE · ${label(t)}"

    // ------------------------------------------------------------------ Matching on messy tags

    /** A file's title and artist, cleaned up: track numbers, "Artist - Title" titles, "<unknown>" artists. */
    data class Tags(val title: String, val artist: String)

    /** "03 - ", "03_", "07. ", "1) ", "A1 - " (a vinyl side); not "4.44" or "7 Rings". */
    private val trackNumber = Regex("^\\s*(?:\\d{1,3}|[a-d]\\d{1,2})\\s*(?:[-_]|[.)]\\s)\\s*", RegexOption.IGNORE_CASE)
    private val unknownArtist = setOf("<unknown>", "unknown", "unknown artist", "various artists", "various", "va")

    fun clean(title: String, artist: String): Tags {
        var t = title.trim()
        var a = artist.trim().takeUnless { it.lowercase() in unknownArtist }.orEmpty()
        // "03 - Title", "03. Title", "A1 - Title"; "7 Rings" and "1979" keep their numbers (no separator after them).
        if (trackNumber.containsMatchIn(t) && t.replace(trackNumber, "").isNotBlank()) t = t.replace(trackNumber, "")
        val dash = t.indexOf(" - ")
        if (dash > 0) {
            val before = t.substring(0, dash).trim()
            val after = t.substring(dash + 3).trim()
            when {
                // "Artist - Title" in the title, no artist tag.
                a.isBlank() && after.isNotBlank() -> { a = before; t = after }
                // "Artist - Title" with the artist tag the same.
                a.isNotBlank() && norm(before) == norm(a) && after.isNotBlank() -> t = after
            }
        }
        return Tags(t, a)
    }

    private val artistSplit = Regex("\\s*(?:;|,|&|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b)\\s*", RegexOption.IGNORE_CASE)

    /** "Daft Punk feat. Pharrell Williams; Nile Rodgers" → [daft punk, pharrell williams, nile rodgers], plus the whole name. */
    fun artistNames(artist: String): List<String> {
        fun n(s: String) = norm(s).removePrefix("the ").trim()
        return (listOf(n(artist.replace(Regex("\\s(&|and)\\s", RegexOption.IGNORE_CASE), " and "))) + artist.split(artistSplit).map(::n))
            .filter { it.isNotBlank() }.distinct()
    }

    /**
     * Whether one of your files is [song], strictly (a wrong file playing for a song is worse than the stream): after
     * [clean], the same title exactly, an artist they share by name, and both lengths known and within [LENGTH_SLACK_SEC].
     * A file with no artist at all is never matched.
     */
    fun sameSong(fileTitle: String, fileArtist: String, fileMs: Long, song: Song): Boolean {
        if (song.isVideo || song.isLive || song.isEpisode) return false
        val tags = clean(fileTitle, fileArtist)
        val title = norm(tags.title)
        if (title.isBlank() || title != norm(song.title)) return false
        val mine = artistNames(tags.artist).toSet()
        if (mine.isEmpty()) return false
        val theirs = song.artists.flatMap { artistNames(it.name) }.toSet()
        if (theirs.none { it in mine }) return false
        return fileMs > 0 && song.durationSec > 0 && kotlin.math.abs(fileMs / 1000.0 - song.durationSec) <= LENGTH_SLACK_SEC
    }

    /** Seconds a file and the song may differ by: rips match YouTube's length to the second or two; edits don't. */
    const val LENGTH_SLACK_SEC = 3
}

/**
 * Which plays a song when files of it are on the phone (#314): **a lossless file, then one of your own lossy files, then
 * the stream.** A lossy file is passed over for the stream when it's clearly worse than YouTube's (Opus ~128-160 kbps,
 * AAC 128/256): under [minKbps] for its format, or a sample rate under [MIN_SAMPLE_RATE].
 */
object PlaySourceRule {
    /** Hz: 32 kHz and 22 kHz files have lost their highs; YouTube streams at 44.1/48 kHz. */
    const val MIN_SAMPLE_RATE = 44_000
    /** A measured bitrate can read a little under its nominal one (128 kbps files read 126): this much is let through. */
    const val SLACK_KBPS = 4

    /** The lowest bitrate a lossy file may have and still play instead of the stream. */
    fun minKbps(format: String): Int = when (format) {
        "Opus" -> 96 // Opus at 96 holds up against AAC at 128
        "Ogg" -> 112
        else -> 128 // MP3, AAC
    }

    /** False when the file is clearly worse than the stream; an unknown bitrate (no length) counts as worse. */
    fun beatsStream(format: String, kbps: Int, sampleRate: Int): Boolean {
        if (kbps <= 0 || kbps + SLACK_KBPS < minKbps(format)) return false
        if (sampleRate in 1 until MIN_SAMPLE_RATE) return false
        return true
    }

    fun beatsStream(t: LocalLossless.Track): Boolean = !t.lossy || beatsStream(t.format, t.bitrateKbps, t.sampleRate)

    /** 2 = lossless, 1 = your own lossy file, 0 = the stream instead. */
    fun rank(t: LocalLossless.Track): Int = when {
        !t.lossy -> 2
        beatsStream(t) -> 1
        else -> 0
    }

    /** The file to play from [files] (all the same song), or null for the stream. The first wins among equals. */
    fun choose(files: List<LocalLossless.Track>): LocalLossless.Track? {
        var best: LocalLossless.Track? = null
        for (f in files) if (rank(f) > 0 && (best == null || rank(f) > rank(best))) best = f
        return best
    }
}

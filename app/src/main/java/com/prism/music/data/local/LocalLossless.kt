package com.prism.music.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * The lossless add-on: lossless files on the phone (FLAC, WAV, AIFF, ALAC) that match
 * songs in Prism are played instead of YouTube's compressed stream.
 * YouTube Music itself has no lossless audio, so this is where real lossless comes from.
 * Since 1.8.1 (#314) it also lists your own MP3/AAC/Ogg files when "Use your own song files" is on ([OwnFiles]).
 */
class LocalLossless(private val context: Context) {
    data class Track(
        val uri: Uri,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val mime: String,
        val size: Long,
        /** The file's name ("Song.opus"); only read for your own lossy files ([OwnFiles]). */
        val name: String = "",
        /** When the file last changed (seconds, the media store's); only for your own lossy files. */
        val modified: Long = 0,
        /** The bitrate the media store read from the file (Android 11+), in kbps; 0 = not known. */
        val tagKbps: Int = 0,
        /** Hz, from the media store (Android 14+); 0 = not known. */
        val sampleRate: Int = 0,
    ) {
        /** One of your own MP3/AAC/Ogg files (1.8.1, #314), not a lossless one. */
        val lossy: Boolean get() = OwnFiles.isLossyMime(mime)
        val format: String get() = when {
            mime.contains("flac") -> "FLAC"
            mime.contains("wav") -> "WAV"
            mime.contains("aif") -> "AIFF"
            mime.contains("alac") -> "ALAC"
            else -> OwnFiles.formatOf(mime, name) ?: "Lossless"
        }
        val kbps: Int get() = if (durationMs > 0) (size * 8 / durationMs).toInt() else 0
        /** The audio's own bitrate when the media store knows it (a cover picture in the tags would add to [kbps]). */
        val bitrateKbps: Int get() = tagKbps.takeIf { it > 0 } ?: kbps
        /** Lossless sync's key for the file: its uri (as in 1.8.0), or for your own files its uri and when it changed. */
        val key: String get() = if (lossy) OwnFiles.key(uri.toString(), modified) else uri.toString()
    }

    private val mimes = listOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/aiff", "audio/x-aiff")
    /** MP4 audio: ALAC or AAC inside, which only the file itself says ([isAlac]). */
    private val mp4Mimes = OwnFiles.MP4_MIMES
    private val alacChecks by lazy { context.getSharedPreferences("lossless_alac", Context.MODE_PRIVATE) }

    /**
     * Whether an .m4a holds ALAC (Apple Lossless, #135) rather than AAC: read once per file (by id and size) with
     * Android's own MediaExtractor, and remembered. Unreadable counts as no.
     */
    private fun isAlac(uri: Uri, key: String): Boolean {
        if (alacChecks.contains(key)) return alacChecks.getBoolean(key, false)
        val alac = runCatching {
            val x = android.media.MediaExtractor()
            try {
                x.setDataSource(context, uri, null)
                (0 until x.trackCount).any { i -> x.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.contains("alac", true) == true }
            } finally { x.release() }
        }.getOrDefault(false)
        alacChecks.edit().putBoolean(key, alac).apply()
        return alac
    }

    @Volatile private var byTitle: Map<String, List<Track>> = emptyMap()
    /** Every lossless file found in the last scan. */
    @Volatile var tracks: List<Track> = emptyList()
        private set
    /** Files lossless sync has tied to a song, by video id: these always match, whatever their tags say. */
    @Volatile var linked: Map<String, Uri> = emptyMap()
    /** Songs Prism has matched to a file, by video id (for stats). */
    val matched = ConcurrentHashMap<String, Track>()
    /** Files this phone couldn't decode (ALAC without a decoder, #135): the song's stream plays instead, this run. */
    val unplayable: MutableSet<Uri> = ConcurrentHashMap.newKeySet()
    /** How many lossless files were found in the last scan (-1 = not scanned). */
    val found = MutableStateFlow(-1)
    /** How many of your own lossy files (good enough to play instead of the stream) the last scan found; -1 = not looked for. */
    val ownFound = MutableStateFlow(-1)

    fun hasPermission(): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Lists the files on the phone: lossless ones when [lossless] (as in 1.8.0), and with [lossy] (#314, "Use your own
     * song files") your own MP3/AAC/Ogg files too, those clearly worse than the stream left out ([PlaySourceRule]). Each
     * kind is asked for by MIME type in the query itself.
     */
    suspend fun scan(lossless: Boolean = true, lossy: Boolean = false) = withContext(Dispatchers.IO) {
        if (!hasPermission()) { found.value = -1; ownFound.value = -1; return@withContext }
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
        )
        if (lossless) {
            val selection = "${MediaStore.Audio.Media.MIME_TYPE} IN (${mimes.joinToString { "?" }})"
            runCatching {
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, mimes.toTypedArray(), null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        tracks += Track(
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: continue, c.getString(2) ?: "", c.getLong(3), c.getString(4) ?: "", c.getLong(5),
                        )
                    }
                }
            }
        }
        // Your own files' extra columns: the file's name (.opus), when it changed (the lookup cache's key), and what the
        // media store read of its bitrate and sample rate, where this Android has them.
        val ownProjection = projection + listOfNotNull(
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= 30) "bitrate" else null,
            // The media store only has the column from Android 14: asked for below that, the whole query fails.
            if (Build.VERSION.SDK_INT >= 34) "samplerate" else null,
        )
        fun own(c: android.database.Cursor, uri: Uri, mime: String): Track? {
            val bitrate = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex("bitrate").takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L else 0L
            val rate = if (Build.VERSION.SDK_INT >= 34) c.getColumnIndex("samplerate").takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0 else 0
            return Track(
                uri, c.getString(1) ?: return null, c.getString(2) ?: "", c.getLong(3), mime, c.getLong(5),
                name = c.getString(6) ?: "", modified = c.getLong(7), tagKbps = (bitrate / 1000).toInt(), sampleRate = rate,
            ).takeIf { PlaySourceRule.beatsStream(it) }
        }
        if (lossy) runCatching {
            val selection = "${MediaStore.Audio.Media.MIME_TYPE} IN (${OwnFiles.LOSSY_MIMES.joinToString { "?" }})"
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ownProjection, selection, OwnFiles.LOSSY_MIMES.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    own(c, uri, c.getString(4) ?: continue)?.let { tracks += it }
                }
            }
        }.onFailure { android.util.Log.w("PrismLossless", "the MP3/AAC/Ogg pass failed", it) }
        // ALAC (#135): .m4a files whose track is Apple Lossless, looked at once each (a few hundred a scan at most; the
        // rest on the next scans). The others are AAC: your own files when [lossy].
        runCatching {
            var reads = 0
            val mp4 = "${MediaStore.Audio.Media.MIME_TYPE} IN (${mp4Mimes.joinToString { "?" }})"
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, if (lossy) ownProjection else projection, mp4, mp4Mimes.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val key = "$id:${c.getLong(5)}"
                    if (!alacChecks.contains(key) && reads++ >= MAX_ALAC_READS) continue
                    if (isAlac(uri, key)) {
                        if (lossless) tracks += Track(uri, c.getString(1) ?: continue, c.getString(2) ?: "", c.getLong(3), "audio/alac", c.getLong(5))
                    } else if (lossy) {
                        own(c, uri, c.getString(4) ?: continue)?.let { tracks += it }
                    }
                }
            }
        }.onFailure { android.util.Log.w("PrismLossless", "the .m4a pass failed", it) }
        use(tracks)
        found.value = tracks.count { !it.lossy }
        ownFound.value = if (lossy) tracks.count { it.lossy } else -1
    }

    /** The files a scan found, lossless ones first. */
    internal fun use(found: List<Track>) {
        val ordered = found.sortedBy { if (it.lossy) 1 else 0 }
        // Your own files by their cleaned title ("03 - Artist - Title" is filed under "title").
        byTitle = ordered.groupBy { norm(if (it.lossy) OwnFiles.clean(it.title, it.artist).title else it.title) }
        tracks = ordered
        matched.clear()
    }

    /**
     * The file a song plays from, if there is one ([PlaySourceRule]: lossless, then your own lossy file, then the stream):
     * the one lossless sync tied to it, else a lossless file with the same title, artist and about the same length (as
     * in 1.8.0), else one of your own files that is strictly the same song ([OwnFiles.sameSong]). [lossless] / [lossy]:
     * which kinds the settings allow right now (turned off, a kind is passed over at once, before the next scan).
     */
    fun match(song: Song, lossless: Boolean = true, lossy: Boolean = true, note: Boolean = true): Track? {
        if (byTitle.isEmpty() || song.isVideo) return null
        fun allowed(t: Track) = t.uri !in unplayable && (if (t.lossy) lossy && PlaySourceRule.beatsStream(t) else lossless)
        val tied = linked[song.id]?.let { uri -> tracks.firstOrNull { it.uri == uri && allowed(it) } }
        // A tied lossy file still gives way to a lossless one of the same song.
        if (tied != null && !tied.lossy) { if (note) matched[song.id] = tied; return tied }
        val candidates = byTitle[norm(song.title)]?.filter(::allowed).orEmpty()
        val artist = norm(song.primaryArtist)
        val hit = candidates.firstOrNull { t ->
            if (t.lossy) return@firstOrNull false
            val a = norm(t.artist)
            val artistOk = artist.isBlank() || a.isBlank() || a.contains(artist) || artist.contains(a)
            val lengthOk = song.durationSec <= 0 || t.durationMs <= 0 || kotlin.math.abs(t.durationMs / 1000 - song.durationSec) <= 4
            artistOk && lengthOk
        } ?: tied ?: candidates.firstOrNull { it.lossy && OwnFiles.sameSong(it.title, it.artist, it.durationMs, song) }
        hit ?: return null
        // [note]: a play, kept for the stats; a song row only asking (#385) isn't one.
        if (note) matched[song.id] = hit
        return hit
    }

    companion object {
        /** .m4a files opened per scan to see whether they're ALAC. */
        private const val MAX_ALAC_READS = 400
        val permission: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        /** "Mr. Brightside (Remastered 2020) [feat. X]" -> "mr brightside" */
        fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(BRACKETS, "")
            .replace(VERSION_TAIL, "")
            .replace(FEATURING, "")
            .replace(NOT_WORD, "")
            .replace(SPACES, " ")
            .trim()

        // Compiled once: song rows ask the matcher too (#385), not only playback.
        private val BRACKETS = Regex("\\(.*?\\)|\\[.*?]")
        private val VERSION_TAIL = Regex("\\s-\\s.*(remaster|version|edit|mix|live).*")
        private val FEATURING = Regex("\\b(feat|ft|featuring)\\b.*")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N} ]")
        private val SPACES = Regex("\\s+")
    }
}

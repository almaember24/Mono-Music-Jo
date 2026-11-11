package com.prism.music.data.blend

import com.prism.music.R
import com.prism.music.tr
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** An artist in a taste code: YouTube's channel id (when known), name, and how much they're played (1–99). */
data class CodeArtist(val id: String?, val name: String, val weight: Int) {
    /** What an artist is matched by across two codes: the name (ids are left out of codes that need the room). */
    val key: String get() = BlendCode.norm(name)
}

/** A song in a taste code: video id, title, its artist's name, and how much it's played (1–99). */
data class CodeSong(val id: String, val title: String, val artist: String, val artistId: String?, val weight: Int) {
    val artistKey: String get() = BlendCode.norm(artist)
}

/**
 * What one person shares to make a Blend: a random id for them ([who], made on their phone, nothing else about them),
 * the name they chose to show, their top artists and songs, and their genres (share of listening, percent). Nothing
 * else: no history, no times, no account.
 */
data class TasteCode(
    val who: String,
    val name: String,
    val artists: List<CodeArtist>,
    val songs: List<CodeSong>,
    val genres: Map<String, Int>,
)

/**
 * Taste codes as text: `PRISMBLEND1:` and the code deflated and in URL-safe Base64, small enough for a QR code
 * ([MAX_CHARS]; the least played songs and artists give way first). Fields are separated by control characters
 * that never occur in names, so nothing needs escaping.
 */
object BlendCode {
    const val PREFIX = "PRISMBLEND1:"
    /** Comfortably inside a QR code (version 40 at medium error correction holds 2,331 bytes). */
    const val MAX_CHARS = 1_800
    private const val FIELD = '\u001F'
    private const val LINE = '\u001E'
    private const val MID_SONGS = 35
    private const val MIN_SONGS = 15
    private const val MIN_ARTISTS = 10

    fun encode(code: TasteCode): String {
        // What gives way when it doesn't fit, in turn: artists' channel ids (names are what match), the least played
        // songs down to [MID_SONGS], the least played artists down to [MIN_ARTISTS], then songs down to [MIN_SONGS].
        var songs = code.songs.sortedByDescending { it.weight }
        var artists = code.artists.sortedByDescending { it.weight }
        while (true) {
            val text = PREFIX + pack(code.copy(songs = songs, artists = artists))
            if (text.length <= MAX_CHARS) return text
            when {
                artists.any { it.id != null } -> artists = artists.map { it.copy(id = null) }
                songs.size > MID_SONGS -> songs = songs.dropLast(1)
                artists.size > MIN_ARTISTS -> artists = artists.dropLast(1)
                songs.size > MIN_SONGS -> songs = songs.dropLast(1)
                else -> return text
            }
        }
    }

    /** The taste code somewhere in [text] (a message, a scanned QR code), or null. */
    fun findIn(text: String?): String? {
        val t = text ?: return null
        val at = t.indexOf(PREFIX)
        if (at < 0) return null
        val body = t.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (body.isEmpty()) null else PREFIX + body
    }

    fun decode(text: String?): TasteCode? = runCatching {
        val code = findIn(text) ?: return null
        val bytes = Base64.getUrlDecoder().decode(code.removePrefix(PREFIX))
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024) return null
        }
        inflater.end()
        unpack(out.toString(Charsets.UTF_8.name()))
    }.getOrNull()

    private fun clean(s: String) = s.filter { it != FIELD && it != LINE && it != '\n' }.trim().take(80)

    /** "UC" + 22 characters is a channel id: kept without the "UC". */
    private fun shortId(id: String?) = when {
        id == null -> ""
        id.length == 24 && id.startsWith("UC") -> id.substring(2)
        else -> "=$id"
    }
    private fun longId(s: String) = when { s.isEmpty() -> null; s.startsWith("=") -> s.substring(1); else -> "UC$s" }

    private fun pack(c: TasteCode): String {
        val sb = StringBuilder()
        sb.append("1").append(LINE).append(clean(c.who)).append(LINE).append(clean(c.name)).append(LINE)
        sb.append(c.genres.entries.joinToString(FIELD.toString()) { "${clean(it.key)}=${it.value.coerceIn(0, 100)}" }).append(LINE)
        val artistIndex = HashMap<String, Int>()
        sb.append(c.artists.joinToString(FIELD.toString()) { a ->
            artistIndex[a.key] = artistIndex.size
            "${shortId(a.id)}$FIELD${clean(a.name)}$FIELD${a.weight.coerceIn(1, 99)}"
        }).append(LINE)
        // A song by one of the listed artists names them by their place in the list ("#3"), not again in full.
        sb.append(c.songs.joinToString(FIELD.toString()) { s ->
            val by = artistIndex[s.artistKey]?.let { "#$it" } ?: clean(s.artist)
            "${clean(s.id)}$FIELD${clean(s.title)}$FIELD$by$FIELD${s.weight.coerceIn(1, 99)}"
        })
        val raw = sb.toString().toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun unpack(text: String): TasteCode? {
        val lines = text.split(LINE)
        if (lines.size < 6 || lines[0] != "1") return null
        val genres = lines[3].split(FIELD).filter { it.contains('=') }
            .associate { it.substringBeforeLast('=') to (it.substringAfterLast('=').toIntOrNull() ?: 0) }
        val af = lines[4].split(FIELD)
        val artists = if (lines[4].isEmpty()) emptyList() else af.chunked(3).filter { it.size == 3 }
            .map { (id, name, w) -> CodeArtist(longId(id), name, w.toIntOrNull() ?: 1) }
        val sf = lines[5].split(FIELD)
        val songs = if (lines[5].isEmpty()) emptyList() else sf.chunked(4).filter { it.size == 4 }.mapNotNull { (id, title, by, w) ->
            if (id.length != 11) return@mapNotNull null
            val artist = by.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()?.let { artists.getOrNull(it) }
            CodeSong(id, title, artist?.name ?: by, artist?.id, w.toIntOrNull() ?: 1)
        }
        if (lines[1].isBlank()) return null
        return TasteCode(lines[1], lines[2].ifBlank { tr(R.string.a_friend) }, artists, songs, genres)
    }

    /** Names compared without case, accents or punctuation. */
    fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .filter { it.isLetterOrDigit() }
}

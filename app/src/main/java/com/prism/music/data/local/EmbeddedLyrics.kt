package com.prism.music.data.local

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.LruCache
import com.prism.music.data.lyrics.Lrc
import com.prism.music.data.model.Song
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The lyrics in the file a song plays from ([EmbeddedLyrics]). A file only ever gets opened for reading, and what it held
 * is remembered for as long as it stays the same, so a song's lyrics being looked at again costs one look at its size
 * and time. A file that can't be opened has no lyrics.
 */
class EmbeddedLyricsFiles(private val context: Context, private val fileOf: (Song) -> Uri?) {
    private class Read(val stamp: String, val text: String?)
    private val known = LruCache<String, Read>(32)

    fun lyricsOf(song: Song): String? {
        val uri = fileOf(song) ?: return null
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val st = Os.fstat(pfd.fileDescriptor)
                val stamp = "${st.st_size}:${st.st_mtime}"
                val key = uri.toString()
                known.get(key)?.takeIf { it.stamp == stamp }?.let { return@use it.text }
                // Not closed here: the stream only borrows the descriptor, and [pfd] closes it.
                EmbeddedLyrics.read(FileInputStream(pfd.fileDescriptor).channel).also { known.put(key, Read(stamp, it)) }
            }
        }.getOrNull()
    }
}

/**
 * Lyrics already inside a music file's own tags (#344), read and never written ([LyricsTags] writes): Vorbis comments
 * in FLAC and Ogg/Opus (LYRICS, UNSYNCEDLYRICS, SYNCEDLYRICS), ID3v2's USLT, SYLT and TXXX frames in MP3, and MP4's
 * ©lyr atom. What comes back is the text as it's stored, LRC when it's timed, so the lyrics' own parser reads it like
 * any source's. The format is told by the file's first bytes, not its name. Only the tag is read (a cover picture is
 * stepped over), and nothing here trusts a size in the file: a damaged or odd tag gives null.
 */
object EmbeddedLyrics {
    /** Longer than any song's lyrics, however many languages they carry. */
    private const val MAX_TEXT = 2 shl 20
    /** A tag or comment block read whole to get at its text (an Ogg comment packet holds its cover too). */
    private const val MAX_BLOCK = 16 shl 20
    private const val MAX_ID3 = 64 shl 20
    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "SYNCED LYRICS")

    /** The lyrics in the file open on [file], or null. Timed ones win over plain ones when a file has both. */
    fun read(file: FileChannel): String? = runCatching { readAll(file) }.getOrNull()?.let(::pick)

    /** Of what a file holds: the first with LRC timing, else the first with any text. */
    internal fun pick(found: List<String>): String? {
        val texts = found.map { it.trim('\u0000', ' ', '\n', '\r', '\t', '\uFEFF') }.filter { it.isNotEmpty() }
        return texts.firstOrNull(Lrc::isLrc) ?: texts.firstOrNull()
    }

    private fun readAll(f: FileChannel): List<String> {
        val head = f.bytesAt(0, 12) ?: return emptyList()
        return when {
            head.ascii(0, 3) == "ID3" -> {
                val tagEnd = 10L + syncsafe(head, 6) + if (head[5].toInt() and 0x10 != 0) 10 else 0
                val inTag = id3(f, head)
                // Some FLAC files carry an ID3v2 tag in front of their own header.
                val flac = f.bytesAt(tagEnd, 4)?.ascii(0, 4) == "fLaC"
                inTag + if (flac) flac(f, tagEnd + 4) else emptyList()
            }
            head.ascii(0, 4) == "fLaC" -> flac(f, 4)
            head.ascii(0, 4) == "OggS" -> ogg(f)
            head.ascii(4, 4) == "ftyp" -> mp4(f)
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------ Vorbis comments: FLAC and Ogg

    private fun flac(f: FileChannel, start: Long): List<String> {
        var at = start
        repeat(1000) {
            val h = f.bytesAt(at, 4) ?: return emptyList()
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4) return comments(f.bytesAt(at + 4, len) ?: return emptyList(), 0)
            if (h[0].toInt() and 0x80 != 0) return emptyList()
            at += 4L + len
        }
        return emptyList()
    }

    /** The lyric comments in a Vorbis comment block starting at [from]; those read before any damage count. */
    private fun comments(b: ByteArray, from: Int): List<String> {
        val out = ArrayList<String>()
        var p = from
        fun int(): Int? {
            if (p + 4 > b.size) return null
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendor = int() ?: return out
        if (vendor < 0 || vendor > b.size - p) return out
        p += vendor
        val count = int() ?: return out
        var left = count
        while (left-- > 0) {
            val n = int() ?: return out
            if (n < 0 || n > b.size - p) return out
            // Only the key is looked at first: most comments are short, but a cover picture can be one of them.
            val eq = (p until p + n).firstOrNull { b[it] == '='.code.toByte() }
            if (eq != null && n <= MAX_TEXT && String(b, p, eq - p, Charsets.US_ASCII).uppercase() in LYRIC_KEYS) {
                out += String(b, eq + 1, p + n - eq - 1, Charsets.UTF_8)
            }
            p += n
        }
        return out
    }

    /**
     * An Ogg file's comments: the second packet of its first stream, across as many pages as it takes. Vorbis and Opus
     * mark it by name; Ogg FLAC has a block header before its comment block.
     */
    private fun ogg(f: FileChannel): List<String> {
        var at = 0L
        var serial: Int? = null
        val packets = ArrayList<ByteArray>()
        var building = java.io.ByteArrayOutputStream()
        while (packets.size < 2) {
            val h = f.bytesAt(at, 27) ?: return emptyList()
            if (h.ascii(0, 4) != "OggS") return emptyList()
            val segments = h[26].toInt() and 0xFF
            val table = f.bytesAt(at + 27, segments) ?: return emptyList()
            val pageSerial = ByteBuffer.wrap(h, 14, 4).int
            if (serial == null) serial = pageSerial
            var data = at + 27 + segments
            if (pageSerial == serial) {
                var run = 0
                var runStart = data
                for (s in table) {
                    val n = s.toInt() and 0xFF
                    run += n
                    if (n < 255) {
                        if (building.size().toLong() + run > MAX_BLOCK) return emptyList()
                        building.write(f.bytesAt(runStart, run) ?: return emptyList())
                        packets += building.toByteArray()
                        building = java.io.ByteArrayOutputStream()
                        runStart += run
                        run = 0
                        if (packets.size == 2) break
                    }
                }
                // A packet that goes on in the next page.
                if (run > 0 && packets.size < 2) {
                    if (building.size().toLong() + run > MAX_BLOCK) return emptyList()
                    building.write(f.bytesAt(runStart, run) ?: return emptyList())
                }
            }
            for (s in table) data += s.toInt() and 0xFF
            at = data
        }
        val first = packets[0]
        val second = packets[1]
        return when {
            first.ascii(0, 8) == "OpusHead" && second.ascii(0, 8) == "OpusTags" -> comments(second, 8)
            first.ascii(1, 6) == "vorbis" && second.ascii(1, 6) == "vorbis" && second[0].toInt() == 3 -> comments(second, 7)
            first.ascii(1, 4) == "FLAC" && second.isNotEmpty() && second[0].toInt() and 0x7F == 4 -> comments(second, 4)
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------ ID3v2: MP3

    private fun id3(f: FileChannel, head: ByteArray): List<String> {
        val major = head[3].toInt()
        if (major !in 2..4) return emptyList()
        val flags = head[5].toInt() and 0xFF
        val size = syncsafe(head, 6)
        if (size <= 0 || size > MAX_ID3) return emptyList()
        // A whole tag unsynchronised (ID3v2.2 and 2.3) has to be undone before its frames can be walked; otherwise the
        // frames are looked at in the file, so a big cover never comes into memory.
        val read: (Long, Int) -> ByteArray?
        var end = size.toLong()
        if (major <= 3 && flags and 0x80 != 0) {
            val body = deunsync(f.bytesAt(10, size) ?: return emptyList())
            end = body.size.toLong()
            read = { at, n -> if (at < 0 || n < 0 || at + n > body.size) null else body.copyOfRange(at.toInt(), at.toInt() + n) }
        } else {
            read = { at, n -> f.bytesAt(10 + at, n) }
        }
        var p = 0L
        if (major >= 3 && flags and 0x40 != 0) {
            val x = read(0, 4) ?: return emptyList()
            // Its size leaves itself out in 2.3 (and the four bytes of the size), and includes itself in 2.4.
            p = if (major == 3) 4L + be(x, 0) else syncsafe(x, 0).toLong()
            if (p < 0 || p >= end) return emptyList()
        }
        val out = ArrayList<String>()
        val headerSize = if (major == 2) 6 else 10
        while (p + headerSize <= end) {
            val h = read(p, headerSize) ?: return out
            if (h[0].toInt() == 0) return out
            val id = h.ascii(0, if (major == 2) 3 else 4)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) return out
            val frameSize = when (major) {
                2 -> ((h[3].toInt() and 0xFF) shl 16) or ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
                3 -> be(h, 4)
                // A size with a top bit set can't be syncsafe: some taggers write 2.4 with plain sizes.
                else -> if ((4..7).any { h[it].toInt() and 0x80 != 0 }) be(h, 4) else syncsafe(h, 4)
            }
            if (frameSize < 0 || p + headerSize + frameSize > end) return out
            val wanted = when (id) {
                "USLT", "ULT" -> ::uslt
                "SYLT", "SLT" -> ::sylt
                "TXXX", "TXX" -> ::txxx
                else -> null
            }
            if (wanted != null && frameSize <= MAX_TEXT) {
                val raw = read(p + headerSize, frameSize) ?: return out
                frameBody(raw, major, h)?.let { body -> runCatching { wanted(body) }.getOrNull()?.let { out += it } }
            }
            p += headerSize + frameSize
        }
        return out
    }

    /** A frame's body with its own extras undone; null for one that's compressed or encrypted. */
    private fun frameBody(raw: ByteArray, major: Int, header: ByteArray): ByteArray? {
        if (major == 2) return raw
        val flags = header[9].toInt() and 0xFF
        if (major == 3) {
            if (flags and 0xC0 != 0) return null
            return if (flags and 0x20 != 0) raw.copyOfRange(1, raw.size) else raw
        }
        if (flags and 0x0C != 0) return null
        var from = 0
        if (flags and 0x40 != 0) from += 1
        if (flags and 0x01 != 0) from += 4
        val body = raw.copyOfRange(from, raw.size)
        return if (flags and 0x02 != 0) deunsync(body) else body
    }

    private fun deunsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** Encoding, language, description, the text. */
    private fun uslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = b[0].toInt()
        val after = terminated(b, 4, enc).second
        return decode(b, after, b.size, enc)
    }

    /** Encoding, description, the value; only a frame its description calls lyrics (as some taggers write them). */
    private fun txxx(b: ByteArray): String? {
        if (b.size < 2) return null
        val enc = b[0].toInt()
        val (name, after) = terminated(b, 1, enc)
        if (name.trim().uppercase() !in LYRIC_KEYS) return null
        return decode(b, after, b.size, enc)
    }

    /**
     * Synchronised lyrics, as LRC. Encoding, language, how the times count (2: milliseconds; MPEG frames can't be told
     * without reading the audio, so those are left out), content type, description, then text and a four-byte time, over
     * and over.
     */
    private fun sylt(b: ByteArray): String? {
        if (b.size < 7) return null
        val enc = b[0].toInt()
        if (b[4].toInt() != 2) return null
        var p = terminated(b, 6, enc).second
        val lines = ArrayList<String>()
        while (p < b.size) {
            val (text, after) = terminated(b, p, enc)
            if (after + 4 > b.size) break
            val ms = be(b, after).toLong() and 0xFFFFFFFFL
            lines += "[${stamp(ms)}]${text.trim('\n', '\r', ' ')}"
            p = after + 4
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun stamp(ms: Long): String {
        val cs = ms / 10
        return "%02d:%02d.%02d".format(java.util.Locale.ROOT, cs / 6000, cs / 100 % 60, cs % 100)
    }

    /** The string at [from], ended by its encoding's zero or the end, and where the next thing starts. */
    private fun terminated(b: ByteArray, from: Int, enc: Int): Pair<String, Int> {
        if (from >= b.size) return "" to b.size
        val wide = enc == 1 || enc == 2
        var i = from
        if (wide) {
            while (i + 1 < b.size && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
            return decode(b, from, minOf(i, b.size), enc) to minOf(i + 2, b.size)
        }
        while (i < b.size && b[i].toInt() != 0) i++
        return decode(b, from, i, enc) to minOf(i + 1, b.size)
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        return when (enc) {
            0 -> String(b, from, to - from, Charsets.ISO_8859_1)
            // UTF-16 with its byte order mark; Java's decoder reads it, and takes big-endian when there's none.
            1 -> String(b, from, to - from, Charsets.UTF_16)
            2 -> String(b, from, to - from, Charsets.UTF_16BE)
            else -> String(b, from, to - from, Charsets.UTF_8)
        }
    }

    // ------------------------------------------------------------ MP4

    /** moov/udta/meta/ilst: "©lyr", or a "----" item its name calls lyrics. Walked in the file, so a cover stays there. */
    private fun mp4(f: FileChannel): List<String> {
        val moov = boxes(f, 0, f.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val udta = boxes(f, moov.body, moov.end).firstOrNull { it.type == "udta" } ?: return emptyList()
        val meta = boxes(f, udta.body, udta.end).firstOrNull { it.type == "meta" } ?: return emptyList()
        // A full box (four bytes of version and flags), except in QuickTime's own files.
        val start = if (f.bytesAt(meta.body + 4, 4)?.ascii(0, 4) == "hdlr") meta.body else meta.body + 4
        val ilst = boxes(f, start, meta.end).firstOrNull { it.type == "ilst" } ?: return emptyList()
        val out = ArrayList<String>()
        for (item in boxes(f, ilst.body, ilst.end)) {
            when (item.type) {
                "©lyr" -> dataOf(f, item)?.let { out += it }
                "----" -> {
                    val name = boxes(f, item.body, item.end).firstOrNull { it.type == "name" }?.let { n ->
                        val len = (n.end - n.body - 4).toInt()
                        if (len in 1..64) f.bytesAt(n.body + 4, len)?.let { String(it, Charsets.UTF_8) } else null
                    }
                    if (name?.trim()?.uppercase() in LYRIC_KEYS) dataOf(f, item)?.let { out += it }
                }
            }
        }
        return out
    }

    /** An item's "data" box as text: four bytes of type, four of locale, the value. */
    private fun dataOf(f: FileChannel, item: Box): String? {
        val data = boxes(f, item.body, item.end).firstOrNull { it.type == "data" } ?: return null
        val len = data.end - data.body - 8
        if (len <= 0 || len > MAX_TEXT) return null
        val head = f.bytesAt(data.body, 4) ?: return null
        val text = f.bytesAt(data.body + 8, len.toInt()) ?: return null
        return if (be(head, 0) and 0xFFFFFF == 2) String(text, Charsets.UTF_16BE) else String(text, Charsets.UTF_8)
    }

    private class Box(val type: String, val body: Long, val end: Long)

    /** The boxes from [from] to [to]; those read before any damage count. */
    private fun boxes(f: FileChannel, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to && out.size < 10_000) {
            val h = f.bytesAt(p, 8) ?: break
            var size = be(h, 0).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L) {
                val large = f.bytesAt(p + 8, 8) ?: break
                size = ByteBuffer.wrap(large).long
                header = 16
            } else if (size == 0L) size = to - p
            if (size < header || p + size > to) break
            out += Box(h.ascii(4, 4), p + header, p + size)
            p += size
        }
        return out
    }

    // ------------------------------------------------------------ Bytes

    /** [n] bytes at [at]; null when the file ends first or the request makes no sense. */
    private fun FileChannel.bytesAt(at: Long, n: Int): ByteArray? {
        if (at < 0 || n < 0 || n > MAX_ID3) return null
        val buf = ByteBuffer.allocate(n)
        var pos = at
        while (buf.hasRemaining()) {
            val got = read(buf, pos)
            if (got <= 0) return null
            pos += got
        }
        return buf.array()
    }

    private fun ByteArray.ascii(at: Int, n: Int): String = if (at + n > size) "" else String(this, at, n, Charsets.ISO_8859_1)

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}

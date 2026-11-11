package com.prism.music.data.local

import com.prism.music.data.lyrics.Lyrics
import java.io.File
import java.io.RandomAccessFile

/**
 * Lyrics written into music files' own tags (#16), so other players and devices show them too: FLAC files get a
 * LYRICS Vorbis comment, MP3s an ID3v2 USLT frame, both holding LRC text ("[01:02.34]A line") when the lyrics are
 * synced, or plain text when they aren't. Any lyrics already in the tag are replaced; everything else is kept as it
 * was. The file is written to [output]; the caller swaps it in once it's whole.
 */
object LyricsTags {
    enum class Kind { FLAC, MP3, M4A }

    fun kindOf(mime: String, name: String = ""): Kind? = when {
        mime.contains("flac", true) || name.endsWith(".flac", true) -> Kind.FLAC
        mime.contains("mpeg", true) || mime.contains("mp3", true) || name.endsWith(".mp3", true) -> Kind.MP3
        // AAC and ALAC in MP4 (1.7.8, Reddit): a ©lyr atom, as iTunes and most players read it.
        mime.contains("mp4", true) || mime.contains("m4a", true) || mime.contains("aac", true) || mime.contains("alac", true) ||
            name.endsWith(".m4a", true) -> Kind.M4A
        else -> null
    }

    /**
     * Whether [output] still holds exactly [input]'s audio: for FLAC and MP3 their endings match (the tag is at the
     * start); for M4A the audio box (mdat) is byte for byte the same, wherever it sits.
     */
    fun audioIntact(input: File, output: File, kind: Kind): Boolean = when (kind) {
        Kind.M4A -> runCatching { mdatDigest(input)?.let { it.contentEquals(mdatDigest(output)) } ?: false }.getOrDefault(false)
        else -> sameEnding(input, output)
    }

    private fun sameEnding(a: File, b: File): Boolean {
        val n = minOf(1L shl 20, a.length(), b.length()).toInt()
        if (n <= 0) return false
        fun tail(f: File) = RandomAccessFile(f, "r").use { r -> ByteArray(n).also { r.seek(f.length() - n); r.readFully(it) } }
        return tail(a).contentEquals(tail(b))
    }

    /**
     * The lyrics as LRC (one "[mm:ss.xx]line" per line, an empty one for each instrumental break), or plain text.
     * [words] (1.7.9.2, Reddit): a word-timed line keeps each word's start, as enhanced LRC ("[00:12.00]<00:12.00>Word
     * <00:12.48>by"), which karaoke-capable players (Poweramp and others) light word by word. [translations] (one per
     * line, null where a line has none): each goes under its line, with the same time, as two-language LRC does.
     */
    fun text(l: Lyrics, words: Boolean = false, translations: List<String?>? = null): String {
        fun under(i: Int) = translations?.getOrNull(i)?.takeIf { it.isNotBlank() && it != l.lines[i].text }
        if (!l.synced) return l.lines.withIndex().joinToString("\n") { (i, line) -> listOfNotNull(line.text, under(i)).joinToString("\n") }.trim()
        // Western digits whatever the phone's language: players read LRC times as those.
        fun stamp(ms: Long): String { val cs = ms / 10; return "%02d:%02d.%02d".format(java.util.Locale.ROOT, cs / 6000, cs / 100 % 60, cs % 100) }
        return l.lines.withIndex().filter { it.value.timeMs >= 0 }.joinToString("\n") { (i, line) ->
            val text = when {
                line.isGap -> ""
                words && line.words.isNotEmpty() -> line.words.joinToString("") { "<${stamp(it.startMs)}>${it.text}" }
                else -> line.text
            }
            "[${stamp(line.timeMs)}]$text" + (under(i)?.let { "\n[${stamp(line.timeMs)}]$it" } ?: "")
        }
    }

    /** Writes [input] with [lyrics] in its tag to [output]. False (and nothing written) for a file it can't read safely. */
    fun write(input: File, output: File, kind: Kind, lyrics: String): Boolean = runCatching {
        when (kind) {
            Kind.FLAC -> flac(input, output, lyrics)
            Kind.MP3 -> mp3(input, output, lyrics)
            Kind.M4A -> m4a(input, output, lyrics)
        }
    }.getOrDefault(false).also { if (!it) output.delete() }

    // ------------------------------------------------------------ FLAC

    private class Block(val type: Int, val data: ByteArray)

    private fun flac(input: File, output: File, lyrics: String): Boolean {
        RandomAccessFile(input, "r").use { f ->
            // Some FLAC files carry an ID3v2 tag in front: kept as it is.
            var start = 0L
            val head = ByteArray(10)
            f.readFully(head)
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                start = 10L + syncsafe(head, 6) + if (head[5].toInt() and 0x10 != 0) 10 else 0
            }
            f.seek(start)
            val magic = ByteArray(4).also { f.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != "fLaC") return false
            val blocks = ArrayList<Block>()
            while (true) {
                val h = f.readUnsignedByte()
                val len = (f.readUnsignedByte() shl 16) or (f.readUnsignedByte() shl 8) or f.readUnsignedByte()
                blocks += Block(h and 0x7F, ByteArray(len).also { f.readFully(it) })
                if (h and 0x80 != 0) break
                if (blocks.size > 1000) return false
            }
            if (blocks.firstOrNull()?.type != 0) return false // STREAMINFO comes first in every valid file
            val audioAt = f.filePointer
            val oldComment = blocks.firstOrNull { it.type == 4 }
            val comment = Block(4, vorbisComment(oldComment?.data, lyrics))
            val kept = blocks.filter { it.type != 4 && it.type != 1 }.toMutableList()
            kept.add(1, comment)
            // Room to grow next time: the padding there was, or 4 KB.
            kept += Block(1, ByteArray(blocks.firstOrNull { it.type == 1 }?.data?.size?.coerceIn(1024, 65536) ?: 4096))
            output.outputStream().buffered().use { out ->
                if (start > 0) { val pre = ByteArray(start.toInt()); f.seek(0); f.readFully(pre); out.write(pre) }
                out.write(magic)
                kept.forEachIndexed { i, b ->
                    if (b.data.size >= 1 shl 24) return false
                    out.write((if (i == kept.lastIndex) 0x80 else 0) or b.type)
                    out.write(b.data.size shr 16 and 0xFF); out.write(b.data.size shr 8 and 0xFF); out.write(b.data.size and 0xFF)
                    out.write(b.data)
                }
                f.seek(audioAt)
                copyRest(f, out)
            }
        }
        return true
    }

    /** A Vorbis comment block with every comment kept except old lyrics, and LYRICS=[lyrics] added. */
    private fun vorbisComment(old: ByteArray?, lyrics: String): ByteArray {
        var vendor = "Prism".toByteArray()
        val comments = ArrayList<ByteArray>()
        if (old != null) {
            var p = 0
            fun int(): Int { val v = (old[p].toInt() and 0xFF) or ((old[p + 1].toInt() and 0xFF) shl 8) or ((old[p + 2].toInt() and 0xFF) shl 16) or ((old[p + 3].toInt() and 0xFF) shl 24); p += 4; return v }
            val vl = int(); vendor = old.copyOfRange(p, p + vl); p += vl
            repeat(int()) {
                val cl = int()
                val c = old.copyOfRange(p, p + cl); p += cl
                val key = String(c, Charsets.UTF_8).substringBefore('=').uppercase()
                if (key != "LYRICS" && key != "UNSYNCEDLYRICS" && key != "SYNCEDLYRICS") comments += c
            }
        }
        comments += "LYRICS=$lyrics".toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        le(vendor.size); out.write(vendor)
        le(comments.size); comments.forEach { le(it.size); out.write(it) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ MP3

    private fun mp3(input: File, output: File, lyrics: String): Boolean {
        RandomAccessFile(input, "r").use { f ->
            val head = ByteArray(10)
            val hasTag = f.length() >= 10 && run { f.readFully(head); String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" }
            val major = if (hasTag) head[3].toInt() else 4
            val flags = if (hasTag) head[5].toInt() and 0xFF else 0
            // ID3v2.2, and tags that are unsynchronised or have extended headers or footers, are left alone (rare
            // today, and rewriting them wrong could spoil the file).
            if (hasTag && (major !in 3..4 || flags != 0)) return false
            val tagSize = if (hasTag) syncsafe(head, 6) else 0
            val frames = java.io.ByteArrayOutputStream()
            if (hasTag) {
                val body = ByteArray(tagSize).also { f.seek(10); f.readFully(it) }
                var p = 0
                while (p + 10 <= body.size && body[p].toInt() != 0) {
                    val id = String(body, p, 4, Charsets.ISO_8859_1)
                    val size = if (major == 4) syncsafe(body, p + 4) else be(body, p + 4)
                    if (size < 0 || p + 10 + size > body.size) return false
                    if (id != "USLT") frames.write(body, p, 10 + size)
                    p += 10 + size
                }
            }
            frames.write(uslt(major, lyrics))
            val padding = 2048
            val newSize = frames.size() + padding
            output.outputStream().buffered().use { out ->
                out.write("ID3".toByteArray(Charsets.ISO_8859_1))
                out.write(major); out.write(0); out.write(0)
                out.write(syncsafeBytes(newSize))
                frames.writeTo(out)
                out.write(ByteArray(padding))
                f.seek(if (hasTag) 10L + tagSize else 0L)
                copyRest(f, out)
            }
        }
        return true
    }

    /** An unsynchronised-lyrics frame: UTF-8 in ID3v2.4, UTF-16 in v2.3 (which has no UTF-8). */
    private fun uslt(major: Int, lyrics: String): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        if (major == 4) {
            body.write(3); body.write("eng".toByteArray()); body.write(0)
            body.write(lyrics.toByteArray(Charsets.UTF_8))
        } else {
            body.write(1); body.write("eng".toByteArray())
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)) // an empty description, with its BOM
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); body.write(lyrics.toByteArray(Charsets.UTF_16LE))
        }
        val data = body.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("USLT".toByteArray(Charsets.ISO_8859_1))
        out.write(if (major == 4) syncsafeBytes(data.size) else byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(0); out.write(0)
        out.write(data)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ M4A (MP4)

    /** One box: its type, where it starts in the file, its whole size, and its header's length (8, or 16 for a 64-bit size). */
    private class Box(val type: String, val at: Long, val size: Long, val header: Int)

    /** The boxes from [from] to [to] in [f]; null when one is malformed. */
    private fun boxes(f: RandomAccessFile, from: Long, to: Long): List<Box>? {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            f.seek(p)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = f.readLong(); header = 16 } else if (size == 0L) size = to - p
            if (size < header || p + size > to) return null
            out += Box(type, p, size, header)
            p += size
            if (out.size > 10_000) return null
        }
        return out
    }

    /** The boxes inside a container box held in [b] (bytes), from [start]; null when malformed. */
    private fun children(b: ByteArray, start: Int, end: Int): List<Triple<String, Int, Int>>? {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = start
        while (p + 8 <= end) {
            val size = be(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || p + size > end) return null
            out += Triple(type, p, size)
            p += size
        }
        return if (p == end) out else null
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val size = 8 + body.size
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl", "edts", "dinf", "udta")

    /**
     * MP4 with the lyrics in moov/udta/meta/ilst as "©lyr" (made if missing). When moov comes before the audio, every
     * chunk offset (stco/co64) moves by however much moov grew. Fragmented files (moof) are left alone.
     */
    private fun m4a(input: File, output: File, lyrics: String): Boolean {
        RandomAccessFile(input, "r").use { f ->
            val top = boxes(f, 0, f.length()) ?: return false
            if (top.firstOrNull()?.type != "ftyp" || top.any { it.type == "moof" }) return false
            val moov = top.singleOrNull { it.type == "moov" } ?: return false
            if (moov.header != 8 || moov.size > 64L shl 20) return false
            val mdatAfter = top.filter { it.type == "mdat" }.let { m -> m.isNotEmpty() && m.all { it.at > moov.at } }
            val mdatBefore = top.filter { it.type == "mdat" }.all { it.at < moov.at }
            if (!mdatAfter && !mdatBefore) return false
            val old = ByteArray(moov.size.toInt()).also { f.seek(moov.at); f.readFully(it) }
            val inner = rebuildMoov(old, lyrics) ?: return false
            val grew = inner.size.toLong() + 8 - moov.size
            val moved = if (mdatAfter && grew != 0L) shiftOffsets(inner, grew) ?: return false else inner
            val newMoov = box("moov", moved)
            output.outputStream().buffered().use { out ->
                val buf = ByteArray(256 * 1024)
                fun copy(from: Long, len: Long) {
                    f.seek(from); var left = len
                    while (left > 0) { val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
                }
                copy(0, moov.at)
                out.write(newMoov)
                copy(moov.at + moov.size, f.length() - moov.at - moov.size)
            }
        }
        return true
    }

    /** moov's body (without its own header) with the lyrics put in; null when its layout isn't understood. */
    private fun rebuildMoov(moov: ByteArray, lyrics: String): ByteArray? {
        val kids = children(moov, 8, moov.size) ?: return null
        val lyr = box("©lyr", box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0), lyrics.toByteArray(Charsets.UTF_8)))
        val udta = kids.firstOrNull { it.first == "udta" }
        val newUdta = if (udta == null) box("udta", newMeta(null, lyr) ?: return null) else {
            val (_, at, size) = udta
            val u = children(moov, at + 8, at + size) ?: return null
            val meta = u.firstOrNull { it.first == "meta" }
            val parts = u.filter { it.first != "meta" }.map { (_, a, s) -> moov.copyOfRange(a, a + s) }
            val newMeta = newMeta(meta?.let { (_, a, s) -> moov.copyOfRange(a, a + s) }, lyr) ?: return null
            box("udta", *(parts + listOf(newMeta)).toTypedArray())
        }
        val body = java.io.ByteArrayOutputStream()
        kids.forEach { (t, a, s) -> if (t != "udta") body.write(moov, a, s) }
        body.write(newUdta)
        return body.toByteArray()
    }

    /** A meta box ([old], or a new one) whose ilst has [lyr] in place of any lyrics it had. */
    private fun newMeta(old: ByteArray?, lyr: ByteArray): ByteArray? {
        // An iTunes metadata handler: version and flags, pre_defined, "mdir", "appl" and 8 reserved bytes, an empty name.
        val hdlr = box("hdlr", ByteArray(8), "mdirappl".toByteArray(Charsets.ISO_8859_1), ByteArray(9))
        if (old == null) return box("meta", ByteArray(4), hdlr, box("ilst", lyr))
        // meta is a full box: four bytes of version and flags before its children.
        val m = children(old, 12, old.size) ?: return null
        val ilst = m.firstOrNull { it.first == "ilst" }
        val items = ilst?.let { (_, a, s) -> children(old, a + 8, a + s) ?: return null }.orEmpty()
            .filter { it.first != "©lyr" }.map { (_, a, s) -> old.copyOfRange(a, a + s) }
        val rest = m.filter { it.first != "ilst" }.map { (_, a, s) -> old.copyOfRange(a, a + s) }
        val withHdlr = if (m.any { it.first == "hdlr" }) rest else listOf(hdlr) + rest
        val newIlst = box("ilst", *(items + listOf(lyr)).toTypedArray())
        return box("meta", old.copyOfRange(8, 12), *(withHdlr + listOf(newIlst)).toTypedArray())
    }

    /** moov's body with every chunk offset (stco, co64) in its tracks moved by [by]; null when one won't fit. */
    private fun shiftOffsets(body: ByteArray, by: Long): ByteArray? {
        val b = body.copyOf()
        fun walk(start: Int, end: Int): Boolean {
            val kids = children(b, start, end) ?: return false
            for ((t, a, s) in kids) when (t) {
                in CONTAINERS -> if (!walk(a + 8, a + s)) return false
                "stco" -> {
                    val n = be(b, a + 12)
                    if (16 + n.toLong() * 4 > s) return false
                    for (i in 0 until n) {
                        val at = a + 16 + i * 4
                        val v = (be(b, at).toLong() and 0xFFFFFFFFL) + by
                        if (v < 0 || v > 0xFFFFFFFFL) return false
                        b[at] = (v shr 24).toByte(); b[at + 1] = (v shr 16).toByte(); b[at + 2] = (v shr 8).toByte(); b[at + 3] = v.toByte()
                    }
                }
                "co64" -> {
                    val n = be(b, a + 12)
                    if (16 + n.toLong() * 8 > s) return false
                    for (i in 0 until n) {
                        val at = a + 16 + i * 8
                        var v = 0L
                        for (k in 0 until 8) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
                        v += by
                        for (k in 0 until 8) b[at + k] = (v shr (56 - 8 * k)).toByte()
                    }
                }
            }
            return true
        }
        return if (walk(0, b.size)) b else null
    }

    /** A digest of an MP4 file's audio boxes (mdat), in order; null when it has none or can't be read. */
    private fun mdatDigest(file: File): ByteArray? = RandomAccessFile(file, "r").use { f ->
        val mdats = boxes(f, 0, f.length())?.filter { it.type == "mdat" }?.takeIf { it.isNotEmpty() } ?: return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        for (m in mdats) {
            f.seek(m.at + m.header); var left = m.size - m.header
            while (left > 0) { val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; md.update(buf, 0, n); left -= n }
        }
        md.digest()
    }

    // ------------------------------------------------------------ Helpers

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun syncsafeBytes(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun copyRest(f: RandomAccessFile, out: java.io.OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = f.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }
}

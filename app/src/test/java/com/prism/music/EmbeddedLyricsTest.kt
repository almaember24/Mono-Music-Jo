package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.local.EmbeddedLyrics
import com.prism.music.data.local.LyricsTags
import com.prism.music.data.lyrics.Lrc
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.data.prefs.LyricsSource
import com.prism.music.data.prefs.defaultLyricsOrder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * #344: lyrics already in a music file's tags, read by format, and how that source sits among the others. Files written
 * by [LyricsTags] (and two real M4As made with ffmpeg), or built byte by byte to the format's specification. Made-up lyrics.
 */
class EmbeddedLyricsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val synced = "[00:00.50]A yellow bicycle\n[00:01.20]By the quiet river"
    private val plain = "A yellow bicycle\nBy the quiet river"

    private fun read(bytes: ByteArray): String? = read(tmp.newFile().also { it.writeBytes(bytes) })
    private fun read(f: File): String? = RandomAccessFile(f, "r").use { EmbeddedLyrics.read(it.channel) }

    // ------------------------------------------------------------ Bytes

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())
    private val audio = ByteArray(4000) { (it * 7 + 3).toByte() }

    private fun comment(vararg entries: String): ByteArray = ByteArrayOutputStream().also { o ->
        val vendor = "test".toByteArray()
        o.write(le(vendor.size)); o.write(vendor); o.write(le(entries.size))
        entries.forEach { e -> e.toByteArray().let { o.write(le(it.size)); o.write(it) } }
    }.toByteArray()

    private fun flac(vararg entries: String): ByteArray = ByteArrayOutputStream().also { o ->
        o.write("fLaC".toByteArray())
        o.write(0); o.write(0); o.write(0); o.write(34); o.write(ByteArray(34) { 1 })
        val vc = comment(*entries)
        o.write(0x80 or 4); o.write(vc.size shr 16); o.write(vc.size shr 8); o.write(vc.size); o.write(vc)
        o.write(audio)
    }.toByteArray()

    private fun frame23(id: String, data: ByteArray) = id.toByteArray() + be(data.size) + byteArrayOf(0, 0) + data
    private fun frame24(id: String, data: ByteArray, flags: Int = 0) = id.toByteArray() + syncsafe(data.size) + byteArrayOf(0, flags.toByte()) + data
    private fun id3(major: Int, frames: ByteArray, flags: Int = 0) = "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, flags.toByte()) + syncsafe(frames.size) + frames + audio

    private fun uslt(text: String, enc: Int = 3): ByteArray = when (enc) {
        0 -> byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        1 -> byteArrayOf(1) + "eng".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        2 -> byteArrayOf(2) + "eng".toByteArray() + byteArrayOf(0, 0) + text.toByteArray(Charsets.UTF_16BE)
        else -> byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray()
    }

    /** A CRC, for Ogg's pages: nothing here checks it, so zero does. */
    private fun oggPage(serial: Int, seq: Int, payload: ByteArray, firstFlags: Int = 0): ByteArray {
        val segments = ArrayList<Byte>()
        var left = payload.size
        while (left >= 255) { segments += 255.toByte(); left -= 255 }
        segments += left.toByte()
        return "OggS".toByteArray() + byteArrayOf(0, firstFlags.toByte()) + ByteArray(8) + be(serial) + le(seq) + ByteArray(4) +
            byteArrayOf(segments.size.toByte()) + segments.toByteArray() + payload
    }

    // ------------------------------------------------------------ Vorbis comments

    @Test fun flacComment() {
        assertEquals(synced, read(flac("TITLE=Song", "LYRICS=$synced")))
        assertEquals(plain, read(flac("TITLE=Song", "unsyncedlyrics=$plain")))
        assertNull(read(flac("TITLE=Song", "COMMENT=Not lyrics")))
    }

    @Test fun flacWrittenByPrismComesBack() {
        val src = tmp.newFile("a.flac").also { it.writeBytes(flac("TITLE=Song")) }
        val out = tmp.newFile("b.flac")
        assertTrue(LyricsTags.write(src, out, LyricsTags.Kind.FLAC, synced))
        assertEquals(synced, read(out))
    }

    @Test fun flacBehindAnId3Tag() {
        val tag = frame23("TIT2", byteArrayOf(0) + "Song".toByteArray())
        val file = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(tag.size) + tag + flac("LYRICS=$plain")
        assertEquals(plain, read(file))
    }

    @Test fun oggVorbisAcrossPages() {
        // A long cover in the comments pushes the packet over two pages (255 × 255 bytes is one page's most).
        val cover = "METADATA_BLOCK_PICTURE=" + "A".repeat(90_000)
        val packet = byteArrayOf(3) + "vorbis".toByteArray() + comment("TITLE=Song", cover, "LYRICS=$synced") + byteArrayOf(1)
        val first = oggPage(77, 0, byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23), firstFlags = 2)
        // Written by hand: the first part ends in a 255 segment, so the packet goes on.
        val head = packet.copyOf(255 * 250)
        val tail = packet.copyOfRange(head.size, packet.size)
        val second = "OggS".toByteArray() + byteArrayOf(0, 0) + ByteArray(8) + be(77) + le(1) + ByteArray(4) +
            byteArrayOf(250.toByte()) + ByteArray(250) { 255.toByte() } + head
        val third = oggPage(77, 2, tail, firstFlags = 1)
        assertTrue(tail.isNotEmpty())
        assertEquals(synced, read(first + second + third + oggPage(77, 3, audio.copyOf(200))))
    }

    @Test fun oggOpus() {
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val tags = "OpusTags".toByteArray() + comment("SYNCEDLYRICS=$synced")
        assertEquals(synced, read(oggPage(5, 0, head, 2) + oggPage(5, 1, tags) + oggPage(5, 2, audio.copyOf(100))))
    }

    @Test fun oggOtherStreamsAreLeftAlone() {
        val head = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)
        val other = oggPage(9, 0, "not comments".toByteArray())
        val tags = byteArrayOf(3) + "vorbis".toByteArray() + comment("LYRICS=$plain") + byteArrayOf(1)
        assertEquals(plain, read(oggPage(5, 0, head, 2) + other + oggPage(5, 1, tags)))
    }

    @Test fun oggFlac() {
        val head = byteArrayOf(0x7F) + "FLAC".toByteArray() + ByteArray(40)
        val vc = comment("LYRICS=$plain")
        val tags = byteArrayOf(0x84.toByte(), 0, (vc.size shr 8).toByte(), vc.size.toByte()) + vc
        assertEquals(plain, read(oggPage(5, 0, head, 2) + oggPage(5, 1, tags)))
    }

    // ------------------------------------------------------------ ID3v2

    @Test fun mp3WrittenByPrismComesBack() {
        // Prism's writer: a new ID3v2.4 tag on a file with none, then replacing it in a v2.3 one.
        val bare = tmp.newFile("a.mp3").also { it.writeBytes(audio) }
        val out = tmp.newFile("b.mp3")
        assertTrue(LyricsTags.write(bare, out, LyricsTags.Kind.MP3, synced))
        assertEquals(synced, read(out))

        val v23 = tmp.newFile("c.mp3").also { it.writeBytes(id3(3, frame23("TIT2", byteArrayOf(0) + "Song".toByteArray()) + frame23("USLT", uslt("old", 0)))) }
        val out2 = tmp.newFile("d.mp3")
        assertTrue(LyricsTags.write(v23, out2, LyricsTags.Kind.MP3, "Wörds"))
        assertEquals("Wörds", read(out2))
    }

    @Test fun usltInEveryEncoding() {
        for (enc in 0..3) {
            val text = if (enc == 0) "Grüße\nAgain" else "Grüße 夜\nAgain"
            assertEquals("encoding $enc", text, read(id3(if (enc == 0 || enc == 1) 3 else 4, if (enc <= 1) frame23("USLT", uslt(text, enc)) else frame24("USLT", uslt(text, enc)))))
        }
    }

    @Test fun id3v22() {
        val body = uslt(plain, 0)
        val frame = "ULT".toByteArray() + byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        assertEquals(plain, read(id3(2, frame)))
    }

    /** A big cover comes first in many files, and stays in the file: the frames after it are still found. */
    @Test fun afterACover() {
        val cover = frame23("APIC", ByteArray(3_000_000) { 1 })
        assertEquals(plain, read(id3(3, cover + frame23("USLT", uslt(plain)))))
    }

    @Test fun syltBecomesLrc() {
        fun sylt(format: Int) = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(format.toByte(), 1, 0) +
            "Hello".toByteArray() + byteArrayOf(0) + be(500) + "\nWorld".toByteArray() + byteArrayOf(0) + be(61_230)
        assertEquals("[00:00.50]Hello\n[01:01.23]World", read(id3(3, frame23("SYLT", sylt(2)))))
        // Times in MPEG frames mean nothing without reading the audio.
        assertNull(read(id3(3, frame23("SYLT", sylt(1)))))
    }

    @Test fun timedLyricsWinOverPlainInOneFile() {
        val sylt = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(2, 1, 0) + "Hello".toByteArray() + byteArrayOf(0) + be(500)
        assertEquals("[00:00.50]Hello", read(id3(3, frame23("USLT", uslt(plain)) + frame23("SYLT", sylt))))
    }

    @Test fun txxxOnlyWhenItSaysLyrics() {
        fun txxx(name: String, value: String) = byteArrayOf(3) + name.toByteArray() + byteArrayOf(0) + value.toByteArray()
        assertEquals(plain, read(id3(3, frame23("TXXX", txxx("UNSYNCEDLYRICS", plain)))))
        assertEquals(synced, read(id3(4, frame24("TXXX", txxx("Lyrics", synced)))))
        assertNull(read(id3(3, frame23("TXXX", txxx("MOOD", plain)))))
    }

    @Test fun wholeTagUnsynchronised() {
        // 0xFF 0xFE in the UTF-16 text's byte order mark is what unsynchronisation stuffs a zero into.
        val frames = frame23("USLT", uslt("Hello", 1))
        val stuffed = ByteArrayOutputStream().also { o ->
            frames.forEachIndexed { i, b ->
                o.write(b.toInt())
                if (b == 0xFF.toByte() && i + 1 < frames.size && frames[i + 1].toInt() and 0xE0 == 0xE0) o.write(0)
            }
        }.toByteArray()
        assertEquals("Hello", read(id3(3, stuffed, flags = 0x80)))
    }

    @Test fun v24FrameFlags() {
        // Data length indicator and a frame of its own unsynchronised.
        val body = uslt("Hello", 1)
        val stuffed = ByteArrayOutputStream().also { o ->
            body.forEachIndexed { i, b ->
                o.write(b.toInt())
                if (b == 0xFF.toByte() && i + 1 < body.size && body[i + 1].toInt() and 0xE0 == 0xE0) o.write(0)
            }
        }.toByteArray()
        assertEquals("Hello", read(id3(4, frame24("USLT", syncsafe(body.size) + stuffed, flags = 0x03))))
        // Compressed: left out rather than shown as nonsense.
        assertNull(read(id3(4, frame24("USLT", uslt("Hello"), flags = 0x08))))
    }

    @Test fun extendedHeader() {
        val frames = frame23("USLT", uslt(plain))
        val extended = be(6) + ByteArray(6)
        assertEquals(plain, read(id3(3, extended + frames, flags = 0x40)))
        val extended24 = syncsafe(6) + byteArrayOf(1, 0)
        assertEquals(plain, read(id3(4, extended24 + frame24("USLT", uslt(plain)), flags = 0x40)))
    }

    // ------------------------------------------------------------ MP4

    private fun res(name: String) = File(javaClass.classLoader!!.getResource("lyrics-tags/$name")!!.toURI())

    @Test fun m4aWrittenByPrismComesBack() {
        for (name in listOf("front.m4a", "end.m4a")) {
            assertNull(name, read(res(name)))
            val out = tmp.newFile("out-$name")
            assertTrue(LyricsTags.write(res(name), out, LyricsTags.Kind.M4A, synced))
            assertEquals(name, synced, read(out))
        }
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return be(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test fun m4aFreeformItem() {
        val item = box("----", box("mean", ByteArray(4), "com.apple.iTunes".toByteArray()), box("name", ByteArray(4), "LYRICS".toByteArray()), box("data", be(1), ByteArray(4), plain.toByteArray()))
        val file = box("ftyp", "M4A ".toByteArray()) + box("moov", box("udta", box("meta", ByteArray(4), box("ilst", item))))
        assertEquals(plain, read(file))
        // QuickTime's "meta" has no version and flags.
        val qt = box("ftyp", "M4A ".toByteArray()) + box("moov", box("udta", box("meta", box("hdlr", ByteArray(8)), box("ilst", item))))
        assertEquals(plain, read(qt))
    }

    // ------------------------------------------------------------ Damaged files

    /** Cut anywhere, or with bytes changed, a file gives lyrics or null, and never an exception or a hang. */
    @Test fun damagedFiles() {
        val samples = listOf(
            flac("TITLE=Song", "LYRICS=$synced"),
            id3(3, frame23("TIT2", byteArrayOf(0) + "Song".toByteArray()) + frame23("USLT", uslt(plain, 1))),
            id3(4, frame24("USLT", uslt(plain))),
            oggPage(5, 0, byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23), 2) + oggPage(5, 1, byteArrayOf(3) + "vorbis".toByteArray() + comment("LYRICS=$plain") + byteArrayOf(1)),
            res("front.m4a").readBytes().copyOf(4000),
        )
        val random = java.util.Random(344)
        val file = tmp.newFile()
        for (s in samples) {
            for (n in 0..minOf(s.size, 300)) { file.writeBytes(s.copyOf(n)); read(file) }
            repeat(1500) {
                val b = s.copyOf()
                repeat(1 + random.nextInt(6)) { b[random.nextInt(minOf(b.size, 400))] = random.nextInt(256).toByte() }
                file.writeBytes(b)
                read(file)
            }
        }
        for (n in listOf(0, 1, 11, 12, 13)) { file.writeBytes(ByteArray(n)); assertNull(read(file)) }
        // Formats it doesn't know.
        file.writeBytes("RIFF".toByteArray() + ByteArray(100)); assertNull(read(file))
    }

    // ------------------------------------------------------------ Enhanced LRC

    @Test fun enhancedLrcIsReadAsWords() {
        val worded = Lyrics(LyricsSource.LRCLIB, true, listOf(
            LyricLine(1_000, "Hi there", words = listOf(LyricWord(1_000, 1_400, "Hi "), LyricWord(1_500, 2_000, "there"))),
            LyricLine(65_400, "", isGap = true),
            LyricLine(70_010, "Bye"),
        ))
        val lines = Lrc.parse(LyricsTags.text(worded, words = true))
        val hi = lines.first { it.text == "Hi there" }
        assertEquals(listOf("Hi ", "there"), hi.words.map { it.text })
        assertEquals(listOf(1_000L, 1_500L), hi.words.map { it.startMs })
        // The first word to the next's start, the last to where the line ends.
        assertEquals(listOf(1_500L, 65_400L), hi.words.map { it.endMs })
        assertTrue(lines.any { it.isGap && it.timeMs == 65_400L })
        assertTrue(lines.first { it.text == "Bye" }.words.isEmpty())
        assertFalse(lines.any { it.text.contains("<") })
    }

    @Test fun plainLrcIsUnchanged() {
        val lines = Lrc.parse("[00:01.00]Less <3 more\n[00:02.50]Two")
        assertEquals(listOf("Less <3 more", "Two"), lines.filter { !it.isGap }.map { it.text })
        assertTrue(lines.all { it.words.isEmpty() })
    }

    // ------------------------------------------------------------ Among the other sources

    private val later = 2_000_000_000_000L // saved "in the future", so nothing is re-asked over the network
    private val song = Song("local:344", "Test Song", listOf(ArtistRef("Test Artist")), durationSec = 30)

    private fun repo(
        rows: List<LyricsEntity>, order: List<LyricsSource>, on: Boolean = true, inFile: String? = synced,
        preferSynced: Boolean = true,
    ): LyricsRepository {
        val dao = object : LyricsDao {
            override suspend fun get(songId: String, source: String) = rows.firstOrNull { it.songId == songId && it.source == source }
            override suspend fun put(e: LyricsEntity) {}
            override suspend fun clearMisses() {}
            override suspend fun allFor(songId: String) = rows.filter { it.songId == songId }
        }
        val settings = AppSettings(lyricsOrder = order, preferSynced = preferSynced, behavior = BehaviorPrefs(embeddedLyrics = on))
        return LyricsRepository(OkHttpClient(), dao, YouTubeMusic(InnerTube(OkHttpClient(), { settings })), { settings }).apply {
            fileLyrics = { inFile }
        }
    }

    private val lrclib = LyricsEntity(song.id, LyricsSource.LRCLIB.name, true, "[00:01.00]From a service", later)
    private val genius = LyricsEntity(song.id, LyricsSource.GENIUS.name, false, "Plain from a service", later)

    @Test fun offUntilSwitchedOn() {
        val off = AppSettings()
        assertFalse(off.behavior.embeddedLyrics)
        assertFalse(LyricsSource.EMBEDDED in off.lyricsSources)
        assertEquals(defaultLyricsOrder.filter { it != LyricsSource.EMBEDDED }, off.lyricsSources)
        assertEquals(LyricsSource.EMBEDDED, defaultLyricsOrder.last())
        val on = off.copy(behavior = off.behavior.copy(embeddedLyrics = true))
        assertEquals(LyricsSource.EMBEDDED, on.lyricsSources.last())
        // Every other source switched off leaves the file's alone only when it's on.
        val allOff = LyricsSource.entries.toSet()
        assertEquals(defaultLyricsOrder.filter { it != LyricsSource.EMBEDDED }, off.copy(lyricsOff = allOff).lyricsSources)
        assertEquals(listOf(LyricsSource.EMBEDDED), on.copy(lyricsOff = allOff).lyricsSources)
    }

    @Test fun switchedOffTheFileIsNotLookedAt() = runBlocking {
        val order = listOf(LyricsSource.LRCLIB, LyricsSource.EMBEDDED)
        val r = repo(listOf(lrclib), order, on = false)
        assertEquals(LyricsSource.LRCLIB, r.auto(song)!!.source)
    }

    @Test fun anEarlierSourceInTheOrderWinsLikeAnyOther() = runBlocking {
        val r = repo(listOf(lrclib), listOf(LyricsSource.LRCLIB, LyricsSource.EMBEDDED))
        assertEquals(LyricsSource.LRCLIB, r.auto(song)!!.source)
    }

    @Test fun movedUpTheFileWins() = runBlocking {
        val r = repo(listOf(lrclib), listOf(LyricsSource.EMBEDDED, LyricsSource.LRCLIB))
        val got = r.auto(song)!!
        assertEquals(LyricsSource.EMBEDDED, got.source)
        assertTrue(got.synced)
    }

    @Test fun syncedInTheFileBeatsPlainFromAService() = runBlocking {
        val r = repo(listOf(genius), listOf(LyricsSource.GENIUS, LyricsSource.EMBEDDED))
        assertEquals(LyricsSource.EMBEDDED, r.auto(song)!!.source)
    }

    @Test fun plainInTheFileIsUsedWhenNothingElseHasLyrics() = runBlocking {
        val none = LyricsEntity(song.id, LyricsSource.LRCLIB.name, false, "", later)
        val r = repo(listOf(none), listOf(LyricsSource.LRCLIB, LyricsSource.EMBEDDED), inFile = plain)
        val got = r.auto(song)!!
        assertEquals(LyricsSource.EMBEDDED, got.source)
        assertFalse(got.synced)
    }

    /** With no network and nothing saved: what "Lyrics on mobile data" off and offline both go through. */
    @Test fun readsWithNoServiceAsked() = runBlocking {
        val r = repo(emptyList(), listOf(LyricsSource.KUGOU, LyricsSource.EMBEDDED))
        assertEquals(LyricsSource.EMBEDDED, r.savedOnly(song)!!.source)
        r.choices = null
        assertEquals(LyricsSource.EMBEDDED, r.fetch(song, LyricsSource.EMBEDDED)!!.source)
    }

    @Test fun aSongWithoutAFileHasNone() = runBlocking {
        val r = repo(emptyList(), listOf(LyricsSource.EMBEDDED), inFile = null)
        assertNull(r.savedOnly(song))
        assertNull(r.fetch(song, LyricsSource.EMBEDDED))
    }

    /** A file's own word timings are its own: nothing is compared with a service's. */
    @Test fun wordsInTheFileAreLeftAsTheyAre() = runBlocking {
        val r = repo(emptyList(), listOf(LyricsSource.EMBEDDED), inFile = "[00:01.00]<00:01.00>Hi <00:01.50>there\n[00:02.50]<00:02.50>Again <00:03.00>now")
        val got = r.auto(song)!!
        assertTrue(got.wordSynced)
        assertEquals(1_500L, got.lines.first { !it.isGap }.words[1].startMs)
    }
}

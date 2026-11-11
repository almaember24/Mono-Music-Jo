package com.prism.music

import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.db.MetaDao
import com.prism.music.data.db.MotionArtEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.meta.MetaRepository
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import com.prism.music.data.stream.NewPipeDownloader
import com.prism.music.data.stream.YouTubeStreamInterceptor
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Live integration checks against YouTube Music and the lyric/metadata
 * providers, run on the JVM (signed-out). Run with: gradlew :app:testReleaseUnitTest
 */
@Category(LiveNetwork::class)
class LiveApiTest {
    companion object {
        private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        private var settings = AppSettings()
        private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

        @BeforeClass @JvmStatic
        fun init() {
            NewPipe.init(NewPipeDownloader(http), Localization("en", "US"))
        }
    }

    private fun log(msg: String) = println("[live] $msg")

    @Test
    fun homeFeed() = runBlocking {
        val shelves = ytm.fullHome(2)
        shelves.forEach { log("home shelf '${it.title}' (${it.items.size}) e.g. ${it.items.first().title}") }
        assertTrue("home has shelves", shelves.isNotEmpty())
        assertTrue("shelves have items", shelves.all { it.items.isNotEmpty() })
    }

    @Test
    fun searchAndCollections() = runBlocking {
        val songs = ytm.search("Blinding Lights The Weeknd", SearchFilter.SONGS).items.filterIsInstance<SongItem>()
        log("songs: " + songs.take(3).joinToString { "${it.song.title} / ${it.song.artistText} / ${it.song.album?.title} / ${it.song.durationSec}s / ${it.song.id}" })
        assertTrue(songs.isNotEmpty())
        val s = songs.first().song
        assertTrue("artist parsed", s.artists.isNotEmpty())
        assertTrue("duration parsed", s.durationSec > 0)

        val albums = ytm.search("After Hours The Weeknd", SearchFilter.ALBUMS).items.filterIsInstance<AlbumItem>()
        log("albums: " + albums.take(3).joinToString { "${it.title} (${it.subtitle}) ${it.id}" })
        val album = ytm.album((albums.firstOrNull { it.subtitle.contains("Weeknd") && it.subtitle.startsWith("Album") } ?: albums.first()).id)
        log("album '${album.title}' by '${album.subtitle}' · ${album.secondSubtitle} · ${album.songs.size} tracks · playlist ${album.playlistId} · thumb ${album.thumbnail != null}")
        album.songs.take(3).forEach { log("  track ${it.title} / ${it.artistText} / ${it.durationSec}s") }
        assertTrue(album.songs.size > 5)

        val artists = ytm.search("Daft Punk", SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>()
        val artist = ytm.artist(artists.first().id)
        log("artist '${artist.name}' subs=${artist.subscribers} shelves=" + artist.shelves.joinToString { "${it.title}(${it.items.size})" } + " radio=${artist.radioPlaylistId} shuffle=${artist.shufflePlaylistId}")
        assertTrue(artist.shelves.isNotEmpty())
        assertNotNull(artist.radioPlaylistId)
        assertNotNull(artist.shufflePlaylistId)
        val shuffled = ytm.next(artist.shuffleVideoId, artist.shufflePlaylistId, artist.shuffleParams)
        log("artist shuffle queue: ${shuffled.songs.size} songs; mix=${artist.radioPlaylistId}")
        assertTrue(shuffled.songs.size > 5)

        val playlists = ytm.search("top 100 global", SearchFilter.PLAYLISTS).items.filterIsInstance<PlaylistItem>()
        log("playlists: " + playlists.take(3).joinToString { "${it.title} ${it.id}" })
        val pl = ytm.playlistAll(playlists.first().id, 300)
        log("playlist '${pl.title}' ${pl.subtitle} · ${pl.songs.size} songs")
        assertTrue(pl.songs.isNotEmpty())

        val sugg = ytm.searchSuggestions("taylor sw")
        log("suggestions: $sugg")
        assertTrue(sugg.isNotEmpty())
    }

    @Test
    fun radioLyricsAndVideoCounterpart() = runBlocking {
        val song = ytm.search("Blinding Lights The Weeknd", SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
        val radio = ytm.next(song.id, "RDAMVM${song.id}")
        log("radio ${radio.songs.size} songs, cont=${radio.continuation != null}, lyrics=${radio.lyricsBrowseId}, counterparts=${radio.counterparts.size}")
        radio.songs.take(4).forEach { log("  ${it.title} / ${it.artistText} / ${it.durationSec}s") }
        assertTrue(radio.songs.size > 5)
        val more = ytm.next(song.id, "RDAMVM${song.id}", continuation = radio.continuation)
        log("radio continuation ${more.songs.size} songs")

        val single = ytm.next(song.id)
        for (q in listOf("Blinding Lights The Weeknd", "Levitating Dua Lipa", "Bad Guy Billie Eilish", "Espresso Sabrina Carpenter")) {
            val s = ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
            val mv = ytm.findMusicVideo(s)
            log("music video for ${s.title} / ${s.artistText} -> $mv")
            assertNotNull("music video for $q", mv)
        }

        val lyrics = ytm.lyrics(single.lyricsBrowseId ?: radio.lyricsBrowseId!!)
        log("ytm lyrics: ${lyrics?.take(80)?.replace("\n", " / ")}")
        assertTrue(!lyrics.isNullOrBlank())

        val extras = ytm.playerExtras(song.id)
        log("player extras: loudness=${extras.loudnessDb} tracking=${extras.trackingUrl?.take(60)}")
    }

    @Test
    fun musicVideosOpenAsTheirSong() = runBlocking {
        var found = 0
        for (q in listOf("Blinding Lights The Weeknd", "Pursuit of Happiness Kid Cudi", "Bad Guy Billie Eilish", "Kid Cudi live performance", "Tiny Desk Concert")) {
            val videos = ytm.search(q, SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song }.take(2)
            for (v in videos) {
                val s = ytm.songForVideo(v)
                log("video \"${v.title}\" / ${v.artistText} (${v.durationSec}s) -> ${s?.let { "\"${it.title}\" / ${it.artistText} (${it.durationSec}s, video=${it.isVideo})" }}")
                if (s != null) { found++; assertTrue(!s.isVideo) }
            }
        }
        assertTrue("some videos matched a song", found > 0)
        // Artist pages' Videos and Live shelves.
        for (name in listOf("Kid Cudi", "Billie Eilish", "Coldplay")) {
            val id = ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<com.prism.music.data.model.ArtistItem>().first().id
            ytm.artist(id).shelves.filter { it.title.contains("video", true) || it.title.contains("live", true) }.forEach { shelf ->
                shelf.items.filterIsInstance<SongItem>().map { it.song }.take(4).forEach { v ->
                    val s = ytm.songForVideo(v)
                    log("[$name · ${shelf.title}] \"${v.title}\" -> ${s?.let { "\"${it.title}\" / ${it.artistText}" }}")
                }
            }
        }
    }

    @Test
    fun moods() = runBlocking {
        val shelves = ytm.moodsAndGenres()
        shelves.forEach { s -> log("mood shelf '${s.title}': " + s.items.joinToString { it.title }) }
        val moods = shelves.flatMap { it.items }.filterIsInstance<MoodItem>()
        assertTrue(moods.isNotEmpty())
        val page = ytm.browseShelves(moods.first().id, moods.first().params)
        log("mood page shelves: " + page.joinToString { "${it.title}(${it.items.size})" })
        assertTrue(page.isNotEmpty())
        val ranked = com.prism.music.data.CategoryRanker.rank(shelves, mapOf("Rock" to 0.5, "Indie & alternative" to 0.5), 20)
        ranked.sections.forEach { (t, l) -> log("kept '$t': " + l.joinToString { it.title }) }
        val dropped = moods.map { it.title }.distinct() - ranked.sections.flatMap { it.second }.map { it.title }.toSet()
        log("dropped as regional: $dropped")
        assertTrue(ranked.sections.flatMap { it.second }.none { com.prism.music.data.meta.Genres.isRegional(it.title) })
    }

    @Test
    fun streamResolutionAndRangeRequests() {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=4NRXx6U8ABQ")
        val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        audio.forEach { log("audio itag=${it.itag} ${it.format?.mimeType} ${it.codec} ${it.averageBitrate}kbps sr=${it.itagItem?.sampleRate}") }
        assertTrue(audio.isNotEmpty())
        val video = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        log("video-only streams: " + video.joinToString { "${it.height}p ${it.codec}" })

        val client = http.newBuilder().addInterceptor(YouTubeStreamInterceptor()).build()
        val best = audio.maxBy { it.averageBitrate }
        log("client param: c=" + Regex("[?&]c=([^&]+)").find(best.content)?.groupValues?.get(1))
        client.newCall(Request.Builder().url(best.content).header("Range", "bytes=1000-1999").build()).execute().use { r ->
            val bytes = r.body.bytes()
            log("range 1000-1999 -> HTTP ${r.code} ${bytes.size} bytes, content-range=${r.header("Content-Range")}")
            assertEquals(206, r.code)
            assertEquals(1000, bytes.size)
        }
        client.newCall(Request.Builder().url(best.content).build()).execute().use { r ->
            val buf = ByteArray(65536)
            val n = r.body.byteStream().read(buf)
            log("open request -> HTTP ${r.code}, length=${r.header("Content-Length")}, first read $n bytes")
            assertTrue(r.code == 200 || r.code == 206)
            assertTrue(n > 0)
        }
    }

    @Test
    fun downloadThroughput() {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=4NRXx6U8ABQ")
        val best = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxBy { it.averageBitrate }
        val clen = Regex("[?&]clen=(\\d+)").find(best.content)!!.groupValues[1].toLong()
        val raw = http.newBuilder().addInterceptor(YouTubeStreamInterceptor()).build()
        fun timeIt(label: String, block: () -> Long) {
            val t = System.nanoTime(); val n = block(); val s = (System.nanoTime() - t) / 1e9
            log("$label: $n bytes in ${"%.2f".format(s)}s = ${"%.0f".format(n / 1024.0 / s)} KB/s")
        }
        timeIt("open-ended (old path), first 2MB") {
            raw.newCall(Request.Builder().url(best.content).build()).execute().use { r ->
                val src = r.body.byteStream(); val buf = ByteArray(65536); var total = 0L
                while (total < 2_000_000) { val n = src.read(buf); if (n < 0) break; total += n }
                total
            }
        }
        val chunked = http.newBuilder().addInterceptor(YouTubeStreamInterceptor(http)).build()
        timeIt("chunked, whole file ($clen)") {
            chunked.newCall(Request.Builder().url(best.content).build()).execute().use { r ->
                assertEquals(clen, r.body.contentLength())
                val src = r.body.byteStream(); val buf = ByteArray(65536); var total = 0L
                while (true) { val n = src.read(buf); if (n < 0) break; total += n }
                assertEquals(clen, total)
                total
            }
        }
        chunked.newCall(Request.Builder().url(best.content).header("Range", "bytes=100-").build()).execute().use { r ->
            assertEquals(206, r.code)
            assertEquals(clen - 100, r.body.bytes().size.toLong())
        }
    }

    private val fakeLyricsDao = object : LyricsDao {
        override suspend fun get(songId: String, source: String): LyricsEntity? = null
        override suspend fun put(e: LyricsEntity) {}
        override suspend fun clearMisses() {}
        override suspend fun allFor(songId: String): List<LyricsEntity> = emptyList()
    }

    @Test
    fun lyricSources() = runBlocking {
        val repo = LyricsRepository(http, fakeLyricsDao, ytm) { settings }
        val song = ytm.search("Blinding Lights The Weeknd", SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
        var found = 0
        for (src in LyricsSource.entries) {
            val l = repo.fetch(song, src, force = true)
            log("lyrics ${src.label}: ${if (l == null) "none" else "${l.lines.size} lines, synced=${l.synced}, wordSynced=${l.wordSynced}, first='${l.lines.firstOrNull { !it.isGap }?.text}'"}")
            if (l != null) found++
        }
        assertTrue("at least 8 lyric sources answered", found >= 8)
        val apple = repo.fetch(song, LyricsSource.APPLE)
        assertTrue("Apple Music lyrics are word-synced", apple?.wordSynced == true)
        val line = apple!!.lines.first { !it.isGap }
        log("apple first line words: " + line.words.joinToString("|") { "${it.text}@${it.startMs}" })
        assertTrue(line.words.joinToString("") { it.text }.trim() == line.text)
        val auto = repo.auto(song)
        log("auto -> ${auto?.source} synced=${auto?.synced}")
        assertTrue(auto?.synced == true)
    }

    /** In-memory DAO that remembers, so a poisoned cache entry would show up. */
    private class MemoryLyricsDao : LyricsDao {
        val rows = HashMap<String, LyricsEntity>()
        override suspend fun get(songId: String, source: String) = rows["$songId|$source"]
        override suspend fun put(e: LyricsEntity) { rows["${e.songId}|${e.source}"] = e }
        override suspend fun clearMisses() { rows.values.removeAll { it.content.isEmpty() } }
        override suspend fun allFor(songId: String) = rows.values.filter { it.songId == songId && it.content.isNotEmpty() }
    }

    @Test
    fun newLyricSourcesAndCancellationSafety() = runBlocking {
        val dao = MemoryLyricsDao()
        val repo = LyricsRepository(http, dao, ytm) { settings }
        val espresso = ytm.search("Espresso Sabrina Carpenter", SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
        for (src in listOf(LyricsSource.LYRICSPLUS, LyricsSource.BINI, LyricsSource.UNISON, LyricsSource.APPLE)) {
            val l = repo.fetch(espresso, src, force = true)
            log("new source ${src.label}: ${l?.lines?.size} lines, wordSynced=${l?.wordSynced}, first='${l?.lines?.firstOrNull { !it.isGap }?.text}'")
            assertNotNull("${src.label} has lyrics for Espresso", l)
        }

        // Regression: a lookup cancelled part-way must not be cached as "no lyrics".
        val song = ytm.search("Levitating Dua Lipa", SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
        val job = launch { repo.fetch(song, LyricsSource.LRCLIB) }
        kotlinx.coroutines.delay(30)
        job.cancel()
        job.join()
        log("cache after cancel: ${dao.rows["${song.id}|LRCLIB"]?.content?.length}")
        val again = repo.fetch(song, LyricsSource.LRCLIB)
        log("after cancel, LRCLIB -> ${again?.lines?.size} lines")
        assertNotNull("lyrics still found after a cancelled lookup", again)
    }

    private val fakeMeta = object : MetaDao {
        val g = HashMap<String, GenreEntity>()
        val m = HashMap<String, MotionArtEntity>()
        override suspend fun genre(key: String) = g[key]
        override suspend fun genres(keys: List<String>) = keys.mapNotNull { g[it] }
        override suspend fun putGenre(e: GenreEntity) { g[e.key] = e }
        override suspend fun clearGenresBefore(before: Long) { g.values.removeAll { it.fetchedAt < before && !it.key.startsWith("dzalbum:") } }
        override suspend fun motion(key: String) = m[key]
        override suspend fun putMotion(e: MotionArtEntity) { m[e.key] = e }
    }

    @Test
    fun genresAndMotionArtwork() = runBlocking {
        val meta = MetaRepository(http, fakeMeta)
        val queries = listOf("Blinding Lights The Weeknd", "HUMBLE Kendrick Lamar", "Jolene Dolly Parton", "One More Time Daft Punk", "Smells Like Teen Spirit")
        var hits = 0
        for (q in queries) {
            val s = ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
            val g = meta.genreFor(s)
            log("genre '${s.title}' by ${s.artistText} -> $g")
            if (g != null) hits++
        }
        assertTrue(hits >= 4)
    }

    @Test
    fun extraGenresAreConservative() = runBlocking {
        val meta = MetaRepository(http, fakeMeta)
        for (name in listOf("Kid Cudi", "The Weeknd", "Post Malone", "Daft Punk", "Taylor Swift", "Linkin Park", "Frank Ocean", "Coldplay", "Beyoncé", "Morgan Wallen", "Drake", "Lana Del Rey")) {
            val main = meta.artistGenre(name) ?: continue
            log("$name: $main + ${meta.artistExtraGenres(name, main)}")
        }
    }

    @Test
    fun genreCategoriesMatchYouTubeMusic() {
        val G = com.prism.music.data.meta.Genres
        val cases = mapOf(
            "Hip-Hop/Rap" to "Hip-hop", "Rap/Hip Hop" to "Hip-hop", "R&B/Soul" to "R&B & soul", "Alternative" to "Indie & alternative",
            "Metalcore" to "Metal", "Hard Rock" to "Rock", "Dance" to "Dance & electronic", "Dubstep" to "Dance & electronic",
            "Reggae" to "Reggae & caribbean", "Singer/Songwriter" to "Folk & acoustic", "Films/Games" to "Soundtracks & musicals",
            "Latin Music" to "Pop", "K-Pop" to "Pop", "J-Pop" to "Pop", "Reggaeton" to "Hip-hop", "Latin Jazz" to "Jazz", "Pop" to "Pop", "Country" to "Country & Americana",
            "Christian & Gospel" to "Christian & gospel", "Afrobeats" to "Pop", "Brazilian Music" to "Pop", "Asian Music" to "Pop", "Mandopop & cantopop" to "Pop",
            "Indian Music" to "Pop", "Bollywood & Indian" to "Pop", "Kids" to "Family", "Soul & Funk" to "R&B & soul", "Electro" to "Dance & electronic",
            "Trap" to "Hip-hop", "Punk" to "Rock", "Classical" to "Classical", "Jazz" to "Jazz", "Blues" to "Blues", "Folk" to "Folk & acoustic",
            // iTunes' names (songs' genres come from there now) and Deezer's sub-genres.
            "Musicals" to "Soundtracks & musicals", "Soundtrack" to "Soundtracks & musicals", "Christian" to "Christian & gospel",
            "Children's Music" to "Family", "Metal" to "Metal", "Hard Rock" to "Rock", "Electronic" to "Dance & electronic",
            "Urbano latino" to "Hip-hop", "Pop Latino" to "Pop", "Chill Out/Trip-Hop/Lounge" to "Dance & electronic", "Dancehall/Ragga" to "Reggae & caribbean",
        )
        cases.forEach { (raw, want) -> assertEquals("'$raw'", want, G.normalize(raw)) }
        listOf("Bollywood & Indian", "K-Pop", "Hip-hop en Español", "Afrobeats", "Regional Mexican", "Arabic", "Mandopop & Cantopop", "OPM").forEach {
            assertTrue("regional: $it", G.isRegional(it))
        }
        listOf("Hip-hop", "Pop", "Indie & alternative", "Rock", "Designer", "Chill", "Workout", "Reggae & caribbean", "Country & Americana").forEach {
            assertTrue("not regional: $it", !G.isRegional(it))
        }
    }

    @Test
    fun bigPlaylistLoadsFastAndEverySongGetsACategory() = runBlocking {
        val meta = MetaRepository(http, fakeMeta).apply { trace = { if (it.contains("itunes=") && !it.contains("itunes=null") || it.contains("-> null") || it.contains("error")) log(it) } }
        val id = ytm.search("2010s hits", SearchFilter.PLAYLISTS).items.filterIsInstance<PlaylistItem>().first().id
        var t = System.currentTimeMillis()
        val first = ytm.playlist(id)
        log("first page: ${first.songs.size} songs in ${System.currentTimeMillis() - t} ms (continuation=${first.continuation != null})")
        val all = ytm.playlistAll(id, 300).songs
        t = System.currentTimeMillis()
        val got = HashMap<String, String>()
        var batches = 0
        meta.resolveGenres(all) { b -> got.putAll(b); batches++ }
        val ms = System.currentTimeMillis() - t
        log("classified ${all.size} songs (${all.map { it.primaryArtist }.distinct().size} artists) in $ms ms over $batches batches")
        log("categories: " + got.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.joinToString { "${it.first} ${it.second}" })
        all.take(12).forEach { log("  ${it.title} / ${it.primaryArtist} -> ${got[it.id]}") }
        assertEquals("every song categorised", all.size, all.count { it.id in got })
        assertTrue("only YouTube Music categories", got.values.all { it in com.prism.music.data.meta.Genres.all })
    }

    @Test
    fun canvasSources() = runBlocking {
        val repo = com.prism.music.data.canvas.CanvasRepository(http)
        var hits = 0
        for (q in listOf("Anti-Hero Taylor Swift", "Espresso Sabrina Carpenter", "Blinding Lights The Weeknd", "Houdini Dua Lipa", "Not Like Us Kendrick Lamar")) {
            val s = ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song
            val canvas = repo.canvasFor(s)
            log("canvas for '${s.title}' (${s.album?.title}): ${canvas?.url}")
            if (canvas != null) hits++
        }
        assertTrue("at least two songs have animated cover art", hits >= 2)
    }
}

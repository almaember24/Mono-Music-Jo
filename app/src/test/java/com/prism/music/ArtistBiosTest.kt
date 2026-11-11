package com.prism.music

import com.prism.music.data.ArtistBio
import com.prism.music.data.ArtistBioCheck
import com.prism.music.data.ArtistBios
import com.prism.music.data.WikipediaBios
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/** The bio's checks with Wikipedia's answers typed by hand; [ArtistBioLiveTest] asks the real thing. */
class ArtistBiosTest {
    @Test fun anArticleIsNamedWhatTheArtistIsWhateverItsBrackets() {
        assertTrue(ArtistBioCheck.sameName("Muse", "Muse (band)"))
        assertTrue(ArtistBioCheck.sameName("クイーン", "クイーン (バンド)"))
        assertTrue(ArtistBioCheck.sameName("周杰倫", "周杰倫（歌手）"))
        assertTrue(ArtistBioCheck.sameName("AC/DC", "AC/DC"))
        assertTrue("through a redirect", ArtistBioCheck.sameName("Ye", "Kanye West", matched = "Ye (musician)"))
        assertFalse(ArtistBioCheck.sameName("Bush", "Kate Bush"))
        assertFalse(ArtistBioCheck.sameName("Muse", "Muses"))
        assertFalse(ArtistBioCheck.sameName("", "Muse"))
    }

    @Test fun titlesCountOnlyWhenSetOffAsTitles() {
        val text = """Their album ''Nevermind'' sold well. [[In Utero (album)|In Utero]] followed. "Lithium," was a single.
            |The word bleach in passing, and a walk on the beach.""".trimMargin()
        assertEquals(3, ArtistBioCheck.named(text, listOf("Nevermind", "In Utero", "Lithium", "Bleach", "Walk On")))
        assertEquals("curly or straight", 1, ArtistBioCheck.named("the song ''Don't Stop''", listOf("Don’t Stop")))
        assertEquals("Japanese marks", 1, ArtistBioCheck.named("アルバム『オペラ座の夜』", listOf("オペラ座の夜")))
    }

    @Test fun titlesThatTellNobodyApartAreLeftOut() {
        val page = ArtistPage(
            id = "x", name = "Boston", thumbnail = null, description = null, subscribers = null, radioPlaylistId = null, shufflePlaylistId = null,
            shelves = listOf(
                Shelf("Albums", items = listOf(AlbumItem("a", "Boston", "", null, null), AlbumItem("b", "Greatest Hits", "", null, null), AlbumItem("c", "Third Stage (Deluxe)", "", null, null))),
                Shelf("Songs", items = listOf(SongItem(Song("s", "Amanda", emptyList())), SongItem(Song("v", "Amanda 2", emptyList(), isVideo = true)), SongItem(Song("t", "Third Stage", emptyList())))),
            ),
        )
        assertEquals(listOf("Third Stage", "Amanda"), ArtistBioCheck.titlesOf(page))
    }

    @Test fun aLongDiscographyHasToBeNamedMore() {
        assertEquals(2, ArtistBioCheck.needed(List(7) { "t$it" }))
        assertEquals(3, ArtistBioCheck.needed(List(8) { "t$it" }))
    }

    @Test fun whatYouTubeQuotesAlreadyIsntAskedFor() {
        assertTrue(ArtistBioCheck.youTubeQuotesIt("Queen are… From Wikipedia (https://en.wikipedia.org/wiki/Queen_...) under CC"))
        assertTrue(ArtistBioCheck.youTubeQuotesIt("Fuente: Wikipedia (https://es.wikipedia.org/wiki/Muse), bajo la licencia"))
        assertFalse(ArtistBioCheck.youTubeQuotesIt("The official channel of Boston"))
        assertFalse(ArtistBioCheck.youTubeQuotesIt(null))
    }

    @Test fun languagesNameTheirWikipedia() {
        assertEquals("es", ArtistBioCheck.wikiLanguage(Locale("es", "MX")))
        assertEquals("he", ArtistBioCheck.wikiLanguage(Locale("iw")))
        assertEquals("id", ArtistBioCheck.wikiLanguage(Locale("in")))
        assertEquals("no", ArtistBioCheck.wikiLanguage(Locale("nb")))
        assertNull(ArtistBioCheck.wikiLanguage(Locale.ROOT))
    }

    // -------- The finder, against typed answers.

    private fun wikipedia(vararg answers: Pair<String, String>, asked: MutableList<String> = mutableListOf(), code: Int = 200): WikipediaBios {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            asked += url
            val body = answers.firstOrNull { url.contains(it.first) }?.second
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Test")
                .code(if (body != null) code else 404)
                .body((body ?: "{}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        return WikipediaBios(http, "test")
    }

    private fun summary(type: String = "standard", title: String = "Muse (band)", item: String? = "Q1") =
        """{"type":"$type","title":"$title","extract":"Muse are an English rock band.",${if (item != null) "\"wikibase_item\":\"$item\"," else ""}
            "content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/Muse_(band)"}}}"""

    private val search = "search/page" to """{"pages":[{"key":"Muses","title":"Muses"},{"key":"Muse_(band)","title":"Muse (band)"},{"key":"Muse_(disambiguation)","title":"Muse (disambiguation)"}]}"""
    private val isArtist = "wikidata.org" to """{"claims":{"P434":[{"id":"x"}]}}"""
    private val article = "v1/page/Muse_(band)" to """{"source":"''Absolution'' and ''Drones'' and [[Hysteria (song)|Hysteria]]","license":{"title":"CC BY-SA 4.0 here","url":"https://creativecommons.org/licenses/by-sa/4.0/deed.en"}}"""
    private val titles = listOf("Absolution", "Drones", "Hysteria")

    @Test fun anArticlePassingEveryCheckBecomesABioWithItsCredit() = runBlocking {
        val bio = wikipedia(search, "summary/Muse_(band)" to summary(), isArtist, article).find("Muse", titles, "en")
        assertEquals("Muse (band)", bio?.title)
        assertEquals("CC BY-SA 4.0 here", bio?.licenseName)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/deed.en", bio?.licenseUrl)
        assertEquals("https://en.m.wikipedia.org/wiki/Muse_(band)", bio?.url)
    }

    @Test fun aDisambiguationPageDoesNot() = runBlocking {
        assertNull(wikipedia(search, "summary/Muse_(band)" to summary(type = "disambiguation"), isArtist, article).find("Muse", titles, "en"))
    }

    @Test fun neitherDoesASubjectWikidataDoesntKnowAsAMusician() = runBlocking {
        assertNull(wikipedia(search, "summary/Muse_(band)" to summary(), "wikidata.org" to """{"claims":{}}""", article).find("Muse", titles, "en"))
        assertNull(wikipedia(search, "summary/Muse_(band)" to summary(item = null), isArtist, article).find("Muse", titles, "en"))
    }

    @Test fun neitherDoesAnArticleNamingTooFewOfTheirTitles() = runBlocking {
        val thin = "v1/page/Muse_(band)" to """{"source":"''Absolution'' only"}"""
        assertNull(wikipedia(search, "summary/Muse_(band)" to summary(), isArtist, thin).find("Muse", titles, "en"))
    }

    @Test fun theLongArticleIsOnlyAskedForAfterTheCheapChecks() = runBlocking {
        val asked = mutableListOf<String>()
        wikipedia(search, "summary/Muse_(band)" to summary(), "wikidata.org" to """{"claims":{}}""", article, asked = asked).find("Muse", titles, "en")
        assertFalse(asked.toString(), asked.any { it.contains("rest.php/v1/page/") })
    }

    @Test fun aListenersLanguageWithNoArticleShowsNothing() = runBlocking {
        val en = arrayOf(search, "en.wikipedia.org/api/rest_v1/page/summary/Muse_(band)" to summary(), isArtist, article)
        // No German links from the English article: no English text in its place.
        assertNull(wikipedia(*en, "links/language" to """[{"code":"fr","key":"Muse_(groupe)"}]""").find("Muse", titles, "de"))
    }

    @Test fun aShortLocalArticleIsFoundThroughTheEnglishOne() = runBlocking {
        val bio = wikipedia(
            "en.wikipedia.org/w/rest.php/v1/search" to search.second,
            "en.wikipedia.org/api/rest_v1/page/summary/Muse_(band)" to summary(),
            isArtist,
            "en.wikipedia.org/w/rest.php/v1/page/Muse_(band)/links/language" to """[{"code":"de","key":"Muse_(Band)"}]""",
            "en.wikipedia.org/w/rest.php/v1/page/Muse_(band)" to article.second,
            "de.wikipedia.org/api/rest_v1/page/summary/Muse_(Band)" to summary(title = "Muse (Band)"),
        ).find("Muse", titles, "de")
        assertEquals("de", bio?.lang)
        assertEquals("Muse (Band)", bio?.title)
    }

    @Test fun aFailedAskIsAnErrorNotNoArticle() {
        val failed = runCatching { runBlocking { wikipedia(search, code = 503).find("Muse", titles, "en") } }
        assertTrue(failed.exceptionOrNull() is IOException)
    }

    /** Wikipedia's answers, but for [broken] answers (each a body that dies partway through, or isn't an answer at all). */
    private fun answering(broken: () -> okhttp3.ResponseBody?, vararg answers: Pair<String, String>): WikipediaBios {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val cut = broken()
            val typed = answers.firstOrNull { url.contains(it.first) }?.second
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Test").code(if (cut != null || typed != null) 200 else 404)
                .body(cut ?: (typed ?: "{}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        return WikipediaBios(http, "test")
    }

    /** The first bytes arrive, then the connection goes. */
    private fun dying(): okhttp3.ResponseBody {
        val source = object : okio.Source {
            private var sent = false
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (sent) throw IOException("connection lost")
                sent = true
                sink.writeUtf8("{\"pages\":[{\"key\":")
                return 17
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }
        return object : okhttp3.ResponseBody() {
            private val buffered = source.buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = buffered
        }
    }

    private val whole = arrayOf(search, "summary/Muse_(band)" to summary(), isArtist, article)

    /** The class promises a bad connection is never remembered as "no article": it would stay that way for a month. */
    @Test fun aConnectionLostMidAnswerIsAFailureNotNoArticle() = runBlocking {
        var broken = true
        val wiki = answering({ if (broken) dying() else null }, *whole)
        val failed = runCatching { wiki.find("Muse", titles, "en") }
        assertTrue("${failed.getOrNull()} / ${failed.exceptionOrNull()}", failed.exceptionOrNull() is IOException)

        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), wiki::find)
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = true))
        broken = false
        assertEquals("nothing was remembered: asked again, and found", "Muse (band)", bios.bio("id", "Muse", titles, "en", mayAsk = true)?.title)
    }

    /** A Wi-Fi sign-in page answers 200 with its own HTML: nothing to do with what Wikipedia has. */
    @Test fun aPageThatIsntAnAnswerIsAFailureToo() = runBlocking {
        var broken = true
        val wiki = answering({ if (broken) "<html>Sign in to this Wi-Fi</html>".toResponseBody("text/html".toMediaType()) else null }, *whole)
        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), wiki::find)
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = true))
        broken = false
        assertEquals("Muse (band)", bios.bio("id", "Muse", titles, "en", mayAsk = true)?.title)
    }

    @Test fun anAddressFromElsewhereIsNotLinked() = runBlocking {
        val odd = summary().replace("https://en.m.wikipedia.org/wiki/Muse_(band)", "https://example.com/x")
        assertNull(wikipedia(search, "summary/Muse_(band)" to odd, isArtist, article).find("Muse", titles, "en"))
    }

    // -------- What's kept.

    private val bio = ArtistBio("Muse (band)", "Muse are…", "https://en.wikipedia.org/wiki/Muse_(band)", "CC", "https://creativecommons.org/", "en")

    private class Finder(var answer: suspend () -> ArtistBio?) {
        var asks = 0
        suspend fun find(n: String, t: List<String>, l: String): ArtistBio? { asks++; return answer() }
    }

    @Test fun aBioIsKeptAndComesBackWithoutANetwork() = runBlocking {
        var now = 1_000L
        val f = Finder { bio }
        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), f::find) { now }
        assertEquals(bio, bios.bio("id", "Muse", titles, "en", mayAsk = true))
        assertEquals(bio, bios.bio("id", "Muse", titles, "en", mayAsk = false))
        assertEquals(bio, bios.bio("id", "Muse", titles, "en", mayAsk = true))
        assertEquals("kept, not asked again", 1, f.asks)
        now += ArtistBios.KEEP_BIO_MS + 1
        bios.bio("id", "Muse", titles, "en", mayAsk = true)
        assertEquals("asked again once it's aged", 2, f.asks)
    }

    @Test fun noArticleIsRememberedButAFailureIsNot() = runBlocking {
        var now = 1_000L
        val f = Finder { null }
        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), f::find) { now }
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = true))
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = true))
        assertEquals(1, f.asks)
        now += ArtistBios.KEEP_NONE_MS + 1
        f.answer = { throw IOException("no connection") }
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = true))
        assertEquals(2, f.asks)
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = true))
        assertEquals("the failure wasn't kept as an answer", 3, f.asks)
    }

    @Test fun aFailureKeepsTheBioThatWasThere() = runBlocking {
        var now = 1_000L
        val f = Finder { bio }
        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), f::find) { now }
        bios.bio("id", "Muse", titles, "en", mayAsk = true)
        now += ArtistBios.KEEP_BIO_MS + 1
        f.answer = { throw IOException("no connection") }
        assertEquals(bio, bios.bio("id", "Muse", titles, "en", mayAsk = true))
    }

    @Test fun eachLanguageHasItsOwn() = runBlocking {
        val f = Finder { bio }
        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), f::find)
        bios.bio("id", "Muse", titles, "en", mayAsk = true)
        assertNull(bios.bio("id", "Muse", titles, "de", mayAsk = false))
    }

    @Test fun nothingAskedWhenNotAllowed() = runBlocking {
        val f = Finder { bio }
        val bios = ArtistBios(Files.createTempDirectory("bios").toFile(), f::find)
        assertNull(bios.bio("id", "Muse", titles, "en", mayAsk = false))
        assertEquals(0, f.asks)
    }
}

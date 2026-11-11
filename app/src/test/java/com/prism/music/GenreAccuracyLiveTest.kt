package com.prism.music

import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.MetaDao
import com.prism.music.data.db.MotionArtEntity
import com.prism.music.data.meta.MetaRepository
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * How often Prism's genre for a song is right, live against Deezer (and iTunes), on well-known songs across every
 * category: including artists who cross genres (Post Malone's and Beyoncé's country records, Taylor Swift's pop),
 * where a song can't just take its artist's usual genre. Each song lists the answers a listener would accept.
 * Prints every miss; fails below 90%.
 */
@Category(LiveNetwork::class)
class GenreAccuracyLiveTest {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private val dao = object : MetaDao {
        val g = HashMap<String, GenreEntity>()
        override suspend fun genre(key: String) = g[key]
        override suspend fun genres(keys: List<String>) = keys.mapNotNull { g[it] }
        override suspend fun putGenre(e: GenreEntity) { g[e.key] = e }
        override suspend fun clearGenresBefore(before: Long) { g.values.removeAll { it.fetchedAt < before && !it.key.startsWith("dzalbum:") } }
        override suspend fun motion(key: String): MotionArtEntity? = null
        override suspend fun putMotion(e: MotionArtEntity) {}
    }

    private val POP = "Pop"; private val ROCK = "Rock"; private val ALT = "Indie & alternative"; private val HIP = "Hip-hop"
    private val RNB = "R&B & soul"; private val COUNTRY = "Country & Americana"; private val DANCE = "Dance & electronic"
    private val METAL = "Metal"; private val JAZZ = "Jazz"; private val CLASSICAL = "Classical"; private val BLUES = "Blues"
    private val REGGAE = "Reggae & caribbean"; private val FOLK = "Folk & acoustic"; private val CHRISTIAN = "Christian & gospel"
    private val FILM = "Soundtracks & musicals"

    /** Title, artist, length in seconds (0: unknown), the genres that are right. */
    private val cases: List<Triple<String, String, Set<String>>> = listOf(
        // Pop
        Triple("Anti-Hero", "Taylor Swift", setOf(POP)),
        Triple("Shake It Off", "Taylor Swift", setOf(POP)),
        Triple("Espresso", "Sabrina Carpenter", setOf(POP)),
        Triple("Flowers", "Miley Cyrus", setOf(POP)),
        Triple("As It Was", "Harry Styles", setOf(POP)),
        Triple("Levitating", "Dua Lipa", setOf(POP, DANCE)),
        Triple("Blinding Lights", "The Weeknd", setOf(POP, RNB)),
        Triple("Billie Jean", "Michael Jackson", setOf(POP, RNB)),
        Triple("drivers license", "Olivia Rodrigo", setOf(POP)),
        // Country
        Triple("Last Night", "Morgan Wallen", setOf(COUNTRY)),
        Triple("Fast Car", "Luke Combs", setOf(COUNTRY)),
        Triple("Tennessee Whiskey", "Chris Stapleton", setOf(COUNTRY)),
        Triple("Something in the Orange", "Zach Bryan", setOf(COUNTRY, FOLK)),
        Triple("Jolene", "Dolly Parton", setOf(COUNTRY)),
        Triple("I Had Some Help", "Post Malone", setOf(COUNTRY)),
        Triple("TEXAS HOLD 'EM", "Beyoncé", setOf(COUNTRY)),
        // Hip-hop
        Triple("HUMBLE.", "Kendrick Lamar", setOf(HIP)),
        Triple("God's Plan", "Drake", setOf(HIP)),
        Triple("SICKO MODE", "Travis Scott", setOf(HIP)),
        Triple("Lose Yourself", "Eminem", setOf(HIP)),
        Triple("rockstar", "Post Malone", setOf(HIP)),
        Triple("Juicy", "The Notorious B.I.G.", setOf(HIP)),
        // R&B & soul
        Triple("Kill Bill", "SZA", setOf(RNB)),
        Triple("Thinkin Bout You", "Frank Ocean", setOf(RNB)),
        Triple("Yeah!", "Usher", setOf(RNB, HIP)),
        Triple("Crazy in Love", "Beyoncé", setOf(RNB, POP)),
        Triple("Best Part", "Daniel Caesar", setOf(RNB)),
        Triple("Respect", "Aretha Franklin", setOf(RNB)),
        // Rock
        Triple("Bohemian Rhapsody", "Queen", setOf(ROCK)),
        Triple("Back in Black", "AC/DC", setOf(ROCK, METAL)),
        Triple("Everlong", "Foo Fighters", setOf(ROCK, ALT)),
        Triple("Stairway to Heaven", "Led Zeppelin", setOf(ROCK)),
        Triple("Hotel California", "Eagles", setOf(ROCK)),
        // Indie & alternative
        Triple("Do I Wanna Know?", "Arctic Monkeys", setOf(ALT, ROCK)),
        Triple("Mr. Brightside", "The Killers", setOf(ALT, ROCK)),
        Triple("Creep", "Radiohead", setOf(ALT, ROCK)),
        Triple("The Less I Know the Better", "Tame Impala", setOf(ALT)),
        Triple("Smells Like Teen Spirit", "Nirvana", setOf(ALT, ROCK)),
        Triple("In the End", "Linkin Park", setOf(ALT, ROCK, METAL)),
        // Metal
        Triple("Enter Sandman", "Metallica", setOf(METAL)),
        Triple("Duality", "Slipknot", setOf(METAL)),
        Triple("Chop Suey!", "System Of A Down", setOf(METAL)),
        Triple("Can You Feel My Heart", "Bring Me The Horizon", setOf(METAL, ALT, ROCK)),
        // Dance & electronic
        Triple("One More Time", "Daft Punk", setOf(DANCE)),
        Triple("Levels", "Avicii", setOf(DANCE)),
        Triple("Summer", "Calvin Harris", setOf(DANCE, POP)),
        Triple("Don't You Worry Child", "Swedish House Mafia", setOf(DANCE)),
        Triple("Animals", "Martin Garrix", setOf(DANCE)),
        Triple("Teardrop", "Massive Attack", setOf(DANCE, ALT)),
        // Jazz, classical, blues
        Triple("So What", "Miles Davis", setOf(JAZZ)),
        Triple("Giant Steps", "John Coltrane", setOf(JAZZ)),
        Triple("What a Wonderful World", "Louis Armstrong", setOf(JAZZ, POP)),
        Triple("Experience", "Ludovico Einaudi", setOf(CLASSICAL)),
        Triple("On the Nature of Daylight", "Max Richter", setOf(CLASSICAL, FILM)),
        Triple("The Thrill Is Gone", "B.B. King", setOf(BLUES)),
        Triple("Hoochie Coochie Man", "Muddy Waters", setOf(BLUES)),
        // Reggae, folk, Christian, soundtracks
        Triple("Three Little Birds", "Bob Marley & The Wailers", setOf(REGGAE)),
        Triple("Temperature", "Sean Paul", setOf(REGGAE, HIP, POP)),
        Triple("Little Lion Man", "Mumford & Sons", setOf(FOLK, ALT)),
        Triple("Skinny Love", "Bon Iver", setOf(FOLK, ALT)),
        Triple("Stick Season", "Noah Kahan", setOf(FOLK, ALT, POP)),
        Triple("The Sound of Silence", "Simon & Garfunkel", setOf(FOLK, ROCK)),
        Triple("Oceans (Where Feet May Fail)", "Hillsong UNITED", setOf(CHRISTIAN)),
        Triple("Graves Into Gardens", "Elevation Worship", setOf(CHRISTIAN)),
        Triple("You Say", "Lauren Daigle", setOf(CHRISTIAN)),
        Triple("Time", "Hans Zimmer", setOf(FILM, CLASSICAL)),
        Triple("Let It Go", "Idina Menzel", setOf(FILM)),
        Triple("This Is Me", "Keala Settle", setOf(FILM)),
    )

    @Test
    fun songsGetTheRightGenre() = runBlocking {
        val meta = MetaRepository(http, dao)
        var right = 0
        val misses = ArrayList<String>()
        cases.forEachIndexed { i, (title, artist, want) ->
            val song = Song("acc$i", title, listOf(ArtistRef(artist)))
            val got = runCatching { meta.genreFor(song, wait = true) }.getOrNull()
            if (got in want) right++ else misses += "'$title' by $artist -> $got (want ${want.joinToString(" / ")})"
        }
        val pct = right * 100 / cases.size
        println("GENRES: $right of ${cases.size} right ($pct%)")
        misses.forEach { println("  MISS $it") }
        assertTrue("$pct% right", pct >= 90)
    }
}

package com.prism.music

import com.prism.music.data.ArtistBio
import com.prism.music.data.WikipediaBios
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * The artist page's bio live against Wikipedia. The names here are the ones a plain lookup gets wrong (2026-11-05: "Prince"
 * is Prince William's article, "Nirvana" the Buddhist idea, "Muse" the goddesses, "Boston" the city, and Drake, Queen and
 * Bush are disambiguation pages), with a few of each artist's titles as YouTube Music lists them.
 */
@Category(LiveNetwork::class)
class ArtistBioLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val finder = WikipediaBios(http, "Prism-test (https://github.com/Yigder/Prism)")

    private fun find(name: String, lang: String, vararg titles: String): ArtistBio? = runBlocking { finder.find(name, titles.toList(), lang) }

    private fun assertArticle(key: String, bio: ArtistBio?) {
        assertNotNull(key, bio)
        assertTrue("${bio!!.url} isn't $key", bio.url.contains(key))
        assertTrue(bio.text.length > 40)
        assertTrue(bio.licenseUrl, bio.licenseUrl.contains("creativecommons.org"))
        assertTrue(bio.licenseName, bio.licenseName.isNotBlank())
    }

    @Test fun theArtistsOwnArticleEvenWhenTheirNameIsSomethingElsesFirst() {
        assertArticle("Prince_(musician)", find("Prince", "en", "Purple Rain", "When Doves Cry", "Kiss", "Sign O' The Times", "Raspberry Beret"))
        assertArticle("Drake_(musician)", find("Drake", "en", "God's Plan", "Hotline Bling", "Her Loss", "For All The Dogs", "Passionfruit"))
        assertArticle("Queen_(band)", find("Queen", "en", "Bohemian Rhapsody", "Another One Bites The Dust", "We Will Rock You", "Don't Stop Me Now"))
        assertArticle("Muse_(band)", find("Muse", "en", "Supermassive Black Hole", "Hysteria", "Absolution", "The Resistance", "Drones"))
        assertArticle("Nirvana_(band)", find("Nirvana", "en", "Smells Like Teen Spirit", "Nevermind", "In Utero", "Lithium", "Bleach"))
        assertArticle("Boston_(band)", find("Boston", "en", "More Than a Feeling", "Third Stage", "Don't Look Back", "Peace of Mind", "Walk On"))
        assertArticle("Bush_(British_band)", find("Bush", "en", "Glycerine", "Comedown", "Machinehead", "The Science Of Things", "Golden State"))
        assertArticle("The_Weeknd", find("The Weeknd", "en", "Blinding Lights", "After Hours", "Starboy", "Dawn FM", "Kiss Land"))
    }

    @Test fun theListenersLanguage() {
        assertEquals("es", find("Daft Punk", "es", "Random Access Memories", "Discovery", "Homework", "Human After All", "One More Time")?.lang)
        assertEquals("de", find("Muse", "de", "Absolution", "The Resistance", "Drones", "Black Holes and Revelations", "Hysteria")?.lang)
    }

    @Test fun aSameNamedArticleIsNotThisArtist() {
        // Someone else called Prince, Queen or Boston, with titles of their own.
        assertNull(find("Prince", "en", "Midnight In Lagos", "Paper Lanterns Falling", "Another Quiet Tuesday"))
        assertNull(find("Queen", "en", "Midnight In Lagos", "Paper Lanterns Falling", "Another Quiet Tuesday"))
        assertNull(find("Boston", "en", "Midnight In Lagos", "Paper Lanterns Falling", "Another Quiet Tuesday"))
        // A real singer of that name, whose article exists, but not with these titles.
        assertNull(find("Drake", "en", "Pink Moon", "Bryter Layter", "Five Leaves Left"))
    }

    @Test fun nothingWhenThereIsNoArticle() {
        assertNull(find("Zzqx Nonexistent Band", "en", "Midnight In Lagos", "Paper Lanterns Falling"))
        // Too little to tell anyone apart by.
        assertNull(find("Queen", "en", "Bohemian Rhapsody"))
    }
}

package com.prism.music

import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.TakeoutHistory
import com.prism.music.data.looks.Backgrounds
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookStore
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.playback.RangedSource
import com.prism.music.ui.screens.phoneNorm
import com.prism.music.ui.screens.splitCredit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What 1.8.2.1's review found in that release's own changes, and in the same family as #378. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ReviewFixes1821Test {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    // ------------------------------------------------------------------ #378's family

    @Test fun anOwnLookSavedInGermanKeepsItsPhoto(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(app, scope)
        val photo = File(app.filesDir, "backdrops/all-1.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(64) { it.toByte() }) }
        val dir = File(app.filesDir, "looks").apply { mkdirs() }
        val look = Look(
            id = "my-old", name = "Alt", icons = com.prism.music.data.looks.LookIcons(),
            // As on a phone, the path starts with "/" (without a Windows drive letter, when the tests run on Windows).
            values = mapOf(Look.BACKDROPS to Backgrounds.tag(mapOf("HOME" to "FOTO|${photo.absolutePath.substringAfter(':').replace('\\', '/')}", "SEARCH" to "AURORA"))),
        )
        File(dir, "my-old.json").writeText(kotlinx.serialization.json.Json.encodeToString(Look.serializer(), look))

        val mine = LookStore(app, settings, scope).mine.value.single { it.id == "my-old" }
        val map = Backgrounds.parse(mine.values[Look.BACKDROPS])
        assertNotNull("the photo's kept with the Look", Backgrounds.assetOf(map.getValue("HOME")))
        assertEquals("AURORA", map["SEARCH"])
        assertTrue("and that's saved", File(dir, "my-old.json").readText().contains("asset:"))
    }

    @Test fun aSharedLookCannotBringAPathUnderAnotherWord() {
        val kinds = mapOf(Look.BACKDROPS to 's')
        val shared = Look(
            id = "", name = "Shared", icons = com.prism.music.data.looks.LookIcons(),
            values = mapOf(Look.BACKDROPS to Backgrounds.tag(mapOf("HOME" to "X|/sdcard/a.jpg", "SEARCH" to "AURORA"))),
        )
        val left = Backgrounds.parse(LookCode.sanitized(shared, kinds).values[Look.BACKDROPS])
        assertNull(left["HOME"])
        assertEquals("AURORA", left["SEARCH"])
    }

    // ------------------------------------------------------------------ Takeout, by content

    @Test fun aPlayListedInTwoFilesOfATakeoutCountsOnce() {
        val zip = java.io.ByteArrayOutputStream()
        val play = """{"header":"YouTube Music","title":"Watched A","titleUrl":"https://www.youtube.com/watch?v=abcdefghijk","time":"2026-01-01T10:00:00Z"}"""
        java.util.zip.ZipOutputStream(zip).use { z ->
            listOf("Takeout/YouTube/historial-de-reproducciones.json", "Takeout/Mi actividad/MiActividad.json").forEach { name ->
                z.putNextEntry(java.util.zip.ZipEntry(name)); z.write("[$play]".toByteArray()); z.closeEntry()
            }
        }
        val plays = ArrayList<ParsedPlay>()
        assertTrue(TakeoutHistory.read("takeout.zip", zip.toByteArray().inputStream()) { plays += it })
        assertEquals(1, plays.size)
    }

    // ------------------------------------------------------------------ An album's box

    @Test fun aWikipediaLinkInSomeonesOwnDescriptionIsNotACredit() {
        val own = "My favorite songs\nAbout https://en.wikipedia.org/wiki/Jazz here\nAnd a lot more that I wrote myself."
        val about = splitCredit(own)
        assertEquals(own, about.body)
        assertNull(about.credit)
        val album = "A record.\nFrom Wikipedia (https://en.wikipedia.org/wiki/X) under Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/legalcode)"
        assertNotNull(splitCredit(album).credit)
    }

    // ------------------------------------------------------------------ Search in Library

    @Test fun hindiVowelSignsAreNotGapsInAWord() {
        assertEquals(1, phoneNorm("काला").split(' ').size)
        assertFalse("क and ल alone aren't this word", phoneNorm("कमल").contains(phoneNorm("काला")))
        assertEquals("beyonce", phoneNorm("Beyoncé"))
        assertEquals("ac dc", phoneNorm("AC/DC"))
    }

    // ------------------------------------------------------------------ DJ mode's quick scan

    @Test fun aFailedRangedReadIsRemembered() {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("gone")
                .body("".toResponseBody("text/plain".toMediaType())).build()
        }).build()
        val source = RangedSource(http, "https://rr1.googlevideo.com/videoplayback", 2_000_000)
        assertNull(source.failure)
        runCatching { source.readAt(0, ByteArray(16), 0, 16) }
        // The extractor reads a failed read as the end of the song; a key from part of one mustn't be kept.
        assertNotNull(source.failure)
    }

    // ------------------------------------------------------------------ Romanized lyrics

    @Test fun anOrdinaryJapaneseKanjiIsNotTakenForAChineseForm() {
        // 筑 is 筑波's, not only the simplified form of 築.
        assertFalse(com.prism.music.data.lyrics.roman.JapaneseKanji.fromChinese('筑'.toString()).contains('築'))
    }
}

package com.prism.music

import com.prism.music.data.ModelStore
import com.prism.music.data.db.RomanizedDao
import com.prism.music.data.db.RomanizedEntity
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Romanizer
import com.prism.music.data.lyrics.roman.Japanese
import com.prism.music.data.lyrics.roman.JapaneseKanji
import com.prism.music.data.lyrics.roman.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * #370: a Japanese song from KuGou came out partly in pinyin. Found on the reporter's song: its lyrics write some kanji
 * in simplified Chinese forms, which the Japanese dictionary doesn't know, so those lines went to Google, which reads
 * those forms in pinyin even when told the text is Japanese; and a line of only kanji sent on its own passes for Chinese.
 * The lines here are made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class JapaneseKanjiTest {
    private val saved = HashMap<String, RomanizedEntity>()
    private val dao = object : RomanizedDao {
        override suspend fun get(key: String) = saved[key]
        override suspend fun put(e: RomanizedEntity) { saved[e.key] = e }
    }
    private val asked = ArrayList<Pair<String, List<String>>>()

    /** Stands in for Google: says what it was asked, and answers each line with "roman". */
    private val fakeGoogle = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8().substringAfter("q=")
        val lines = URLDecoder.decode(body, "UTF-8").split("\n")
        asked += req.url.queryParameter("sl")!! to lines
        val json = """[[[null,null,null,"${lines.joinToString("\\n") { "roman" }}"]]]"""
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }).build()

    private fun romanizer(http: OkHttpClient, reader: Japanese? = null) =
        Romanizer(http, dao, ModelStore(RuntimeEnvironment.getApplication(), http), CoroutineScope(Dispatchers.Default), japaneseForTests = reader)

    @Test fun aJapaneseSongsKanjiOnlyLineIsAskedForAsJapanese() = runBlocking {
        val lines = listOf(LyricLine(0, "夢を見ていた"), LyricLine(2_000, "月光"))
        val r = romanizer(fakeGoogle).romanize("ja-google", lines, emptySet(), network = true)
        assertEquals("all as Japanese, and together", listOf("ja" to listOf("夢を見ていた", "月光")), asked)
        assertEquals("roman", r[1]!!.text)
    }

    @Test fun chineseFormsGoToGoogleAsJapaneseKanji() = runBlocking {
        val lines = listOf(LyricLine(0, "强い心を守る"))
        romanizer(fakeGoogle).romanize("ja-forms", lines, emptySet(), network = true)
        assertEquals(listOf("強い心を守る"), asked.single().second)
    }

    @Test fun theSongsTitleCanSayItsJapanese() = runBlocking {
        romanizer(fakeGoogle).romanize("ja-title", listOf(LyricLine(0, "月光")), emptySet(), network = true, title = "ひかり")
        assertEquals("ja", asked.single().first)
    }

    @Test fun aChineseSongStaysChinese() = runBlocking {
        romanizer(fakeGoogle).romanize("zh", listOf(LyricLine(0, "我爱你"), LyricLine(2_000, "月光")), emptySet(), network = true)
        assertEquals("auto", asked.single().first)
        assertEquals("its own characters are sent as they are", "我爱你", asked.single().second[0])
    }

    /** With the dictionary: everything's read here, Google isn't asked, and a karaoke line's words stay lined up. */
    @Test fun theDictionaryReadsThemWithTheirTimings() = runBlocking {
        val line = LyricLine(
            0, "一生悬命に", 2_000,
            words = listOf(LyricWord(0, 1_000, "一生"), LyricWord(1_000, 1_800, "悬命"), LyricWord(1_800, 2_000, "に")),
        )
        val r = romanizer(fakeGoogle, Japanese.bundled()).romanize("ja-dict", listOf(line, LyricLine(3_000, "强い心")), emptySet(), network = true)
        assertTrue("nothing left for Google: $asked", asked.isEmpty())
        val first = r[0]
        assertNotNull(first)
        assertTrue(first!!.text, first.text.replace(" ", "").startsWith("isshoukenmei"))
        assertTrue("no kanji left: ${first.text}", Script.foreignIn(first.text).isEmpty())
        assertEquals(listOf(0L, 1_800L), first.words.map { it.startMs }.let { listOf(it.first(), it.last()) })
        assertTrue(r[1]!!.text, r[1]!!.text.startsWith("tsuyoi"))
    }

    @Test fun theTextKeepsItsLength() {
        assertEquals("強い", JapaneseKanji.fromChinese("强い"))
        assertEquals("机の上", JapaneseKanji.fromChinese("机の上"))
        assertEquals("nothing to do", JapaneseKanji.fromChinese("nothing to do"))
    }

    /** Only forms Japanese doesn't use: a kanji the dictionary can read must never be changed into another one. */
    @Test fun everyPairIsRight() {
        val ja = Japanese.bundled()
        val known = JapaneseKanji.pairs.keys.filter { Script.foreignIn(ja.romanize(it.toString())).isEmpty() }
        assertTrue("Japanese reads these itself, so they can't be in the table: $known", known.isEmpty())
        val unread = JapaneseKanji.pairs.values.toSet().filter { Script.foreignIn(ja.romanize(it.toString())).isNotEmpty() }
        println("[kanji] ${JapaneseKanji.pairs.size} pairs; no reading on their own: $unread")
        assertFalse("nothing maps onto another Chinese form", JapaneseKanji.pairs.values.any { it in JapaneseKanji.pairs })
    }

    /** Live: Google's answer for a kanji-only line, asked as Japanese, is no pinyin. */
    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test fun googleReadsTheLineAsJapanese() = runBlocking {
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val r = romanizer(http).romanize("ja-live2", listOf(LyricLine(0, "夢を見ていた"), LyricLine(2_000, "月光"), LyricLine(4_000, "一生悬命")), emptySet(), network = true)
        println("[live] " + r.map { it?.text })
        val pinyin = Regex("[āáǎàēéěèīíǐìǒòóǔùúǖǘǚǜ]|yuè|xuán")
        for (line in r) assertFalse(line?.text, pinyin.containsMatchIn(line!!.text.lowercase()))
    }
}

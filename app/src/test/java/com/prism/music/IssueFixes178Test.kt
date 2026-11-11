package com.prism.music

import com.prism.music.data.lyrics.LyricsTranslator
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 1.7.8's fixes for GitHub issues. */
class IssueFixes178Test {
    @get:Rule val tmp = TemporaryFolder()

    /** A fake Google: [gtx] and [dict] say how each endpoint answers; counts every request. */
    private class FakeGoogle(var gtx: Int = 200, var dict: Int = 200) : Interceptor {
        var requests = 0
        override fun intercept(chain: Interceptor.Chain): Response {
            requests++
            val req = chain.request()
            val url = req.url.toString()
            val form = req.body as okhttp3.FormBody
            val qs = (0 until form.size).filter { form.name(it) == "q" }.map { form.value(it) }
            val (code, body) = when {
                "translate_a/single" in url -> gtx to """[[["${qs.single().split("\n").joinToString("\\n") { "es:$it" }}","x",null]],null,"en"]"""
                "translate_a/t" in url -> dict to qs.joinToString(",", "[", "]") { """["es:$it","en"]""" }
                else -> 404 to ""
            }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body((if (code == 200) body else "refused").toResponseBody("application/json".toMediaType())).build()
        }
    }

    private val lines = listOf("a yellow bicycle", "", "by the quiet river")

    @Test fun translationsFallBackToTheOtherEndpointAndStayOnThePhone() = runBlocking {
        val google = FakeGoogle(gtx = 429)
        val http = OkHttpClient.Builder().addInterceptor(google).build()
        val dir = tmp.newFolder("translations")
        val t = LyricsTranslator(http, dir).translate(lines, "es")
        assertEquals(listOf("es:a yellow bicycle", null, "es:by the quiet river"), t.translations)
        assertEquals("en", t.from)
        assertEquals(1, dir.listFiles()!!.size)
        // A new Prism start (a new translator) reads it from the phone, without asking Google.
        val before = google.requests
        val again = LyricsTranslator(http, dir).translate(lines, "es")
        assertEquals(t.translations, again.translations)
        assertEquals(before, google.requests)
    }

    @Test fun bothEndpointsRefusingSaysWhenToTryAgain() = runBlocking {
        val google = FakeGoogle(gtx = 429, dict = 429)
        val translator = LyricsTranslator(OkHttpClient.Builder().addInterceptor(google).build(), tmp.newFolder("t"))
        try {
            translator.translate(lines, "es"); fail("expected Unavailable")
        } catch (e: LyricsTranslator.Unavailable) {
            assertTrue(e.retryAtMs > System.currentTimeMillis())
        }
        // Resting: the next song doesn't hammer Google again.
        val before = google.requests
        runCatching { translator.translate(listOf("another song"), "es") }
        assertEquals(before, google.requests)
    }

    /** Live: the main endpoint made to refuse, the real second one answers line by line (made-up text). */
    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test fun theSecondEndpointTranslatesLive() = runBlocking {
        val refuseMain = Interceptor { chain ->
            val req = chain.request()
            if ("translate_a/single" in req.url.toString()) {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(429).message("x").body("".toResponseBody(null)).build()
            } else chain.proceed(req)
        }
        val t = LyricsTranslator(OkHttpClient.Builder().addInterceptor(refuseMain).build())
            .translate(listOf("the yellow bicycle waits by the river", "", "a small boat"), "fr")
        assertEquals("en", t.from)
        assertEquals(3, t.translations.size)
        assertTrue(t.translations[0]!!.contains("vélo"))
        assertEquals(null, t.translations[1])
        assertTrue(t.translations[2]!!.contains("bateau"))
    }

    /** R11: several queue songs moved at once land in their order, and autoplay's suggestions stay after the listener's. */
    @Test fun queueSongsMoveTogether() {
        // Queue 0..7, playing 1, autoplay's suggestions from 5. Play 6 (autoplay's) and 3 next, in play order 3 then 6.
        var anchor = 1
        val next = com.prism.music.playback.QueueEdit.plan(8, listOf(3, 6), 5) { list, s -> (list.indexOf(anchor) + 1).also { anchor = s } }
        assertEquals(listOf(0, 1, 3, 6, 2, 4, 5, 7), next.order)
        // 5 is still autoplay's first suggestion; it sits at place 6 now.
        assertEquals(6, next.autoplayStart)
        // The player's moves give the same order.
        val replay = MutableList(8) { it }
        next.moves.forEach { (from, to) -> replay.add(to, replay.removeAt(from)) }
        assertEquals(next.order, replay)
        // To the end of what's queued: 2 goes just before autoplay's first, which stays first.
        val end = com.prism.music.playback.QueueEdit.plan(8, listOf(2), 5) { list, _ -> list.indexOfFirst { it >= 5 && it != 2 } }
        assertEquals(listOf(0, 1, 3, 4, 2, 5, 6, 7), end.order)
        assertEquals(5, end.autoplayStart)
        // With no autoplay, it stays none.
        assertEquals(-1, com.prism.music.playback.QueueEdit.plan(4, listOf(3), -1) { _, _ -> 2 }.autoplayStart)
    }

    /** Q11 (#149): Replay for any month and any days; a pick survives being saved and read back. */
    @Test fun replayForAnyDates() {
        val zone = java.time.ZoneId.systemDefault()
        fun ms(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val march = com.prism.music.ui.screens.Period.monthOf(2025, 2)
        assertEquals(march, com.prism.music.ui.screens.Period.of(march.key))
        val (a, b) = com.prism.music.ui.screens.range(march)
        assertEquals(ms(java.time.LocalDate.of(2025, 3, 1)), a)
        assertEquals(ms(java.time.LocalDate.of(2025, 4, 1)) - 1, b)
        // Picked either way round, the days are in order; the last day counts whole.
        val first = java.time.LocalDate.of(2025, 6, 10).toEpochDay()
        val last = java.time.LocalDate.of(2025, 6, 12).toEpochDay()
        val days = com.prism.music.ui.screens.Period.days(last, first)
        assertEquals(days, com.prism.music.ui.screens.Period.of(days.key))
        val (from, to) = com.prism.music.ui.screens.range(days)
        assertEquals(ms(java.time.LocalDate.of(2025, 6, 10)), from)
        assertEquals(ms(java.time.LocalDate.of(2025, 6, 13)) - 1, to)
        // The old keys still read as before.
        assertEquals(com.prism.music.ui.screens.Period.year(2024), com.prism.music.ui.screens.Period.of("y2024"))
        assertEquals(com.prism.music.ui.screens.Period.DAYS30, com.prism.music.ui.screens.Period.of("DAYS30"))
        assertEquals(com.prism.music.ui.screens.Period.TODAY, com.prism.music.ui.screens.Period.of("TODAY"))
    }

    @Test fun theMainEndpointIsUsedWhenItAnswers() = runBlocking {
        val google = FakeGoogle(dict = 500)
        val t = LyricsTranslator(OkHttpClient.Builder().addInterceptor(google).build()).translate(lines, "es")
        assertEquals(listOf("es:a yellow bicycle", null, "es:by the quiet river"), t.translations)
        assertEquals(1, google.requests)
    }
}

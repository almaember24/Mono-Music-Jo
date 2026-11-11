package com.prism.music

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.HomeCache
import com.prism.music.data.foryou.Variety
import com.prism.music.data.foryou.YourMusic
import com.prism.music.data.innertube.AccountSummary
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeSeen
import com.prism.music.data.innertube.describePlayerAnswer
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.stream.StreamResolver
import com.prism.music.ui.screens.extraShelves
import com.prism.music.ui.screens.picksDuringRefresh
import com.prism.music.ui.screens.speedDialTiles
import com.prism.music.ui.screens.youtubePicksNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.8.2.1's account round: Premium quality (#373), Speed dial (#372), the history's diagnostics (#377), Quick picks (#371). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IssueFixes1821AccountTest {
    @get:Rule val tmp = TemporaryFolder()

    private var signedIn: SettingsRepository? = null

    /** The settings store is shared with the JVM's other tests: this sign-in doesn't stay in it. */
    @org.junit.After fun signOut() {
        val s = signedIn ?: return
        s.logout()
        s.setPremiumStream(false)
        runBlocking { withTimeout(10_000) { s.flow.first { !it.isLoggedIn && !it.premiumStream } } }
    }

    /** A YouTube that answers the player as each client is told to: which itags each has for a song. */
    private inner class FakeStreams(val itagsFor: (client: String, id: String) -> List<Int>?) {
        val asked = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val text = if (path.endsWith("/player")) {
                val buf = okio.Buffer().also { req.body?.writeTo(it) }
                val body = InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject
                val client = body["context"]!!.jsonObject["client"]!!.jsonObject
                val name = client["clientName"]!!.toString().trim('"').let { n -> if (n == "TVHTML5" && "deviceMake" in client) "TV_SAMSUNG" else n }
                val id = body["videoId"]!!.toString().trim('"')
                asked += name to id
                playerAnswer(itagsFor(name, id))
            } else if (path.contains("visitor_id")) """{"responseContext":{"visitorData":"v"}}""" else "{}"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (path.endsWith("/player") || path.contains("visitor_id")) 200 else 404).message("fake")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val streams: StreamResolver

        init {
            signedIn = settings
            runBlocking {
                settings.saveAccount("SAPISID=abc; __Secure-3PAPISID=abc", "visitor", "user||")
                settings.setPremiumStream(true)
                withTimeout(10_000) { settings.flow.first { it.isLoggedIn && it.premiumStream } }
            }
            streams = StreamResolver(ApplicationProvider.getApplicationContext(), http, settings, InnerTube(http, { settings.current }))
        }

        fun account() = asked.filter { it.first != "VISIONOS" }
    }

    private fun playerAnswer(itags: List<Int>?): String {
        if (itags == null) return """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"nope"}}"""
        val formats = itags.joinToString(",") { """{"itag":$it,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://rr1.googlevideo.com/videoplayback?itag=$it","averageBitrate":"${if (it in StreamResolver.PREMIUM_ITAGS) 256000 else 130000}","contentLength":"1000","approxDurationMs":"200000"}""" }
        return """{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"21600","adaptiveFormats":[$formats]},"videoDetails":{"lengthSeconds":"200"}}"""
    }

    // ------------------------------------------------------------------ #373

    @Test fun aMusicVideoLookedUpForItsPictureDoesNotAskForPremium() {
        val fake = FakeStreams { _, _ -> listOf(251) }
        // Each song's music video is looked up ahead of time: three of them used to pause Premium for an hour.
        repeat(3) { fake.streams.resolve("video$it", withVideo = true) }
        fake.streams.analysisAudio("video4", video = true)
        fake.streams.durationSec("video5", video = true)
        assertEquals(emptyList<Pair<String, String>>(), fake.account())
        // So the next song is still asked for.
        fake.streams.resolve("song")
        assertTrue(fake.account().any { it.second == "song" })
    }

    @Test fun whenTheFirstClientHasNoPremiumFormatsTheOthersAreAskedOnce() {
        val fake = FakeStreams { client, _ -> if (client == "WEB_REMIX") listOf(251, 141) else listOf(251) }
        assertEquals(141, fake.streams.resolve("song1").itag)
        assertEquals(StreamResolver.PremiumState.GETTING, fake.streams.premiumState.value)
        // The one that had them is asked first from then on, and alone.
        fake.asked.clear()
        assertEquals(141, fake.streams.resolve("song2").itag)
        assertEquals(listOf("WEB_REMIX" to "song2"), fake.account())
    }

    @Test fun withoutPremiumFormatsThreeSongsPauseAskingAndItIsSaid() {
        val fake = FakeStreams { _, _ -> listOf(251) }
        fake.streams.resolve("song1")
        // Every client asked, to no avail. One song without them says nothing about the account yet.
        assertEquals(StreamResolver.AccountClients.order.size, fake.account().size)
        assertEquals(StreamResolver.PremiumState.NOT_KNOWN, fake.streams.premiumState.value)
        fake.asked.clear()
        // Nothing's had them yet, so the next song goes round too: the first song may just not have had them anywhere.
        fake.streams.resolve("song2")
        assertEquals(StreamResolver.AccountClients.order.size, fake.account().size)
        fake.streams.resolve("song3")
        assertEquals(StreamResolver.PremiumState.MISSING, fake.streams.premiumState.value)
        fake.asked.clear()
        // Paused: played the usual way, without asking.
        assertEquals(251, fake.streams.resolve("song4").itag)
        assertEquals(emptyList<Pair<String, String>>(), fake.account())
    }

    @Test fun aSongWithoutThemAnywhereDoesNotStopTheNextOneGoingRound() {
        // Only WEB_REMIX has them, and not for the first song.
        val fake = FakeStreams { client, id -> if (client == "WEB_REMIX" && id != "song1") listOf(251, 141) else listOf(251) }
        assertEquals(251, fake.streams.resolve("song1").itag)
        assertEquals(141, fake.streams.resolve("song2").itag)
        assertEquals(StreamResolver.PremiumState.GETTING, fake.streams.premiumState.value)
    }

    @Test fun anotherSignInStartsAfresh() {
        val fake = FakeStreams { _, _ -> listOf(251) }
        repeat(3) { fake.streams.resolve("song$it") }
        assertEquals(StreamResolver.PremiumState.MISSING, fake.streams.premiumState.value)
        fake.asked.clear()
        runBlocking {
            fake.settings.saveAccount("SAPISID=other; __Secure-3PAPISID=other", "visitor", "other||")
            withTimeout(10_000) { fake.settings.flow.first { it.cookie.contains("other") } }
        }
        assertFalse("the note was about the last sign-in", fake.streams.premiumStateIsCurrent())
        fake.streams.resolve("song9")
        assertTrue("the pause was too", fake.account().any { it.second == "song9" })
        assertTrue(fake.streams.premiumStateIsCurrent())
        assertEquals(StreamResolver.PremiumState.NOT_KNOWN, fake.streams.premiumState.value)
    }

    @Test fun aPremiumAnswerEndsAPauseOthersStartedWhileItWasAsked() {
        var now = 1_000L
        val b = StreamResolver.PremiumBackoff { now }
        repeat(StreamResolver.PremiumBackoff.MISSES_TO_PAUSE) { b.missed() }
        assertFalse(b.asking())
        b.found()
        assertTrue(b.asking())
        assertFalse("the client that had them is enough", b.walkNow())
        b.missed()
        assertTrue("a miss goes round again", b.walkNow())
    }

    @Test fun onlyTheFeedsOwnQuickPicksStayOutOfTheAddedSections() {
        fun shelf(title: String) = com.prism.music.data.model.Shelf(title = title, items = emptyList())
        val feeds = shelf("Quick picks")
        val other = shelf("From the community")
        val early = shelf("Quick picks")
        // The section shows this load's early copy, so the feed's isn't the same object, and came in as a second section.
        assertEquals(listOf(other), extraShelves(listOf(feeds, other), emptySet(), emptyList(), early, feeds, null, emptySet()))
    }

    @Test fun aRefreshKeepsTheSetOnShowInsteadOfYouTubesAfterTheWait() {
        val youtube = listOf(com.prism.music.data.model.Song(id = "yt", title = "YouTube's"))
        val shown = 0 to listOf(com.prism.music.data.model.Song(id = "mine", title = "On show"))
        assertEquals(emptyList<com.prism.music.data.model.Song>(), youtubePicksNow(youtube, false, shown, 1, true))
        assertEquals("the first load has nothing to keep", youtube, youtubePicksNow(youtube, false, null, 0, true))
        assertEquals("YouTube's own, chosen", youtube, youtubePicksNow(youtube, true, shown, 1, true))
        assertEquals("the source was switched", youtube, youtubePicksNow(youtube, false, shown, 1, false))
    }

    @Test fun thePauseIsShortForASignInThatHasHadPremiumAndEndsWithTheNetwork() {
        var now = 1_000L
        val b = StreamResolver.PremiumBackoff { now }
        b.onNetwork("wifi-1")
        repeat(StreamResolver.PremiumBackoff.MISSES_TO_PAUSE - 1) { assertFalse(b.missed(known = true)) }
        assertTrue(b.missed(known = true))
        assertEquals(10, b.minutesPaused())
        now += StreamResolver.PremiumBackoff.KNOWN_PAUSE_MS
        assertTrue(b.asking())
        repeat(StreamResolver.PremiumBackoff.MISSES_TO_PAUSE) { b.missed() }
        assertEquals(60, b.minutesPaused())
        // Same network: still paused. Another one, Wi-Fi or mobile data: asked again, at once.
        b.onNetwork("wifi-1")
        assertFalse(b.asking())
        b.onNetwork("wifi-2")
        assertTrue(b.asking())
        assertTrue("and all the clients again", b.walkNow())
    }

    @Test fun theDiagnosticsSayWhyPremiumWasNotAsked() {
        assertEquals("Premium quality is off", StreamResolver.premiumSkip(false, true, false, false, 0))
        assertEquals("signed out", StreamResolver.premiumSkip(true, false, false, false, 0))
        assertEquals("Data saver on a metered network", StreamResolver.premiumSkip(true, true, true, true, 0))
        assertNull("Data saver on Wi-Fi", StreamResolver.premiumSkip(true, true, true, false, 0))
        assertEquals("paused after songs without 256 kbps (5 min left)", StreamResolver.premiumSkip(true, true, false, false, 5))
        assertNull(StreamResolver.premiumSkip(true, true, false, false, 0))
    }

    @Test fun playerAnswersAreDescribedWithoutTheirLinks() {
        val answer = InnerTube.json.parseToJsonElement(
            """{"responseContext":{"serviceTrackingParams":[{"service":"GFEEDBACK","params":[{"key":"logged_in","value":"1"},{"key":"premium_membership","value":"member"}]}]},
               "videoDetails":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"},
               "streamingData":{"adaptiveFormats":[
                 {"itag":251,"mimeType":"audio/webm","url":"https://secret.example/link?sig=abc"},
                 {"itag":141,"mimeType":"audio/mp4"},
                 {"itag":774,"mimeType":"audio/webm","url":"https://secret.example/x","drmFamilies":["WIDEVINE"]},
                 {"itag":137,"mimeType":"video/mp4","url":"https://secret.example/v"}]}}""",
        ).jsonObject
        val text = describePlayerAnswer(answer)
        assertEquals("audio 251, without links 141, copy-protected 774, type ATV, signed in yes, membership member", text)
        assertFalse(text.contains("secret"))
    }

    // ------------------------------------------------------------------ The account, without secrets

    @Test fun youtubesOwnWordOnTheSignIn() {
        fun seen(json: String) = YouTubeSeen.of(InnerTube.json.parseToJsonElement(json))
        assertEquals(false, seen("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":true}}}""").signedIn)
        assertEquals(true, seen("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}}}""").signedIn)
        assertEquals(false, seen("""{"responseContext":{"serviceTrackingParams":[{"service":"CSI","params":[{"key":"yt_li","value":"0"}]}]}}""").signedIn)
        assertNull(seen("""{"responseContext":{}}""").signedIn)
        assertNull(seen("{}").signedIn)
    }

    @Test fun theAccountSummaryHoldsCookieNamesAndNeverValues() {
        val cookie = "SID=sidsecret; __Secure-3PAPISID=papisecret; SAPISID=sapisecret; LOGIN_INFO=loginsecret; nonsense without equals secret9"
        val line = AccountSummary.of(cookie, authUser = 1, dataSyncId = "brandsecret||usersecret", visitorData = "visitorsecret", savedAt = 1_000, now = 1_000 + 5 * 3_600_000L)
        assertFalse(line, line.contains("secret"))
        assertTrue(line, line.contains("cookies: LOGIN_INFO SAPISID SID __Secure-3PAPISID"))
        assertTrue(line, line.contains("Google account #1") && line.contains("brand channel in use") && line.contains("5 h ago") && line.contains("visitor id set"))
        assertEquals("signed out", AccountSummary.of("", 0, "", "", 0))
        assertTrue(AccountSummary.of("SAPISID=x", 0, "user||", "", 0).contains("own channel"))
    }

    // ------------------------------------------------------------------ #371

    private fun song(id: String, artist: String = "Artist $id") = Song(id = id, title = "Title $id", artists = listOf(ArtistRef(artist, null)))

    @Test fun eachPullToRefreshIsAnotherDraw() {
        val day = "2026-11-06"
        // The day's own draw stays as it was.
        assertEquals(Variety.daySeed(day, "picks"), YourMusic.drawSeed(day, "picks", 0))
        assertNotEquals(YourMusic.drawSeed(day, "picks", 0), YourMusic.drawSeed(day, "picks", 1))
        assertNotEquals(YourMusic.drawSeed(day, "picks", 1), YourMusic.drawSeed(day, "picks", 2))
        // And it does change what's drawn.
        val songs = (1..200).map { song("s$it") }
        val first = Variety.sample(songs, 20, YourMusic.drawSeed(day, "picks", 0), sharpness = 1.5) { 1.0 }
        val second = Variety.sample(songs, 20, YourMusic.drawSeed(day, "picks", 1), sharpness = 1.5) { 1.0 }
        assertNotEquals(first, second)
    }

    @Test fun aRefreshLeavesOutWhatIsOnShowWhileThereIsMore() {
        val fav = (1..30).map { song("f$it") }
        val new = (1..30).map { song("n$it") }
        val yt = (1..30).map { song("y$it") }
        val before = YourMusic.takeTurns(fav, new, yt, 20)
        assertEquals(20, before.size)
        val after = YourMusic.takeTurns(fav, new, yt, 20, shown = before.map { it.id }.toSet())
        assertEquals(20, after.size)
        assertTrue(after.none { a -> before.any { it.id == a.id } })
        // Too few others: they still make up the set.
        val few = YourMusic.takeTurns(fav.take(3), new.take(3), emptyList(), 6, shown = fav.take(3).map { it.id }.toSet())
        assertEquals(6, few.size)
    }

    @Test fun picksStayOnShowUntilARefreshsNewSetIsIn() {
        val old = listOf(song("old"))
        val new = listOf(song("new"))
        assertEquals("while it's made", old, picksDuringRefresh(emptyList(), 0 to old, refresh = 1, sameSource = true))
        assertEquals("then the new set", new, picksDuringRefresh(new, 0 to old, refresh = 1, sameSource = true))
        assertEquals("nothing at the start", emptyList<Song>(), picksDuringRefresh(emptyList(), null, refresh = 0, sameSource = true))
        assertEquals("the other source's set isn't kept", emptyList<Song>(), picksDuringRefresh(emptyList(), 0 to old, refresh = 1, sameSource = false))
    }

    @Test fun todaysPicksAreKeptForTheNextStartOnly() = runBlocking {
        val cache = HomeCache(tmp.newFolder("home"))
        assertNull(cache.readPicks("2026-11-06", "a"))
        cache.writePicks("2026-11-06", "a", listOf(song("x")))
        assertEquals(listOf("x"), cache.readPicks("2026-11-06", "a")?.map { it.id })
        assertNull("another day", cache.readPicks("2026-11-07", "a"))
        assertNull("another account", cache.readPicks("2026-11-06", "b"))
        cache.clear()
        assertNull("signing out clears them", cache.readPicks("2026-11-06", "a"))
    }

    @Test fun homePagesAreHandedOverAsTheyCome() = runBlocking {
        val fake = FakeYouTube { _, _ -> 200 to "{}" }
        var pages = 0
        fake.ytm.fullHomePages(onPage = { pages++ })
        assertEquals(1, pages)
    }

    // ------------------------------------------------------------------ #372

    @Test fun yourPlaysCanComeFirstOnSpeedDial() {
        val youtube = (1..20).map { AlbumItem("y$it", "Y$it", "", null) }
        val mine = (1..5).map { SongItem(song("m$it")) }
        // As before, off: YouTube's lead.
        assertEquals("y1", speedDialTiles(youtube, mine, emptyList(), youtubeOnly = false).first().id)
        val first = speedDialTiles(youtube, mine, emptyList(), youtubeOnly = false, yoursFirst = true)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "y1"), first.take(6).map { it.id })
        // "YouTube Music's only" still means that.
        assertEquals("y1", speedDialTiles(youtube, mine, emptyList(), youtubeOnly = true, yoursFirst = true).first().id)
    }
}

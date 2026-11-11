package com.prism.music

import android.content.Context
import androidx.room.Room
import com.prism.music.SpotifyFixtures.antiHero
import com.prism.music.SpotifyFixtures.blinding
import com.prism.music.SpotifyFixtures.creep
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.toEntity
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.MatchResult
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.TasteBoost
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.spotify.LikesPlan
import com.prism.music.data.spotify.LikesState
import com.prism.music.data.spotify.PrismLike
import com.prism.music.data.spotify.SavedTrack
import com.prism.music.data.spotify.SpotifyApi
import com.prism.music.data.spotify.SpotifyLikes
import com.prism.music.data.spotify.SpotifyParse
import com.prism.music.data.spotify.SpotifyPlaylists
import com.prism.music.data.spotify.SpotifyRecent
import com.prism.music.data.spotify.SpotifyStore
import com.prism.music.data.spotify.SpotifyTokens
import com.prism.music.data.spotify.SpotifyTop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * The Spotify connection's features over a real (in-memory) database (1.8.1): recent plays de-duplicated against a data
 * download's, top items shaping taste, playlists mirrored as Prism playlists through matching (with Spotify's ISRCs),
 * and Liked Songs both ways never going round in circles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotifyFeaturesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val importer = HistoryImporter(db)
    private val auth = app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)
    private val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox).apply {
        clientId = "0123456789abcdef0123456789abcdef"
        saveTokens(SpotifyTokens("acc", "ref", Long.MAX_VALUE))
        userId = "samr"
    }
    private val net = SpotifyFixtures.FakeSpotify()
    private val api = SpotifyApi(net.client, store, pause = {})
    private val dir = File(app.filesDir, "spotify-test").apply { deleteRecursively() }

    @After fun close() { db.close(); auth.edit().clear().commit(); prefs.edit().clear().commit(); dir.deleteRecursively() }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun ytSong(id: String, title: String, artist: String, sec: Int) = Song(id, title, listOf(ArtistRef(artist, "UC$id")), durationSec = sec, thumbnail = "https://i/$id.jpg")

    private fun matchOf(vararg songs: Pair<String, Song>): suspend (TrackToMatch) -> MatchResult = { t ->
        songs.firstOrNull { it.first == t.title }?.second?.let { MatchResult(MatchStatus.SURE, it, listOf(it)) } ?: MatchResult(MatchStatus.NONE, null, emptyList())
    }

    // ---- Recent plays

    @Test fun recentPlaysAreTheirOwnSourceAndNotCountedTwice() = runBlocking {
        // A data download already has Blinding Lights, ended at 20:44:04.
        val s = importer.session(ImportSource.SPOTIFY)
        s.add(ParsedPlay(at("2026-10-30T20:44:04Z"), 200_040, "Blinding Lights", "The Weeknd", "After Hours", fullPlay = true))
        s.finish()
        net.routes["https://api.spotify.com/v1/me/player/recently-played"] = {
            200 to SpotifyFixtures.recentlyPlayed(blinding to "2026-10-30T20:44:30.000Z", creep to "2026-10-31T09:00:00Z")
        }
        val recent = SpotifyRecent(api, db, importer, store)
        assertEquals("only Creep is new", 1, recent.sync())
        assertEquals(1, db.spotify().livePlays())
        val summaries = db.imports().summaries().associateBy { it.origin }
        assertEquals(1, summaries.getValue("spotify_live").plays)
        assertEquals(1, summaries.getValue("spotify").plays)
        // Spotify's length and ISRC go to matching.
        val m = db.imports().match(ImportKeys.songKey("Creep", "Radiohead"))!!
        assertEquals("GBAYE9200070", m.isrc)
        assertEquals(238_640L, m.durationMs)
        assertEquals(MatchStatus.PENDING.name, m.status)
        // The same list again adds nothing.
        assertEquals(0, recent.sync())
        // A download that brings Creep later takes the live one out (its time is the play's end).
        val later = importer.session(ImportSource.SPOTIFY)
        later.add(ParsedPlay(at("2026-10-31T09:03:58Z"), 238_000, "Creep", "Radiohead", fullPlay = true))
        later.finish()
        assertEquals(1, db.spotify().dropLiveTwins(SpotifyRecent.TWIN_MS))
        assertEquals(0, db.spotify().livePlays())
    }

    @Test fun twinsAreFoundAroundThePlaysStartOrEnd() = runBlocking {
        val p = SpotifyParse.recentlyPlayed(SpotifyFixtures.recentlyPlayed(blinding to "2026-10-30T20:40:00Z"))
        val start = p[0].playedAt
        suspend fun twins(times: List<Long>) = SpotifyRecent.withoutTwins(p) { _, _, from, to -> times.filter { it in from..to } }.isEmpty()
        assertTrue("at the same time", twins(listOf(start)))
        assertTrue("at its end", twins(listOf(start + 200_040)))
        assertTrue("a minute after its end", twins(listOf(start + 260_000)))
        assertFalse("an hour later", twins(listOf(start + 3_600_000)))
        val parsed = SpotifyRecent.toParsed(p[0])
        assertTrue("its length is only Spotify's", parsed.estimated)
        assertEquals(200_040L, parsed.playedMs)
        assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", parsed.sourceId)
    }

    @Test fun aDownloadThatNamesTheAlbumsArtistIsStillATwin() = runBlocking {
        // The extended history names a compilation's song by the album's artist: another name, the same Spotify URI.
        val s = importer.session(ImportSource.SPOTIFY)
        s.add(ParsedPlay(at("2026-10-30T20:44:04Z"), 200_040, "Blinding Lights", "Various Artists", sourceId = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b", fullPlay = true))
        s.finish()
        net.routes["https://api.spotify.com/v1/me/player/recently-played"] = {
            200 to SpotifyFixtures.recentlyPlayed(blinding to "2026-10-30T20:44:30.000Z", creep to "2026-10-31T09:00:00Z")
        }
        assertEquals("only Creep is new", 1, SpotifyRecent(api, db, importer, store).sync())
        // And a download brought later takes the live one out by its URI too.
        importer.session(ImportSource.SPOTIFY).apply { add(ParsedPlay(at("2026-10-31T09:03:58Z"), 238_000, "Creep", "Various Artists", sourceId = "spotify:track:70LcF31zb1H0PyJoS1Sx1r", fullPlay = true)) }.finish()
        assertEquals(1, db.spotify().dropLiveTwins(SpotifyRecent.TWIN_MS))
        assertEquals(0, db.spotify().livePlays())
    }

    // ---- Top songs and artists

    @Test fun topItemsShapeTasteOnceMatched() = runBlocking {
        net.routes["https://api.spotify.com/v1/me/top/tracks"] = { 200 to SpotifyFixtures.page(listOf(blinding, creep)) }
        net.routes["https://api.spotify.com/v1/me/top/artists"] = { 200 to SpotifyFixtures.topArtists }
        val top = SpotifyTop(api, db, dir)
        assertTrue(top.due())
        top.refresh(now = 5_000)
        assertFalse(top.due(now = 5_000 + SpotifyTop.WEEK_MS - 1))
        assertEquals(6, net.requests.size)
        var on = true
        val boost = top.boost { on }
        assertTrue("nothing matched yet", boost.songs().isEmpty())
        val song = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201)
        importer.applyMatch(db.imports().match(ImportKeys.songKey("Blinding Lights", "The Weeknd"))!!, MatchResult(MatchStatus.SURE, song, listOf(song)))
        val songs = boost.songs()
        assertEquals(listOf("fHI8X4OXluQ"), songs.map { it.songId })
        // First in all three ranges: 12 + 8 + 4.
        assertEquals(24, songs[0].plays)
        assertEquals(listOf("The Weeknd", "Radiohead"), boost.artists().map { it.artistName })
        // Merged into plays: a song played here and in the top climbs; one only in the top joins.
        val played = listOf(SongPlays("p1", 30, 30 * 200_000L, "Starboy", "The Weeknd", null, null, 230, null, "The Weeknd"))
        val merged = TasteBoost.mergeSongs(played + SongPlays("fHI8X4OXluQ", 1, 200_000, "Blinding Lights", "The Weeknd", null, null, 201, null, "The Weeknd"), songs, 10)
        assertEquals(listOf("p1", "fHI8X4OXluQ"), merged.map { it.songId })
        assertEquals(25, merged[1].plays)
        val artists = TasteBoost.mergeArtists(listOf(ArtistPlays("the weeknd", 2, 400_000, null, "UC1")), boost.artists(), 5)
        assertEquals("one artist, by name", 2, artists.size)
        assertEquals("UC1", artists.first { it.artistName.equals("the weeknd", true) }.artistId)
        // Off: nothing.
        on = false
        assertTrue(boost.songs().isEmpty() && boost.artists().isEmpty())
        assertEquals(0, boost.version())
    }

    // ---- Playlists

    private class FakeHands : SpotifyPlaylists.Hands {
        val lists = linkedMapOf<String, Pair<String, List<Song>>>()
        var n = 0
        override fun create(name: String, songs: List<Song>, description: String): String = "prism:${++n}".also { lists[it] = name to songs }
        override fun replace(id: String, songs: List<Song>) { lists[id] = lists.getValue(id).first to songs }
        override fun rename(id: String, name: String, description: String) { lists[id] = name to lists.getValue(id).second }
        override fun exists(id: String) = id in lists
        override fun songIds(id: String) = lists[id]?.second?.map { it.id }.orEmpty()
        override fun delete(id: String) { lists.remove(id) }
    }

    @Test fun yourPlaylistsBecomePrismPlaylistsThroughTheirMatches() = runBlocking {
        net.routes["https://api.spotify.com/v1/me/playlists"] = {
            200 to SpotifyFixtures.page(listOf(
                SpotifyFixtures.playlist("p1", "Road trip", "samr", 3), SpotifyFixtures.playlist("p2", "Today's Top Hits", "spotify", 50),
                SpotifyFixtures.playlist("p3", "Shared", "friend", 1, collaborative = true),
            ))
        }
        net.routes["https://api.spotify.com/v1/playlists/p1/items"] = {
            200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(blinding), SpotifyFixtures.playlistItem(creep), SpotifyFixtures.episodeItem))
        }
        net.routes["https://api.spotify.com/v1/playlists/p3/items"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(antiHero))) }
        val hands = FakeHands()
        val song = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201)
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf("Blinding Lights" to song), pause = {})
        playlists.refreshAll("samr", now = 1)
        val mirror = playlists.mirror()
        assertEquals("yours and collaborative ones", listOf("p1", "p3"), mirror.lists.map { it.playlist.id })
        assertEquals("followed ones can't be read", 1, mirror.notYours)
        assertEquals(0, net.count("https://api.spotify.com/v1/playlists/p2"))
        assertEquals(2, mirror.lists[0].tracks.size)
        val prismId = mirror.lists[0].prismId!!
        assertTrue(playlists.isMirror(prismId))
        assertEquals("Road trip", hands.lists.getValue(prismId).first)
        assertTrue("nothing matched yet", hands.lists.getValue(prismId).second.isEmpty())
        assertEquals("the ISRC goes to matching", "USUG11904206", db.imports().match(ImportKeys.songKey("Blinding Lights", "The Weeknd"))!!.isrc)

        // Opened: its songs matched now, the playlist filled in Spotify's order.
        playlists.refreshOne(prismId, readAgain = false)
        assertEquals(listOf("fHI8X4OXluQ"), hands.songIds(prismId))
        assertEquals("remembered", MatchStatus.SURE.name, db.imports().match(ImportKeys.songKey("Blinding Lights", "The Weeknd"))!!.status)
        assertEquals(MatchStatus.NONE.name, db.imports().match(ImportKeys.songKey("Creep", "Radiohead"))!!.status)

        // The same snapshot isn't read again.
        val itemsAsked = net.count("https://api.spotify.com/v1/playlists/p1/items")
        playlists.refreshAll("samr", now = 2)
        assertEquals(itemsAsked, net.count("https://api.spotify.com/v1/playlists/p1/items"))
        assertEquals(listOf("fHI8X4OXluQ"), hands.songIds(prismId))

        // Deleted in Prism: not made again. Gone from Spotify: gone from Library.
        hands.delete(prismId)
        playlists.refreshAll("samr", now = 3)
        assertFalse(hands.exists(prismId))
        assertFalse(playlists.isMirror(prismId))
        val shared = playlists.mirror().lists.first { it.playlist.id == "p3" }.prismId!!
        net.routes["https://api.spotify.com/v1/me/playlists"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlist("p1", "Road trip", "samr", 3))) }
        playlists.refreshAll("samr", now = 4)
        assertFalse(hands.exists(shared))

        // Forgotten (Disconnect): out of Library unless kept.
        playlists.forget(keep = false)
        assertTrue(playlists.mirror().lists.isEmpty())
        assertFalse(File(dir, "playlists.json").exists())
    }

    @Test fun removingAnImportKeepsTheMatchesTheConnectionNeeds() = runBlocking {
        net.routes["https://api.spotify.com/v1/me/playlists"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlist("p1", "Road trip", "samr", 2))) }
        net.routes["https://api.spotify.com/v1/playlists/p1/items"] = {
            200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(blinding), SpotifyFixtures.playlistItem(creep)))
        }
        val hands = FakeHands()
        val song = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201)
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf("Blinding Lights" to song), pause = {})
        playlists.refreshAll("samr", now = 1)
        val prismId = playlists.mirror().lists.single().prismId!!
        playlists.refreshOne(prismId, readAgain = false)
        assertEquals(listOf("fHI8X4OXluQ"), hands.songIds(prismId))
        // A data download had Blinding Lights and Africa; the listener removes it.
        importer.session(ImportSource.SPOTIFY).apply {
            add(ParsedPlay(at("2026-10-30T20:44:04Z"), 200_040, "Blinding Lights", "The Weeknd", fullPlay = true))
            add(ParsedPlay(at("2026-10-30T21:00:00Z"), 295_000, "Africa", "TOTO", fullPlay = true))
        }.finish()
        importer.remove(ImportSource.SPOTIFY, keep = playlists.matchKeys())
        assertEquals("the playlist's songs keep their matches", MatchStatus.SURE.name, db.imports().match(ImportKeys.songKey("Blinding Lights", "The Weeknd"))?.status)
        assertTrue(db.imports().match(ImportKeys.songKey("Creep", "Radiohead")) != null)
        assertEquals("the download's own song goes", null, db.imports().match(ImportKeys.songKey("Africa", "TOTO")))
        // So the playlist isn't emptied at its next run.
        playlists.reapply()
        assertEquals(listOf("fHI8X4OXluQ"), hands.songIds(prismId))
    }

    @Test fun aPlaylistSpotifyWontLetTheAppReadIsLeftOut() = runBlocking {
        net.routes["https://api.spotify.com/v1/me/playlists"] = {
            200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlist("p1", "Road trip", "samr", 1), SpotifyFixtures.playlist("p3", "Shared", "friend", 1, collaborative = true)))
        }
        net.routes["https://api.spotify.com/v1/playlists/p1/items"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(blinding))) }
        net.routes["https://api.spotify.com/v1/playlists/p3/items"] = { 403 to """{"error":{"status":403,"message":"Forbidden"}}""" }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf(), pause = {})
        playlists.refreshAll("samr", now = 1)
        assertEquals(listOf("p1"), playlists.mirror().lists.map { it.playlist.id })
        assertEquals(1, playlists.mirror().notYours)
        assertEquals(1, hands.lists.size)
        assertEquals("named in Library with the followed ones", listOf("p3"), playlists.followed.value.map { it.id })
    }

    @Test fun followedPlaylistsAreListedNeverMirroredAndForgotten() = runBlocking {
        val body = SpotifyFixtures.page(listOf(
            SpotifyFixtures.playlist("p1", "Self Care", "samr", 1),
            SpotifyFixtures.playlist("lofi", "lofi beats", "lofigirl", 400, ownerName = "Lofi Girl"),
            SpotifyFixtures.playlist("emo", "Emo Forever", "spotify", 100, ownerName = "Spotify"),
            SpotifyFixtures.playlist("p4", "Shared", "friend", 1, collaborative = true),
        ))
        // Parsed: the owner's name comes along; split into what this app can read and the rest.
        val parsed = SpotifyParse.playlists(body).items
        assertEquals(listOf("samr", "Lofi Girl", "Spotify", "friend"), parsed.map { it.ownerName })
        val (yours, others) = SpotifyPlaylists.split(parsed, "samr")
        assertEquals(listOf("p1", "p4"), yours.map { it.id })
        assertEquals(listOf("lofi", "emo"), others.map { it.id })
        assertEquals("https://open.spotify.com/playlist/lofi", SpotifyPlaylists.openLink("lofi"))

        net.routes["https://api.spotify.com/v1/me/playlists"] = { 200 to body }
        net.routes["https://api.spotify.com/v1/playlists/p1/items"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(blinding))) }
        net.routes["https://api.spotify.com/v1/playlists/p4/items"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(creep))) }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf(), pause = {})
        playlists.refreshAll("samr", now = 1)
        assertEquals(listOf("lofi", "emo"), playlists.followed.value.map { it.id })
        assertEquals(listOf("Lofi Girl", "Spotify"), playlists.followed.value.map { it.ownerName })
        assertEquals("their songs are never asked for", 0, net.count("https://api.spotify.com/v1/playlists/lofi") + net.count("https://api.spotify.com/v1/playlists/emo"))
        assertEquals("no mirror entry for them", listOf("p1", "p4"), playlists.mirror().lists.map { it.playlist.id })
        assertEquals("no Prism playlist for them", listOf("Self Care", "Shared"), hands.lists.values.map { it.first })
        assertEquals(2, playlists.mirror().notYours)
        assertTrue(File(dir, "followed.json").exists())
        // Read back from the file by a new instance (the app's next start).
        assertEquals(listOf("lofi", "emo"), SpotifyPlaylists(api, db, importer, dir, FakeHands(), matchOf(), pause = {}).followed.value.map { it.id })

        // A copy made in Spotify (yours now) is mirrored at the next refresh; the followed one stays listed.
        net.routes["https://api.spotify.com/v1/me/playlists"] = {
            200 to SpotifyFixtures.page(listOf(
                SpotifyFixtures.playlist("p1", "Self Care", "samr", 1), SpotifyFixtures.playlist("lofi", "lofi beats", "lofigirl", 400, ownerName = "Lofi Girl"),
                SpotifyFixtures.playlist("copy", "lofi beats", "samr", 1),
            ))
        }
        net.routes["https://api.spotify.com/v1/playlists/copy/items"] = { 200 to SpotifyFixtures.page(listOf(SpotifyFixtures.playlistItem(antiHero))) }
        playlists.refreshAll("samr", now = 2)
        assertEquals(listOf("p1", "copy"), playlists.mirror().lists.map { it.playlist.id })
        assertEquals(listOf("lofi"), playlists.followed.value.map { it.id })

        // Disconnect (or the switch off): gone.
        playlists.forget(keep = false)
        assertTrue(playlists.followed.value.isEmpty())
        assertFalse(File(dir, "followed.json").exists())
    }

    // ---- Liked Songs both ways

    private fun saved(t: String, iso: String) = SavedTrack(SpotifyParse.track(kotlinx.serialization.json.Json.parseToJsonElement(t))!!, at(iso))

    @Test fun theDryRunCountsSongsNotLikes() {
        val spotify = listOf(saved(blinding, "2025-01-01T00:00:00Z"), saved(antiHero, "2025-01-02T00:00:00Z"))
        val prism = listOf(
            PrismLike("y1", "Blinding Lights", "The Weeknd", 201, 1), PrismLike("y2", "Creep", "Radiohead", 239, 2),
            PrismLike("y3", "Starboy", "The Weeknd", 230, 3),
        )
        assertEquals(LikesPlan.Counts(1, 2), LikesPlan.dryRun(spotify, prism, kept = emptySet()))
        // A like brought from a data download stays out of it.
        assertEquals(LikesPlan.Counts(1, 1), LikesPlan.dryRun(spotify, prism, kept = setOf("y3")))
    }

    @Test fun likesGoAcrossOnceAndNeverBack() {
        var state = LikesState()
        val s1 = listOf(saved(blinding, "2025-01-01T00:00:00Z"))
        val p1 = listOf(PrismLike("y2", "Creep", "Radiohead", 239, 100))
        val plan1 = LikesPlan.plan(s1, p1, state, emptySet())
        assertEquals(listOf("Blinding Lights"), plan1.toLikeHere.map { it.title })
        assertEquals(listOf("y2"), plan1.toLikeOnSpotify.map { it.id })
        // Run 1 liked Blinding Lights here (y1) and Creep on Spotify.
        val creepUri = "spotify:track:70LcF31zb1H0PyJoS1Sx1r"
        state = LikesPlan.after(state, s1, p1, likedHere = listOf("y1"), likedOnSpotify = listOf(creepUri), unprocessed = emptySet(), now = 1)
        // Run 2 sees both sides with the other's likes: nothing to do.
        val s2 = s1 + saved(creep, "2025-01-05T00:00:00Z")
        val p2 = p1 + PrismLike("y1", "Blinding Lights", "The Weeknd", 201, 200)
        val plan2 = LikesPlan.plan(s2, p2, state, emptySet())
        assertTrue(plan2.toLikeHere.isEmpty() && plan2.toLikeOnSpotify.isEmpty())
        state = LikesPlan.after(state, s2, p2, emptyList(), emptyList(), emptySet(), now = 2)
        // The listener unlikes Blinding Lights here: it isn't liked again from Spotify, nor taken away there.
        val p3 = p1
        val plan3 = LikesPlan.plan(s2, p3, state, emptySet())
        assertTrue(plan3.toLikeHere.isEmpty() && plan3.toLikeOnSpotify.isEmpty())
        state = LikesPlan.after(state, s2, p3, emptyList(), emptyList(), emptySet(), now = 3)
        // Liked again on Spotify later (a newer time): that's a new like.
        val s4 = listOf(saved(blinding, "2026-03-01T00:00:00Z"), saved(creep, "2025-01-05T00:00:00Z"))
        assertEquals(listOf("Blinding Lights"), LikesPlan.plan(s4, p3, state, emptySet()).toLikeHere.map { it.title })
    }

    @Test fun likesNotLookedUpYetWaitForTheNextRun() {
        val p = listOf(PrismLike("y2", "Creep", "Radiohead", 239, 100), PrismLike("y3", "Starboy", "The Weeknd", 230, 101))
        val state = LikesPlan.after(LikesState(), emptyList(), p, emptyList(), emptyList(), unprocessed = setOf("y3"), now = 1)
        assertEquals(listOf("y3"), LikesPlan.plan(emptyList(), p, state, emptySet()).toLikeOnSpotify.map { it.id })
    }

    @Test fun bothWaysOverTheDatabase() = runBlocking {
        // Liked here: Creep. Liked on Spotify: Blinding Lights and Anti-Hero.
        val creepHere = ytSong("ytCreep0001", "Creep", "Radiohead", 239)
        db.songs().upsert(creepHere.toEntity().copy(liked = true, likedAt = 100))
        net.routes["https://api.spotify.com/v1/me/tracks"] = {
            200 to SpotifyFixtures.page(listOf(SpotifyFixtures.saved(blinding, "2025-01-01T00:00:00Z"), SpotifyFixtures.saved(antiHero, "2025-01-02T00:00:00Z")))
        }
        net.routes["https://api.spotify.com/v1/search"] = { 200 to SpotifyFixtures.search(creep) }
        net.routes["https://api.spotify.com/v1/me/library"] = { 200 to "" }
        val likedHere = mutableListOf<Song>()
        val bl = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 200)
        val ah = ytSong("b1kbLwvqugk", "Anti-Hero", "Taylor Swift", 201)
        val likes = SpotifyLikes(api, db, importer, dir, { songs ->
            likedHere += songs
            songs.forEach { db.songs().upsert(it.toEntity(db.songs().get(it.id)).copy(liked = true, likedAt = 500)) }
        }, { emptySet() }, matchOf("Blinding Lights" to bl, "Anti-Hero" to ah), pause = {})
        assertEquals(LikesPlan.Counts(2, 1), likes.dryRun())
        assertEquals("a dry run changes nothing", 0, net.count("https://api.spotify.com/v1/me/library"))

        val s1 = likes.sync(now = 1)
        assertEquals(setOf("fHI8X4OXluQ", "b1kbLwvqugk"), likedHere.map { it.id }.toSet())
        val put = net.requests.last { it.url.encodedPath == "/v1/me/library" }
        assertEquals("PUT", put.method)
        assertEquals("spotify:track:70LcF31zb1H0PyJoS1Sx1r", put.url.queryParameter("uris"))
        assertEquals(2, s1.likedHere); assertEquals(1, s1.likedOnSpotify)

        // Spotify now has Creep too; the second run does nothing either way.
        net.routes["https://api.spotify.com/v1/me/tracks"] = {
            200 to SpotifyFixtures.page(listOf(
                SpotifyFixtures.saved(blinding, "2025-01-01T00:00:00Z"), SpotifyFixtures.saved(antiHero, "2025-01-02T00:00:00Z"),
                SpotifyFixtures.saved(creep, "2026-11-02T10:00:00Z"),
            ))
        }
        likedHere.clear()
        val puts = net.count("https://api.spotify.com/v1/me/library")
        val searches = net.count("https://api.spotify.com/v1/search")
        likes.sync(now = 2)
        assertTrue(likedHere.isEmpty())
        assertEquals(puts, net.count("https://api.spotify.com/v1/me/library"))
        assertEquals(searches, net.count("https://api.spotify.com/v1/search"))

        // Unliked here: not liked again.
        db.songs().upsert(db.songs().get("fHI8X4OXluQ")!!.copy(liked = false))
        likes.sync(now = 3)
        assertTrue(likedHere.isEmpty())
        likes.forget()
        assertFalse(File(dir, "likes.json").exists())
    }
}

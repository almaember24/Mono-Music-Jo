package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.player.BACKDROP_SHOW_MIN
import com.prism.music.ui.player.backdropBlackout
import com.prism.music.ui.player.backdropShowFromSlider
import com.prism.music.ui.player.backdropShowOnSlider
import com.prism.music.ui.screens.previewVeil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import com.prism.music.playback.toMediaItem
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.1's library, player-look and settings requests from GitHub and Reddit: each one's logic, and that every new
 * choice is off (today's way) until it's picked.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class Requests181Test {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    private val eps = 1e-4f

    // ------------------------------------------------ #310 album names in Downloads

    @Test fun aSongRowCanLeaveTheAlbumOut() {
        val song = com.prism.music.data.model.Song(
            "s1", "Northern Lights", listOf(com.prism.music.data.model.ArtistRef("Ava Lune")), album = com.prism.music.data.model.AlbumRef("Polar"),
        )
        var showAlbum by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            val settings = AppSettings()
            androidx.compose.runtime.CompositionLocalProvider(com.prism.music.ui.theme.LocalAppSettings provides settings) {
                com.prism.music.ui.theme.PrismTheme(settings, artworkColor = null) {
                    com.prism.music.ui.components.SongRowLayout(song, showAlbum = showAlbum)
                }
            }
        }
        compose.onNodeWithText("Ava Lune · Polar").assertExists()
        showAlbum = false
        compose.onNodeWithText("Ava Lune").assertExists()
        compose.onNodeWithText("Ava Lune · Polar").assertDoesNotExist()
    }

    private fun repo() = com.prism.music.data.prefs.SettingsRepository(
        org.robolectric.RuntimeEnvironment.getApplication(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    )

    // ------------------------------------------------ #309 Show background at 0% = fully dark

    @Test fun showBackgroundsStartIsFullyDark() {
        // The slider's start is solid black, and its end as bright as before.
        assertEquals(BACKDROP_SHOW_MIN, backdropShowFromSlider(0f), eps)
        assertEquals(1f, backdropBlackout(backdropShowFromSlider(0f)), eps)
        assertEquals(1f, backdropShowFromSlider(1f), eps)
        assertEquals(1f, previewVeil(BACKDROP_SHOW_MIN), eps)
    }

    @Test fun showBackgroundKeepsEveryOldValueAsItWas() {
        // Values from before (0..1) draw no veil and come back from the slider unchanged.
        for (old in listOf(0f, 0.2f, AppSettings().playerBackdropShow, 0.7f, 1f)) {
            assertEquals(0f, backdropBlackout(old), eps)
            assertEquals(old, backdropShowFromSlider(backdropShowOnSlider(old)), eps)
            assertEquals(1f - 0.55f * old, previewVeil(old), eps)
        }
        // Halfway into the new stretch, half dark.
        assertEquals(0.5f, backdropBlackout(BACKDROP_SHOW_MIN / 2), eps)
    }

    @Test fun showBackgroundIsReadWithinItsRange(): Unit = kotlinx.coroutines.runBlocking {
        val repo = repo()
        repo.setPlayerBackdropShow(-3f)
        val s = kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.playerBackdropShow < 0f } }
        assertEquals(BACKDROP_SHOW_MIN, s.playerBackdropShow, eps)
        repo.setPlayerBackdropShow(AppSettings().playerBackdropShow)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.playerBackdropShow > 0f } }
        assertTrue(true)
    }

    // ------------------------------------------------ Every new choice keeps today's way until picked

    @Test fun theNewChoicesAreOffUntilPicked() {
        val d = AppSettings()
        assertTrue("#310 album names stay", d.view.downloadsAlbums)
        assertTrue("#289 one tap still asks", !d.view.artistLinks)
        assertTrue("#283 the background still cuts", !d.view.backdropFade)
    }

    @Test fun theNewChoicesAreKeptAndRead(): Unit = kotlinx.coroutines.runBlocking {
        val repo = repo()
        repo.setDownloadsAlbums(false); repo.setArtistLinks(true); repo.setBackdropFade(true)
        val s = kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { !it.view.downloadsAlbums && it.view.artistLinks && it.view.backdropFade } }
        assertTrue(s.view.artistLinks)
        repo.setDownloadsAlbums(true); repo.setArtistLinks(false); repo.setBackdropFade(false)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.view.downloadsAlbums && !it.view.artistLinks && !it.view.backdropFade } }
    }

    // ------------------------------------------------ #283 the Now playing background's fade

    @Test fun nowPlayingFadesOnlyWhenAskedAtTheColorsPace() {
        val off = AppSettings()
        assertEquals(null, com.prism.music.ui.theme.nowPlayingFadeMs(off))
        val on = off.copy(view = off.view.copy(backdropFade = true))
        assertEquals(on.artworkChange.ms.takeIf { it > 0 } ?: com.prism.music.data.prefs.ArtworkChange.FADE.ms, com.prism.music.ui.theme.nowPlayingFadeMs(on))
        assertEquals(2400, com.prism.music.ui.theme.nowPlayingFadeMs(on.copy(artworkChange = com.prism.music.data.prefs.ArtworkChange.SLOW)))
        // Colors that change at once still get a fade (the switch asks for one); Reduce motion has none.
        assertEquals(900, com.prism.music.ui.theme.nowPlayingFadeMs(on.copy(artworkChange = com.prism.music.data.prefs.ArtworkChange.INSTANT)))
        assertEquals(null, com.prism.music.ui.theme.nowPlayingFadeMs(on.copy(reduceMotion = true)))
    }

    // ------------------------------------------------ #289 each artist its own link

    private fun ref(name: String, id: String? = "UC$name") = com.prism.music.data.model.ArtistRef(name, id)

    @Test fun eachArtistsNameIsFoundInTheLine() {
        val a = listOf(ref("Daft Punk"), ref("Pharrell Williams"), ref("Nile Rodgers"))
        val text = "Daft Punk, Pharrell Williams, Nile Rodgers"
        val spans = com.prism.music.ui.components.artistSpans(text, a)!!
        assertEquals(listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"), spans.map { (r, _) -> text.substring(r) })
        assertEquals(a, spans.map { it.second })
        // A name inside another's ("Kid" in "Kid Cudi") is found where it stands, after the one before it.
        val b = listOf(ref("Kid Cudi"), ref("Kid"))
        val t2 = "Kid Cudi, Kid"
        assertEquals(listOf("Kid Cudi", "Kid"), com.prism.music.ui.components.artistSpans(t2, b)!!.map { (r, _) -> t2.substring(r) })
    }

    @Test fun oneArtistOrAMissingNameKeepsTheMenu() {
        assertEquals(null, com.prism.music.ui.components.artistSpans("Adele", listOf(ref("Adele"))))
        assertEquals(null, com.prism.music.ui.components.artistSpans("Someone & Else", listOf(ref("Someone"), ref("Nobody"))))
    }

    // ------------------------------------------------ #246 Add to playlist says where it is already

    @Test fun playlistsThatHoldTheSongAreCounted() {
        com.prism.music.data.PlaylistMembership.forget()
        val contents = mapOf("VLPL1" to listOf("a", "b"), "prism:x" to listOf("c"), "VLPL2" to emptyList())
        assertEquals(mapOf("VLPL1" to 1), com.prism.music.data.PlaylistMembership.counts(listOf("a"), contents))
        assertEquals(mapOf("VLPL1" to 2, "prism:x" to 1), com.prism.music.data.PlaylistMembership.counts(listOf("a", "b", "c"), contents))
        // Added from this phone since: known at once, however the playlist's id is written.
        com.prism.music.data.PlaylistMembership.noteAdded("PL2", listOf("a"))
        assertEquals(mapOf("VLPL1" to 1, "VLPL2" to 1), com.prism.music.data.PlaylistMembership.counts(listOf("a"), contents))
        com.prism.music.data.PlaylistMembership.forget()
    }

    @Test fun aPlaylistsSongsComeFromWhatsOnThePhone() {
        val lists = listOf(
            com.prism.music.data.model.PlaylistItem("prism:x", "Mine", "", null),
            com.prism.music.data.model.PlaylistItem("VLPL1", "Kept", "", null),
            com.prism.music.data.model.PlaylistItem("VLPL9", "Never opened", "", null),
        )
        val asked = mutableListOf<String>()
        val known = com.prism.music.data.PlaylistMembership.known(lists, mapOf("prism:x" to listOf("c"))) { id ->
            asked += id
            if (id == "VLPL1") listOf("a") else null
        }
        assertEquals(listOf("c"), known["prism:x"])
        assertEquals(listOf("a"), known["VLPL1"])
        assertEquals(emptyList<String>(), known["VLPL9"])
        // Prism playlists are never looked up on disk.
        assertTrue("prism:x" !in asked)
    }

    // ------------------------------------------------ #216 explicit songs

    private fun song(id: String, explicit: Boolean = false) = com.prism.music.data.model.Song(id, "Song $id", explicit = explicit)
    private val SHOW = com.prism.music.data.prefs.ExplicitContent.SHOW
    private val SKIP = com.prism.music.data.prefs.ExplicitContent.SKIP
    private val HIDE = com.prism.music.data.prefs.ExplicitContent.HIDE

    @Test fun explicitSongsPlayAsBeforeUnlessTheFilterIsOn() {
        assertEquals(SHOW, AppSettings().behavior.explicitContent)
        val e = song("e", explicit = true)
        val f = com.prism.music.data.ExplicitFilter
        assertTrue(f.plays(SHOW, e) && f.shows(SHOW, e))
        // Skip: listed, never played; Hide: neither.
        assertTrue(!f.plays(SKIP, e) && f.shows(SKIP, e))
        assertTrue(!f.plays(HIDE, e) && !f.shows(HIDE, e))
        // A clean song is never touched.
        assertTrue(f.plays(HIDE, song("c")) && f.shows(HIDE, song("c")))
    }

    @Test fun aQueueStartsFromTheNextSongThatMayPlay() {
        val f = com.prism.music.data.ExplicitFilter
        val list = listOf(song("a"), song("b", true), song("c"), song("d", true))
        // Nothing changes with the filter off.
        assertEquals(list to 1, f.queue(SHOW, list, 1))
        // The tapped explicit song gives way to the next clean one; the rest keep their order.
        assertEquals(listOf("a", "c") to 1, f.queue(SKIP, list, 1)!!.let { (s, i) -> s.map { it.id } to i })
        // Tapped past the last clean song: from the first.
        assertEquals(0, f.queue(SKIP, list, 3)!!.second)
        assertEquals(null, f.queue(HIDE, listOf(song("x", true)), 0))
    }

    @Test fun hideLeavesExplicitSongsOutOfSearch() {
        val f = com.prism.music.data.ExplicitFilter
        val e = com.prism.music.data.model.SongItem(song("e", true))
        val c = com.prism.music.data.model.SongItem(song("c"))
        val album = com.prism.music.data.model.AlbumItem("MPREb_1", "An album", "Album", null, null)
        val top = com.prism.music.data.innertube.TopSearch(
            e, listOf(com.prism.music.data.innertube.SearchFilter.SONGS to listOf(e, c), com.prism.music.data.innertube.SearchFilter.ALBUMS to listOf(album)),
        )
        assertEquals(top, f.top(SKIP, top))
        val hidden = f.top(HIDE, top)
        assertEquals(c, hidden.top)
        assertEquals(listOf(listOf(c), listOf(album)), hidden.sections.map { it.second })
        assertEquals(listOf(c, album), f.items(HIDE, listOf(e, c, album)))
    }

    @Test fun theExplicitChoiceIsKept(): Unit = kotlinx.coroutines.runBlocking {
        val repo = repo()
        repo.setExplicitContent(HIDE)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.behavior.explicitContent == HIDE } }
        repo.setExplicitContent(SHOW)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.behavior.explicitContent == SHOW } }
    }

    // ------------------------------------------------ #305 a song's or an album's own cover

    @Test fun aPickedCoverShowsForTheSongAndItsAlbumsSongs() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("playlist_covers", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val covers = com.prism.music.data.PlaylistCovers(app)
        val a = com.prism.music.data.model.Song("a", "A", album = com.prism.music.data.model.AlbumRef("Polar", "MPREb_p"), thumbnail = "https://lh3.googleusercontent.com/a")
        val b = com.prism.music.data.model.Song("b", "B", album = com.prism.music.data.model.AlbumRef("Polar", "MPREb_p"), thumbnail = "https://lh3.googleusercontent.com/b")
        val other = com.prism.music.data.model.Song("c", "C", thumbnail = "https://i.ytimg.com/vi/c/hqdefault.jpg")
        // Nothing picked: every song as it is.
        assertEquals(a.thumbnail, covers.art(a))
        assertEquals(other.thumbnail, com.prism.music.data.PlaylistCovers.artOf(other))
        // An album's cover shows on its songs and its card.
        covers.useImage(covers.albumKey("MPREb_p"), "https://example.org/album.jpg")
        assertEquals("https://example.org/album.jpg", covers.art(a))
        assertEquals("https://example.org/album.jpg", covers.art(com.prism.music.data.model.SongItem(b)))
        assertEquals("https://example.org/album.jpg", covers.art(com.prism.music.data.model.AlbumItem("MPREb_p", "Polar", "Album", "https://lh3.googleusercontent.com/p", null)))
        // A song's own beats its album's, and is everywhere the song is drawn (the session, the rows).
        covers.useImage(covers.songKey("a"), "https://example.org/a.jpg")
        assertEquals("https://example.org/a.jpg", covers.art(a))
        assertEquals("https://example.org/a.jpg", com.prism.music.data.PlaylistCovers.artOf(a))
        assertEquals("https://example.org/album.jpg", covers.art(b))
        // "Use the original" puts each back.
        covers.reset(covers.songKey("a"))
        covers.reset(covers.albumKey("MPREb_p"))
        assertEquals(a.thumbnail, covers.art(a))
        assertEquals(b.thumbnail, covers.art(b))
        // Kept on the phone: a new store reads them back.
        covers.useImage(covers.songKey("c"), "https://example.org/c.jpg")
        assertEquals("https://example.org/c.jpg", com.prism.music.data.PlaylistCovers(app).art(other))
        covers.reset(covers.songKey("c"))
    }

    @Test fun pickedCoversGoInBackupsWithTheLibrary() {
        assertEquals(com.prism.music.data.BackupPart.LIBRARY, com.prism.music.data.Backup.prefsPart("playlist_covers"))
        assertEquals(com.prism.music.data.BackupPart.LIBRARY, com.prism.music.data.Backup.filePart("covers"))
    }

    @Test fun theSessionGetsAPickedPhotoThroughTheArtworkProvider() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val covers = com.prism.music.data.PlaylistCovers(app)
        val song = com.prism.music.data.model.Song("s", "S", thumbnail = "https://lh3.googleusercontent.com/s=w120-h120")
        assertEquals("https", song.toMediaItem().mediaMetadata.artworkUri?.scheme)
        covers.useImage(covers.songKey("s"), "file:///data/user/0/com.prism.music/files/covers/song_s-1.jpg")
        val uri = song.toMediaItem().mediaMetadata.artworkUri!!
        assertEquals("content", uri.scheme)
        assertTrue(uri.authority!!.endsWith(".artwork"))
        covers.reset(covers.songKey("s"))
    }

    @Test fun otherVersionsGiveTheirCovers() {
        val song = com.prism.music.data.model.Song("v", "Linger", thumbnail = "https://i.ytimg.com/vi/v/hqdefault.jpg", isVideo = true)
        val found = listOf(
            com.prism.music.data.model.Song("1", "Linger", thumbnail = "https://lh3.googleusercontent.com/one=w120-h120"),
            com.prism.music.data.model.Song("2", "Linger", thumbnail = "https://lh3.googleusercontent.com/one=w544-h544"),
            com.prism.music.data.model.Song("3", "Zombie", thumbnail = "https://lh3.googleusercontent.com/z"),
            com.prism.music.data.model.Song("4", "Linger (Live)", thumbnail = "https://lh3.googleusercontent.com/live", isVideo = true),
            com.prism.music.data.model.Song("v", "Linger", thumbnail = "https://i.ytimg.com/vi/v/hqdefault.jpg"),
        )
        // The same picture in two sizes once; another song's, a video's and the song's own aren't offered.
        assertEquals(listOf("https://lh3.googleusercontent.com/one=w120-h120"), com.prism.music.ui.components.otherCovers(song, found))
    }

    // ------------------------------------------------ #318 a font installed on the phone

    @Test fun anInstalledFontIsNamedAsItNamesItself() {
        // Prism's own Outfit names its family in its 'name' table.
        assertEquals("Outfit", com.prism.music.ui.theme.AppFonts.fontFamilyName(java.io.File("src/main/res/font/outfit.ttf")))
        // Not a font: no name, no crash.
        assertEquals(null, com.prism.music.ui.theme.AppFonts.fontFamilyName(java.io.File("src/main/res/values/strings.xml")))
        assertEquals("Noto Serif Display", com.prism.music.ui.theme.AppFonts.familyFromFileName("NotoSerifDisplay-Italic.ttf"))
        assertEquals("Roboto", com.prism.music.ui.theme.AppFonts.familyFromFileName("Roboto-Regular.ttf"))
        assertEquals("Source Sans 3", com.prism.music.ui.theme.AppFonts.familyFromFileName("SourceSans3-Bold.otf"))
    }

    @Test fun anInstalledFontIsCopiedInLikeAPickedFile() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.prism.music.ui.theme.AppFonts.init(app)
        // The list itself is the phone's (Robolectric's is whatever it is): it never throws.
        com.prism.music.ui.theme.AppFonts.installed()
        val font = com.prism.music.ui.theme.AppFonts.InstalledFont("Outfit", java.io.File("src/main/res/font/outfit.ttf"))
        assertEquals("Outfit", com.prism.music.ui.theme.AppFonts.importInstalled(app, font, com.prism.music.ui.theme.AppFonts.FontSlot.LYRICS))
        val copied = com.prism.music.ui.theme.AppFonts.customFile(com.prism.music.ui.theme.AppFonts.FontSlot.LYRICS)!!
        assertEquals(font.file.length(), copied.length())
        com.prism.music.ui.theme.AppFonts.remove(com.prism.music.ui.theme.AppFonts.FontSlot.LYRICS)
        // A file that isn't a font is turned away, and nothing is left behind.
        val bad = com.prism.music.ui.theme.AppFonts.InstalledFont("Bad", java.io.File("src/main/res/values/strings.xml"))
        assertTrue(runCatching { com.prism.music.ui.theme.AppFonts.importInstalled(app, bad, com.prism.music.ui.theme.AppFonts.FontSlot.LYRICS) }.isFailure)
        assertEquals(null, com.prism.music.ui.theme.AppFonts.customFile(com.prism.music.ui.theme.AppFonts.FontSlot.LYRICS))
    }

    // ------------------------------------------------ #311 new releases from favorite artists

    private fun release(id: String, artist: String, name: String = artist) = com.prism.music.data.db.ReleaseEntity(
        id = id, artistId = artist, artistName = name, title = "T$id", kind = "Single", year = 2026,
        releaseDate = null, thumbnail = null, playlistId = null, firstSeenAt = 0, baseline = false, notifiedAt = 0,
    )

    @Test fun favoritesOnlyKeepsTheStarredArtists() {
        val all = listOf(release("1", "a"), release("2", "b"), release("3", "a"))
        assertEquals(listOf("1", "3"), com.prism.music.data.foryou.ReleaseRadar.fromFavorites(all, setOf("a")).map { it.id })
        assertTrue(com.prism.music.data.foryou.ReleaseRadar.fromFavorites(all, emptySet()).isEmpty())
    }

    @Test fun theArtistsToPickLeadWithTheMostPlayed() {
        val all = listOf(release("1", "z", "Zed"), release("2", "b", "Bea"), release("3", "a", "Ana"), release("4", "b", "Bea"))
        val picks = com.prism.music.data.foryou.ReleaseRadar.artistsToPick(all, mostPlayed = listOf("a"))
        assertEquals(listOf("a" to "Ana", "b" to "Bea", "z" to "Zed"), picks)
    }
}

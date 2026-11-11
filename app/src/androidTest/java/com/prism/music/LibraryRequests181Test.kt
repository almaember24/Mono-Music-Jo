package com.prism.music

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.ExplicitContent
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.1's library, player-look and settings requests from GitHub and Reddit, in the app: album names in Downloads
 * (#310), Add to playlist's marks (#246), the explicit filter (#216), a song's own cover (#305), the White player
 * (#320), Show background's dark end (#309), favorite artists' releases (#311), an installed font (#318) and the Now
 * playing background's fade (#283). Test songs and a silent tone on the phone stand in for real ones: no network.
 */
@RunWith(AndroidJUnit4::class)
class LibraryRequests181Test {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var tone: android.net.Uri? = null

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /** Opens one of Prism's own links (as a shortcut or a notification does). */
    private fun open(link: String) {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun openLibraryPlaylist(title: String, first: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        // Library keeps the page it was on (an earlier test leaves it on Downloads or History): its own "Library" page,
        // the topmost "Library" that can be tapped (the tab bar's is at the bottom), holds the playlists.
        // Library keeps the page it was on (an earlier test leaves it on Downloads): its first page, left of Songs, holds
        // the playlists. Swiped back there.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Songs")).fetchSemanticsNodes().isNotEmpty() }
        repeat(6) {
            if (compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.clickWhenFound(hasText(title) and hasClickAction(), 15_000)
        compose.waitForText(first)
    }

    /** Scrolls the page to [text] (settings pages scroll; their headings are in capitals). */
    private fun scrollTo(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }
        compose.waitForText(text)
    }

    /** #310: Downloads can show each song with just its artist. */
    @Test fun downloadsCanLeaveTheAlbumNamesOut() {
        Fixture.ready()
        val songs = Fixture.songs(2).map { it.copy(album = AlbumRef("Polar")) }
        Fixture.downloaded(songs)
        Fixture.launch()
        open("prism://downloads")
        compose.waitForText("Test Artist · Polar", 15_000)
        runBlocking { c.settings.setDownloadsAlbums(false) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test Artist · Polar")).fetchSemanticsNodes().isEmpty() }
        val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
        // (Once the songs' genre is known, it follows the artist: "Test Artist · Pop". The album stays out either way.)
        assertTrue(
            "no row with the artist and no album; on screen: ${shown.joinToString(" | ").take(1500)}",
            compose.onAllNodes(hasText("Test Artist", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Polar", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** #246: Add to playlist marks the playlist that has the song already, and not the one without it. */
    @Test fun addToPlaylistSaysWhereTheSongIsAlready() {
        Fixture.ready()
        val songs = Fixture.songs(2)
        Fixture.prismPlaylist("Road trip", songs)
        Fixture.prismPlaylist("Weekend", listOf(songs[1]))
        Fixture.launch()
        openLibraryPlaylist("Road trip", "Test song 1")
        compose.onAllNodes(hasContentDescription("More") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Add to playlist") and hasClickAction())
        compose.waitForText("Already has this song")
        val roadTrip = c.prismLibrary.lists.value.first { it.title == "Road trip" }.id
        val weekend = c.prismLibrary.lists.value.first { it.title == "Weekend" }.id
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("inPlaylist:$roadTrip"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no mark where it isn't", compose.onAllNodes(hasTestTag("inPlaylist:$weekend"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** #216: Hide leaves explicit songs off a playlist's list; Skip and Hide keep them out of the queue. */
    @Test fun explicitSongsAreLeftOutWhenAsked() {
        Fixture.ready()
        val songs = Fixture.songs(3).mapIndexed { i, s -> if (i == 0) s.copy(explicit = true) else s }
        Fixture.prismPlaylist("Clean", songs)
        runBlocking { c.settings.setExplicitContent(ExplicitContent.HIDE) }
        Fixture.launch()
        openLibraryPlaylist("Clean", "Test song 2")
        assertTrue("the explicit song isn't listed", compose.onAllNodes(hasText("Test song 1")).fetchSemanticsNodes().isEmpty())
        runBlocking { c.settings.setExplicitContent(ExplicitContent.SKIP) }
        compose.waitForText("Test song 1")
        // Tapped first: the queue starts from the next clean song and holds no explicit one.
        instrumentation.runOnMainSync { c.player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Explicit test")) }
        compose.waitUntil(10_000) { c.player.queue.value.isNotEmpty() }
        assertEquals(listOf(songs[1].id, songs[2].id), c.player.queue.value.map { it.song.id })
        runBlocking { c.settings.setExplicitContent(ExplicitContent.SHOW) }
    }

    /** #305: a song's own cover, used for the song playing (the notification's picture too), and back to the original. */
    @Test fun aSongsCoverCanBeChangedAndPutBack() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism cover tone", 60)
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism cover tone" }.song
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Cover test")) }
        compose.waitForText("Prism cover tone", 20_000)
        // A picture of its own (as the sheet keeps one), then the queue takes it up.
        val file = java.io.File(c.covers.dir.apply { mkdirs() }, "song_test-1.jpg")
        android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
            .let { b -> file.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }
        instrumentation.runOnMainSync {
            c.covers.useImage(c.covers.songKey(song.id), android.net.Uri.fromFile(file).toString())
            c.player.refreshCovers { it.id == song.id }
        }
        compose.waitUntil(10_000) {
            var uri: android.net.Uri? = null
            instrumentation.runOnMainSync { uri = c.player.player?.currentMediaItem?.mediaMetadata?.artworkUri }
            uri?.scheme == "content" && uri?.authority?.endsWith(".artwork") == true
        }
        // The song menu: Change cover → Use the original.
        instrumentation.runOnMainSync { c.player.setPlaying(false) }
        open("prism://player")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("playerArtBox")).fetchSemanticsNodes().isNotEmpty() }
        compose.clickWhenFound(hasContentDescription(tr(R.string.song_options)) and hasClickAction())
        scrollTo(tr(R.string.change_cover))
        compose.clickWhenFound(hasText(tr(R.string.change_cover)) and hasClickAction())
        compose.clickWhenFound(hasText(tr(R.string.use_the_original)) and hasClickAction())
        compose.waitUntil(10_000) { c.covers.custom(c.covers.songKey(song.id)) == null }
        assertNull(c.covers.custom(c.covers.songKey(song.id)))
    }

    /** #320: the White background turns the player's type dark, and the lyrics (their dark blurred cover) back to white. */
    @Test fun theWhitePlayerDrawsDarkTypeUntilTheLyricsOpen() {
        Fixture.ready()
        runBlocking { c.settings.setPlayerBackground(PlayerBackground.WHITE) }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism white tone", 60)
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism white tone" }.song
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "White test")) }
        compose.waitForText("Prism white tone", 20_000)
        instrumentation.runOnMainSync { c.player.setPlaying(false) }
        open("prism://player")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("playerArtBox")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { com.prism.music.ui.player.PlayerInk.light }
        Fixture.screenshot("white-player")
        compose.clickWhenFound(hasContentDescription(tr(R.string.lyrics)) and hasClickAction(), 15_000)
        compose.waitUntil(5_000) { !com.prism.music.ui.player.PlayerInk.light }
    }

    /** #309: Show background's start reads as a fully dark player. */
    @Test fun showBackgroundStartsFullyDark() {
        Fixture.ready()
        runBlocking {
            c.settings.setPlayerBackground(PlayerBackground.BLUR)
            c.settings.setPlayerBackdropShow(com.prism.music.ui.player.BACKDROP_SHOW_MIN)
        }
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Customize Prism") and hasClickAction())
        compose.clickWhenFound(hasText(tr(R.string.player)) and hasClickAction())
        scrollTo(tr(R.string.show_background))
        compose.waitForText("${tr(R.string.dark)} · 0%")
    }

    /** #311: "Favorite artists only" keeps New releases to the starred artists. */
    @Test fun newReleasesCanKeepToFavoriteArtists() {
        Fixture.ready()
        val now = System.currentTimeMillis()
        fun release(id: String, artist: String, name: String, title: String) = com.prism.music.data.db.ReleaseEntity(
            id = id, artistId = artist, artistName = name, title = title, kind = "Single", year = java.time.LocalDate.now().year,
            releaseDate = java.time.LocalDate.now().toString(), thumbnail = null, playlistId = null, firstSeenAt = now, baseline = false, notifiedAt = now,
        )
        runBlocking { c.db.releases().insertAll(listOf(release("MPREb_t1", "UCstar", "Starred Band", "Starry Single"), release("MPREb_t2", "UCother", "Other Band", "Other Single"))) }
        c.artistPrefs.setFavorite(com.prism.music.data.FavoriteArtist("UCstar", "Starred Band"), true)
        Fixture.launch()
        open("prism://releases")
        compose.waitForText("Other Single", 15_000)
        compose.clickWhenFound(hasText(tr(R.string.favorite_artists_only)) and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Other Single")).fetchSemanticsNodes().isEmpty() }
        assertTrue(compose.onAllNodes(hasText("Starry Single")).fetchSemanticsNodes().isNotEmpty())
    }

    /** #318: a font installed on the phone becomes the app's font, as a picked file does (Android 10 and later). */
    @Test fun anInstalledFontCanBeTheAppsFont() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Customize Prism") and hasClickAction())
        compose.clickWhenFound(hasText(tr(R.string.text)) and hasClickAction())
        scrollTo(tr(R.string.installed_fonts))
        compose.clickWhenFound(hasText(tr(R.string.installed_fonts)) and hasClickAction())
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("installedFont")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("installedFont")).onFirst().performClick()
        compose.waitUntil(10_000) { c.settings.current.fontChoice == com.prism.music.data.prefs.FontChoice.CUSTOM }
        assertNotNull(com.prism.music.ui.theme.AppFonts.customFile(com.prism.music.ui.theme.AppFonts.FontSlot.APP))
    }

    /** #283: with a screen on Now playing, Backgrounds offers its fade (off until turned on). */
    @Test fun theNowPlayingBackgroundOffersItsFade() {
        Fixture.ready()
        runBlocking { c.settings.setBackdrops(mapOf("HOME" to "ARTWORK")) }
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Customize Prism") and hasClickAction())
        compose.clickWhenFound(hasText(tr(R.string.glass_backgrounds)) and hasClickAction())
        compose.clickWhenFound(hasText(tr(R.string.screen_backgrounds)) and hasClickAction())
        scrollTo(tr(R.string.now_playing_background_fades))
        assertTrue("off until turned on", !c.settings.current.view.backdropFade)
        compose.clickWhenFound(hasTestTag("backdropFade"))
        compose.waitUntil(10_000) { c.settings.current.view.backdropFade }
    }
}

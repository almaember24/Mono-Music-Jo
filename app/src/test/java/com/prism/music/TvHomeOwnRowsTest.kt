package com.prism.music

import com.prism.music.data.db.ReleaseEntity
import com.prism.music.data.foryou.Mix
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.HomeSections
import com.prism.music.ui.tv.TV_PLAY
import com.prism.music.ui.tv.TvHomeRow
import com.prism.music.ui.tv.TvPlayList
import com.prism.music.ui.tv.TvItem
import com.prism.music.ui.tv.toTvItem
import com.prism.music.ui.tv.tvAllRows
import com.prism.music.ui.tv.tvGenreRow
import com.prism.music.ui.tv.tvMixRow
import com.prism.music.ui.tv.tvMoodRow
import com.prism.music.ui.tv.tvReleaseRow
import com.prism.music.ui.tv.tvReplayRow
import com.prism.music.ui.tv.tvSongRow
import com.prism.music.ui.tv.tvTilesRow
import com.prism.music.ui.tv.tvTopGenres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV Home's rows from your own music (1.8.0, the owner: "I do not see things like Prism Music mixes or Quick picks
 * etc. that match my phone app"): each built from the phone's own sources, keyed as the phone's sections, empty ones
 * left out, and in the phone's order before any layout.
 */
class TvHomeOwnRowsTest {
    private fun song(i: Int, art: String? = "https://lh3/s$i") = Song("s$i", "Song $i", listOf(ArtistRef("Artist $i", "a$i")), durationSec = 200, thumbnail = art)
    private fun mix(id: String, n: Int) = Mix(id, "Mix $id", "With Artist 1", listOf("Artist 1"), 0, 0L, (1..n).map(::song))

    @Test fun madeForYouHoldsPrismsMixesAndPlaysThem() {
        val row = tvMixRow("Made for you", listOf(mix("time", 3), mix("daily:1", 0), mix("daily:2", 2), mix("time", 3)))!!
        assertEquals(HomeSections.MADE_FOR_YOU, row.row.key)
        // A mix with no songs isn't a card; each mix once.
        assertEquals(listOf(TV_PLAY + "mix:time", TV_PLAY + "mix:daily:2"), row.row.items.map { it.id })
        val card = row.row.items.first() as PlaylistItem
        assertTrue(card.isMix)
        assertEquals("https://lh3/s1", card.thumbnail)
        assertEquals(TvItem.Kind.MIX, card.toTvItem().kind)
        // OK plays the mix's own songs.
        assertEquals(listOf("s1", "s2", "s3"), row.plays.getValue(card.id).songs.map { it.id })
        assertNull(tvMixRow("Made for you", listOf(mix("daily:1", 0))))
    }

    @Test fun songRowsAreEachSongOnceAndLeaveThemselvesOutWhenEmpty() {
        val row = tvSongRow(HomeSections.ON_REPEAT, "On repeat", listOf(song(1), song(2), song(1)) + (3..40).map(::song))!!
        assertEquals(HomeSections.ON_REPEAT, row.row.key)
        assertEquals(20, row.row.items.size)
        assertEquals(listOf("s1", "s2", "s3"), row.row.items.take(3).map { it.id })
        assertTrue(row.row.items.all { it is SongItem })
        // No history yet: no On repeat.
        assertNull(tvSongRow(HomeSections.ON_REPEAT, "On repeat", emptyList()))
    }

    @Test fun newReleasesAreAlbumCards() {
        val r = ReleaseEntity("MPREb_1", "a1", "Artist 1", "New One", "Single", 2026, "2026-10-30", "https://lh3/r", "OLAK1", 0L, false)
        val row = tvReleaseRow("New from your artists", listOf(r))!!
        assertEquals(HomeSections.NEW_RELEASES, row.row.key)
        val card = row.row.items.single() as AlbumItem
        assertEquals("MPREb_1", card.id)
        assertEquals("Single · Artist 1", card.subtitle)
        assertNull(tvReleaseRow("New from your artists", emptyList()))
    }

    @Test fun moodsAreTilesThatOpenTheirPage() {
        val row = tvMoodRow("Moods & genres", (1..15).map { MoodItem("FEmusic_moods_$it", "Mood $it", "p$it", 0xFF00FF00) })!!
        assertEquals(HomeSections.MOODS, row.row.key)
        assertEquals(12, row.row.items.size)
        assertTrue(row.row.items.all { it is MoodItem })
        assertNull(tvMoodRow("Moods & genres", emptyList()))
    }

    @Test fun theReplayCardIsTheYearsTimeAndTopSong() {
        val top = listOf(song(5), song(6))
        val row = tvReplayRow("Replay 2026", 3_600_000L * 30, "30h 0m", "Listened this year", top, "Your top song · Artist 5")!!
        assertEquals(HomeSections.REPLAY, row.row.key)
        assertEquals(listOf("30h 0m", "Song 5"), row.row.items.map { it.title })
        // The time plays the year's top songs; the top song plays itself.
        assertEquals(listOf("s5", "s6"), row.plays.getValue(TV_PLAY + "replay").songs.map { it.id })
        assertEquals(listOf("s5"), row.plays.getValue(TV_PLAY + "replay:top").songs.map { it.id })
        // Under a minute of listening (as on the phone), or nothing played: no card.
        assertNull(tvReplayRow("Replay 2026", 30_000L, "0h 0m", "Listened this year", top, null))
        assertNull(tvReplayRow("Replay 2026", 3_600_000L, "1h 0m", "Listened this year", emptyList(), null))
    }

    @Test fun theTilesAreLikedSongsDownloadsPinsAndRecentAlbums() {
        val pinned = listOf(PlaylistItem("prism:road", "Road trip", "Playlist", null))
        val albums = listOf(AlbumItem("MPREb_9", "Album 9", "Artist 9", null))
        val row = tvTilesRow("Shortcut tiles", TvPlayList("Liked songs", listOf(song(1), song(2))), "2 songs", TvPlayList("Downloads", emptyList()), "0 songs", pinned, albums)!!
        assertEquals(HomeSections.GREETING, row.row.key)
        // No downloads: no Downloads tile.
        assertEquals(listOf(TV_PLAY + "liked", "prism:road", "MPREb_9"), row.row.items.map { it.id })
        assertEquals("2 songs", row.row.items.first().subtitle)
        assertEquals(listOf("s1", "s2"), row.plays.getValue(TV_PLAY + "liked").songs.map { it.id })
        assertNull(tvTilesRow("Shortcut tiles", null, "", null, "", emptyList(), emptyList()))
    }

    @Test fun topGenresComeFromLikedSongs() {
        val liked = (1..6).map(::song)
        val genres = tvTopGenres(liked, mapOf("s1" to "Rock", "s2" to "Pop", "s3" to "Rock", "s4" to "Rock", "s5" to "Pop"))
        assertEquals(listOf("Rock", "Pop"), genres.map { it.first })
        val row = tvGenreRow("Your top genres", genres) { "$it songs" }!!
        assertEquals(HomeSections.GENRES, row.row.key)
        assertEquals(listOf("3 songs", "2 songs"), row.row.items.map { it.subtitle })
        assertEquals(listOf("s1", "s3", "s4"), row.plays.getValue(TV_PLAY + "genre:Rock").songs.map { it.id })
        assertNull(tvGenreRow("Your top genres", emptyList()) { "" })
    }

    @Test fun beforeAnyLayoutRowsComeInThePhonesSectionOrderThenPinsThenYouTube() {
        fun r(key: String) = TvHomeRow(key, key, emptyList())
        val rows = listOf(
            r(HomeSections.shelfKey("Mixed for you")), r(HomeSections.pinKey("album", "x")), r(HomeSections.RECENT),
            r(HomeSections.MADE_FOR_YOU), r(HomeSections.QUICK_PICKS), r(HomeSections.shelfKey("Throwbacks")), r(HomeSections.REPLAY),
        )
        assertEquals(
            listOf(
                HomeSections.REPLAY, HomeSections.QUICK_PICKS, HomeSections.MADE_FOR_YOU, HomeSections.RECENT,
                HomeSections.pinKey("album", "x"), HomeSections.shelfKey("Mixed for you"), HomeSections.shelfKey("Throwbacks"),
            ),
            tvAllRows(rows).map { it.key },
        )
    }
    @Test fun theHolidayRowShowsOnlyInItsSeason() {
        fun row(date: java.time.LocalDate) = com.prism.music.ui.tv.tvHolidayRow(
            "Holiday music", com.prism.music.data.seasonal.HolidayMixes.on(date), { it.key }, { "blurb" }, { "file:///covers/${it.key}.jpg" },
        )
        // December: the holiday mixes, each a mix card with its own cover, made when it's picked.
        val december = row(java.time.LocalDate.of(2026, 12, 10))!!
        assertEquals(HomeSections.SEASONAL, december.row.key)
        val first = december.row.items.first() as PlaylistItem
        assertTrue(first.isMix)
        assertTrue(first.id.startsWith(com.prism.music.ui.tv.TV_HOLIDAY + com.prism.music.data.seasonal.HolidayMixes.PREFIX))
        assertEquals("file:///covers/${first.title}.jpg", first.thumbnail)
        assertTrue(december.plays.isEmpty())
        // November has Thanksgiving's; July has none, so no row at all.
        val november = row(java.time.LocalDate.of(2026, 11, 20))!!
        assertTrue(november.row.items.map { it.id }.none { it in december.row.items.map { d -> d.id } })
        assertNull(row(java.time.LocalDate.of(2026, 7, 4)))
        // It comes after Quick picks, as the phone's layout puts it.
        assertEquals(
            listOf(HomeSections.QUICK_PICKS, HomeSections.SEASONAL, HomeSections.MADE_FOR_YOU),
            tvAllRows(listOf(TvHomeRow(HomeSections.MADE_FOR_YOU, "", emptyList()), december.row, TvHomeRow(HomeSections.QUICK_PICKS, "", emptyList()))).map { it.key },
        )
    }
}
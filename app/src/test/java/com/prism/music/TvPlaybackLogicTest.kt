package com.prism.music

import android.view.KeyEvent
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.ui.tv.KEY_DIAL
import com.prism.music.ui.tv.KEY_PICKS
import com.prism.music.ui.tv.KEY_RECENT
import com.prism.music.ui.tv.TvHomeTitles
import com.prism.music.ui.tv.TvItem
import com.prism.music.ui.tv.toTvItem
import com.prism.music.ui.tv.tvHomeRows
import com.prism.music.ui.tv.tvNow
import com.prism.music.ui.tv.tvTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prism TV (1.8.0 slice 3): Home's rows, what the player shows, and the remote's media keys. */
class TvPlaybackLogicTest {
    private fun song(i: Int, title: String = "Song $i") = Song("s$i", title, listOf(ArtistRef("Artist $i", "a$i")), AlbumRef("Album $i", "al$i"), durationSec = 200 + i)
    private val titles = TvHomeTitles("Quick picks", "Speed dial", "Recently played")

    @Test fun homeRowsComeInThePhonesOrder() {
        val shelves = listOf(
            Shelf("Mixed for you", items = listOf(PlaylistItem("RDmix", "Mix 1", "Mix", null, isMix = true))),
            Shelf("Quick picks", items = (1..3).map { SongItem(song(it)) }),
            Shelf("Speed dial", items = listOf(AlbumItem("al9", "Album 9", "Artist", null), MoodItem("m", "Chill", null, 0L))),
            Shelf("Moods & genres", items = listOf(MoodItem("m2", "Focus", null, 0L))),
        )
        val rows = tvHomeRows(shelves, "Alex", recent = listOf(song(7), song(7), song(8)), mostPlayed = listOf(SongItem(song(9)), AlbumItem("al9", "Album 9", "", null)), titles)
        assertEquals(listOf(KEY_PICKS, KEY_DIAL, KEY_RECENT, "yt:shelf:mixed for you"), rows.map { it.key })
        // Speed dial: YouTube's tiles first, then your most played, each once; mood tiles aren't cards.
        assertEquals(listOf("al9", "s9"), rows[1].items.map { it.id })
        // Recently played: each song once. A shelf of only mood tiles is left out.
        assertEquals(listOf("s7", "s8"), rows[2].items.map { it.id })
    }

    @Test fun signedOutHomeHasNoEmptyRows() {
        val rows = tvHomeRows(emptyList(), null, emptyList(), emptyList(), titles)
        assertTrue(rows.isEmpty())
    }

    @Test fun cardsKnowWhatTheyAre() {
        assertEquals(TvItem.Kind.ARTIST, ArtistItem("a", "A", "Artist", null).toTvItem().kind)
        assertEquals(TvItem.Kind.MIX, PlaylistItem("RD1", "M", "Mix", null, isMix = true).toTvItem().kind)
        assertEquals(TvItem.Kind.PLAYLIST, PlaylistItem("PL1", "P", "Playlist", null).toTvItem().kind)
        assertEquals(TvItem.Kind.VIDEO, SongItem(song(1).copy(isVideo = true)).toTvItem().kind)
        // A blank second line isn't a line.
        assertNull(AlbumItem("al", "Al", "", null).toTvItem().subtitle)
    }

    @Test fun timesReadAsOnThePhone() {
        assertEquals("0:00", tvTime(0))
        assertEquals("0:00", tvTime(-5))
        assertEquals("3:41", tvTime(221_000))
        assertEquals("1:02:05", tvTime(3_725_000))
    }

    @Test fun thePlayerShowsTheSongAndWhatsNext() {
        val now = tvNow(song(1, "Glass Horizon"), playing = true, positionMs = 84_000, durationMs = 0, liked = true, shuffle = false, repeat = 2, hasVideo = true, next = song(2, "Northern Static"))
        assertEquals("Glass Horizon", now.title)
        assertEquals("Album 1", now.album)
        // No duration from the player yet: the song's own length.
        assertEquals("3:21", now.total)
        assertEquals(84f / 201f, now.fraction, 0.001f)
        assertEquals("Northern Static · Artist 2", now.next)
        assertTrue(now.liked && now.hasVideo && now.playing)
        // Past the end (a late position report) stays at the end; a live stream has no total.
        assertEquals(1f, tvNow(song(1), true, 999_000, 201_000, false, false, 0, false, null).fraction, 0f)
        assertEquals("", tvNow(song(1).copy(isLive = true), true, 5_000, 0, false, false, 0, false, null).total)
        // A podcast episode has no lyrics button.
        assertTrue(!tvNow(song(1).copy(isEpisode = true), true, 0, 0, false, false, 0, false, null).hasLyrics)
    }

    @Test fun everyMediaKeyDoesSomething() {
        val keys = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to TvMediaKeys.Action.TOGGLE, KeyEvent.KEYCODE_MEDIA_PLAY to TvMediaKeys.Action.PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE to TvMediaKeys.Action.PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT to TvMediaKeys.Action.NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to TvMediaKeys.Action.PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to TvMediaKeys.Action.FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND to TvMediaKeys.Action.BACKWARD, KeyEvent.KEYCODE_MEDIA_STOP to TvMediaKeys.Action.STOP,
        )
        for ((k, a) in keys) assertEquals("key $k", a, TvMediaKeys.action(k))
        // The D-pad and Back stay the screens'.
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) assertNull(TvMediaKeys.action(k))
    }
}

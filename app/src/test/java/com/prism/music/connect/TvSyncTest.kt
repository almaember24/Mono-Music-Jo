package com.prism.music.connect

import com.prism.music.connect.TvSync.cleaned
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playing on a Prism TV from the phone (1.8.0, the owner: "the phone and TV were supposed to have the music in sync"):
 * what the TV does with what the phone sends, what the phone sends, and when the phone's playhead follows the TV's.
 */
class TvSyncTest {
    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist", null)), durationSec = 200, thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
    private val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd")
    private fun state(index: Int, playing: Boolean = true, seekTo: Long = -1, songs: List<String> = ids) =
        FollowState(songs.map(::song), index, playing, seekTo)

    @Test fun theTvLoadsAnotherSongAndKeepsTheOnePlayingWhenOnlyWhatComesNextChanged() {
        // Nothing yet: the songs, from where the phone is.
        assertEquals(TvSync.Plan(true, load = true, startMs = 42_000), TvSync.plan(TvSync.Have(emptyList(), -1, -1), state(1, seekTo = 42_000)))
        // On it already, the same songs: only play or pause.
        assertEquals(TvSync.Plan(false), TvSync.plan(TvSync.Have(ids, 1, 90_000), state(1, playing = false)))
        // On it, but the queue after it changed (a song added, shuffle): the songs around it, no gap in the music.
        val added = ids.take(2) + "eeeeeeeeeee" + ids.drop(2)
        assertEquals(TvSync.Plan(true, around = true), TvSync.plan(TvSync.Have(ids, 1, 90_000), state(1, songs = added)))
        // A seek on the phone.
        assertEquals(TvSync.Plan(true, seekTo = 30_000), TvSync.plan(TvSync.Have(ids, 1, 90_000), state(1, seekTo = 30_000)))
        // A song skipped to on the phone: loaded, from its start.
        assertEquals(TvSync.Plan(true, load = true), TvSync.plan(TvSync.Have(ids, 1, 90_000), state(3)))
    }

    @Test fun aSongTheTvHasNearlyFinishedRunsOutIntoTheNextRatherThanBeingCut() {
        // The phone got to the next song a moment first: the TV's song runs out into the same one by itself.
        assertTrue(TvSync.plan(TvSync.Have(ids, 1, 1_200), state(2)).wait)
        // Far from the end (a skip on the phone): it goes at once.
        assertTrue(TvSync.plan(TvSync.Have(ids, 1, 60_000), state(2)).load)
        // Not known how much is left (still loading): at once too.
        assertTrue(TvSync.plan(TvSync.Have(ids, 1, -1), state(2)).load)
    }

    @Test fun aSongTheTvCantPlayPausesIt() {
        // A file on the phone: the phone plays it itself, the TV pauses.
        assertEquals(TvSync.Plan(false), TvSync.plan(TvSync.Have(ids, 1, 90_000), state(-1)))
        val local = FollowState(listOf(song(ids[0]), song("local:42"), song(ids[1])), 1, true).cleaned()
        assertEquals(-1, local.index)
        assertEquals(listOf(ids[0], ids[1]), local.songs.map { it.id })
        // Pictures only from YouTube's own image servers.
        val odd = FollowState(listOf(song(ids[0]).copy(thumbnail = "https://example.com/x.jpg")), 0, true).cleaned()
        assertNull(odd.songs.single().thumbnail)
        assertEquals(0, odd.index)
    }

    @Test fun thePhoneSendsTheSongBeforeTheOnePlayingAndWhatComesNextInPlayOrder() {
        // Shuffled: 4 → 0 → 2 → 1 → 3 (queue indices).
        val order = listOf(4, 0, 2, 1, 3)
        val next = { i: Int -> order.getOrNull(order.indexOf(i) + 1) }
        val previous = { i: Int -> order.getOrNull(order.indexOf(i) - 1) }
        assertEquals(listOf(0, 2, 1, 3) to 1, TvSync.window(2, previous, next))
        // At the start: nothing before it.
        assertEquals(listOf(4, 0, 2) to 0, TvSync.window(4, previous, next, ahead = 2))
        // Repeat all loops round: the queue once.
        val loop = { i: Int -> (i + 1) % 3 }
        val back = { i: Int -> (i + 2) % 3 }
        assertEquals(listOf(0, 1, 2) to 1, TvSync.window(1, back, loop))
    }

    @Test fun thePhonesPlayheadFollowsTheTvsOnlyWhenItsWellOff() {
        val w = TvWhere(ids[0], 60_000, playing = true)
        assertNull(TvSync.follow(60_400, w, 0))
        assertEquals(61_000L, TvSync.follow(59_000, w, 1_000))
        // The TV paused, or loading the song: the phone isn't moved.
        assertNull(TvSync.follow(10_000, w.copy(playing = false), 0))
        assertNull(TvSync.follow(10_000, w.copy(buffering = true), 0))
    }

    /**
     * The 1.8.0 review (1): the TV played into the next song a moment before the phone's muted player; a keepalive or a
     * queue change still naming the song before started that over from 0 (about one song change in five).
     */
    @Test fun aSongTheTvPlayedIntoByItselfIsntStartedOverByALateState() {
        // On C (index 2), reached by itself from B; the phone's state still names B, C next.
        assertEquals(TvSync.Plan(true), TvSync.plan(TvSync.Have(ids, 2, 200_000), state(1), reachedFrom = ids[1]))
        // Without that (a skip back on the phone), B is loaded, as before.
        assertTrue(TvSync.plan(TvSync.Have(ids, 2, 200_000), state(1)).load)
        // A seek on the phone into B is meant: it's loaded.
        assertTrue(TvSync.plan(TvSync.Have(ids, 2, 200_000), state(1, seekTo = 30_000), reachedFrom = ids[1]).load)
    }

    /**
     * The 1.8.0 review (2): Next or Pause on the TV's remote, then a keepalive sent before the phone had it, reloaded the
     * song before or played again. A state is taken only once the phone has taken in the TV's latest act.
     */
    @Test fun aStateFromBeforeTheTvsLatestActIsntTaken() {
        val f = state(1).copy(acts = 4)
        assertTrue(TvSync.current(f, actsDone = 4))
        assertTrue(TvSync.current(f, actsDone = 0))
        assertFalse(TvSync.current(f, actsDone = 5))
    }

    /** The 1.8.0 review (4): a queue edit on the phone, then a pick on the TV: the song picked, wherever it is now. */
    @Test fun aSongPickedOnTheTvIsFoundAfterTheQueueChanged() {
        val sent = listOf(7, 8, 9)
        val sentIds = listOf(ids[0], ids[1], ids[2])
        val pick = TvAct(TvAct.PICK, index = 2, songId = ids[2])
        // The queue as sent: index 9.
        val same = { i: Int -> mapOf(7 to ids[0], 8 to ids[1], 9 to ids[2])[i] }
        assertEquals(9, TvSync.pickIndex(pick, sent, sentIds, same, 20, 8))
        // A song put in before it: it's at 10 now, not 9.
        val moved = { i: Int -> mapOf(7 to ids[0], 8 to ids[1], 9 to "eeeeeeeeeee", 10 to ids[2])[i] }
        assertEquals(10, TvSync.pickIndex(pick, sent, sentIds, moved, 20, 8))
        // Taken out of the queue: nothing.
        val gone = { i: Int -> mapOf(7 to ids[0], 8 to ids[1])[i] }
        assertNull(TvSync.pickIndex(pick, sent, sentIds, gone, 20, 8))
    }

    /** The 1.8.0 review (7): the TV's word on its volume, sent before a change on the phone reached it, isn't taken. */
    @Test fun aVolumeSetOnThePhoneIsntUndoneByAnOlderWordFromTheTv() {
        assertTrue(TvSync.takesVolume(nowMs = 10_000, setAtMs = 0))
        assertFalse(TvSync.takesVolume(nowMs = 10_000, setAtMs = 9_000))
        assertTrue(TvSync.takesVolume(nowMs = 12_500, setAtMs = 10_000))
    }

    @Test fun aSongPickedOnTheTvIsTheOneThePhoneSentThere() {
        val sent = listOf(7, 8, 9)
        val sentIds = listOf(ids[0], ids[1], ids[2])
        assertEquals(9, TvSync.picked(TvAct(TvAct.PICK, index = 2, songId = ids[2]), sent, sentIds))
        // Not what was sent there (the queue moved on meanwhile), or out of range: nothing.
        assertNull(TvSync.picked(TvAct(TvAct.PICK, index = 2, songId = ids[3]), sent, sentIds))
        assertNull(TvSync.picked(TvAct(TvAct.PICK, index = 5, songId = ids[2]), sent, sentIds))
        assertFalse(TvSync.picked(TvAct(TvAct.PAUSE), sent, sentIds) != null)
    }
}

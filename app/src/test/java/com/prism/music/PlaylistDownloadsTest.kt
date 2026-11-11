package com.prism.music

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.download.PlaylistDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #221 skipping songs in a list's download, #298 keeping a playlist downloaded. */
class PlaylistDownloadsTest {
    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Someone")), durationSec = 120)
    private val songs = listOf("a", "b", "c").map(::song)
    private fun ids(l: List<Song>) = l.map { it.id }

    @Test fun skippedSongsAreLeftOutOfTheDownload() {
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.wanted(songs, setOf("b"), emptyMap())))
        assertEquals(listOf("a", "b", "c"), ids(PlaylistDownloads.wanted(songs, emptySet(), emptyMap())))
    }

    /** Failed downloads names the song a video row came down as: the row counts as skipped too. */
    @Test fun aVideoRowFollowsItsSong() {
        val standIns = mapOf("b" to "songOfB")
        assertTrue(PlaylistDownloads.isSkipped("b", setOf("songOfB"), standIns))
        assertTrue(PlaylistDownloads.isSkipped("b", setOf("b"), standIns))
        assertFalse(PlaylistDownloads.isSkipped("a", setOf("songOfB"), standIns))
    }

    @Test fun aListWithSkippedSongsCountsAsDownloaded() {
        val done = setOf("a", "c")
        assertFalse(PlaylistDownloads.allDownloaded(songs, emptySet(), emptyMap()) { it in done })
        assertTrue(PlaylistDownloads.allDownloaded(songs, setOf("b"), emptyMap()) { it in done })
        assertFalse(PlaylistDownloads.allDownloaded(songs, setOf("b"), emptyMap()) { it == "a" })
    }

    /** Nothing wanted and nothing here isn't "downloaded": the page would offer to delete nothing. */
    @Test fun allSkippedOrEmptyIsNotDownloaded() {
        assertFalse(PlaylistDownloads.allDownloaded(songs, setOf("a", "b", "c"), emptyMap()) { true })
        assertFalse(PlaylistDownloads.allDownloaded(emptyList(), emptySet(), emptyMap()) { true })
    }

    @Test fun keepingFetchesOnlyWhatsNew() {
        assertEquals(listOf("c"), ids(PlaylistDownloads.toFetch(songs, emptySet(), emptyMap(), setOf("a", "b"), emptySet())))
    }

    @Test fun keepingLeavesFailedSongsAlone() {
        // By its reason kept, or by a failed entry in the list.
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, emptySet(), emptyMap(), emptySet(), setOf("b"))))
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, emptySet(), emptyMap(), setOf("b"), emptySet())))
    }

    @Test fun keepingRespectsSkips() {
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, setOf("b"), emptyMap(), emptySet(), emptySet())))
    }

    /** PR #304 looked at the row's id only, so a video that came down as its song (or failed as it) was fetched again. */
    @Test fun keepingKnowsAVideoRowByItsSong() {
        val standIns = mapOf("b" to "songOfB")
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, emptySet(), standIns, setOf("songOfB"), emptySet())))
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, emptySet(), standIns, emptySet(), setOf("songOfB"))))
    }

    @Test fun keepingLeavesWhatCantDownload() {
        val local = song("local:5")
        val live = song("l").copy(isLive = true)
        assertEquals(listOf("a"), ids(PlaylistDownloads.toFetch(listOf(local, live, song("a")), emptySet(), emptyMap(), emptySet(), emptySet())))
    }

    /** Deleted while the list was kept: not fetched back, by the row's id or, for a video row, by its song's. New ones still are. */
    @Test fun keepingLeavesWhatWasDeleted() {
        val noOne = emptySet<String>()
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, noOne, emptyMap(), noOne, noOne, deleted = setOf("b"))))
        assertEquals(listOf("a", "c"), ids(PlaylistDownloads.toFetch(songs, noOne, mapOf("b" to "songOfB"), noOne, noOne, deleted = setOf("songOfB"))))
    }

    /** The record stays small: what's left the list goes from it, and what's on it stays, by the row or the song it came down as. */
    @Test fun theDeletedRecordFollowsTheList() {
        assertEquals(setOf("a", "songOfB"), PlaylistDownloads.stillDeleted(setOf("a", "songOfB", "gone"), songs, mapOf("b" to "songOfB")))
    }

    @Test fun theWifiRule() {
        assertTrue(PlaylistDownloads.mayFetch(online = true, metered = false, wifiOnly = true))
        assertTrue(PlaylistDownloads.mayFetch(online = true, metered = true, wifiOnly = false))
        assertFalse(PlaylistDownloads.mayFetch(online = true, metered = true, wifiOnly = true))
        assertFalse(PlaylistDownloads.mayFetch(online = false, metered = false, wifiOnly = false))
    }

    @Test fun whatCanBeKept() {
        assertEquals("PLabc", PlaylistDownloads.keepKey("VLPLabc"))
        assertEquals(PlaylistDownloads.keepKey("PLabc"), PlaylistDownloads.keepKey("VLPLabc"))
        assertEquals("prism:1", PlaylistDownloads.keepKey("prism:1"))
        assertTrue(PlaylistDownloads.canKeep("VLPLabc"))
        assertTrue(PlaylistDownloads.canKeep("prism:1"))
        // YouTube's own featured playlists stay as they are; mixes and Liked Music don't.
        assertTrue(PlaylistDownloads.canKeep("VLRDCLAK5uy_abc"))
        assertFalse(PlaylistDownloads.canKeep("VLRDabc"))
        assertFalse(PlaylistDownloads.canKeep("VLLM"))
    }

    @Test fun aPlaylistIsReadAgainAfterAnHour() {
        val now = 10L * PlaylistDownloads.READ_GAP_MS
        assertTrue(PlaylistDownloads.readDue(0, now))
        assertFalse(PlaylistDownloads.readDue(now - 1000, now))
        assertTrue(PlaylistDownloads.readDue(now - PlaylistDownloads.READ_GAP_MS, now))
        // A clock put back doesn't stop it for good.
        assertTrue(PlaylistDownloads.readDue(now + 1000, now))
    }
}

package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.PlaylistEdits
import com.prism.music.data.innertube.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 1.8.1, Reddit (blinkertoon, 1.7.9): "Add to playlist" said "Added" for songs that never got there. An answer without
 * its per-song results, or with a failed status, counted as added, and a whole album's add wasn't checked at all.
 */
class PlaylistEditsTest {
    private fun json(s: String) = InnerTube.json.parseToJsonElement(s).jsonObject

    private fun added(vararg ids: String) =
        """{"status":"STATUS_SUCCEEDED","playlistEditResults":[${ids.joinToString(",") { """{"playlistEditVideoAddedResultData":{"videoId":"$it","setVideoId":"s$it"}}""" }}]}"""

    @Test fun onlySongsYouTubeListsCountAsAdded() {
        assertEquals(PlaylistEdits.Outcome.Done(2), PlaylistEdits.read(json(added("a", "b")), 3))
        // Listed with none of them: they were in it already.
        assertEquals(PlaylistEdits.Outcome.Done(0), PlaylistEdits.read(json("""{"status":"STATUS_SUCCEEDED","playlistEditResults":[{}]}"""), 1))
    }

    @Test fun aFailedStatusIsNotAnAdd() {
        val r = PlaylistEdits.read(json("""{"status":"STATUS_FAILED","actions":[{"addToToastAction":{"item":{"notificationTextRenderer":{"successResponseText":{"runs":[{"text":"x"}]},"text":{"runs":[{"text":"This playlist can't be changed"}]}}}}}]}"""), 1)
        assertTrue("$r", r is PlaylistEdits.Outcome.Refused)
        assertEquals("This playlist can't be changed", (r as PlaylistEdits.Outcome.Refused).message)
    }

    @Test fun anAnswerThatDoesntSayIsUnclear() {
        // 1.7.9 called this added.
        assertEquals(PlaylistEdits.Outcome.Unclear, PlaylistEdits.read(json("""{"responseContext":{}}"""), 1))
    }

    @Test fun anUnclearAnswerIsCheckedAgainstThePlaylist() = runBlocking {
        val yt = FakeYouTube { endpoint, body ->
            when (endpoint) {
                "browse/edit_playlist" -> 200 to """{"responseContext":{}}"""
                // The playlist afterwards: only "a" is in it.
                "browse" -> 200 to playlistPage(listOf("a"))
                else -> 404 to "{}"
            }
        }
        val r = yt.ytm.addSongsToPlaylist("PLmine", listOf("a", "b"))
        assertEquals(1, r.added)
        assertEquals(1, r.refused)
        // Nothing of it got there: an error, not "Added".
        try {
            yt.ytm.addSongsToPlaylist("PLmine", listOf("c"))
            fail("a song that isn't in the playlist was taken as added")
        } catch (e: java.io.IOException) { /* expected */ }
    }

    @Test fun manySongsAreCountedAndATurnedDownBatchGoesSongBySong() = runBlocking {
        // YouTube turns down a batch with "bad" in it; one by one, the others go in.
        val yt = FakeYouTube { endpoint, body ->
            if (endpoint != "browse/edit_playlist") return@FakeYouTube 404 to "{}"
            val ids = (body["actions"] as JsonArray).map { it.jsonObject.str("addedVideoId")!! }
            when {
                "bad" in ids -> 400 to """{"error":{"code":400,"message":"Request contains an invalid argument."}}"""
                else -> 200 to added(*ids.filter { it != "dup" }.toTypedArray())
            }
        }
        val r = yt.ytm.addSongsToPlaylist("PLmine", listOf("a", "b", "bad", "dup"))
        assertEquals(2, r.added)
        assertEquals(1, r.alreadyThere)
        assertEquals(1, r.refused)
    }

    @Test fun aConnectionDroppedOnALaterBatchKeepsTheSongsAlreadyAdded() = runBlocking {
        // The first 50 go in; the connection drops on the next 50.
        val yt = FakeYouTube { endpoint, body ->
            if (endpoint != "browse/edit_playlist") return@FakeYouTube 404 to "{}"
            val ids = (body["actions"] as JsonArray).map { it.jsonObject.str("addedVideoId")!! }
            if ("s50" in ids) throw java.io.IOException("connection reset")
            200 to added(*ids.toTypedArray())
        }
        val r = yt.ytm.addSongsToPlaylist("PLmine", (0 until 100).map { "s$it" })
        assertEquals(50, r.added)
        assertEquals(50, r.refused)
    }

    @Test fun theMessageSaysWhatHappened() {
        val ok = com.prism.music.data.innertube.YouTubeMusic.PlaylistAdd(added = 3, alreadyThere = 0)
        assertTrue(com.prism.music.ui.components.addedMessage(ok, "Road trip", many = true).contains("3"))
        assertEquals("Already in Road trip", com.prism.music.ui.components.addedMessage(ok.copy(added = 0, alreadyThere = 1), "Road trip", many = false))
        assertEquals("Couldn't add to Road trip", com.prism.music.ui.components.addedMessage(null, "Road trip", many = false))
        assertEquals("Added to Road trip", com.prism.music.ui.components.addedMessage(ok.copy(added = 1), "Road trip", many = false))
    }

    companion object {
        /** An owned playlist's page with [ids] in it. */
        fun playlistPage(ids: List<String>) = """{"contents":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Mine"}]}}}},
            "x":{"musicPlaylistShelfRenderer":{"contents":[${ids.joinToString(",") { row(it) }}]}}}}"""

        fun row(id: String) = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id","playlistSetVideoId":"s$id"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}"""
    }
}

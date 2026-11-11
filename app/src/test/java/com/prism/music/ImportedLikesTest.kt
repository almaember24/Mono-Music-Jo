package com.prism.music

import com.prism.music.data.LibraryRepository
import com.prism.music.data.imports.HistoryImports
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Imported Liked Songs (1.8.1): liked in Prism, and on the YouTube Music account only when the listener turns that on
 * (owner: "not automatically, but it should be an option"). Kept off the account, a library sync must leave them liked.
 */
class ImportedLikesTest {
    @Test fun syncLeavesImportedLikesKeptOffTheAccount() {
        val before = setOf("unliked1234", "imported123", "local:42", "stillLiked1")
        val remote = setOf("stillLiked1")
        assertEquals(listOf("unliked1234"), LibraryRepository.likesToDrop(before, remote, kept = setOf("imported123")))
    }

    @Test fun aRunStoppedHalfWayLeavesTheRestKept() = runBlocking {
        // Liked on the account one by one; the job is stopped (its ten minutes) while the second is being asked.
        val songs = listOf("oneSong0001", "twoSong0002", "threeSong03").map { Song(it, it, listOf(ArtistRef("A"))) }
        val kept = linkedSetOf<String>()
        val asking = CompletableDeferred<Unit>()
        val run = launch {
            HistoryImports.likeAll(
                songs, likeHere = { _, _ -> true }, onAccount = true,
                likeThere = { id -> if (id == songs[1].id) { asking.complete(Unit); awaitCancellation() } else true },
                keep = { kept += it }, unkeep = { kept -= it }, pause = {},
            )
        }
        asking.await()
        run.cancelAndJoin()
        assertEquals("the ones the account doesn't have yet stay kept", setOf(songs[1].id, songs[2].id), kept)
        // So the next library sync (the account has only the first) leaves them liked here.
        val liked = songs.map { it.id }.toSet() + "unliked1234"
        assertEquals(listOf("unliked1234"), LibraryRepository.likesToDrop(liked, setOf(songs[0].id), kept))
    }

    @Test fun withoutKeptLikesSyncIsAsBefore() {
        val before = setOf("a1", "b2", "local:7")
        assertEquals(listOf("a1"), LibraryRepository.likesToDrop(before, setOf("b2"), kept = emptySet()))
    }
}

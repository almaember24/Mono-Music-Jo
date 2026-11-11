package com.prism.music.data.innertube

import kotlinx.serialization.json.JsonObject

/**
 * How YouTube Music answered a playlist edit (`browse/edit_playlist` with songs to add). It says "STATUS_SUCCEEDED" and
 * lists each song that went in (`playlistEditVideoAddedResultData`, with the song's new place); one left out as already
 * there has no entry. Until 1.8.1 an answer without that list counted as every song added, and a failed status wasn't
 * read at all: "Added" showed for songs that never got there (Reddit, 1.7.9).
 */
object PlaylistEdits {
    sealed interface Outcome {
        /** [added] of the songs sent went in; the rest were there already. */
        data class Done(val added: Int) : Outcome
        /** YouTube said no (its message, when it gave one). */
        data class Refused(val message: String) : Outcome
        /** The answer doesn't say what happened: the playlist itself has to be looked at. */
        data object Unclear : Outcome
    }

    fun read(res: JsonObject, asked: Int): Outcome {
        val status = res.str("status")
        if (status != null && status != "STATUS_SUCCEEDED") return Outcome.Refused(message(res) ?: status)
        val added = res.findAll("playlistEditVideoAddedResultData").size
        if (added > 0) return Outcome.Done(added.coerceAtMost(asked))
        // The list is there and names none: they were all in it already. No list and no status: nothing to go by.
        return if (res["playlistEditResults"] != null && status != null) Outcome.Done(0) else Outcome.Unclear
    }

    /** The note YouTube shows with its answer ("This playlist can't be changed"), if there's one. */
    private fun message(res: JsonObject): String? =
        res.findFirst("addToToastAction").findFirst("text").text()
            ?: res.findFirst("notificationActionRenderer").findFirst("responseText").text()
}

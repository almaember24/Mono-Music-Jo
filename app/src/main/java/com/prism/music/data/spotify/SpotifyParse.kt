package com.prism.music.data.spotify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A Spotify track as Prism keeps it: what matching needs (the ISRC above all) and what a list shows. */
@Serializable
data class SpotifyTrack(
    val uri: String,
    val title: String,
    /** Its artists in Spotify's order; the first is the main one. */
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long = 0,
    val isrc: String? = null,
    val explicit: Boolean = false,
    val image: String? = null,
) {
    val artist: String get() = artists.firstOrNull().orEmpty()
}

data class SpotifyProfile(val id: String, val name: String)
data class RecentPlay(val track: SpotifyTrack, val playedAt: Long)
data class SavedTrack(val track: SpotifyTrack, val addedAt: Long)
@Serializable
data class TopArtist(val name: String, val image: String? = null)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val ownerId: String,
    val collaborative: Boolean = false,
    val snapshot: String = "",
    val image: String? = null,
    val total: Int = 0,
    val description: String = "",
    /** Its owner as Spotify shows them ("Lofi Girl", "Spotify"); the id when they have no name. */
    val ownerName: String = "",
)

/** One page of a Spotify list and where the next one is (null: the last). */
data class SpotifyPage<T>(val items: List<T>, val next: String?, val total: Int = items.size)

/**
 * Reads the Web API's answers (November 2026 shapes, from the reference's examples). Fields Spotify marked deprecated
 * (popularity, available markets, preview links) aren't read; playlists' items come under "item" since February 2026,
 * "track" read as well for older answers. Episodes and local files in a list are left out: there's nothing to match.
 */
object SpotifyParse {
    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(body: String) = json.parseToJsonElement(body).jsonObject
    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.o(k: String) = this[k]?.takeIf { it !is JsonNull } as? JsonObject
    private fun JsonObject.arr(k: String) = (this[k]?.takeIf { it !is JsonNull } as? JsonArray).orEmpty()

    private fun time(s: String?): Long = s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /** The biggest picture is listed first; a mid-sized one is enough for a list. */
    private fun image(images: List<JsonElement>): String? =
        images.mapNotNull { it as? JsonObject }.let { list -> (list.getOrNull(1) ?: list.firstOrNull())?.str("url") }

    fun profile(body: String): SpotifyProfile {
        val o = obj(body)
        val id = o.str("id") ?: throw IllegalArgumentException("no user id")
        return SpotifyProfile(id, o.str("display_name")?.takeIf { it.isNotBlank() } ?: id)
    }

    /** A track object; null for an episode, a local file or a track without a name. */
    fun track(e: JsonElement?): SpotifyTrack? {
        val o = e?.takeIf { it !is JsonNull } as? JsonObject ?: return null
        if (o.str("type") != null && o.str("type") != "track") return null
        if ((o["is_local"]?.jsonPrimitive?.booleanOrNull) == true) return null
        val uri = o.str("uri")?.takeIf { it.startsWith("spotify:track:") } ?: return null
        val title = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val artists = o.arr("artists").mapNotNull { (it as? JsonObject)?.str("name") }.filter { it.isNotBlank() }
        val album = o.o("album")
        return SpotifyTrack(
            uri = uri, title = title, artists = artists, album = album?.str("name"),
            durationMs = o["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0,
            isrc = o.o("external_ids")?.str("isrc")?.takeIf { it.isNotBlank() }?.uppercase(),
            explicit = o["explicit"]?.jsonPrimitive?.booleanOrNull ?: false,
            image = album?.let { image(it.arr("images")) },
        )
    }

    fun recentlyPlayed(body: String): List<RecentPlay> = obj(body).arr("items").mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val t = track(o["track"]) ?: return@mapNotNull null
        val at = time(o.str("played_at")).takeIf { it > 0 } ?: return@mapNotNull null
        RecentPlay(t, at)
    }

    fun topTracks(body: String): List<SpotifyTrack> = obj(body).arr("items").mapNotNull { track(it) }

    fun topArtists(body: String): List<TopArtist> = obj(body).arr("items").mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TopArtist(name, image(o.arr("images")))
    }

    fun playlists(body: String): SpotifyPage<SpotifyPlaylist> {
        val o = obj(body)
        val list = o.arr("items").mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            val owner = p.o("owner")
            SpotifyPlaylist(
                id = id, name = p.str("name").orEmpty().ifBlank { id }, ownerId = owner?.str("id").orEmpty(),
                ownerName = owner?.str("display_name")?.takeIf { it.isNotBlank() } ?: owner?.str("id").orEmpty(),
                collaborative = p["collaborative"]?.jsonPrimitive?.booleanOrNull ?: false, snapshot = p.str("snapshot_id").orEmpty(),
                image = image(p.arr("images")),
                // "items" holds the count since February 2026 ("tracks" before, deprecated).
                total = (p.o("items") ?: p.o("tracks"))?.get("total")?.jsonPrimitive?.intOrNull ?: 0,
                description = p.str("description").orEmpty(),
            )
        }
        return SpotifyPage(list, o.str("next"), o["total"]?.jsonPrimitive?.intOrNull ?: list.size)
    }

    /** One playlist's snapshot id and name (GET /playlists/{id} with fields=snapshot_id,name). */
    fun playlistSnapshot(body: String): Pair<String, String?> {
        val o = obj(body)
        return (o.str("snapshot_id") ?: throw IllegalArgumentException("no snapshot")) to o.str("name")
    }

    /** A page of a playlist's items: tracks only (null where an item isn't one, so positions still count). */
    fun playlistItems(body: String): SpotifyPage<SpotifyTrack?> {
        val o = obj(body)
        val list = o.arr("items").map { e ->
            val it = e as? JsonObject
            if (it?.get("is_local")?.jsonPrimitive?.booleanOrNull == true) null else track(it?.get("item") ?: it?.get("track"))
        }
        return SpotifyPage(list, o.str("next"), o["total"]?.jsonPrimitive?.intOrNull ?: list.size)
    }

    fun savedTracks(body: String): SpotifyPage<SavedTrack> {
        val o = obj(body)
        val list = o.arr("items").mapNotNull { e ->
            val it = e as? JsonObject ?: return@mapNotNull null
            val t = track(it["track"]) ?: return@mapNotNull null
            SavedTrack(t, time(it.str("added_at")))
        }
        return SpotifyPage(list, o.str("next"), o["total"]?.jsonPrimitive?.intOrNull ?: list.size)
    }

    fun searchTracks(body: String): List<SpotifyTrack> =
        obj(body).o("tracks")?.arr("items")?.mapNotNull { track(it) }.orEmpty()

    /** An error answer's message ({"error":{"status":403,"message":"…"}}), for diagnostics. */
    fun errorMessage(body: String): String? = runCatching {
        val e = obj(body)["error"]
        (e as? JsonObject)?.str("message") ?: (e as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** The quota's own 429 (July 2026: "reason": "QUOTA_EXCEEDED"). */
    fun quotaExceeded(body: String): Boolean = runCatching { obj(body).o("error")?.str("reason") == "QUOTA_EXCEEDED" }.getOrDefault(false)
}

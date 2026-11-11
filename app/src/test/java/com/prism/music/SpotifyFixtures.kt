package com.prism.music

import com.prism.music.data.spotify.TokenBox
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Spotify's answers for the Spotify tests (1.8.1), written in the shapes of the Web API reference's examples (November
 * 2026: playlists' items under "item", deprecated fields still in the answers but not read), and a fake network that
 * serves them and notes every request.
 */
object SpotifyFixtures {
    fun track(id: String, name: String, artist: String, album: String, ms: Long, isrc: String?, explicit: Boolean = false) = """
        {"album":{"album_type":"album","total_tracks":14,"available_markets":["US"],"external_urls":{"spotify":"https://open.spotify.com/album/al$id"},
          "href":"https://api.spotify.com/v1/albums/al$id","id":"al$id",
          "images":[{"url":"https://i.scdn.co/image/640-$id","height":640,"width":640},{"url":"https://i.scdn.co/image/300-$id","height":300,"width":300}],
          "name":"$album","release_date":"2020-03-20","release_date_precision":"day","type":"album","uri":"spotify:album:al$id",
          "artists":[{"external_urls":{"spotify":"https://open.spotify.com/artist/ar$id"},"href":"https://api.spotify.com/v1/artists/ar$id","id":"ar$id","name":"$artist","type":"artist","uri":"spotify:artist:ar$id"}]},
         "artists":[{"external_urls":{"spotify":"https://open.spotify.com/artist/ar$id"},"href":"https://api.spotify.com/v1/artists/ar$id","id":"ar$id","name":"$artist","type":"artist","uri":"spotify:artist:ar$id"}],
         "available_markets":["US"],"disc_number":1,"duration_ms":$ms,"explicit":$explicit,
         "external_ids":{${if (isrc != null) "\"isrc\":\"$isrc\"," else ""}"upc":null},"external_urls":{"spotify":"https://open.spotify.com/track/$id"},
         "href":"https://api.spotify.com/v1/tracks/$id","id":"$id","is_playable":true,"linked_from":{},"restrictions":{"reason":""},
         "name":"$name","popularity":80,"preview_url":null,"track_number":1,"type":"track","uri":"spotify:track:$id","is_local":false}
    """.trimIndent()

    val blinding = track("0VjIjW4GlUZAMYd2vXMi3b", "Blinding Lights", "The Weeknd", "After Hours", 200_040, "USUG11904206")
    val antiHero = track("0V3wPSX9ygBnCm8psDIegu", "Anti-Hero", "Taylor Swift", "Midnights", 200_690, "USUG12205736", explicit = true)
    val creep = track("70LcF31zb1H0PyJoS1Sx1r", "Creep", "Radiohead", "Pablo Honey", 238_640, "GBAYE9200070")

    val me = """{"display_name":"Sam Rivera","external_urls":{"spotify":"https://open.spotify.com/user/samr"},"href":"https://api.spotify.com/v1/users/samr","id":"samr","images":[],"type":"user","uri":"spotify:user:samr"}"""

    fun recentlyPlayed(vararg plays: Pair<String, String>) = """
        {"href":"https://api.spotify.com/v1/me/player/recently-played?limit=50","limit":50,"next":null,
         "cursors":{"after":"1730000000000","before":"1720000000000"},"total":${plays.size},
         "items":[${plays.joinToString(",") { (t, at) -> """{"track":$t,"played_at":"$at","context":{"type":"playlist","href":"https://api.spotify.com/v1/playlists/x","external_urls":{"spotify":""},"uri":"spotify:playlist:x"}}""" }}]}
    """.trimIndent()

    fun page(items: List<String>, next: String? = null, total: Int = items.size) =
        """{"href":"https://api.spotify.com/v1/x","limit":50,"next":${next?.let { "\"$it\"" } ?: "null"},"offset":0,"previous":null,"total":$total,"items":[${items.joinToString(",")}]}"""

    val topArtists = page(listOf(
        """{"external_urls":{"spotify":""},"followers":{"href":null,"total":0},"genres":["pop"],"href":"","id":"a1","images":[{"url":"https://i.scdn.co/image/w640","height":640,"width":640},{"url":"https://i.scdn.co/image/w320","height":320,"width":320}],"name":"The Weeknd","popularity":90,"type":"artist","uri":"spotify:artist:a1"}""",
        """{"external_urls":{"spotify":""},"href":"","id":"a2","images":[],"name":"Radiohead","type":"artist","uri":"spotify:artist:a2"}""",
    ))

    fun playlist(id: String, name: String, owner: String, total: Int, collaborative: Boolean = false, snapshot: String = "s1", ownerName: String = owner) = """
        {"collaborative":$collaborative,"description":"Made by $owner","external_urls":{"spotify":"https://open.spotify.com/playlist/$id"},
         "href":"https://api.spotify.com/v1/playlists/$id","id":"$id","images":[{"url":"https://mosaic.scdn.co/640/$id","height":640,"width":640}],
         "name":"$name","owner":{"external_urls":{"spotify":""},"href":"","id":"$owner","type":"user","uri":"spotify:user:$owner","display_name":"$ownerName"},
         "public":false,"snapshot_id":"$snapshot","items":{"href":"https://api.spotify.com/v1/playlists/$id/items","total":$total},
         "tracks":{"href":"https://api.spotify.com/v1/playlists/$id/tracks","total":$total},"type":"playlist","uri":"spotify:playlist:$id"}
    """.trimIndent()

    fun playlistItem(track: String, newShape: Boolean = true) =
        """{"added_at":"2025-01-02T10:00:00Z","added_by":{"id":"samr","type":"user","uri":"spotify:user:samr","href":"","external_urls":{}},"is_local":false,"${if (newShape) "item" else "track"}":$track}"""

    val episodeItem = """{"added_at":"2025-01-02T10:00:00Z","is_local":false,"item":{"type":"episode","uri":"spotify:episode:e1","name":"An episode","duration_ms":1800000}}"""
    val localItem = """{"added_at":"2025-01-02T10:00:00Z","is_local":true,"item":{"type":"track","uri":"spotify:local:::x:120","name":"x","is_local":true,"duration_ms":120000,"artists":[]}}"""

    fun saved(track: String, at: String) = """{"added_at":"$at","track":$track}"""

    fun search(vararg tracks: String) = """{"tracks":{"href":"","limit":5,"next":null,"offset":0,"previous":null,"total":${tracks.size},"items":[${tracks.joinToString(",")}]}}"""

    fun tokens(access: String = "acc-1", refresh: String? = "ref-1", expires: Int = 3600) =
        """{"access_token":"$access","token_type":"Bearer","scope":"user-read-recently-played user-top-read","expires_in":$expires${refresh?.let { ",\"refresh_token\":\"$it\"" } ?: ""}}"""

    /** A network that answers from [routes] (the first whose key the URL starts with) and notes every request. */
    class FakeSpotify(val routes: MutableMap<String, () -> Pair<Int, String>> = linkedMapOf()) {
        val requests = mutableListOf<Request>()
        val headers = mutableMapOf<String, String>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val r = chain.request()
            requests += r
            val url = r.url.toString()
            val (code, body) = routes.entries.firstOrNull { url.startsWith(it.key) }?.value?.invoke() ?: (404 to """{"error":{"status":404,"message":"no route $url"}}""")
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

        fun count(prefix: String) = requests.count { it.url.toString().startsWith(prefix) }
    }

    /** Tokens "sealed" by reversing them, plainly enough to check they're never stored as they are. */
    object ReverseBox : TokenBox {
        override fun seal(plain: String) = "sealed:" + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed()
    }
}

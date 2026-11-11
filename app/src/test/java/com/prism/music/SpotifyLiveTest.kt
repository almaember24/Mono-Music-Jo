package com.prism.music

import android.content.Context
import com.prism.music.data.spotify.SpotifyApi
import com.prism.music.data.spotify.SpotifyParse
import com.prism.music.data.spotify.SpotifyStore
import com.prism.music.data.spotify.SpotifyTokens
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.experimental.categories.Category

/**
 * The Spotify connection against Spotify itself (1.8.1): skipped unless a developer app's Client ID and a refresh token
 * from it are given (PRISM_SPOTIFY_CLIENT_ID, PRISM_SPOTIFY_REFRESH_TOKEN; a test account's, never printed). It renews
 * the token and reads every endpoint the features use, read-only (nothing is liked or saved), so a change on Spotify's
 * side shows here first. Spotify may hand back a new refresh token: it's only kept in this run's memory, so a second
 * run may need the newest one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@Category(LiveNetwork::class)
class SpotifyLiveTest {
    private val clientId = System.getenv("PRISM_SPOTIFY_CLIENT_ID")
    private val refresh = System.getenv("PRISM_SPOTIFY_REFRESH_TOKEN")
    private val app = RuntimeEnvironment.getApplication()
    private val auth = app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)

    @After fun clean() { auth.edit().clear().commit(); prefs.edit().clear().commit() }

    @Test fun everyEndpointTheFeaturesUseAnswers() = runBlocking {
        assumeTrue(!clientId.isNullOrBlank() && !refresh.isNullOrBlank())
        val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox)
        store.clientId = clientId!!
        // Expired at once: the first request renews it with the refresh token.
        store.saveTokens(SpotifyTokens("expired", refresh, 0))
        val api = SpotifyApi(okhttp3.OkHttpClient(), store)

        val me = SpotifyParse.profile(api.get("/me"))
        assertTrue(me.id.isNotBlank())
        SpotifyParse.recentlyPlayed(api.get("/me/player/recently-played", mapOf("limit" to "50")))
        SpotifyParse.topTracks(api.get("/me/top/tracks", mapOf("time_range" to "short_term", "limit" to "50")))
        SpotifyParse.topArtists(api.get("/me/top/artists", mapOf("time_range" to "short_term", "limit" to "50")))
        val lists = SpotifyParse.playlists(api.get("/me/playlists", mapOf("limit" to "50")))
        lists.items.firstOrNull { it.ownerId == me.id }?.let { p ->
            val items = SpotifyParse.playlistItems(api.get("/playlists/${p.id}/items", mapOf("limit" to "50", "additional_types" to "track", "fields" to com.prism.music.data.spotify.SpotifyPlaylists.ITEM_FIELDS)))
            assertTrue("the playlist's own songs (${p.total})", p.total == 0 || items.items.isNotEmpty())
            SpotifyParse.playlistSnapshot(api.get("/playlists/${p.id}", mapOf("fields" to "snapshot_id,name,description")))
        }
        SpotifyParse.savedTracks(api.get("/me/tracks", mapOf("limit" to "50")))
        val found = SpotifyParse.searchTracks(api.get("/search", mapOf("q" to "Creep artist:Radiohead", "type" to "track", "limit" to "5")))
        assertTrue("search finds Creep", found.any { it.title.startsWith("Creep") })
        assertTrue("ISRCs are still given", found.any { it.isrc != null })
    }
}

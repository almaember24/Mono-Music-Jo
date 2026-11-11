package com.prism.music

import android.content.Context
import com.prism.music.data.spotify.Spotify
import com.prism.music.data.spotify.SpotifyStore
import com.prism.music.data.spotify.SpotifyTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A Prism playlist's page (1.8.1): for someone who never connected Spotify, opening it doesn't build Prism's Spotify
 * (AppContainer's lazy `spotify`, here the lambda handed over), which it used to on the main thread for every playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotifyPageOpenedTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val auth = context.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)

    @After fun clear() {
        auth.edit().clear().commit()
        prefs.edit().clear().commit()
    }

    /** Counts how often the page asked for Spotify; never connected, it mustn't. */
    private fun builds(): Int {
        var built = 0
        Spotify.pageOpened(context, "prism:road-trip") { built++; error("Spotify was built for a playlist page") }
        return built
    }

    @Test
    fun aPlaylistPageDoesNotBuildSpotifyForSomeoneNeverConnected() {
        assertEquals(0, builds())
        // A Client ID pasted but never signed in is still not connected.
        SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox).clientId = "0123456789abcdef0123456789abcdef"
        assertEquals(0, builds())
    }

    @Test
    fun connectedItDoesAndRevokedItDoesNot() {
        val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox)
        store.saveTokens(SpotifyTokens("access", "refresh", Long.MAX_VALUE, "playlist-read-private"))
        assertTrue(SpotifyStore.connectedIn(auth))
        assertEquals(store.connected, SpotifyStore.connectedIn(auth))
        var asked = 0
        // Connected, the page asks for Spotify (its opened() then checks the playlist is a mirrored one).
        runCatching { Spotify.pageOpened(context, "prism:road-trip") { asked++; error("stand-in") } }
        assertEquals(1, asked)
        store.revoked = true
        assertFalse(SpotifyStore.connectedIn(auth))
        assertEquals(0, builds())
        store.clearTokens()
        assertFalse(SpotifyStore.connectedIn(auth))
    }
}

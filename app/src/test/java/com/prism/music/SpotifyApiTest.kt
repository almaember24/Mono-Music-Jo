package com.prism.music

import android.content.Context
import com.prism.music.data.Backup
import com.prism.music.data.BackupPart
import com.prism.music.data.spotify.SpotifyApi
import com.prism.music.data.spotify.SpotifyError
import com.prism.music.data.spotify.SpotifyFeature
import com.prism.music.data.spotify.SpotifyGuard
import com.prism.music.data.spotify.SpotifyStore
import com.prism.music.data.spotify.SpotifyTokens
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Spotify connection's requests and keeping (1.8.1): tokens renewed when they run out and kept sealed, access taken
 * back read as such, a 429's Retry-After honored by every later request, a 403 told apart, the kill switch, nothing
 * asked at all when not connected, Disconnect leaving nothing behind, and backups never carrying the tokens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotifyApiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val auth = app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)
    private val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox)
    private var clock = 1_000_000L
    private val net = SpotifyFixtures.FakeSpotify()
    private val api = SpotifyApi(net.client, store, now = { clock }, pause = { clock += it })

    @After fun clean() { auth.edit().clear().commit(); prefs.edit().clear().commit() }

    private fun connect(expiresAt: Long = clock + 3_600_000) {
        store.clientId = "0123456789abcdef0123456789abcdef"
        store.saveTokens(SpotifyTokens("acc-0", "ref-0", expiresAt))
    }

    @Test fun nothingIsAskedWhenNotConnected() = runBlocking {
        try { api.get("/me/player/recently-played"); fail() } catch (_: SpotifyError.NotConnected) {}
        assertEquals("no request at all", 0, net.requests.size)
        assertFalse(store.connected)
    }

    @Test fun theCodeIsSwappedForTokensAndTheAccount() = runBlocking {
        net.routes["https://accounts.spotify.com/api/token"] = { 200 to SpotifyFixtures.tokens("acc-1", "ref-1") }
        net.routes["https://api.spotify.com/v1/me"] = { 200 to SpotifyFixtures.me }
        val p = api.exchange("0123456789abcdef0123456789abcdef", "the-code", "http://127.0.0.1:5000/callback", "verifier-xyz")
        assertEquals("Sam Rivera", p.name)
        assertTrue(store.connected)
        assertEquals("samr", store.userId)
        val form = net.requests.first().body.let { b -> okio.Buffer().also { b!!.writeTo(it) }.readUtf8() }
        assertTrue(form, form.contains("code_verifier=verifier-xyz") && form.contains("grant_type=authorization_code") && !form.contains("client_secret"))
        assertEquals("Bearer acc-1", net.requests.last().header("Authorization"))
    }

    @Test fun tokensAreKeptSealed() {
        connect()
        val stored = File(app.filesDir.parentFile, "shared_prefs/${SpotifyStore.AUTH_PREFS}.xml").takeIf { it.exists() }?.readText()
            ?: auth.all.values.joinToString()
        assertFalse("the access token as it is", stored.contains("acc-0"))
        assertFalse("the refresh token as it is", stored.contains("ref-0"))
        assertEquals("acc-0", store.tokens()?.access)
    }

    @Test fun anExpiredTokenIsRenewedAndTheRefreshTokenKept() = runBlocking {
        connect(expiresAt = clock - 1)
        net.routes["https://accounts.spotify.com/api/token"] = { 200 to SpotifyFixtures.tokens("acc-2", refresh = null) }
        net.routes["https://api.spotify.com/v1/me"] = { 200 to SpotifyFixtures.me }
        api.get("/me")
        assertEquals(1, net.count("https://accounts.spotify.com/api/token"))
        val form = net.requests.first().body.let { b -> okio.Buffer().also { b!!.writeTo(it) }.readUtf8() }
        assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=ref-0") && form.contains("client_id=0123456789abcdef0123456789abcdef"))
        assertEquals("acc-2", store.tokens()?.access)
        assertEquals("kept", "ref-0", store.tokens()?.refresh)
        assertEquals("Bearer acc-2", net.requests.last().header("Authorization"))
    }

    @Test fun a401GetsANewTokenOnce() = runBlocking {
        connect()
        var first = true
        net.routes["https://accounts.spotify.com/api/token"] = { 200 to SpotifyFixtures.tokens("acc-3", "ref-3") }
        net.routes["https://api.spotify.com/v1/me"] = { if (first) { first = false; 401 to "{}" } else 200 to SpotifyFixtures.me }
        api.get("/me")
        assertEquals(3, net.requests.size)
        assertEquals("ref-3", store.tokens()?.refresh)
    }

    @Test fun takenBackAccessIsRevokedNotRetried() = runBlocking {
        connect(expiresAt = clock - 1)
        net.routes["https://accounts.spotify.com/api/token"] = { 400 to """{"error":"invalid_grant","error_description":"Refresh token revoked"}""" }
        try { api.get("/me"); fail() } catch (_: SpotifyError.Revoked) {}
        assertTrue(store.revoked)
        assertFalse(store.connected)
        val before = net.requests.size
        try { api.get("/me"); fail() } catch (_: SpotifyError.Revoked) {}
        assertEquals("not asked again", before, net.requests.size)
    }

    @Test fun a429sRetryAfterIsHonoredByEveryLaterRequest() = runBlocking {
        connect()
        net.headers["Retry-After"] = "120"
        net.routes["https://api.spotify.com/v1/me/top"] = { 429 to """{"error":{"status":429,"message":"Too many requests","reason":"QUOTA_EXCEEDED"}}""" }
        val e = runCatching { api.get("/me/top/tracks") }.exceptionOrNull() as SpotifyError.RateLimited
        assertEquals(120_000L, e.retryAfterMs)
        assertTrue(e.quota)
        val asked = net.requests.size
        assertTrue(runCatching { api.get("/me/playlists") }.exceptionOrNull() is SpotifyError.RateLimited)
        assertEquals("nothing sent while waiting", asked, net.requests.size)
        clock += 121_000
        net.headers.clear()
        net.routes["https://api.spotify.com/v1/me/playlists"] = { 200 to SpotifyFixtures.page(emptyList()) }
        api.get("/me/playlists")
        assertEquals(asked + 1, net.requests.size)
    }

    @Test fun forbiddenAndUnavailableAreToldApart() = runBlocking {
        connect()
        net.routes["https://api.spotify.com/v1/playlists/x/items"] = { 403 to """{"error":{"status":403,"message":"Forbidden"}}""" }
        net.routes["https://api.spotify.com/v1/me/tracks"] = { 503 to "" }
        assertTrue(runCatching { api.get("/playlists/x/items") }.exceptionOrNull() is SpotifyError.Forbidden)
        val e = runCatching { api.get("/me/tracks") }.exceptionOrNull()
        assertTrue(e is SpotifyError.Unavailable && e.code == 503)
    }

    @Test fun requestsAreSpaced() = runBlocking {
        connect()
        net.routes["https://api.spotify.com/v1/me"] = { 200 to SpotifyFixtures.me }
        val start = clock
        repeat(3) { api.get("/me") }
        assertTrue("at least two gaps: ${clock - start}", clock - start >= 2 * SpotifyApi.GAP_MS)
    }

    @Test fun nextLinksStayOnSpotifysApi() = runBlocking {
        connect()
        assertTrue(runCatching { api.next("https://evil.example.com/v1/me/tracks?offset=50") }.exceptionOrNull() is IllegalArgumentException)
        net.routes["https://api.spotify.com/v1/me/tracks"] = { 200 to SpotifyFixtures.page(emptyList()) }
        api.next("https://api.spotify.com/v1/me/tracks?offset=50&limit=50")
        assertEquals("50", net.requests.last().url.queryParameter("offset"))
    }

    @Test fun theKillSwitchPausesAFeatureForADay() {
        val logged = mutableListOf<String>()
        val guard = SpotifyGuard(prefs, SpotifyFeature.PLAYLISTS, now = { clock }, log = { logged += it })
        repeat(SpotifyGuard.LIMIT - 1) { assertFalse(guard.failed(SpotifyError.Unavailable(500, "x"))) }
        assertEquals(SpotifyGuard.State.NOT_ANSWERING, guard.state.value)
        assertTrue(guard.mayRun())
        assertTrue(guard.failed(SpotifyError.Unavailable(500, "x")))
        assertEquals(SpotifyGuard.State.PAUSED, guard.state.value)
        assertFalse(guard.mayRun())
        assertTrue(logged.last().contains("paused for a day"))
        // Kept across a restart.
        assertFalse(SpotifyGuard(prefs, SpotifyFeature.PLAYLISTS, now = { clock }).mayRun())
        // The others aren't touched.
        assertTrue(SpotifyGuard(prefs, SpotifyFeature.RECENT, now = { clock }).mayRun())
        clock += SpotifyGuard.DAY_MS + 1
        assertTrue(guard.mayRun())
        guard.succeeded()
        assertEquals(SpotifyGuard.State.OK, guard.state.value)
        assertEquals(0, guard.failures)
    }

    @Test fun aRateLimitPausesUntilRetryAfter() {
        val guard = SpotifyGuard(prefs, SpotifyFeature.RECENT, now = { clock })
        assertTrue(guard.failed(SpotifyError.RateLimited(30_000, quota = false)))
        assertFalse(guard.mayRun())
        clock += 30_001
        assertTrue(guard.mayRun())
    }

    @Test fun disconnectLeavesNothingBehind() {
        connect()
        store.userId = "samr"; store.userName = "Sam"
        prefs.edit().putBoolean("on_recent", true).putInt("guard_recent_failures", 3).commit()
        store.wipe(clientIdToo = false)
        assertFalse(store.connected)
        assertNull(store.tokens())
        assertNull(store.userId)
        assertTrue(auth.all.isEmpty())
        assertEquals("the Client ID stays unless asked", "0123456789abcdef0123456789abcdef", store.clientId)
        assertEquals(setOf("client_id"), prefs.all.keys)
        store.wipe(clientIdToo = true)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun backupsNeverCarryTheTokens() {
        assertNull("tokens: never", Backup.prefsPart(SpotifyStore.AUTH_PREFS))
        assertEquals(BackupPart.ACCOUNTS, Backup.prefsPart(SpotifyStore.PREFS))
        assertEquals(BackupPart.ACCOUNTS, Backup.filePart(com.prism.music.data.spotify.Spotify.DIR))
        assertEquals("spotify_auth", SpotifyStore.AUTH_PREFS)
    }
}

package com.prism.music

import com.prism.music.data.spotify.LoopbackReceiver
import com.prism.music.data.spotify.Pkce
import com.prism.music.data.spotify.SpotifyOAuth
import com.prism.music.data.spotify.SpotifyOAuth.Callback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spotify's sign-in (1.8.1): PKCE as RFC 7636 says (its own test vector), the authorize address with every parameter
 * Spotify asks for, the redirect's answer held to this sign-in's state, the token answers (a refresh without a new
 * refresh token keeps the old one), and the loopback receiver on the phone.
 */
class SpotifyAuthTest {
    @Test fun pkceMatchesRfc7636sTestVector() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun verifiersAreLongRandomAndOfTheAllowedCharacters() {
        val v = Pkce.verifier()
        assertEquals(64, v.length)
        assertTrue(v.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~" })
        assertFalse("random", v == Pkce.verifier())
        assertEquals(128, Pkce.verifier(128).length)
        assertTrue(runCatching { Pkce.verifier(42) }.isFailure)
        // A challenge is base64url without padding: 43 characters for SHA-256.
        assertEquals(43, Pkce.challenge(v).length)
        assertFalse(Pkce.challenge(v).contains('='))
    }

    @Test fun theAuthorizeAddressCarriesWhatSpotifyAsksFor() {
        val url = SpotifyOAuth.authorizeUrl("0123456789abcdef0123456789abcdef", SpotifyOAuth.redirectUri(45678), "st8", "chal-lenge")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        val q = SpotifyOAuth.parseQuery(url.substringAfter('?'))
        assertEquals("0123456789abcdef0123456789abcdef", q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://127.0.0.1:45678/callback", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("chal-lenge", q["code_challenge"])
        assertEquals("st8", q["state"])
        assertEquals(SpotifyOAuth.SCOPES.joinToString(" "), q["scope"])
        // Spaces as %20, not "+".
        assertTrue(url.contains("user-read-recently-played%20user-top-read"))
        // What the listener registers has no port (Spotify adds the request's own for loopback addresses).
        // With a port: Spotify's dashboard refused the bare address as "not secure" (owner, 2026-11-02).
        assertEquals("http://127.0.0.1:43827/callback", SpotifyOAuth.REDIRECT_TO_REGISTER)
        assertEquals(SpotifyOAuth.REDIRECT_TO_REGISTER, SpotifyOAuth.redirectUri(SpotifyOAuth.PORT))
    }

    @Test fun clientIdsAre32HexCharacters() {
        assertTrue(SpotifyOAuth.validClientId("0123456789abcdef0123456789ABCDEF"))
        assertTrue(SpotifyOAuth.validClientId(" 0123456789abcdef0123456789abcdef "))
        assertFalse(SpotifyOAuth.validClientId("0123456789abcdef"))
        assertFalse(SpotifyOAuth.validClientId("0123456789abcdef0123456789abcdeg"))
    }

    @Test fun theRedirectIsHeldToThisSignInsState() {
        assertEquals(Callback.Code("AQD-x"), SpotifyOAuth.parseCallback("code=AQD-x&state=abc", "abc"))
        assertEquals(Callback.WrongState, SpotifyOAuth.parseCallback("code=AQD-x&state=other", "abc"))
        assertEquals(Callback.WrongState, SpotifyOAuth.parseCallback("code=AQD-x", "abc"))
        assertEquals(Callback.WrongState, SpotifyOAuth.parseCallback("error=access_denied&state=other", "abc"))
        assertEquals(Callback.Refused("access_denied"), SpotifyOAuth.parseCallback("error=access_denied&state=abc", "abc"))
        assertEquals(Callback.Invalid, SpotifyOAuth.parseCallback("state=abc", "abc"))
        assertEquals(Callback.WrongState, SpotifyOAuth.parseCallback(null, "abc"))
        // Encoded values come back decoded.
        assertEquals(Callback.Code("a/b c"), SpotifyOAuth.parseCallback("code=a%2Fb%20c&state=x%3Dy", "x=y"))
    }

    @Test fun tokenFormsAreSpotifysPkceOnes() {
        val code = SpotifyOAuth.codeForm("cid", "the-code", "http://127.0.0.1:1/callback", "verifier")
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "the-code", "redirect_uri" to "http://127.0.0.1:1/callback", "client_id" to "cid", "code_verifier" to "verifier"), code)
        val refresh = SpotifyOAuth.refreshForm("cid", "r1")
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "r1", "client_id" to "cid"), refresh)
        assertFalse("no secret, ever", (code.keys + refresh.keys).contains("client_secret"))
    }

    @Test fun tokenAnswersAreRead() {
        val t = SpotifyOAuth.parseTokens(SpotifyFixtures.tokens("a1", "r1", 3600), now = 1_000_000)
        assertEquals("a1", t.access)
        assertEquals("r1", t.refresh)
        // A minute early.
        assertEquals(1_000_000 + 3540_000L, t.expiresAt)
        // A refresh without a new refresh token keeps the old one.
        assertEquals("old", SpotifyOAuth.parseTokens(SpotifyFixtures.tokens("a2", null), 0, keepRefresh = "old").refresh)
        assertEquals("invalid_grant", SpotifyOAuth.tokenError("""{"error":"invalid_grant","error_description":"Refresh token revoked"}"""))
        assertNull(SpotifyOAuth.tokenError("not json"))
    }

    @Test fun theLoopbackReceiverTakesTheCallbackAndSendsTheBrowserBack() {
        val pool = Executors.newSingleThreadExecutor()
        LoopbackReceiver("prism://spotify", "Done <here>", "Back").use { r ->
            val waiting = pool.submit<String?> { r.await(10_000) }
            // A favicon first: answered 404, and the wait goes on.
            fun ask(path: String): String = Socket("127.0.0.1", r.port).use { s ->
                s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                s.getInputStream().readBytes().decodeToString()
            }
            assertTrue(ask("/favicon.ico").startsWith("HTTP/1.1 404"))
            val answer = ask("/callback?code=abc&state=xyz")
            assertTrue(answer, answer.startsWith("HTTP/1.1 302"))
            assertTrue(answer.contains("Location: prism://spotify\r\n"))
            assertTrue("escaped", answer.contains("Done &lt;here&gt;"))
            assertEquals("code=abc&state=xyz", waiting.get(10, TimeUnit.SECONDS))
        }
        pool.shutdown()
    }

    @Test fun theLoopbackReceiverListensOnTheLoopbackOnlyAndTimesOut() {
        LoopbackReceiver("prism://spotify").use { r ->
            assertTrue(r.port > 0)
            assertNull(r.await(200))
        }
        assertEquals(null, LoopbackReceiver.requestTarget("POST /callback HTTP/1.1"))
        assertEquals("/callback", LoopbackReceiver.requestTarget("GET /callback HTTP/1.1")?.path)
    }
}

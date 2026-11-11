package com.prism.music.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636): the phone keeps a random verifier and sends only its SHA-256 (the challenge) to Spotify's authorize
 * page; the code that comes back is worth nothing without the verifier. No client secret is ever on the phone.
 */
object Pkce {
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val random = SecureRandom()

    /** A verifier of [length] characters (43 to 128, of the unreserved ones RFC 7636 allows). */
    fun verifier(length: Int = 64): String {
        require(length in 43..128)
        return buildString(length) { repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) } }
    }

    /** S256: base64url (no padding) of the verifier's SHA-256. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** A random state for one sign-in (checked when Spotify comes back, so another page can't hand Prism a code). */
    fun state(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

/** Tokens from Spotify's token endpoint. [expiresAt] is the phone's clock when the access token runs out. */
data class SpotifyTokens(val access: String, val refresh: String?, val expiresAt: Long, val scope: String = "")

/**
 * Spotify's official authorization (Authorization Code with PKCE), for the listener's own developer app ("bring your
 * own key"). The redirect is a loopback address on the phone ([LoopbackReceiver]) on a fixed port: Spotify's rules allow
 * `http://127.0.0.1` without a port, but its dashboard refused that as "not secure" (owner, 2026-11-02) while it takes
 * one with a port, like its own `http://127.0.0.1:8000/callback` example. Custom schemes are refused for new apps.
 */
object SpotifyOAuth {
    const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    const val TOKEN = "https://accounts.spotify.com/api/token"
    const val API = "https://api.spotify.com/v1"

    /** The phone's port the sign-in comes back to (high, unassigned, so another app is very unlikely to hold it). */
    const val PORT = 43827

    /** What the listener registers in the dashboard, exactly. */
    const val REDIRECT_TO_REGISTER = "http://127.0.0.1:$PORT/callback"

    /** The redirect for one sign-in, on the port the phone opened. */
    fun redirectUri(port: Int) = "http://127.0.0.1:$port/callback"

    /**
     * What Prism asks for: recently played (history), top items (taste), your playlists (read), Liked Songs (read, and
     * saving when "Liked Songs both ways" is on). Nothing that controls playback or changes playlists.
     */
    val SCOPES = listOf(
        "user-read-recently-played", "user-top-read", "playlist-read-private", "playlist-read-collaborative",
        "user-library-read", "user-library-modify",
    )

    /** A Client ID as the dashboard shows it: 32 hexadecimal characters. */
    fun validClientId(id: String): Boolean = Regex("[0-9a-fA-F]{32}").matches(id.trim())

    fun authorizeUrl(clientId: String, redirect: String, state: String, challenge: String, scopes: List<String> = SCOPES): String =
        AUTHORIZE + "?" + query(
            "client_id" to clientId.trim(), "response_type" to "code", "redirect_uri" to redirect,
            "code_challenge_method" to "S256", "code_challenge" to challenge, "state" to state,
            "scope" to scopes.joinToString(" "),
        )

    /** The token request's form fields for the code Spotify sent back. */
    fun codeForm(clientId: String, code: String, redirect: String, verifier: String): Map<String, String> = linkedMapOf(
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "client_id" to clientId.trim(), "code_verifier" to verifier,
    )

    /** The refresh request's form fields (PKCE: the Client ID, no secret). */
    fun refreshForm(clientId: String, refresh: String): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId.trim(),
    )

    /** What came back to the redirect. */
    sealed interface Callback {
        data class Code(val code: String) : Callback
        /** The listener said no, or Spotify refused ([error] as Spotify names it: "access_denied", "invalid_client"…). */
        data class Refused(val error: String) : Callback
        /** Not this sign-in's answer (another state, or none): ignored. */
        data object WrongState : Callback
        data object Invalid : Callback
    }

    /** Reads the redirect's query string ("code=…&state=…"), held to [expectedState]. */
    fun parseCallback(query: String?, expectedState: String): Callback {
        val q = parseQuery(query.orEmpty())
        val state = q["state"]
        // The state is checked first: an error from another page isn't this sign-in's either.
        if (state == null || !constantTimeEquals(state, expectedState)) return Callback.WrongState
        q["error"]?.let { return Callback.Refused(it) }
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return Callback.Invalid
        return Callback.Code(code)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The token endpoint's answer. A refresh may come without a new refresh token: then [keepRefresh] stays (Spotify:
     * "keep using the existing one").
     */
    fun parseTokens(body: String, now: Long, keepRefresh: String? = null): SpotifyTokens {
        val o = json.parseToJsonElement(body).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("no access token")
        val expires = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: keepRefresh
        // A minute early, so a request never goes out with a token about to run out.
        return SpotifyTokens(access, refresh, now + (expires - 60).coerceAtLeast(30) * 1000, o["scope"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** The token endpoint's error ("invalid_grant": the refresh token was taken back or ran out). */
    fun tokenError(body: String): String? = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    fun query(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun parseQuery(q: String): Map<String, String> = q.split("&").filter { it.isNotEmpty() }.associate { part ->
        val i = part.indexOf('=')
        val k = if (i < 0) part else part.substring(0, i)
        val v = if (i < 0) "" else part.substring(i + 1)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

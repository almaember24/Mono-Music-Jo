package com.prism.music.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Why a Spotify request didn't work. Each feature turns these into "Spotify isn't answering" or "Reconnect". */
sealed class SpotifyError(message: String) : IOException(message) {
    class NotConnected : SpotifyError("not connected")
    /** The refresh token was refused (the listener removed Prism's access, or the developer app was deleted). */
    class Revoked(why: String) : SpotifyError("access taken back: $why")
    /** Too many requests: not before [retryAfterMs] from now ([quota]: the app's quota for the day, July 2026). */
    class RateLimited(val retryAfterMs: Long, val quota: Boolean) : SpotifyError("rate limited for ${retryAfterMs / 1000} s")
    /**
     * 403: in Development Mode, an account that isn't the app's owner or in its User Management, a lapsed Premium on
     * the developer's side, or a playlist that isn't the listener's.
     */
    class Forbidden(why: String) : SpotifyError("refused: $why")
    class Unavailable(val code: Int, why: String) : SpotifyError("HTTP $code: $why")
}

/**
 * Spotify's Web API for the connected account: the access token added to each request and renewed when it runs out
 * (one refresh at a time), requests spaced at least [GAP_MS] apart, a 429's Retry-After honored by everyone, and a
 * refused refresh marked as taken back. Only accounts.spotify.com and api.spotify.com are ever asked.
 */
class SpotifyApi(
    private val http: OkHttpClient,
    private val store: SpotifyStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Spaces requests (a test passes a no-op). */
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val tokenLock = Mutex()
    private val paceLock = Mutex()
    @Volatile private var lastAt = 0L
    /** No request before this (a 429 said so). */
    @Volatile var quietUntil = 0L
        private set

    /** The code from the redirect, swapped for tokens (and the account they're for). */
    suspend fun exchange(clientId: String, code: String, redirect: String, verifier: String): SpotifyProfile {
        val t = tokenCall(SpotifyOAuth.codeForm(clientId, code, redirect, verifier), keepRefresh = null)
        store.saveTokens(t)
        val p = SpotifyParse.profile(get("/me"))
        store.userId = p.id
        store.userName = p.name
        return p
    }

    /** A token fit to use: the kept one, or a new one when it's about to run out. */
    private suspend fun accessToken(force: Boolean = false): String = tokenLock.withLock {
        if (store.revoked) throw SpotifyError.Revoked("earlier")
        val t = store.tokens() ?: throw SpotifyError.NotConnected()
        if (!force && t.expiresAt > now()) return t.access
        val refresh = t.refresh ?: run { store.revoked = true; throw SpotifyError.Revoked("no refresh token") }
        val fresh = tokenCall(SpotifyOAuth.refreshForm(store.clientId, refresh), keepRefresh = refresh)
        store.saveTokens(fresh)
        fresh.access
    }

    private suspend fun tokenCall(form: Map<String, String>, keepRefresh: String?): SpotifyTokens = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(SpotifyOAuth.TOKEN).post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (r.isSuccessful) return@withContext SpotifyOAuth.parseTokens(text, now(), keepRefresh)
            val error = SpotifyOAuth.tokenError(text)
            if (r.code == 429) throw rateLimited(r.header("Retry-After"), text)
            // invalid_grant: the refresh token (or the code) isn't good any more; invalid_client: the app is gone.
            if (error == "invalid_grant" || error == "invalid_client" || error == "unauthorized_client") {
                if (form["grant_type"] == "refresh_token") store.revoked = true
                throw SpotifyError.Revoked(error)
            }
            throw SpotifyError.Unavailable(r.code, error ?: "token request failed")
        }
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String = send("GET", path, query)

    /** PUT with the query only (Spotify's library takes its uris there). */
    suspend fun put(path: String, query: Map<String, String>): String = send("PUT", path, query)

    /** A "next" link from a page (already a whole address on api.spotify.com). */
    suspend fun next(url: String): String {
        val u = url.toHttpUrl()
        require(u.host == "api.spotify.com") { "not Spotify's API: ${u.host}" }
        return send("GET", u.encodedPath.removePrefix("/v1"), u.queryParameterNames.associateWith { u.queryParameter(it).orEmpty() })
    }

    private suspend fun send(method: String, path: String, query: Map<String, String>, retried: Boolean = false): String {
        pace()
        val token = accessToken()
        val url = (SpotifyOAuth.API + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            if (method == "PUT") put(ByteArray(0).toRequestBody(null))
        }.build()
        val (code, text, retryAfter) = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r -> Triple(r.code, r.body.string(), r.header("Retry-After")) }
        }
        return when {
            code in 200..299 -> text
            // The token ran out early (or was renewed elsewhere): once more with a new one.
            code == 401 && !retried -> { accessToken(force = true); send(method, path, query, retried = true) }
            code == 401 -> throw SpotifyError.Revoked("401 after a new token")
            code == 429 -> throw rateLimited(retryAfter, text)
            code == 403 -> throw SpotifyError.Forbidden(SpotifyParse.errorMessage(text) ?: "403")
            else -> throw SpotifyError.Unavailable(code, SpotifyParse.errorMessage(text) ?: "")
        }
    }

    private fun rateLimited(header: String?, body: String): SpotifyError.RateLimited {
        // Retry-After is in seconds; without one, a minute.
        val ms = (header?.trim()?.toLongOrNull() ?: 60L).coerceIn(1, 86_400) * 1000
        quietUntil = maxOf(quietUntil, now() + ms)
        return SpotifyError.RateLimited(ms, SpotifyParse.quotaExceeded(body))
    }

    /** At most one request every [GAP_MS], and none while a 429's wait lasts. */
    private suspend fun pace() = paceLock.withLock {
        val wait = quietUntil - now()
        if (wait > 0) throw SpotifyError.RateLimited(wait, false)
        val gap = lastAt + GAP_MS - now()
        if (gap > 0) pause(gap)
        lastAt = now()
    }

    companion object {
        const val GAP_MS = 350L
    }
}

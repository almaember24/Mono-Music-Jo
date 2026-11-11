package com.prism.music.data.recognize

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What Shazam said about a signature. */
sealed interface ShazamResult {
    data class Match(
        val title: String,
        val artist: String,
        val album: String?,
        val cover: String?,
        /** Shazam's own id for the track. */
        val key: String?,
        val isrc: String?,
        /** Where in the song the recording was, in ms (Shazam's offset), when it says. */
        val offsetMs: Long?,
    ) : ShazamResult
    data object NoMatch : ShazamResult
    /** Too many asks lately: try again in a while. */
    data object RateLimited : ShazamResult
}

/**
 * Asks Shazam which song a [ShazamSignature] is, the way its Android app does (as SongRec does): only the fingerprint
 * and its length go, with fixed stand-in values for the location and time zone, never the phone's own.
 */
class Shazam(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun recognize(signature: ShazamSignature): ShazamResult {
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            putJsonObject("geolocation") { put("altitude", 300); put("latitude", 45); put("longitude", 2) }
            putJsonObject("signature") {
                put("samplems", signature.sampleMs)
                put("timestamp", now.toInt())
                put("uri", signature.uri())
            }
            put("timestamp", now.toInt())
            put("timezone", "Europe/Paris")
        }
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}".toHttpUrl()
            .newBuilder()
            .addQueryParameter("sync", "true").addQueryParameter("webv3", "true").addQueryParameter("sampling", "true")
            .addQueryParameter("connected", "").addQueryParameter("shazamapiversion", "v3").addQueryParameter("sharehub", "true")
            .addQueryParameter("video", "v3")
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Content-Language", "en_US")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 429) return ShazamResult.RateLimited
            if (!r.isSuccessful) throw java.io.IOException(tr(R.string.shazam_answered_http, r.code))
            return parse(json.parseToJsonElement(r.body.string()).jsonObject)
        }
    }

    companion object {
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230901.001)"

        fun parse(o: JsonObject): ShazamResult {
            val matches = o.arr("matches")
            if (matches.isNullOrEmpty()) return ShazamResult.NoMatch
            val track = o.obj("track") ?: return ShazamResult.NoMatch
            val title = track.str("title") ?: return ShazamResult.NoMatch
            val album = track.arr("sections")?.firstNotNullOfOrNull { s ->
                (s as? JsonObject)?.arr("metadata")?.firstNotNullOfOrNull { m -> (m as? JsonObject)?.takeIf { it.str("title") == "Album" }?.str("text") }
            }
            val offset = (matches.firstOrNull() as? JsonObject)?.str("offset")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            return ShazamResult.Match(
                title = title,
                artist = track.str("subtitle") ?: "",
                album = album,
                cover = track.obj("images")?.str("coverarthq") ?: track.obj("images")?.str("coverart"),
                key = track.str("key"),
                isrc = track.str("isrc"),
                offsetMs = offset,
            )
        }
    }
}

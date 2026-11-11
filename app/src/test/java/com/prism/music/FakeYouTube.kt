package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A YouTube Music that answers from the test, for code that reads YouTube's answers: each request (its endpoint and
 * body) goes to [answer], which gives the JSON to send back and an HTTP code. Requests are kept in [sent].
 */
class FakeYouTube(signedIn: Boolean = true, private val answer: (endpoint: String, body: JsonObject) -> Pair<Int, String>) {
    val sent = mutableListOf<Pair<String, JsonObject>>()

    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val endpoint = req.url.encodedPath.substringAfter("/youtubei/v1/", "")
        val (code, text) = if (endpoint.isEmpty()) 200 to "<html></html>" else {
            val buf = okio.Buffer().also { req.body?.writeTo(it) }
            val body = InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject
            synchronized(sent) { sent += endpoint to body }
            answer(endpoint, body)
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }).build()

    private val settings = AppSettings(cookie = if (signedIn) "SAPISID=abc; __Secure-3PAPISID=abc" else "")
    val ytm = YouTubeMusic(InnerTube(http, { settings }))

    fun sentTo(endpoint: String) = synchronized(sent) { sent.filter { it.first == endpoint }.map { it.second } }
}

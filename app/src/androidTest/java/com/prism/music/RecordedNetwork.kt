package com.prism.music

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.security.MessageDigest

/** A test's own answer of any kind, [RecordedNetwork.byteAnswers]. */
class ByteAnswer(private val type: String, private val bytes: ByteArray, private val code: Int = 200) {
    fun respond(request: Request): Response {
        val from = request.header("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Test")
        if (code != 200 || from == null) return builder.code(code).body(bytes.toResponseBody(type.toMediaTypeOrNull())).build()
        if (from >= bytes.size) return builder.code(416).header("Content-Range", "bytes */${bytes.size}").body(ByteArray(0).toResponseBody(null)).build()
        return builder.code(206).header("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
            .body(bytes.copyOfRange(from, bytes.size).toResponseBody(type.toMediaTypeOrNull())).build()
    }
}

/**
 * Prism's network in the in-app tests: every request is answered from a recording in the test APK's assets
 * (`recorded/<key>.json`), the same on every run. A request with no recording gets a 503 ("offline"), logged under
 * PrismTestNet with its key, and is never sent. With [record] the requests go out for real and their answers are kept in
 * [RECORDED] (audio and pictures aren't: tests play local files and don't look at covers).
 *
 * A request's key is its method, host and path, plus what picks the answer out: the query string without the API key,
 * or for YouTube's JSON requests the fields that name what's asked for (browseId, query, videoId, params, continuation,
 * playlistId, input); anything else in the body (visitor data, client version) changes from run to run.
 */
class RecordedNetwork(private val record: Boolean) : Interceptor {
    @Serializable
    private class Recording(val code: Int, val type: String? = null, val body: String, val base64: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Keys asked for that had no recording, for a test to report. */
    val misses: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = keyOf(request)
        // A test's own answers by what's asked for (a playlist's pages share one address).
        answering?.let { answer ->
            answer(request.url.toString(), request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty())?.let { body ->
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                    .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()
            }
        }
        // A test's own answer comes first (GitHub announcing a newer Prism, for one test only).
        answers.entries.firstOrNull { (url, _) -> request.url.toString().startsWith(url) }?.let { (_, body) ->
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()
        }
        // And its own answer to one of YouTube's pages, by what's asked for (no account here has what those tests need).
        pageAsked(request)?.let { body ->
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()
        }
        byteAnswers.entries.firstOrNull { (url, _) -> request.url.toString().startsWith(url) }?.let { (_, answer) ->
            asked += request
            return answer.respond(request)
        }
        if (record) return chain.proceed(request).also { keep(key, it) }
        val saved = runCatching {
            instrumentation.context.assets.open("recorded/$key.json").use { json.decodeFromString(Recording.serializer(), it.readBytes().decodeToString()) }
        }.getOrNull()
        if (saved == null) {
            misses += key
            Log.w(TAG, "no recording: $key (${request.method} ${request.url})")
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("Offline (test)")
                .body("".toResponseBody(null)).build()
        }
        val bytes = if (saved.base64) Base64.decode(saved.body, Base64.NO_WRAP) else saved.body.encodeToByteArray()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(saved.code).message("Recorded")
            .body(bytes.toResponseBody(saved.type?.toMediaTypeOrNull())).build()
    }

    private fun keep(key: String, response: Response) {
        val type = response.body.contentType()
        val kind = type?.let { "${it.type}/${it.subtype}" }.orEmpty()
        if (kind.startsWith("audio/") || kind.startsWith("video/") || kind.startsWith("image/")) return
        val bytes = response.peekBody(MAX_RECORDING).bytes()
        if (bytes.size >= MAX_RECORDING) return
        val text = type?.charset() != null || kind.contains("json") || kind.startsWith("text/") || kind.contains("xml") || kind.contains("javascript")
        // Google API keys out (YouTube's pages carry their web client's key): the recordings are published in the repo,
        // where GitHub's secret scanning flags one. Prism never reads a key from an answer.
        val rec = Recording(response.code, type?.toString(), if (text) GOOGLE_KEY.replace(bytes.decodeToString(), "REDACTED_GOOGLE_API_KEY") else Base64.encodeToString(bytes, Base64.NO_WRAP), !text)
        // To /sdcard through the test's shell (Prism's own files are cleared before the next test); tools/record.ps1
        // pulls them into src/androidTest/assets/recorded/.
        val ui = instrumentation.uiAutomation
        ui.executeShellCommand("mkdir -p $RECORDED").close()
        // One word after -c (the shell splits on spaces), and nothing echoed back: `tee` echoed, and with nobody reading
        // that while the answer was written, both ends waited on a full pipe.
        val (out, input) = ui.executeShellCommandRw("sh -c cat>$RECORDED/$key.json")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(json.encodeToString(Recording.serializer(), rec).encodeToByteArray()) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        Log.i(TAG, "recorded: $key")
    }

    companion object {
        private const val TAG = "PrismTestNet"
        private const val MAX_RECORDING = 4L * 1024 * 1024
        const val RECORDED = "/sdcard/prism-tests/recorded"
        private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_-]{35}")

        /** Answers a test sets for itself, by the start of the address (each test runs in a fresh app). */
        val answers: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        /** Answers a test sets for itself to YouTube's `browse`, by the browseId or continuation asked for. */
        val pages: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        /** A test's answer from a request's address and body (null: the usual answer). It may wait, as a slow page does. */
        @Volatile var answering: ((url: String, body: String) -> String?)? = null

        /** Like [answers], for what isn't JSON: a server that also answers ranges, the way ExoPlayer asks for audio. */
        val byteAnswers: MutableMap<String, ByteAnswer> = java.util.concurrent.ConcurrentHashMap()

        /** The requests [byteAnswers] answered, for a test to see what was sent (its headers). */
        val asked: MutableList<Request> = java.util.concurrent.CopyOnWriteArrayList()
        private val NAMING = listOf("browseId", "query", "videoId", "params", "continuation", "playlistId", "input")

        private fun pageAsked(request: Request): String? {
            if (pages.isEmpty() || !request.url.encodedPath.endsWith("/browse")) return null
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: return null
            val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            return (find(obj, "browseId") ?: find(obj, "continuation"))?.let { pages[it] }
        }

        fun keyOf(request: Request): String {
            val url = request.url
            val query = url.queryParameterNames.filter { it != "key" && it != "prettyPrint" && it != "t" }.sorted()
                .joinToString("&") { n -> "$n=${url.queryParameterValues(n).joinToString(",")}" }
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val naming = if (body.isEmpty()) "" else runCatching {
                val obj = Json.parseToJsonElement(body).jsonObject
                NAMING.mapNotNull { n -> find(obj, n)?.let { "$n=$it" } }.joinToString("&")
            }.getOrElse { body }
            val slug = (url.host.removePrefix("www.") + url.encodedPath).replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(60)
            return "${slug}_" + sha1("${request.method} ${url.host}${url.encodedPath}?$query#$naming").take(12)
        }

        /** The first [name] anywhere in [obj] (YouTube nests some of them in the context). */
        private fun find(obj: JsonObject, name: String): String? {
            (obj[name] as? JsonPrimitive)?.let { return it.content }
            for (v in obj.values) (v as? JsonObject)?.let { inner -> find(inner, name)?.let { return it } }
            return null
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

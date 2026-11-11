package com.prism.music.data.lyrics

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.playback.AudioDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Lyric translations, line by line, from Google Translate's keyless web endpoints (the ones its browser extensions use).
 * Only the lyrics' text is sent, and only when the listener has turned translations on.
 *
 * Google stops answering a network that asks for many songs in a short time (#198: a phone and a tablet on the same
 * Wi-Fi, both without translations after a dozen songs). So a finished translation is kept on the phone ([dir]) and
 * never asked for again; a refused endpoint rests for a while (longer each time) while the other one is used; and with
 * both resting, [translate] throws [Unavailable] so the lyrics can say so and try again later.
 */
class LyricsTranslator(private val http: OkHttpClient, private val dir: File? = null) {
    /** A song's lines in another language; [translations] lines up with the lines asked about (null: nothing to show). */
    @Serializable
    class Translation(val from: String?, val to: String, val translations: List<String?>) {
        /** The song is already in [to]: nothing worth showing. */
        val sameLanguage: Boolean get() = from != null && from.substringBefore('-').equals(to.substringBefore('-'), true)
    }

    /** Both endpoints are resting after Google refused them; worth asking again from [retryAtMs] (wall clock). */
    class Unavailable(val retryAtMs: Long) : IOException(tr(R.string.google_translate_isn_t_answering_right))

    private val cache = object : LinkedHashMap<String, Translation>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translation>?) = size > 40
    }

    /** One of Google's endpoints, resting after a refusal. */
    private class Endpoint(val name: String) {
        @Volatile var restingUntil = 0L
        @Volatile var rest = FIRST_REST_MS
        fun resting(now: Long) = now < restingUntil
        fun refused(now: Long, code: Int) {
            restingUntil = now + rest
            AudioDiagnostics.log("translate", "$name refused (HTTP $code); resting ${rest / 1000} s")
            rest = (rest * 2).coerceAtMost(MAX_REST_MS)
        }
        fun answered() { rest = FIRST_REST_MS }
    }

    private val gtx = Endpoint("translate_a/single")
    private val dict = Endpoint("translate_a/t")

    private class Refused(val code: Int) : IOException(tr(R.string.translate_returned_http, code))

    /** Translates [lines] into [to] (a language code such as "es" or "pt-BR"). Blank lines stay null. */
    suspend fun translate(lines: List<String>, to: String): Translation = withContext(Dispatchers.IO) {
        val key = keyOf(lines, to)
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        load(key)?.let { saved -> synchronized(cache) { cache[key] = saved }; return@withContext saved }
        val wanted = lines.withIndex().filter { it.value.isNotBlank() }
        val out = arrayOfNulls<String>(lines.size)
        var from: String? = null
        // Google takes about 5000 characters at a time; lyrics rarely need more than one request.
        for (batch in batches(wanted.map { it.value.trim() })) {
            val (translated, detected) = ask(batch.texts, to)
            from = from ?: detected
            translated.forEachIndexed { i, t -> out[wanted[batch.start + i].index] = t?.trim()?.takeIf { it.isNotEmpty() } }
        }
        val result = Translation(from, to, out.toList())
        synchronized(cache) { cache[key] = result }
        // Kept only when (nearly) every line came back, so a half-refused song is asked again next time.
        val missing = wanted.count { out[it.index] == null }
        if (missing <= wanted.size / 10) save(key, result)
        result
    }

    /**
     * One batch, as lines lined up with [texts], and the language Google detected: the main endpoint first (one request,
     * lines joined by newlines), else the other one (each line its own field, so lines never merge or split).
     */
    private suspend fun ask(texts: List<String>, to: String): Pair<List<String?>, String?> {
        val now = System.currentTimeMillis()
        if (!gtx.resting(now)) {
            try {
                val (joined, detected) = requestJoined(texts, to)
                gtx.answered()
                // Google sometimes merges or splits lines; then the batch is asked again a line at a time (Reddit, 1.7.7),
                // rather than the whole translation being dropped.
                return (if (joined.size == texts.size) joined else lineByLine(texts, to)) to detected
            } catch (e: Refused) {
                gtx.refused(now, e.code)
            }
        }
        if (!dict.resting(now)) {
            try {
                return requestEach(texts, to).also { dict.answered() }
            } catch (e: Refused) {
                dict.refused(now, e.code)
            }
        }
        throw Unavailable(minOf(gtx.restingUntil, dict.restingUntil))
    }

    /** Each line on its own, a few at a time; a line that can't be translated stays null. */
    private suspend fun lineByLine(texts: List<String>, to: String): List<String?> = coroutineScope {
        val gate = Semaphore(LINE_REQUESTS)
        texts.map { text ->
            async { gate.withPermit { runCatching { requestJoined(listOf(text), to).first.joinToString(" ") }.getOrNull() } }
        }.awaitAll()
    }

    private class Batch(val start: Int, val texts: List<String>)

    private fun batches(texts: List<String>): List<Batch> {
        val out = ArrayList<Batch>()
        var start = 0
        var size = 0
        for (i in texts.indices) {
            if (size + texts[i].length + 1 > 4500 && i > start) {
                out += Batch(start, texts.subList(start, i)); start = i; size = 0
            }
            size += texts[i].length + 1
        }
        if (start < texts.size) out += Batch(start, texts.subList(start, texts.size))
        return out
    }

    /** One request: the lines joined by newlines (which Google keeps), and the language it detected. */
    private fun requestJoined(texts: List<String>, to: String): Pair<List<String>, String?> {
        val req = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$to&dt=t")
            .post(FormBody.Builder().add("q", texts.joinToString("\n")).build())
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Refused(resp.code)
            val root = Json.parseToJsonElement(resp.body.string()).jsonArray
            val text = (root.getOrNull(0) as? JsonArray).orEmpty().joinToString("") { seg ->
                ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: ""
            }
            val detected = (root.getOrNull(2) as? JsonPrimitive)?.contentOrNull
            return text.trimEnd('\n').split("\n") to detected
        }
    }

    /** The Chrome extension's endpoint: every line its own "q", answered as [[translation, language], …] in order. */
    private fun requestEach(texts: List<String>, to: String): Pair<List<String?>, String?> {
        val form = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val req = Request.Builder()
            .url("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$to")
            .post(form)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Refused(resp.code)
            val root = Json.parseToJsonElement(resp.body.string()).jsonArray
            val lines = root.map { item ->
                when (item) {
                    is JsonArray -> (item.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                    is JsonPrimitive -> item.contentOrNull
                    else -> null
                }
            }
            val detected = ((root.firstOrNull() as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.contentOrNull
            return (if (lines.size == texts.size) lines else List(texts.size) { null }) to detected
        }
    }

    // ---- Kept on the phone

    private fun keyOf(lines: List<String>, to: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((to + "\n" + lines.joinToString("\n")).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun load(key: String): Translation? {
        val f = dir?.let { File(it, "$key.json") }?.takeIf { it.isFile } ?: return null
        return runCatching { JSON.decodeFromString(Translation.serializer(), f.readText()) }.getOrNull()
            ?.also { f.setLastModified(System.currentTimeMillis()) }
    }

    private fun save(key: String, t: Translation) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            File(d, "$key.json").writeText(JSON.encodeToString(Translation.serializer(), t))
            // The songs translated longest ago go first past a few hundred (each file is a few kilobytes).
            val all = d.listFiles().orEmpty()
            if (all.size > KEEP) all.sortedBy { it.lastModified() }.take(all.size - KEEP).forEach { it.delete() }
        }
    }

    private companion object {
        /** Requests at once when a song is translated a line at a time. */
        const val LINE_REQUESTS = 4
        /** An endpoint's first rest after a refusal, doubling each time up to [MAX_REST_MS]. */
        const val FIRST_REST_MS = 60_000L
        const val MAX_REST_MS = 30 * 60_000L
        /** Translated songs kept on the phone. */
        const val KEEP = 500
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

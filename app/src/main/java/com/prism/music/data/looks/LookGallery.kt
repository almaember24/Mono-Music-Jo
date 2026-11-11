package com.prism.music.data.looks

import com.prism.music.R
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** One Look in the community gallery's index (themes/index.json in Prism's GitHub repository). */
@Serializable
data class GalleryEntry(
    val id: String,
    val name: String,
    /** Who made it (their GitHub name). */
    val by: String = "",
    val note: String = "",
    /** The Look as a code (`PRISMLOOK1:…`): every entry has one, so its card can be drawn without a download. */
    val code: String,
    /** A `.prismlook` file beside the index, for a Look with its own photos or font; downloaded only when it's used. */
    val file: String? = null,
)

@Serializable
private data class GalleryIndex(val format: Int = 1, val looks: List<GalleryEntry> = emptyList())

/** A gallery Look: its entry, and the Look its code makes (checked like any shared Look). */
data class GalleryLook(val entry: GalleryEntry, val look: Look)

/**
 * The community gallery (1.7.3): Looks people add to Prism's GitHub repository (themes/, by pull request, each one
 * looked at before it's added). Prism downloads the index only when the listener opens the gallery (and again after
 * [MAX_AGE_MS]); GitHub sees a download, nothing else is sent. Each Look is checked exactly as a shared one is.
 */
class LookGallery(private val http: OkHttpClient, private val dir: File, private val kinds: () -> Map<String, Char>) {
    private val file get() = File(dir, "gallery.json")
    @Volatile private var cached: List<GalleryLook>? = null

    /** The gallery's Looks; [refresh] asks GitHub even when the copy on the phone is recent. */
    suspend fun looks(refresh: Boolean = false): List<GalleryLook> = withContext(Dispatchers.IO) {
        if (!refresh) cached?.let { return@withContext it }
        val stale = refresh || !file.isFile || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
        if (stale) runCatching {
            val text = get("$RAW/index.json", MAX_INDEX)
            parse(text, kinds()) // only a readable index replaces the one kept
            dir.mkdirs()
            file.writeText(text)
        }.onFailure { if (!file.isFile) throw it }
        parse(file.readText(), kinds()).also { cached = it }
    }

    /** [g]'s whole Look: from its code, or its .prismlook file (with photos and font) when it has one. */
    suspend fun fetch(g: GalleryLook, store: LookStore): Look = withContext(Dispatchers.IO) {
        val name = g.entry.file ?: return@withContext g.look
        val bytes = getBytes("$RAW/$name", LookStore.MAX_FILE + 64 * 1024)
        store.readFile(bytes.inputStream()).copy(name = g.look.name, note = g.look.note)
    }

    private fun get(url: String, max: Long): String = getBytes(url, max).decodeToString()

    private fun getBytes(url: String, max: Long): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(tr(R.string.github_answered_http, resp.code))
            val body = resp.body
            if (body.contentLength() > max) throw IOException(tr(R.string.that_s_too_big_for_a))
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IOException(tr(R.string.that_s_too_big_for_a))
                }
            }
            out.toByteArray()
        }

    companion object {
        /** Where the gallery lives: Prism's repository on GitHub. */
        const val RAW = "https://raw.githubusercontent.com/Yigder/Prism/main/themes"
        /** Where to read how to add a Look. */
        const val ADD_YOURS = "https://github.com/Yigder/Prism/tree/main/themes"
        const val MAX_AGE_MS = 6L * 3600 * 1000
        private const val MAX_INDEX = 1L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")

        /**
         * The index's Looks that can be used: a sensible id (once), a code that reads as a Look, and a file (if any) named
         * `<id>.prismlook`. Names and notes are kept short; anything else is skipped rather than failing the gallery.
         */
        fun parse(text: String, kinds: Map<String, Char>): List<GalleryLook> {
            val index = json.decodeFromString(GalleryIndex.serializer(), text)
            if (index.format > 1) throw IOException(tr(R.string.the_gallery_needs_a_newer_prism))
            val seen = HashSet<String>()
            return index.looks.mapNotNull { e ->
                if (!ID.matches(e.id) || !seen.add(e.id)) return@mapNotNull null
                if (e.file != null && e.file != "${e.id}.${LookStore.FILE_EXTENSION}") return@mapNotNull null
                val look = LookCode.decode(e.code, kinds) ?: return@mapNotNull null
                val by = e.by.filter { it.isLetterOrDigit() || it in "-_ ." }.take(39)
                val note = e.note.filter { !it.isISOControl() }.take(80)
                GalleryLook(
                    e.copy(by = by, note = note),
                    look.copy(
                        id = "gallery-${e.id}", name = e.name.filter { !it.isISOControl() }.trim().take(Look.MAX_NAME).ifBlank { look.name },
                        note = listOfNotNull(by.takeIf { it.isNotBlank() }?.let { tr(R.string.by_2, it) }, note.takeIf { it.isNotBlank() }).joinToString(" · "),
                    ),
                )
            }
        }
    }
}

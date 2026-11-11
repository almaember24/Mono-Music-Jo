package com.prism.music.data

import android.content.Context
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.prism.music.data.model.hiRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers kept on the phone (#54, #55): downloaded songs' artwork (a lossless file's own embedded cover when it has
 * one), and the library's playlists' and albums', saved as files so they show offline: on the player and its blurred
 * backdrop, in lists, on the notification and on widgets. Any image request for a saved cover is answered from its
 * file, whatever size it asks for ([OfflineArtInterceptor]), so they also cost no data once saved.
 */
class ArtworkStore(
    context: Context,
    private val http: OkHttpClient,
    /** Waits until saving won't compete with the music for the network. */
    private val quiet: suspend () -> Unit = {},
) {
    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }
    private val saved: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        // A cover saved under a key many covers used to share (every YouTube Music mix's) is dropped; they're saved again.
        SHARED_OLD_KEYS.forEach { File(dir, "$it.jpg").delete() }
        dir.list()?.filter { it.endsWith(".jpg") }?.forEach { add(it.removeSuffix(".jpg")) }
    }
    private val gate = Semaphore(3)

    /** The saved file for this cover, if there is one. Cheap: it's a lookup in memory. */
    fun fileFor(url: String?): File? = keyOf(url)?.takeIf { it in saved }?.let { File(dir, "$it.jpg") }

    fun has(url: String?): Boolean = keyOf(url)?.let { it in saved } == true

    /** Saved at about [size] px or more (a video's thumbnail, at most 480 px wide, counts as soon as it's saved). */
    fun hasFull(url: String?, size: Int = 1080): Boolean {
        val key = keyOf(url)?.takeIf { it in saved } ?: return false
        return url!!.contains("ytimg.com/vi") || widthOf(key) >= size * 3 / 4
    }

    /** Saves the cover at [url] (asked for at [size] px), unless it's already here. True once it's on the phone. */
    suspend fun save(url: String?, size: Int = 1080): Boolean {
        val key = keyOf(url) ?: return false
        // A downloaded song from a library album shares its cover, which was kept at the library's 544 px: the player
        // showed that one stretched (#86). A bigger ask fetches it again at the bigger size.
        fun enough() = hasFull(url, size)
        if (enough()) return true
        return gate.withPermit {
            quiet()
            if (enough()) return@withPermit true
            withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(hiRes(url, size) ?: url!!).build()).execute().use { res ->
                        if (!res.isSuccessful) return@use false
                        val bytes = res.body.bytes()
                        bytes.isNotEmpty() && write(key, bytes)
                    }
                }.getOrDefault(false)
            }
        }
    }

    /** Keeps [bytes] (a cover embedded in a music file) as the cover for [url]. */
    fun saveBytes(url: String?, bytes: ByteArray): Boolean {
        val key = keyOf(url) ?: return false
        return write(key, bytes)
    }

    private val widths = ConcurrentHashMap<String, Int>()

    /** A saved cover's width in pixels (read from its header once). */
    private fun widthOf(key: String): Int = widths.getOrPut(key) {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(File(dir, "$key.jpg").path, o)
        o.outWidth.coerceAtLeast(0)
    }

    private fun write(key: String, bytes: ByteArray): Boolean {
        widths.remove(key)
        // Its own temporary name: two saves of the same cover (an album's songs finishing together) can overlap.
        val tmp = File(dir, "$key.${System.nanoTime()}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, "$key.jpg"))) { tmp.delete(); return false }
        saved += key
        return true
    }

    /** Deletes covers nothing on the phone uses any more ([keep]: every cover that should stay). */
    fun prune(keep: Collection<String?>) {
        val wanted = keep.mapNotNull(::keyOf).toSet()
        for (key in saved.toList()) if (key !in wanted) {
            File(dir, "$key.jpg").delete()
            saved -= key
        }
    }

    val bytesUsed: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /**
         * A cover's identity, whatever size it's asked for: Google's image addresses carry the size after "=", and
         * YouTube's thumbnails come in several names per video. Anything else is the whole address, query included:
         * YouTube Music's own mixes all have the same path (`music.youtube.com/image/mixart`) and differ only in
         * their `?r=…`, so leaving that out gave every mix one cover (My Supermix's). Null for what's on the phone.
         */
        fun keyOf(url: String?): String? {
            if (url.isNullOrBlank() || !url.startsWith("http")) return null
            // Every picture on screen asks (Coil runs its interceptors on the main thread), so the answer is kept: a
            // fresh SHA-1 and its hex for each cover of a long list showed up in a slow phone's stalls (#103).
            keys[url]?.let { return it }
            val bare = url.removePrefix("https://").removePrefix("http://")
            val base = when {
                bare.contains("googleusercontent.com") || bare.contains("ggpht.com") -> bare.substringBefore('=')
                bare.contains("ytimg.com/vi") -> "ytimg:" + bare.substringAfter("/vi").substringAfter('/').substringBefore('/')
                else -> bare
            }
            if (keys.size > MAX_KEYS) keys.clear()
            return sha1(base).also { keys[url] = it }
        }

        private const val MAX_KEYS = 4_000
        private val keys = ConcurrentHashMap<String, String>()
        private val HEX = "0123456789abcdef".toCharArray()

        private fun sha1(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            val out = CharArray(d.size * 2)
            for (i in d.indices) {
                val b = d[i].toInt() and 0xFF
                out[i * 2] = HEX[b ushr 4]
                out[i * 2 + 1] = HEX[b and 0x0F]
            }
            return String(out)
        }

        /**
         * Keys the old [keyOf] gave many different covers at once (every mix's): their files are one of those covers
         * shown for all of them, so they go. No cover gets one of these keys any more.
         */
        internal val SHARED_OLD_KEYS = listOf("music.youtube.com/image/mixart", "www.youtube.com/image/mixart").map(::sha1)
    }
}

/**
 * Data saver on mobile data (#40): pictures are asked of YouTube at the size they're shown at (at most [MAX_PX]), not
 * at the full size the app otherwise asks for, so a cover costs a few KB rather than a few hundred.
 */
class DataSaverInterceptor(private val active: () -> Boolean) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http") || !active()) return chain.proceed()
        val want = (chain.size.width as? coil3.size.Dimension.Pixels)?.px ?: MAX_PX
        val px = want.coerceIn(96, MAX_PX)
        val small = when {
            url.contains("googleusercontent.com") || url.contains("ggpht.com") -> url.substringBefore('=') + "=w$px-h$px-l70-rj"
            url.contains("ytimg.com/vi") -> url.replace(Regex("/(maxresdefault|sddefault|hqdefault|hq720)\\.jpg"), "/mqdefault.jpg")
            else -> return chain.proceed()
        }
        return chain.withRequest(chain.request.newBuilder().data(small).build()).proceed()
    }

    private companion object {
        const val MAX_PX = 400
    }
}

/** Answers image requests for saved covers from their files ([ArtworkStore]); the network is only asked if that fails. */
class OfflineArtInterceptor(private val store: () -> ArtworkStore?) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val url = when (data) {
            is String -> data
            is Uri -> data.toString()
            is coil3.Uri -> data.toString()
            else -> null
        } ?: return chain.proceed()
        val file = store()?.fileFor(url) ?: return chain.proceed()
        val local = chain.withRequest(chain.request.newBuilder().data(file).build()).proceed()
        return if (local is ErrorResult) chain.proceed() else local
    }
}

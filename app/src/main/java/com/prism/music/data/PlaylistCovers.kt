package com.prism.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** #305 (1.8.1): the song's picture as Prism shows it: the cover picked for it or its album, else its own. */
val com.prism.music.data.model.Song.art: String? get() = PlaylistCovers.artOf(this)

/**
 * Pictures the listener picked for their playlists (and for Liked songs and Downloads),
 * kept on this phone by playlist id: either a photo copied into the app or a song's cover.
 */
class PlaylistCovers(private val context: Context) {
    private val prefs = context.getSharedPreferences("playlist_covers", Context.MODE_PRIVATE)
    /** Compose state, so every cover on screen updates the moment one is changed. */
    private val covers = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) -> (v as? String)?.let { put(k, it) } }
    }
    val dir: File get() = File(context.filesDir, "covers")

    fun custom(id: String): String? = covers[id]

    /** The picture to show for playlist [id]: the listener's own if they picked one, else [fallback]. */
    fun art(id: String, fallback: String?): String? = covers[id] ?: fallback

    /**
     * Any item's picture, with a playlist's custom cover (or an artist's own photo, 1.7.6, or a song's or an album's,
     * #305) in place of YouTube's.
     */
    fun art(item: BrowseItem): String? = when (item) {
        is PlaylistItem -> art(item.id, item.thumbnail)
        is com.prism.music.data.model.ArtistItem -> covers["artist:${item.id}"] ?: item.thumbnail
        is com.prism.music.data.model.SongItem -> art(item.song)
        is com.prism.music.data.model.AlbumItem -> albumArt(item.id, item.thumbnail)
        else -> item.thumbnail
    }

    /** A song's own cover (#305, 1.8.1), kept under the song's id. */
    fun songKey(id: String) = "song:$id"

    /** An album's own cover (#305), kept under the album's id; its songs show it too. */
    fun albumKey(id: String) = "album:$id"

    /**
     * #305: the picture to show for [song] wherever it shows: the one picked for it, else the one picked for its album,
     * else the cover of the version picked to play in its place (#328), else its own. Read in a composable, it follows a
     * change at once.
     */
    fun art(song: com.prism.music.data.model.Song): String? = picked(song) ?: SongVersions.shared?.coverOf(song.id) ?: song.thumbnail

    /** The cover picked for [song] (or its album), if any. */
    fun picked(song: com.prism.music.data.model.Song): String? =
        // With no covers picked (most phones), no keys are made for each row drawn (the empty check is read too, so a
        // first pick still shows at once).
        if (covers.isEmpty()) null
        else covers[songKey(song.id)] ?: song.album?.id?.let { covers[albumKey(it)] }

    /** An album's picture: the one picked for it (#305), else [fallback]. */
    fun albumArt(id: String, fallback: String?): String? = covers[albumKey(id)] ?: fallback

    init { shared = this }

    companion object {
        /** The phone's covers, for what draws a song without the app's container at hand (the media session, a row). */
        @Volatile var shared: PlaylistCovers? = null
            internal set

        /** [art] for [song], or its own picture before the covers have been read. */
        fun artOf(song: com.prism.music.data.model.Song): String? = shared?.art(song) ?: song.thumbnail
    }

    private fun put(id: String, value: String) {
        val old = covers[id]
        covers[id] = value
        prefs.edit().putString(id, value).apply()
        old?.let(::deleteIfOurs)
    }

    /** One of the playlist's own songs' covers (a web address). */
    fun useImage(id: String, url: String) = put(id, url)

    /** Copies a picked photo into the app as a square, so it survives the original being moved or deleted. */
    suspend fun usePhoto(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bmp = runCatching { decode(uri) }.getOrNull() ?: return@withContext false
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = if (side > 1200) Bitmap.createScaledBitmap(square, 1200, 1200, true) else square
        dir.mkdirs()
        // A fresh name each time, so the new picture isn't served from the image cache.
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$safe-${System.currentTimeMillis()}.jpg")
        runCatching { file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrElse { return@withContext false }
        withContext(Dispatchers.Main) { put(id, Uri.fromFile(file).toString()) }
        true
    }

    /** A picture made in Prism (the cover maker), saved as a photo would be. */
    suspend fun useBitmap(id: String, bmp: Bitmap): Boolean = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$safe-${System.currentTimeMillis()}.jpg")
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.getOrElse { return@withContext false }
        withContext(Dispatchers.Main) { put(id, Uri.fromFile(file).toString()) }
        true
    }

    /** An artist's own photo (1.7.6), kept under the artist's id. */
    fun artistKey(id: String) = "artist:$id"

    fun reset(id: String) {
        val old = covers.remove(id) ?: return
        prefs.edit().remove(id).apply()
        deleteIfOurs(old)
    }

    private fun deleteIfOurs(value: String) {
        if (!value.startsWith("file:")) return
        val f = File(Uri.parse(value).path ?: return)
        if (f.canonicalPath.startsWith(dir.canonicalPath)) f.delete()
    }

    /** Decoded at a sensible size, the right way up (phone photos often carry a rotation). */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2400) d.setTargetSampleSize(longest / 2400 + 1)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

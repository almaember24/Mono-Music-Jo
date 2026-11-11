package com.prism.music.data.local

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves synced lyrics into your own music files (#16): Local files, and the lossless files Prism plays in place of
 * songs. Each song's best lyrics (saved, or found now) go into the file's tag ([LyricsTags]), so any player shows them.
 * Android asks once, for all of them, before files that other apps made can be changed. Prism's own downloads live
 * in its storage rather than as files, so there's nothing there to tag.
 */
class LyricsToFiles(private val context: Context, private val c: AppContainer) {
    class Target(val song: Song, val uri: Uri, val kind: LyricsTags.Kind)
    class Item(val target: Target, val text: String)

    private val _requests = MutableSharedFlow<List<Song>?>(extraBufferCapacity = 1)
    /** Asks for lyrics to be saved into these songs' files (null: every music file). The app's root carries it out. */
    val requests = _requests.asSharedFlow()

    fun request(songs: List<Song>?) { _requests.tryEmit(songs) }

    /** The files [songs] play from that can take lyrics (FLAC, MP3 and M4A); null: all of them. */
    fun targets(songs: List<Song>?): List<Target> {
        val local = c.localMusic.tracks.value.associateBy { it.song.id }
        fun of(s: Song): Target? {
            if (s.isLocal) {
                val t = local[s.id] ?: return null
                return LyricsTags.kindOf(t.mime)?.let { Target(s, t.uri, it) }
            }
            val t = c.localLossless.match(s) ?: return null
            return LyricsTags.kindOf(t.mime)?.let { Target(s, t.uri, it) }
        }
        val all = songs ?: (local.values.map { it.song } + c.downloads.downloads.value.values.filter { it.lossless }.mapNotNull { it.song })
        return all.mapNotNull(::of).distinctBy { it.uri }
    }

    /** Each file's lyrics text: saved ones, or looked up now when online (a few at a time). Files without any are left out. */
    suspend fun prepare(targets: List<Target>): List<Item> = coroutineScope {
        val online = c.network.isOnline()
        val s = c.settings.current
        // Settings → Lyrics & Sing → Your music files (1.7.9.2, Reddit): word timings and the translation, when asked for.
        val to = s.lyricsTranslateTo.ifBlank { com.prism.music.ui.player.defaultTranslateTarget() }
        val gate = Semaphore(4)
        targets.map { t ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val l = runCatching { c.lyrics.best(t.song, fetch = online) }.getOrNull()
                        ?.takeIf { it.lines.any { line -> !line.isGap && line.text.isNotBlank() } } ?: return@withPermit null
                    // Kept translations come back without the network (1.7.8); a song already in that language gets none.
                    val translations = if (!s.lyricsFileTranslation) null else runCatching {
                        c.translator.translate(l.lines.map { if (it.isGap) "" else it.text }, to).takeUnless { it.sameLanguage }?.translations
                    }.getOrNull()
                    Item(t, LyricsTags.text(l, words = s.lyricsFileWords, translations = translations))
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Android's own "allow Prism to modify these files?" (Android 11+), for every file at once. */
    fun writeRequest(items: List<Item>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, items.map { it.target.uri }).intentSender else null

    /** Writes each file's lyrics in, on the app's own scope (it carries on if the screen that asked goes). How many changed. */
    suspend fun write(items: List<Item>): Int = withContext(c.scope.coroutineContext + Dispatchers.IO) {
        recover()
        var done = 0
        for ((n, item) in items.withIndex()) {
            val src = File(work, "in"); val out = File(work, "out")
            try {
                context.contentResolver.openInputStream(item.target.uri)?.use { input -> src.outputStream().use { input.copyTo(it) } } ?: continue
                if (!LyricsTags.write(src, out, item.target.kind, item.text)) continue
                // The new file has to hold exactly the old one's audio, or it doesn't go back.
                if (!LyricsTags.audioIntact(src, out, item.target.kind)) continue
                // Until the new file is fully back in place, the original is kept (with where it goes), so a failed or
                // cut-short write can be put right, here or at the next launch ([recover]).
                val backup = File(work, "backup-$n")
                if (!src.renameTo(backup)) continue
                File(work, "backup-$n.uri").writeText("${item.target.uri}\n${out.length()}")
                val ok = runCatching {
                    context.contentResolver.openOutputStream(item.target.uri, "wt")?.use { o -> out.inputStream().use { it.copyTo(o) } } != null &&
                        sizeOf(item.target.uri) == out.length()
                }.getOrDefault(false)
                if (ok) done++ else restore(item.target.uri, backup)
                backup.delete(); File(work, "backup-$n.uri").delete()
            } catch (e: Exception) {
                Log.w("PrismLyricsTags", "Couldn't save lyrics into ${item.target.uri}: $e")
            } finally {
                src.delete(); out.delete()
            }
        }
        done
    }

    private val work get() = File(context.filesDir, "lyrics-tags").apply { mkdirs() }

    private fun sizeOf(uri: Uri): Long = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1

    private fun restore(uri: Uri, backup: File) {
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { o -> backup.inputStream().use { it.copyTo(o) } } }
            .onFailure { Log.w("PrismLyricsTags", "Couldn't put $uri back: $it") }
    }

    /**
     * A write that was cut short (Prism closed mid-way) left its original behind: if the file isn't the size the new
     * one should have been, the original goes back. Runs at launch and before each batch.
     */
    fun recover() {
        work.listFiles { f -> f.name.endsWith(".uri") }?.forEach { meta ->
            val backup = File(work, meta.name.removeSuffix(".uri"))
            val (uriText, size) = meta.readText().lines().let { it.getOrNull(0).orEmpty() to (it.getOrNull(1)?.toLongOrNull() ?: -1) }
            if (backup.exists() && uriText.isNotBlank()) {
                val uri = Uri.parse(uriText)
                if (runCatching { sizeOf(uri) }.getOrDefault(-1) != size) restore(uri, backup)
            }
            backup.delete(); meta.delete()
        }
    }
}

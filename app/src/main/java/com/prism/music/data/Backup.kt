package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.BuildConfig
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The parts a backup can hold (1.7.8, #149: settings can be shared without your listening or your sign-in). */
enum class BackupPart(val label: String, val detail: String) {
    SETTINGS(tr(R.string.settings_looks), tr(R.string.everything_you_ve_set_your_looks)),
    LIBRARY(tr(R.string.library), tr(R.string.prism_playlists_folders_and_pins_starred)),
    HISTORY(tr(R.string.listening_history_replay), tr(R.string.what_you_ve_played_likes_replay)),
    ACCOUNTS(tr(R.string.accounts), tr(R.string.your_youtube_music_sign_in_and)),
    DOWNLOADS(tr(R.string.downloaded_songs), tr(R.string.the_audio_itself_otherwise_they_re)),
}

/** What's written first in every backup. */
@Serializable
private data class BackupManifest(
    val format: Int = 1,
    val app: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val createdAt: Long = 0,
    val withDownloads: Boolean = false,
    /** Downloaded songs, so they can be fetched again when the audio itself isn't in the backup. */
    val downloads: List<Song> = emptyList(),
    /** The parts it holds ([BackupPart] names); null in backups made before 1.7.8, which hold everything. */
    val parts: List<String>? = null,
    /** "Bring your Prism" (1.8.0): a phone's listening for a TV, merged in there, never restored as a backup. */
    val bring: Boolean = false,
)

/**
 * Backs Prism up to one file the listener keeps (a zip: the library and Replay database, settings
 * and sign-in, saved preferences, custom covers and backgrounds; optionally the downloaded audio),
 * and restores one. A restore is staged and put in place on the next launch, before anything has
 * opened those files, then Prism restarts. Nothing leaves the phone except to where the listener saves it.
 * Since 1.7.8 the listener picks the [BackupPart]s; a restore replaces only those and leaves the rest.
 */
class Backup(private val c: AppContainer) {
    private val ctx: Context get() = c.app
    private val data: File get() = ctx.filesDir.parentFile!!

    /**
     * A backup any Prism made, whatever its app id: the test copy's, a copy renamed to show in a phone's own media
     * island (#204), the real one. Its manifest says it's Prism's, and a restore only ever takes Prism's own folders.
     */
    private fun fromPrism(m: BackupManifest) = m.app.isNotBlank()

    /** Writes a backup of [parts] to [uri]. */
    suspend fun export(uri: Uri, parts: Set<BackupPart>) = withContext(Dispatchers.IO) {
        // Fold Room's write-ahead log into the main file so the copy is complete.
        runCatching { c.db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close() }
        val withDownloads = BackupPart.DOWNLOADS in parts
        val manifest = BackupManifest(
            format = FORMAT, app = ctx.packageName, versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(), withDownloads = withDownloads,
            downloads = if (BackupPart.LIBRARY in parts || withDownloads) c.downloads.completedSongs() else emptyList(),
            parts = parts.map { it.name },
        )
        val settings = c.settings.forBackup(settings = BackupPart.SETTINGS in parts, accounts = BackupPart.ACCOUNTS in parts)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error(tr(R.string.couldn_t_open_that_file))
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(InnerTube.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            // Settings as values, not the store's own file: it also holds the sign-in, which only "Accounts" brings.
            if (settings.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
                zip.write(InnerTube.json.encodeToString(SETTINGS_MAP, settings).toByteArray())
                zip.closeEntry()
            }
            // Tags (1.8.3) live in the database (History) but belong to the library: they go as a file of their own.
            if (BackupPart.LIBRARY in parts) {
                zip.putNextEntry(ZipEntry(TAGS_ENTRY))
                zip.write(c.tags.exportJson(c.tags.export()).toByteArray())
                zip.closeEntry()
            }
            filesToBackUp(parts).forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(data).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** About how big a backup of [parts] would be, in bytes. */
    suspend fun estimate(parts: Set<BackupPart>): Long = withContext(Dispatchers.IO) { filesToBackUp(parts).sumOf { it.length() } }

    private fun filesToBackUp(parts: Set<BackupPart>): List<File> {
        val keep = mutableListOf<File>()
        File(data, "databases").listFiles()?.forEach { f ->
            if ((BackupPart.HISTORY in parts && f.name.startsWith(DB)) || (BackupPart.DOWNLOADS in parts && f.name.startsWith(EXO_DB))) keep += f
        }
        File(data, "shared_prefs").listFiles()?.forEach { f -> if (prefsPart(f.name.removeSuffix(".xml")) in parts) keep += f }
        ctx.filesDir.walkTopDown()
            .onEnter { dir -> dir == ctx.filesDir || filePart(dir.relativeTo(ctx.filesDir).invariantSeparatorsPath.substringBefore('/')) in parts }
            .filter { it.isFile && (it.parentFile != ctx.filesDir || filePart(it.name) in parts) }
            // A restore's tags not read in yet: the database's are written above instead.
            .filter { "files/" + it.relativeTo(ctx.filesDir).invariantSeparatorsPath != TAGS_ENTRY }
            .forEach { keep += it }
        return keep
    }

    /** The parts the backup at [uri] holds (every one for a backup made before 1.7.8), or null when it isn't one. */
    suspend fun partsOf(uri: Uri): Set<BackupPart>? = withContext(Dispatchers.IO) {
        runCatching {
            ZipInputStream((ctx.contentResolver.openInputStream(uri) ?: return@runCatching null).buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name != MANIFEST) continue
                    val m = InnerTube.json.decodeFromString(BackupManifest.serializer(), zip.readBytes().decodeToString())
                    if (!fromPrism(m)) return@runCatching null
                    return@runCatching m.partSet()
                }
                null
            }
        }.getOrNull()
    }

    /**
     * Checks the backup at [uri] and unpacks it to be put in place at the next launch, then
     * restarts Prism. Throws with a readable message if it isn't a Prism backup.
     */
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val staging = File(ctx.filesDir, STAGING)
        staging.deleteRecursively()
        staging.mkdirs()
        var manifest: BackupManifest? = null
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error(tr(R.string.couldn_t_open_that_file))
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name == MANIFEST) {
                        manifest = runCatching { InnerTube.json.decodeFromString(BackupManifest.serializer(), zip.readBytes().decodeToString()) }.getOrNull()
                        continue
                    }
                    // Only Prism's own folders, and nothing that climbs out of them.
                    if (name.contains("..") || name.startsWith("/") || (name != SETTINGS_ENTRY && ALLOWED.none { name.startsWith(it) })) continue
                    val target = File(staging, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            val m = manifest ?: error(tr(R.string.that_file_isn_t_a_prism_2))
            if (!fromPrism(m) || m.bring) error(tr(R.string.that_file_isn_t_a_prism_2))
            if (m.format > FORMAT) error(tr(R.string.that_backup_is_from_a_newer))
            File(staging, MANIFEST).writeText(InnerTube.json.encodeToString(BackupManifest.serializer(), m))
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        restart()
    }

    private fun restart() {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    /** After a restore: puts the backup's settings in place (a backup since 1.7.8 holds them as values; see [export]). */
    suspend fun applyRestoredSettings() {
        val s = File(ctx.filesDir, RESTORED_SETTINGS)
        if (!s.exists()) return
        runCatching {
            val r = InnerTube.json.decodeFromString(RestoredSettings.serializer(), s.readText())
            c.settings.fromBackup(r.values, settings = BackupPart.SETTINGS.name in r.parts, accounts = BackupPart.ACCOUNTS.name in r.parts)
        }
        s.delete()
    }

    /** After a restore: fetches the downloads again when their audio wasn't in the backup. */
    fun resumeDownloads() {
        val f = File(ctx.filesDir, REDOWNLOAD)
        if (!f.exists()) return
        val songs = runCatching { InnerTube.json.decodeFromString(BackupManifest.serializer(), f.readText()).downloads }.getOrDefault(emptyList())
        f.delete()
        c.downloads.downloadAll(songs)
    }

    // ------------------------------------------------------------ Bring your Prism (1.8.0)

    private fun prefsOf(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * What makes this Prism the listener's, for their TV, written to [out] in the backup's format (a zip with
     * prism-backup.json): listening history and likes, Prism playlists, folders and saved albums, starred artists, song
     * versions, lyric timings, best parts and exclusions ([BringOver]); [look]: their Look's code, when they chose it.
     * Never the sign-in, downloads' audio or the phone's settings.
     */
    suspend fun bringBundle(out: File, look: String?) = withContext(Dispatchers.IO) {
        runCatching { c.db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close() }
        val content = BringOver.collect(c.db, ::prefsOf, c.prismLibrary.forBringing(), look)
        out.outputStream().buffered().use { writeBring(it, content, ctx.packageName) }
    }

    /**
     * A phone's bundle ([bringBundle]) merged into this Prism (a TV's): added to what's here, nothing here wiped
     * ([BringOver.merge]); then what keeps those choices in memory reads them again. Throws when it isn't one.
     */
    suspend fun mergeBrought(file: File): BringMerged = withContext(Dispatchers.IO) {
        val content = file.inputStream().buffered().use { readBring(it) } ?: error(tr(R.string.bring_didn_t_check_out))
        val merged = BringOver.merge(content, c.db, ::prefsOf, c.prismLibrary)
        c.artistPrefs.reload()
        c.versions.reload()
        c.exclusions.reload()
        c.library.dislikedIds.value = prefsOf("dislikes").getStringSet("ids", emptySet()).orEmpty().toSet()
        content.look?.let { code -> c.looks.decode(code)?.let { c.looks.apply(it) } }
        merged
    }

    @Serializable
    private data class RestoredSettings(val parts: List<String>, val values: Map<String, String>)

    companion object {
        /** 2 (1.7.8): settings as values and a list of parts; Prism before 1.7.8 says "update first" to these. */
        private const val FORMAT = 2
        private const val MANIFEST = "prism-backup.json"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val STAGING = "restore_pending"
        private const val REDOWNLOAD = "restore_downloads.json"
        /** A restored backup's settings, waiting for the settings store to open after the restart. */
        private const val RESTORED_SETTINGS = "restore_settings.json"
        private const val DB = "prism.db"
        private const val EXO_DB = "exoplayer_internal.db"
        private val ALLOWED = listOf("databases/", "shared_prefs/", "datastore/", "files/")
        private val SETTINGS_MAP = MapSerializer(String.serializer(), String.serializer())

        private const val BRING_HISTORY = "bring/history.json"
        private const val BRING_PREFS = "bring/prefs.json"
        /** Prism's playlists in the library file's own format, at the library file's own place in a backup. */
        private const val BRING_LIBRARY = "files/library/prism.json"
        private const val BRING_LOOK = "bring/look.txt"
        /** The library's tags (1.8.3), read in at the next start ([TagStore.readPending]). */
        private const val TAGS_ENTRY = "files/library/" + TagStore.PENDING
        /** All a bundle may unpack to (its own size is held to [com.prism.music.connect.BringTransfer.MAX_BYTES]). */
        private const val BRING_UNPACKED_MAX = 128L * 1024 * 1024

        /** A "Bring your Prism" bundle ([bringBundle]): the backup's manifest, marked as one, and its entries. */
        fun writeBring(out: java.io.OutputStream, content: BringContent, app: String) {
            val manifest = BackupManifest(
                format = FORMAT, app = app, versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME,
                createdAt = System.currentTimeMillis(), parts = listOf(BackupPart.HISTORY.name, BackupPart.LIBRARY.name), bring = true,
            )
            ZipOutputStream(out).use { zip ->
                fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                put(MANIFEST, InnerTube.json.encodeToString(BackupManifest.serializer(), manifest))
                put(BRING_HISTORY, BringOver.historyJson(content.history))
                put(BRING_PREFS, BringOver.prefsJson(content.prefs))
                content.library?.let { put(BRING_LIBRARY, it) }
                content.look?.let { put(BRING_LOOK, it) }
            }
        }

        /**
         * A bundle read back, or null when it isn't one: no manifest of Prism's marked as a bundle, a newer format, an
         * entry that won't read, or more than [BRING_UNPACKED_MAX] unpacked. Entries it doesn't know are skipped.
         */
        fun readBring(input: java.io.InputStream): BringContent? = runCatching {
            var manifest: BackupManifest? = null
            var history: BringHistory? = null
            var prefs: Map<String, Map<String, String>>? = null
            var library: String? = null
            var look: String? = null
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || e.name !in setOf(MANIFEST, BRING_HISTORY, BRING_PREFS, BRING_LIBRARY, BRING_LOOK)) continue
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > BRING_UNPACKED_MAX) return@runCatching null
                        buf.write(chunk, 0, n)
                    }
                    val text = buf.toString(Charsets.UTF_8.name())
                    when (e.name) {
                        MANIFEST -> manifest = InnerTube.json.decodeFromString(BackupManifest.serializer(), text)
                        BRING_HISTORY -> history = BringOver.history(text) ?: return@runCatching null
                        BRING_PREFS -> prefs = BringOver.prefs(text) ?: return@runCatching null
                        BRING_LIBRARY -> library = text
                        BRING_LOOK -> look = text.take(64_000)
                    }
                }
            }
            val m = manifest ?: return@runCatching null
            if (m.app.isBlank() || !m.bring || m.format > FORMAT) return@runCatching null
            BringContent(history ?: BringHistory(), prefs.orEmpty(), library, look)
        }.getOrNull()

        /** Saved preferences by part; anything not named here counts as Settings. Some never go in a backup (null). */
        private val PREFS_PARTS: Map<String, BackupPart?> = buildMap {
            // playlist_covers (with files/covers below) holds songs' and albums' own covers too (#305, 1.8.1).
            listOf("artist_prefs", "artist_thumbs", "playlist_covers", "library_order", "blend", "liked_playlist", "recent_collections", "song_versions")
                .forEach { put(it, BackupPart.LIBRARY) }
            listOf(
                "dislikes", "exclusions", "for_you", "for_you_feedback", com.prism.music.data.foryou.Discover.PREFS, "release_radar", "search", "episode_progress", "episode_played", "best_part_mine",
                "mix_tempo", "song_loudness", "song_peak", "song_key", "dj_quick", "loudness", "lyric_offsets", "lyrics_choice", "lyrics_search_as",
                "romanize_songs", "video_sync", "local_album_tags", "local_lengths", "local_no_art", "lyrics_word_shift",
                // 1.8.1: Bring your history's choices (what Replay counts, what shapes the mixes).
                "imports",
                // 1.8.1: YouTube Music (live): its switch, its kill switch and the History page as last read.
                com.prism.music.data.imports.YouTubeLive.PREFS,
            ).forEach { put(it, BackupPart.HISTORY) }
            listOf(
                "canvas_saved", "download_lyrics", "download_failures", "download_auto_retry", "download_formats", "smart_downloads", "download_standins",
                "lossless_links", "lossless_misses", "lossless_hidden", "lossless_alac",
                // 1.8.2: songs skipped in lists' downloads (#221), when kept playlists were last read (#298).
                "download_skips", "kept_playlists",
            ).forEach { put(it, BackupPart.DOWNLOADS) }
            // The Last.fm connection (and its waiting scrobbles) is the listener's account.
            put("lastfm", BackupPart.ACCOUNTS)
            // 1.8.1: the Spotify connection's Client ID and switches go with the accounts (a Client ID is public in
            // PKCE); its tokens never travel, as Discord's sign-in doesn't (sealed by a key only this phone has anyway).
            put(com.prism.music.data.spotify.SpotifyStore.PREFS, BackupPart.ACCOUNTS)
            put(com.prism.music.data.spotify.SpotifyStore.AUTH_PREFS, null)
            // The Discord sign-in is a key to the whole Discord account; Prism Connect's pairings belong to this phone.
            listOf("discord", "connect_remote", "connect_handoff").forEach { put(it, null) }
            // The song cache's formats describe this phone's cache, which a backup doesn't carry.
            put("cache_formats", null)
            // What this sitting's mixes have played (1.8.2): over within hours, nothing a restored phone would want.
            put("session_heard", null)
            // Which install this is (RestoredInstall): restoring one's own backup elsewhere mustn't look like Android's.
            put(RestoredInstall.PREFS, null)
        }

        /** Folders (and files) right under files/ by part; anything else counts as Settings. */
        private val FILE_PARTS: Map<String, BackupPart?> = buildMap {
            listOf("library", "blends", "covers", "offline", "artwork").forEach { put(it, BackupPart.LIBRARY) }
            // 1.8.1: imports/ holds the library a data download is still bringing over (its plays are in the database).
            listOf("rewind", "waves", "queue.json", "imports").forEach { put(it, BackupPart.HISTORY) }
            put("account", BackupPart.ACCOUNTS)
            // What the Spotify connection read (which Prism playlists show which Spotify ones, the likes' sync state).
            put(com.prism.music.data.spotify.Spotify.DIR, BackupPart.ACCOUNTS)
            listOf("downloads", "canvas_saved").forEach { put(it, BackupPart.DOWNLOADS) }
            // Downloaded data files (models/) can always be downloaded again; stems are made again; staging is a restore's.
            listOf(STAGING, "models", "stems", REDOWNLOAD, RESTORED_SETTINGS).forEach { put(it, null) }
            // Lyric translations (1.7.8) are a cache of Google's answers, asked for again when missing.
            put("translations", null)
        }

        internal fun prefsPart(name: String): BackupPart? = if (name in PREFS_PARTS) PREFS_PARTS[name] else BackupPart.SETTINGS
        internal fun filePart(top: String): BackupPart? = if (top in FILE_PARTS) FILE_PARTS[top] else BackupPart.SETTINGS

        private fun BackupManifest.partSet(): Set<BackupPart> =
            parts?.mapNotNull { n -> BackupPart.entries.firstOrNull { it.name == n } }?.toSet()
                ?: (BackupPart.entries.toSet() - if (withDownloads) emptySet() else setOf(BackupPart.DOWNLOADS))

        /**
         * Puts a staged restore in place. Runs first thing at launch, before Room, DataStore or any
         * preferences are opened, so nothing holds the old files. Only the parts the backup holds are replaced.
         */
        fun applyPending(context: Context) {
            val files = context.filesDir
            val staging = File(files, STAGING)
            val manifestFile = File(staging, MANIFEST)
            if (!manifestFile.exists()) { staging.deleteRecursively(); return }
            runCatching {
                val m = InnerTube.json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
                val data = files.parentFile!!
                val whole = m.parts == null
                val parts = m.partSet()
                // Tags are the library's, kept in the database: a restore of the history alone keeps this phone's.
                if (BackupPart.HISTORY in parts && BackupPart.LIBRARY !in parts) TagStore.readFrom(File(data, "databases/$DB"))?.let { mine ->
                    File(files, "library").mkdirs()
                    File(files, "library/" + TagStore.PENDING).writeText(InnerTube.json.encodeToString(TagsFile.serializer(), mine))
                }
                // Out with what the backup replaces.
                File(data, "databases").listFiles()?.forEach {
                    if ((BackupPart.HISTORY in parts && it.name.startsWith(DB)) || (BackupPart.DOWNLOADS in parts && it.name.startsWith(EXO_DB))) it.delete()
                }
                File(data, "shared_prefs").listFiles()?.forEach { if (whole || prefsPart(it.name.removeSuffix(".xml")) in parts) it.delete() }
                // A backup from before 1.7.8 brings the settings store's own file; a newer one, its values (below).
                if (whole) File(data, "datastore").deleteRecursively()
                files.listFiles()?.forEach { f ->
                    if (f.name == STAGING) return@forEach
                    if (whole) { if (m.withDownloads || filePart(f.name) != BackupPart.DOWNLOADS) f.deleteRecursively() }
                    else if (filePart(f.name) in parts) f.deleteRecursively()
                }
                // In with the backup.
                val settingsFile = File(staging, SETTINGS_ENTRY)
                staging.walkTopDown().filter { it.isFile && it != manifestFile && it != settingsFile }.forEach { f ->
                    val target = File(data, f.relativeTo(staging).path)
                    target.parentFile?.mkdirs()
                    if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
                }
                // The settings wait for the store to open ([afterRestore]); "Settings" or "Accounts" without any values
                // still clears what they replace.
                if (!whole && (BackupPart.SETTINGS in parts || BackupPart.ACCOUNTS in parts)) {
                    val values = if (settingsFile.exists()) InnerTube.json.decodeFromString(SETTINGS_MAP, settingsFile.readText()) else emptyMap()
                    File(files, RESTORED_SETTINGS).writeText(InnerTube.json.encodeToString(RestoredSettings.serializer(), RestoredSettings(m.parts.orEmpty(), values)))
                }
                if (!m.withDownloads && m.downloads.isNotEmpty()) manifestFile.copyTo(File(files, REDOWNLOAD), overwrite = true)
            }
            staging.deleteRecursively()
        }
    }
}

package com.prism.music

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The fixed starting point of an in-app test. The orchestrator clears Prism's data before each test, so every test
 * starts from a fresh install; [ready] then puts Prism where a listener who has set it up would be (welcome screen and
 * tour done, the one-time offers already answered), and [launch] opens it.
 */
object Fixture {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val container: AppContainer get() = context.container

    /** Prism as set up: past the welcome screen and the tour, with nothing offered on the way in. */
    fun ready() = runBlocking {
        container.settings.editNow {
            it[booleanPreferencesKey("onboarded")] = true
            it[booleanPreferencesKey("performance_offered")] = true
            // A TV's welcome (1.8.0) seen too: tests start where a listener's day does.
            it[booleanPreferencesKey("tv_welcomed")] = true
        }
        com.prism.music.ui.tour.Tour.markSeen(context)
        // Holiday Mode (1.8.0) asked already, around whatever year the test's clock says.
        val year = com.prism.music.data.looks.Seasons.today().year
        val seasons = (year - 1..year + 1).flatMap { y -> com.prism.music.data.looks.Season.entries.map { "${it.name}:$y" } }.toSet()
        context.getSharedPreferences("prompts", Context.MODE_PRIVATE).edit().putBoolean("notifications_asked", true)
            .putStringSet("season_asked", seasons).commit()
        // Holiday mixes (1.8.0) out of season unless a test picks a date: in November or December their row leads
        // Home (phone and TV), which isn't what tests about other rows expect. A test that wants them sets it after.
        if (container.seasonal.clock == null) {
            val zone = java.time.ZoneId.systemDefault()
            container.seasonal.clock = java.time.Clock.fixed(java.time.LocalDateTime.of(2026, 7, 10, 12, 0).atZone(zone).toInstant(), zone)
        }
        // The settings Prism reads at start are its live copy of the store: wait until it has them.
        withTimeout(10_000) { while (!container.settings.current.onboarded) kotlinx.coroutines.delay(20) }
    }

    /** Songs that exist only in tests (ids no service knows), with a length and an artist like real ones. */
    fun songs(n: Int, prefix: String = "Test song") = (1..n).map { i ->
        com.prism.music.data.model.Song(
            id = "tst${i.toString().padStart(8, '0')}", title = "$prefix $i",
            artists = listOf(com.prism.music.data.model.ArtistRef("Test Artist", null)), durationSec = 180 + i,
        )
    }

    /** A Prism playlist (no account needed) holding [songs]; its id. */
    fun prismPlaylist(title: String, songs: List<com.prism.music.data.model.Song>): String = container.prismLibrary.create(title, songs)

    /**
     * [songs] as finished downloads, written into Media3's download index before Prism's downloads start (call it
     * before [launch]): pages and Downloads show them as downloaded, with no file behind them.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun downloaded(songs: List<com.prism.music.data.model.Song>) {
        val index = androidx.media3.exoplayer.offline.DefaultDownloadIndex(container.databaseProvider)
        val now = System.currentTimeMillis()
        for (s in songs) {
            val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(s.id, android.net.Uri.parse("prism://audio/${s.id}"))
                .setCustomCacheKey(s.id)
                .setData(com.prism.music.data.innertube.InnerTube.json.encodeToString(com.prism.music.data.model.Song.serializer(), s).toByteArray())
                .build()
            index.putDownload(
                androidx.media3.exoplayer.offline.Download(
                    request, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, now, now, 3_000_000L,
                    androidx.media3.exoplayer.offline.Download.STOP_REASON_NONE, androidx.media3.exoplayer.offline.Download.FAILURE_REASON_NONE,
                ),
            )
        }
    }

    /**
     * The screen as it is, to /sdcard/prism-tests/<name>.png (outside Prism's data, which the next test clears), for
     * a failure's report and the release check's screenshot tours.
     */
    fun screenshot(name: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("mkdir -p /sdcard/prism-tests").close()
        ui.executeShellCommand("screencap -p /sdcard/prism-tests/$name.png").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
    }

    /**
     * A silent WAV of [seconds] in Music/, as a music file the phone's media store lists under [title]. Android 10+
     * through the store itself; Android 9 has no RELATIVE_PATH, so the file is written there (the storage permission
     * Prism declares up to 9) and the media scanner lists it, its title from the file's name. Delete it with its uri.
     */
    fun tone(title: String, seconds: Int = 25): android.net.Uri {
        val rate = 8_000
        val samples = rate * seconds
        val data = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        data.write("RIFF".toByteArray()); le32(36 + samples * 2); data.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        data.write("data".toByteArray()); le32(samples * 2); data.write(ByteArray(samples * 2))
        if (android.os.Build.VERSION.SDK_INT < 29) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).apply { mkdirs() }
            val file = java.io.File(dir, "$title.wav").apply { writeBytes(data.toByteArray()) }
            val found = java.util.concurrent.LinkedBlockingQueue<android.net.Uri>()
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("audio/wav")) { _, uri -> uri?.let { found.put(it) } }
            return found.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: error("the media scanner didn't list $file")
        }
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$title.wav")
            put(android.provider.MediaStore.Audio.Media.TITLE, title)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(data.toByteArray()) }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Opens Prism as its launcher icon does. */
    fun launch(): ActivityScenario<PrismActivity> = ActivityScenario.launch(PrismActivity::class.java)
}

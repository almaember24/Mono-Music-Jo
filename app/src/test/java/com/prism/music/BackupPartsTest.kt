package com.prism.music

import com.prism.music.data.Backup
import com.prism.music.data.BackupPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Q10 (#149): a restore replaces only the parts the backup holds; a backup from before 1.7.8 still replaces all. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class BackupPartsTest {
    private val app = org.robolectric.RuntimeEnvironment.getApplication()
    private val files = app.filesDir
    private val data = files.parentFile!!

    private fun write(path: String, text: String) = File(data, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun read(path: String) = File(data, path).takeIf { it.exists() }?.readText()

    private fun phone() {
        write("shared_prefs/icon_style.xml", "old settings")
        write("shared_prefs/search.xml", "old searches")
        write("files/library/prism.json", "old library")
        write("files/looks/my-a.json", "old look")
        write("databases/prism.db", "old history")
        write("files/downloads/blob", "audio")
    }

    private fun stage(manifest: String, vararg files: Pair<String, String>) {
        write("files/restore_pending/prism-backup.json", manifest)
        files.forEach { (p, t) -> write("files/restore_pending/$p", t) }
    }

    @Test fun onlyTheBackupsPartsAreReplaced() {
        phone()
        stage(
            """{"format":2,"app":"${app.packageName}","parts":["SETTINGS"]}""",
            "shared_prefs/icon_style.xml" to "new settings",
            "files/looks/my-b.json" to "new look",
            "settings.json" to """{"theme_mode":"s:DARK"}""",
        )
        Backup.applyPending(app)
        assertEquals("new settings", read("shared_prefs/icon_style.xml"))
        assertEquals("new look", read("files/looks/my-b.json"))
        // Settings' own files go (the backup's replace them), everything else stays as it was.
        assertFalse(File(data, "files/looks/my-a.json").exists())
        assertEquals("old searches", read("shared_prefs/search.xml"))
        assertEquals("old library", read("files/library/prism.json"))
        assertEquals("old history", read("databases/prism.db"))
        assertEquals("audio", read("files/downloads/blob"))
        // The settings' values wait for the store to open.
        assertTrue(read("files/restore_settings.json")!!.contains("theme_mode"))
        assertFalse(File(files, "restore_pending").exists())
    }

    /**
     * 1.8.3's library: smart playlists live in the library file, the Songs view's playlists ("Songs from") in
     * library_order: both come back with Library. Traits (tempo, key, energy) and romanized titles are in the database:
     * they stay with History.
     */
    @Test fun the183LibraryComesBackWithLibrary() {
        phone()
        write("shared_prefs/library_order.xml", "old songs-from")
        stage(
            """{"format":2,"app":"${app.packageName}","parts":["LIBRARY"]}""",
            "files/library/prism.json" to """{"playlists":[{"id":"smart-1","smart":{"rules":[]}}]}""",
            "shared_prefs/library_order.xml" to "new songs-from",
        )
        Backup.applyPending(app)
        assertTrue("smart playlists", read("files/library/prism.json")!!.contains("smart-1"))
        assertEquals("the Songs view's playlists", "new songs-from", read("shared_prefs/library_order.xml"))
        assertEquals("traits stay with the history", "old history", read("databases/prism.db"))
        assertEquals("old settings", read("shared_prefs/icon_style.xml"))
    }

    @Test fun anOldBackupReplacesEverythingButDownloads() {
        phone()
        write("datastore/prism_settings.preferences_pb", "old store")
        stage(
            """{"format":1,"app":"${app.packageName}","withDownloads":false}""",
            "shared_prefs/icon_style.xml" to "new settings",
            "datastore/prism_settings.preferences_pb" to "new store",
            "databases/prism.db" to "new history",
        )
        Backup.applyPending(app)
        assertEquals("new settings", read("shared_prefs/icon_style.xml"))
        assertEquals("new store", read("datastore/prism_settings.preferences_pb"))
        assertEquals("new history", read("databases/prism.db"))
        assertFalse(File(data, "shared_prefs/search.xml").exists())
        assertFalse(File(data, "files/library/prism.json").exists())
        assertEquals("audio", read("files/downloads/blob"))
    }

    /** Settings travel as values; the sign-in only with "Accounts", and restoring settings alone keeps the sign-in here. */
    @Test fun settingsTravelWithoutTheSignIn(): Unit = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val settings = com.prism.music.data.prefs.SettingsRepository(app, scope)
        // The settings store is one per process: what this test writes is cleared after it, or later test classes
        // (LookTest) would find a signed-in account.
        try { settingsTravel(settings) } finally { settings.editNow { it.clear() } }
    }

    private suspend fun settingsTravel(settings: com.prism.music.data.prefs.SettingsRepository) {
        settings.saveAccount("SID=secret", "visitor", "sync")
        settings.editNow { it[androidx.datastore.preferences.core.booleanPreferencesKey("pure_black")] = true }
        val shared = settings.forBackup(settings = true, accounts = false)
        assertEquals("b:true", shared["pure_black"])
        assertFalse("the sign-in stays out", "cookie" in shared)
        assertTrue("cookie" in settings.forBackup(settings = false, accounts = true))
        // Restored elsewhere: the settings come in, the sign-in already here stays.
        settings.saveAccount("SID=mine", "visitor2", "sync2")
        settings.editNow { it[androidx.datastore.preferences.core.booleanPreferencesKey("pure_black")] = false }
        settings.fromBackup(shared, settings = true, accounts = false)
        val now = settings.forBackup(settings = true, accounts = true)
        assertEquals("b:true", now["pure_black"])
        assertEquals("s:SID=mine", now["cookie"])
    }

    /**
     * Tags (1.8.3) are the library's but live in the database: restoring the history alone keeps this phone's tags
     * (read from its database before the backup's replaces it, and read back in at the start); with the library they're
     * the backup's.
     */
    @Test fun historyAloneKeepsThisPhonesTags() {
        fun phoneWithTags() {
            app.deleteDatabase("prism.db")
            val db = androidx.room.Room.databaseBuilder(app, com.prism.music.data.db.PrismDatabase::class.java, "prism.db").allowMainThreadQueries().build()
            kotlinx.coroutines.runBlocking {
                com.prism.music.data.TagStore(db, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), File(files, "library"))
                    .add("gym", listOf(com.prism.music.data.model.Song("a", "Alpha")))
            }
            db.close()
            File(files, "library/tags.json").delete()
        }
        phoneWithTags()
        stage("""{"format":2,"app":"${app.packageName}","parts":["HISTORY"]}""", "databases/prism.db" to "new history")
        Backup.applyPending(app)
        assertEquals("new history", read("databases/prism.db"))
        val kept = read("files/library/tags.json")!!
        assertTrue(kept, kept.contains("gym") && kept.contains("Alpha"))

        phoneWithTags()
        stage("""{"format":2,"app":"${app.packageName}","parts":["HISTORY","LIBRARY"]}""", "databases/prism.db" to "new history", "files/library/tags.json" to """{"tags":[]}""")
        Backup.applyPending(app)
        assertEquals("the backup's tags", """{"tags":[]}""", read("files/library/tags.json"))
        app.deleteDatabase("prism.db")
    }

    @Test fun everyPrefsFileHasAPart() {
        assertEquals(BackupPart.HISTORY, Backup.prefsPart("search"))
        assertEquals(BackupPart.LIBRARY, Backup.prefsPart("artist_prefs"))
        assertEquals(BackupPart.SETTINGS, Backup.prefsPart("icon_style"))
        assertEquals(null, Backup.prefsPart("discord"))
        // Last session's songs, by id and name: not a setting, and over in six hours anyway.
        assertEquals(null, Backup.prefsPart("session_heard"))
        assertEquals(BackupPart.ACCOUNTS, Backup.filePart("account"))
        assertEquals(null, Backup.filePart("models"))
    }
}

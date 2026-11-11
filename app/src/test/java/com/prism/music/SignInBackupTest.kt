package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.Backup
import com.prism.music.data.RestoredInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sign-ins and Android's own backup (1.8.1): the sign-ins kept in files of their own never leave the phone that way,
 * and a restored copy of the YouTube Music sign-in (it lives with the settings) is signed out once at start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SignInBackupTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("install-test", Context.MODE_PRIVATE)

    @Test fun aRestoredCopySignsOutAndAnUpdateDoesNot() {
        prefs.edit().clear().commit()
        var signedOut = 0
        // The update that brings this in, and later starts of the same install: nothing to sign out.
        assertFalse(RestoredInstall.check(prefs, 1_000L) { signedOut++ })
        assertFalse(RestoredInstall.check(prefs, 1_000L) { signedOut++ })
        // The same files on another install (Android's backup restored): signed out once.
        assertTrue(RestoredInstall.check(prefs, 2_000L) { signedOut++ })
        assertFalse(RestoredInstall.check(prefs, 2_000L) { signedOut++ })
        assertEquals(1, signedOut)
    }

    @Test fun signInFilesAreLeftOutOfAndroidsBackup() {
        val signIns = listOf("discord.xml", "lastfm.xml", "connect_remote.xml", "connect_handoff.xml", "spotify_auth.xml")
        val res = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val rules = File(res, name).readText()
            signIns.forEach { assertTrue("$name leaves out $it", Regex("""exclude domain="sharedpref" path="${Regex.escape(it)}"""").findAll(rules).count() >= 1) }
        }
        // Phone to phone too, not only the cloud.
        val extraction = File(res, "data_extraction_rules.xml").readText()
        assertTrue(extraction.substringAfter("<device-transfer>").contains("discord.xml"))
        // The sign-in page's cookie jar (Google's session) stays out of every list: restored, Sign in let it back in.
        val webview = """exclude domain="root" path="app_webview/""""
        assertTrue(extraction.substringBefore("</cloud-backup>").contains(webview))
        assertTrue(extraction.substringAfter("<device-transfer>").contains(webview))
        assertTrue(File(res, "backup_rules.xml").readText().contains(webview))
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(manifest.contains("@xml/data_extraction_rules") && manifest.contains("@xml/backup_rules"))
    }

    @Test fun prismsOwnBackupsLeaveTheInstallMarkOut() {
        // Restoring one's own backup on a new phone keeps its accounts: the mark of the old install mustn't come along.
        assertNull(Backup.prefsPart(RestoredInstall.PREFS))
        assertNull(Backup.prefsPart("discord"))
    }
}

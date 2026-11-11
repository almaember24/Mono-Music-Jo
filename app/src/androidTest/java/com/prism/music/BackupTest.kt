package com.prism.music

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Backups move between Prism copies (#204, 1.7.9): one made by a Prism with another app id is still a Prism backup. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private fun zip(manifest: String?): Uri {
        val f = File(Fixture.context.cacheDir, "test-backup-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            manifest?.let { z.putNextEntry(ZipEntry("prism-backup.json")); z.write(it.toByteArray()); z.closeEntry() }
            z.putNextEntry(ZipEntry("files/library/prism.json")); z.write("{}".toByteArray()); z.closeEntry()
        }
        return Uri.fromFile(f)
    }

    @Test fun aBackupFromAnotherPrismCopyIsRecognised() = runBlocking {
        val parts = Fixture.container.backup.partsOf(zip("""{"format":2,"app":"com.netease.cloudmusic","parts":["LIBRARY"]}"""))
        assertEquals(setOf(com.prism.music.data.BackupPart.LIBRARY), parts)
    }

    @Test fun aZipThatIsntABackupIsnt() = runBlocking {
        assertNull(Fixture.container.backup.partsOf(zip(null)))
        assertNull(Fixture.container.backup.partsOf(zip("""{"format":2,"parts":["LIBRARY"]}""")))
    }
}

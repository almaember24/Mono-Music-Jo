package com.prism.music

import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookGallery
import com.prism.music.data.looks.LookStore
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The community gallery (themes/ in the repository, see its README): everything in index.json has to be usable, since
 * Prism quietly skips entries it can't use. Run it before adding a Look.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ThemeGalleryTest {
    private val kinds by lazy {
        SettingsRepository(org.robolectric.RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default)).lookKinds
    }
    private val folder = File("../themes")

    @Test
    fun everyLookInTheGalleryIsUsable() {
        val text = File(folder, "index.json").readText()
        val raw = Regex("\"id\"\\s*:").findAll(text).count()
        val looks = LookGallery.parse(text, kinds)
        assertEquals("Entries Prism would skip (bad id, repeated id, bad code or file name)", raw, looks.size)
        looks.forEach { g ->
            assertTrue("${g.entry.id}: name up to 40", g.entry.name.length <= 40)
            assertTrue("${g.entry.id}: note up to 80", g.entry.note.length <= 80)
            // Nothing in the code is dropped as not being a look setting.
            val code = LookCode.decode(g.entry.code, kinds)!!
            assertEquals(g.entry.id, code.values, LookCode.sanitized(code, kinds).values)
            assertTrue("${g.entry.id}: an empty Look", g.look.values.isNotEmpty())
            g.entry.file?.let { name ->
                val f = File(folder, name)
                assertTrue("${g.entry.id}: $name is missing", f.isFile)
                assertTrue("${g.entry.id}: $name is over 8 MB", f.length() <= LookStore.MAX_FILE)
                ZipFile(f).use { z -> assertTrue("${g.entry.id}: $name has no look.txt", z.getEntry("look.txt") != null) }
            }
        }
    }

    @Test
    fun badEntriesAreSkipped() {
        val good = LookGallery.parse(File(folder, "index.json").readText(), kinds).first().entry.code
        val text = """
            {"format": 1, "looks": [
              {"id": "ok", "name": "Fine", "by": "someone", "code": "$good"},
              {"id": "ok", "name": "Same id", "code": "$good"},
              {"id": "Bad Id!", "name": "Bad id", "code": "$good"},
              {"id": "nocode", "name": "No Look", "code": "PRISMLOOK1:nope"},
              {"id": "path", "name": "Bad file", "code": "$good", "file": "../../secret.prismlook"},
              {"id": "extra", "name": "Unknown fields are fine", "code": "$good", "stars": 5}
            ]}
        """.trimIndent()
        val looks = LookGallery.parse(text, kinds)
        assertEquals(listOf("ok", "extra"), looks.map { it.entry.id })
        assertEquals("by someone", looks.first().look.note)
        assertEquals("gallery-ok", looks.first().look.id)
    }
}

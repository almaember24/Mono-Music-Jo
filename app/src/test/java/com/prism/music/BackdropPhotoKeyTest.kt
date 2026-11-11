package com.prism.music

import com.prism.music.ui.theme.Backdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * #378: "your own photo shows only black". The value saved for a background was built from a translated string, so a
 * German phone saved "FOTO|…", which isn't a background Prism knows: Home fell back to Ambient (black with Pure black),
 * and Backgrounds ticked Ambient while its "Your photo" card showed the picture. 8 of the 31 languages had it.
 */
class BackdropPhotoKeyTest {
    @Test fun aPhotoSavedInAnyLanguageIsStillAPhoto() {
        for (word in listOf("PHOTO", "FOTO", "FOTÓ", "ZDJĘCIE", "ФОТО", "LARAWAN")) {
            val (style, path) = Backdrop.parse("$word|/data/user/0/com.prism.music/files/backdrops/all-1.jpg")
            assertEquals(word, Backdrop.PHOTO, style)
            assertEquals(word, "/data/user/0/com.prism.music/files/backdrops/all-1.jpg", path)
        }
    }

    @Test fun otherValuesReadAsBefore() {
        assertEquals(Backdrop.AURORA to null, Backdrop.parse("AURORA"))
        assertEquals(Backdrop.DEFAULT to null, Backdrop.parse(null))
        assertEquals(Backdrop.DEFAULT to null, Backdrop.parse("NOT_A_BACKGROUND"))
        // A Look's kept photo, before it's resolved to its file.
        assertEquals(Backdrop.PHOTO to "asset:abc", Backdrop.parse("PHOTO|asset:abc"))
        assertNull(Backdrop.parse("PHOTO").second)
    }

    @Test fun whatWasSavedIsPutRight() {
        assertEquals("PHOTO|/data/a.jpg", com.prism.music.data.looks.Backgrounds.photoKeyFixed("FOTO|/data/a.jpg"))
        assertEquals("PHOTO|/data/a.jpg", com.prism.music.data.looks.Backgrounds.photoKeyFixed("PHOTO|/data/a.jpg"))
        assertEquals("AURORA", com.prism.music.data.looks.Backgrounds.photoKeyFixed("AURORA"))
        assertEquals("PHOTO|asset:abc", com.prism.music.data.looks.Backgrounds.photoKeyFixed("PHOTO|asset:abc"))
        assertEquals("PHOTO|/x", Backdrop.photoValue("/x"))
    }

    /** What's saved never comes from a translation again: stored formats aren't translatable strings. */
    @Test fun whatsSavedIsntTranslated() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val base = File("src/main/res/values/strings.xml").readText()
        for (key in listOf("photo", "songs_albums", "mix_3", "sha_1")) {
            assertFalse(key, sources.any { it.readText().contains("R.string.$key,") || it.readText().contains("R.string.$key)") })
            assertFalse(key, base.contains("<string name=\"$key\">"))
        }
    }
}
